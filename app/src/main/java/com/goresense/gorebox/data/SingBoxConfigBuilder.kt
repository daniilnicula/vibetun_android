package com.goresense.gorebox.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Converts a GoreBox profile into the sing-box 1.9.x Android service configuration. */
object SingBoxConfigBuilder {
    private const val PROXY_TAG = "gorebox-proxy"
    private const val TUN_TAG = "gorebox-tun"
    private const val DNS_TAG = "gorebox-dns"

    fun build(
        profile: ProxyProfile,
        selectedPackages: Set<String> = emptySet(),
        selectedAppsOnly: Boolean = false,
    ): String {
        require(profile.source.isNotBlank()) { "У профиля нет исходной ссылки или конфигурации." }
        val packageList = selectedPackages.asSequence()
            .filter { it.isNotBlank() }
            .filterNot { it == "com.goresense.gorebox" }
            .distinct()
            .sorted()
            .toList()
        if (selectedAppsOnly) {
            require(packageList.isNotEmpty()) { "Для режима «Выбранные приложения» отметьте хотя бы одно приложение." }
        }

        val source = profile.source.trim()
        if (source.startsWith("{")) {
            val json = runCatching { JSONObject(source) }
                .getOrElse { throw IllegalArgumentException("Некорректный JSON-конфиг: ${it.message}") }
            if (json.has("outbounds")) {
                return normalizeFullConfig(json, packageList, selectedAppsOnly).toString()
            }
            val type = json.optString("type").ifBlank { json.optString("protocol") }
            require(type.isNotBlank()) { "В JSON не найден тип прокси или список outbounds." }
            val outbound = JSONObject(json.toString()).put("type", type).put("tag", PROXY_TAG)
            return createServiceConfig(outbound, packageList, selectedAppsOnly).toString()
        }
        require(!source.startsWith("[") || source.contains("[Interface]", ignoreCase = true)) {
            "JSON-массив не является конфигурацией sing-box. Нужен объект с outbounds или профиль-ссылка."
        }
        val outbound = createOutbound(profile.protocol, source)
        return createServiceConfig(outbound, packageList, selectedAppsOnly).toString()
    }

    private fun createServiceConfig(
        proxy: JSONObject,
        packages: List<String>,
        selectedAppsOnly: Boolean,
    ): JSONObject {
        proxy.put("tag", PROXY_TAG)
        val config = JSONObject()
        config.put("log", JSONObject().put("level", "warn"))
        config.put("inbounds", JSONArray().put(createTunInbound(packages, selectedAppsOnly)))
        config.put(
            "outbounds",
            JSONArray()
                .put(proxy)
                .put(JSONObject().put("type", "direct").put("tag", "gorebox-direct")),
        )
        config.put(
            "route",
            JSONObject()
                .put("auto_detect_interface", true)
                .put("final", PROXY_TAG),
        )
        config.put(
            "dns",
            JSONObject()
                .put(
                    "servers",
                    JSONArray().put(
                        JSONObject()
                            .put("tag", DNS_TAG)
                            .put("address", "https://1.1.1.1/dns-query")
                            .put("detour", PROXY_TAG),
                    ),
                )
                .put("final", DNS_TAG)
                .put("strategy", "prefer_ipv4"),
        )
        return config
    }

    private fun normalizeFullConfig(config: JSONObject, packages: List<String>, selectedAppsOnly: Boolean): JSONObject {
        val outbounds = config.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("В конфигурации отсутствует массив outbounds.")
        require(outbounds.length() > 0) { "В конфигурации нет исходящих подключений (outbounds)." }

        var tun: JSONObject? = null
        val inbounds = config.optJSONArray("inbounds") ?: JSONArray()
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(index) ?: continue
            if (inbound.optString("type") == "tun") {
                tun = inbound
                break
            }
        }
        if (tun == null) {
            tun = createTunInbound(packages, selectedAppsOnly)
            inbounds.put(tun)
        } else {
            tun.put("auto_route", true)
            tun.put("strict_route", true)
            if (!tun.has("inet4_address")) tun.put("inet4_address", JSONArray().put("172.19.0.1/30"))
            if (!tun.has("inet6_address")) tun.put("inet6_address", JSONArray().put("fd00:676f:7265::1/126"))
            if (selectedAppsOnly) tun.put("include_package", JSONArray(packages))
        }
        // Android's system stack may try to bind sockets to the virtual IPv6 address and fail
        // with EADDRNOTAVAIL. gVisor keeps the TCP/IP stack inside the process and avoids that.
        tun.put("stack", "gvisor")
        config.put("inbounds", inbounds)

        val route = config.optJSONObject("route") ?: JSONObject()
        if (!route.has("final") || route.optString("final").isBlank()) {
            val proxyTag = (0 until outbounds.length())
                .asSequence()
                .mapNotNull { outbounds.optJSONObject(it) }
                .firstOrNull { it.optString("type") !in setOf("direct", "block", "dns") }
                ?.optString("tag")
                .orEmpty()
            require(proxyTag.isNotBlank()) {
                "Нельзя выбрать маршрут: назначьте тег серверного outbound и route.final в JSON-конфиге."
            }
            route.put("final", proxyTag)
        }
        route.put("auto_detect_interface", true)
        config.put("route", route)
        return config
    }

    private fun createTunInbound(packages: List<String>, selectedAppsOnly: Boolean): JSONObject {
        val tun = JSONObject()
            .put("type", "tun")
            .put("tag", TUN_TAG)
            .put("inet4_address", JSONArray().put("172.19.0.1/30"))
            .put("inet6_address", JSONArray().put("fd00:676f:7265::1/126"))
            .put("mtu", 1500)
            .put("auto_route", true)
            .put("strict_route", true)
            .put("stack", "gvisor")
        if (selectedAppsOnly) tun.put("include_package", JSONArray(packages))
        return tun
    }

    private fun createOutbound(protocol: String, source: String): JSONObject {
        val scheme = source.substringBefore("://", "").lowercase()
        if (scheme == "vmess") return createVmess(source)
        if (scheme == "ss") return createShadowsocks(source)
        if (scheme == "wg" || scheme == "wireguard") return createWireGuardLink(source)
        if (scheme == "awg" || scheme == "amneziawg" || protocol.equals("amneziawg", ignoreCase = true)) {
            throw IllegalArgumentException("AmneziaWG не входит в Android-сборку sing-box 1.9.7; импорт профиля сохранён, но подключение невозможно.")
        }
        if (scheme == "ssr") {
            throw IllegalArgumentException("ShadowsocksR (SSR) не поддерживается используемым ядром sing-box.")
        }
        if (scheme == "tg" || protocol.equals("mtproto", ignoreCase = true)) {
            throw IllegalArgumentException("MTProto предназначен только для Telegram и не является TUN-прокси общего назначения.")
        }
        if (protocol.equals("anytls", ignoreCase = true) || scheme == "anytls") {
            throw IllegalArgumentException("AnyTLS отсутствует в Android-сборке sing-box 1.9.7.")
        }
        if (protocol.equals("wireguard", ignoreCase = true) && source.contains("[Interface]", ignoreCase = true) && source.contains("PrivateKey", ignoreCase = true)) {
            return createWireGuardConfig(source)
        }

        val uri = parseUri(source)
        val query = parseQuery(uri.rawQuery.orEmpty())
        val host = uri.host.orEmpty()
        require(host.isNotBlank()) { "В ссылке не найден адрес сервера." }
        val userInfo = uri.rawUserInfo?.let(::decodeComponent).orEmpty()
        val (username, password) = splitCredentialsDecoded(userInfo)
        val port = uri.port.takeIf { it in 1..65535 } ?: defaultPort(scheme, protocol)
        val outbound = JSONObject()
            .put("server", host)
            .put("server_port", port)

        when (protocol.lowercase()) {
            "vless" -> {
                outbound.put("type", "vless")
                outbound.put("uuid", username.ifBlank { userInfo })
                query["flow"]?.takeIf { it.isNotBlank() }?.let { outbound.put("flow", it) }
                val security = query["security"]?.lowercase()
                applyTls(outbound, query, host, force = security == "tls" || security == "reality")
                applyTransport(outbound, query)
            }
            "trojan" -> {
                outbound.put("type", "trojan")
                outbound.put("password", userInfo)
                applyTls(outbound, query, host, force = true)
                applyTransport(outbound, query)
            }
            "shadowsocks" -> throw IllegalArgumentException("Не удалось прочитать Shadowsocks-ссылку.")
            "socks" -> {
                outbound.put("type", "socks")
                val version = when (scheme) {
                    "socks4" -> "4"
                    "socks", "socks5" -> "5"
                    else -> query["version"] ?: "5"
                }
                outbound.put("version", version)
                if (username.isNotBlank()) outbound.put("username", username)
                if (password.isNotBlank()) outbound.put("password", password)
            }
            "http" -> {
                outbound.put("type", "http")
                if (username.isNotBlank()) outbound.put("username", username)
                if (password.isNotBlank()) outbound.put("password", password)
                if (scheme == "https" || query["tls"]?.toBooleanFlag() == true) {
                    applyTls(outbound, query, host, force = true)
                }
            }
            "hysteria", "hy" -> {
                outbound.put("type", "hysteria")
                val auth = query["auth"] ?: query["auth_str"] ?: userInfo
                if (auth.isNotBlank()) outbound.put("auth_str", auth)
                putInteger(outbound, "up_mbps", query["up_mbps"] ?: query["upmbps"] ?: query["up"])
                putInteger(outbound, "down_mbps", query["down_mbps"] ?: query["downmbps"] ?: query["down"])
                query["obfs"]?.takeIf { it.isNotBlank() }?.let { outbound.put("obfs", it) }
                query["ports"]?.takeIf { it.isNotBlank() }?.let { outbound.put("hop_ports", it) }
                query["hop_interval"]?.toDurationSeconds()?.let { outbound.put("hop_interval", it) }
                applyTls(outbound, query, host, force = true)
            }
            "hysteria2", "hy2" -> {
                outbound.put("type", "hysteria2")
                val secret = password.ifBlank { username.ifBlank { userInfo } }
                outbound.put("password", query["password"] ?: secret)
                putInteger(outbound, "up_mbps", query["up_mbps"] ?: query["up"])
                putInteger(outbound, "down_mbps", query["down_mbps"] ?: query["down"])
                val obfsType = query["obfs"]
                val obfsPassword = query["obfs-password"] ?: query["obfs_password"]
                if (!obfsType.isNullOrBlank() && !obfsPassword.isNullOrBlank()) {
                    outbound.put("obfs", JSONObject().put("type", obfsType).put("password", obfsPassword))
                }
                query["mport"]?.takeIf { it.isNotBlank() }?.let { ports ->
                    outbound.put("hop_ports", ports)
                    query["hop_interval"]?.toDurationSeconds()?.let { outbound.put("hop_interval", it) }
                }
                applyTls(outbound, query, host, force = true)
            }
            "tuic" -> {
                outbound.put("type", "tuic")
                outbound.put("uuid", query["uuid"] ?: username)
                outbound.put("password", query["password"] ?: password)
                query["congestion_control"]?.let { outbound.put("congestion_control", it) }
                query["udp_relay_mode"]?.let { outbound.put("udp_relay_mode", it) }
                query["zero_rtt_handshake"]?.toBooleanFlag()?.let { outbound.put("zero_rtt_handshake", it) }
                applyTls(outbound, query, host, force = true, defaultAlpn = "h3")
            }
            "ssh" -> {
                outbound.put("type", "ssh")
                outbound.put("user", query["user"] ?: username.ifBlank { "root" })
                if (password.isNotBlank()) outbound.put("password", password)
                query["private_key_path"]?.let { outbound.put("private_key_path", it) }
            }
            "wireguard", "wg" -> return createWireGuardLink(source)
            else -> throw IllegalArgumentException("Протокол ${ProtocolCatalog.label(protocol)} пока не подключён к Android-ядру.")
        }
        return outbound
    }

    private fun createVmess(source: String): JSONObject {
        val raw = source.substringAfter("vmess://", "").substringBefore('#').substringBefore('?')
        val decoded = decodeBase64(raw) ?: throw IllegalArgumentException("Некорректная VMess-ссылка: не удалось декодировать Base64.")
        val vmess = runCatching { JSONObject(decoded) }
            .getOrElse { throw IllegalArgumentException("Некорректные данные VMess: ${it.message}") }
        val host = vmess.optString("add", vmess.optString("server", ""))
        require(host.isNotBlank()) { "В VMess-ссылке не указан сервер." }
        val port = vmess.optString("port", "443").toIntOrNull() ?: 443
        val outbound = JSONObject()
            .put("type", "vmess")
            .put("tag", PROXY_TAG)
            .put("server", host)
            .put("server_port", port)
            .put("uuid", vmess.optString("id"))
            .put("security", vmess.optString("scy").ifBlank { "auto" })
        val alterId = vmess.optInt("aid", 0)
        if (alterId > 0) outbound.put("alter_id", alterId)
        val query = mutableMapOf<String, String>()
        vmess.optString("sni").takeIf { it.isNotBlank() }?.let { query["sni"] = it }
        vmess.optString("fp").takeIf { it.isNotBlank() }?.let { query["fp"] = it }
        if (vmess.optString("tls").isNotBlank()) applyTls(outbound, query, host, force = true)
        val transport = mutableMapOf("type" to vmess.optString("net", "tcp"))
        vmess.optString("path").takeIf { it.isNotBlank() }?.let { transport["path"] = it }
        vmess.optString("host").takeIf { it.isNotBlank() }?.let { transport["host"] = it }
        applyTransport(outbound, transport)
        return outbound
    }

    private fun createShadowsocks(source: String): JSONObject {
        val fragmentLess = source.substringAfter("ss://", "").substringBefore('#')
        val body = fragmentLess.substringBefore('?')
        val at = body.lastIndexOf('@')
        val decodedCredentials: String
        val authority: String
        if (at >= 0) {
            decodedCredentials = decodeBase64(body.substring(0, at)) ?: decodeComponent(body.substring(0, at))
            authority = body.substring(at + 1)
        } else {
            val decoded = decodeBase64(body) ?: throw IllegalArgumentException("Некорректная Shadowsocks-ссылка.")
            val decodedAt = decoded.lastIndexOf('@')
            require(decodedAt > 0) { "В Shadowsocks-ссылке отсутствует адрес сервера." }
            decodedCredentials = decoded.substring(0, decodedAt)
            authority = decoded.substring(decodedAt + 1)
        }
        val (method, password) = splitCredentials(decodedCredentials)
        require(method.isNotBlank() && password.isNotBlank()) { "В Shadowsocks-ссылке отсутствуют метод шифрования или пароль." }
        val endpoint = parseAuthority(authority)
        val outbound = JSONObject()
            .put("type", "shadowsocks")
            .put("tag", PROXY_TAG)
            .put("server", endpoint.first)
            .put("server_port", endpoint.second)
            .put("method", method)
            .put("password", password)
        val query = parseQuery(source.substringAfter('?', "").substringBefore('#'))
        query["plugin"]?.let { outbound.put("plugin", it) }
        query["plugin_opts"]?.let { outbound.put("plugin_opts", it) }
        return outbound
    }

    private fun createWireGuardLink(source: String): JSONObject {
        val uri = parseUri(source)
        val query = parseQuery(uri.rawQuery.orEmpty())
        val privateKey = query.firstOf("privateKey", "private_key", "privkey") ?: uri.rawUserInfo?.let(::decodeComponent).orEmpty()
        val peerKey = query.firstOf("publicKey", "public_key", "peerPublicKey", "peer_public_key")
            ?: throw IllegalArgumentException("В WireGuard-ссылке отсутствует public key.")
        val address = query.firstOf("address", "local_address", "ip")
            ?: throw IllegalArgumentException("В WireGuard-ссылке отсутствует локальный address.")
        val allowed = query.firstOf("allowedIPs", "allowed_ips") ?: "0.0.0.0/0,::/0"
        val peer = JSONObject()
            .put("server", uri.host.orEmpty())
            .put("server_port", uri.port.takeIf { it > 0 } ?: 51820)
            .put("public_key", peerKey)
            .put("allowed_ips", splitList(allowed))
        query.firstOf("preSharedKey", "pre_shared_key")?.let { peer.put("pre_shared_key", it) }
        return JSONObject()
            .put("type", "wireguard")
            .put("tag", PROXY_TAG)
            .put("server", uri.host.orEmpty())
            .put("server_port", uri.port.takeIf { it > 0 } ?: 51820)
            .put("local_address", splitList(address))
            .put("private_key", privateKey)
            .put("peers", JSONArray().put(peer))
            .also { query["mtu"]?.toIntOrNull()?.let { mtu -> it.put("mtu", mtu) } }
    }

    private fun createWireGuardConfig(source: String): JSONObject {
        val sections = parseIni(source)
        val iface = sections["interface"]?.firstOrNull().orEmpty()
        val peers = sections["peer"].orEmpty()
        val privateKey = iface["privatekey"].orEmpty()
        val addresses = iface["address"].orEmpty()
        require(privateKey.isNotBlank() && addresses.isNotBlank()) {
            "В WireGuard-конфигурации нужны PrivateKey и Address в секции [Interface]."
        }
        require(peers.isNotEmpty()) { "В WireGuard-конфигурации нет секции [Peer]." }
        val peerArray = JSONArray()
        var defaultServer: Pair<String, Int>? = null
        peers.forEach { peer ->
            val endpoint = parseAuthority(peer["endpoint"].orEmpty())
            require(endpoint.first.isNotBlank()) { "В секции [Peer] не указан Endpoint." }
            if (defaultServer == null) defaultServer = endpoint
            val item = JSONObject()
                .put("server", endpoint.first)
                .put("server_port", endpoint.second)
                .put("public_key", peer["publickey"].orEmpty())
                .put("allowed_ips", splitList(peer["allowedips"] ?: "0.0.0.0/0,::/0"))
            peer["presharedkey"]?.takeIf { it.isNotBlank() }?.let { item.put("pre_shared_key", it) }
            peerArray.put(item)
        }
        val server = checkNotNull(defaultServer)
        val outbound = JSONObject()
            .put("type", "wireguard")
            .put("tag", PROXY_TAG)
            .put("server", server.first)
            .put("server_port", server.second)
            .put("local_address", splitList(addresses))
            .put("private_key", privateKey)
            .put("peers", peerArray)
        iface["mtu"]?.toIntOrNull()?.let { outbound.put("mtu", it) }
        return outbound
    }

    private fun applyTls(
        outbound: JSONObject,
        query: Map<String, String>,
        server: String,
        force: Boolean,
        defaultAlpn: String? = null,
    ) {
        val security = query["security"]?.lowercase().orEmpty()
        val realityEnabled = security == "reality" || query["pbk"] != null || query["publickey"] != null
        val tlsEnabled = force || realityEnabled || security == "tls" || query["tls"]?.toBooleanFlag() == true
        if (!tlsEnabled) return
        val tls = JSONObject().put("enabled", true)
        val serverName = query.firstOf("sni", "server_name", "peer")?.takeIf { it.isNotBlank() } ?: server
        tls.put("server_name", serverName)
        if ((query["insecure"] ?: query["allowinsecure"])?.toBooleanFlag() == true) tls.put("insecure", true)
        val alpn = splitList(query["alpn"].orEmpty())
        if (alpn.length() > 0) tls.put("alpn", alpn)
        val fingerprint = query.firstOf("fp", "fingerprint")
        if (!fingerprint.isNullOrBlank()) {
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
        }
        if (realityEnabled) {
            val reality = JSONObject().put("enabled", true)
            query.firstOf("pbk", "publickey", "public_key")?.takeIf { it.isNotBlank() }?.let { reality.put("public_key", it) }
            query.firstOf("sid", "shortid", "short_id")?.takeIf { it.isNotBlank() }?.let { reality.put("short_id", it) }
            tls.put("reality", reality)
            if (!tls.has("utls")) tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", "chrome"))
        }
        if (alpn.length() == 0 && defaultAlpn != null) tls.put("alpn", JSONArray().put(defaultAlpn))
        outbound.put("tls", tls)
    }

    private fun applyTransport(outbound: JSONObject, values: Map<String, String>) {
        val type = values.firstOf("type", "network", "net")?.lowercase().orEmpty()
        if (type.isBlank() || type == "tcp" || type == "raw") return
        val path = values["path"]?.takeIf { it.isNotBlank() } ?: "/"
        val host = values.firstOf("host", "host_header")
        val transport = when (type) {
            "ws", "websocket" -> JSONObject()
                .put("type", "ws")
                .put("path", path)
                .apply {
                    if (!host.isNullOrBlank()) put("headers", JSONObject().put("Host", host))
                    values["ed"]?.toIntOrNull()?.let { put("max_early_data", it) }
                    values["eh"]?.takeIf { it.isNotBlank() }?.let { put("early_data_header_name", it) }
                }
            "grpc" -> JSONObject()
                .put("type", "grpc")
                .put("service_name", values.firstOf("serviceName", "service_name", "path") ?: "")
            "h2", "http" -> JSONObject()
                .put("type", "http")
                .put("path", path)
                .apply { if (!host.isNullOrBlank()) put("host", splitList(host)) }
            "httpupgrade", "http-upgrade" -> JSONObject()
                .put("type", "httpupgrade")
                .put("path", path)
                .apply { if (!host.isNullOrBlank()) put("host", host) }
            "quic" -> JSONObject().put("type", "quic")
            else -> throw IllegalArgumentException("Транспорт $type не поддерживается sing-box 1.9.7.")
        }
        outbound.put("transport", transport)
    }

    private fun parseUri(source: String): URI = runCatching {
        URI(source.replace(" ", "%20"))
    }.getOrElse { throw IllegalArgumentException("Некорректная ссылка профиля: ${it.message}") }

    private fun parseAuthority(value: String): Pair<String, Int> {
        val uri = runCatching { URI("udp://$value") }.getOrNull()
        val host = uri?.host.orEmpty()
        val port = uri?.port?.takeIf { it in 1..65535 } ?: 0
        require(host.isNotBlank() && port > 0) { "Некорректный адрес сервера: $value" }
        return host to port
    }

    private fun parseQuery(raw: String): Map<String, String> = raw.split('&')
        .mapNotNull { item ->
            if (item.isBlank()) return@mapNotNull null
            val key = decodeComponent(item.substringBefore('='))
            val value = decodeComponent(item.substringAfter('=', ""))
            key.lowercase() to value
        }.toMap()

    private fun decodeComponent(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private fun decodeBase64(value: String): String? {
        val cleaned = value.trim().replace('-', '+').replace('_', '/')
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8) }
            .recoverCatching { String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8) }
            .getOrNull()
    }

    private fun parseIni(raw: String): Map<String, List<Map<String, String>>> {
        val sections = mutableMapOf<String, MutableList<MutableMap<String, String>>>()
        var currentName = ""
        var current: MutableMap<String, String>? = null
        raw.lineSequence().map { it.substringBefore('#').substringBefore(';').trim() }
            .filter { it.isNotBlank() }
            .forEach { line ->
                if (line.startsWith('[') && line.endsWith(']')) {
                    currentName = line.substring(1, line.length - 1).trim().lowercase()
                    current = mutableMapOf()
                    sections.getOrPut(currentName) { mutableListOf() }.add(checkNotNull(current))
                } else {
                    val target = current ?: return@forEach
                    val split = line.indexOf('=')
                    if (split > 0) target[line.substring(0, split).trim().lowercase()] = line.substring(split + 1).trim()
                }
            }
        return sections.mapValues { (_, items) -> items.map { it.toMap() } }
    }

    private fun splitCredentials(value: String): Pair<String, String> = splitCredentialsDecoded(decodeComponent(value))

    private fun splitCredentialsDecoded(decoded: String): Pair<String, String> {
        val separator = decoded.indexOf(':')
        return if (separator < 0) decoded to "" else decoded.substring(0, separator) to decoded.substring(separator + 1)
    }

    private fun splitList(value: String): JSONArray = JSONArray().apply {
        value.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { item -> put(item) }
    }

    private fun defaultPort(scheme: String, protocol: String): Int = when (scheme) {
        "http" -> 80
        "https" -> 443
        "socks", "socks5" -> 1080
        "socks4" -> 1080
        "hysteria", "hy", "hysteria2", "hy2" -> 443
        "tuic" -> 443
        "wg", "wireguard" -> 51820
        else -> if (protocol.equals("http", true)) 8080 else 443
    }

    private fun Map<String, String>.firstOf(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> get(key.lowercase())?.takeIf { it.isNotBlank() } }

    private fun String.toBooleanFlag(): Boolean = lowercase() in setOf("1", "true", "yes", "on")

    private fun String.toDurationSeconds(): Int? {
        val normalized = trim().lowercase()
        val number = Regex("^([0-9]+)").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        return when {
            normalized.endsWith("ms") -> (number / 1000).coerceAtLeast(1)
            normalized.endsWith('m') -> number * 60
            normalized.endsWith('h') -> number * 3600
            else -> number
        }
    }

    private fun putInteger(target: JSONObject, key: String, value: String?) {
        value?.toIntOrNull()?.let { target.put(key, it) }
    }
}

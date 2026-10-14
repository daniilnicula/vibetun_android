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
    private const val DIRECT_TAG = "gorebox-direct"
    private const val DNS_DIRECT_TAG = "gorebox-dns-direct"
    private const val BLOCK_TAG = "gorebox-block"
    /** Outbound типа dns: сюда уходят DNS-запросы из TUN (перехват для sing-box < 1.11). */
    private const val DNS_OUT_TAG = "gorebox-dns-out"
    private val SECRET_KEYS = setOf("password", "uuid", "private_key", "pre_shared_key", "auth_str", "auth")

    /** QUIC (UDP/443) блокируем: у нашего ядра (форк NekoBox) QUIC-relay через
     *  туннель на части серверов мёртв — без блокировки браузеры не откатываются
     *  на TCP и ничего не грузят. TCP-fallback работает предсказуемо. */
    private fun quicBlockRule(): JSONObject =
        JSONObject().put("network", "udp").put("port", 443).put("outbound", BLOCK_TAG)

    private fun blockOutbound(): JSONObject =
        JSONObject().put("type", "block").put("tag", BLOCK_TAG)

    fun build(
        profile: ProxyProfile,
        selectedPackages: Set<String> = emptySet(),
        selectedAppsOnly: Boolean = false,
        directPackages: Set<String> = emptySet(),
        routeRules: List<RouteRule> = emptyList(),
    ): String {
        require(profile.source.isNotBlank()) { "У профиля нет исходной ссылки или конфигурации." }
        val packageList = selectedPackages.asSequence()
            .filter { it.isNotBlank() }
            .filterNot { it == "com.goresense.gorebox" }
            .filterNot { it in directPackages }
            .distinct()
            .sorted()
            .toList()
        val directList = directPackages.asSequence()
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
                return normalizeFullConfig(json, packageList, selectedAppsOnly, directList, routeRules).toString()
            }
            val type = json.optString("type").ifBlank { json.optString("protocol") }
            require(type.isNotBlank()) { "В JSON не найден тип прокси или список outbounds." }
            val outbound = JSONObject(json.toString()).put("type", type).put("tag", PROXY_TAG)
            return createServiceConfig(outbound, packageList, selectedAppsOnly, directList, routeRules).toString()
        }
        require(!source.startsWith("[") || source.contains("[Interface]", ignoreCase = true)) {
            "JSON-массив не является конфигурацией sing-box. Нужен объект с outbounds или профиль-ссылка."
        }
        val outbound = createOutbound(profile.protocol, source)
        return createServiceConfig(outbound, packageList, selectedAppsOnly, directList, routeRules).toString()
    }

    /**
     * Минимальный конфиг для замера задержки профиля: локальный mixed-inbound (HTTP/SOCKS
     * на 127.0.0.1) + исходящий outbound профиля. Замер = время HTTP-204 через этот прокси,
     * так честно измеряются и UDP-протоколы (hysteria2/tuic), на которые TCP-ping не отвечает.
     * null — протокол не поддерживается ядром sing-box (amneziawg) или некорректный источник.
     */
    fun buildProbeConfig(profile: ProxyProfile, port: Int): String? = runCatching {
        val source = profile.source.trim()
        val outbound = if (source.startsWith("{")) {
            val json = JSONObject(source)
            val outbounds = json.optJSONArray("outbounds") ?: return null
            (0 until outbounds.length())
                .asSequence()
                .mapNotNull { outbounds.optJSONObject(it) }
                .firstOrNull { it.optString("type") !in setOf("direct", "block", "dns") }
                ?: return null
        } else {
            val protocol = profile.protocol.trim()
            if (protocol.equals("amneziawg", ignoreCase = true)) return null
            createOutbound(protocol, source)
        }
        outbound.put("tag", PROXY_TAG)
        JSONObject()
            .put("log", JSONObject().put("level", "panic"))
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "mixed")
                        .put("tag", "gorebox-probe")
                        .put("listen", "127.0.0.1")
                        .put("listen_port", port),
                ),
            )
            .put("outbounds", JSONArray().put(outbound))
            .put("route", JSONObject().put("auto_detect_interface", true).put("final", PROXY_TAG))
            .toString()
    }.getOrNull()

    private fun createServiceConfig(
        proxy: JSONObject,
        packages: List<String>,
        selectedAppsOnly: Boolean,
        directPackages: List<String>,
        routeRules: List<RouteRule>,
    ): JSONObject {
        proxy.put("tag", PROXY_TAG)
        val config = JSONObject()
        // debug в файле ядра: нужен для диагностики обрывов (DNS, закрытия соединений).
        // В AppLog/logcat debug-строки не попадают (фильтр в writeLog).
        config.put("log", JSONObject().put("level", "debug").put("output", "logs/core.log").put("timestamp", true))
        config.put("inbounds", JSONArray().put(createTunInbound(packages, selectedAppsOnly, directPackages)))
        config.put(
            "outbounds",
            JSONArray()
                .put(proxy)
                .put(JSONObject().put("type", "direct").put("tag", DIRECT_TAG))
                .put(blockOutbound())
                .put(dnsOutbound()),
        )
        config.put(
            "route",
            JSONObject()
                .put("auto_detect_interface", true)
                .put("rules", createRouteRules(routeRules))
                .put("final", PROXY_TAG),
        )
        config.put(
            "dns",
            JSONObject()
                .put("servers", createDnsServers())
                .put("rules", createDnsRules(routeRules, listOfNotNull(proxy.optString("server"))))
                .put("final", DNS_TAG)
                // IPv4-only: серверы в основном IPv4, AAAA-ответы заворачивают приложения
                // на IPv6-адреса, которые через туннель не достижимы (YouTube вис).
                .put("strategy", "ipv4_only"),
        )
        return config
    }

    private fun normalizeFullConfig(
        config: JSONObject,
        packages: List<String>,
        selectedAppsOnly: Boolean,
        directPackages: List<String>,
        routeRules: List<RouteRule>,
    ): JSONObject {
        val outbounds = config.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("В конфигурации отсутствует массив outbounds.")
        require(outbounds.length() > 0) { "В конфигурации нет исходящих подключений (outbounds)." }
        val hasBlockOutbound = (0 until outbounds.length())
            .asSequence()
            .mapNotNull { outbounds.optJSONObject(it) }
            .any { it.optString("type") == "block" }
        if (!hasBlockOutbound) outbounds.put(blockOutbound())

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
            tun = createTunInbound(packages, selectedAppsOnly, directPackages)
            inbounds.put(tun)
        } else {
            tun.put("auto_route", true)
            tun.put("strict_route", true)
            if (!tun.has("inet4_address")) tun.put("inet4_address", JSONArray().put("172.19.0.1/30"))
            // IPv4-only: inet6_address не добавляем — AAAA-трафик через IPv4-only серверы виснет
            if (selectedAppsOnly) tun.put("include_package", JSONArray(packages))
            if (directPackages.isNotEmpty()) tun.put("exclude_package", JSONArray(directPackages))
        }
        // Android's system stack may try to bind sockets to the virtual IPv6 address and fail
        // with EADDRNOTAVAIL. gVisor keeps the TCP/IP stack inside the process and avoids that.
        tun.put("stack", "gvisor")
        tun.put("sniff", true)
        config.put("inbounds", inbounds)
        if (!config.has("log")) {
            config.put("log", JSONObject().put("level", "info").put("output", "logs/core.log").put("timestamp", true))
        }

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
        val userRules = createRouteRules(routeRules)
        val existingRules = route.optJSONArray("rules") ?: JSONArray()
        val mergedRules = JSONArray()
        for (index in 0 until userRules.length()) mergedRules.put(userRules.getJSONObject(index))
        for (index in 0 until existingRules.length()) mergedRules.put(existingRules.getJSONObject(index))
        if (mergedRules.length() > 0) route.put("rules", mergedRules)
        config.put("route", route)

        // Служебные outbound'ы, на которые ссылаются наши правила DNS и маршрутизации.
        val hasTag = { tag: String ->
            (0 until outbounds.length()).any { outbounds.optJSONObject(it)?.optString("tag") == tag }
        }
        if (!hasTag(DIRECT_TAG)) outbounds.put(JSONObject().put("type", "direct").put("tag", DIRECT_TAG))
        if (!hasTag(DNS_OUT_TAG)) outbounds.put(dnsOutbound())
        normalizeFullDns(config, outbounds)
        return config
    }

    /**
     * DNS для полного JSON: перехват через dns-outbound уже есть в правилах маршрутизации;
     * здесь добавляем прямой DNS-сервер и bootstrap-правила для доменов серверов профилей.
     */
    private fun normalizeFullDns(config: JSONObject, outbounds: JSONArray) {
        val serverHosts = (0 until outbounds.length())
            .mapNotNull { outbounds.optJSONObject(it) }
            .filterNot { it.optString("type") in setOf("direct", "block", "dns") }
            .map { it.optString("server") }
            .filter { it.isNotBlank() }
        val dns = config.optJSONObject("dns") ?: JSONObject()
            .put("servers", createDnsServers())
            .put("final", DNS_TAG)
            .put("strategy", "ipv4_only")
        val servers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        val hasDirectServer = (0 until servers.length()).any { servers.optJSONObject(it)?.optString("tag") == DNS_DIRECT_TAG }
        if (!hasDirectServer) servers.put(directDnsServer())
        val rules = dns.optJSONArray("rules") ?: JSONArray()
        val merged = JSONArray()
        bootstrapDnsRule(serverHosts)?.let { merged.put(it) }
        for (index in 0 until rules.length()) merged.put(rules.getJSONObject(index))
        dns.put("rules", merged)
        config.put("dns", dns)
    }

    /** Правила маршрутизации: QUIC блокируем первым правилом, DIRECT-значения
     *  уходят в gorebox-direct, остальное решает final. */
    private fun createRouteRules(rules: List<RouteRule>): JSONArray {
        // DNS перехватываем первым правилом: иначе запросы уходят к final как обычный UDP
        // на 172.19.0.2:53 и не получают ответа (sing-box 1.9 не перехватывает DNS сам).
        val merged = JSONArray()
            .put(JSONObject().put("protocol", "dns").put("outbound", DNS_OUT_TAG))
            .put(JSONObject().put("port", 53).put("outbound", DNS_OUT_TAG))
            .put(quicBlockRule())
        val direct = rules.filter { it.action == RouteAction.DIRECT }
        fun valuesOf(type: RouteRuleType): JSONArray = JSONArray().apply {
            direct.filter { it.type == type }.forEach { rule ->
                put(
                    if (type == RouteRuleType.KEYWORD) RouteRule.keywordRegex(rule.value)
                    else rule.value.trim().removePrefix("*."),
                )
            }
        }
        val result = JSONArray()
        val suffix = valuesOf(RouteRuleType.DOMAIN_SUFFIX)
        val keyword = valuesOf(RouteRuleType.KEYWORD)
        val cidr = valuesOf(RouteRuleType.IP_CIDR)
        if (suffix.length() > 0) result.put(JSONObject().put("domain_suffix", suffix).put("outbound", DIRECT_TAG))
        if (keyword.length() > 0) result.put(JSONObject().put("domain_regex", keyword).put("outbound", DIRECT_TAG))
        if (cidr.length() > 0) result.put(JSONObject().put("ip_cidr", cidr).put("outbound", DIRECT_TAG))
        for (index in 0 until result.length()) merged.put(result.getJSONObject(index))
        return merged
    }

    private fun dnsOutbound(): JSONObject = JSONObject().put("type", "dns").put("tag", DNS_OUT_TAG)

    private fun directDnsServer(): JSONObject = JSONObject()
        .put("tag", DNS_DIRECT_TAG)
        .put("address", "local")
        .put("detour", DIRECT_TAG)

    /** Всегда есть прямой DNS: без него доменный адрес сервера резолвится через ещё не поднятый прокси. */
    private fun createDnsServers(): JSONArray = JSONArray().apply {
        put(
            JSONObject()
                .put("tag", DNS_TAG)
                .put("address", "https://1.1.1.1/dns-query")
                .put("detour", PROXY_TAG),
        )
        put(directDnsServer())
    }

    /** Bootstrap: доменные адреса серверов профилей резолвим напрямую, а не через прокси. */
    private fun bootstrapDnsRule(hosts: List<String>): JSONObject? {
        val domains = hosts.map { it.trim() }
            .filter { it.isNotEmpty() && !isIpLiteral(it) }
            .distinct()
        if (domains.isEmpty()) return null
        return JSONObject().put("domain", JSONArray(domains)).put("server", DNS_DIRECT_TAG)
    }

    private fun isIpLiteral(host: String): Boolean =
        host.contains(':') || Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$").matches(host)

    private fun createDnsRules(rules: List<RouteRule>, bootstrapHosts: List<String>): JSONArray {
        val result = JSONArray()
        bootstrapDnsRule(bootstrapHosts)?.let { result.put(it) }
        val direct = rules.filter { it.action == RouteAction.DIRECT && it.type != RouteRuleType.IP_CIDR }
        if (direct.isEmpty()) return result
        fun valuesOf(type: RouteRuleType): JSONArray = JSONArray().apply {
            direct.filter { it.type == type }.forEach { rule ->
                put(
                    if (type == RouteRuleType.KEYWORD) RouteRule.keywordRegex(rule.value)
                    else rule.value.trim().removePrefix("*."),
                )
            }
        }
        val rule = JSONObject().put("server", DNS_DIRECT_TAG)
        val suffix = valuesOf(RouteRuleType.DOMAIN_SUFFIX)
        val keyword = valuesOf(RouteRuleType.KEYWORD)
        if (suffix.length() > 0) rule.put("domain_suffix", suffix)
        if (keyword.length() > 0) rule.put("domain_regex", keyword)
        return result.put(rule)
    }

    private fun createTunInbound(packages: List<String>, selectedAppsOnly: Boolean, directPackages: List<String>): JSONObject {
        val tun = JSONObject()
            .put("type", "tun")
            .put("tag", TUN_TAG)
            .put("inet4_address", JSONArray().put("172.19.0.1/30"))
            // gVisor + MTU 9000 (как в Hiddify): QUIC-транспорты (hysteria2/tuic) сами
            // режут датаграммы по своему PMTU; маленький MTU тут давал фрагментацию
            // внешнего UDP и зависание крупных передач (видео YouTube — не грузилось,
            // превью и TCP-сервисы — работали).
            .put("mtu", 9000)
            .put("auto_route", true)
            .put("strict_route", true)
            .put("stack", "gvisor")
            .put("sniff", true)
        if (selectedAppsOnly) tun.put("include_package", JSONArray(packages))
        if (directPackages.isNotEmpty()) tun.put("exclude_package", JSONArray(directPackages))
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
                // Hysteria v1 без полос пропускания ядро не запускает — проверяем заранее.
                require(outbound.has("up_mbps") && outbound.has("down_mbps")) {
                    "Hysteria v1: в ссылке нужны параметры up и down (Мбит/с)."
                }
                query["obfs"]?.takeIf { it.isNotBlank() }?.let { outbound.put("obfs", it) }
                query["ports"]?.takeIf { it.isNotBlank() }?.let { outbound.put("hop_ports", it) }
                query["hop_interval"]?.toDurationSeconds()?.let { outbound.put("hop_interval", it) }
                applyTls(outbound, query, host, force = true, allowUtls = false)
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
                applyTls(outbound, query, host, force = true, allowUtls = false)
            }
            "tuic" -> {
                outbound.put("type", "tuic")
                outbound.put("uuid", query["uuid"] ?: username)
                outbound.put("password", query["password"] ?: password)
                query["congestion_control"]?.let { outbound.put("congestion_control", it) }
                query["udp_relay_mode"]?.let { outbound.put("udp_relay_mode", it) }
                query["zero_rtt_handshake"]?.toBooleanFlag()?.let { outbound.put("zero_rtt_handshake", it) }
                applyTls(outbound, query, host, force = true, defaultAlpn = "h3", allowUtls = false)
                // TUIC v5 работает поверх HTTP/3 — корректный ALPN только "h3".
                // Ссылки из старых генераторов несут набор "h3,spdy/3.1": строгие
                // серверы отклоняют такое согласование, а спек-совместимого смысла в нём нет.
                outbound.optJSONObject("tls")?.put("alpn", JSONArray().put("h3"))
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
        // Плагины SIP003 (obfs-local, v2ray-plugin) в Android-ядре не исполняются: молча
        // потерять плагин нельзя (туннель не поднимется без обфускации), поэтому явная ошибка.
        require(query["plugin"].isNullOrBlank()) {
            "Shadowsocks-плагин «${query["plugin"]}» не поддерживается Android-сборкой. Используйте профиль без плагина."
        }
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
        allowUtls: Boolean = true,
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
        if (allowUtls && !fingerprint.isNullOrBlank()) {
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

    /**
     * Конфиг для лога: секреты (пароли, UUID, приватные ключи, reality public_key) заменяются на ***.
     * Возвращает исходную строку, если JSON не разбирается.
     */
    fun redactForLog(configJson: String): String = runCatching {
        redactValue(JSONObject(configJson), parentKey = "").toString()
    }.getOrDefault("<не удалось разобрать конфиг>")

    private fun redactValue(value: Any?, parentKey: String): Any? = when (value) {
        is JSONObject -> {
            val copy = JSONObject()
            value.keys().forEach { key ->
                val child = value.get(key)
                val masked = when {
                    key.lowercase() in SECRET_KEYS -> "***"
                    key == "public_key" && parentKey == "reality" -> "***"
                    else -> redactValue(child, key)
                }
                copy.put(key, masked)
            }
            copy
        }
        is JSONArray -> JSONArray().also { array ->
            for (index in 0 until value.length()) array.put(redactValue(value.get(index), parentKey))
        }
        else -> value
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

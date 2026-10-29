package com.vibetun.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Converts a VibeTun profile into the sing-box 1.14.4 Android service configuration. */
object SingBoxConfigBuilder {
    const val DEFAULT_TUN_MTU = 9000
    const val MIN_TUN_MTU = 576
    const val MAX_TUN_MTU = 9000
    private const val PROXY_TAG = "vibetun-proxy"
    private const val TUN_TAG = "vibetun-tun"
    private const val DNS_TAG = "vibetun-dns"
    private const val DIRECT_TAG = "vibetun-direct"
    private const val DNS_DIRECT_TAG = "vibetun-dns-direct"
    /** Резолвер домена DoH-сервера: IP-литерал, поэтому сам резолвер не нужен. */
    private const val DNS_DIRECT_RESOLVER_TAG = "vibetun-dns-direct-resolver"
    private val SECRET_KEYS = setOf("password", "uuid", "private_key", "pre_shared_key", "auth_str", "auth")

    /** QUIC (UDP/443) блокируем: на части серверов QUIC-relay через туннель мёртв,
     *  а TCP-fallback работает предсказуемо. В sing-box 1.14 блокировка — rule action. */
    private fun quicBlockRule(): JSONObject =
        JSONObject().put("network", "udp").put("port", 443).put("action", "reject")

    fun build(
        profile: ProxyProfile,
        selectedPackages: Set<String> = emptySet(),
        selectedAppsOnly: Boolean = false,
        directPackages: Set<String> = emptySet(),
        routeRules: List<RouteRule> = emptyList(),
        tunMtu: Int = DEFAULT_TUN_MTU,
    ): String {
        require(profile.source.isNotBlank()) { "У профиля нет исходной ссылки или конфигурации." }
        require(tunMtu in MIN_TUN_MTU..MAX_TUN_MTU) { "MTU туннеля должен быть в диапазоне $MIN_TUN_MTU–$MAX_TUN_MTU." }
        val packageList = selectedPackages.asSequence()
            .filter { it.isNotBlank() }
            .filterNot { it == "com.vibetun.app" }
            .filterNot { it in directPackages }
            .distinct()
            .sorted()
            .toList()
        val directList = directPackages.asSequence()
            .filter { it.isNotBlank() }
            .filterNot { it == "com.vibetun.app" }
            .distinct()
            .sorted()
            .toList()
        if (selectedAppsOnly) {
            require(packageList.isNotEmpty()) { "Для режима «Выбранные приложения» отметьте хотя бы одно приложение." }
        }
        // Снятые в интерфейсе галочки не попадают ни в route, ни в DNS-правила.
        val activeRules = routeRules.filter { it.enabled }

        val source = profile.source.trim()
        if (source.startsWith("{")) {
            val json = runCatching { JSONObject(source) }
                .getOrElse { throw IllegalArgumentException("Некорректный JSON-конфиг: ${it.message}") }
            if (json.has("outbounds") || json.has("endpoints")) {
                return normalizeFullConfig(json, packageList, selectedAppsOnly, directList, activeRules, tunMtu).toString()
            }
            val type = json.optString("type").ifBlank { json.optString("protocol") }
            require(type.isNotBlank()) { "В JSON не найден тип прокси или список outbounds/endpoints." }
            val outbound = JSONObject(json.toString()).put("type", type).put("tag", PROXY_TAG)
            return createServiceConfig(outbound, packageList, selectedAppsOnly, directList, activeRules, tunMtu).toString()
        }
        require(!source.startsWith("[") || source.contains("[Interface]", ignoreCase = true)) {
            "JSON-массив не является конфигурацией sing-box. Нужен объект с outbounds/endpoints или профиль-ссылка."
        }
        val outbound = createOutbound(profile.protocol, source)
        return createServiceConfig(outbound, packageList, selectedAppsOnly, directList, activeRules, tunMtu).toString()
    }

    /**
     * Минимальный конфиг для замера задержки профиля: локальный mixed-inbound (HTTP/SOCKS
     * на 127.0.0.1) + исходящий outbound/endpoint профиля. Замер = время HTTP-204 через прокси.
     */
    fun buildProbeConfig(profile: ProxyProfile, port: Int): String? = runCatching {
        val source = profile.source.trim()
        val proxy = if (source.startsWith("{")) {
            val json = JSONObject(source)
            if (json.has("outbounds") || json.has("endpoints")) {
                firstProxy(normalizeLegacyConfig(json)) ?: return null
            } else {
                val type = json.optString("type").ifBlank { json.optString("protocol") }
                if (type.isBlank()) return null
                json.put("type", type)
            }
        } else {
            val protocol = profile.protocol.trim()
            if (protocol.equals("amneziawg", ignoreCase = true)) return null
            createOutbound(protocol, source)
        }
        proxy.put("tag", PROXY_TAG)
        val config = JSONObject()
            .put("log", JSONObject().put("level", "panic"))
            // Не пересекаемся с cache.db основного VPN: его bbolt держит эксклюзивную блокировку.
            .put(
                "experimental",
                JSONObject().put(
                    "cache_file",
                    JSONObject().put("enabled", true).put("path", "probe-cache.db"),
                ),
            )
            .put(
                "inbounds",
                JSONArray().put(
                    JSONObject()
                        .put("type", "mixed")
                        .put("tag", "vibetun-probe")
                        .put("listen", "127.0.0.1")
                        .put("listen_port", port),
                ),
            )
            .put(
                "route",
                JSONObject()
                    .put("auto_detect_interface", true)
                    .put("default_domain_resolver", DNS_DIRECT_TAG)
                    .put("final", PROXY_TAG),
            )
            .put("dns", JSONObject().put("servers", createDnsServers()).put("final", DNS_TAG))
        putProxy(config, proxy)
        (config.optJSONArray("outbounds") ?: JSONArray().also { config.put("outbounds", it) })
            .put(JSONObject().put("type", "direct").put("tag", DIRECT_TAG))
        config.toString()
    }.getOrNull()

    private fun createServiceConfig(
        proxy: JSONObject,
        packages: List<String>,
        selectedAppsOnly: Boolean,
        directPackages: List<String>,
        routeRules: List<RouteRule>,
        tunMtu: Int,
    ): JSONObject {
        proxy.put("tag", PROXY_TAG)
        migrateDialFields(proxy)
        val config = JSONObject()
        // debug в файле ядра: нужен для диагностики обрывов (DNS, закрытия соединений).
        config.put("log", JSONObject().put("level", "debug").put("output", "logs/core.log").put("timestamp", true))
        config.put("inbounds", JSONArray().put(createTunInbound(packages, selectedAppsOnly, directPackages, tunMtu)))
        putProxy(config, proxy)
        (config.optJSONArray("outbounds") ?: JSONArray().also { config.put("outbounds", it) })
            .put(JSONObject().put("type", "direct").put("tag", DIRECT_TAG))
        config.put(
            "route",
            JSONObject()
                .put("auto_detect_interface", true)
                .put("default_domain_resolver", DNS_DIRECT_TAG)
                .put("rules", createRouteRules(routeRules, directPackages, if (selectedAppsOnly) packages else emptyList()))
                .put("final", PROXY_TAG),
        )
        config.put(
            "dns",
            JSONObject()
                .put("servers", createDnsServers())
                .put("rules", createDnsRules(routeRules, proxyServerHosts(proxy), directPackages))
                .put("final", DNS_TAG)
                // IPv4-only: серверы в основном IPv4, AAAA-ответы заворачивают приложения
                // на IPv6-адреса, которые через туннель не достижимы (YouTube вис).
                .put("strategy", "ipv4_only")
                .put("reverse_mapping", true),
        )
        return config
    }

    private fun putProxy(config: JSONObject, proxy: JSONObject) {
        val collection = if (proxy.optString("type") == "wireguard") "endpoints" else "outbounds"
        val items = config.optJSONArray(collection) ?: JSONArray().also { config.put(collection, it) }
        items.put(proxy)
    }

    private fun firstProxy(config: JSONObject): JSONObject? {
        for (collection in listOf("outbounds", "endpoints")) {
            val items = config.optJSONArray(collection) ?: continue
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                if (item.optString("type") !in setOf("direct", "block", "dns")) return item
            }
        }
        return null
    }

    private fun proxyServerHosts(proxy: JSONObject): List<String> {
        val result = mutableListOf<String>()
        proxy.optString("server").takeIf { it.isNotBlank() }?.let(result::add)
        val peers = proxy.optJSONArray("peers") ?: return result
        for (index in 0 until peers.length()) {
            peers.optJSONObject(index)?.optString("address")?.takeIf { it.isNotBlank() }?.let(result::add)
        }
        return result
    }

    private fun normalizeFullConfig(
        config: JSONObject,
        packages: List<String>,
        selectedAppsOnly: Boolean,
        directPackages: List<String>,
        routeRules: List<RouteRule>,
        tunMtu: Int,
    ): JSONObject {
        normalizeLegacyConfig(config)
        val outbounds = config.optJSONArray("outbounds") ?: JSONArray().also { config.put("outbounds", it) }
        val proxy = firstProxy(config)
            ?: throw IllegalArgumentException("В конфигурации нет серверного outbound или endpoint.")

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
            tun = createTunInbound(packages, selectedAppsOnly, directPackages, tunMtu)
            inbounds.put(tun)
        } else {
            tun.put("auto_route", true)
            tun.put("strict_route", true)
            if (!tun.has("address")) tun.put("address", JSONArray().put("172.19.0.1/30"))
            if (selectedAppsOnly) tun.put("include_package", JSONArray(packages)) else tun.remove("include_package")
            if (directPackages.isNotEmpty()) tun.put("exclude_package", JSONArray(directPackages)) else tun.remove("exclude_package")
        }
        // gVisor изолирует виртуальные адреса от системного IPv6-стека Android.
        // Настройка приложения заменяет MTU и из импортированного полного JSON.
        tun.put("stack", "gvisor").put("mtu", tunMtu)
        config.put("inbounds", inbounds)
        if (!config.has("log")) {
            config.put("log", JSONObject().put("level", "info").put("output", "logs/core.log").put("timestamp", true))
        }

        val route = config.optJSONObject("route") ?: JSONObject()
        if (!route.has("final") || route.optString("final").isBlank()) {
            val proxyTag = proxy.optString("tag")
            require(proxyTag.isNotBlank()) {
                "Нельзя выбрать маршрут: назначьте тег серверного outbound/endpoint и route.final в JSON-конфиге."
            }
            route.put("final", proxyTag)
        }
        route.put("auto_detect_interface", true)
        if (!route.has("default_domain_resolver")) route.put("default_domain_resolver", DNS_DIRECT_TAG)
        val actualProxyTag = route.optString("final")
        val userRules = createRouteRules(routeRules, directPackages, if (selectedAppsOnly) packages else emptyList(), proxyOutbound = actualProxyTag)
        val existingRules = route.optJSONArray("rules") ?: JSONArray()
        val mergedRules = JSONArray()
        for (index in 0 until userRules.length()) mergedRules.put(userRules.getJSONObject(index))
        for (index in 0 until existingRules.length()) mergedRules.put(existingRules.getJSONObject(index))
        route.put("rules", mergedRules)
        config.put("route", route)

        val hasDirect = (0 until outbounds.length()).any {
            outbounds.optJSONObject(it)?.optString("tag") == DIRECT_TAG
        }
        if (!hasDirect) outbounds.put(JSONObject().put("type", "direct").put("tag", DIRECT_TAG))
        normalizeFullDns(config)
        return config
    }

    /** Миграция полей, удалённых из sing-box 1.14, для ранее импортированных JSON-профилей. */
    private fun normalizeLegacyConfig(config: JSONObject): JSONObject {
        val blockTags = mutableSetOf<String>()
        val dnsTags = mutableSetOf<String>()
        val oldOutbounds = config.optJSONArray("outbounds") ?: JSONArray()
        val outbounds = JSONArray()
        val endpoints = config.optJSONArray("endpoints") ?: JSONArray()
        for (index in 0 until oldOutbounds.length()) {
            val outbound = oldOutbounds.optJSONObject(index) ?: continue
            when (outbound.optString("type")) {
                "block" -> outbound.optString("tag").takeIf { it.isNotBlank() }?.let(blockTags::add)
                "dns" -> outbound.optString("tag").takeIf { it.isNotBlank() }?.let(dnsTags::add)
                "wireguard" -> endpoints.put(wireGuardEndpoint(outbound))
                else -> {
                    migrateDialFields(outbound)
                    outbounds.put(outbound)
                }
            }
        }
        for (index in 0 until endpoints.length()) endpoints.optJSONObject(index)?.let(::migrateDialFields)
        config.put("outbounds", outbounds)
        if (endpoints.length() > 0) config.put("endpoints", endpoints)

        val route = config.optJSONObject("route")
        val routeRules = route?.optJSONArray("rules")
        if (routeRules != null) {
            for (index in 0 until routeRules.length()) {
                val rule = routeRules.optJSONObject(index) ?: continue
                when (rule.optString("outbound")) {
                    in blockTags -> rule.remove("outbound").let { rule.put("action", "reject") }
                    in dnsTags -> rule.remove("outbound").let { rule.put("action", "hijack-dns") }
                }
            }
        }

        val inbounds = config.optJSONArray("inbounds")
        if (inbounds != null) {
            for (index in 0 until inbounds.length()) {
                val inbound = inbounds.optJSONObject(index) ?: continue
                inbound.remove("sniff")
                inbound.remove("sniff_timeout")
                inbound.remove("sniff_override_destination")
                inbound.remove("domain_strategy")
                if (inbound.optString("type") == "tun") {
                    mergeJsonFields(inbound, "address", "inet4_address", "inet6_address")
                    mergeJsonFields(inbound, "route_address", "inet4_route_address", "inet6_route_address")
                    mergeJsonFields(inbound, "route_exclude_address", "inet4_route_exclude_address", "inet6_route_exclude_address")
                }
            }
        }

        val servers = config.optJSONObject("dns")?.optJSONArray("servers")
        if (servers != null) {
            for (index in 0 until servers.length()) {
                servers.optJSONObject(index)?.let(::migrateDnsServer)
            }
        }
        return config
    }

    private fun mergeJsonFields(target: JSONObject, newName: String, vararg oldNames: String) {
        if (target.has(newName)) {
            oldNames.forEach(target::remove)
            return
        }
        val merged = JSONArray()
        for (name in oldNames) {
            val value = target.opt(name) ?: continue
            if (value is JSONArray) {
                for (index in 0 until value.length()) merged.put(value.get(index))
            } else {
                merged.put(value)
            }
            target.remove(name)
        }
        if (merged.length() > 0) target.put(newName, merged)
    }

    private fun migrateDialFields(outbound: JSONObject) {
        val strategy = outbound.optString("domain_strategy")
        outbound.remove("domain_strategy")
        if (strategy.isNotBlank()) {
            outbound.put(
                "domain_resolver",
                JSONObject().put("server", DNS_DIRECT_TAG).put("strategy", strategy),
            )
        }
    }

    private fun migrateDnsServer(server: JSONObject) {
        if (server.has("address_resolver")) {
            server.put("domain_resolver", server.optString("address_resolver"))
            server.remove("address_resolver")
        }
        server.remove("address_strategy")
        if (!server.has("address")) return
        val address = server.optString("address")
        server.remove("address")
        if (address == "local") {
            server.put("type", "local")
            return
        }
        require(!address.startsWith("rcode:") && address != "fakeip") {
            "DNS-сервер «$address» требует ручной миграции для sing-box 1.14."
        }
        val withScheme = if (address.contains("://")) address else "udp://$address"
        val uri = parseUri(withScheme)
        server.put("type", uri.scheme.lowercase())
        server.put("server", uri.host.orEmpty())
        if (uri.port > 0) server.put("server_port", uri.port)
        if (uri.scheme.equals("https", true) || uri.scheme.equals("h3", true)) {
            if (!uri.rawPath.isNullOrBlank()) server.put("path", uri.rawPath)
        }
    }

    private fun wireGuardEndpoint(outbound: JSONObject): JSONObject {
        val endpoint = JSONObject(outbound.toString())
        if (!endpoint.has("address")) {
            endpoint.put("address", endpoint.opt("local_address") ?: JSONArray())
        }
        endpoint.remove("local_address")
        if (endpoint.has("system_interface")) {
            endpoint.put("system", endpoint.optBoolean("system_interface"))
            endpoint.remove("system_interface")
        }
        if (endpoint.has("interface_name")) {
            endpoint.put("name", endpoint.optString("interface_name"))
            endpoint.remove("interface_name")
        }
        val peers = endpoint.optJSONArray("peers") ?: JSONArray().also { endpoint.put("peers", it) }
        if (peers.length() == 0) {
            peers.put(
                JSONObject()
                    .put("address", endpoint.optString("server"))
                    .put("port", endpoint.optInt("server_port"))
                    .put("public_key", endpoint.optString("peer_public_key"))
                    .apply {
                        endpoint.optString("pre_shared_key").takeIf { it.isNotBlank() }?.let { put("pre_shared_key", it) }
                        endpoint.optJSONArray("allowed_ips")?.let { put("allowed_ips", it) }
                        endpoint.optJSONArray("reserved")?.let { put("reserved", it) }
                    },
            )
        }
        for (index in 0 until peers.length()) {
            val peer = peers.optJSONObject(index) ?: continue
            if (peer.has("server")) {
                peer.put("address", peer.optString("server"))
                peer.remove("server")
            }
            if (peer.has("server_port")) {
                peer.put("port", peer.optInt("server_port"))
                peer.remove("server_port")
            }
        }
        listOf("server", "server_port", "peer_public_key", "pre_shared_key", "allowed_ips", "reserved").forEach(endpoint::remove)
        return endpoint
    }

    /** DNS для полного JSON: добавляем прямой сервер и bootstrap доменов профилей. */
    private fun normalizeFullDns(config: JSONObject) {
        val serverHosts = mutableListOf<String>()
        for (collection in listOf("outbounds", "endpoints")) {
            val items = config.optJSONArray(collection) ?: continue
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                if (item.optString("type") !in setOf("direct", "block", "dns")) {
                    serverHosts += proxyServerHosts(item)
                }
            }
        }
        val routeFinal = config.getJSONObject("route").getString("final")
        val dns = config.optJSONObject("dns") ?: JSONObject()
            .put("servers", createDnsServers())
            .put("final", DNS_TAG)
            .put("strategy", "ipv4_only")
        if (!dns.has("reverse_mapping")) dns.put("reverse_mapping", true)
        val servers = dns.optJSONArray("servers") ?: JSONArray().also { dns.put("servers", it) }
        for (index in 0 until servers.length()) {
            val server = servers.optJSONObject(index) ?: continue
            if (server.optString("tag") == DNS_TAG) server.put("detour", routeFinal)
        }
        val hasDirectServer = (0 until servers.length()).any { servers.optJSONObject(it)?.optString("tag") == DNS_DIRECT_TAG }
        if (!hasDirectServer) {
            servers.put(directDnsResolver())
            servers.put(directDnsServer())
        }
        val rules = dns.optJSONArray("rules") ?: JSONArray()
        val merged = JSONArray()
        bootstrapDnsRule(serverHosts)?.let { merged.put(it) }
        for (index in 0 until rules.length()) merged.put(rules.getJSONObject(index))
        dns.put("rules", merged)
        config.put("dns", dns)
    }

    /** Правила маршрутизации: QUIC блокируем первым правилом, DIRECT-значения
     *  уходят в vibetun-direct, PROXY-значения — явно в прокси-outbound, остальное решает final.
     *
     *  Раньше правила PROXY молчо выбрасывались: для обычного конфига это маскировалось
     *  тем, что final и так прокси, но в полном пользовательском JSON (final может быть
     *  любым) и при перекрытии широким DIRECT-правилом явный PROXY не работал вовсе.
     *  Группы идут от частных к общим (ip_cidr → domain_suffix → keyword) внутри каждого
     *  действия. Доменные/IP-правила стоят РАНЬШЕ per-app: если приложение идёт через
     *  прокси (само или по умолчанию), домены/IP из «Домены и IP» решат по каждому
     *  соединению (Chrome=PROXY, но домен DIRECT → этот домен напрямую). Per-app правила
     *  ниже — запасной маршрут для трафика приложений, не попавшего под доменные правила;
     *  приложение с маршрутом DIRECT обычно вообще не попадает в TUN (exclude на уровне
     *  Android), поэтому доменное PROXY его оттуда не вытащит.
     *
     *  proxyOutbound: тег серверного outbound для PROXY-правил. Для стандартного конфига
     *  это PROXY_TAG («vibetun-proxy»), для полного пользовательского JSON — тег реального
     *  серверного outbound (тот же, что в route.final), чтобы правила не ссылались на
     *  несуществующий тег. */
    private fun createRouteRules(rules: List<RouteRule>, directPackages: List<String> = emptyList(), proxyPackages: List<String> = emptyList(), proxyOutbound: String = PROXY_TAG): JSONArray {
        // Sniff и DNS hijack в 1.14 — действия правил, а не поля inbound и dns-outbound.
        val merged = JSONArray()
            .put(JSONObject().put("action", "sniff"))
            .put(JSONObject().put("protocol", "dns").put("action", "hijack-dns"))
            .put(JSONObject().put("port", 53).put("action", "hijack-dns"))
            .put(quicBlockRule())
        fun group(action: RouteAction, outbound: String?) {
            fun valuesOf(type: RouteRuleType): JSONArray = JSONArray().apply {
                rules.filter { it.action == action && it.type == type }.forEach { rule ->
                    put(
                        when (type) {
                            RouteRuleType.KEYWORD -> RouteRule.keywordRegex(rule.value)
                            RouteRuleType.IP_CIDR -> normalizeCidr(rule.value)
                            else -> rule.value.trim().removePrefix("*.").removePrefix(".").lowercase()
                        },
                    )
                }
            }
            val cidr = valuesOf(RouteRuleType.IP_CIDR)
            val suffix = valuesOf(RouteRuleType.DOMAIN_SUFFIX)
            val keyword = valuesOf(RouteRuleType.KEYWORD)
            fun condition(key: String, values: JSONArray): JSONObject = JSONObject().put(key, values).apply {
                // В sing-box 1.14 блокировка — действие reject, block-outbound удалён.
                if (outbound == null) put("action", "reject") else put("outbound", outbound)
            }
            if (cidr.length() > 0) merged.put(condition("ip_cidr", cidr))
            if (suffix.length() > 0) merged.put(condition("domain_suffix", suffix))
            if (keyword.length() > 0) merged.put(condition("domain_regex", keyword))
        }
        // BLOCK стоит раньше более общих DIRECT/PROXY-правил.
        group(RouteAction.BLOCK, null)
        group(RouteAction.DIRECT, DIRECT_TAG)
        group(RouteAction.PROXY, proxyOutbound)
        // Per-app маршрут — после доменных/IP (см. комментарий к функции): ядро определяет
        // приложение по UID соединения (package_name включает поиск процесса в router).
        if (directPackages.isNotEmpty()) {
            merged.put(JSONObject().put("package_name", JSONArray(directPackages)).put("outbound", DIRECT_TAG))
        }
        if (proxyPackages.isNotEmpty()) {
            merged.put(JSONObject().put("package_name", JSONArray(proxyPackages)).put("outbound", proxyOutbound))
        }
        return merged
    }

    /** «1.2.3.4» → «1.2.3.4/32», «::1» → «::1/128»: ядро ждёт префикс в ip_cidr. */
    private fun normalizeCidr(raw: String): String {
        val value = raw.trim()
        if (value.contains('/')) return value
        return if (value.contains(':')) "$value/128" else "$value/32"
    }

    // НЕ «local»: на Android системный DNS аплинка бывает сломан. Яндекс-DoH идёт
    // напрямую, а домен его адреса разрешается через отдельный UDP-резолвер с IP-литералом.
    // В sing-box 1.14 DNS без detour уже использует прямой dialer. Явный detour на пустой
    // direct-outbound теперь отклоняется ядром: "empty direct outbound makes no sense".
    private fun directDnsServer(): JSONObject = JSONObject()
        .put("type", "https")
        .put("tag", DNS_DIRECT_TAG)
        .put("server", "common.dot.dns.yandex.net")
        .put("path", "/dns-query")
        .put("domain_resolver", DNS_DIRECT_RESOLVER_TAG)

    private fun directDnsResolver(): JSONObject = JSONObject()
        .put("type", "udp")
        .put("tag", DNS_DIRECT_RESOLVER_TAG)
        .put("server", "77.88.8.8")

    /** Всегда есть прямой DNS: без него доменный адрес сервера резолвится через ещё не поднятый прокси. */
    private fun createDnsServers(): JSONArray = JSONArray().apply {
        put(
            JSONObject()
                .put("type", "https")
                .put("tag", DNS_TAG)
                .put("server", "1.1.1.1")
                .put("path", "/dns-query")
                .put("detour", PROXY_TAG),
        )
        put(directDnsResolver())
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

    private fun createDnsRules(rules: List<RouteRule>, bootstrapHosts: List<String>, directPackages: List<String> = emptyList()): JSONArray {
        val result = JSONArray()
        bootstrapDnsRule(bootstrapHosts)?.let { result.put(it) }
        // DNS приложений с маршрутом DIRECT резолвим напрямую, а не через прокси-DNS.
        if (directPackages.isNotEmpty()) {
            result.put(JSONObject().put("package_name", JSONArray(directPackages)).put("server", DNS_DIRECT_TAG))
        }
        val direct = rules.filter { it.action == RouteAction.DIRECT && it.type != RouteRuleType.IP_CIDR }
        if (direct.isEmpty()) return result
        fun valuesOf(type: RouteRuleType): JSONArray = JSONArray().apply {
            direct.filter { it.type == type }.forEach { rule ->
                put(
                    if (type == RouteRuleType.KEYWORD) RouteRule.keywordRegex(rule.value)
                    else rule.value.trim().removePrefix("*.").removePrefix(".").lowercase(),
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

    private fun createTunInbound(packages: List<String>, selectedAppsOnly: Boolean, directPackages: List<String>, tunMtu: Int): JSONObject {
        val tun = JSONObject()
            .put("type", "tun")
            .put("tag", TUN_TAG)
            .put("address", JSONArray().put("172.19.0.1/30"))
            // Sniff перенесён в route.rules для sing-box 1.14.
            .put("mtu", tunMtu)
            .put("auto_route", true)
            .put("strict_route", true)
            .put("stack", "gvisor")
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
            throw IllegalArgumentException("AmneziaWG обрабатывается отдельным Android-движком, а не sing-box.")
        }
        if (scheme == "ssr") {
            throw IllegalArgumentException("ShadowsocksR (SSR) не поддерживается используемым ядром sing-box.")
        }
        if (scheme == "tg" || protocol.equals("mtproto", ignoreCase = true)) {
            throw IllegalArgumentException("MTProto предназначен только для Telegram и не является TUN-прокси общего назначения.")
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
            "anytls" -> {
                outbound.put("type", "anytls")
                outbound.put("password", password.ifBlank { username.ifBlank { userInfo } })
                applyTls(outbound, query, host, force = true)
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
            .put("address", uri.host.orEmpty())
            .put("port", uri.port.takeIf { it > 0 } ?: 51820)
            .put("public_key", peerKey)
            .put("allowed_ips", splitList(allowed))
        query.firstOf("preSharedKey", "pre_shared_key")?.let { peer.put("pre_shared_key", it) }
        return JSONObject()
            .put("type", "wireguard")
            .put("tag", PROXY_TAG)
            .put("address", splitList(address))
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
        peers.forEach { peer ->
            val endpoint = parseAuthority(peer["endpoint"].orEmpty())
            require(endpoint.first.isNotBlank()) { "В секции [Peer] не указан Endpoint." }
            val item = JSONObject()
                .put("address", endpoint.first)
                .put("port", endpoint.second)
                .put("public_key", peer["publickey"].orEmpty())
                .put("allowed_ips", splitList(peer["allowedips"] ?: "0.0.0.0/0,::/0"))
            peer["presharedkey"]?.takeIf { it.isNotBlank() }?.let { item.put("pre_shared_key", it) }
            peer["persistentkeepalive"]?.toIntOrNull()?.let { item.put("persistent_keepalive_interval", it) }
            peerArray.put(item)
        }
        val outbound = JSONObject()
            .put("type", "wireguard")
            .put("tag", PROXY_TAG)
            .put("address", splitList(addresses))
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
            else -> throw IllegalArgumentException("Транспорт $type не поддерживается sing-box 1.14.4.")
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

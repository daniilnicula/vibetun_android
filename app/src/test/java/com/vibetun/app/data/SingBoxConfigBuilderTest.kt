package com.vibetun.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SingBoxConfigBuilderTest {
    @Test
    fun `builds VLESS Reality WebSocket profile and selected-app TUN`() {
        val profile = profile(
            protocol = "vless",
            source = "vless://123e4567-e89b-12d3-a456-426614174000@edge.example:443" +
                "?security=reality&sni=cdn.example&fp=chrome&pbk=public-key&sid=01&type=ws" +
                "&host=cdn.example&path=%2Fconnect",
        )

        val config = JSONObject(SingBoxConfigBuilder.build(profile, setOf("org.example.mail"), true))
        val tun = config.getJSONArray("inbounds").getJSONObject(0)
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)

        assertEquals("tun", tun.getString("type"))
        assertEquals("gvisor", tun.getString("stack"))
        assertEquals("org.example.mail", tun.getJSONArray("include_package").getString(0))
        assertEquals("vless", outbound.getString("type"))
        assertEquals("123e4567-e89b-12d3-a456-426614174000", outbound.getString("uuid"))
        assertEquals("cdn.example", outbound.getJSONObject("tls").getString("server_name"))
        assertEquals("public-key", outbound.getJSONObject("tls").getJSONObject("reality").getString("public_key"))
        assertEquals("ws", outbound.getJSONObject("transport").getString("type"))
        assertEquals("/connect", outbound.getJSONObject("transport").getString("path"))
        assertEquals("vibetun-proxy", config.getJSONObject("route").getString("final"))
        assertEquals(SingBoxConfigBuilder.DEFAULT_TUN_MTU, tun.getInt("mtu"))
    }

    @Test
    fun `applies custom MTU to standard and full JSON TUN configs`() {
        val standard = JSONObject(
            SingBoxConfigBuilder.build(profile("socks", "socks5://127.0.0.1:1080"), tunMtu = 1420),
        )
        assertEquals(1420, standard.getJSONArray("inbounds").getJSONObject(0).getInt("mtu"))

        val raw = """{"inbounds":[{"type":"tun","mtu":9000}],"outbounds":[{"type":"vless","tag":"proxy","server":"full.example","server_port":443,"uuid":"u"}],"route":{"final":"proxy"}}"""
        val full = JSONObject(SingBoxConfigBuilder.build(profile("vless", raw), tunMtu = 1280))
        assertEquals(1280, full.getJSONArray("inbounds").getJSONObject(0).getInt("mtu"))
    }

    @Test
    fun `rejects invalid TUN MTU`() {
        val error = runCatching {
            SingBoxConfigBuilder.build(profile("socks", "socks5://127.0.0.1:1080"), tunMtu = 150)
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("MTU"))
    }

    @Test
    fun `converts VMess base64 links`() {
        val json = """{"v":"2","ps":"test","add":"vmess.example","port":"8443","id":"uuid","aid":"0","scy":"auto","net":"grpc","tls":"tls","sni":"vmess.example","path":"svc"}"""
        val link = "vmess://${Base64.getEncoder().withoutPadding().encodeToString(json.toByteArray())}"
        val config = JSONObject(SingBoxConfigBuilder.build(profile("vmess", link)))
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)

        assertEquals("vmess", outbound.getString("type"))
        assertEquals("uuid", outbound.getString("uuid"))
        assertEquals(8443, outbound.getInt("server_port"))
        assertEquals("grpc", outbound.getJSONObject("transport").getString("type"))
        assertTrue(outbound.getJSONObject("tls").getBoolean("enabled"))
    }

    @Test
    fun `converts Shadowsocks SIP002 credentials`() {
        val credentials = Base64.getEncoder().withoutPadding().encodeToString("aes-128-gcm:p@ss".toByteArray())
        val config = JSONObject(
            SingBoxConfigBuilder.build(profile("shadowsocks", "ss://$credentials@ss.example:8388#home")),
        )
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)

        assertEquals("shadowsocks", outbound.getString("type"))
        assertEquals("aes-128-gcm", outbound.getString("method"))
        assertEquals("p@ss", outbound.getString("password"))
        assertEquals(8388, outbound.getInt("server_port"))
    }

    @Test
    fun `converts WireGuard INI with peers`() {
        val configText = """
            [Interface]
            PrivateKey = private-key
            Address = 10.0.0.2/32
            DNS = 1.1.1.1

            [Peer]
            PublicKey = public-key
            PresharedKey = preshared-key
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = wg.example:51820
            PersistentKeepalive = 25
        """.trimIndent()
        val configTextJson = try {
            SingBoxConfigBuilder.build(profile("wireguard", configText))
        } catch (error: Exception) {
            throw AssertionError("WireGuard INI conversion failed: ${error.message}", error)
        }
        val config = JSONObject(configTextJson)
        val endpoint = config.getJSONArray("endpoints").getJSONObject(0)
        val peer = endpoint.getJSONArray("peers").getJSONObject(0)

        assertEquals("wireguard", endpoint.getString("type"))
        assertEquals("private-key", endpoint.getString("private_key"))
        assertEquals("10.0.0.2/32", endpoint.getJSONArray("address").getString(0))
        assertEquals("wg.example", peer.getString("address"))
        assertEquals(51820, peer.getInt("port"))
        assertEquals("public-key", peer.getString("public_key"))
        assertEquals(2, peer.getJSONArray("allowed_ips").length())
    }

    @Test
    fun `adds VPN inbound to a full sing-box JSON config`() {
        val raw = """{"outbounds":[{"type":"socks","tag":"upstream","server":"127.0.0.1","server_port":1080}],"route":{"final":"upstream"}}"""
        val config = JSONObject(SingBoxConfigBuilder.build(profile("tunnel", raw)))

        assertEquals("tun", config.getJSONArray("inbounds").getJSONObject(0).getString("type"))
        assertEquals("gvisor", config.getJSONArray("inbounds").getJSONObject(0).getString("stack"))
        assertEquals("upstream", config.getJSONObject("route").getString("final"))
        assertTrue(config.getJSONObject("route").getBoolean("auto_detect_interface"))
        val dnsServers = config.getJSONObject("dns").getJSONArray("servers")
        val proxyDns = (0 until dnsServers.length()).map { dnsServers.getJSONObject(it) }
            .first { it.optString("tag") == "vibetun-dns" }
        assertEquals("upstream", proxyDns.getString("detour"))
    }

    @Test
    fun `builds AnyTLS profile with TLS`() {
        val config = JSONObject(
            SingBoxConfigBuilder.build(profile("anytls", "anytls://password@server.example:443?sni=cdn.example")),
        )
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)

        assertEquals("anytls", outbound.getString("type"))
        assertEquals("password", outbound.getString("password"))
        assertTrue(outbound.getJSONObject("tls").getBoolean("enabled"))
        assertEquals("cdn.example", outbound.getJSONObject("tls").getString("server_name"))
    }

    @Test
    fun `excludes VibeTun from selected applications`() {
        val profile = profile("socks", "socks5://127.0.0.1:1080")
        val config = JSONObject(
            SingBoxConfigBuilder.build(profile, setOf("com.vibetun.app", "org.example.mail"), true),
        )

        val packages = config.getJSONArray("inbounds").getJSONObject(0).getJSONArray("include_package")
        assertEquals(1, packages.length())
        assertEquals("org.example.mail", packages.getString(0))
    }

    @Test
    fun `emits explicit PROXY and DIRECT domain and IP rules`() {
        val rules = listOf(
            RouteRule("example.com", RouteAction.DIRECT),
            RouteRule("video.example.org", RouteAction.PROXY),
            RouteRule("203.0.113.7", RouteAction.DIRECT),
            RouteRule("198.51.100.0/24", RouteAction.PROXY),
        )
        val config = JSONObject(
            SingBoxConfigBuilder.build(profile("socks", "socks5://127.0.0.1:1080"), routeRules = rules),
        )
        val routeRules = config.getJSONObject("route").getJSONArray("rules")
        val byOutbound = mutableMapOf<String, JSONObject>()
        for (i in 0 until routeRules.length()) {
            val rule = routeRules.getJSONObject(i)
            if (rule.has("domain_suffix")) byOutbound[rule.getString("outbound") + ":suffix"] = rule
            if (rule.has("ip_cidr")) byOutbound[rule.getString("outbound") + ":cidr"] = rule
        }
        assertEquals("example.com", byOutbound.getValue("vibetun-direct:suffix").getJSONArray("domain_suffix").getString(0))
        assertEquals("video.example.org", byOutbound.getValue("vibetun-proxy:suffix").getJSONArray("domain_suffix").getString(0))
        // голый IP нормализуется в /32, готовая подсеть не трогается
        assertEquals("203.0.113.7/32", byOutbound.getValue("vibetun-direct:cidr").getJSONArray("ip_cidr").getString(0))
        assertEquals("198.51.100.0/24", byOutbound.getValue("vibetun-proxy:cidr").getJSONArray("ip_cidr").getString(0))
    }

    @Test
    fun `BLOCK uses reject and disabled rules are omitted`() {
        val rules = listOf(
            RouteRule("blocked.example", RouteAction.BLOCK),
            RouteRule("disabled.example", RouteAction.DIRECT, enabled = false),
        )
        val config = JSONObject(
            SingBoxConfigBuilder.build(profile("socks", "socks5://127.0.0.1:1080"), routeRules = rules),
        )
        val routeRules = config.getJSONObject("route").getJSONArray("rules")
        val block = (0 until routeRules.length()).map { routeRules.getJSONObject(it) }
            .first { it.optString("action") == "reject" && it.has("domain_suffix") }

        assertEquals("blocked.example", block.getJSONArray("domain_suffix").getString(0))
        assertFalse(block.has("outbound"))
        assertFalse(routeRules.toString().contains("disabled.example"))
        assertFalse(config.getJSONObject("dns").getJSONArray("rules").toString().contains("disabled.example"))
    }

    @Test
    fun `routes direct apps by package name after domain rules`() {
        val config = JSONObject(
            SingBoxConfigBuilder.build(
                profile("socks", "socks5://127.0.0.1:1080"),
                directPackages = setOf("org.example.bank"),
                routeRules = listOf(RouteRule("example.com", RouteAction.PROXY)),
            ),
        )
        val routeRules = config.getJSONObject("route").getJSONArray("rules")
        var packageRuleIndex = -1
        var domainRuleIndex = -1
        for (i in 0 until routeRules.length()) {
            val rule = routeRules.getJSONObject(i)
            if (rule.has("package_name")) packageRuleIndex = i
            if (rule.has("domain_suffix")) domainRuleIndex = i
        }
        assertTrue(packageRuleIndex >= 0)
        assertEquals("vibetun-direct", routeRules.getJSONObject(packageRuleIndex).getString("outbound"))
        assertEquals("org.example.bank", routeRules.getJSONObject(packageRuleIndex).getJSONArray("package_name").getString(0))
        // Доменные правила раньше per-app: приложение через прокси, но PROXY/DIRECT
        // конкретного домена из «Домены и IP» решает по этому соединению.
        assertTrue(domainRuleIndex in 0 until packageRuleIndex)
        // DNS прямого приложения тоже идёт напрямую
        val dnsRules = config.getJSONObject("dns").getJSONArray("rules")
        val dnsPackageRule = (0 until dnsRules.length()).map { dnsRules.getJSONObject(it) }.first { it.has("package_name") }
        assertEquals("vibetun-dns-direct", dnsPackageRule.getString("server"))
    }

    @Test
    fun `treats bare and dotted TLD as domain suffix so io rules match 2ip io`() {
        assertEquals(RouteRuleType.DOMAIN_SUFFIX, RouteRule.detectType("io"))
        assertEquals(RouteRuleType.DOMAIN_SUFFIX, RouteRule.detectType(".io"))
        assertEquals(RouteRuleType.DOMAIN_SUFFIX, RouteRule.detectType("2ip.io"))
        assertEquals(RouteRuleType.KEYWORD, RouteRule.detectType("youtube"))

        val config = JSONObject(
            SingBoxConfigBuilder.build(
                profile("socks", "socks5://127.0.0.1:1080"),
                routeRules = listOf(RouteRule(".io", RouteAction.DIRECT)),
            ),
        )
        val rules = config.getJSONObject("route").getJSONArray("rules")
        val directSuffix = (0 until rules.length()).map { rules.getJSONObject(it) }
            .first { it.optString("outbound") == "vibetun-direct" && it.has("domain_suffix") }
        assertEquals("io", directSuffix.getJSONArray("domain_suffix").getString(0))
        // Снифф — первое route-действие; старые поля TUN в 1.14 удалены.
        assertEquals("sniff", rules.getJSONObject(0).getString("action"))
        val tun = config.getJSONArray("inbounds").getJSONObject(0)
        assertFalse(tun.has("sniff_override_destination"))
        assertTrue(config.getJSONObject("dns").getBoolean("reverse_mapping"))
    }

    @Test
    fun `keyword regex escapes regexp metacharacters instead of Q E quoting`() {
        val regex = RouteRule.keywordRegex("2ip.io (test)")
        assertTrue(!regex.contains("\\Q"))
        assertTrue(regex.contains("2ip\\.io"))
        assertTrue(java.util.regex.Pattern.compile(regex).matcher("www.2ip.io (test)").find())
    }

    private fun profile(protocol: String, source: String) = ProxyProfile(
        name = "Test",
        protocol = protocol,
        server = "",
        port = 0,
        source = source,
    )

    @Test
    fun `routes sniff DNS hijack and QUIC rejection before other rules`() {
        val config = JSONObject(SingBoxConfigBuilder.build(profile("hysteria2", "hy2://pass@hy.example:443")))
        val outboundTypes = (0 until config.getJSONArray("outbounds").length())
            .map { config.getJSONArray("outbounds").getJSONObject(it).getString("type") }
        assertFalse("dns" in outboundTypes)
        assertFalse("block" in outboundTypes)

        val rules = config.getJSONObject("route").getJSONArray("rules")
        assertEquals("sniff", rules.getJSONObject(0).getString("action"))
        val dnsProtocol = rules.getJSONObject(1)
        assertEquals("dns", dnsProtocol.getString("protocol"))
        assertEquals("hijack-dns", dnsProtocol.getString("action"))
        val dnsPort = rules.getJSONObject(2)
        assertEquals(53, dnsPort.getInt("port"))
        assertEquals("hijack-dns", dnsPort.getString("action"))
        val quic = rules.getJSONObject(3)
        assertEquals("udp", quic.getString("network"))
        assertEquals(443, quic.getInt("port"))
        assertEquals("reject", quic.getString("action"))
    }

    @Test
    fun `bootstraps domain proxy server through direct DNS`() {
        val config = JSONObject(SingBoxConfigBuilder.build(profile("trojan", "trojan://pw@edge.example:443")))
        val dns = config.getJSONObject("dns")
        val servers = dns.getJSONArray("servers")
        val directServer = (0 until servers.length()).map { servers.getJSONObject(it) }
            .first { it.getString("tag") == "vibetun-dns-direct" }
        val directResolver = (0 until servers.length()).map { servers.getJSONObject(it) }
            .first { it.getString("tag") == "vibetun-dns-direct-resolver" }
        // В 1.14 прямой DNS использует собственный dialer; detour на пустой direct-outbound
        // не нужен и приводит к отказу запуска всех sing-box протоколов.
        assertFalse(directServer.has("detour"))
        assertFalse(directResolver.has("detour"))

        val bootstrap = dns.getJSONArray("rules").getJSONObject(0)
        assertEquals("vibetun-dns-direct", bootstrap.getString("server"))
        assertEquals("edge.example", bootstrap.getJSONArray("domain").getString(0))
    }

    @Test
    fun `does not bootstrap IP literal server`() {
        val config = JSONObject(SingBoxConfigBuilder.build(profile("trojan", "trojan://pw@203.0.113.7:443")))
        val rules = config.getJSONObject("dns").getJSONArray("rules")
        for (index in 0 until rules.length()) {
            assertTrue(!rules.getJSONObject(index).has("domain"))
        }
    }

    @Test
    fun `full JSON config gets route actions sniff and bootstrap without legacy outbounds`() {
        val raw = """{"outbounds":[{"type":"vless","tag":"proxy","server":"full.example","server_port":443,"uuid":"u"}],"route":{"final":"proxy"}}"""
        val config = JSONObject(SingBoxConfigBuilder.build(profile("vless", raw)))
        val types = (0 until config.getJSONArray("outbounds").length())
            .map { config.getJSONArray("outbounds").getJSONObject(it).getString("type") }
        assertFalse("dns" in types)
        assertFalse("block" in types)
        assertTrue("direct" in types)
        val rules = config.getJSONObject("route").getJSONArray("rules")
        assertEquals("sniff", rules.getJSONObject(0).getString("action"))
        assertEquals("hijack-dns", rules.getJSONObject(1).getString("action"))
        val dnsRules = config.getJSONObject("dns").getJSONArray("rules")
        assertEquals("full.example", dnsRules.getJSONObject(0).getJSONArray("domain").getString(0))
    }

    @Test
    fun `redactForLog masks secrets but keeps structure`() {
        val config = SingBoxConfigBuilder.build(
            profile("vless", "vless://123e4567-e89b-12d3-a456-426614174000@edge.example:443?security=reality&pbk=PUBKEY&sid=01&sni=cdn.example"),
        )
        val redacted = SingBoxConfigBuilder.redactForLog(config)
        assertTrue(!redacted.contains("123e4567-e89b-12d3-a456-426614174000"))
        assertTrue(!redacted.contains("PUBKEY"))
        assertTrue(redacted.contains("edge.example"))
        assertTrue(redacted.contains("\"uuid\":\"***\""))
    }

    @Test
    fun `route has sniff and TUN has no legacy sniff fields in standard config`() {
        val config = JSONObject(
            SingBoxConfigBuilder.build(profile("socks", "socks5://127.0.0.1:1080")),
        )
        val tun = config.getJSONArray("inbounds").getJSONObject(0)
        val rules = config.getJSONObject("route").getJSONArray("rules")
        assertEquals("sniff", rules.getJSONObject(0).getString("action"))
        assertFalse(tun.has("sniff"))
        assertFalse(
            "sniff_override_destination must NOT be set (breaks ip_cidr matching)",
            tun.has("sniff_override_destination"),
        )
    }

    @Test
    fun `route has sniff and TUN has no legacy sniff fields in full JSON config`() {
        val raw = """{"outbounds":[{"type":"vless","tag":"my-proxy","server":"x.example","server_port":443,"uuid":"u"}],"route":{"final":"my-proxy"}}"""
        val config = JSONObject(SingBoxConfigBuilder.build(profile("vless", raw)))
        val tun = config.getJSONArray("inbounds").getJSONObject(0)
        val rules = config.getJSONObject("route").getJSONArray("rules")
        assertEquals("sniff", rules.getJSONObject(0).getString("action"))
        assertFalse(tun.has("sniff"))
        assertFalse(
            "sniff_override_destination must be stripped from full JSON",
            tun.has("sniff_override_destination"),
        )
    }

    @Test
    fun `full JSON PROXY rules reference the real outbound tag not vibetun-proxy`() {
        val raw = """{"outbounds":[{"type":"vless","tag":"my-custom-proxy","server":"full.example","server_port":443,"uuid":"u"}],"route":{"final":"my-custom-proxy"}}"""
        val rules = listOf(
            RouteRule("youtube.com", RouteAction.PROXY),
            RouteRule("198.51.100.0/24", RouteAction.PROXY),
            RouteRule("example.org", RouteAction.DIRECT),
        )
        val config = JSONObject(SingBoxConfigBuilder.build(profile("vless", raw), routeRules = rules))
        val routeRules = config.getJSONObject("route").getJSONArray("rules")
        for (i in 0 until routeRules.length()) {
            val rule = routeRules.getJSONObject(i)
            val outbound = rule.optString("outbound")
            // PROXY-правила ссылаются на «my-custom-proxy», а не на несуществующий «vibetun-proxy»
            if (outbound == "vibetun-proxy") {
                throw AssertionError("PROXY rule at index $i references vibetun-proxy, but the actual outbound tag is my-custom-proxy")
            }
        }
        // Проверяем, что PROXY-правила вообще есть и ссылаются на правильный тег
        val proxyRules = (0 until routeRules.length())
            .map { routeRules.getJSONObject(it) }
            .filter { it.optString("outbound") == "my-custom-proxy" }
        assertTrue("PROXY rules must reference the real outbound tag", proxyRules.isNotEmpty())
        //DIRECT-правила продолжают ссылаться на vibetun-direct
        assertEquals("vibetun-direct", routeRules.let { arr ->
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .first { it.has("domain_suffix") }.getString("outbound")
        })
    }

    @Test
    fun `DIRECT rules appear before PROXY group and package rules`() {
        val rules = listOf(
            RouteRule("example.com", RouteAction.DIRECT),
            RouteRule("203.0.113.0/24", RouteAction.DIRECT),
            RouteRule("blocked.com", RouteAction.PROXY),
        )
        val config = JSONObject(
            SingBoxConfigBuilder.build(
                profile("socks", "socks5://127.0.0.1:1080"),
                selectedPackages = setOf("com.app.proxy"),
                selectedAppsOnly = true,
                directPackages = setOf("com.app.direct"),
                routeRules = rules,
            ),
        )
        val routeRules = config.getJSONObject("route").getJSONArray("rules")
        // Находим позиции
        var firstDirectIdx = -1
        var firstProxyIdx = -1
        var firstPackageIdx = -1
        for (i in 0 until routeRules.length()) {
            val rule = routeRules.getJSONObject(i)
            if (firstDirectIdx < 0 && rule.optString("outbound") == "vibetun-direct" && !rule.has("package_name")) {
                firstDirectIdx = i
            }
            if (firstProxyIdx < 0 && rule.optString("outbound") == "vibetun-proxy" && !rule.has("package_name")) {
                firstProxyIdx = i
            }
            if (firstPackageIdx < 0 && rule.has("package_name")) {
                firstPackageIdx = i
            }
        }
        assertTrue("DIRECT domain/IP rules must exist", firstDirectIdx >= 0)
        assertTrue("PROXY domain/IP rules must exist", firstProxyIdx >= 0)
        assertTrue("Package rules must exist", firstPackageIdx >= 0)
        assertTrue("DIRECT rules must come before PROXY rules", firstDirectIdx < firstProxyIdx)
        assertTrue("Domain/IP rules must come before package rules", firstProxyIdx < firstPackageIdx)
    }

    @Test
    fun `full JSON with existing sniff_override_destination gets it removed`() {
        val raw = """{"inbounds":[{"type":"tun","tag":"tun","inet4_address":"172.19.0.1/30"}],"outbounds":[{"type":"vless","tag":"proxy","server":"x.example","server_port":443,"uuid":"u"}],"route":{"final":"proxy"}}"""
        val configObj = JSONObject(raw).apply {
            getJSONArray("inbounds").getJSONObject(0).put("sniff_override_destination", true)
        }
        val config = JSONObject(SingBoxConfigBuilder.build(profile("vless", configObj.toString())))
        val tun = config.getJSONArray("inbounds").getJSONObject(0)
        assertFalse(
            "User's sniff_override_destination must be removed",
            tun.has("sniff_override_destination"),
        )
        assertFalse(tun.has("sniff"))
        assertEquals("172.19.0.1/30", tun.getJSONArray("address").getString(0))
        assertEquals("sniff", config.getJSONObject("route").getJSONArray("rules").getJSONObject(0).getString("action"))
    }

    @Test
    fun `migrates legacy DNS TUN WireGuard and special outbounds`() {
        val raw = """
            {
              "inbounds":[{"type":"tun","inet4_address":"172.19.0.1/30","inet6_address":["fdfe:dcba:9876::1/126"],"sniff":true,"sniff_override_destination":true}],
              "outbounds":[
                {"type":"wireguard","tag":"legacy-wg","server":"wg.example","server_port":51820,"local_address":["10.0.0.2/32"],"private_key":"private","peer_public_key":"public","allowed_ips":["0.0.0.0/0"],"domain_strategy":"ipv4_only"},
                {"type":"block","tag":"legacy-block"},
                {"type":"dns","tag":"legacy-dns"}
              ],
              "route":{"final":"legacy-wg","rules":[{"port":443,"outbound":"legacy-block"},{"protocol":"dns","outbound":"legacy-dns"}]},
              "dns":{"servers":[{"tag":"cloudflare","address":"https://1.1.1.1/dns-query"}],"final":"cloudflare"}
            }
        """.trimIndent()
        val config = JSONObject(SingBoxConfigBuilder.build(profile("wireguard", raw)))
        val tun = config.getJSONArray("inbounds").getJSONObject(0)
        assertEquals(2, tun.getJSONArray("address").length())
        assertFalse(tun.has("inet4_address"))
        assertFalse(tun.has("sniff"))

        val endpoint = config.getJSONArray("endpoints").getJSONObject(0)
        assertEquals("10.0.0.2/32", endpoint.getJSONArray("address").getString(0))
        assertFalse(endpoint.has("domain_strategy"))
        assertEquals("wg.example", endpoint.getJSONArray("peers").getJSONObject(0).getString("address"))
        val outboundTypes = (0 until config.getJSONArray("outbounds").length())
            .map { config.getJSONArray("outbounds").getJSONObject(it).getString("type") }
        assertFalse("wireguard" in outboundTypes)
        assertFalse("block" in outboundTypes)
        assertFalse("dns" in outboundTypes)

        val migratedRules = config.getJSONObject("route").getJSONArray("rules")
        assertTrue((0 until migratedRules.length()).map { migratedRules.getJSONObject(it) }
            .any { it.optString("action") == "reject" && !it.has("outbound") })
        assertTrue((0 until migratedRules.length()).map { migratedRules.getJSONObject(it) }
            .any { it.optString("action") == "hijack-dns" && it.optString("protocol") == "dns" && !it.has("outbound") })
        val dnsServer = config.getJSONObject("dns").getJSONArray("servers").getJSONObject(0)
        assertEquals("https", dnsServer.getString("type"))
        assertEquals("1.1.1.1", dnsServer.getString("server"))
        assertEquals("/dns-query", dnsServer.getString("path"))
        assertFalse(dnsServer.has("address"))
    }

    @Test
    fun `probe uses a cache file separate from the active VPN service`() {
        val profile = profile("socks", "socks5://127.0.0.1:1080")
        val probe = JSONObject(SingBoxConfigBuilder.buildProbeConfig(profile, 12080))
        val probeCache = probe.getJSONObject("experimental").getJSONObject("cache_file")

        assertTrue(probeCache.getBoolean("enabled"))
        assertEquals("probe-cache.db", probeCache.getString("path"))

        val service = JSONObject(SingBoxConfigBuilder.build(profile))
        val serviceCache = service.optJSONObject("experimental")?.optJSONObject("cache_file")
        assertTrue(serviceCache == null || serviceCache.optString("path") != "probe-cache.db")
    }
}

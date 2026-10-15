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
        val outbound = config.getJSONArray("outbounds").getJSONObject(0)
        val peer = outbound.getJSONArray("peers").getJSONObject(0)

        assertEquals("wireguard", outbound.getString("type"))
        assertEquals("private-key", outbound.getString("private_key"))
        assertEquals("10.0.0.2/32", outbound.getJSONArray("local_address").getString(0))
        assertEquals("wg.example", peer.getString("server"))
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
    }

    @Test
    fun `rejects protocols that the bundled core does not implement`() {
        val error = runCatching {
            SingBoxConfigBuilder.build(profile("anytls", "anytls://password@server.example:443"))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("AnyTLS"))
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
        // снифф включён (доменные правила работают через metadata.Domain),
        // но override выключен — ip_cidr матчится по исходному IP, а не по резолвленному.
        val tun = config.getJSONArray("inbounds").getJSONObject(0)
        assertTrue(tun.getBoolean("sniff"))
        assertTrue(!tun.has("sniff_override_destination") || !tun.getBoolean("sniff_override_destination"))
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
    fun `routes DNS from TUN to dns outbound before any other rule`() {
        val config = JSONObject(SingBoxConfigBuilder.build(profile("hysteria2", "hy2://pass@hy.example:443")))
        val tun = config.getJSONArray("inbounds").getJSONObject(0)
        assertTrue(tun.getBoolean("sniff"))

        val outboundTypes = (0 until config.getJSONArray("outbounds").length())
            .map { config.getJSONArray("outbounds").getJSONObject(it).getString("type") }
        assertTrue("dns" in outboundTypes)

        val rules = config.getJSONObject("route").getJSONArray("rules")
        val first = rules.getJSONObject(0)
        val second = rules.getJSONObject(1)
        assertEquals("dns", first.getString("protocol"))
        assertEquals("vibetun-dns-out", first.getString("outbound"))
        assertEquals(53, second.getInt("port"))
        assertEquals("vibetun-dns-out", second.getString("outbound"))
        // QUIC-блокировка стоит после DNS-правил
        assertEquals("udp", rules.getJSONObject(2).getString("network"))
    }

    @Test
    fun `bootstraps domain proxy server through direct DNS`() {
        val config = JSONObject(SingBoxConfigBuilder.build(profile("trojan", "trojan://pw@edge.example:443")))
        val dns = config.getJSONObject("dns")
        val servers = dns.getJSONArray("servers")
        val directServer = (0 until servers.length()).map { servers.getJSONObject(it) }
            .first { it.getString("tag") == "vibetun-dns-direct" }
        assertEquals("vibetun-direct", directServer.getString("detour"))

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
    fun `full JSON config gets DNS outbound, sniff and bootstrap`() {
        val raw = """{"outbounds":[{"type":"vless","tag":"proxy","server":"full.example","server_port":443,"uuid":"u"}],"route":{"final":"proxy"}}"""
        val config = JSONObject(SingBoxConfigBuilder.build(profile("vless", raw)))
        val types = (0 until config.getJSONArray("outbounds").length())
            .map { config.getJSONArray("outbounds").getJSONObject(it).getString("type") }
        assertTrue("dns" in types)
        assertTrue("direct" in types)
        assertTrue(config.getJSONArray("inbounds").getJSONObject(0).getBoolean("sniff"))
        val firstRule = config.getJSONObject("route").getJSONArray("rules").getJSONObject(0)
        assertEquals("dns", firstRule.getString("protocol"))
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
    fun `TUN has sniff but not sniff_override_destination in standard config`() {
        val config = JSONObject(
            SingBoxConfigBuilder.build(profile("socks", "socks5://127.0.0.1:1080")),
        )
        val tun = config.getJSONArray("inbounds").getJSONObject(0)
        assertTrue("sniff must be enabled", tun.getBoolean("sniff"))
        assertFalse(
            "sniff_override_destination must NOT be set (breaks ip_cidr matching)",
            tun.has("sniff_override_destination"),
        )
    }

    @Test
    fun `TUN has sniff but not sniff_override_destination in full JSON config`() {
        val raw = """{"outbounds":[{"type":"vless","tag":"my-proxy","server":"x.example","server_port":443,"uuid":"u"}],"route":{"final":"my-proxy"}}"""
        val config = JSONObject(SingBoxConfigBuilder.build(profile("vless", raw)))
        val tun = config.getJSONArray("inbounds").getJSONObject(0)
        assertTrue("sniff must be enabled for full JSON", tun.getBoolean("sniff"))
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
        assertTrue(tun.getBoolean("sniff"))
    }
}

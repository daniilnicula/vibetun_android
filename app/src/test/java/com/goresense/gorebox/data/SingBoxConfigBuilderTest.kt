package com.goresense.gorebox.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
        assertEquals("gorebox-proxy", config.getJSONObject("route").getString("final"))
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
    fun `excludes GoreBox from selected applications`() {
        val profile = profile("socks", "socks5://127.0.0.1:1080")
        val config = JSONObject(
            SingBoxConfigBuilder.build(profile, setOf("com.goresense.gorebox", "org.example.mail"), true),
        )

        val packages = config.getJSONArray("inbounds").getJSONObject(0).getJSONArray("include_package")
        assertEquals(1, packages.length())
        assertEquals("org.example.mail", packages.getString(0))
    }

    private fun profile(protocol: String, source: String) = ProxyProfile(
        name = "Test",
        protocol = protocol,
        server = "",
        port = 0,
        source = source,
    )
}

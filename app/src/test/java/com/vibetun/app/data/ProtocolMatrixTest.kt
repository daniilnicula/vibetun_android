package com.vibetun.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * Матрица протоколов: для каждого типа ссылки проверяем структуру outbound, который уйдёт в ядро sing-box 1.9.7.
 * Это проверка конфигурации (unit-тест), а не реального подключения — последнее см. README (чек-лист на устройстве).
 */
class ProtocolMatrixTest {
    private fun proxyOutbound(protocol: String, link: String): JSONObject {
        val config = JSONObject(SingBoxConfigBuilder.build(profile(protocol, link)))
        return config.getJSONArray("outbounds").getJSONObject(0)
    }

    @Test
    fun `VLESS over WebSocket with TLS and ALPN`() {
        val outbound = proxyOutbound(
            "vless",
            "vless://123e4567-e89b-12d3-a456-426614174000@v.example:443?security=tls&sni=v.example&type=ws&path=%2Fws&host=v.example&alpn=h2,http/1.1",
        )
        assertEquals("vless", outbound.getString("type"))
        assertEquals("ws", outbound.getJSONObject("transport").getString("type"))
        assertEquals("/ws", outbound.getJSONObject("transport").getString("path"))
        assertEquals("v.example", outbound.getJSONObject("transport").getJSONObject("headers").getString("Host"))
        assertTrue(outbound.getJSONObject("tls").getBoolean("enabled"))
        assertEquals(2, outbound.getJSONObject("tls").getJSONArray("alpn").length())
        // utls без fingerprint не включаем по умолчанию для обычного TLS
        assertFalse(outbound.getJSONObject("tls").has("utls"))
    }

    @Test
    fun `VLESS Reality over gRPC uses reality key and default chrome fingerprint`() {
        val outbound = proxyOutbound(
            "vless",
            "vless://123e4567-e89b-12d3-a456-426614174000@r.example:443?security=reality&sni=cdn.example&pbk=PUB&sid=ab&type=grpc&serviceName=svc&flow=xtls-rprx-vision",
        )
        assertEquals("xtls-rprx-vision", outbound.getString("flow"))
        assertEquals("grpc", outbound.getJSONObject("transport").getString("type"))
        assertEquals("svc", outbound.getJSONObject("transport").getString("service_name"))
        val tls = outbound.getJSONObject("tls")
        assertEquals("PUB", tls.getJSONObject("reality").getString("public_key"))
        assertEquals("ab", tls.getJSONObject("reality").getString("short_id"))
        assertEquals("chrome", tls.getJSONObject("utls").getString("fingerprint"))
    }

    @Test
    fun `VLESS over HTTPUpgrade`() {
        val outbound = proxyOutbound(
            "vless",
            "vless://123e4567-e89b-12d3-a456-426614174000@h.example:80?type=httpupgrade&path=%2Fup&host=h.example",
        )
        assertEquals("httpupgrade", outbound.getJSONObject("transport").getString("type"))
        assertEquals("/up", outbound.getJSONObject("transport").getString("path"))
    }

    @Test
    fun `Trojan forces TLS and takes password from userinfo`() {
        val outbound = proxyOutbound("trojan", "trojan://secret-pass@t.example:443?sni=t.example")
        assertEquals("trojan", outbound.getString("type"))
        assertEquals("secret-pass", outbound.getString("password"))
        assertTrue(outbound.getJSONObject("tls").getBoolean("enabled"))
    }

    @Test
    fun `Shadowsocks accepts fully base64 encoded link`() {
        val body = Base64.getEncoder().withoutPadding().encodeToString("chacha20-ietf-poly1305:pw@ss2.example:8388".toByteArray())
        val outbound = proxyOutbound("shadowsocks", "ss://$body#name")
        assertEquals("shadowsocks", outbound.getString("type"))
        assertEquals("chacha20-ietf-poly1305", outbound.getString("method"))
        assertEquals("pw", outbound.getString("password"))
        assertEquals("ss2.example", outbound.getString("server"))
        assertEquals(8388, outbound.getInt("server_port"))
    }

    @Test
    fun `Shadowsocks plugin is rejected explicitly instead of being dropped`() {
        val credentials = Base64.getEncoder().withoutPadding().encodeToString("aes-128-gcm:pw".toByteArray())
        try {
            SingBoxConfigBuilder.build(profile("shadowsocks", "ss://$credentials@ss.example:8388?plugin=obfs-local%3Bobfs%3Dhttp"))
            fail("plugin must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("плагин"))
        }
    }

    @Test
    fun `SOCKS5 and HTTP keep credentials`() {
        val socks = proxyOutbound("socks", "socks5://user:pw@s.example:1080")
        assertEquals("5", socks.getString("version"))
        assertEquals("user", socks.getString("username"))
        assertEquals("pw", socks.getString("password"))

        val http = proxyOutbound("http", "https://u:p@h.example:443")
        assertEquals("http", http.getString("type"))
        assertEquals("u", http.getString("username"))
        assertTrue(http.getJSONObject("tls").getBoolean("enabled"))
    }

    @Test
    fun `Hysteria v1 needs bandwidth and keeps auth`() {
        val outbound = proxyOutbound("hysteria", "hysteria://h1.example:443?auth=token&up=10&down=50&obfs=xplus")
        assertEquals("hysteria", outbound.getString("type"))
        assertEquals("token", outbound.getString("auth_str"))
        assertEquals(10, outbound.getInt("up_mbps"))
        assertEquals(50, outbound.getInt("down_mbps"))
        assertEquals("xplus", outbound.getString("obfs"))

        try {
            SingBoxConfigBuilder.build(profile("hysteria", "hysteria://h1.example:443?auth=token"))
            fail("missing up/down must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("up"))
        }
    }

    @Test
    fun `Hysteria 2 maps mport to hop_ports and insecure TLS`() {
        val outbound = proxyOutbound("hysteria2", "hy2://pass@hy.example:443?mport=20000-30000&sni=cdn.example&insecure=1")
        assertEquals("hysteria2", outbound.getString("type"))
        assertEquals("pass", outbound.getString("password"))
        assertEquals("20000-30000", outbound.getString("hop_ports"))
        assertEquals("cdn.example", outbound.getJSONObject("tls").getString("server_name"))
        assertTrue(outbound.getJSONObject("tls").getBoolean("insecure"))
    }

    @Test
    fun `TUIC uses uuid password and h3 ALPN by default`() {
        val outbound = proxyOutbound("tuic", "tuic://11111111-2222-3333-4444-555555555555:tpass@tu.example:443?congestion_control=bbr")
        assertEquals("tuic", outbound.getString("type"))
        assertEquals("11111111-2222-3333-4444-555555555555", outbound.getString("uuid"))
        assertEquals("tpass", outbound.getString("password"))
        assertEquals("bbr", outbound.getString("congestion_control"))
        assertEquals("h3", outbound.getJSONObject("tls").getJSONArray("alpn").getString(0))
    }

    @Test
    fun `TUIC normalizes legacy ALPN lists to h3`() {
        // Старые генераторы ссылок подставляют "h3,spdy/3.1": для TUIC v5 корректен только h3.
        val outbound = proxyOutbound(
            "tuic",
            "tuic://11111111-2222-3333-4444-555555555555:tpass@tu.example:443?alpn=h3%2Cspdy%2F3.1&udp_relay_mode=native",
        )
        val alpn = outbound.getJSONObject("tls").getJSONArray("alpn")
        assertEquals(1, alpn.length())
        assertEquals("h3", alpn.getString(0))
    }

    @Test
    fun `SSH takes user and password`() {
        val outbound = proxyOutbound("ssh", "ssh://admin:pw@ssh.example:22")
        assertEquals("ssh", outbound.getString("type"))
        assertEquals("admin", outbound.getString("user"))
        assertEquals("pw", outbound.getString("password"))
    }

    private fun profile(protocol: String, source: String) = ProxyProfile(
        name = "Test",
        protocol = protocol,
        server = "",
        port = 0,
        source = source,
    )
}

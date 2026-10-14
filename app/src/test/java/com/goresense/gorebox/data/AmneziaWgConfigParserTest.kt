package com.goresense.gorebox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmneziaWgConfigParserTest {
    @Test
    fun `parses AWG parameters and serializes userspace API settings`() {
        val config = AmneziaWgConfig.parse(sampleConfig)
        val userspace = config.userspaceConfig { "198.51.100.8:51820" }

        assertEquals(listOf("10.8.0.2/32", "fd00::2/128"), config.addresses)
        assertEquals(listOf("1.1.1.1"), config.dnsServers)
        assertEquals(listOf("corp.example"), config.dnsSearchDomains)
        assertEquals(1280, config.mtu)
        assertEquals(listOf("0.0.0.0/0", "::/0"), config.peerAllowedIps())
        assertTrue(userspace.contains("private_key=${"00".repeat(32)}\n"))
        assertTrue(userspace.contains("jc=4\njmin=40\njmax=70\n"))
        assertTrue(userspace.contains("s1=15\ns2=16\nh1=123456\ni1=\\x01\\x02\n"))
        assertTrue(userspace.contains("header_protection_key=${"00".repeat(32)}\n"))
        assertTrue(userspace.contains("content_padding_addition=20-40\nrandom_trailers=1\ndisable_cookies=0\n"))
        assertTrue(userspace.contains("replace_peers=true\npublic_key=${"01".repeat(32)}\n"))
        assertTrue(userspace.contains("endpoint=198.51.100.8:51820\n"))
        assertTrue(userspace.contains("persistent_keepalive_interval=25\n"))
        assertTrue(userspace.contains("preshared_key=${"01".repeat(32)}\n"))

        // В журнале запуска параметры обфускации есть, а ключей — нет.
        val debug = config.debugDescription()
        assertTrue(debug.contains("jc=4"))
        assertTrue(debug.contains("h1=123456"))
        assertTrue(!debug.contains("private_key"))
    }

    @Test
    fun `recognizes AWG configurations by any supported obfuscation key`() {
        val profile = ProfileLinkParser.parseMany(
            """
                [Interface]
                PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
                Address = 10.8.0.2/32
                S3 = 12
                [Peer]
                PublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
                AllowedIPs = 0.0.0.0/0
                Endpoint = 192.0.2.1:51820
            """.trimIndent(),
        ).profiles.single()

        assertEquals("amneziawg", profile.protocol)
    }

    @Test
    fun `rejects malformed AWG keys and missing peer routes`() {
        val invalidKey = runCatching { AmneziaWgConfig.parse(sampleConfig.replace(
            "PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "PrivateKey = not-a-key",
        )) }.exceptionOrNull()
        assertTrue(invalidKey is IllegalArgumentException)

        val missingRoutes = runCatching {
            AmneziaWgConfig.parse(sampleConfig.replace("AllowedIPs = 0.0.0.0/0, ::/0", "AllowedIPs = "))
        }.exceptionOrNull()
        assertTrue(missingRoutes is IllegalArgumentException)
    }

    private fun configWithKeepalive(line: String?): String =
        sampleConfig.lines().filterNot { it.trim().startsWith("PersistentKeepalive") }
            .let { lines -> if (line == null) lines else lines + "        $line" }
            .joinToString("\n")

    @Test
    fun `keeps 25 second keepalive when PersistentKeepalive is absent`() {
        val userspace = AmneziaWgConfig.parse(configWithKeepalive(null)).userspaceConfig { "198.51.100.8:51820" }
        assertTrue(userspace.contains("persistent_keepalive_interval=25\n"))
    }

    @Test
    fun `PersistentKeepalive zero or off disables keepalive`() {
        listOf("PersistentKeepalive = 0", "PersistentKeepalive = off").forEach { line ->
            val userspace = AmneziaWgConfig.parse(configWithKeepalive(line)).userspaceConfig { "198.51.100.8:51820" }
            assertTrue("$line must not emit keepalive", !userspace.contains("persistent_keepalive_interval"))
        }
    }

    @Test
    fun `parses AWG 1x configuration without S3 S4 and I1-I5`() {
        val legacy = sampleConfig.lines()
            .filterNot { line -> listOf("S2", "I1", "HeaderProtectionKey", "ContentPaddingAddition", "RandomTrailers").any { line.trim().startsWith(it) } }
            .joinToString("\n")
        val config = AmneziaWgConfig.parse(legacy)
        val userspace = config.userspaceConfig { "198.51.100.8:51820" }
        assertEquals(listOf("10.8.0.2/32", "fd00::2/128"), config.addresses)
        assertTrue(userspace.contains("jc=4\n"))
        assertTrue(!userspace.contains("header_protection_key"))
    }

    @Test
    fun `tolerates extra spaces and mixed-case keys`() {
        val messy = sampleConfig
            .replace("[Peer]", "  [peer]  ")
            .replace("AllowedIPs = 0.0.0.0/0, ::/0", "allowedips   =   0.0.0.0/0 ,   ::/0  ")
            .replace("Address = 10.8.0.2/32, fd00::2/128", "ADDRESS=10.8.0.2/32,fd00::2/128")
        val config = AmneziaWgConfig.parse(messy)
        assertEquals(listOf("10.8.0.2/32", "fd00::2/128"), config.addresses)
        assertEquals(listOf("0.0.0.0/0", "::/0"), config.peerAllowedIps())
    }

    private val sampleConfig = """
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = 10.8.0.2/32, fd00::2/128
        DNS = 1.1.1.1, corp.example
        MTU = 1280
        Jc = 4
        Jmin = 40
        Jmax = 70
        S1 = 15
        S2 = 16
        H1 = 123456
        I1 = \x01\x02
        HeaderProtectionKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        ContentPaddingAddition = 20-40
        RandomTrailers = true
        DisableCookies = off

        [Peer]
        PublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
        PresharedKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = awg.example.net:51820
        PersistentKeepalive = 25
    """.trimIndent()
}

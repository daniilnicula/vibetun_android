package com.goresense.gorebox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLinkParserTest {
    @Test
    fun parsesVlessLinkAndKeepsOriginalSource() {
        val source = "vless://123e4567-e89b-12d3-a456-426614174000@example.net:443?security=tls#Home%20node"
        val parsed = ProfileLinkParser.parseMany(source)

        assertEquals(1, parsed.profiles.size)
        assertTrue(parsed.errors.isEmpty())
        assertEquals("vless", parsed.profiles.single().protocol)
        assertEquals("Home node", parsed.profiles.single().name)
        assertEquals("example.net", parsed.profiles.single().server)
        assertEquals(443, parsed.profiles.single().port)
        assertEquals(source, parsed.profiles.single().source)
    }

    @Test
    fun parsesMultipleProtocolsOnSeparateLines() {
        val parsed = ProfileLinkParser.parseMany(
            """# GoreBox subscription
               trojan://secret@example.org:8443#Backup
               socks5://127.0.0.1:1080#Local""".trimIndent(),
        )

        assertEquals(listOf("trojan", "socks"), parsed.profiles.map { it.protocol })
        assertEquals(2, parsed.profiles.size)
    }

    @Test
    fun classifiesRawSingBoxJsonAsCustomAndPreservesIt() {
        val json = """{"outbounds":[{"type":"vless","tag":"edge","server":"edge.example","server_port":443}]}"""
        val parsed = ProfileLinkParser.parseMany(json)

        assertEquals("tunnel", parsed.profiles.single().protocol)
        assertEquals(json, parsed.profiles.single().source)
        assertEquals("edge", parsed.profiles.single().name)
    }

    @Test
    fun reportsUnknownSchemes() {
        val parsed = ProfileLinkParser.parseMany("gopher://example.org")
        assertTrue(parsed.profiles.isEmpty())
        assertFalse(parsed.errors.isEmpty())
    }
}

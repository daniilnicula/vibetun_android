package com.vibetun.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Формат набора правил маршрутизации: действие, тип и флаг включения.
 * Функции JSON вынесены из RouteSetStore (ему нужен Android-контекст),
 * чтобы тестировать без Robolectric/Instrumentation.
 */
class RouteRulesJsonTest {
    @Test
    fun `round trip keeps action type and enabled flag`() {
        val rules = listOf(
            RouteRule("example.com", RouteAction.DIRECT),
            RouteRule("ads.example", RouteAction.BLOCK),
            RouteRule("198.51.100.0/24", RouteAction.PROXY, RouteRuleType.IP_CIDR, enabled = false),
        )

        val parsed = parseRouteRulesJson(routeRulesToJson(rules))

        assertEquals(3, parsed.size)
        assertEquals(RouteAction.DIRECT, parsed[0].action)
        assertEquals(RouteAction.BLOCK, parsed[1].action)
        assertEquals(RouteRuleType.IP_CIDR, parsed[2].type)
        assertFalse(parsed[2].enabled)
        assertTrue(parsed[0].enabled)
    }

    @Test
    fun `old files without enabled flag stay enabled`() {
        val parsed = parseRouteRulesJson("""[{"value":"vk.com","action":"proxy","type":"DOMAIN_SUFFIX"}]""")

        assertEquals(1, parsed.size)
        assertTrue(parsed[0].enabled)
        assertEquals(RouteAction.PROXY, parsed[0].action)
    }

    @Test
    fun `block action is stored as block`() {
        val json = routeRulesToJson(listOf(RouteRule("blocked.example", RouteAction.BLOCK)))

        assertTrue(json.contains("\"action\":\"block\""))
        assertEquals(RouteAction.BLOCK, parseRouteRulesJson(json)[0].action)
    }

    @Test
    fun `unknown action falls back to proxy`() {
        val parsed = parseRouteRulesJson("""[{"value":"example.com","action":"weird"}]""")

        assertEquals(RouteAction.PROXY, parsed[0].action)
    }

    @Test
    fun `blank values are skipped`() {
        val parsed = parseRouteRulesJson("""[{"value":"  ","action":"proxy"},{"value":"ok.example","action":"direct"}]""")

        assertEquals(1, parsed.size)
        assertEquals("ok.example", parsed[0].value)
    }

    @Test
    fun `rule type is detected from the entered value`() {
        assertEquals(RouteRuleType.IP_CIDR, RouteRule.detectType("203.0.113.7"))
        assertEquals(RouteRuleType.IP_CIDR, RouteRule.detectType("203.0.113.0/24"))
        assertEquals(RouteRuleType.DOMAIN_SUFFIX, RouteRule.detectType("youtube.com"))
        assertEquals(RouteRuleType.DOMAIN_SUFFIX, RouteRule.detectType("*.example.org"))
        assertEquals(RouteRuleType.DOMAIN_SUFFIX, RouteRule.detectType(".io"))
        assertEquals(RouteRuleType.KEYWORD, RouteRule.detectType("youtube"))
    }

    @Test
    fun `type label is human readable`() {
        assertEquals("домен", RouteRule("example.com", RouteAction.PROXY).typeLabel)
        assertEquals("ip", RouteRule("203.0.113.7", RouteAction.PROXY).typeLabel)
        assertEquals("слово", RouteRule("youtube", RouteAction.PROXY).typeLabel)
    }
}

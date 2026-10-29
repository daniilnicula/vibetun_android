package com.vibetun.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Подписки: имя группы по ссылке, сборка групп профилей и обновление профилей
 * подписки по свежей выгрузке.
 */
class SubscriptionGroupTest {
    @Test
    fun `subscription name is the host of the link`() {
        assertEquals("84.19.3.106", subscriptionNameOf("https://84.19.3.106/sub/abc123"))
        assertEquals("sub.example.com", subscriptionNameOf("https://sub.example.com/link?token=1"))
        assertEquals("Подписка", subscriptionNameOf("не-ссылка"))
    }

    @Test
    fun `subscription group comes first and keeps its profiles`() {
        val subOne = profile("p1", "84.19.3.106", "vless://a")
        val subTwo = profile("p2", "84.19.3.106", "trojan://b")
        val manual = profile("p3", "10.0.0.1", "socks5://c")
        val subscription = Subscription(
            id = "sub-1",
            name = "84.19.3.106",
            url = "https://84.19.3.106/sub/abc",
            profileIds = listOf("p1", "p2"),
        )

        val groups = buildProfileGroups(listOf(manual, subTwo, subOne), listOf(subscription))

        assertEquals(2, groups.size)
        val first = groups[0]
        assertEquals("sub:sub-1", first.id)
        assertNotNull(first.subscription)
        assertEquals(2, first.profiles.size)
        assertEquals("84.19.3.106", first.title)
        // группа подписки идёт раньше обычных групп
        assertEquals("host:10.0.0.1", groups[1].id)
        assertNull(groups[1].subscription)
    }

    @Test
    fun `subscription without profiles is skipped`() {
        val subscription = Subscription(id = "sub-1", name = "Пусто", url = "https://example.com/sub")
        val groups = buildProfileGroups(listOf(profile("p1", "10.0.0.1", "socks5://c")), listOf(subscription))

        assertEquals(1, groups.size)
        assertEquals("host:10.0.0.1", groups[0].id)
    }

    @Test
    fun `refresh updates existing configs and adds new ones`() {
        val old = profile("p1", "84.19.3.106", "vless://old")
        val manual = profile("p9", "10.0.0.1", "socks5://keep")
        val changed = old.copy(server = "84.19.3.106", port = 443, source = "vless://new")
        val added = profile("p2", "84.19.3.106", "trojan://added")

        val result = mergeSubscriptionProfiles(
            current = listOf(old, manual),
            previousIds = listOf("p1"),
            parsed = listOf(changed, added),
        )

        assertEquals(1, result.added)
        assertEquals(1, result.updated)
        assertEquals(0, result.removed)
        assertEquals(2, result.profileIds.size)
        // профиль пользователя вне подписки не тронут
        assertTrue(result.profiles.any { it.id == "p9" })
        // существующий профиль сохранил id, избранное и обновил ссылку
        val updated = result.profiles.first { it.id == "p1" }
        assertEquals("vless://new", updated.source)
        assertEquals(added.id, result.profiles.first { it.source == "trojan://added" }.id)
    }

    @Test
    fun `refresh updates a config whose credentials changed but the server stayed`() {
        val old = profile("p1", "84.19.3.106", "vless://old-uuid")
        val changed = old.copy(source = "vless://new-uuid")

        val result = mergeSubscriptionProfiles(listOf(old), listOf("p1"), listOf(changed))

        assertEquals(0, result.added)
        assertEquals(1, result.updated)
        assertEquals("p1", result.profileIds.single())
        assertEquals("vless://new-uuid", result.profiles.first { it.id == "p1" }.source)
    }

    @Test
    fun `refresh removes configs that disappeared from the subscription`() {
        val kept = profile("p1", "84.19.3.106", "vless://one")
        val gone = profile("p2", "84.19.3.106", "trojan://two")

        val result = mergeSubscriptionProfiles(
            current = listOf(kept, gone),
            previousIds = listOf("p1", "p2"),
            parsed = listOf(kept),
        )

        assertEquals(1, result.removed)
        assertEquals(0, result.added)
        assertEquals(1, result.updated)
        assertEquals(listOf("p1"), result.profileIds)
        assertTrue(result.profiles.none { it.id == "p2" })
    }

    @Test
    fun `favorite flag survives a subscription refresh`() {
        val favorite = profile("p1", "84.19.3.106", "vless://one").copy(favorite = true)
        val result = mergeSubscriptionProfiles(
            current = listOf(favorite),
            previousIds = listOf("p1"),
            parsed = listOf(favorite.copy(source = "vless://one-updated")),
        )

        assertTrue(result.profiles.first { it.id == "p1" }.favorite)
    }

    private fun profile(id: String, server: String, source: String) = ProxyProfile(
        id = id,
        name = id,
        protocol = "vless",
        server = server,
        port = 443,
        source = source,
    )
}

package com.vibetun.app.data

import java.net.URI

/**
 * Логика подписок: имя группы по ссылке, сборка групп профилей и обновление
 * профилей подписки по свежей выгрузке.
 *
 * Всё здесь — чистые функции без Android-зависимостей: их удобно покрыть
 * юнит-тестами (см. SubscriptionGroupTest).
 */

/** Хост ссылки подписки: «84.19.3.106» из «https://84.19.3.106/sub/abc». */
fun subscriptionHostOf(url: String): String {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return ""
    val uriHost = runCatching { URI(trimmed).host }.getOrNull()?.takeIf { it.isNotBlank() }
    if (uriHost != null) return uriHost
    // Без схемы хост не угадать: берём его только из явно URL-подобной строки.
    if (!trimmed.contains("://")) return ""
    val host = trimmed.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':')
    return host.takeIf { it.isNotBlank() && it.contains('.') && !it.contains(' ') }.orEmpty()
}

/** Имя группы подписки: хост ссылки, а если разобрать не удалось — «Подписка». */
fun subscriptionNameOf(url: String): String = subscriptionHostOf(url).ifBlank { "Подписка" }

/** Ключ группы для профилей без подписки: адрес сервера. */
fun profileGroupKeyOf(profile: ProxyProfile): String = when {
    profile.server.isNotBlank() -> profile.server
    profile.protocol.equals("tunnel", ignoreCase = true) -> "Локальный JSON-конфиг"
    else -> "Без адреса"
}

/**
 * Группы профилей для списка серверов: сначала подписки (в порядке добавления),
 * затем группы одного адреса для остальных профилей.
 */
fun buildProfileGroups(
    profiles: List<ProxyProfile>,
    subscriptions: List<Subscription>,
): List<ProfileGroup> {
    val groups = mutableListOf<ProfileGroup>()
    val grouped = mutableSetOf<String>()
    subscriptions.forEach { subscription ->
        val items = profiles.filter { it.id in subscription.profileIds }
        if (items.isEmpty()) return@forEach
        grouped += items.map { it.id }
        groups += ProfileGroup(
            id = "sub:${subscription.id}",
            title = subscription.name.ifBlank { subscriptionNameOf(subscription.url) },
            host = subscriptionHostOf(subscription.url),
            profiles = items,
            subscription = subscription,
        )
    }
    val rest = profiles.filter { it.id !in grouped }
    val byHost = LinkedHashMap<String, MutableList<ProxyProfile>>()
    rest.forEach { profile ->
        byHost.getOrPut(profileGroupKeyOf(profile)) { mutableListOf() }.add(profile)
    }
    byHost.forEach { (host, items) ->
        groups += ProfileGroup(id = "host:$host", title = host, host = host, profiles = items)
    }
    return groups
}

/** Итог обновления подписки: новый список профилей и статистика изменений. */
data class SubscriptionMergeResult(
    val profiles: List<ProxyProfile>,
    val profileIds: List<String>,
    val added: Int,
    val updated: Int,
    val removed: Int,
)

/**
 * Обновление профилей подписки по свежей выгрузке.
 *
 * Профиль сопоставляется с уже сохранённым (сначала по исходной ссылке, затем по
 * адресу сервера — у сервера часто меняются ключи/UUID, а хост и порт те же, затем
 * по названию). Найденный профиль обновляем техническими полями (протокол, адрес,
 * порт, ссылка), сохраняя id, имя и отметку «избранное»: выбранный профиль не
 * «уезжает», а конфиг внутри группы обновляется по-настоящему. Новая ссылка без
 * совпадения — новый профиль; профили, которых больше нет в выгрузке, удаляются.
 */
fun mergeSubscriptionProfiles(
    current: List<ProxyProfile>,
    previousIds: List<String>,
    parsed: List<ProxyProfile>,
): SubscriptionMergeResult {
    val owned = previousIds.toSet()
    val ownedProfiles = current.filter { it.id in owned }
    val used = mutableSetOf<String>()
    val merged = mutableListOf<ProxyProfile>()
    var added = 0
    var updated = 0
    parsed.forEach { item ->
        val existing = ownedProfiles.firstOrNull { candidate ->
            candidate.id !in used && matchesProfile(candidate, item)
        }
        if (existing == null) {
            merged += item
            added++
        } else {
            used += existing.id
            merged += existing.copy(
                protocol = item.protocol,
                server = item.server,
                port = item.port,
                source = item.source,
                latencyMs = if (existing.endpoint == item.endpoint) existing.latencyMs else null,
            )
            updated++
        }
    }
    val removed = ownedProfiles.count { it.id !in used }
    val result = ArrayList<ProxyProfile>(current.size + merged.size)
    current.forEach { profile ->
        if (profile.id !in owned) result += profile
    }
    result += merged
    return SubscriptionMergeResult(
        profiles = result,
        profileIds = merged.map { it.id },
        added = added,
        updated = updated,
        removed = removed,
    )
}

/**
 * Совпадение профиля подписки с уже сохранённым: ссылка, затем адрес сервера
 * (часто меняются только ключи/UUID), затем название.
 */
private fun matchesProfile(existing: ProxyProfile, item: ProxyProfile): Boolean = when {
    existing.source == item.source -> true
    existing.server.isNotBlank() && existing.server == item.server &&
        existing.port == item.port &&
        existing.protocol.equals(item.protocol, ignoreCase = true) -> true
    existing.name.isNotBlank() && existing.name == item.name -> true
    else -> false
}

package com.vibetun.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class RouteAction { PROXY, DIRECT }

enum class RouteRuleType { DOMAIN_SUFFIX, KEYWORD, IP_CIDR }

/**
 * Правило маршрутизации, как в Windows-версии: значение + действие.
 * Тип определяется автоматически: IP/CIDR → ip_cidr, домен → domain_suffix
 * (захватывает поддомены), всё остальное («youtube», «vk») → keyword-regex.
 */
data class RouteRule(
    val value: String,
    val action: RouteAction,
    val type: RouteRuleType = detectType(value),
) {
    companion object {
        private val DOMAIN = Regex("^(?:\\*\\.)?(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}$")
        private val IPV4 = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?:/(?:[0-9]|[12][0-9]|3[0-2]))?$")
        private val IPV6 = Regex("^[0-9a-fA-F:]+(?:/(?:[0-9]|[0-9]{2}|1[01][0-9]|12[0-8]))?$")

        fun detectType(raw: String): RouteRuleType {
            val value = raw.trim()
            return when {
                IPV4.matches(value) || (value.contains(':') && IPV6.matches(value)) -> RouteRuleType.IP_CIDR
                DOMAIN.matches(value) -> RouteRuleType.DOMAIN_SUFFIX
                // «.io» / «*.io»: пользователь имеет в виду весь домен верхнего уровня
                // (после снятия префикса «io» не проходит DOMAIN — там нужна точка,
                // поэтому проверяем остаток и как домен, и как одиночную метку).
                (value.startsWith('.') || value.startsWith("*.")) && (
                    DOMAIN.matches(value.removePrefix("*.").removePrefix(".")) ||
                        value.removePrefix("*.").removePrefix(".").all { it.isLetterOrDigit() || it == '-' }
                    ) -> RouteRuleType.DOMAIN_SUFFIX
                // «io», «ru», «com»: короткая одна метка — это TLD-суффикс, а не ключевое слово;
                // иначе «io» уходило в domain_regex и не ловило 2ip.io как суффикс.
                value.length in 2..3 && value.all { it.isLetter() } -> RouteRuleType.DOMAIN_SUFFIX
                else -> RouteRuleType.KEYWORD
            }
        }

        /**
         * keyword → литеральный regex для domain_regex ядра.
         * Go-regexp (RE2-синтаксис в sing-box) НЕ поддерживает \Q…\E — такое выражение
         * отклоняется при разборе правила; экранируем метасимволы вручную.
         */
        fun keywordRegex(keyword: String): String = buildString {
            keyword.trim().forEach { ch ->
                if (ch in "\\.+*?()|[]{}^$") append('\\')
                append(ch)
            }
        }
    }
}

/**
 * Наборы правил маршрутизации хранятся отдельными json-файлами
 * (filesDir/routes/<имя>.json) — как routes\ в Windows-версии; файл можно
 * перенести между устройствами через экспорт/импорт.
 */
class RouteSetStore(context: Context) {
    private val dir = File(context.filesDir, "routes").apply { mkdirs() }

    fun listSets(): List<String> =
        dir.listFiles { file -> file.extension == "json" }
            ?.map { it.nameWithoutExtension }
            ?.sorted()
            .orEmpty()

    fun load(setName: String): List<RouteRule> {
        val file = File(dir, "$setName.json")
        if (!file.isFile) return emptyList()
        return runCatching { parseJson(file.readText()) }.getOrDefault(emptyList())
    }

    fun save(setName: String, rules: List<RouteRule>) {
        File(dir, "$setName.json").writeText(toJson(rules))
    }

    fun toJson(rules: List<RouteRule>): String {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject()
                    .put("value", rule.value)
                    .put("action", rule.action.name.lowercase())
                    .put("type", rule.type.name),
            )
        }
        return array.toString()
    }

    fun parseJson(text: String): List<RouteRule> {
        val array = JSONArray(text)
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val value = item.optString("value").trim()
                if (value.isBlank()) continue
                val action = if (item.optString("action").equals("direct", ignoreCase = true)) {
                    RouteAction.DIRECT
                } else {
                    RouteAction.PROXY
                }
                add(RouteRule(value, action))
            }
        }
    }

    companion object {
        /** Валидация значения правила: не пустое, одна строка. */
        fun isValidValue(value: String): Boolean = value.isNotBlank() && !value.contains('\n')
    }
}

/**
 * Выбор активного набора маршрутов: сохранённое имя приоритетно, fallback на первый
 * по алфавиту. Вынесено в чистую функцию для юнит-тестирования без Android-контекста.
 */
fun resolveActiveRouteSet(saved: String, available: List<String>): String =
    if (saved.isNotBlank() && saved in available) saved else available.firstOrNull().orEmpty()

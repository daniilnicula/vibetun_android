package com.goresense.gorebox.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Small Android-side importer for the link formats recognized by GoreBox on Windows.
 * The original link/JSON is kept intact so the native sing-box adapter can consume it later.
 */
object ProfileLinkParser {
    fun parseMany(input: String): ProfileParseResult {
        val text = input.trim()
        if (text.isEmpty()) return ProfileParseResult(emptyList(), listOf("Вставьте ссылку или конфиг."))

        if (looksLikeWireGuardConfig(text)) {
            return ProfileParseResult(listOf(parseWireGuardConfig(text)), emptyList())
        }
        if (text.startsWith("{") || text.startsWith("[")) {
            return parseConfig(text)
        }

        // A subscription is sometimes returned as one base64 blob.
        if (!text.contains('\n') && !hasScheme(text)) {
            val decoded = decodeBase64(text)
            if (decoded != null && (hasScheme(decoded) || decoded.contains("[Interface]"))) {
                return parseMany(decoded)
            }
        }

        val profiles = mutableListOf<ProxyProfile>()
        val errors = mutableListOf<String>()
        text.lineSequence()
            .map { it.trim().trim('\uFEFF') }
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("//") }
            .forEach { line ->
                val parsed = parseOne(line)
                if (parsed != null) {
                    profiles += parsed
                } else {
                    val decoded = decodeBase64(line)
                    if (decoded != null && hasScheme(decoded)) {
                        val nested = parseMany(decoded)
                        profiles += nested.profiles
                        errors += nested.errors
                    } else {
                        errors += "Не распознана строка: ${line.take(64)}"
                    }
                }
            }
        return ProfileParseResult(profiles, errors)
    }

    fun parseOne(source: String): ProxyProfile? {
        val line = source.trim()
        if (looksLikeWireGuardConfig(line)) return parseWireGuardConfig(line)
        if (line.startsWith("{") || line.startsWith("[")) return parseConfig(line).profiles.firstOrNull()

        val scheme = line.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme.isBlank()) return null
        if (scheme == "vmess") return parseVmess(line)
        if (scheme == "ss") return parseShadowsocks(line)
        if (scheme == "ssr") return parseShadowsocksR(line)
        if (scheme == "vpn") {
            val decoded = decodeBase64(line.substringAfter("vpn://", "")) ?: return null
            return parseOne(decoded)
        }
        if (scheme == "tg") return parseTelegram(line)

        val protocol = when (scheme) {
            "vless" -> "vless"
            "trojan", "trojan-go" -> "trojan"
            "socks", "socks4", "socks5" -> "socks"
            "http", "https" -> "http"
            "hysteria", "hy" -> "hysteria"
            "hysteria2", "hy2" -> "hysteria2"
            "tuic" -> "tuic"
            "anytls" -> "anytls"
            "ssh" -> "ssh"
            "wireguard", "wg" -> "wireguard"
            "awg", "amneziawg" -> "amneziawg"
            "custom", "tunnel" -> "tunnel"
            else -> return null
        }
        val uri = runCatching { URI(line.replace(" ", "%20")) }.getOrNull() ?: return null
        val host = uri.host.orEmpty()
        val port = uri.port.takeIf { it > 0 } ?: defaultPort(protocol)
        val fragment = uri.rawFragment?.let(::decodeComponent).orEmpty()
        val fallbackName = if (host.isNotBlank()) "$host${if (port > 0) ":$port" else ""}" else ProtocolCatalog.label(protocol)
        val name = fragment.ifBlank { fallbackName }
        return profile(name, protocol, host, port, line)
    }

    private fun parseVmess(line: String): ProxyProfile? {
        val payload = line.substringAfter("vmess://", "").substringBefore('#')
        val decoded = decodeBase64(payload) ?: return null
        return runCatching {
            val json = JSONObject(decoded)
            val host = json.optString("add", json.optString("server", ""))
            val port = json.optString("port", "443").toIntOrNull() ?: 443
            val name = json.optString("ps", "").ifBlank { "$host:$port" }
            profile(name, "vmess", host, port, line)
        }.getOrNull()
    }

    private fun parseShadowsocks(line: String): ProxyProfile? {
        val fragment = line.substringAfter('#', "").takeIf { it.isNotBlank() }?.let(::decodeComponent).orEmpty()
        val body = line.substringAfter("ss://", "").substringBefore('#').substringBefore('?')
        val at = body.lastIndexOf('@')
        val decoded = if (at >= 0) decodeBase64(body.substring(0, at)) else decodeBase64(body)
        val addressPart = if (at >= 0) body.substring(at + 1) else decoded?.substringAfter('@', "").orEmpty()
        val address = parseAuthority(addressPart)
        if (address.first.isBlank()) return null
        val name = fragment.ifBlank { "${address.first}:${address.second}" }
        return profile(name, "shadowsocks", address.first, address.second, line)
    }

    private fun parseShadowsocksR(line: String): ProxyProfile? {
        val fragment = line.substringAfter('#', "").takeIf { it.isNotBlank() }?.let(::decodeComponent).orEmpty()
        val payload = line.substringAfter("ssr://", "").substringBefore('#')
        val decoded = decodeBase64(payload) ?: return null
        val endpoint = decoded.substringBefore('/').split(':')
        val host = endpoint.getOrNull(0).orEmpty()
        val port = endpoint.getOrNull(1)?.toIntOrNull() ?: 0
        if (host.isBlank()) return null
        return profile(fragment.ifBlank { "$host:$port" }, "shadowsocksr", host, port, line)
    }

    private fun parseTelegram(line: String): ProxyProfile? {
        val uri = runCatching { URI(line) }.getOrNull() ?: return null
        val query = parseQuery(uri.rawQuery.orEmpty())
        val host = query["server"] ?: query["host"] ?: return null
        val port = query["port"]?.toIntOrNull() ?: 443
        return profile("Telegram · $host", "mtproto", host, port, line)
    }

    private fun parseConfig(raw: String): ProfileParseResult {
        val validJson = runCatching {
            if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw)
        }.isSuccess
        if (!validJson) return ProfileParseResult(emptyList(), listOf("Некорректный JSON-конфиг."))
        val name = Regex("\"(?:tag|name|remarks)\"\\s*:\\s*\"([^\"]+)\"")
            .find(raw)?.groupValues?.getOrNull(1).orEmpty()
        val isFullConfig = Regex("\"outbounds\"\\s*:").containsMatchIn(raw)
        val type = if (isFullConfig) "tunnel" else {
            Regex("\"(?:type|protocol)\"\\s*:\\s*\"([^\"]+)\"")
                .find(raw)?.groupValues?.getOrNull(1)?.lowercase().orEmpty()
        }
        val host = Regex("\"(?:server|address|host)\"\\s*:\\s*\"([^\"]+)\"")
            .find(raw)?.groupValues?.getOrNull(1).orEmpty()
        val port = Regex("\"server_port\"\\s*:\\s*(\\d+)")
            .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("\"port\"\\s*:\\s*(\\d+)")
                .find(raw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: 0
        val protocol = when (type) {
            "vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria", "hysteria2", "tuic", "anytls", "wireguard", "ssh" -> type
            "amneziawg" -> "amneziawg"
            else -> "tunnel"
        }
        val profile = profile(name.ifBlank { if (host.isNotBlank()) "$host:$port" else "Custom JSON" }, protocol, host, port, raw)
        return ProfileParseResult(listOf(profile), emptyList())
    }

    private fun parseWireGuardConfig(raw: String): ProxyProfile {
        val amnezia = Regex(
            "(?im)^\\s*(?:Jc|Jmin|Jmax|S[1-4]|H[1-4]|I[1-5]|HeaderProtectionKey|ContentPaddingAddition|RandomTrailers|DisableCookies)\\s*=",
        ).containsMatchIn(raw)
        val protocol = if (amnezia) "amneziawg" else "wireguard"
        return profile(if (amnezia) "AmneziaWG" else "WireGuard", protocol, "", 0, raw)
    }

    private fun parseAuthority(address: String): Pair<String, Int> {
        val parsed = runCatching { URI("https://$address") }.getOrNull()
        val host = parsed?.host.orEmpty()
        val port = parsed?.port?.takeIf { it > 0 } ?: 0
        return host to port
    }

    private fun parseQuery(rawQuery: String): Map<String, String> = rawQuery.split('&')
        .mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val key = part.substringBefore('=').let(::decodeComponent)
            val value = part.substringAfter('=', "").let(::decodeComponent)
            key to value
        }.toMap()

    private fun decodeComponent(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    private fun decodeBase64(value: String): String? {
        val cleaned = value.trim().replace('-', '+').replace('_', '/')
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return runCatching { String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8) }
            .recoverCatching { String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8) }
            .getOrNull()
    }

    private fun hasScheme(value: String): Boolean = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://").containsMatchIn(value)

    private fun looksLikeWireGuardConfig(value: String): Boolean =
        value.contains("[Interface]", ignoreCase = true) && value.contains("PrivateKey", ignoreCase = true)

    private fun defaultPort(protocol: String): Int = when (protocol) {
        "http" -> 8080
        "socks" -> 1080
        else -> 443
    }

    private fun profile(name: String, protocol: String, host: String, port: Int, source: String) =
        ProxyProfile(name = name.ifBlank { ProtocolCatalog.label(protocol) }, protocol = protocol, server = host, port = port, source = source)
}

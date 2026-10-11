package com.goresense.gorebox.data

import java.util.Base64
import java.util.Locale

/** Parsed AmneziaWG .conf data, including the obfuscation parameters consumed by amneziawg-go. */
internal class AmneziaWgConfig private constructor(
    val addresses: List<String>,
    val dnsServers: List<String>,
    val dnsSearchDomains: List<String>,
    val mtu: Int,
    private val interfaceOptions: Map<String, String>,
    private val peers: List<Peer>,
) {
    internal class Peer(
        private val publicKey: String,
        private val presharedKey: String?,
        private val endpoint: String,
        val allowedIps: List<String>,
        private val persistentKeepalive: String?,
    ) {
        fun appendUapi(lines: MutableList<String>, resolveEndpoint: (String) -> String) {
            // public_key marks the start of a peer in WireGuard's userspace API.
            lines += "public_key=$publicKey"
            allowedIps.forEach { lines += "allowed_ip=$it" }
            lines += "endpoint=${resolveEndpoint(endpoint)}"
            persistentKeepalive?.let { lines += "persistent_keepalive_interval=$it" }
            presharedKey?.let { lines += "preshared_key=$it" }
        }
    }

    fun userspaceConfig(resolveEndpoint: (String) -> String): String {
        val lines = mutableListOf("private_key=${interfaceOptions.getValue("private_key")}")
        UAPI_OPTION_ORDER.forEach { key ->
            interfaceOptions[key]?.let { lines += "$key=$it" }
        }
        lines += "replace_peers=true"
        peers.forEach { it.appendUapi(lines, resolveEndpoint) }
        return lines.joinToString(separator = "\n", postfix = "\n")
    }

    fun peerAllowedIps(): List<String> = peers.flatMap { it.allowedIps }.distinct()

    companion object {
        private val IPV4_PATTERN = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
        private val MULTI_VALUE_KEYS = setOf("address", "dns", "allowedips")
        private val UAPI_OPTION_ORDER = listOf(
            "listen_port",
            "jc", "jmin", "jmax",
            "s1", "s2", "s3", "s4",
            "h1", "h2", "h3", "h4",
            "i1", "i2", "i3", "i4", "i5",
            "header_protection_key", "content_padding_addition",
            "rekey_after_time", "rekey_timeout", "reject_after_time",
            "keepalive_timeout", "max_handshake_attempts",
            "random_trailers", "disable_cookies",
        )
        private val UAPI_KEY_BY_CONFIG_KEY = mapOf(
            "listenport" to "listen_port",
            "jc" to "jc", "jmin" to "jmin", "jmax" to "jmax",
            "s1" to "s1", "s2" to "s2", "s3" to "s3", "s4" to "s4",
            "h1" to "h1", "h2" to "h2", "h3" to "h3", "h4" to "h4",
            "i1" to "i1", "i2" to "i2", "i3" to "i3", "i4" to "i4", "i5" to "i5",
            "headerprotectionkey" to "header_protection_key",
            "contentpaddingaddition" to "content_padding_addition",
            "rekeyaftertime" to "rekey_after_time",
            "rekeytimeout" to "rekey_timeout",
            "rejectaftertime" to "reject_after_time",
            "keepalivetimeout" to "keepalive_timeout",
            "maxhandshakeattempts" to "max_handshake_attempts",
            "randomtrailers" to "random_trailers",
            "disablecookies" to "disable_cookies",
        )

        fun parse(source: String): AmneziaWgConfig {
            val sections = parseIni(source)
            val interfaceConfig = sections["interface"]?.firstOrNull()
                ?: throw IllegalArgumentException("В конфигурации AmneziaWG отсутствует секция [Interface].")
            val privateKey = interfaceConfig["privatekey"]
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("В секции [Interface] не указан PrivateKey.")
            val addresses = csv(interfaceConfig["address"])
            require(addresses.isNotEmpty()) { "В секции [Interface] не указан Address." }

            val interfaceOptions = linkedMapOf("private_key" to keyToHex(privateKey, "PrivateKey"))
            interfaceConfig["listenport"]?.takeIf { it.isNotBlank() }?.let {
                val port = it.toIntOrNull()
                require(port != null && port in 0..65535) { "Некорректный ListenPort в AmneziaWG-конфигурации." }
                interfaceOptions["listen_port"] = port.toString()
            }
            interfaceConfig.forEach { (configKey, value) ->
                val uapiKey = UAPI_KEY_BY_CONFIG_KEY[configKey] ?: return@forEach
                if (value.isBlank()) return@forEach
                interfaceOptions[uapiKey] = when (uapiKey) {
                    "header_protection_key" -> keyToHex(value, "HeaderProtectionKey")
                    "random_trailers", "disable_cookies" -> toUapiBoolean(value, configKey)
                    else -> value
                }
            }

            val peerConfigs = sections["peer"].orEmpty().mapIndexed { index, peer ->
                val publicKey = peer["publickey"]?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("В секции [Peer] №${index + 1} не указан PublicKey.")
                val endpoint = peer["endpoint"]?.takeIf { it.isNotBlank() }
                    ?: throw IllegalArgumentException("В секции [Peer] №${index + 1} не указан Endpoint.")
                val allowedIps = csv(peer["allowedips"])
                require(allowedIps.isNotEmpty()) {
                    "В секции [Peer] №${index + 1} не указан AllowedIPs."
                }
                val keepalive = peer["persistentkeepalive"]
                    ?: peer["persistentkeepaliveinterval"]
                Peer(
                    publicKey = keyToHex(publicKey, "PublicKey"),
                    presharedKey = peer["presharedkey"]?.takeIf { it.isNotBlank() && !it.equals("(none)", true) }
                        ?.let { keyToHex(it, "PresharedKey") },
                    endpoint = endpoint,
                    allowedIps = allowedIps,
                    persistentKeepalive = keepalive?.takeIf { it.isNotBlank() && !it.equals("off", true) && it != "0" }
                        ?.also {
                            val seconds = it.toIntOrNull()
                            require(seconds != null && seconds in 1..65535) {
                                "Некорректный PersistentKeepalive в секции [Peer] №${index + 1}."
                            }
                        },
                )
            }
            require(peerConfigs.isNotEmpty()) { "В конфигурации AmneziaWG нет секций [Peer]." }

            val mtu = interfaceConfig["mtu"]?.toIntOrNull() ?: DEFAULT_MTU
            require(mtu in MIN_MTU..MAX_MTU) { "Некорректный MTU в AmneziaWG-конфигурации." }
            val dnsEntries = csv(interfaceConfig["dns"])
            return AmneziaWgConfig(
                addresses = addresses,
                dnsServers = dnsEntries.filter(::looksLikeIpAddress),
                dnsSearchDomains = dnsEntries.filterNot(::looksLikeIpAddress),
                mtu = mtu,
                interfaceOptions = interfaceOptions,
                peers = peerConfigs,
            )
        }

        private fun parseIni(source: String): Map<String, List<Map<String, String>>> {
            val sections = linkedMapOf<String, MutableList<MutableMap<String, String>>>()
            var currentName: String? = null
            source.lineSequence().forEachIndexed { lineNumber, rawLine ->
                val line = rawLine.substringBefore('#').substringBefore(';').trim()
                if (line.isEmpty()) return@forEachIndexed
                if (line.startsWith('[') && line.endsWith(']')) {
                    currentName = line.substring(1, line.length - 1).trim().lowercase(Locale.ROOT)
                    sections.getOrPut(currentName!!) { mutableListOf() }.add(linkedMapOf())
                    return@forEachIndexed
                }
                val sectionName = currentName
                    ?: throw IllegalArgumentException("Некорректная строка ${lineNumber + 1} в AmneziaWG-конфигурации.")
                val separator = line.indexOf('=')
                require(separator > 0) { "Некорректная строка ${lineNumber + 1} в AmneziaWG-конфигурации." }
                val key = line.substring(0, separator).trim().lowercase(Locale.ROOT).replace("_", "")
                val value = line.substring(separator + 1).trim()
                val section = sections.getValue(sectionName).last()
                if (key in MULTI_VALUE_KEYS && section[key].isNullOrBlank()) {
                    section[key] = value
                } else if (key in MULTI_VALUE_KEYS) {
                    section[key] = "${section.getValue(key)},$value"
                } else {
                    section[key] = value
                }
            }
            return sections
        }

        private fun csv(value: String?): List<String> = value.orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)

        private fun keyToHex(value: String, field: String): String {
            val bytes = runCatching { Base64.getDecoder().decode(value.trim()) }
                .recoverCatching { Base64.getUrlDecoder().decode(value.trim()) }
                .getOrElse { throw IllegalArgumentException("Некорректный $field в AmneziaWG-конфигурации.", it) }
            require(bytes.size == 32) { "$field должен содержать ключ длиной 32 байта." }
            val digits = "0123456789abcdef"
            return buildString(bytes.size * 2) {
                bytes.forEach { byte ->
                    val unsigned = byte.toInt() and 0xff
                    append(digits[unsigned ushr 4])
                    append(digits[unsigned and 0x0f])
                }
            }
        }

        private fun toUapiBoolean(value: String, field: String): String = when (value.trim().lowercase(Locale.ROOT)) {
            "1", "true", "yes", "on" -> "1"
            "0", "false", "no", "off" -> "0"
            else -> throw IllegalArgumentException("Некорректное значение $field в AmneziaWG-конфигурации.")
        }

        private fun looksLikeIpAddress(value: String): Boolean =
            value.contains(':') || IPV4_PATTERN.matches(value)

        private const val DEFAULT_MTU = 1280
        private const val MIN_MTU = 576
        private const val MAX_MTU = 9000
    }
}

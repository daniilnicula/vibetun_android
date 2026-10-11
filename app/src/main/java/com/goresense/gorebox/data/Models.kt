package com.goresense.gorebox.data

import java.util.UUID

data class ProxyProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: String,
    val server: String = "",
    val port: Int = 0,
    val source: String,
    val latencyMs: Long? = null,
    val favorite: Boolean = false,
) {
    val protocolLabel: String
        get() = ProtocolCatalog.label(protocol)

    val endpoint: String
        get() = when {
            server.isNotBlank() && port > 0 -> "$server:$port"
            server.isNotBlank() -> server
            protocol.equals("tunnel", ignoreCase = true) -> "Локальный JSON-конфиг"
            protocol.equals("wireguard", ignoreCase = true) || protocol.equals("amneziawg", ignoreCase = true) -> "Конфигурация WireGuard"
            else -> "Сервер не указан"
        }
}

data class ProfileParseResult(
    val profiles: List<ProxyProfile>,
    val errors: List<String>,
)

data class InstalledApp(
    val packageName: String,
    val label: String,
)

enum class AppTab(val label: String) {
    Profiles("Профили"),
    Routing("Маршруты"),
    Settings("Настройки"),
}

enum class ProxyMode(val label: String, val description: String) {
    AllApps("Все приложения", "Маршрутизировать весь трафик устройства через VPN."),
    SelectedApps("Выбранные приложения", "Применять прокси только к отмеченным приложениям."),
}

enum class ConnectionState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR,
}

object ProtocolCatalog {
    val ids = listOf(
        "vless", "vmess", "trojan", "shadowsocks", "socks", "http",
        "hysteria2", "hysteria", "tuic", "anytls", "wireguard",
        "amneziawg", "ssh", "mtproto", "tunnel",
    )

    fun label(id: String): String = when (id.lowercase()) {
        "vless" -> "VLESS"
        "vmess" -> "VMess"
        "trojan" -> "Trojan"
        "shadowsocks" -> "Shadowsocks"
        "shadowsocksr" -> "ShadowsocksR"
        "socks" -> "SOCKS"
        "http" -> "HTTP"
        "hysteria" -> "Hysteria"
        "hysteria2" -> "Hysteria 2"
        "tuic" -> "TUIC"
        "anytls" -> "AnyTLS"
        "wireguard" -> "WireGuard"
        "amneziawg" -> "AmneziaWG"
        "ssh" -> "SSH"
        "mtproto" -> "MTProto"
        "tunnel" -> "Custom JSON"
        else -> id.uppercase()
    }

    fun badge(id: String): String = when (id.lowercase()) {
        "vless" -> "VL"
        "vmess" -> "VM"
        "trojan" -> "TR"
        "shadowsocks" -> "SS"
        "shadowsocksr" -> "SR"
        "socks" -> "SK"
        "http" -> "HT"
        "hysteria", "hysteria2" -> "HY"
        "tuic" -> "TU"
        "anytls" -> "AT"
        "wireguard" -> "WG"
        "amneziawg" -> "AW"
        "ssh" -> "SH"
        "mtproto" -> "TG"
        "tunnel" -> "{}"
        else -> id.take(2).uppercase()
    }

    /** Protocols requiring the extended sing-box build in the Windows client. */
    fun needsExtendedCore(id: String): Boolean = id.equals("amneziawg", ignoreCase = true)

    fun hint(id: String): String? = when (id.lowercase()) {
        "mtproto" -> "MTProto рассчитан на Telegram и не подключается как общий TUN-прокси."
        "amneziawg" -> "Для AmneziaWG запускается встроенное AmneziaWG-ядро с поддержкой параметров обфускации."
        "anytls" -> "AnyTLS можно импортировать, но он отсутствует в используемой Android-версии sing-box."
        "shadowsocksr" -> "SSR можно импортировать, но используемое sing-box-ядро его не поддерживает."
        "tunnel" -> "Пользовательский sing-box JSON передаётся в ядро; конфигу нужен валидный outbound."
        else -> null
    }
}

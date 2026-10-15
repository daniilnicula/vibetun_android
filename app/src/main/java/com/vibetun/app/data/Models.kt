package com.vibetun.app.data

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

/**
 * Маршрут приложений по умолчанию: PROXY — все приложения идут через VPN, кроме отмеченных
 * DIRECT; DIRECT — все идут мимо VPN, кроме отмеченных PROXY. Для приложений через прокси
 * домены/IP из раздела «Домены и IP» действуют поверх: домен DIRECT пойдёт напрямую и т.д.
 */
enum class AppDefaultRoute(val label: String) {
    PROXY("Proxy"),
    DIRECT("Direct"),
}

enum class ConnectionState {
    STOPPED,
    STARTING,
    RUNNING,

    /**
     * Остановка запрошена, служба закрывает ядро и TUN. Состояние пишет служба (и UI — сразу после
     * запроса остановки), а STOPPED приходит только от службы: иначе UI показывал бы «Отключено»,
     * пока VPN-ключ в шторке и туннель ещё живы.
     */
    STOPPING,
    ERROR,
}

/** Что делает тап по кнопке «Подключить/Отключить» или по плитке быстрых настроек. */
enum class ConnectionAction { CONNECT, STOP, IGNORE }

/**
 * Тап не должен запускать подключение, пока идёт остановка или запуск: иначе stop и start
 * гоняются между собой, и запоздавшая остановка убивает только что запущенную службу
 * (в логах это выглядело как вечное «Подключение» до таймаута 60 с).
 */
fun ConnectionState.actionOnTap(): ConnectionAction = when (this) {
    ConnectionState.RUNNING -> ConnectionAction.STOP
    ConnectionState.STARTING, ConnectionState.STOPPING -> ConnectionAction.IGNORE
    ConnectionState.STOPPED, ConnectionState.ERROR -> ConnectionAction.CONNECT
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

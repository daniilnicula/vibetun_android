package com.vibetun.app.service

/**
 * VPN-служба для профилей AmneziaWG. Живёт в отдельном процессе `:vpn_awg`
 * (см. AndroidManifest), потому что рантайм AmneziaWG (libwg-go.so) и sing-box (libbox.so)
 * — два разных Go-runtime, которые нельзя держать в одном процессе.
 */
class VibeTunAwgVpnService : VibeTunVpnService() {
    override val engineFamily: String get() = FAMILY_AWG
}

package com.goresense.gorebox.service

/**
 * VPN-служба для профилей AmneziaWG. Живёт в отдельном процессе `:vpn_awg`
 * (см. AndroidManifest), потому что рантайм AmneziaWG (libwg-go.so) и sing-box (libbox.so)
 * — два разных Go-runtime, которые нельзя держать в одном процессе.
 */
class GoreBoxAwgVpnService : GoreBoxVpnService() {
    override val engineFamily: String get() = FAMILY_AWG
}

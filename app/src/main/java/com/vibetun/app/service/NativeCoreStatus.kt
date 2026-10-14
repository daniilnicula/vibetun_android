package com.vibetun.app.service

/** Runtime guard for the gomobile sing-box library bundled into the APK. */
object NativeCoreStatus {
    /** Версия форка sing-box, собранного в libbox.aar (см. core/sing-box). */
    const val SING_BOX_VERSION = "1.9.7-neko-1"

    val isIntegrated: Boolean
        get() = runCatching { Class.forName("io.nekohasekai.libbox.Libbox") }.isSuccess

    val message: String
        get() = if (isIntegrated) {
            "Ядро sing-box 1.9.7 собрано для Android и подключено к VPN/TUN-службе VibeTun."
        } else {
            "В этой сборке нет Android-ядра sing-box. Выполните scripts/build-libbox-android.sh перед сборкой APK."
        }
}

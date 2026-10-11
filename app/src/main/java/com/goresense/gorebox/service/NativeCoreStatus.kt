package com.goresense.gorebox.service

/** Runtime guard for the gomobile sing-box library bundled into the APK. */
object NativeCoreStatus {
    val isIntegrated: Boolean
        get() = runCatching { Class.forName("io.nekohasekai.libbox.Libbox") }.isSuccess

    val message: String
        get() = if (isIntegrated) {
            "Ядро sing-box 1.9.7 собрано для Android и подключено к VPN/TUN-службе GoreBox."
        } else {
            "В этой сборке нет Android-ядра sing-box. Выполните scripts/build-libbox-android.sh перед сборкой APK."
        }
}

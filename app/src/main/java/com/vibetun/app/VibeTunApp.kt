package com.vibetun.app

import android.app.Application
import com.vibetun.app.data.AppLog
import com.vibetun.app.data.NetworkPersona
import com.vibetun.app.data.VibeTunStore

class VibeTunApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i("App", "VibeTun запущен")
        // сетевой облик (User-Agent): режим из настроек, свежая версия браузера раз в сутки, фоном и молча
        NetworkPersona.init(this, VibeTunStore(this).personaMode)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                AppLog.e("Crash", "Необработанное исключение в потоке ${thread.name}", throwable)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}

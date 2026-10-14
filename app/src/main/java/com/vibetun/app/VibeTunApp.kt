package com.vibetun.app

import android.app.Application
import com.vibetun.app.data.AppLog

class VibeTunApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i("App", "VibeTun запущен")
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                AppLog.e("Crash", "Необработанное исключение в потоке ${thread.name}", throwable)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}

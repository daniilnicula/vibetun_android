package com.goresense.gorebox

import android.app.Application
import com.goresense.gorebox.data.AppLog

class GoreBoxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        AppLog.i("App", "GoreBox запущен")
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                AppLog.e("Crash", "Необработанное исключение в потоке ${thread.name}", throwable)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}

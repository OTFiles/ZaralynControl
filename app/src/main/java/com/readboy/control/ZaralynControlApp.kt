package com.readboy.control

import android.app.Application
import com.readboy.control.db.MirrorDatabase

class ZaralynControlApp : Application() {

    lateinit var database: MirrorDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = MirrorDatabase.getInstance(this)
        AppLogger.i("ZaralynControl 初始化完成")
    }

    companion object {
        @Volatile
        lateinit var instance: ZaralynControlApp
            private set
    }
}
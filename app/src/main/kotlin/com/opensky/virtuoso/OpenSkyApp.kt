package com.opensky.virtuoso

import android.app.Application
import com.opensky.virtuoso.di.AppContainer

class OpenSkyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

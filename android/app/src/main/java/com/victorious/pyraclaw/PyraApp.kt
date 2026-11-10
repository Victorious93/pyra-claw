package com.victorious.pyraclaw

import android.app.Application
import android.content.Context

class PyraApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Context.container: AppContainer get() = (applicationContext as PyraApp).container

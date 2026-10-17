package com.resqroute.app

import android.app.Application

class ResQRouteApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Start loading now, but strictly off the Main thread: the container
        // only launches background work; nothing is parsed here.
        container.start()
    }
}

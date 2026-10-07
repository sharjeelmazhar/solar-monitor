package com.solarmonitor.app

import android.app.Application
import com.solarmonitor.app.data.Prefs
import com.solarmonitor.app.data.Repository
import com.solarmonitor.app.notify.Alerts

class SolarApp : Application() {
    lateinit var repo: Repository
        private set

    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        repo = Repository(this, prefs)
        val alerts = Alerts(this, prefs)
        alerts.createChannels()
        repo.onReading = alerts::onReading
    }
}

val android.content.Context.repo: Repository get() = (applicationContext as SolarApp).repo

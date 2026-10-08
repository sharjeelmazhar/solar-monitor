package com.solarmonitor.app

import android.app.Application
import com.solarmonitor.app.data.Prefs
import com.solarmonitor.app.data.Repository
import com.solarmonitor.app.notify.Alerts

class SolarApp : Application() {
    lateinit var repo: Repository
        private set
    lateinit var alerts: Alerts
        private set

    override fun onCreate() {
        super.onCreate()
        val prefs = Prefs(this)
        com.solarmonitor.app.ui.hour12 = prefs.value.hour12
        repo = Repository(this, prefs)
        alerts = Alerts(this, prefs)
        alerts.createChannels()
        alerts.noBattery = { repo.info.value?.rated?.battV == 0.0 }
        repo.onReading = alerts::onReading
    }
}

val android.content.Context.repo: Repository get() = (applicationContext as SolarApp).repo
val android.content.Context.alerts: Alerts get() = (applicationContext as SolarApp).alerts

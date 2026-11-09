package com.androiddream.callrelay

import android.app.Application
import com.androiddream.callrelay.work.Scheduler

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        Scheduler.ensureScheduled(this)
    }
}

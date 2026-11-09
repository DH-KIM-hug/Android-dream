package com.androiddream.callrelay.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.androiddream.callrelay.data.Settings
import com.androiddream.callrelay.work.Scheduler

/** 통화가 끝나면(IDLE) 녹음 파일 저장을 기다렸다가 검사한다. */
class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!Settings(context).autoEnabled) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            Scheduler.scanSoon(context, 20)
            Scheduler.scanFollowUp(context, 90)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.ensureScheduled(context)
    }
}

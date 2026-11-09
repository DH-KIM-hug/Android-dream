package com.androiddream.callrelay

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.androiddream.callrelay.ui.MainActivity

object Notifier {
    private const val CH_RESULT = "result"
    private const val CH_ERROR = "error"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RESULT, "전송 완료", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ERROR, "전송 오류", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun sent(context: Context, contact: String, channels: String) =
        post(context, CH_RESULT, "통화녹음 전송 완료", "$contact → $channels", android.R.drawable.stat_sys_upload_done)

    fun error(context: Context, message: String) =
        post(context, CH_ERROR, "통화녹음 전달 오류", message, android.R.drawable.stat_notify_error)

    private fun post(context: Context, channel: String, title: String, text: String, icon: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }
}

package com.lonelytragedy.r1999trackerapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BannerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        val title = intent.getStringExtra("title") ?: ctx.getString(R.string.banner_notif_title)
        val key = intent.getStringExtra("key")

        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                "banners",
                ctx.getString(R.string.banner_notif_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
        )

        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val n = Notification.Builder(ctx, "banners")
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(("banner_$key").hashCode(), n)

        val prefs = ctx.getSharedPreferences("banners", Context.MODE_PRIVATE)
        val set = HashSet(prefs.getStringSet("notified", emptySet()) ?: emptySet())
        if (key != null) set.add(key)
        prefs.edit().putStringSet("notified", set).apply()
    }
}

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
        val kind = intent.getStringExtra("kind") ?: return
        val at = intent.getLongExtra("at", 0L)
        if (at <= 0L) return
        BannerScheduler.markNotified(ctx, kind, at)
        if (System.currentTimeMillis() - at > 6 * 3600_000L) return
        val (title, text) = BannerScheduler.eventText(ctx, kind, at) ?: return

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
            .setSmallIcon(R.drawable.ic_notif_banner)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify("banner_$kind:$at".hashCode(), n)
    }
}

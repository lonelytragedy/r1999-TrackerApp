package com.lonelytragedy.r1999trackerapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.RemoteViews
import org.json.JSONTokener
import java.util.Calendar
import java.util.TimeZone

class BannerWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_REFRESH = "com.lonelytragedy.r1999trackerapp.WIDGET_REFRESH"
        private const val RESET_HOUR_UTC = 10

        fun pushData(ctx: Context, raw: String?) {
            if (raw == null) return
            val value = try { JSONTokener(raw).nextValue() } catch (_: Exception) { return }
            val arrStr = when (value) {
                is String -> value
                is org.json.JSONArray -> value.toString()
                else -> return
            }
            ctx.getSharedPreferences("banners", Context.MODE_PRIVATE)
                .edit().putString("widget", arrStr).apply()
            refresh(ctx)
        }

        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, BannerWidgetProvider::class.java))
            if (ids.isEmpty()) return
            for (id in ids) mgr.updateAppWidget(id, buildRoot(ctx, id))
            mgr.notifyAppWidgetViewDataChanged(ids, R.id.widgetList)
        }

        fun nextResetMillis(now: Long): Long {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.timeInMillis = now
            cal.set(Calendar.HOUR_OF_DAY, RESET_HOUR_UTC)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            var reset = cal.timeInMillis
            if (reset <= now) reset += AlarmManager.INTERVAL_DAY
            return reset
        }

        private fun buildRoot(ctx: Context, widgetId: Int): RemoteViews {
            val rv = RemoteViews(ctx.packageName, R.layout.widget_banner)

            val now = System.currentTimeMillis()
            val reset = nextResetMillis(now)
            val base = SystemClock.elapsedRealtime() + (reset - now)
            rv.setChronometer(R.id.resetTimer, base, null, true)
            rv.setChronometerCountDown(R.id.resetTimer, true)

            val svc = Intent(ctx, BannerWidgetService::class.java)
            svc.data = Uri.parse("widget://banners/$widgetId")
            rv.setRemoteAdapter(R.id.widgetList, svc)
            rv.setEmptyView(R.id.widgetList, R.id.widgetEmpty)

            val open = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val openPi = PendingIntent.getActivity(
                ctx, 0, open,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            rv.setOnClickPendingIntent(R.id.widgetHeader, openPi)
            rv.setPendingIntentTemplate(R.id.widgetList, openPi)

            return rv
        }

        private fun scheduleResetRefresh(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, 91,
                Intent(ctx, BannerWidgetProvider::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val at = nextResetMillis(System.currentTimeMillis()) + 2000
            am.set(AlarmManager.RTC, at, pi)
        }

        private fun cancelResetRefresh(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(
                PendingIntent.getBroadcast(
                    ctx, 91,
                    Intent(ctx, BannerWidgetProvider::class.java).setAction(ACTION_REFRESH),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        for (id in ids) mgr.updateAppWidget(id, buildRoot(ctx, id))
        mgr.notifyAppWidgetViewDataChanged(ids, R.id.widgetList)
        scheduleResetRefresh(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) {
            refresh(ctx)
            scheduleResetRefresh(ctx)
        }
    }

    override fun onEnabled(ctx: Context) {
        scheduleResetRefresh(ctx)
    }

    override fun onDisabled(ctx: Context) {
        cancelResetRefresh(ctx)
    }
}

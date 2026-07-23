package com.lonelytragedy.r1999trackerapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONTokener

object BannerScheduler {

    fun schedule(ctx: Context, raw: String?) {
        if (raw == null) return
        ctx.getSharedPreferences("banners", Context.MODE_PRIVATE)
            .edit().putString("schedule", raw).apply()
        register(ctx, raw)
    }

    fun reschedule(ctx: Context) {
        val raw = ctx.getSharedPreferences("banners", Context.MODE_PRIVATE)
            .getString("schedule", null) ?: return
        register(ctx, raw)
    }

    private fun register(ctx: Context, raw: String) {
        try {
            val value = JSONTokener(raw).nextValue()
            val arr = when (value) {
                is String -> JSONArray(value)
                is JSONArray -> value
                else -> return
            }
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val prefs = ctx.getSharedPreferences("banners", Context.MODE_PRIVATE)
            val notified = prefs.getStringSet("notified", emptySet()) ?: emptySet()
            val now = System.currentTimeMillis()
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

            for (i in 0 until arr.length()) {
                val group = arr.getJSONObject(i)
                val at = group.getLong("at")
                if (at <= now) continue
                val key = at.toString()
                if (notified.contains(key)) continue

                val (title, text) = buildText(ctx, group.getJSONArray("banners"))
                val intent = Intent(ctx, BannerAlarmReceiver::class.java).apply {
                    putExtra("key", key)
                    putExtra("title", title)
                    putExtra("text", text)
                }
                val pi = PendingIntent.getBroadcast(
                    ctx, (at / 60000).toInt(), intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                if (exact) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun buildText(ctx: Context, banners: JSONArray): Pair<String, String> {
        val lines = ArrayList<String>()
        for (i in 0 until banners.length()) {
            val b = banners.getJSONObject(i)
            val name = b.getString("name")
            val r6 = b.optJSONArray("rate6")
            val chars = if (r6 != null && r6.length() > 0) {
                (0 until r6.length()).joinToString(", ") { r6.getString(it) }
            } else ""
            lines.add(if (chars.isEmpty()) name else "$name — $chars")
        }
        val title = ctx.getString(
            if (lines.size > 1) R.string.banner_notif_title_multi else R.string.banner_notif_title
        )
        return title to lines.joinToString("\n")
    }
}

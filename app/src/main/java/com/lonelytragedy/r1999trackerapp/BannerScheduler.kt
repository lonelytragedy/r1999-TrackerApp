package com.lonelytragedy.r1999trackerapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object BannerScheduler {
    const val KIND_START = "start"
    const val KIND_END = "end"
    private const val ACTION_REMIND = "com.lonelytragedy.r1999trackerapp.BANNER_REMINDER"
    private const val END_NOTICE_MS = 24 * 3600_000L
    private const val WEEK_MS = 7 * 24 * 3600_000L
    private val END_TYPES = setOf("Limited", "Collab", "Character", "Special")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("banners", Context.MODE_PRIVATE)

    fun remindNew(ctx: Context) = prefs(ctx).getBoolean("remind_new", true)
    fun remindEnd(ctx: Context) = prefs(ctx).getBoolean("remind_end", true)

    fun setReminders(ctx: Context, newOn: Boolean, endOn: Boolean) {
        prefs(ctx).edit().putBoolean("remind_new", newOn).putBoolean("remind_end", endOn).apply()
        reschedule(ctx)
    }

    fun update(ctx: Context, raw: String?) {
        val arr = parse(raw) ?: return
        prefs(ctx).edit().putString("all", arr.toString()).apply()
        pushWidget(ctx, arr)
        reschedule(ctx)
    }

    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val p = prefs(ctx)
        cancelLegacy(ctx, am)
        for (key in p.getStringSet("scheduled", emptySet()) ?: emptySet()) {
            val (kind, at) = splitKey(key) ?: continue
            am.cancel(pending(ctx, kind, at))
        }

        val now = System.currentTimeMillis()
        val notified = (p.getStringSet("notified", emptySet()) ?: emptySet())
            .filter { k -> (splitKey(k)?.second ?: 0L) > now - WEEK_MS }.toSet()
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        val scheduled = HashSet<String>()
        for ((kind, at) in events(ctx, now)) {
            val key = "$kind:$at"
            if (notified.contains(key)) continue
            val pi = pending(ctx, kind, at)
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            scheduled.add(key)
        }
        p.edit().putStringSet("scheduled", scheduled).putStringSet("notified", notified).apply()
    }

    fun eventText(ctx: Context, kind: String, at: Long): Pair<String, String>? {
        if (kind == KIND_START && !remindNew(ctx)) return null
        if (kind == KIND_END && !remindEnd(ctx)) return null
        val banners = banners(ctx).filter { triggerAt(it, kind) == at }
        if (banners.isEmpty()) return null
        val lines = banners.map { b ->
            val chars = if (b.optString("type") == "Water") "" else joinRate(b.optJSONArray("rate6"))
            val name = b.optString("name")
            if (chars.isEmpty()) name else "$name — $chars"
        }
        val multi = lines.size > 1
        val title = ctx.getString(
            when {
                kind == KIND_END && multi -> R.string.banner_end_title_multi
                kind == KIND_END -> R.string.banner_end_title
                multi -> R.string.banner_notif_title_multi
                else -> R.string.banner_notif_title
            }
        )
        return title to lines.joinToString("\n")
    }

    fun markNotified(ctx: Context, kind: String, at: Long) {
        val p = prefs(ctx)
        val set = HashSet(p.getStringSet("notified", emptySet()) ?: emptySet())
        set.add("$kind:$at")
        p.edit().putStringSet("notified", set).apply()
    }

    private fun events(ctx: Context, now: Long): Set<Pair<String, Long>> {
        val out = HashSet<Pair<String, Long>>()
        for (b in banners(ctx)) {
            if (remindNew(ctx)) triggerAt(b, KIND_START)?.takeIf { it > now }?.let { out.add(KIND_START to it) }
            if (remindEnd(ctx)) triggerAt(b, KIND_END)?.takeIf { it > now }?.let { out.add(KIND_END to it) }
        }
        return out
    }

    private fun triggerAt(b: JSONObject, kind: String): Long? {
        val start = b.optLong("start")
        val end = b.optLong("end")
        return when (kind) {
            KIND_START -> start
            KIND_END -> if (b.optString("type") in END_TYPES && end - END_NOTICE_MS > start) end - END_NOTICE_MS else null
            else -> null
        }
    }

    private fun banners(ctx: Context): List<JSONObject> {
        val raw = prefs(ctx).getString("all", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun pushWidget(ctx: Context, arr: JSONArray) {
        val now = System.currentTimeMillis()
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val b = arr.getJSONObject(i)
            if (b.optLong("end") <= now) continue
            out.put(JSONObject().apply {
                put("name", b.optString("name"))
                put("type", b.optString("type"))
                put("rate", b.optJSONArray("rate") ?: JSONArray())
                put("image", b.optString("image"))
                put("start", b.optLong("start"))
                put("end", b.optLong("end"))
            })
        }
        BannerWidgetProvider.pushData(ctx, out.toString())
    }

    private fun parse(raw: String?): JSONArray? {
        if (raw == null) return null
        return try {
            when (val v = JSONTokener(raw).nextValue()) {
                is String -> JSONArray(v)
                is JSONArray -> v
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun joinRate(arr: JSONArray?): String =
        if (arr == null) "" else (0 until arr.length()).joinToString(", ") { arr.getString(it) }

    private fun splitKey(key: String): Pair<String, Long>? {
        val i = key.indexOf(':')
        if (i <= 0) return null
        val at = key.substring(i + 1).toLongOrNull() ?: return null
        return key.substring(0, i) to at
    }

    private fun pending(ctx: Context, kind: String, at: Long): PendingIntent {
        val intent = Intent(ctx, BannerAlarmReceiver::class.java)
            .setAction(ACTION_REMIND)
            .setData(Uri.parse("reminder://$kind/$at"))
            .putExtra("kind", kind)
            .putExtra("at", at)
        return PendingIntent.getBroadcast(
            ctx, "$kind:$at".hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun cancelLegacy(ctx: Context, am: AlarmManager) {
        val p = prefs(ctx)
        val legacy = p.getString("schedule", null) ?: return
        try {
            val arr = parse(legacy) ?: JSONArray()
            for (i in 0 until arr.length()) {
                val at = arr.getJSONObject(i).optLong("at")
                val pi = PendingIntent.getBroadcast(
                    ctx, (at / 60000).toInt(), Intent(ctx, BannerAlarmReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE
                )
                if (pi != null) { am.cancel(pi); pi.cancel() }
            }
        } catch (_: Exception) {
        }
        p.edit().remove("schedule").apply()
    }
}

package com.lonelytragedy.r1999trackerapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class BannerWidgetFactory(private val ctx: Context) : RemoteViewsService.RemoteViewsFactory {

    private data class Row(
        val name: String,
        val type: String,
        val rate: List<String>,
        val image: String,
        val start: Long,
        val end: Long
    )

    private val imageBase = "https://lonelytragedy.github.io/r1999-tracker/"
    private var rows: List<Row> = emptyList()

    override fun onCreate() {}

    override fun onDataSetChanged() {
        val raw = ctx.getSharedPreferences("banners", Context.MODE_PRIVATE)
            .getString("widget", null) ?: run { rows = emptyList(); return }
        val now = System.currentTimeMillis()
        val parsed = ArrayList<Row>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val end = o.optLong("end")
                if (end <= now) continue
                val r = ArrayList<String>()
                o.optJSONArray("rate")?.let { for (j in 0 until it.length()) r.add(it.getString(j)) }
                parsed.add(
                    Row(
                        o.optString("name"),
                        o.optString("type"),
                        r,
                        o.optString("image"),
                        o.optLong("start"),
                        end
                    )
                )
            }
        } catch (_: Exception) {
        }
        parsed.sortWith(Comparator { a, b ->
            val aActive = a.start <= now
            val bActive = b.start <= now
            if (aActive != bActive) return@Comparator if (aActive) -1 else 1
            val ka = if (aActive) a.end else a.start
            val kb = if (bActive) b.end else b.start
            ka.compareTo(kb)
        })
        rows = parsed
    }

    override fun onDestroy() {}

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_banner_item)
        val row = rows.getOrNull(position) ?: return rv
        val now = System.currentTimeMillis()
        val active = row.start <= now

        rv.setInt(
            R.id.itemRoot, "setBackgroundResource",
            if (active) R.drawable.widget_item_bg else R.drawable.widget_item_bg_upcoming
        )

        rv.setTextViewText(R.id.itemType, row.type)
        rv.setInt(R.id.itemType, "setBackgroundResource", badgeRes(row.type))
        rv.setTextColor(R.id.itemType, ContextCompat.getColor(ctx, typeColor(row.type)))

        rv.setTextViewText(R.id.itemName, row.name)

        if (row.rate.isEmpty()) {
            rv.setViewVisibility(R.id.itemStar, View.GONE)
            rv.setViewVisibility(R.id.itemRate, View.GONE)
            rv.setViewVisibility(R.id.itemPlus, View.GONE)
        } else {
            val (text, overflow) = rateDisplay(row.rate)
            rv.setViewVisibility(R.id.itemStar, View.VISIBLE)
            rv.setViewVisibility(R.id.itemRate, View.VISIBLE)
            rv.setTextViewText(R.id.itemRate, text)
            if (overflow > 0) {
                rv.setViewVisibility(R.id.itemPlus, View.VISIBLE)
                rv.setTextViewText(R.id.itemPlus, "+$overflow")
            } else {
                rv.setViewVisibility(R.id.itemPlus, View.GONE)
            }
        }

        if (active) {
            val remain = row.end - now
            rv.setTextViewText(R.id.itemTimeLabel, ctx.getString(R.string.widget_ends_in))
            rv.setTextViewText(R.id.itemTimeValue, formatDuration(remain))
            val color = if (remain < 2L * 86400000L) R.color.widget_time_soon else R.color.widget_time_active
            rv.setTextColor(R.id.itemTimeValue, ContextCompat.getColor(ctx, color))
        } else {
            rv.setTextViewText(R.id.itemTimeLabel, ctx.getString(R.string.widget_starts_in))
            rv.setTextViewText(R.id.itemTimeValue, formatDuration(row.start - now))
            rv.setTextColor(R.id.itemTimeValue, ContextCompat.getColor(ctx, R.color.widget_time_start))
        }

        val bmp = art(row.image)
        if (bmp != null) rv.setImageViewBitmap(R.id.itemArt, bmp)
        else rv.setViewVisibility(R.id.itemArt, View.GONE)

        rv.setOnClickFillInIntent(R.id.itemRoot, android.content.Intent())
        return rv
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true

    private fun rateDisplay(list: List<String>): Pair<String, Int> {
        val budget = 30
        val sb = StringBuilder()
        var count = 0
        for (n in list) {
            val candidate = if (sb.isEmpty()) n else "$sb, $n"
            if (count > 0 && candidate.length > budget) break
            if (sb.isEmpty()) sb.append(n) else sb.append(", ").append(n)
            count++
        }
        return sb.toString() to (list.size - count)
    }

    private fun formatDuration(ms: Long): String {
        val total = if (ms < 0) 0 else ms
        val mins = total / 60000
        val days = mins / 1440
        val hours = (mins % 1440) / 60
        val minutes = mins % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    private fun badgeRes(type: String): Int = when (type) {
        "Collab" -> R.drawable.badge_collab
        "Water" -> R.drawable.badge_water
        "Character" -> R.drawable.badge_character
        "Limited" -> R.drawable.badge_limited
        "Regular" -> R.drawable.badge_regular
        "Special" -> R.drawable.badge_special
        else -> R.drawable.badge_default
    }

    private fun typeColor(type: String): Int = when (type) {
        "Collab" -> R.color.type_collab
        "Water" -> R.color.type_water
        "Character" -> R.color.type_character
        "Limited" -> R.color.type_limited
        "Regular" -> R.color.type_regular
        "Special" -> R.color.type_special
        else -> R.color.widget_gold
    }

    private fun art(image: String): Bitmap? {
        if (image.isBlank()) return null
        val fname = image.substringAfterLast('/')
        if (fname.isBlank()) return null
        val dir = File(ctx.filesDir, "wbanner").apply { mkdirs() }
        val file = File(dir, fname)
        if (!file.exists() || file.length() == 0L) download(imageBase + image, file)
        if (!file.exists() || file.length() == 0L) return null
        val src = try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Throwable) {
            null
        } ?: return null
        return try {
            render(src)
        } catch (_: Throwable) {
            null
        } finally {
            src.recycle()
        }
    }

    private fun render(src: Bitmap): Bitmap {
        val w = 600
        val h = 80
        val radius = 22f
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val clip = Path().apply {
            addRoundRect(
                rect,
                floatArrayOf(0f, 0f, 0f, 0f, radius, radius, radius, radius),
                Path.Direction.CW
            )
        }
        canvas.clipPath(clip)
        val srcRatio = src.width.toFloat() / src.height
        val dstRatio = w.toFloat() / h
        val m = Matrix()
        if (srcRatio > dstRatio) {
            val scale = h.toFloat() / src.height
            m.setScale(scale, scale)
            m.postTranslate((w - src.width * scale) / 2f, 0f)
        } else {
            val scale = w.toFloat() / src.width
            m.setScale(scale, scale)
            m.postTranslate(0f, (h - src.height * scale) / 2f)
        }
        canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        val scrim = Paint().apply {
            shader = LinearGradient(
                0f, 0f, w * 0.5f, 0f,
                intArrayOf(0xE00A0703.toInt(), 0x590A0703, 0x000A0703),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, scrim)
        return out
    }

    private fun download(url: String, dest: File) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "R1999Tracker")
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            conn.connect()
            if (conn.responseCode != 200) return
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            if (tmp.length() > 0) tmp.renameTo(dest) else tmp.delete()
        } catch (_: Throwable) {
        }
    }
}

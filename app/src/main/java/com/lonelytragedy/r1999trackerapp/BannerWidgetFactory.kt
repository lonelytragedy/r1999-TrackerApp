package com.lonelytragedy.r1999trackerapp

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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

class BannerWidgetFactory(private val ctx: Context, intent: Intent) : RemoteViewsService.RemoteViewsFactory {

    private data class Row(
        val name: String,
        val type: String,
        val rate: List<String>,
        val image: String,
        val start: Long,
        val end: Long
    )

    private val imageBase = "https://lonelytragedy.github.io/r1999-tracker/"
    private val widgetId = intent.data?.lastPathSegment?.toIntOrNull() ?: AppWidgetManager.INVALID_APPWIDGET_ID
    private var rows: List<Row> = emptyList()
    private val art = HashMap<String, Bitmap>()
    private var classic = false

    override fun onCreate() {}

    override fun onDataSetChanged() {
        classic = isClassic(ctx)
        val raw = ctx.getSharedPreferences("banners", Context.MODE_PRIVATE).getString("widget", null)
        val now = System.currentTimeMillis()
        val parsed = ArrayList<Row>()
        if (raw != null) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val end = o.optLong("end")
                    if (end <= now) continue
                    val r = ArrayList<String>()
                    o.optJSONArray("rate")?.let { for (j in 0 until it.length()) r.add(it.getString(j)) }
                    parsed.add(Row(o.optString("name"), o.optString("type"), r, o.optString("image"), o.optLong("start"), end))
                }
            } catch (_: Exception) {
            }
        }
        parsed.sortWith(Comparator { a, b ->
            val aActive = a.start <= now
            val bActive = b.start <= now
            if (aActive != bActive) return@Comparator if (aActive) -1 else 1
            (if (aActive) a.end else a.start).compareTo(if (bActive) b.end else b.start)
        })
        rows = parsed

        val (w, h) = cardSize()
        val wanted = HashMap<String, Bitmap>()
        for (row in parsed) {
            val key = artKey(row, w, h)
            val ready = art[key] ?: renderArt(row, w, h)
            if (ready != null) wanted[key] = ready
        }
        for ((k, bmp) in art) if (!wanted.containsKey(k)) bmp.recycle()
        art.clear()
        art.putAll(wanted)
    }

    override fun onDestroy() {
        for (bmp in art.values) bmp.recycle()
        art.clear()
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val rv = RemoteViews(ctx.packageName, R.layout.widget_banner_item)
        val row = rows.getOrNull(position) ?: return rv
        val now = System.currentTimeMillis()
        val active = row.start <= now

        rv.setInt(R.id.itemRoot, "setBackgroundResource", itemBackground(active))

        rv.setTextViewText(R.id.itemType, row.type)
        rv.setTextColor(R.id.itemType, ContextCompat.getColor(ctx, typeColor(row.type)))
        rv.setTextViewText(R.id.itemName, row.name)
        rv.setTextColor(R.id.itemName, color(if (classic) R.color.widget_cool_name else R.color.widget_name))

        if (row.rate.isEmpty()) {
            rv.setViewVisibility(R.id.itemRate, View.GONE)
        } else {
            rv.setViewVisibility(R.id.itemRate, View.VISIBLE)
            rv.setTextViewText(R.id.itemRate, rateText(row.rate))
            rv.setTextColor(R.id.itemRate, color(if (classic) R.color.widget_cool_accent else R.color.widget_rate))
        }

        val muted = color(if (classic) R.color.widget_cool_muted else R.color.widget_muted)
        rv.setTextColor(R.id.itemTimeLabel, muted)
        val valueColor: Int
        if (active) {
            val remain = row.end - now
            rv.setTextViewText(R.id.itemTimeLabel, ctx.getString(R.string.widget_ends_in))
            rv.setTextViewText(R.id.itemTimeValue, formatDuration(remain))
            valueColor = when {
                remain < 86400000L -> color(R.color.widget_time_soon)
                classic -> color(R.color.widget_cool_name)
                else -> color(R.color.widget_name)
            }
        } else {
            rv.setTextViewText(R.id.itemTimeLabel, ctx.getString(R.string.widget_starts_in))
            rv.setTextViewText(R.id.itemTimeValue, formatDuration(row.start - now))
            valueColor = color(if (classic) R.color.widget_cool_accent else R.color.widget_time_start)
        }
        rv.setTextColor(R.id.itemTimeValue, valueColor)
        rv.setInt(R.id.itemClock, "setColorFilter", valueColor)

        val (w, h) = cardSize()
        val bmp = art[artKey(row, w, h)]
        if (bmp != null && !bmp.isRecycled) {
            rv.setViewVisibility(R.id.itemArt, View.VISIBLE)
            rv.setImageViewBitmap(R.id.itemArt, bmp)
        } else {
            rv.setViewVisibility(R.id.itemArt, View.GONE)
        }

        rv.setOnClickFillInIntent(R.id.itemRoot, Intent())
        return rv
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long =
        rows.getOrNull(position)?.let { (it.name + it.start).hashCode().toLong() } ?: position.toLong()
    override fun hasStableIds(): Boolean = true

    private fun color(res: Int): Int = ContextCompat.getColor(ctx, res)

    private fun itemBackground(active: Boolean): Int = when {
        classic && active -> R.drawable.widget_item_bg_classic
        classic -> R.drawable.widget_item_bg_classic_upcoming
        active -> R.drawable.widget_item_bg
        else -> R.drawable.widget_item_bg_upcoming
    }

    private fun rateText(list: List<String>): String {
        val budget = 32
        val sb = StringBuilder()
        var count = 0
        for (n in list) {
            val candidate = if (sb.isEmpty()) n else "$sb, $n"
            if (count > 0 && candidate.length > budget) break
            if (sb.isEmpty()) sb.append(n) else sb.append(", ").append(n)
            count++
        }
        val more = list.size - count
        return "★ " + sb + if (more > 0) "  +$more" else ""
    }

    private fun formatDuration(ms: Long): String {
        val mins = (if (ms < 0) 0 else ms) / 60000
        val days = mins / 1440
        val hours = (mins % 1440) / 60
        val minutes = mins % 60
        val d = ctx.getString(R.string.widget_unit_day)
        val hr = ctx.getString(R.string.widget_unit_hour)
        val m = ctx.getString(R.string.widget_unit_min)
        return when {
            days > 0 -> "$days$d $hours$hr"
            hours > 0 -> "$hours$hr $minutes$m"
            else -> "$minutes$m"
        }
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

    private fun cardSize(): Pair<Int, Int> {
        val dm = ctx.resources.displayMetrics
        val opts = if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
            AppWidgetManager.getInstance(ctx).getAppWidgetOptions(widgetId) else null
        val widthDp = opts?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)?.takeIf { it > 0 } ?: 320
        val cardWpx = ((widthDp - 24) * dm.density).coerceAtLeast(160f)
        val cardHpx = ctx.resources.getDimension(R.dimen.widget_card_height)
        val scale = minOf(1f, 720f / cardWpx)
        return Pair((cardWpx * scale).toInt(), (cardHpx * scale).toInt())
    }

    private fun artKey(row: Row, w: Int, h: Int): String =
        "${row.image}|$w|$h|${row.start <= System.currentTimeMillis()}|$classic"

    private fun renderArt(row: Row, w: Int, h: Int): Bitmap? {
        val file = cachedImage(row.image) ?: return null
        val src = try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Throwable) {
            null
        } ?: return null
        return try {
            compose(src, w, h, row.start <= System.currentTimeMillis())
        } catch (_: Throwable) {
            null
        } finally {
            src.recycle()
        }
    }

    private fun compose(src: Bitmap, w: Int, h: Int, active: Boolean): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val radius = h * 16f / 96f
        canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })

        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val m = Matrix()
        m.setScale(scale, scale)
        m.postTranslate((w - src.width * scale) / 2f, (h - src.height * scale) * 0.3f)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        if (!active) paint.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.35f) })
        canvas.drawBitmap(src, m, paint)

        val base = if (classic) 0x0E1015 else 0x0C0906
        val strong = (if (active) 0xF2 else 0xF8) shl 24 or base
        val mid = (if (active) 0xB8 else 0xD0) shl 24 or base
        val weak = (if (active) 0x1A else 0x60) shl 24 or base
        canvas.drawRect(rect, Paint().apply {
            shader = LinearGradient(0f, 0f, w.toFloat(), 0f, intArrayOf(strong, mid, weak), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        })
        return out
    }

    private fun cachedImage(image: String): File? {
        if (image.isBlank()) return null
        val name = image.substringAfterLast('/')
        if (name.isBlank()) return null
        val dir = File(ctx.filesDir, "wbanner").apply { mkdirs() }
        val file = File(dir, name)
        val miss = File(dir, "$name.missing")
        if (file.exists() && file.length() > 0) return file
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < 6 * 3600_000L) return null
        if (download(imageBase + image, file)) return file
        miss.writeText("")
        return null
    }

    private fun download(url: String, dest: File): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "R1999Tracker")
            conn.connectTimeout = 12000
            conn.readTimeout = 20000
            if (conn.responseCode != 200) return false
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            conn.inputStream.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
            if (tmp.length() > 0 && tmp.renameTo(dest)) true else { tmp.delete(); false }
        } catch (_: Throwable) {
            false
        }
    }

    companion object {
        fun isClassic(ctx: Context): Boolean =
            ctx.getSharedPreferences("app", Context.MODE_PRIVATE).getString("skin", "reversed") == "classic"
    }
}

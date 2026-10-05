package com.lonelytragedy.r1999trackerapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.LinearInterpolator

class LoaderRing @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private val stroke = 3.5f * dp
    private val glowRadius = 9f * dp
    private val accent = themeColor(R.attr.appAccent)
    private val box = RectF()
    private var angle = 0f

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = themeColor(R.attr.appStroke)
        alpha = 110
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke * 2.6f
        strokeCap = Paint.Cap.ROUND
        color = accent
        alpha = 170
        maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL)
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
        color = accent
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke * 0.45f
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
        alpha = 150
    }

    private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            angle = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun themeColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = glowRadius + stroke * 1.5f
        box.set(inset, inset, w - inset, h - inset)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawOval(box, track)
        canvas.drawArc(box, angle - 90f, 100f, false, glow)
        canvas.drawArc(box, angle - 90f, 100f, false, arc)
        canvas.drawArc(box, angle - 60f, 40f, false, core)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateSpin()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateSpin()
    }

    override fun onDetachedFromWindow() {
        spin.cancel()
        super.onDetachedFromWindow()
    }

    private fun updateSpin() {
        if (isShown && isAttachedToWindow) {
            if (!spin.isStarted) spin.start()
        } else {
            spin.cancel()
        }
    }
}

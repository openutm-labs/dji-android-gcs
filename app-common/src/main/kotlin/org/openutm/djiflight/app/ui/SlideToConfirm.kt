package org.openutm.djiflight.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import org.openutm.djiflight.app.R

/**
 * Drag the knob to the end to confirm (DJI's slide to take off or start a route): a gloved tap or a
 * pocket can't trigger it. Releasing early springs back. Accessibility services get a plain click.
 */
@SuppressLint("ViewConstructor")
class SlideToConfirm(context: Context, private val label: String, private val onConfirm: () -> Unit) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.color(R.color.surface2) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.color(R.color.accent); alpha = 90 }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.color(R.color.accent) }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.color(R.color.text); style = Paint.Style.STROKE; strokeWidth = context.dp(3).toFloat(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.color(R.color.text)
        textSize = context.resources.displayMetrics.scaledDensity * 17
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private var offset = 0f
    private var dragging = false
    private var done = false
    private val rect = RectF()
    private val path = Path()

    init {
        isClickable = true
        contentDescription = label
    }

    private val knobSize get() = height.toFloat()
    private val travel get() = (width - knobSize).coerceAtLeast(1f)

    override fun onDraw(c: Canvas) {
        val r = height / 2f
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, r, r, track)
        if (offset > 0) {
            rect.set(0f, 0f, offset + knobSize, height.toFloat())
            c.drawRoundRect(rect, r, r, fillPaint)
        }
        textPaint.alpha = (255 * (1 - offset / travel)).toInt().coerceIn(40, 255)
        c.drawText(label, width / 2f + knobSize / 3, height / 2f - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        val cx = offset + r
        c.drawCircle(cx, r, r - context.dp(4), knob)
        val a = r * 0.28f
        path.reset()
        path.moveTo(cx - a * 0.6f, r - a)
        path.lineTo(cx + a * 0.5f, r)
        path.lineTo(cx - a * 0.6f, r + a)
        c.drawPath(path, arrow)
        if (!isEnabled) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled || done) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = e.x <= offset + knobSize * 1.3f
                if (dragging) parent?.requestDisallowInterceptTouchEvent(true)
                return dragging
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                offset = (e.x - knobSize / 2).coerceIn(0f, travel)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                if (offset >= travel * 0.92f && e.actionMasked == MotionEvent.ACTION_UP) {
                    done = true
                    offset = travel
                    invalidate()
                    onConfirm()
                } else {
                    animate().cancel()
                    springBack()
                }
            }
        }
        return true
    }

    private fun springBack() {
        val start = offset
        val t0 = System.nanoTime()
        postOnAnimation(object : Runnable {
            override fun run() {
                val f = ((System.nanoTime() - t0) / 1.8e8).toFloat().coerceAtMost(1f)
                offset = start * (1 - f)
                invalidate()
                if (f < 1f) postOnAnimation(this)
            }
        })
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.35f
    }

    override fun performClick(): Boolean {
        super.performClick()
        // Only reached from accessibility services (touch handles the slide itself).
        if (isEnabled && !done) { done = true; onConfirm() }
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Button::class.java.name
    }
}

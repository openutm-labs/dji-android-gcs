package org.openutm.djiflight.app.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.openutm.djiflight.app.R

/**
 * The app's small UI kit, plain Android views (no extra libraries; minSdk 23): sizes for gloved
 * fingers (48 dp minimum, 56–72 dp for flight actions), solid surfaces behind all text, and labels
 * on one line so nothing reflows while the pilot reaches for it.
 */
fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())
fun Context.color(id: Int): Int = getColor(id)
fun View.dp(v: Int): Int = context.dp(v)

fun rounded(color: Int, radiusPx: Float, strokePx: Int = 0, strokeColor: Int = 0): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = radiusPx
    setColor(color)
    if (strokePx > 0) setStroke(strokePx, strokeColor)
}

/** Normal and pressed backgrounds; disabled views are dimmed with alpha instead. */
fun pressable(normal: Drawable, pressed: Drawable): StateListDrawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), pressed)
    addState(intArrayOf(), normal)
}

/** Sets text only when it changed: the Fly screen redraws twice a second on slow controllers. */
fun TextView.setIfChanged(s: CharSequence) {
    if (text.toString() != s.toString()) text = s
}

fun View.show(visible: Boolean, keepSpace: Boolean = false) {
    val v = if (visible) View.VISIBLE else if (keepSpace) View.INVISIBLE else View.GONE
    if (visibility != v) visibility = v
}

/** Tabular digits, so readouts don't jitter as values change. */
fun TextView.tabular(): TextView = apply { fontFeatureSettings = "tnum" }

fun Context.text(sizeSp: Float, colorId: Int = R.color.text, bold: Boolean = false): TextView = TextView(this).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color(colorId))
    if (bold) typeface = Typeface.DEFAULT_BOLD
    includeFontPadding = false
}

enum class Kind { PRIMARY, SECONDARY, DANGER, WARN, QUIET }

private fun Context.fill(kind: Kind, pressed: Boolean): Int = when (kind) {
    Kind.PRIMARY -> color(if (pressed) R.color.accent_pressed else R.color.accent)
    Kind.SECONDARY -> color(if (pressed) R.color.surface3 else R.color.surface2)
    Kind.DANGER -> if (pressed) 0xFFB0252A.toInt() else color(R.color.crit)
    Kind.WARN -> if (pressed) 0xFFD08B1A.toInt() else color(R.color.warn)
    Kind.QUIET -> if (pressed) color(R.color.surface2) else 0x00000000
}

fun Context.buttonBackground(kind: Kind, radiusDp: Int = 8): Drawable =
    pressable(rounded(fill(kind, false), dp(radiusDp).toFloat()), rounded(fill(kind, true), dp(radiusDp).toFloat()))

private fun Context.onColor(kind: Kind) = color(if (kind == Kind.WARN) R.color.on_warn else R.color.text)

/**
 * A button with an optional icon and a one-line label. [vertical]: icon over label, for the Fly
 * screen's action rail; otherwise icon beside label.
 */
class ActionButton(context: Context, private val vertical: Boolean = false) : LinearLayout(context) {
    val icon = ImageView(context)
    val label = context.text(if (vertical) 13f else 16f, bold = true).apply {
        // The rail's narrow slots take two lines, for translations longer than English.
        maxLines = if (vertical) 2 else 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER
    }
    var fitLabel: Boolean = true
    var kind: Kind = Kind.SECONDARY
        set(v) { field = v; restyle() }

    init {
        orientation = if (vertical) VERTICAL else HORIZONTAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        minimumHeight = context.dp(if (vertical) 56 else 52)
        val pad = context.dp(if (vertical) 4 else 16)
        setPadding(pad, context.dp(6), pad, context.dp(6))
        val s = context.dp(if (vertical) 26 else 22)
        addView(icon, LayoutParams(s, s).apply { if (vertical) bottomMargin = context.dp(4) else marginEnd = context.dp(8) })
        addView(label, LayoutParams(if (vertical) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        restyle()
    }

    private fun restyle() {
        background = context.buttonBackground(kind)
        val on = context.onColor(kind)
        label.setTextColor(on)
        icon.imageTintList = ColorStateList.valueOf(on)
    }

    fun set(text: String, iconRes: Int?, kind: Kind = this.kind): ActionButton {
        label.setIfChanged(text)
        if (iconRes == null) icon.show(false) else { icon.setImageResource(iconRes); icon.show(true) }
        if (this.kind != kind) this.kind = kind
        contentDescription = text
        return this
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.38f
    }

    /** The size callers asked for (read at the first measure, after their setup), the most a label grows back to. */
    private var baseSp = 0f
    private var fittedFor: Pair<String, Int>? = null // text, and the space it was fitted to

    /**
     * Translations run longer than English: the label shrinks (down to [MIN_SP]) until it fits its
     * lines without cutting a word, before it would be cut off with "…".
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (!fitLabel) return
        val avail = label.measuredWidth - label.paddingLeft - label.paddingRight
        // Vertical: what the slot leaves under the icon; a fixed-height slot clips a second line otherwise.
        val availH = if (!vertical || MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else measuredHeight - paddingTop - paddingBottom - (if (icon.visibility == View.VISIBLE) icon.layoutParams.height + context.dp(4) else 0)
        val text = label.text?.toString().orEmpty()
        if (avail <= 0 || text.isEmpty() || fittedFor == text to avail * 31 + availH) return
        fittedFor = text to avail * 31 + availH
        val dm = resources.displayMetrics
        if (baseSp == 0f) baseSp = label.textSize / dm.scaledDensity
        val paint = android.text.TextPaint(label.paint)
        fun fits(sp: Float): Boolean {
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
            if (text.split(' ', '\n').any { paint.measureText(it) > avail }) return false
            val layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, avail).build()
            return layout.lineCount <= label.maxLines && layout.height <= availH
        }
        var sp = baseSp
        while (sp > MIN_SP && !fits(sp)) sp -= 0.5f
        val px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, dm)
        if (kotlin.math.abs(label.textSize - px) > 0.5f) {
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    companion object {
        const val MIN_SP = 11f
    }
}

/** A rounded status label: the colour carries the state, the words say it. */
class Pill(context: Context, sizeSp: Float = 14f) : TextView(context) {
    enum class Tone { OK, WARN, CRIT, NEUTRAL, INFO }
    private var tone: Tone? = null

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        setPadding(context.dp(12), context.dp(6), context.dp(12), context.dp(6))
        set("", Tone.NEUTRAL)
    }

    fun set(s: String, t: Tone) {
        setIfChanged(s)
        if (t == tone) return
        tone = t
        val (bg, fg) = when (t) {
            Tone.OK -> context.color(R.color.ok) to context.color(R.color.text)
            Tone.WARN -> context.color(R.color.warn) to context.color(R.color.on_warn)
            Tone.CRIT -> context.color(R.color.crit) to context.color(R.color.text)
            Tone.INFO -> context.color(R.color.accent) to context.color(R.color.text)
            Tone.NEUTRAL -> context.color(R.color.surface3) to context.color(R.color.text)
        }
        background = rounded(bg, context.dp(16).toFloat())
        setTextColor(fg)
    }
}

/**
 * Full screen on every screen, as DJI's own apps run: the system bars come back with a swipe
 * from the edge. (The pre-API-30 flags, so it works the same on the Smart Controller's Android 7.)
 */
@Suppress("DEPRECATION")
fun Activity.immersive() {
    window.decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
}

/** A screen's top bar: Back, a title, then whatever the screen adds on the right. */
fun Activity.topBar(title: String, onBack: () -> Unit): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setBackgroundColor(color(R.color.bar))
    setPadding(dp(4), 0, dp(8), 0)
    addView(iconButton(R.drawable.ic_back, getString(R.string.ui_back), onBack), LinearLayout.LayoutParams(dp(52), dp(48)))
    addView(text(18f, bold = true).apply { this.text = title; setPadding(dp(4), 0, dp(16), 0) })
}

fun Context.iconButton(iconRes: Int, description: String, onClick: () -> Unit): ImageView = ImageView(this).apply {
    setImageResource(iconRes)
    imageTintList = ColorStateList.valueOf(color(R.color.text))
    scaleType = ImageView.ScaleType.CENTER
    contentDescription = description
    background = buttonBackground(Kind.QUIET, 24)
    isClickable = true
    setOnClickListener { onClick() }
}

fun ViewGroup.addRow(v: View, heightDp: Int = ViewGroup.LayoutParams.WRAP_CONTENT, topDp: Int = 0): View {
    addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (heightDp > 0) dp(heightDp) else heightDp).apply { topMargin = dp(topDp) })
    return v
}

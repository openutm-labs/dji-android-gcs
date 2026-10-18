package org.openutm.djiflight.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import org.openutm.djiflight.app.R
import kotlin.math.roundToInt

/**
 * − value + for numbers, instead of a keyboard that covers half of a landscape screen. Hold a
 * button to repeat; tap the value to type an exact number.
 */
@SuppressLint("ViewConstructor")
class Stepper(
    private val activity: Activity,
    private val title: String,
    private var value: Double,
    private val min: Double,
    private val max: Double,
    private val step: Double,
    private val format: (Double) -> String,
    /** Typed values are rounded to this; defaults to [step]. */
    private val typedStep: Double = step,
    private val onChange: (Double) -> Unit,
) : LinearLayout(activity) {
    private val valueView = activity.text(18f, bold = true).apply {
        gravity = Gravity.CENTER
        tabular()
        background = activity.buttonBackground(Kind.QUIET, 6)
        isClickable = true
        setOnClickListener { typeValue() }
    }
    private val minus = activity.iconButton(R.drawable.ic_minus, activity.getString(R.string.ui_less)) { nudge(-1) }
    private val plus = activity.iconButton(R.drawable.ic_plus, activity.getString(R.string.ui_more)) { nudge(+1) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minus.background = activity.buttonBackground(Kind.SECONDARY, 8)
        plus.background = activity.buttonBackground(Kind.SECONDARY, 8)
        addView(minus, LayoutParams(activity.dp(48), activity.dp(48)))
        addView(valueView, LayoutParams(0, activity.dp(48), 1f))
        addView(plus, LayoutParams(activity.dp(48), activity.dp(48)))
        repeatOnHold(minus, -1)
        repeatOnHold(plus, +1)
        render()
    }

    val current get() = value

    fun setValue(v: Double, notify: Boolean = false) {
        val c = v.coerceIn(min, max)
        if (c == value) { render(); return }
        value = c
        render()
        if (notify) onChange(value)
    }

    private fun render() {
        valueView.setIfChanged(format(value))
        minus.isEnabled = value > min
        plus.isEnabled = value < max
        minus.alpha = if (minus.isEnabled) 1f else 0.35f
        plus.alpha = if (plus.isEnabled) 1f else 0.35f
    }

    private fun nudge(dir: Int) {
        // Snap to the step grid first, so 83 + 5 gives 85, not 88.
        val snapped = (value / step).roundToInt() * step
        val next = if ((dir > 0 && snapped > value) || (dir < 0 && snapped < value)) snapped else snapped + dir * step
        setValue(next, notify = true)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun repeatOnHold(v: android.view.View, dir: Int) {
        val h = Handler(Looper.getMainLooper())
        var repeats = 0
        val tick = object : Runnable {
            override fun run() {
                nudge(dir)
                repeats++
                h.postDelayed(this, if (repeats > 8) 60 else 140)
            }
        }
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { repeats = 0; h.postDelayed(tick, 450) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { val held = repeats > 0; h.removeCallbacks(tick); if (held) return@setOnTouchListener true }
            }
            false
        }
    }

    private fun typeValue() {
        val input = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or (if (min < 0) InputType.TYPE_NUMBER_FLAG_SIGNED else 0)
            // Use the numeric value, not a localized label that may contain units or a decimal comma.
            setText(value.toString())
            setTextColor(activity.color(R.color.text))
            textSize = 22f
            selectAll()
        }
        Sheet(activity).title(title)
            .body(input)
            .body(Sheet.note(activity, activity.getString(R.string.ui_stepper_between, format(min), format(max))))
            .button(activity.getString(R.string.ui_cancel))
            .button(activity.getString(R.string.ui_set), Kind.PRIMARY) {
                input.text.toString().replace(',', '.').toDoubleOrNull()?.let { setValue((it / typedStep).roundToInt() * typedStep, notify = true) }
            }
            .show()
        input.requestFocus()
    }

    companion object {
        /** A labelled stepper row: label and hint on the left, the stepper on the right. */
        fun row(activity: Activity, label: String, hint: String?, stepper: Stepper, stepperWidthDp: Int = 168): LinearLayout = LinearLayout(activity).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(6), 0, activity.dp(6))
            val left = LinearLayout(activity).apply {
                orientation = VERTICAL
                addView(activity.text(15f, bold = true).apply { text = label })
                if (hint != null) addView(activity.text(13f, R.color.text3).apply { text = hint; tag = "hint" })
            }
            addView(left, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(stepper, LayoutParams(activity.dp(stepperWidthDp), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }
}

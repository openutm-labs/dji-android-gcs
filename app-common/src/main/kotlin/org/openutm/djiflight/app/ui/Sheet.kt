package org.openutm.djiflight.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.openutm.djiflight.app.R

/**
 * One component for every confirmation and short task (docs/12, The app): a title, plain words,
 * then large buttons across the bottom, or a slide to confirm where the aircraft will start moving
 * on its own. Replaces the stock dialogs, whose small corner buttons are easy to tap through.
 */
class Sheet(private val activity: Activity, wide: Boolean = false) {
    private val dialog = Dialog(activity, R.style.Theme_OpenUTM_Sheet)
    private val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val buttons = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
    /** Pinned under the scrolling body: a slide to confirm must never be below the fold. */
    private val footer = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val titleView = activity.text(21f, bold = true).apply { setPadding(0, 0, 0, activity.dp(10)) }
    private val card: LinearLayout
    private var onDismiss: (() -> Unit)? = null

    init {
        val ctx = activity
        val bodyScroll = ScrollView(ctx).apply {
            isFillViewport = false
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(ctx.color(R.color.surface), ctx.dp(14).toFloat(), ctx.dp(1), ctx.color(R.color.outline))
            setPadding(ctx.dp(22), ctx.dp(18), ctx.dp(22), ctx.dp(16))
            addView(titleView)
            addView(bodyScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(buttons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(14) })
        }
        dialog.setContentView(card)
        dialog.setOnDismissListener { onDismiss?.invoke() }
        val m = ctx.resources.displayMetrics
        val maxW = ctx.dp(if (wide) 860 else 560)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(minOf(maxW, (m.widthPixels * 0.94f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
        // The card never grows past the screen: the body scrolls, the title and buttons stay.
        card.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            val cap = (m.heightPixels * 0.92f).toInt()
            if (bottom - top > cap) dialog.window?.setLayout(dialog.window!!.attributes.width, cap)
        }
    }

    fun title(t: String) = apply { titleView.text = t }

    fun message(t: CharSequence) = apply {
        content.addView(activity.text(16f, R.color.text2).apply { text = t; setLineSpacing(activity.dp(3).toFloat(), 1f) })
    }

    fun body(v: View) = apply { content.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }

    /** Status or instructions that must remain visible while a long form scrolls. */
    fun footer(v: View) = apply { footer.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }

    /** Buttons fill the bottom row, in the order added; the first is usually the safe choice. */
    fun button(label: String, kind: Kind = Kind.SECONDARY, iconRes: Int? = null, dismiss: Boolean = true, ref: ((ActionButton) -> Unit)? = null, action: () -> Unit = {}) = apply {
        val b = ActionButton(activity).set(label, iconRes, kind)
        ref?.invoke(b)
        b.setOnClickListener {
            if (dismiss) dialog.dismiss()
            action()
        }
        buttons.addView(b, LinearLayout.LayoutParams(0, activity.dp(56), 1f).apply { if (buttons.childCount > 0) marginStart = activity.dp(10) })
    }

    /** Slide to confirm, DJI's convention for anything that makes the aircraft move. */
    fun slide(label: String, enabled: Boolean = true, onConfirm: () -> Unit) = apply {
        val s = SlideToConfirm(activity, label) {
            dialog.dismiss()
            onConfirm()
        }
        s.isEnabled = enabled
        footer.addView(s, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(60)).apply { topMargin = activity.dp(14) })
    }

    fun onDismiss(f: () -> Unit) = apply { onDismiss = f }

    val isShowing get() = dialog.isShowing

    fun dismiss() = dialog.dismiss()

    /** Shown without bringing the system bars back (the window takes focus only after it is up). */
    @SuppressLint("WrongConstant")
    @Suppress("DEPRECATION")
    fun show(): Sheet = apply {
        if (buttons.childCount == 0) buttons.visibility = View.GONE
        val w = dialog.window
        w?.setFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        dialog.show()
        w?.decorView?.systemUiVisibility = activity.window.decorView.systemUiVisibility
        w?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }

    companion object {
        /** A labelled row for sheet bodies: label on the left, value on the right. */
        fun row(activity: Activity, label: String, value: String, valueColor: Int = R.color.text): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, activity.dp(8), 0, activity.dp(8))
            // The label keeps its words; a long value wraps instead.
            addView(activity.text(15f, R.color.text2).apply { text = label; maxLines = 1 }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(activity.text(16f, valueColor, bold = true).apply { text = value; gravity = Gravity.END },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = activity.dp(16) })
        }

        fun note(activity: Activity, s: String, colorId: Int = R.color.text3): TextView =
            activity.text(14f, colorId).apply { text = s; setPadding(0, activity.dp(6), 0, activity.dp(2)) }
    }
}

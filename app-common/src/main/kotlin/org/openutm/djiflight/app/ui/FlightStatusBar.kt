package org.openutm.djiflight.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import org.openutm.djiflight.app.R

/** Keep the battery and link indicators visible without squeezing the status labels away. */
class FlightStatusBar(context: Context, attrs: AttributeSet?) : ViewGroup(context, attrs) {
    private var stacked = false
    private val rowHeight get() = context.dp(44)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val heightSpec = MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY)
        val menu = findViewById<View>(R.id.btnMenu)
        val leading = findViewById<View>(R.id.statusLeading)
        val utm = findViewById<View>(R.id.utmPillBox)
        val trailing = findViewById<View>(R.id.statusTrailing)
        menu.measure(MeasureSpec.makeMeasureSpec(context.dp(56).coerceAtMost(available), MeasureSpec.EXACTLY), heightSpec)
        // Measure the complete group first: SDK widgets can change width when data arrives.
        trailing.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightSpec)
        stacked = available - menu.measuredWidth - trailing.measuredWidth < context.dp(240)
        val labels = (available - menu.measuredWidth - if (stacked) 0 else trailing.measuredWidth).coerceAtLeast(0)
        leading.measure(MeasureSpec.makeMeasureSpec(labels / 2, MeasureSpec.EXACTLY), heightSpec)
        utm.measure(MeasureSpec.makeMeasureSpec(labels - labels / 2, MeasureSpec.EXACTLY), heightSpec)
        val rows = if (stacked) 2 else 1
        setMeasuredDimension(width, resolveSize(paddingTop + rowHeight * rows + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        fun place(view: View, start: Int, y: Int) {
            val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - paddingRight - start - view.measuredWidth else paddingLeft + start
            view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
        }
        val menu = findViewById<View>(R.id.btnMenu)
        val leading = findViewById<View>(R.id.statusLeading)
        val utm = findViewById<View>(R.id.utmPillBox)
        val trailing = findViewById<View>(R.id.statusTrailing)
        place(menu, 0, paddingTop)
        place(leading, menu.measuredWidth, paddingTop)
        place(utm, menu.measuredWidth + leading.measuredWidth, paddingTop)
        place(trailing, width - paddingLeft - paddingRight - trailing.measuredWidth, paddingTop + if (stacked) rowHeight else 0)
    }

    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT)
    override fun generateLayoutParams(attrs: AttributeSet?) = LayoutParams(context, attrs)
}

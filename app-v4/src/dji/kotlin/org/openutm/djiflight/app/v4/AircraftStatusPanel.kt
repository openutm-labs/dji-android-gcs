package org.openutm.djiflight.app.v4

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dji.ux.panel.PreFlightCheckListPanel
import org.openutm.djiflight.app.ui.dp

/** Keep DJI's data binding and actions; give its width-scaled rows a readable, scrollable host. */
internal fun configureAircraftStatusPanel(panel: PreFlightCheckListPanel) {
    val context = panel.context
    with(panel) {
        val list = findViewById<RecyclerView>(dji.ux.R.id.recycle_list_view_content)
        // DJI ships this list as wrap_content: it shrinks well below the panel's width, and
        // its row appearance scales labels to that small width on every SDK measurement.
        list.layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            topMargin = context.dp(48)
        }
        list.layoutManager = object : LinearLayoutManager(context) {
            override fun measureChildWithMargins(child: View, widthUsed: Int, heightUsed: Int) {
                // The adapter uses 0x0 for excluded rows. Keep them excluded when rebinding.
                val p = child.layoutParams
                if (child.visibility != View.GONE && !(p.width == 0 && p.height == 0)) {
                    // UX SDK 4.16.2 precheck rows use a 667 × 44 reference appearance.
                    p.height = maxOf(context.dp(48), (width * 44f / 667f).toInt())
                }
                super.measureChildWithMargins(child, widthUsed, heightUsed)
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), 0, context.dp(4), 0)
            setBackgroundColor(Color.rgb(26, 33, 41))
            addView(TextView(context).apply {
                text = context.getString(R.string.aircraft_status_title)
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(Button(context).apply {
                id = R.id.aircraftStatusClose
                text = context.getString(R.string.aircraft_status_close)
                contentDescription = context.getString(R.string.aircraft_status_close_description)
                isAllCaps = false
                textSize = 16f
                setOnClickListener { panel.visibility = View.INVISIBLE }
            }, LinearLayout.LayoutParams(context.dp(100), LayoutParams.MATCH_PARENT))
        }
        findViewById<FrameLayout>(dji.ux.R.id.root_view).addView(header,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(48), Gravity.TOP))
    }
}

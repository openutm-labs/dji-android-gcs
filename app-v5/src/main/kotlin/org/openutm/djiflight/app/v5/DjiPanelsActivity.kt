package org.openutm.djiflight.app.v5

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import dji.v5.ux.core.base.SchedulerProvider
import dji.v5.ux.core.communication.GlobalPreferenceKeys
import dji.v5.ux.core.communication.ObservableInMemoryKeyedStore
import dji.v5.ux.core.communication.UXKeys
import dji.v5.ux.core.panel.systemstatus.SystemStatusListPanelWidget
import dji.v5.ux.core.widget.setting.SettingPanelWidget
import dji.v5.ux.gimbal.GimbalFineTuneWidget
import dji.v5.ux.training.simulatorcontrol.SimulatorControlWidget
import io.reactivex.rxjava3.disposables.CompositeDisposable

/**
 * DJI's own panels from its UI Library v5, drawn over the Fly screen as in DJI Pilot 2: the settings
 * panel (flight controller, obstacle sensing, RC, video link, battery, gimbal, camera, RTK, with
 * DJI's compass, IMU and RC calibration), the system status list, and the simulator control. They
 * live in their own activity because the settings pages are AndroidX fragments and the Fly screen
 * (app-common) is a plain Activity. Tap outside a panel or Back to close.
 */
class DjiPanelsActivity : AppCompatActivity() {
    private val disposables = CompositeDisposable()
    private lateinit var root: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        // No restored state: the settings panel builds its fragments afresh every time.
        super.onCreate(null)
        root = FrameLayout(this).apply {
            setBackgroundColor(0x66000000)
            setOnClickListener { finish() }
        }
        setContentView(root)
        // Pre-flight can open this before the SDK is up; DJI's panels wait for it (DjiV5Platform.whenSdkReady).
        DjiV5Platform.whenSdkReady(root) {
            when (intent.getStringExtra(EXTRA_PANEL)) {
                PANEL_STATUS -> showStatusList()
                PANEL_SIMULATOR -> add(SimulatorControlWidget(this).apply { visibility = View.VISIBLE }, dp(330), MATCH, Gravity.CENTER_HORIZONTAL)
                else -> showSettings()
            }
        }
    }

    private fun showSettings() {
        val panel = SettingPanelWidget(this).apply { setBackgroundColor(PANEL_BACKGROUND) }
        // DJI's sample hosts this in a drawer; its pages' close buttons are left for the host to wire.
        add(panel, minOf(dp(510), resources.displayMetrics.widthPixels * 4 / 5), MATCH, Gravity.END)
        panel.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() = wireClose(panel)
        })
        // "Gimbal adjust" in the gimbal page: DJI's fine-tune control over the camera picture, as in DJI's sample.
        disposables.add(
            ObservableInMemoryKeyedStore.getInstance().addObserver(UXKeys.create(GlobalPreferenceKeys.GIMBAL_ADJUST_CLICKED))
                .observeOn(SchedulerProvider.ui())
                .subscribe { showGimbalFineTune(panel) },
        )
    }

    private fun wireClose(v: View) {
        if (v.id == dji.v5.ux.R.id.setting_menu_header_close) {
            // The vendor font icon can be blank on controller builds. Use a packaged drawable.
            if (v is android.widget.ImageView && v.tag != "openutm-close") {
                v.setImageResource(org.openutm.djiflight.app.R.drawable.ic_close)
                v.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
                v.contentDescription = getString(org.openutm.djiflight.app.R.string.fly_close)
                v.tag = "openutm-close"
            }
            if (!v.hasOnClickListeners()) v.setOnClickListener { finish() }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) wireClose(v.getChildAt(i))
    }

    private fun showGimbalFineTune(settings: View) {
        settings.visibility = View.GONE
        root.setBackgroundColor(Color.TRANSPARENT)
        val tune = GimbalFineTuneWidget(this)
        add(tune, dp(350), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also {
            (tune.layoutParams as FrameLayout.LayoutParams).bottomMargin = dp(24)
        }
        tune.findViewById<View>(dji.v5.ux.R.id.fpv_gimbal_ok_btn)?.setOnClickListener { finish() }
    }

    private fun showStatusList() {
        val list = SystemStatusListPanelWidget(this).apply {
            setBackgroundColor(PANEL_BACKGROUND)
            titleBarBackgroundColor = Color.BLACK
        }
        add(list, minOf(dp(640), resources.displayMetrics.widthPixels - dp(32)), MATCH, Gravity.START)
        disposables.add(list.closeButtonPressed().observeOn(SchedulerProvider.ui()).subscribe { if (it) finish() })
    }

    private fun add(panel: View, width: Int, height: Int, gravity: Int) {
        panel.isClickable = true // taps inside a panel don't reach the scrim
        root.addView(panel, FrameLayout.LayoutParams(width, height, gravity))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    override fun onDestroy() {
        disposables.dispose()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        /** Opaque, unlike DJI's see-through popovers: the app keeps solid surfaces behind text (docs/12). */
        private const val PANEL_BACKGROUND = 0xFF141414.toInt()
        private const val EXTRA_PANEL = "panel"
        const val PANEL_SETTINGS = "settings"
        const val PANEL_STATUS = "status"
        const val PANEL_SIMULATOR = "simulator"

        fun intent(context: Context, panel: String): Intent = Intent(context, DjiPanelsActivity::class.java).putExtra(EXTRA_PANEL, panel)

        fun open(context: Context, panel: String) {
            val i = intent(context, panel)
            if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }
}

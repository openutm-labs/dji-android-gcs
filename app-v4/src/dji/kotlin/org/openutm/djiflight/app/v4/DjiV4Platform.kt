package org.openutm.djiflight.app.v4

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.multidex.MultiDex
import kotlinx.coroutines.CoroutineScope
import org.openutm.djiflight.app.FlightApp
import org.openutm.djiflight.app.Platform
import org.openutm.djiflight.app.StatusWidgets
import org.openutm.djiflight.app.VideoPreview
import org.openutm.djiflight.core.adapter.DroneAdapter
import org.openutm.djiflight.core.mission.CameraModel
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.dji.v4.DjiV4Adapter
import org.openutm.djiflight.dji.v4.DjiV4Video

/** `dji` flavour: DJI Mobile SDK v4.18. */
object DjiV4Platform : Platform {
    override val label = "dji-v4"
    override val versionCode = BuildConfig.VERSION_CODE
    override val versionName: String = BuildConfig.VERSION_NAME
    override val debug = BuildConfig.DEBUG
    override val devBlenderUrl: String = BuildConfig.DEV_BLENDER_URL
    override val defaultMissionLimits = MissionLimits.WAYPOINT_V1
    override val defaultCamera = CameraModel.P4P_V2

    override fun createAdapter(context: Context, scope: CoroutineScope): DroneAdapter = DjiV4Adapter(context) { Log.i("DjiV4Adapter", it) }

    override fun extraStatus(adapter: DroneAdapter): String? = "SDK ${(adapter as DjiV4Adapter).registration.value}"

    /**
     * DJI's UI Library camera view and controls. If UX SDK 4.16.2 turns out not to work with MSDK 4.18
     * on real aircraft, [DjiV4Video] (our own decoder on a TextureView) is the fallback.
     */
    override fun video(context: Context): VideoPreview = UxCameraView()

    /** DJI's own status widgets in the Fly screen's top bar, as in DJI GO 4 (docs/12, The app). */
    override fun statusWidgets(context: Context): StatusWidgets {
        val inflater = LayoutInflater.from(context)
        // Inflated against a parent so the widgets keep their sizes from the layout files.
        val parent = android.widget.FrameLayout(context)
        return StatusWidgets(inflater.inflate(R.layout.dji_status_leading, parent, false), inflater.inflate(R.layout.dji_status_trailing, parent, false))
    }
}

/** DJI's v4 loader must be installed before anything touches the SDK (DJI sample: DJISampleApplication). */
class DjiFlightApp : FlightApp() {
    override fun platform(): Platform = DjiV4Platform

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MultiDex.install(this)
        com.cySdkyc.clx.Helper.install(this)
    }
}

/** The `dji_camera` layout (UX SDK widgets); compact = just the FPV picture, for the inset. */
class UxCameraView : VideoPreview {
    override val available = true
    override val labelRes = org.openutm.djiflight.app.R.string.fly_video_waiting
    /** DJI's remaining-flight-time bar and exposure readouts across the top of the camera view. */
    override val overlayTopDp = 48
    override val overlayStartDp = 56
    private var root: View? = null
    private var compactNow = false
    private var panelListener: ((Boolean) -> Unit)? = null
    override val panelOpen: Boolean get() = !compactNow && (root as? ViewGroup)?.let { r ->
        PANEL_WINDOWS.any { r.findViewById<View>(it)?.visibility == View.VISIBLE }
    } == true

    override fun onPanelVisibilityChanged(listener: (Boolean) -> Unit) { panelListener = listener }

    override fun createView(context: Context): View =
        LayoutInflater.from(context).inflate(R.layout.dji_camera, null, false).also { r ->
            root = r
            r.viewTreeObserver.addOnGlobalLayoutListener {
                // The header may use two rows on small screens. Keep scrollable camera panels
                // below the readouts and inside the remaining stage, capped at DJI's usual size.
                val top = r.findViewById<View>(R.id.camera).bottom
                if (r.height > top && top > 0) {
                    val height = minOf((263 * r.resources.displayMetrics.density).toInt(), r.height - top)
                    for (id in intArrayOf(R.id.camera_setting_exposure_panel, R.id.camera_setting_advanced_panel)) {
                        r.findViewById<View>(id).let { panel ->
                            if (panel.layoutParams.height != height) panel.layoutParams = panel.layoutParams.apply { this.height = height }
                        }
                    }
                }
                panelListener?.invoke(panelOpen)
            }
        }

    override fun setCompact(compact: Boolean) {
        compactNow = compact
        val r = root as? ViewGroup ?: return
        updateCameraName()
        for (i in 0 until r.childCount) {
            val child = r.getChildAt(i)
            if (child.id == R.id.fpv_widget) continue
            // Panels stay hidden until their widget opens them; everything else follows compact mode.
            if (child.id in PANELS) { if (compact || child.visibility == View.GONE) child.visibility = View.INVISIBLE; continue }
            child.visibility = if (compact) View.GONE else View.VISIBLE
        }
    }

    override fun resubscribe() = updateCameraName()

    private fun updateCameraName() {
        root?.findViewById<dji.ux.widget.FPVWidget>(R.id.fpv_widget)?.setSourceCameraNameVisibility(
            (dji.sdk.sdkmanager.DJISDKManager.getInstance().product as? dji.sdk.products.Aircraft)?.cameras?.size?.let { it > 1 } == true,
        )
    }

    private companion object {
        val PANEL_WINDOWS = setOf(R.id.camera_setting_exposure_panel, R.id.camera_setting_advanced_panel, R.id.pre_flight_check_list)
        val PANELS = setOf(R.id.camera_setting_exposure_panel, R.id.camera_setting_advanced_panel, R.id.pre_flight_check_list, R.id.ux_compass_workflow)
    }
}

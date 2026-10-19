package org.openutm.djiflight.app.v5

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatViewInflater
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.ux.cameracore.widget.autoexposurelock.AutoExposureLockWidget
import dji.v5.ux.cameracore.widget.cameracontrols.CameraControlsWidget
import dji.v5.ux.cameracore.widget.cameracontrols.lenscontrol.LensControlWidget
import dji.v5.ux.cameracore.widget.focusexposureswitch.FocusExposureSwitchWidget
import dji.v5.ux.cameracore.widget.focusmode.FocusModeWidget
import dji.v5.ux.cameracore.widget.fpvinteraction.FPVInteractionWidget
import dji.v5.ux.core.communication.DefaultGlobalPreferences
import dji.v5.ux.core.communication.GlobalPreferencesManager
import dji.v5.ux.core.util.CameraUtil
import dji.v5.ux.core.util.UxSharedPreferencesUtil
import dji.v5.ux.core.widget.fpv.FPVStreamSourceListener
import dji.v5.ux.core.widget.fpv.FPVWidget
import dji.v5.ux.visualcamera.CameraNDVIPanelWidget
import dji.v5.ux.visualcamera.CameraVisiblePanelWidget
import dji.v5.ux.visualcamera.zoom.FocalZoomWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.openutm.djiflight.app.FlightApp
import org.openutm.djiflight.app.Platform
import org.openutm.djiflight.app.StatusWidgets
import org.openutm.djiflight.app.VideoPreview
import org.openutm.djiflight.core.adapter.DroneAdapter
import org.openutm.djiflight.core.mission.CameraModel
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.dji.v5.DjiV5Adapter
import org.openutm.djiflight.dji.v5.DjiV5Camera
import org.openutm.djiflight.dji.v5.Registration

/** DJI Mobile SDK v5.18: Mavic 3 Enterprise, Matrice 400 and other MSDK v5 aircraft. */
object DjiV5Platform : Platform {
    override val label = "dji-v5"
    override val versionCode = BuildConfig.VERSION_CODE
    override val versionName: String = BuildConfig.VERSION_NAME
    override val debug = BuildConfig.DEBUG
    override val devBlenderUrl: String = BuildConfig.DEV_BLENDER_URL
    override val defaultMissionLimits = MissionLimits.WPML
    override val defaultCamera = CameraModel.MAVIC_3E

    private var adapter: DjiV5Adapter? = null

    override fun createAdapter(context: Context, scope: CoroutineScope): DroneAdapter =
        DjiV5Adapter(context) { Log.i("DjiV5Adapter", it) }.also { adapter = it }

    override fun extraStatus(adapter: DroneAdapter): String? = "SDK ${(adapter as DjiV5Adapter).registration.value}"

    /**
     * DJI's UI Library v5 camera view and controls (uxsdk-v5). If it misbehaves on an aircraft,
     * [V5CameraView] (the SDK's decoder into our own view, Photo and Rec) is the fallback.
     */
    override fun video(context: Context): VideoPreview = UxCameraView()

    /**
     * DJI's own top bar, as in DJI Pilot 2 (docs/12, The app): DJI's system status on the left (opens
     * DJI's status list), its indicators on the right, ending in DJI's settings button.
     */
    override fun statusWidgets(context: Context): StatusWidgets {
        val dp = context.resources.displayMetrics.density
        val widthDp = context.resources.displayMetrics.widthPixels / dp
        // Sized as the layouts' roots, so the top bar is laid out before the widgets arrive.
        val leadingDp = if (widthDp < 820) 150 else 200
        val leading = FrameLayout(context).apply { layoutParams = ViewGroup.LayoutParams((leadingDp * dp).toInt(), (32 * dp).toInt()) }
        val trailing = FrameLayout(context).apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (28 * dp).toInt()) }
        whenSdkReady(leading) {
            val inflater = LayoutInflater.from(djiThemed(context))
            inflater.inflate(R.layout.dji_status_leading, leading, true)
            leading.getChildAt(0).layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            inflater.inflate(R.layout.dji_status_trailing, trailing, true)
            // DJI's bar fills a whole screen width; ours shares it with the menu and the UTM pill.
            if (widthDp < 1000) trailing.findViewById<View>(R.id.dji_device_health).visibility = View.GONE
            if (widthDp < 820) for (id in intArrayOf(R.id.dji_air_sense, R.id.dji_perception)) trailing.findViewById<View>(id).visibility = View.GONE
            leading.getChildAt(0).setOnClickListener { DjiPanelsActivity.open(context, DjiPanelsActivity.PANEL_STATUS) }
            trailing.findViewById<View>(R.id.dji_setting).setOnClickListener { DjiPanelsActivity.open(context, DjiPanelsActivity.PANEL_SETTINGS) }
            // DJI's gear is a 30 dp image with 50 dp of padding, sized for DJI's own bar: nothing shows in a 40 dp slot.
            trailing.findViewById<View>(dji.v5.ux.R.id.iv_fpv_top_bar_setting)?.apply {
                setPadding(0, 0, 0, 0)
                layoutParams = layoutParams.apply { width = ViewGroup.LayoutParams.MATCH_PARENT }
            }
            trailing.findViewById<View>(R.id.dji_simulator).setOnClickListener { DjiPanelsActivity.open(context, DjiPanelsActivity.PANEL_SIMULATOR) }
        }
        return StatusWidgets(leading, trailing)
    }

    override fun vendorSettings(context: Context): Intent = DjiPanelsActivity.intent(context, DjiPanelsActivity.PANEL_SETTINGS)

    /**
     * DJI's widgets expect an AppCompat activity; the Fly screen (app-common) is a plain one. So: an
     * AppCompat theme, and AppCompat's view inflater on this context's LayoutInflater (the widgets
     * inflate their own layouts through it), without which `app:srcCompat` icons such as DJI's
     * settings button stay blank.
     */
    internal fun djiThemed(context: Context): Context {
        val themed = ContextThemeWrapper(context, R.style.Theme_OpenUTM_DjiWidgets)
        val inflater = LayoutInflater.from(themed) // the wrapper's own clone, kept for its lifetime
        if (inflater.factory2 == null) inflater.factory2 = AppCompatFactory()
        return themed
    }

    private class AppCompatFactory : LayoutInflater.Factory2 {
        private val appCompat = AppCompatViewInflater()

        // As AppCompatDelegate does on API 21+; null for DJI's own view classes, which inflate as usual.
        override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? =
            appCompat.createView(parent, name, context, attrs, false, false, true, false)

        override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? = onCreateView(null, name, context, attrs)
    }

    /**
     * Runs [block] on the main thread once the SDK has finished initialising, or at once if it has.
     * DJI's widgets listen to the SDK's keys as soon as they're attached, and DJI's sample only opens
     * them after start-up: attached earlier, the process died on the emulator (native, "destroyed mutex").
     * Dropped if [owner] is detached first.
     */
    internal fun whenSdkReady(owner: View, block: () -> Unit) {
        val registration = adapter?.registration ?: return block()
        val scope = MainScope()
        scope.launch {
            registration.first { it != Registration.NotStarted && it != Registration.Initializing }
            logCrashes()
            block()
            scope.cancel()
        }
        owner.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) = scope.cancel()
        })
    }

    private var crashLogInstalled = false

    /**
     * The SDK's crash handler (xcrash, installed during start-up) writes crashes where adb can't read
     * them and exits without Android's usual log; log them under the app's tag first.
     */
    private fun logCrashes() {
        if (crashLogInstalled) return
        crashLogInstalled = true
        val sdkHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            Log.e("OpenUTMFlight", "uncaught exception in ${thread.name}", e)
            sdkHandler?.uncaughtException(thread, e)
        }
    }
}

/** DJI's loader must be installed before anything touches the SDK (DJI sample: DJIAircraftApplication). */
class DjiV5FlightApp : FlightApp() {
    override fun platform(): Platform = DjiV5Platform

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        com.cySdkyc.clx.Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // DJI's UI Library keeps its settings (units, display choices) here (DJI sample: DJIAircraftMainActivity).
        UxSharedPreferencesUtil.initialize(this)
        GlobalPreferencesManager.initialize(DefaultGlobalPreferences(this))
    }
}

/**
 * The `dji_camera` layout (DJI's UI Library v5): DJI's FPV view with tap-to-focus, exposure bar,
 * capture controls, lens switch, zoom, take-off and RTH. Picks the video source as DJI's sample does
 * (main camera first) and tells each camera widget which camera and lens it is showing.
 * Compact = just the picture, for the inset.
 */
class UxCameraView : VideoPreview {
    override val available = true
    override val labelRes = org.openutm.djiflight.app.R.string.fly_video_waiting
    /** DJI's remaining-flight-time bar and camera bar across the top of the camera view. */
    override val overlayStartDp = 56
    override val overlayTopDp = 16 + 35 + 8
    /** DJI's layout, once the SDK is up ([DjiV5Platform.whenSdkReady]); until then an empty black frame. */
    private var root: ViewGroup? = null
    private var fpv: FPVWidget? = null
    private var cameras: List<ComponentIndexType> = emptyList()
    private var lastSource: Pair<ComponentIndexType, CameraLensType>? = null
    private val streams get() = MediaDataCenter.getInstance().cameraStreamManager
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val availableCameras = object : ICameraStreamManager.AvailableCameraUpdatedListener {
        override fun onAvailableCameraUpdated(list: MutableList<ComponentIndexType>) {
            main.post {
                cameras = list.toList()
                pickSource()
                // Camera availability and source callbacks can arrive in either order.
                lastSource?.let { (device, lens) -> onSourceUpdated(device, lens, force = true) }
            }
        }

        override fun onCameraStreamEnableUpdate(enabled: MutableMap<ComponentIndexType, Boolean>) = Unit
    }

    override fun createView(context: Context): View {
        val frame = FrameLayout(context).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        DjiV5Platform.whenSdkReady(frame) {
            val r = LayoutInflater.from(DjiV5Platform.djiThemed(context)).inflate(R.layout.dji_camera, frame, false) as ViewGroup
            frame.addView(r, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root = r
            // Tap-to-focus waits for a camera (onSourceUpdated), as does the camera name.
            r.findViewById<FPVInteractionWidget>(R.id.widget_fpv_interaction).isInteractionEnabled = false
            fpv = r.findViewById<FPVWidget>(R.id.widget_primary_fpv).also {
                it.setOnFPVStreamSourceListener(object : FPVStreamSourceListener {
                    override fun onStreamSourceUpdated(devicePosition: ComponentIndexType, lensType: CameraLensType) {
                        r.post { onSourceUpdated(devicePosition, lensType) }
                    }
                })
            }
            setCompact(compactNow)
            pickSource()
        }
        return frame
    }

    private var compactNow = false

    override fun setCompact(compact: Boolean) {
        compactNow = compact
        val r = root ?: return
        for (i in 0 until r.childCount) {
            val child = r.getChildAt(i)
            if (child.id == R.id.widget_primary_fpv) continue
            // Zoom and NDVI wait for a camera that has them (applyVisibility).
            val shown = if (child.id == R.id.widget_focal_zoom || child.id == R.id.panel_ndvi_camera) View.INVISIBLE else View.VISIBLE
            child.visibility = if (compact) View.GONE else shown
        }
        val source = lastSource
        applyVisibility(source?.first ?: ComponentIndexType.UNKNOWN, source?.second ?: CameraLensType.UNKNOWN)
    }

    override fun onResume() {
        runCatching { streams.addAvailableCameraUpdatedListener(availableCameras) }
    }

    override fun onPause() {
        runCatching { streams.removeAvailableCameraUpdatedListener(availableCameras) }
    }

    override fun resubscribe() {
        pickSource()
    }

    /** DJI's sample order: the main (left) camera, then the other gimbal ports, then the FPV camera. */
    private fun pickSource() {
        val f = fpv ?: return
        if (cameras.isEmpty()) {
            lastSource = null
            root?.findViewById<FPVInteractionWidget>(R.id.widget_fpv_interaction)?.isInteractionEnabled = false
            f.isCameraSourceNameVisible = false
            f.isCameraSourceSideVisible = false
            applyVisibility(ComponentIndexType.UNKNOWN, CameraLensType.UNKNOWN)
            return
        }
        val preferred = listOf(
            ComponentIndexType.LEFT_OR_MAIN, ComponentIndexType.RIGHT, ComponentIndexType.UP, ComponentIndexType.PORT_1,
            ComponentIndexType.PORT_2, ComponentIndexType.PORT_3, ComponentIndexType.PORT_4, ComponentIndexType.VISION_ASSIST,
        ).firstOrNull { it in cameras } ?: cameras.first()
        f.updateVideoSource(preferred)
    }

    private fun onSourceUpdated(device: ComponentIndexType, lens: CameraLensType, force: Boolean = false) {
        val r = root ?: return
        if (!force && lastSource == device to lens) return
        lastSource = device to lens
        applyVisibility(device, lens)
        val available = device != ComponentIndexType.UNKNOWN && device in cameras
        fpv?.let { it.isCameraSourceNameVisible = available; it.isCameraSourceSideVisible = available }
        val fpvCamera = device == ComponentIndexType.UNKNOWN || device !in cameras || CameraUtil.isFPVTypeView(device)
        r.findViewById<FPVInteractionWidget>(R.id.widget_fpv_interaction)?.let {
            it.isInteractionEnabled = !fpvCamera && device != ComponentIndexType.UNKNOWN
            if (!fpvCamera) it.updateCameraSource(device, lens)
        }
        // A late callback after disconnect must not restore stale controls or camera bindings.
        if (!available) return
        for (i in 0 until r.childCount) {
            when (val v = r.getChildAt(i)) {
                is LensControlWidget -> v.updateCameraSource(device, lens)
                is CameraNDVIPanelWidget -> v.updateCameraSource(device, lens)
                is CameraVisiblePanelWidget -> v.updateCameraSource(device, lens)
                is AutoExposureLockWidget -> v.updateCameraSource(device, lens)
                is FocusModeWidget -> v.updateCameraSource(device, lens)
                is FocusExposureSwitchWidget -> v.updateCameraSource(device, lens)
                is CameraControlsWidget -> v.updateCameraSource(device, lens)
                is FocalZoomWidget -> v.updateCameraSource(device, lens)
            }
        }
    }

    /** As DJI's sample: no camera controls on the FPV camera; NDVI only on lenses that have it. */
    private fun applyVisibility(device: ComponentIndexType, lens: CameraLensType) {
        val r = root ?: return
        if (compactNow) return
        val fpvCamera = device == ComponentIndexType.UNKNOWN || device !in cameras || CameraUtil.isFPVTypeView(device)
        val cameraIds = setOf(
            R.id.widget_lens_control, R.id.panel_visual_camera, R.id.widget_auto_exposure_lock, R.id.widget_focus_mode,
            R.id.widget_focus_exposure_switch, R.id.widget_camera_controls, R.id.widget_focal_zoom,
        )
        for (id in cameraIds) r.findViewById<View>(id)?.visibility = if (fpvCamera) View.INVISIBLE else View.VISIBLE
        r.findViewById<View>(R.id.panel_ndvi_camera)?.visibility = if (!fpvCamera && CameraUtil.isSupportForNDVI(lens)) View.VISIBLE else View.INVISIBLE
    }
}

/** The SDK's own decoder into a TextureView, with Photo and Rec; compact = just the picture, for the inset. */
class V5CameraView : VideoPreview {
    override val available = true
    override val labelRes = org.openutm.djiflight.app.R.string.fly_video_waiting
    private var camera: DjiV5Camera? = null

    override fun createView(context: Context): View = DjiV5Camera(context).also { camera = it }.view
    override fun setCompact(compact: Boolean) { camera?.setCompact(compact) }
    override fun onResume() { camera?.onResume() }
    override fun onPause() { camera?.onPause() }
    override fun resubscribe() { camera?.resubscribe() }
}

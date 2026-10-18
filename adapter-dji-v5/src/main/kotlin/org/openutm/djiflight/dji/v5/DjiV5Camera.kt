package org.openutm.djiflight.dji.v5

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager

/**
 * Live view and basic camera control over MSDK v5, without DJI's UI Library (which v5 ships only
 * as source, with MapLibre 10 and Google Play services: docs/12, Decisions).
 *
 * The SDK decodes the stream itself into our TextureView's surface (`CameraStreamManager`), so the
 * map inset can overlap it. Photo and Record use the camera keys; everything else (exposure, zoom,
 * gimbal) stays on the controller's sticks and dials.
 */
class DjiV5Camera(context: Context) {
    private val streams get() = MediaDataCenter.getInstance().cameraStreamManager
    private val keys get() = KeyManager.getInstance()
    private val main = Handler(Looper.getMainLooper())

    private val dp = context.resources.displayMetrics.density
    private val texture = TextureView(context)
    private val status = TextView(context).apply {
        setTextColor(Color.WHITE); setShadowLayer(3f, 0f, 0f, Color.BLACK); textSize = 12f
        gravity = Gravity.CENTER_HORIZONTAL; maxWidth = (120 * dp).toInt()
    }
    private val photo = Button(context).apply { text = "Photo"; setOnClickListener { shoot() } }
    private val record = Button(context).apply { text = "Rec"; setOnClickListener { toggleRecord() } }
    private val controls = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val button = LinearLayout.LayoutParams((96 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
        addView(photo, button)
        addView(record, button)
        addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    val view: FrameLayout = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        addView(texture, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(controls, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = 24 })
    }

    @Volatile private var camera: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN
    /** The SDK has listed at least one camera stream: only then is a surface handed over. */
    @Volatile private var hasCamera = false
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var resumed = false
    @Volatile private var recording = false

    // Both methods spelled out: the API jar declares onCameraStreamEnableUpdate as a default method, but
    // the runtime SDK calls it as abstract (AbstractMethodError on the emulator with a lambda here).
    private val available = object : ICameraStreamManager.AvailableCameraUpdatedListener {
        override fun onAvailableCameraUpdated(list: MutableList<ComponentIndexType>) {
            val pick = list.firstOrNull { it == ComponentIndexType.LEFT_OR_MAIN }
                ?: list.firstOrNull { it != ComponentIndexType.FPV && it != ComponentIndexType.UNKNOWN }
                ?: list.firstOrNull()
            main.post {
                val changed = (pick != null) != hasCamera || (pick != null && pick != camera)
                hasCamera = pick != null
                if (pick != null) camera = pick
                if (changed) reattach()
            }
        }

        override fun onCameraStreamEnableUpdate(enabled: MutableMap<ComponentIndexType, Boolean>) = Unit
    }

    init {
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                width = w
                height = h
                reattach()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                width = w
                height = h
                reattach()
            }
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                detachSurface()
                surface?.release()
                surface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    fun setCompact(compact: Boolean) {
        controls.visibility = if (compact) View.GONE else View.VISIBLE
    }

    fun onResume() {
        resumed = true
        runCatching { streams.addAvailableCameraUpdatedListener(available) }
        listenRecording()
        reattach()
    }

    fun onPause() {
        resumed = false
        runCatching { streams.removeAvailableCameraUpdatedListener(available) }
        runCatching { keys.cancelListen(this) }
        detachSurface()
    }

    /** After a reconnect (battery swap): hand the surface to the SDK again. */
    fun resubscribe() {
        main.post {
            listenRecording()
            reattach()
        }
    }

    private fun reattach() {
        detachSurface()
        val s = surface ?: return
        if (!resumed || !hasCamera || width <= 0 || height <= 0) return
        runCatching { streams.putCameraStreamSurface(camera, s, width, height, ICameraStreamManager.ScaleType.CENTER_INSIDE) }
            .onFailure { say("no video: ${it.message}") }
    }

    private fun detachSurface() {
        surface?.let { s -> runCatching { streams.removeCameraStreamSurface(s) } }
    }

    private fun listenRecording() {
        runCatching { keys.cancelListen(this) }
        runCatching {
            keys.listen(KeyTools.createKey(CameraKey.KeyIsRecording, camera), this, true, CommonCallbacks.KeyListener<Boolean> { _, now ->
                recording = now == true
                main.post { record.text = if (recording) "Stop" else "Rec" }
            })
        }
    }

    private fun shoot() {
        setMode(CameraMode.PHOTO_NORMAL) {
            act(CameraKey.KeyStartShootPhoto, "photo taken")
        }
    }

    private fun toggleRecord() {
        if (recording) act(CameraKey.KeyStopRecord, "recording stopped")
        else setMode(CameraMode.VIDEO_NORMAL) { act(CameraKey.KeyStartRecord, "recording") }
    }

    private fun setMode(mode: CameraMode, then: () -> Unit) {
        keys.setValue(KeyTools.createKey(CameraKey.KeyCameraMode, camera), mode, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = then()
            // Some cameras refuse a mode they are already in, or don't expose the key: try the action anyway.
            override fun onFailure(error: IDJIError) = then()
        })
    }

    private fun act(info: dji.sdk.keyvalue.key.DJIActionKeyInfo<EmptyMsg, EmptyMsg>, ok: String) {
        keys.performAction(KeyTools.createKey(info, camera), object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(result: EmptyMsg?) = say(ok)
            override fun onFailure(error: IDJIError) = say("${error.description() ?: error.errorCode()}")
        })
    }

    private fun say(text: String) {
        main.post {
            status.text = text
            main.removeCallbacksAndMessages(CLEAR)
            main.postAtTime({ status.text = "" }, CLEAR, android.os.SystemClock.uptimeMillis() + 4_000)
        }
    }

    private companion object {
        val CLEAR = Any()
    }
}

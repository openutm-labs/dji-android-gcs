package org.openutm.djiflight.app

import android.content.Context
import android.view.View

/**
 * The aircraft's camera area, supplied per flavour: DJI's UI Library camera view and controls in
 * `dji`, nothing in `sim`. Created once per screen; [setCompact] hides everything but the picture
 * when it is shown in the small inset.
 */
interface VideoPreview {
    val available: Boolean
    /** Shown over the area when there is no picture: a string resource. */
    val labelRes: Int
    fun createView(context: Context): View?
    fun setCompact(compact: Boolean) {}
    /** Space the camera view's own top widgets take while full size; the app's warnings sit below it. */
    val overlayTopDp: Int get() = 0
    fun onResume() {}
    fun onPause() {}
    /** After a reconnect (battery swap). */
    fun resubscribe() {}
}

object NoVideo : VideoPreview {
    override val available = false
    override val labelRes = R.string.fly_video_none
    override fun createView(context: Context): View? = null
}

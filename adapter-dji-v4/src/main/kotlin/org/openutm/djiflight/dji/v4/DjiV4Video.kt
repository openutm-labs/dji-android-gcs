package org.openutm.djiflight.dji.v4

import android.content.Context
import android.graphics.SurfaceTexture
import android.view.TextureView
import dji.sdk.camera.VideoFeeder
import dji.sdk.codec.DJICodecManager

/**
 * Live camera view over MSDK v4: the primary video feed's H.264 stream into DJI's hardware decoder,
 * rendered on a TextureView (so the map can overlap it). Attach in onResume, detach in onPause.
 */
class DjiV4Video(private val context: Context) {
    private var codec: DJICodecManager? = null
    private var view: TextureView? = null
    private val listener = VideoFeeder.VideoDataListener { data, size -> codec?.sendDataToDecoder(data, size) }

    fun attach(textureView: TextureView) {
        view = textureView
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = start(surface, width, height)
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                stopCodec()
                start(surface, width, height)
            }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopCodec()
                return true
            }
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        textureView.surfaceTexture?.let { start(it, textureView.width, textureView.height) }
    }

    fun detach() {
        stopCodec()
        view?.surfaceTextureListener = null
        view = null
    }

    /** Re-hook the feed after a reconnect (battery swap): the feed object can change. */
    fun resubscribe() {
        VideoFeeder.getInstance()?.primaryVideoFeed?.let { feed ->
            feed.removeVideoDataListener(listener)
            if (codec != null) feed.addVideoDataListener(listener)
        }
    }

    private fun start(surface: SurfaceTexture, width: Int, height: Int) {
        if (codec != null || width <= 0 || height <= 0) return
        codec = DJICodecManager(context, surface, width, height)
        VideoFeeder.getInstance()?.primaryVideoFeed?.addVideoDataListener(listener)
    }

    private fun stopCodec() {
        VideoFeeder.getInstance()?.primaryVideoFeed?.removeVideoDataListener(listener)
        codec?.cleanSurface()
        codec?.destroyCodec()
        codec = null
    }
}

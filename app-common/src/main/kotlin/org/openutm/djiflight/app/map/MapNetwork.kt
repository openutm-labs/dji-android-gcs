package org.openutm.djiflight.app.map

import android.content.Context
import okhttp3.Cache
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.File
import java.util.concurrent.TimeUnit

/** A separate public-map client: never attaches Passport tokens, operator IDs or aircraft data. */
object MapNetwork {
    const val USER_AGENT = "OpenUTMFlight/0.6.0 (+https://github.com/openutm-labs/dji-android-gcs)"
    private var client: OkHttpClient? = null

    @Synchronized fun install(context: Context) {
        if (client == null) client = createClient(File(context.cacheDir, "online-map-http"))
        HttpRequestUtil.setOkHttpClient(client)
        HttpRequestUtil.setPrintRequestUrlOnFailure(false)
    }

    fun createClient(cacheDir: File): OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(cacheDir, 64L * 1024 * 1024))
        .dispatcher(Dispatcher().apply { maxRequestsPerHost = 4 })
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        // Preserve provider cache directives and conditional validation. If none exist, OSM's
        // policy requires at least seven days; this applies to successful public OSM tiles only.
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (chain.request().url.host == "tile.openstreetmap.org" && response.code == 200 &&
                response.header("Cache-Control") == null && response.header("Expires") == null) {
                response.newBuilder().header("Cache-Control", "public, max-age=604800").build()
            } else response
        }.build()
}

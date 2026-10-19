package org.openutm.djiflight.app.map

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** All HTTP requests go to loopback, including tests of OSM-specific cache policy. */
class MapNetworkTest {
    private fun fixture(block: (MockWebServer, okhttp3.OkHttpClient) -> Unit) {
        val dir = Files.createTempDirectory("map-http").toFile()
        val server = MockWebServer().apply { start(InetAddress.getLoopbackAddress(), 0) }
        val client = MapNetwork.createClient(dir).newBuilder().dns(object : okhttp3.Dns { override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress()) }).build()
        try { block(server, client) } finally {
            client.cache!!.close(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
            server.close(); dir.deleteRecursively()
        }
    }

    @Test fun publicTileRequestsIdentifyAppAndReuseSevenDayFallbackCache() = fixture { server, client ->
        server.enqueue(MockResponse().setBody("tile"))
        val url = server.url("/2/1/1.png").newBuilder().host("tile.openstreetmap.org").build()
        val request = Request.Builder().url(url).header("User-Agent", "library-default").build()
        repeat(2) { client.newCall(request).execute().use { assertEquals("tile", it.body!!.string()) } }
        assertEquals(1, server.requestCount)
        val sent = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals(MapNetwork.USER_AGENT, sent.getHeader("User-Agent"))
        assertNull(sent.getHeader("Authorization"))
        assertEquals("/2/1/1.png", sent.path)
    }

    @Test fun expiredProviderCacheRevalidatesWithEtagInsteadOfDownloadingAgain() = fixture { server, client ->
        server.enqueue(MockResponse().setBody("original tile").setHeader("Cache-Control", "max-age=0, must-revalidate").setHeader("ETag", "\"map-v1\""))
        server.enqueue(MockResponse().setResponseCode(304).setHeader("Cache-Control", "max-age=3600"))
        val request = Request.Builder().url(server.url("/tile.png")).build()
        repeat(2) { client.newCall(request).execute().use { assertEquals("original tile", it.body!!.string()) } }
        server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("\"map-v1\"", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("If-None-Match"))
        client.newCall(request).execute().use { assertNotNull(it.cacheResponse) }
        assertEquals(2, server.requestCount)
    }

    @Test fun explicitNoStoreProviderDirectiveIsPreserved() = fixture { server, client ->
        repeat(2) { server.enqueue(MockResponse().setBody("tile").setHeader("Cache-Control", "no-store")) }
        val request = Request.Builder().url(server.url("/tile.png").newBuilder().host("tile.openstreetmap.org").build()).build()
        repeat(2) { client.newCall(request).execute().close() }
        assertEquals(2, server.requestCount)
    }
}

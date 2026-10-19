package org.openutm.djiflight.app.map

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.openutm.djiflight.app.RegionPacks
import org.openutm.djiflight.core.provisioning.OnlineBasemap
import java.nio.file.Files

class DefaultMapsTest {
    @Test fun defaultAndExplicitModesKeepOnlineAndOfflineSourcesSeparate() {
        val dir = Files.createTempDirectory("map-style").toFile()
        try {
            fun sources(mode: RegionPacks.Mode = RegionPacks.Mode.AUTOMATIC) = JSONObject(MapStyle.json(
                dir.resolve("base"), dir.resolve("imagery"), mode = mode)).getJSONObject("sources")
            val online = sources().getJSONObject("online-streets")
            assertEquals(OnlineBasemap().tileUrl, online.getJSONArray("tiles").getString(0))
            assertEquals(19, online.getInt("maxzoom"))
            assertEquals("© OpenStreetMap contributors", online.getString("attribution"))
            assertEquals(0, sources(RegionPacks.Mode.OFFLINE).length())
            dir.resolve("base").writeText("fixture")
            assertTrue(sources().has("basemap"))
            assertFalse(sources().has("online-streets"))
            dir.resolve("imagery").writeText("fixture")
            dir.resolve("base").delete()
            assertTrue(sources().has("imagery"))
            assertFalse(sources().has("online-streets"))
            dir.resolve("base").writeText("fixture")
            val forced = sources(RegionPacks.Mode.ONLINE)
            assertTrue(forced.has("online-streets"))
            assertFalse(forced.has("basemap"))
            assertFalse(forced.has("imagery"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun configuredProviderRetainsItsOwnCreditsAndZoomLimit() {
        val provider = OnlineBasemap("Lab", "https://maps.example.org/{z}/{x}/{y}.png", "Lab contributors", "https://maps.example.org/credits", 17)
        val source = JSONObject(MapStyle.json(null, null, online = provider)).getJSONObject("sources").getJSONObject("online-streets")
        assertEquals(provider.tileUrl, source.getJSONArray("tiles").getString(0))
        assertEquals(provider.attribution, source.getString("attribution"))
        assertEquals(17, source.getInt("maxzoom"))
    }
}

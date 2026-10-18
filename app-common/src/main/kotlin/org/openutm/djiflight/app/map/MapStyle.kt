package org.openutm.djiflight.app.map

import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import org.openutm.djiflight.core.terrain.FileByteSource
import org.openutm.djiflight.core.terrain.PmTiles
import java.io.File

/**
 * An offline MapLibre style: local PMTiles only, never online tiles (OSM's servers forbid offline
 * prefetching). The vector layers follow the Protomaps basemap schema v4 (earth, landuse, water, roads,
 * buildings, boundaries, places, pois), checked against a Protomaps daily build (2026-10-17).
 *
 * Light and plain so it reads in sunlight under the dark controls; the declared area, mission and
 * zones drawn over it stay the strongest colours. Text labels use glyphs built from Roboto (Apache 2.0)
 * into the APK's assets (`tools/glyphs`): Latin, Latin Extended, Vietnamese, Greek and Cyrillic.
 * A name in a script MapLibre can't shape (Devanagari, for one) falls back to its English name.
 * Terrain, when present, adds hillshade under the roads; imagery, when present, covers both.
 */
object MapStyle {
    const val FONT = "Roboto Regular"

    fun json(basemap: File?, imagery: File?, terrain: File? = null): String {
        val sources = JSONObject()
        val layers = JSONArray()
        val vector = basemap != null && basemap.isFile
        layers.put(layer("background", "background", paint = JSONObject().put("background-color", "#D9DEE2")))
        if (vector) {
            sources.put("basemap", JSONObject().put("type", "vector").put("url", "pmtiles://file://${basemap!!.absolutePath}"))
            layers.put(layer("earth", "fill", "basemap", "earth", JSONObject().put("fill-color", "#EFEBE3")))
            layers.put(
                layer("landuse", "fill", "basemap", "landuse", JSONObject()
                    .put("fill-color", match("kind", "#E6E1D7", "park" to "#CFE3BF", "forest" to "#BFD8AD", "wood" to "#BFD8AD", "nature_reserve" to "#CFE3BF",
                        "grass" to "#D6E8C6", "farmland" to "#E9E7CF", "cemetery" to "#D5DFCB", "pitch" to "#CFE3BF", "golf_course" to "#CFE3BF",
                        "industrial" to "#E2DDE3", "railway" to "#E2DDE3", "school" to "#ECE3D3", "hospital" to "#F0DCDC", "aerodrome" to "#E0DCE8",
                        "runway" to "#C9C5D2", "taxiway" to "#C9C5D2", "beach" to "#F3EAC7", "glacier" to "#F4F8FB", "sand" to "#F0E6C3"))
                    .put("fill-opacity", 0.75)),
            )
            layers.put(layer("water", "fill", "basemap", "water", JSONObject().put("fill-color", "#A9C9E8")))
        }
        if (terrain != null && terrain.isFile) {
            sources.put("terrain", JSONObject().put("type", "raster-dem").put("url", "pmtiles://file://${terrain.absolutePath}")
                .put("encoding", "terrarium").put("tileSize", rasterTileSize(terrain, 512)))
            layers.put(layer("hillshade", "hillshade", "terrain", paint = JSONObject()
                .put("hillshade-exaggeration", 0.5).put("hillshade-shadow-color", "#5B6670")
                .put("hillshade-highlight-color", "#FFFFFF").put("hillshade-accent-color", "#8A939B")))
        }
        if (imagery != null && imagery.isFile) {
            sources.put("imagery", JSONObject().put("type", "raster").put("url", "pmtiles://file://${imagery.absolutePath}").put("tileSize", rasterTileSize(imagery, 256)))
            layers.put(layer("imagery", "raster", "imagery"))
        }
        if (vector) {
            val roadWidth = JSONArray().put("interpolate").put(JSONArray().put("exponential").put(1.6)).put(JSONArray().put("zoom"))
                .put(10).put(match("kind", 0.6, "highway" to 1.8, "major_road" to 1.4, "path" to 0.4))
                .put(17).put(match("kind", 6.0, "highway" to 12.0, "major_road" to 9.0, "path" to 1.6))
            layers.put(layer("roads-casing", "line", "basemap", "roads", JSONObject().put("line-color", "#B9B1A4").put("line-gap-width", roadWidth).put("line-width", 0.8))
                .put("filter", JSONArray().put("!=").put(JSONArray().put("get").put("kind")).put("path"))
                .put("layout", JSONObject().put("line-cap", "round").put("line-join", "round")))
            layers.put(layer("roads", "line", "basemap", "roads", JSONObject().put("line-width", roadWidth)
                .put("line-color", match("kind", "#FFFFFF", "highway" to "#FFD38A", "major_road" to "#FFF1BF", "path" to "#F6F2EA")))
                .put("layout", JSONObject().put("line-cap", "round").put("line-join", "round")))
            layers.put(layer("buildings", "fill", "basemap", "buildings", JSONObject().put("fill-color", "#CFC6BC").put("fill-opacity", 0.8)).put("minzoom", 13))
            layers.put(layer("boundaries", "line", "basemap", "boundaries", JSONObject().put("line-color", "#8F8AA0").put("line-width", 1.2)
                .put("line-dasharray", JSONArray().put(3).put(2))))

            // Labels, least important first so the place names win collisions.
            layers.put(label("roads-label", "roads", 11.0, 14.0, "#3F3B36").put("minzoom", 14)
                .also { it.getJSONObject("layout").put("symbol-placement", "line").put("text-max-angle", 30) })
            layers.put(label("water-label", "water", 12.0, 15.0, "#2F5F8F").put("minzoom", 12)
                .put("filter", JSONArray().put("has").put("name")))
            layers.put(label("peaks", "pois", 11.0, 13.0, "#5A4A3A").put("minzoom", 11)
                .put("filter", JSONArray().put("in").put(JSONArray().put("get").put("kind")).put(JSONArray().put("literal").put(JSONArray().put("peak").put("volcano")))))
            layers.put(label("places", "places", 12.0, 18.0, "#1E1E1E")
                .put("filter", JSONArray().put("in").put(JSONArray().put("get").put("kind")).put(JSONArray().put("literal").put(JSONArray().put("locality").put("neighbourhood").put("macrohood"))))
                .also { it.getJSONObject("layout").put("text-size", JSONArray().put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom"))
                    .put(8).put(match("kind", 11.0, "locality" to 13.0)).put(16).put(match("kind", 14.0, "locality" to 20.0))) })
        }
        return JSONObject().put("version", 8).put("name", "openutm-offline")
            .put("glyphs", "asset://glyphs/{fontstack}/{range}.pbf")
            .put("sources", sources).put("layers", layers).toString()
    }

    /** A text layer: dark text with a wide white halo, readable over map and imagery alike. */
    private fun label(id: String, sourceLayer: String, minSize: Double, maxSize: Double, color: String): JSONObject {
        val name = JSONArray().put("to-string").put(JSONArray().put("get").put("name"))
        val text = JSONArray().put("case").put(JSONArray().put("is-supported-script").put(name)).put(name)
            .put(JSONArray().put("coalesce").put(JSONArray().put("get").put("name:en")).put(name))
        val size = JSONArray().put("interpolate").put(JSONArray().put("linear")).put(JSONArray().put("zoom")).put(10).put(minSize).put(17).put(maxSize)
        return layer(id, "symbol", "basemap", sourceLayer, JSONObject().put("text-color", color).put("text-halo-color", "#FFFFFF").put("text-halo-width", 1.6))
            .put("layout", JSONObject().put("text-field", text).put("text-font", JSONArray().put(FONT)).put("text-size", size).put("text-max-width", 8))
    }

    private fun match(property: String, fallback: Any, vararg cases: Pair<String, Any>): JSONArray =
        JSONArray().put("match").put(JSONArray().put("get").put(property)).apply { cases.forEach { (k, v) -> put(k); put(v) } }.put(fallback)

    /** 256 or 512: read from one decoded tile, since the style must say and labs' files differ. */
    private fun rasterTileSize(file: File, default: Int): Int = runCatching {
        FileByteSource(file).use { PmTiles(it).sampleTile() }?.let { b ->
            BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(b, 0, b.size, it) }.outWidth.takeIf { it > 0 }
        }
    }.getOrNull() ?: default

    private fun layer(id: String, type: String, source: String? = null, sourceLayer: String? = null, paint: JSONObject? = null) =
        JSONObject().put("id", id).put("type", type).apply {
            source?.let { put("source", it) }
            sourceLayer?.let { put("source-layer", it) }
            paint?.let { put("paint", it) }
        }
}

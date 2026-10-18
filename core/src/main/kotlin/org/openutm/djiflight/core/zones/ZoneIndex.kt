package org.openutm.djiflight.core.zones

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.openutm.djiflight.core.geo.BBox
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng

data class Zone(
    val id: String,
    val name: String,
    /** Exterior rings (a MultiPolygon gives several). */
    val rings: List<List<LatLng>>,
    val lowerM: Double?,
    val upperM: Double?,
    val altitudeRef: String?,
    val source: String,
    /** e.g. DJI's WARNING / AUTHORIZATION / RESTRICTED. */
    val category: String? = null,
) {
    val bbox: BBox = Geo.bbox(rings.flatten())
}

data class ZoneHit(val zone: Zone, val distanceM: Double)

data class ZoneSummary(val name: String, val source: String, val categories: Set<String>) {
    companion object {
        fun of(zones: List<Zone>): List<ZoneSummary> = zones.groupBy { it.name.trim() to it.source }.map { (key, entries) ->
            ZoneSummary(key.first, key.second, entries.flatMap { z ->
                listOfNotNull(z.category?.takeUnless { it == "UNKNOWN" }, "ALTITUDE".takeIf { z.upperM != null && z.upperM > 0 })
            }.toSortedSet())
        }
    }
}

/**
 * Cached restriction zones, checked on the device with no network.
 * Warnings only: v4 has no custom-geofence API, so DJI's own GEO system is the only enforcement.
 */
class ZoneIndex(val zones: List<Zone>, val fetchedAtMs: Long) {
    fun intersecting(area: List<LatLng>): List<Zone> {
        val box = Geo.bbox(area)
        return zones.filter { z -> z.bbox.intersects(box) && z.rings.any { Geo.ringsIntersect(it, area) } }
    }

    /** Zones the point is inside (distance 0) or within [withinM] of; nearest first. */
    fun near(p: LatLng, withinM: Double = 100.0): List<ZoneHit> = zones
        .filter { it.bbox.expandedBy(withinM).contains(p) }
        .mapNotNull { z -> z.rings.minOf { Geo.distanceToRingM(p, it) }.takeIf { it <= withinM }?.let { ZoneHit(z, it) } }
        .sortedBy { it.distanceM }

    companion object {
        /** Parses `GET /geo_fence_ops/geo_fence`: `{"count", "results": [{id, name, raw_geo_fence, upper_limit, lower_limit, altitude_ref, ...}]}`. */
        fun parseBlender(body: String, fetchedAtMs: Long): ZoneIndex {
            val root = Json.parseToJsonElement(body) as JsonObject
            val results = root["results"] as? JsonArray ?: JsonArray(emptyList())
            val zones = results.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val raw = o["raw_geo_fence"].let { if (it is JsonPrimitive) it.contentOrNull?.let(Json::parseToJsonElement) else it }
                val rings = rings(raw)
                if (rings.isEmpty()) return@mapNotNull null
                Zone(
                    id = o.str("id") ?: "?",
                    name = o.str("name") ?: "Restriction zone",
                    rings = rings,
                    lowerM = o.str("lower_limit")?.toDoubleOrNull(),
                    upperM = o.str("upper_limit")?.toDoubleOrNull(),
                    altitudeRef = o.str("altitude_ref"),
                    source = "blender",
                )
            }
            return ZoneIndex(zones, fetchedAtMs)
        }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

        private fun rings(e: JsonElement?): List<List<LatLng>> = when (e) {
            is JsonObject -> when ((e["type"] as? JsonPrimitive)?.contentOrNull) {
                "FeatureCollection" -> (e["features"] as? JsonArray).orEmpty().flatMap { rings(it) }
                "Feature" -> rings(e["geometry"])
                "Polygon" -> listOfNotNull(ring((e["coordinates"] as? JsonArray)?.firstOrNull()))
                "MultiPolygon" -> (e["coordinates"] as? JsonArray).orEmpty().mapNotNull { ring((it as? JsonArray)?.firstOrNull()) }
                else -> emptyList()
            }
            else -> emptyList()
        }

        private fun ring(e: JsonElement?): List<LatLng>? {
            val pts = (e as? JsonArray)?.mapNotNull { c ->
                val a = c as? JsonArray ?: return@mapNotNull null
                val lng = (a.getOrNull(0) as? JsonPrimitive)?.doubleOrNull
                val lat = (a.getOrNull(1) as? JsonPrimitive)?.doubleOrNull
                if (lat != null && lng != null) LatLng(lat, lng) else null
            } ?: return null
            return if (pts.size >= 4) pts else null
        }

        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    }
}

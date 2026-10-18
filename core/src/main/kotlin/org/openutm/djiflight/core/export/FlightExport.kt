package org.openutm.djiflight.core.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.openutm.djiflight.core.PhotoStats
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.model.TelemetrySample
import org.openutm.djiflight.core.session.FlightSnapshot
import org.openutm.djiflight.core.time.Rfc3339
import java.util.Locale

/**
 * A flight as files a lab can keep or hand to its CAA, and the copy of record for flights Blender
 * couldn't take (window expired, never declared). Altitudes: W84 = take-off reference + height.
 */
object FlightExport {
    fun geoJson(f: FlightSnapshot, samples: List<TelemetrySample>): String {
        val ref = f.altitudeReference
        val plan = f.plan
        val first = samples.firstOrNull { it.flying }
        val last = samples.lastOrNull { it.flying }
        return buildJsonObject {
            put("type", "FeatureCollection")
            putJsonArray("features") {
                add(buildJsonObject {
                    put("type", "Feature")
                    putJsonObject("properties") {
                        put("kind", "track")
                        put("flight_id", f.flightId)
                        f.declarationId?.let { put("declaration_id", it) }
                        put("phase", f.phase.name)
                        plan?.let { put("aircraft_serial", it.aircraftSerial); put("registration_id", it.registrationId); put("pilot_id", it.pilotId); put("pilot_signed_in", it.pilotSignIn != null); it.pilotSignIn?.let { s -> put("pilot_identity_issuer", s.issuer) }; put("lab", it.lab); put("operator_id", it.operatorId) }
                        first?.let { put("take_off_utc", Rfc3339.format(it.t)) }
                        last?.let { put("landing_utc", Rfc3339.format(it.t)) }
                        put("samples", samples.size)
                        PhotoStats.of(samples).takeIf { it.count > 0 }?.let { put("photos", it.count) }
                        f.checklist?.let { c -> put("checklist_completed_utc", Rfc3339.format(c.at)); put("checklist_items", JsonArray(c.items.map { JsonPrimitive(it) })) }
                        f.imageryHandoff?.let { h -> put("imagery_service", h.service); put("imagery_name", h.name) }
                        ref?.let { put("takeoff_reference_w84_m", round2(it.ellipsoidM)); put("takeoff_reference_source", it.source.name); put("takeoff_reference_sigma_m", it.sigmaM) }
                        put("altitudes", "WGS-84 ellipsoid (take-off reference + barometric height)")
                    }
                    putJsonObject("geometry") {
                        put("type", "LineString")
                        putJsonArray("coordinates") {
                            samples.forEach { s -> add(buildJsonArray { add(s.lng); add(s.lat); add(round2((ref?.ellipsoidM ?: 0.0) + s.h)) }) }
                        }
                    }
                })
                plan?.let { p ->
                    add(polygonFeature(p.area, "declared_area", f.declarationPlan?.let { mapOf("min_alt_w84_m" to it.minAltW84M, "max_alt_w84_m" to it.maxAltW84M, "start_utc" to Rfc3339.format(it.startMs), "end_utc" to Rfc3339.format(it.endMs)) }))
                }
            }
        }.toString()
    }

    private fun polygonFeature(ring: List<LatLng>, kind: String, props: Map<String, Any>?) = buildJsonObject {
        put("type", "Feature")
        putJsonObject("properties") {
            put("kind", kind)
            props?.forEach { (k, v) -> if (v is Number) put(k, v) else put(k, v.toString()) }
        }
        putJsonObject("geometry") {
            put("type", "Polygon")
            put("coordinates", JsonArray(listOf(JsonArray(ring.map { JsonArray(listOf(JsonPrimitive(it.lng), JsonPrimitive(it.lat))) }))))
        }
    }

    fun csv(f: FlightSnapshot, samples: List<TelemetrySample>): String = buildString {
        val ref = f.altitudeReference?.ellipsoidM
        appendLine("seq,time_utc,lat,lng,height_above_takeoff_m,alt_w84_m,v_north_mps,v_east_mps,v_down_mps,yaw_deg,flying,motors_on,gps_level,satellites,flight_mode,pilot_lat,pilot_lng")
        for (s in samples) {
            appendLine(
                listOf(
                    s.seq, Rfc3339.format(s.t), "%.7f".format(Locale.ROOT, s.lat), "%.7f".format(Locale.ROOT, s.lng), fmt(s.h), ref?.let { fmt(it + s.h) } ?: "",
                    fmt(s.vn), fmt(s.ve), fmt(s.vd), fmt(s.yaw), s.flying, s.motors, s.gps ?: "", s.sats ?: "", csvText(s.mode),
                    s.pilotLat?.let { "%.7f".format(Locale.ROOT, it) } ?: "", s.pilotLng?.let { "%.7f".format(Locale.ROOT, it) } ?: "",
                ).joinToString(","),
            )
        }
    }

    private fun fmt(v: Double) = if (v.isFinite()) "%.2f".format(Locale.ROOT, v) else ""
    private fun round2(v: Double) = Math.round(v * 100) / 100.0
    private fun csvText(s: String?) = when {
        s == null -> ""
        s.any { it == ',' || it == '"' || it == '\n' } -> "\"" + s.replace("\"", "\"\"") + "\""
        else -> s
    }
}

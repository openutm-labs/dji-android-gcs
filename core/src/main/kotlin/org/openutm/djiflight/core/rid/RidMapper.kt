package org.openutm.djiflight.core.rid

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.openutm.djiflight.core.model.TelemetrySample
import org.openutm.djiflight.core.time.Rfc3339
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.round

/** Identity sent with every telemetry batch (F3411 RIDFlightDetails). */
data class FlightIdentity(
    val declarationId: String,
    val serial: String,
    val registrationId: String,
    val operatorId: String,
    val description: String,
)

/**
 * Maps recorded samples to ASTM F3411-22a RIDAircraftState, as Flight Blender's validators take it.
 *
 * Unlike the connector's records (launch-risks H2), each state carries height above take-off,
 * Ground/Airborne from the aircraft, and vertical speed; the batch carries the pilot's location.
 */
object RidMapper {
    /** F3411 "unknown" values. */
    private const val TRACK_UNKNOWN = 361.0
    private const val VSPEED_UNKNOWN = 63.0

    fun state(s: TelemetrySample, takeoffEllipsoidM: Double, takeoffSigmaM: Double): JsonObject = buildJsonObject {
        putJsonObject("timestamp") {
            put("value", Rfc3339.format(s.t))
            put("format", "RFC3339")
        }
        put("timestamp_accuracy", 0.5)
        put("operational_status", if (s.flying) "Airborne" else "Ground")
        putJsonObject("position") {
            put("lat", s.lat)
            put("lng", s.lng)
            put("alt", round2(takeoffEllipsoidM + s.h))
            put("accuracy_h", horizontalAccuracy(s.gps))
            put("accuracy_v", verticalAccuracy(takeoffSigmaM))
            put("extrapolated", false)
            put("pressure_altitude", -1000.0)
            putJsonObject("height") {
                put("distance", round2(s.h))
                put("reference", "TakeoffLocation")
            }
        }
        put("track", track(s))
        put("speed", round2(hypot(s.vn, s.ve).coerceAtMost(254.25)))
        put("speed_accuracy", if ((s.gps ?: 0) >= 3) "SA1mps" else "SAUnknown")
        put("vertical_speed", if (s.vd.isFinite()) round2((-s.vd).coerceIn(-62.0, 62.0)) else VSPEED_UNKNOWN)
    }

    fun flightDetails(id: FlightIdentity, pilotLat: Double?, pilotLng: Double?): JsonObject = buildJsonObject {
        put("id", id.declarationId)
        put("operator_id", id.operatorId)
        put("operation_description", id.description)
        putJsonObject("uas_id") {
            put("serial_number", id.serial)
            put("registration_id", id.registrationId)
            put("utm_id", id.declarationId)
        }
        if (pilotLat != null && pilotLng != null) {
            putJsonObject("operator_location") {
                putJsonObject("position") {
                    put("lat", pilotLat)
                    put("lng", pilotLng)
                }
            }
        }
    }

    /** Course over ground when moving, else the aircraft's yaw. */
    fun track(s: TelemetrySample): Double {
        val speed = hypot(s.vn, s.ve)
        val deg = when {
            speed > 0.5 -> Math.toDegrees(atan2(s.ve, s.vn))
            s.yaw.isFinite() -> s.yaw
            else -> return TRACK_UNKNOWN
        }
        return round(((deg % 360) + 360) % 360 * 10) / 10 % 360
    }

    /** DJI GPSSignalLevel 0..5 to an F3411 95 % horizontal accuracy class. */
    fun horizontalAccuracy(gpsLevel: Int?): String = when {
        gpsLevel == null -> "HAUnknown"
        gpsLevel >= 4 -> "HA3m"
        gpsLevel == 3 -> "HA10m"
        gpsLevel == 2 -> "HA30m"
        else -> "HAUnknown"
    }

    /** F3411 vertical classes are 95 % bounds, so classify 2 sigma of the take-off reference. */
    fun verticalAccuracy(sigmaM: Double): String {
        val b = 2 * sigmaM
        return when {
            !b.isFinite() -> "VAUnknown"
            b <= 1 -> "VA1m"
            b <= 3 -> "VA3m"
            b <= 10 -> "VA10m"
            b <= 25 -> "VA25m"
            b <= 45 -> "VA45m"
            b <= 150 -> "VA150m"
            else -> "VA150mPlus"
        }
    }

    private fun round2(v: Double) = round(v * 100) / 100
}

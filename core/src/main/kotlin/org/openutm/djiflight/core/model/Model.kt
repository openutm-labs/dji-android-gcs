package org.openutm.djiflight.core.model

import kotlinx.serialization.Serializable
import org.openutm.djiflight.core.auth.PilotSignIn
import org.openutm.djiflight.core.declaration.GroundEstimate
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.mission.MissionPlan

/** One recorded aircraft state, as stored in the journal and uploaded. Short names keep the journal small. */
@Serializable
data class TelemetrySample(
    /** Strictly increasing per flight; the upload cursor points at a seq. */
    val seq: Long,
    /** Epoch ms UTC, corrected to GNSS time. */
    val t: Long,
    val lat: Double,
    val lng: Double,
    /** Barometric height above take-off, m. */
    val h: Double,
    val vn: Double,
    val ve: Double,
    val vd: Double,
    val yaw: Double,
    val flying: Boolean,
    val motors: Boolean,
    val gps: Int? = null,
    val sats: Int? = null,
    val mode: String? = null,
    /** The pilot's (device's) position at that moment. */
    val pilotLat: Double? = null,
    val pilotLng: Double? = null,
    /** [org.openutm.djiflight.core.adapter.DroneState.photoCount] at that moment (a running count, not per sample). */
    val photos: Int? = null,
)

@Serializable
enum class OperationKind { MANUAL, MISSION }

/** Everything the pilot decided before the flight. */
@Serializable
data class FlightPlan(
    val flightId: String,
    val kind: OperationKind,
    /** Closed ring of the operating area. */
    val area: List<LatLng>,
    val ground: GroundEstimate,
    val plannedMaxHeightM: Double,
    val legalMaxHeightM: Double = 120.0,
    val missionMaxHeightM: Double? = null,
    val plannedStartMs: Long,
    val plannedEndMs: Long,
    val aircraftSerial: String,
    val registrationId: String,
    val pilotId: String,
    val lab: String,
    val operatorId: String,
    val description: String = "",
    /** Name of the saved site flown from, if any (its ground height is updated after take-off). */
    val siteName: String? = null,
    /** Mission flights: the waypoint plan to upload, and the survey polygon it was generated from. */
    val mission: MissionPlan? = null,
    val surveyArea: List<LatLng>? = null,
    /** The pilot signed in through the lab's identity server when planning; null = picked from the roster only. */
    val pilotSignIn: PilotSignIn? = null,
) {
    /**
     * Most height above take-off the legal limit allows anywhere on the flight. The limit counts from
     * the ground, which a terrain-following mission climbs with ([MissionPlan.groundRiseM]).
     */
    val legalCeilingM: Double get() = legalMaxHeightM + (mission?.groundRiseM ?: 0.0)
}

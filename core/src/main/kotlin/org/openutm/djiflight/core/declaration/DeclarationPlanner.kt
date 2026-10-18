package org.openutm.djiflight.core.declaration

import kotlinx.serialization.Serializable
import org.openutm.djiflight.core.altitude.AltitudeSource
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng
import kotlin.math.max
import kotlin.math.min

/** How high the ground at the take-off point is, as known when declaring. */
@Serializable
data class GroundEstimate(val ellipsoidM: Double, val sigmaM: Double, val source: AltitudeSource)

/** Exactly what gets declared; stored in the journal before it is sent. */
@Serializable
data class DeclarationPlan(
    /** The app's flight UUID; also sent in the GeoJSON so a retried declare can find an earlier success. */
    val clientRef: String,
    /** Closed ring. */
    val ring: List<LatLng>,
    val minAltW84M: Double,
    val maxAltW84M: Double,
    val startMs: Long,
    val endMs: Long,
    val aircraftSerial: String,
    val originatingParty: String,
    val submittedBy: String,
    val vlos: Boolean = true,
)

data class DeclarationInput(
    val clientRef: String,
    val ring: List<LatLng>,
    val ground: GroundEstimate,
    /** Max height above take-off the pilot plans to fly. */
    val plannedMaxHeightM: Double,
    /** Country limit above ground; 120 m in most of the labs' countries. */
    val legalMaxHeightM: Double = 120.0,
    /** Highest mission waypoint above take-off, for mission flights. */
    val missionMaxHeightM: Double? = null,
    /**
     * Terrain following: how far the highest ground under the route is above take-off
     * ([org.openutm.djiflight.core.mission.MissionPlan.groundRiseM]). The legal limit counts from the
     * ground, so above take-off the volume may reach this much over it.
     */
    val groundRiseM: Double = 0.0,
    val plannedStartMs: Long,
    val plannedEndMs: Long,
    val nowMs: Long,
    /** Extra time after the planned end for the phone to get online and upload (docs/12, Offline). */
    val syncGraceMs: Long = DeclarationPlanner.DEFAULT_SYNC_GRACE_MS,
    val marginM: Double = 10.0,
    /** Blender rejects a start in the past, so start this far ahead of now (network delay, clock skew). */
    val minLeadMs: Long = DeclarationPlanner.MIN_LEAD_MS,
    val aircraftSerial: String,
    val originatingParty: String,
    val submittedBy: String,
)

/**
 * Builds the declared 4D volume.
 *
 * The altitude band is ground +/- (margin + 2 sigma) up to max height, never the min/max of
 * waypoint altitudes: that collapsed to a 2.4 mm band for constant-altitude plans (launch-risks B3).
 */
object DeclarationPlanner {
    const val MIN_LEAD_MS = 30_000L
    /** Blender rejects an end more than 2 days ahead; keep 5 minutes of slack for clock skew. */
    const val MAX_AHEAD_MS = 48 * 3_600_000L - 5 * 60_000L
    const val DEFAULT_SYNC_GRACE_MS = 6 * 3_600_000L
    const val MIN_WINDOW_MS = 60_000L

    fun maxHeight(input: DeclarationInput): Double {
        require(input.plannedMaxHeightM > 0) { "max height must be positive" }
        var h = min(input.plannedMaxHeightM, input.legalMaxHeightM + input.groundRiseM.coerceAtLeast(0.0))
        input.missionMaxHeightM?.let { m ->
            require(m <= h) { "mission reaches $m m, above the allowed $h m" }
            h = min(h, m + input.marginM)
        }
        return h
    }

    fun plan(input: DeclarationInput): DeclarationPlan {
        require(input.plannedEndMs > input.plannedStartMs) { "planned end must be after planned start" }
        val ring = Geo.closeRing(input.ring)
        val pad = input.marginM + 2 * input.ground.sigmaM
        val start = max(input.plannedStartMs, input.nowMs + input.minLeadMs)
        val end = min(input.plannedEndMs + input.syncGraceMs, input.nowMs + MAX_AHEAD_MS)
        require(end - start >= MIN_WINDOW_MS) { "the window has passed or is too short to declare" }
        return DeclarationPlan(
            clientRef = input.clientRef,
            ring = ring,
            minAltW84M = input.ground.ellipsoidM - pad,
            maxAltW84M = input.ground.ellipsoidM + maxHeight(input) + pad,
            startMs = start,
            endMs = end,
            aircraftSerial = input.aircraftSerial,
            originatingParty = input.originatingParty,
            submittedBy = input.submittedBy,
        )
    }
}

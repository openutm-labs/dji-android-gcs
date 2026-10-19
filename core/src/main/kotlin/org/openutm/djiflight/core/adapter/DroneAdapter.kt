package org.openutm.djiflight.core.adapter

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.mission.CameraModel
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.core.mission.MissionPlan
import org.openutm.djiflight.core.zones.Zone

/**
 * What every SDK adapter (DJI v4, DJI v5, the fake replay adapter) provides to the core.
 *
 * Rules for implementers:
 * - Re-register SDK callbacks on every product (re)connect, e.g. after a battery swap. The connector
 *   lost all positions after an autopilot reboot because it subscribed once (launch-risks B4).
 * - [resubscribe] must be safe to call at any time; the core calls it when telemetry goes stale.
 * - Never import an SDK type into the core; map to these types inside the adapter.
 */
interface DroneAdapter {
    val link: StateFlow<LinkState>
    val capabilities: StateFlow<DroneCapabilities?>

    /** Raw aircraft states at the SDK's own rate (DJI v4: 10 Hz; v5: per key, to measure). The core downsamples. */
    val states: SharedFlow<DroneState>

    /** The connected aircraft's mission operator, or null if it has none or nothing is connected. */
    fun missionOperator(): MissionOperator?

    fun start()
    fun stop()
    fun resubscribe()

    /** Restriction zones the aircraft's own system knows near it (DJI GEO); shown read-only. */
    suspend fun nearbyRestrictions(): List<Zone> = emptyList()

    /** The aircraft's checks, settings and calibrations; null if this adapter has none. */
    fun aircraftSetup(): AircraftSetup? = null
}

sealed interface LinkState {
    data object Disconnected : LinkState
    data object Connecting : LinkState
    data class Connected(val identity: AircraftIdentity) : LinkState
}

@Serializable
data class AircraftIdentity(
    /** Flight controller serial. Goes into the declaration's aircraft_id and every sample's uas_id. */
    val serial: String,
    val model: String,
    val firmware: String? = null,
)

enum class MissionSupport { NONE, WAYPOINT_V1, WAYPOINT_V2, WPML, VIRTUAL_STICK_EXPERIMENTAL }

data class DroneCapabilities(
    val model: String,
    val missionSupport: MissionSupport,
    val missionLimits: MissionLimits?,
    val hasRtk: Boolean,
    val nativeRateHz: Int,
    /** The mapping camera, for survey planning; null if unknown (the APK's default is used). */
    val camera: CameraModel? = null,
)

/** One aircraft state as the SDK reported it. */
data class DroneState(
    /** Device wall clock when the state arrived, epoch ms (not yet corrected to GNSS time). */
    val timeMs: Long,
    /** NaN when the aircraft has no position fix. */
    val lat: Double,
    val lng: Double,
    /** Barometric height above the take-off point, metres. */
    val heightAboveTakeoffM: Double,
    /** NED velocities, m/s (DJI v4: velocityX = north, Y = east, Z = down). */
    val velocityNorthMps: Double,
    val velocityEastMps: Double,
    val velocityDownMps: Double,
    val yawDeg: Double,
    val isFlying: Boolean,
    val motorsOn: Boolean,
    /** 0..5, DJI GPSSignalLevel; null if unknown. */
    val gpsLevel: Int? = null,
    val satellites: Int? = null,
    val flightMode: String? = null,
    val home: LatLng? = null,
    /** DJI `takeoffLocationAltitude`, "relative to sea level". Null if not reported. */
    val takeoffMslM: Double? = null,
    val rtk: RtkState? = null,
    val batteryPercent: Int? = null,
    /**
     * Photos (JPEG) the aircraft's camera reported since the adapter started; it only counts up.
     * Null if the adapter can't tell. Gives each flight's image count for the processing hand-off.
     */
    val photoCount: Int? = null,
) {
    val position: LatLng get() = LatLng(lat, lng)
}

data class RtkState(val fixed: Boolean, val ellipsoidM: Double?, val sigmaM: Double?)

/** Thin, SDK-neutral mission control. Every call returns the SDK's error text on failure. */
interface MissionOperator {
    val events: SharedFlow<MissionEvent>
    suspend fun upload(plan: MissionPlan): Result<Unit>
    suspend fun start(): Result<Unit>
    suspend fun pause(): Result<Unit>
    suspend fun resume(): Result<Unit>
    suspend fun stop(): Result<Unit>
}

sealed interface MissionEvent {
    data class UploadProgress(val uploaded: Int, val total: Int) : MissionEvent
    data object Uploaded : MissionEvent
    data object Started : MissionEvent
    data class Progress(val targetWaypoint: Int, val total: Int, val reached: Boolean) : MissionEvent
    data class Finished(val error: String?) : MissionEvent
}

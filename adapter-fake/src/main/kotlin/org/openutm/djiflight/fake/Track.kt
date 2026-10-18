package org.openutm.djiflight.fake

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng
import kotlin.math.cos
import kotlin.math.sin

/**
 * A recorded (or generated) flight for [ReplayDroneAdapter], stored as JSON Lines:
 *
 *   {"type":"header", "model", "serial", "takeoff":{lat,lng}, "dji_takeoff_msl_m", "device_gnss_ellipsoid_m", ...}
 *   {"type":"state", "t": ms since start, "lat","lng","h","vn","ve","vd","yaw","flying","motors","gps","sats","mode"}
 *   {"type":"fault", "t": ms, "kind": "LINK_DROP" | "STALE" | "GPS_LOSS", "duration_ms": n}
 *
 * `tools/mavlink_journal_to_track.py` writes these from the UTM connector's MAVLink journal.
 */
data class Track(val header: TrackHeader, val states: List<TrackState>, val faults: List<TrackFault>) {
    val durationMs: Long get() = states.lastOrNull()?.t ?: 0

    fun withFaults(extra: List<TrackFault>) = copy(faults = (faults + extra).sortedBy { it.t })

    /**
     * Prepends [leadMs] on the ground at the take-off point with motors off, so a pilot (or a test)
     * can plan and declare before the recorded take-off. Faults move with the track.
     */
    fun withGroundLead(leadMs: Long, stepMs: Long = 100): Track {
        val first = states.firstOrNull() ?: return this
        val lead = (0L until leadMs step stepMs).map { t ->
            TrackState(t, header.takeoff.lat, header.takeoff.lng, 0.0, flying = false, motors = false, gps = first.gps, sats = first.sats, mode = first.mode)
        }
        return copy(states = lead + states.map { it.copy(t = it.t + leadMs) }, faults = faults.map { it.copy(t = it.t + leadMs) })
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun parse(text: String): Track {
            var header: TrackHeader? = null
            val states = ArrayList<TrackState>()
            val faults = ArrayList<TrackFault>()
            for (line in text.lineSequence().map(String::trim).filter { it.isNotEmpty() }) {
                val o = json.parseToJsonElement(line).jsonObject
                when (o["type"]?.jsonPrimitive?.content) {
                    "header" -> header = json.decodeFromJsonElement(TrackHeader.serializer(), o)
                    "state" -> states += json.decodeFromJsonElement(TrackState.serializer(), o)
                    "fault" -> faults += json.decodeFromJsonElement(TrackFault.serializer(), o)
                }
            }
            return Track(requireNotNull(header) { "track has no header line" }, states.sortedBy { it.t }, faults.sortedBy { it.t })
        }

        /** Tracks bundled in this module's resources, e.g. `sitl-auto-mission.jsonl`. */
        fun bundled(name: String): Track = parse(
            requireNotNull(Track::class.java.getResourceAsStream("/tracks/$name")) { "no bundled track $name" }.bufferedReader().readText(),
        )

        val BUNDLED = listOf("sitl-auto-mission.jsonl")
    }
}

@Serializable
data class TrackHeader(
    val model: String,
    val serial: String,
    val takeoff: LatLng,
    @SerialName("dji_takeoff_msl_m") val djiTakeoffMslM: Double? = null,
    @SerialName("device_gnss_ellipsoid_m") val deviceGnssEllipsoidM: Double? = null,
    @SerialName("device_gnss_sigma_m") val deviceGnssSigmaM: Double = 3.0,
    val source: String = "",
)

@Serializable
data class TrackState(
    val t: Long,
    val lat: Double,
    val lng: Double,
    val h: Double,
    val vn: Double = 0.0,
    val ve: Double = 0.0,
    val vd: Double = 0.0,
    val yaw: Double = 0.0,
    val flying: Boolean,
    val motors: Boolean = flying,
    val gps: Int? = 5,
    val sats: Int? = 12,
    val mode: String? = null,
)

@Serializable
enum class FaultKind {
    /** Aircraft link lost, then back (RC out of range, or a battery swap: same serial). */
    LINK_DROP,

    /** Link stays up but states stop until [ReplayDroneAdapter.resubscribe] (the B4 failure mode). */
    STALE,

    /** Positions become NaN (no GNSS fix) for the duration. */
    GPS_LOSS,
}

@Serializable
data class TrackFault(
    val t: Long,
    val kind: FaultKind,
    @SerialName("duration_ms") val durationMs: Long,
)

/** Deterministic flights for tests and the `sim` app when no recorded track fits. */
object SyntheticTracks {
    /**
     * Manual VLOS flight: 10 s on the ground (5 s motors off, 5 s on), climb to [heightM] at 2 m/s,
     * [loops] circles of [radiusM] at [speedMps], straight back, descend, land, 5 s motors off. 10 Hz.
     */
    fun manualVlos(
        takeoff: LatLng,
        serial: String = "SYNTHP4PV2-0001",
        groundMslM: Double = 1661.0,
        groundEllipsoidM: Double = 1661.0 - 16.06,
        heightM: Double = 40.0,
        radiusM: Double = 120.0,
        speedMps: Double = 5.0,
        loops: Int = 1,
    ): Track {
        val states = ArrayList<TrackState>()
        var t = 0L
        fun add(p: LatLng, h: Double, vn: Double = 0.0, ve: Double = 0.0, vd: Double = 0.0, flying: Boolean, motors: Boolean, mode: String) {
            states += TrackState(t, p.lat, p.lng, h, vn, ve, vd, 0.0, flying, motors, 5, 14, mode)
            t += 100
        }
        repeat(50) { add(takeoff, 0.0, flying = false, motors = false, mode = "P-GPS") }
        repeat(50) { add(takeoff, 0.0, flying = false, motors = true, mode = "P-GPS") }
        var h = 0.0
        while (h < heightM) { h = minOf(heightM, h + 0.2); add(takeoff, h, vd = -2.0, flying = true, motors = true, mode = "P-GPS") }
        // out to the circle
        val outSteps = (radiusM / speedMps * 10).toInt()
        for (i in 1..outSteps) add(Geo.destination(takeoff, 90.0, radiusM * i / outSteps), h, ve = speedMps, flying = true, motors = true, mode = "P-GPS")
        val stepsPerLoop = (2 * Math.PI * radiusM / speedMps * 10).toInt()
        for (i in 0 until stepsPerLoop * loops) {
            val bearing = 90.0 + 360.0 * i / stepsPerLoop // clockwise from the east point
            val dir = Math.toRadians(bearing + 90)        // tangent of a clockwise circle
            add(Geo.destination(takeoff, bearing, radiusM), h, vn = speedMps * cos(dir), ve = speedMps * sin(dir), flying = true, motors = true, mode = "P-GPS")
        }
        for (i in outSteps downTo 0) add(Geo.destination(takeoff, 90.0, radiusM * i / outSteps), h, ve = -speedMps, flying = true, motors = true, mode = "P-GPS")
        while (h > 0) { h = maxOf(0.0, h - 0.15); add(takeoff, h, vd = 1.5, flying = h > 0, motors = true, mode = "P-GPS") }
        repeat(20) { add(takeoff, 0.0, flying = false, motors = true, mode = "P-GPS") }
        repeat(50) { add(takeoff, 0.0, flying = false, motors = false, mode = "P-GPS") }
        return Track(
            TrackHeader("Phantom 4 Pro V2.0 (synthetic)", serial, takeoff, groundMslM, groundEllipsoidM, 3.0, "SyntheticTracks.manualVlos"),
            states, emptyList(),
        )
    }
}


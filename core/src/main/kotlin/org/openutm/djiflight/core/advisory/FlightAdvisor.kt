package org.openutm.djiflight.core.advisory

import org.openutm.djiflight.core.adapter.AircraftStatus
import org.openutm.djiflight.core.adapter.DroneState
import org.openutm.djiflight.core.adapter.Health
import org.openutm.djiflight.core.adapter.RcLostAction
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.session.FlightSnapshot
import org.openutm.djiflight.core.time.Rfc3339
import org.openutm.djiflight.core.zones.Zone
import org.openutm.djiflight.core.zones.ZoneIndex
import kotlin.math.min

enum class Level { INFO, WARNING, CRITICAL }

/**
 * One warning. [message] is English, for logs and exports; screens show their own translation of
 * [key] (by default the code) filled with the same [args], and fall back to [message].
 */
data class Advisory(val code: String, val level: Level, val message: String, val key: String = code, val args: List<String> = emptyList()) {
    companion object {
        /** [template] uses positional `%1${'$'}s` placeholders, the same as the app's string resource for [key]. */
        fun of(code: String, level: Level, template: String, vararg args: Any?, key: String = code): Advisory {
            val a = args.map { it.toString() }
            return Advisory(code, level, String.format(template, *a.toTypedArray()), key, a)
        }
    }
}

private fun m0(v: Double) = "%.0f".format(v)

data class AdvisorInput(
    val snapshot: FlightSnapshot?,
    val state: DroneState?,
    val telemetryStale: Boolean,
    val zones: ZoneIndex?,
    /** Zones reported by the aircraft itself (DJI GEO), shown and checked the same way. */
    val aircraftZones: List<Zone> = emptyList(),
    val nowMs: Long,
    /** Samples recorded but not yet accepted by Blender. */
    val pendingSamples: Long = 0,
    val online: Boolean = true,
    /** The device's setup has a pre-flight checklist. */
    val checklistRequired: Boolean = false,
    /** The aircraft's own settings and sensors, when the adapter reads them (docs/12, Pre-flight panel). */
    val aircraft: AircraftStatus? = null,
    /**
     * How far the ground under the aircraft is below its take-off point (negative: higher), from the
     * lab's terrain model; null without one. Turns height above take-off into height above ground.
     */
    val groundBelowTakeoffM: Double? = null,
)

data class AdvisorConfig(
    val lowBatteryPercent: Int = 30,
    val criticalBatteryPercent: Int = 20,
    val zoneProximityM: Double = 100.0,
    val windowEndingMs: Long = 15 * 60_000L,
    val weakGpsLevel: Int = 2,
)

/**
 * Pilot warnings, computed on the device with no network. Warnings only: v4 can't enforce our zones
 * (DJI's own GEO system is the only enforcement), and the app never commands the aircraft to avoid them.
 */
object FlightAdvisor {
    /** In flight, warn when the terrain model puts the ground this close below, once this far above take-off. */
    const val TERRAIN_LOW_M = 20.0
    const val TERRAIN_WATCH_FROM_M = 30.0

    fun advise(i: AdvisorInput, c: AdvisorConfig = AdvisorConfig()): List<Advisory> = buildList {
        val s = i.state
        val f = i.snapshot
        val plan = f?.plan
        if (i.telemetryStale) add(Advisory.of("TELEMETRY_STALE", Level.CRITICAL, "No telemetry from the aircraft for several seconds"))

        s?.batteryPercent?.let { b ->
            when {
                b <= c.criticalBatteryPercent -> add(Advisory.of("BATTERY_CRITICAL", Level.CRITICAL, "Battery %1${'$'}s %%: land now", b))
                b <= c.lowBatteryPercent -> add(Advisory.of("BATTERY_LOW", Level.WARNING, "Battery %1${'$'}s %%", b))
            }
        }
        if (s != null && (s.gpsLevel ?: 5) <= c.weakGpsLevel) add(Advisory.of("GPS_WEAK", Level.WARNING, "Weak GNSS (level %1${'$'}s, %2${'$'}s satellites)", s.gpsLevel ?: 0, s.satellites ?: 0))

        if (f != null && plan != null) {
            if (f.aircraftMismatch) add(Advisory.of("AIRCRAFT_MISMATCH", Level.WARNING, "Connected aircraft %1${'$'}s is not the declared %2${'$'}s", f.aircraft?.serial, plan.aircraftSerial))
            if (s?.isFlying == true && f.declarationId == null) add(Advisory.of("NOT_DECLARED", Level.WARNING, "Flying without a declaration in Blender (it will be declared when online, if the window allows)"))
            f.takeoffReference?.takeIf { it.pilotCheckNeeded }?.let {
                add(Advisory.of("ALTITUDE_CHECK", Level.WARNING, "Controller and aircraft disagree on take-off height by %1${'$'}s m: choose one in Pre-flight", m0(it.disagreementM ?: 0.0)))
            }
            val ref = f.altitudeReference
            val dp = f.declarationPlan
            if (ref != null && dp != null) {
                if (ref.ellipsoidM < dp.minAltW84M || ref.ellipsoidM + plan.plannedMaxHeightM.coerceAtMost(plan.legalMaxHeightM) > dp.maxAltW84M + 0.5) {
                    add(Advisory.of("BAND_MISMATCH", Level.WARNING, "Take-off height %1${'$'}s m W84 is outside the declared band %2${'$'}s-%3${'$'}s m; re-declare when online", m0(ref.ellipsoidM), m0(dp.minAltW84M), m0(dp.maxAltW84M)))
                }
            }
            if (s != null && s.isFlying) {
                val limit = min(plan.plannedMaxHeightM, plan.legalMaxHeightM)
                if (s.heightAboveTakeoffM > plan.legalMaxHeightM) add(Advisory.of("ABOVE_LEGAL_HEIGHT", Level.CRITICAL, "%1${'$'}s m above take-off: legal limit is %2${'$'}s m", m0(s.heightAboveTakeoffM), m0(plan.legalMaxHeightM)))
                else if (s.heightAboveTakeoffM > limit) add(Advisory.of("ABOVE_PLANNED_HEIGHT", Level.WARNING, "%1${'$'}s m above take-off: planned maximum is %2${'$'}s m", m0(s.heightAboveTakeoffM), m0(limit)))
                if (s.position.isValid && !Geo.contains(plan.area, s.position)) add(Advisory.of("OUTSIDE_AREA", Level.WARNING, "Outside the declared area"))
                i.groundBelowTakeoffM?.let { below ->
                    val agl = s.heightAboveTakeoffM + below
                    // Legal limits count from the ground below: off a ridge, 100 m above take-off can be 200 m above the valley.
                    if (agl > plan.legalMaxHeightM + 5) add(Advisory.of("ABOVE_LEGAL_AGL", Level.WARNING, "About %1${'$'}s m above the ground here (terrain model): legal limit %2${'$'}s m", m0(agl), m0(plan.legalMaxHeightM)))
                    // Only once well clear of take-off and landing, where being low is the point.
                    if (s.heightAboveTakeoffM > TERRAIN_WATCH_FROM_M && agl < TERRAIN_LOW_M) add(Advisory.of("TERRAIN_CLOSE", Level.WARNING, "Ground rising: about %1${'$'}s m below the aircraft (terrain model)", m0(agl)))
                }
            }
            dp?.let {
                val left = it.endMs - i.nowMs
                if (left in 0..c.windowEndingMs && !f.recordingClosed) add(Advisory.of("WINDOW_ENDING", Level.WARNING, "Declared window ends at %1${'$'}s UTC", Rfc3339.format(it.endMs).substring(11, 16)))
            }
            f.windowExpired?.let { add(Advisory.of("NOT_SYNCED", Level.WARNING, "Window closed with samples still on the device: export this flight")) }
        }

        if (s != null && s.position.isValid) {
            val hits = (i.zones?.near(s.position, c.zoneProximityM).orEmpty()) +
                ZoneIndex(i.aircraftZones, 0).near(s.position, c.zoneProximityM)
            hits.minByOrNull { it.distanceM }?.let { h ->
                if (h.distanceM == 0.0) add(Advisory.of("IN_ZONE", Level.CRITICAL, "Inside %1${'$'}s (%2${'$'}s)", h.zone.name, h.zone.source))
                else add(Advisory.of("NEAR_ZONE", Level.WARNING, "%1${'$'}s m from %2${'$'}s (%3${'$'}s)", m0(h.distanceM), h.zone.name, h.zone.source))
            }
        }
        if (plan != null && f?.recordingClosed != true) addAll(aircraftChecks(i.aircraft, plan, s?.isFlying == true))
        val f0 = i.snapshot
        if (i.checklistRequired && f0?.plan != null && f0.checklist == null && !f0.recordingClosed) {
            add(Advisory.of("CHECKLIST", if (i.state?.isFlying == true) Level.WARNING else Level.INFO, "Lab checklist not done: open Pre-flight"))
        }
        if (i.pendingSamples > 0 && !i.online) add(Advisory.of("OFFLINE", Level.INFO, "Offline: %1${'$'}s samples stored, will upload when online", i.pendingSamples))
    }.sortedByDescending { it.level }

    /**
     * The aircraft's settings against the declaration: none of DJI's apps know the declared volume.
     * A return-to-home climb above the declared ceiling leaves the declared airspace; so does an aircraft
     * height limit above the legal one. A mission's safety rests on RTH when the RC link drops (docs/12).
     */
    fun aircraftChecks(a: AircraftStatus?, plan: org.openutm.djiflight.core.model.FlightPlan, flying: Boolean = false): List<Advisory> = buildList {
        if (a == null) return@buildList
        val ceiling = min(plan.plannedMaxHeightM, plan.legalMaxHeightM)
        a.settings.returnHomeHeightM?.let { rth ->
            if (rth > ceiling + 0.5) add(Advisory.of("RTH_ABOVE_DECLARED", Level.WARNING, "Return-to-home height %1${'$'}s m is above the declared %2${'$'}s m: lower it in Pre-flight", rth, m0(ceiling)))
        }
        a.settings.maxAltitudeM?.let { max ->
            if (max > plan.legalMaxHeightM + 0.5) add(Advisory.of("HEIGHT_LIMIT_ABOVE_LEGAL", Level.INFO, "Aircraft height limit %1${'$'}s m is above the legal %2${'$'}s m", max, m0(plan.legalMaxHeightM)))
        }
        a.settings.rcLostAction?.let { act ->
            if (plan.mission != null && act != RcLostAction.RETURN_HOME) {
                if (act == RcLostAction.HOVER) add(Advisory.of("RC_LOST_NOT_RTH", Level.WARNING, "If the RC signal is lost the aircraft will hover: missions expect return to home", key = "RC_LOST_HOVER"))
                else add(Advisory.of("RC_LOST_NOT_RTH", Level.WARNING, "If the RC signal is lost the aircraft will land: missions expect return to home", key = "RC_LOST_LAND"))
            }
        }
        val sensorLevel = if (flying) Level.CRITICAL else Level.WARNING
        if (a.compass.health == Health.ERROR) add(
            a.compass.detail?.let { Advisory.of("COMPASS_ERROR", sensorLevel, "Compass error: %1${'$'}s. Calibrate in Pre-flight", it, key = "COMPASS_ERROR_DETAIL") }
                ?: Advisory.of("COMPASS_ERROR", sensorLevel, "Compass error. Calibrate in Pre-flight"),
        )
        if (a.imu.health == Health.ERROR) add(
            a.imu.detail?.let { Advisory.of("IMU_ERROR", sensorLevel, "IMU error: %1${'$'}s", it, key = "IMU_ERROR_DETAIL") } ?: Advisory.of("IMU_ERROR", sensorLevel, "IMU error"),
        )
    }

    /** Zones the declared area touches, checked before declaring. */
    fun areaConflicts(area: List<org.openutm.djiflight.core.geo.LatLng>, zones: ZoneIndex?, aircraftZones: List<Zone> = emptyList()): List<Zone> =
        (zones?.intersecting(area).orEmpty()) + ZoneIndex(aircraftZones, 0).intersecting(area)
}

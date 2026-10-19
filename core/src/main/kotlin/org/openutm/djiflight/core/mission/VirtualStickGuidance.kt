package org.openutm.djiflight.core.mission

import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.geo.LocalPlane
import kotlin.math.*

/** Monotonic timestamps: never drive flight control from a corrected/network wall clock. */
data class StickState(
    val receivedMs: Long, val position: LatLng, val heightM: Double, val yawDeg: Double,
    val airborne: Boolean, val gpsHealthy: Boolean, val pilotOverride: Boolean,
    val authorityAvailable: Boolean, val batteryPercent: Int?, val photoCount: Int,
    val returnHomeHeightM: Double? = null, val failsafeReturnsHome: Boolean = false,
)
data class StickCommand(val northMps: Double = 0.0, val eastMps: Double = 0.0, val upMps: Double = 0.0, val yawRateDegS: Double = 0.0)
data class GuidanceStep(val command: StickCommand, val reached: Boolean = false, val failure: String? = null)

/** Bounded stop-at-waypoint controller. No arming/takeoff, obstacle sensing or authority acquisition. */
class VirtualStickGuidance(private val origin: LatLng, private val radiusM: Double = 200.0) {
    private var lastTick: Long? = null
    private var trackedTarget: MissionWaypoint? = null
    private var bestError = Double.POSITIVE_INFINITY
    private var lastProgressMs = 0L
    fun step(nowMs: Long, state: StickState?, target: MissionWaypoint, speedMps: Double, held: Boolean = false): GuidanceStep {
        fun fail(reason: String) = GuidanceStep(StickCommand(), failure = reason)
        val gap = lastTick?.let { nowMs - it }; lastTick = nowMs
        if (gap != null && (gap < 0 || gap > 500)) return fail("control loop missed its deadline")
        if (state == null || nowMs - state.receivedMs !in 0..500) return fail("aircraft telemetry is stale")
        if (state.pilotOverride) return fail("pilot override: control returned to the pilot")
        if (!state.authorityAvailable) return fail("Virtual Stick authority is unavailable")
        if (!state.airborne || !state.gpsHealthy) return fail("airborne GPS flight is required")
        if (state.batteryPercent == null || state.batteryPercent <= 25) return fail("battery reserve reached or unknown")
        if (!state.position.isValid || !state.heightM.isFinite() || !state.yawDeg.isFinite()) return fail("invalid aircraft telemetry")
        if (!target.position.isValid || !target.heightM.isFinite() || !speedMps.isFinite() || speedMps <= 0) return fail("invalid navigation target")
        if (Geo.distanceM(origin, state.position) > radiusM || Geo.distanceM(origin, target.position) > radiusM) return fail("experimental operating radius exceeded")
        if (target.heightM !in 5.0..120.0 || state.heightM < 3 || state.heightM > 125) return fail("experimental height envelope exceeded")
        val delta = LocalPlane(state.position).toXY(target.position)
        val distance = hypot(delta.x, delta.y)
        val h = target.heightM - state.heightM
        val desiredYaw = target.headingDeg ?: if (distance > 1.5) Math.toDegrees(atan2(delta.x, delta.y)) else state.yawDeg
        val yawError = ((desiredYaw - state.yawDeg) % 360 + 540) % 360 - 180
        val error = distance + abs(h) + abs(yawError) / 10
        if (target != trackedTarget || held || error < bestError - 0.25) {
            trackedTarget = target; bestError = error; lastProgressMs = nowMs
        } else if (nowMs - lastProgressMs > 15_000) return fail("navigation made no progress for 15 seconds")
        val reached = distance <= 1.5 && abs(h) <= 1 && abs(yawError) <= 10
        val speed = min(speedMps.coerceAtMost(3.0), distance * 0.5)
        // Settle height and heading before translating, rather than flying sideways into a survey.
        val moving = abs(h) <= 2 && abs(yawError) <= 20 && distance > 1.0
        return GuidanceStep(StickCommand(
            northMps = if (moving) delta.y / distance * speed else 0.0,
            eastMps = if (moving) delta.x / distance * speed else 0.0,
            upMps = (h * 0.5).coerceIn(-1.0, 1.0),
            yawRateDegS = (yawError * 0.5).coerceIn(-15.0, 15.0),
        ), reached)
    }
}

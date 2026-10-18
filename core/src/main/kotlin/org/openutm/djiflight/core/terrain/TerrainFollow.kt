package org.openutm.djiflight.core.terrain

import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.core.mission.MissionPlan
import org.openutm.djiflight.core.mission.MissionWaypoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.round

/**
 * Terrain following (DroneDeploy's "terrain awareness"): heights above take-off that keep a survey at
 * one height above the terrain model, so its GSD and clearance stay the same over a hillside.
 *
 * - The ground is sampled at each waypoint and every [SAMPLE_STEP_M] between; the height wanted there
 *   is the ground plus the height above it.
 * - Climbs and descents are capped (m/s, at the leg's speed): where the ground rises faster the route
 *   starts climbing early, where it drops away faster it comes down late. Never lower than wanted.
 * - A waypoint is added wherever flying straight between two would be more than the tolerance off
 *   that height; along an even slope, none is. More than [MissionLimits.maxWaypoints] are flown as
 *   consecutive uploads ([org.openutm.djiflight.core.mission.MissionValidator.split]).
 * - Heights stay relative to take-off (Waypoint 1.0, WPML `relativeToStartPoint`), so this works on
 *   every aircraft; DJI's `realTimeFollowSurface` is only on some. Take off where planned: from ground
 *   that is higher or lower, the whole route is too.
 * - An added waypoint takes the leg's speed and a heading between its ends, and no photo of its own:
 *   survey photos stay distance-triggered along the legs. Travel points and their legs stay as they are.
 */
object TerrainFollow {
    /** About half a pixel of Mapterhorn's zoom-12 tiles. */
    const val SAMPLE_STEP_M = 10.0
    /** The terrain model's own error is about 8 m (σ): a few metres is as close as it is worth following. */
    const val TOLERANCE_M = 5.0
    /** Well inside every supported aircraft's own limits (P4 Pro: 5 m/s up, 3 m/s down). */
    const val MAX_CLIMB_MPS = 3.0
    const val MAX_DESCENT_MPS = 2.0

    /**
     * [plan] with heights [aboveGroundM] above the terrain model, from the take-off point [takeoff].
     * Each waypoint records its ground ([MissionWaypoint.groundM]), the plan the height it follows
     * ([MissionPlan.terrainFollowM]), and the route's top becomes the return-to-home height of every
     * part. A travel altitude no longer above that top is dropped. Null if the model has no height at
     * the take-off point or somewhere along the route.
     */
    fun follow(
        plan: MissionPlan,
        takeoff: LatLng,
        aboveGroundM: Double,
        terrain: TerrainModel,
        limits: MissionLimits,
        toleranceM: Double = TOLERANCE_M,
        maxClimbMps: Double = MAX_CLIMB_MPS,
        maxDescentMps: Double = MAX_DESCENT_MPS,
    ): MissionPlan? {
        require(aboveGroundM > 0 && toleranceM > 0 && maxClimbMps > 0 && maxDescentMps > 0)
        val t0 = terrain.elevationM(takeoff) ?: return null
        val wps = plan.waypoints
        val out = ArrayList<MissionWaypoint>(wps.size * 2)
        var i = 0
        while (i < wps.size) {
            if (wps[i].transit) { out += wps[i++]; continue }
            var j = i
            while (j + 1 < wps.size && !wps[j + 1].transit) j++
            out += stretch(wps.subList(i, j + 1), plan.autoSpeedMps, t0, aboveGroundM, terrain, limits, toleranceM, maxClimbMps, maxDescentMps) ?: return null
            i = j + 1
        }
        val top = out.filter { !it.transit }.maxOfOrNull { it.heightM } ?: return null
        val travel = plan.travelHeightM?.takeIf { it > top }
        return plan.copy(waypoints = out, terrainFollowM = aboveGroundM, travelHeightM = travel, returnHomeHeightM = max(top, travel ?: top))
    }

    private class Sample(
        /** The run's leg this sample starts or lies on: its waypoint index, or the one before. */
        val leg: Int,
        /** Along that leg, 0 at its first waypoint. */
        val f: Double,
        val p: LatLng,
        /** Metres along the run. */
        val s: Double,
        val ground: Double,
        var h: Double,
    )

    /** One stretch of survey waypoints with no travel point among them. */
    private fun stretch(
        run: List<MissionWaypoint>, autoSpeedMps: Double, t0: Double, above: Double, terrain: TerrainModel,
        limits: MissionLimits, tol: Double, climbMps: Double, descentMps: Double,
    ): List<MissionWaypoint>? {
        val samples = ArrayList<Sample>()
        val at = IntArray(run.size)
        fun sample(leg: Int, f: Double, p: LatLng, s: Double): Sample? {
            val g = (terrain.elevationM(p) ?: return null) - t0
            return Sample(leg, f, p, s, g, g + above)
        }
        var s = 0.0
        for (k in run.indices) {
            val w = run[k]
            if (k > 0) {
                val a = run[k - 1]
                val d = Geo.distanceM(a.position, w.position)
                val steps = max(1, ceil(d / SAMPLE_STEP_M).toInt())
                for (m in 1 until steps) {
                    val f = m.toDouble() / steps
                    samples += sample(k - 1, f, LatLng(a.lat + (w.lat - a.lat) * f, a.lng + (w.lng - a.lng) * f), s + d * f) ?: return null
                }
                s += d
            }
            at[k] = samples.size
            samples += sample(k, 0.0, w.position, s) ?: return null
        }

        // Cap the vertical speed on each leg by raising the route, never lowering it: first the descents
        // (forwards: no faster down than the cap), then the climbs (backwards: high enough to climb to the next).
        fun gradient(leg: Int, mps: Double) = mps / (run[leg].speedMps ?: autoSpeedMps)
        for (n in 1 until samples.size) {
            val a = samples[n - 1]
            samples[n].h = max(samples[n].h, a.h - gradient(a.leg, descentMps) * (samples[n].s - a.s))
        }
        for (n in samples.size - 2 downTo 0) {
            val a = samples[n]
            a.h = max(a.h, samples[n + 1].h - gradient(a.leg, climbMps) * (samples[n + 1].s - a.s))
        }

        // Keep every waypoint, and add one wherever straight flight between kept ones strays further than tol.
        val keep = BooleanArray(samples.size)
        at.forEach { keep[it] = true }
        fun split(lo: Int, hi: Int) {
            var worst = -1
            var worstErr = tol
            for (n in lo + 1 until hi) {
                val q = samples[n]
                val line = samples[lo].h + (samples[hi].h - samples[lo].h) * (q.s - samples[lo].s) / (samples[hi].s - samples[lo].s)
                val err = abs(q.h - line)
                if (err > worstErr && q.s - samples[lo].s >= limits.minSpacingM && samples[hi].s - q.s >= limits.minSpacingM) { worst = n; worstErr = err }
            }
            if (worst < 0) return
            keep[worst] = true
            split(lo, worst)
            split(worst, hi)
        }
        for (k in 1 until run.size) split(at[k - 1], at[k])

        return samples.indices.filter { keep[it] }.map { n ->
            val q = samples[n]
            val w = run[q.leg]
            val h = ceil(q.h * 100) / 100 // whole centimetres, never lower
            val g = round(q.ground * 10) / 10
            if (q.f == 0.0) w.copy(heightM = h, groundM = g)
            else MissionWaypoint(q.p.lat, q.p.lng, h, speedMps = w.speedMps, headingDeg = heading(w.headingDeg, run[q.leg + 1].headingDeg, q.f), groundM = g)
        }
    }

    /** Part way from [a] to [b] the short way round; null without a heading at the start. */
    private fun heading(a: Double?, b: Double?, f: Double): Double? = when {
        a == null -> null
        b == null -> a
        else -> a + (((b - a) % 360 + 540) % 360 - 180) * f
    }
}

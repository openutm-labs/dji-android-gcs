package org.openutm.djiflight.core.mission

import kotlinx.serialization.Serializable
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.geo.LocalPlane
import org.openutm.djiflight.core.geo.XY
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** A DJI-free mission. The v4 adapter maps it to a Waypoint 1.0 `WaypointMission`; the v5 adapter to a WPML KMZ ([WpmlWriter]). */
@Serializable
data class MissionPlan(
    val waypoints: List<MissionWaypoint>,
    val autoSpeedMps: Double,
    val maxSpeedMps: Double = 15.0,
    val finishAction: FinishAction = FinishAction.GO_HOME,
    /** Leave the mission on RC signal loss, so the aircraft's own failsafe (RTH) takes over. */
    val exitOnRcSignalLost: Boolean = true,
    /** Survey photos every N metres along the route; null = only the per-waypoint photo actions. */
    val photoDistanceIntervalM: Double? = null,
    /** Camera angle for the whole mission; -90 = straight down. */
    val gimbalPitchDeg: Double = -90.0,
    /**
     * Above take-off: climb to this before flying to the first waypoint, and leave the last at it
     * ([TravelLegs]). Null = fly straight to and from the mission at mission height. Above the mission only.
     */
    val travelHeightM: Double? = null,
    /** Terrain following ([org.openutm.djiflight.core.terrain.TerrainFollow]): the height above the terrain model the waypoints keep. Null = heights above take-off only. */
    val terrainFollowM: Double? = null,
    /**
     * Above take-off: the return-to-home height for every upload part, so a part low on a slope still
     * returns above the highest ground the route crosses. Terrain following sets the whole route's top.
     * Null = the part's own highest point.
     */
    val returnHomeHeightM: Double? = null,
    /** Planned geometry must be reviewed again if the connected survey camera changes. */
    val cameraName: String? = null,
) {
    /** Highest point flown, travel legs and the return home included: what the declaration and RTH height must cover. */
    val maxHeightM: Double get() = maxOf(waypoints.maxOf { it.heightM }, travelHeightM ?: Double.NEGATIVE_INFINITY, returnHomeHeightM ?: Double.NEGATIVE_INFINITY)
    val routeLengthM: Double get() = waypoints.zipWithNext { a, b -> Geo.distanceM(a.position, b.position) }.sum()

    /** Preserve inherited camera orientation when starting a later upload or checkpoint. */
    fun pitchAt(index: Int): Double = waypoints.subList(0, index + 1).lastOrNull { it.gimbalPitchDeg != null }?.gimbalPitchDeg ?: gimbalPitchDeg

    /**
     * How far the highest ground under the waypoints is above take-off (terrain following; 0 otherwise).
     * Legal limits count from the ground, so above take-off such a route may go this much higher.
     */
    val groundRiseM: Double get() = waypoints.maxOfOrNull { it.groundM ?: 0.0 }?.coerceAtLeast(0.0) ?: 0.0
}

@Serializable
data class MissionWaypoint(
    val lat: Double,
    val lng: Double,
    /** Above the take-off point. */
    val heightM: Double,
    val speedMps: Double? = null,
    val headingDeg: Double? = null,
    val takePhoto: Boolean = false,
    /** A travel leg point ([TravelLegs]): no survey photos on the legs to and from it. */
    val transit: Boolean = false,
    /** Terrain following: the terrain model's ground here, relative to the take-off point (negative = lower). Null = not known. */
    val groundM: Double? = null,
    /** Pitch set here and inherited until the next override; null initially uses the mission pitch. */
    val gimbalPitchDeg: Double? = null,
) {
    val position: LatLng get() = LatLng(lat, lng)

    /** Height above the ground below: the legal reference. Above take-off where the ground isn't known. */
    val aboveGroundM: Double get() = heightM - (groundM ?: 0.0)
}

@Serializable
enum class FinishAction { GO_HOME, HOVER, AUTO_LAND, GO_FIRST_WAYPOINT }

/**
 * SDK mission limits. Waypoint 1.0, checked against MSDK 4.18's own constants (`WaypointMission`,
 * `Waypoint`): 2..99 waypoints, speeds up to 15 m/s, altitude from -200 m. 4.18 declares no maximum
 * altitude (Float.MAX_VALUE), so 500 m is our cap; the legal limit (120 m) applies first anyway.
 * The 0.5 m minimum spacing is from DJI's docs, not a constant (docs/12, Open items).
 */
data class MissionLimits(
    val minWaypoints: Int,
    val maxWaypoints: Int,
    val maxSpeedMps: Double,
    val minHeightM: Double,
    val maxHeightM: Double,
    val minSpacingM: Double,
) {
    companion object {
        val WAYPOINT_V1 = MissionLimits(minWaypoints = 2, maxWaypoints = 99, maxSpeedMps = 15.0, minHeightM = -200.0, maxHeightM = 500.0, minSpacingM = 0.5)

        /**
         * WPML waylines (MSDK v5: Mavic 3 Enterprise, Matrice 400). DJI's WPML spec: waypoint index
         * 0..65535 per wayline, transitional speed up to 15 m/s. The spec gives no height range or
         * minimum spacing, so those stay as for Waypoint 1.0 (docs/12, Open items).
         */
        val WPML = MissionLimits(minWaypoints = 2, maxWaypoints = 65_535, maxSpeedMps = 15.0, minHeightM = -200.0, maxHeightM = 500.0, minSpacingM = 0.5)
    }
}

object MissionValidator {
    /**
     * Problems that block upload; empty means the plan is valid for [limits] and [legalMaxHeightM].
     * The legal limit counts from the ground: a terrain-following waypoint ([MissionWaypoint.groundM])
     * is checked by its height above the terrain model, any other by its height above take-off.
     */
    fun validate(plan: MissionPlan, limits: MissionLimits, legalMaxHeightM: Double): List<String> = buildList {
        if (!legalMaxHeightM.isFinite() || legalMaxHeightM <= 0) add("invalid operating height limit")
        if (!plan.autoSpeedMps.isFinite() || !plan.maxSpeedMps.isFinite() || plan.maxSpeedMps <= 0) add("mission speed must be positive and finite")
        if (plan.travelHeightM?.isFinite() == false || plan.returnHomeHeightM?.isFinite() == false) add("travel and return heights must be finite")
        if (plan.terrainFollowM != null && (!plan.terrainFollowM.isFinite() || plan.terrainFollowM <= 0)) add("terrain-follow height must be positive and finite")
        if (plan.photoDistanceIntervalM != null && (!plan.photoDistanceIntervalM.isFinite() || plan.photoDistanceIntervalM <= 0)) add("photo interval must be positive and finite")
        val n = plan.waypoints.size
        if (n < limits.minWaypoints) add("mission needs at least ${limits.minWaypoints} waypoints, has $n")
        if (n > limits.maxWaypoints) add("mission has $n waypoints; this aircraft takes at most ${limits.maxWaypoints} (split it)")
        if (plan.autoSpeedMps <= 0 || plan.autoSpeedMps > limits.maxSpeedMps) add("speed ${plan.autoSpeedMps} m/s is outside 0..${limits.maxSpeedMps}")
        if (plan.maxSpeedMps > limits.maxSpeedMps) add("max speed ${plan.maxSpeedMps} m/s exceeds ${limits.maxSpeedMps}")
        if (plan.gimbalPitchDeg !in -90.0..0.0) add("gimbal angle ${plan.gimbalPitchDeg}° is outside -90..0")
        plan.travelHeightM?.let { t ->
            val top = plan.waypoints.filter { !it.transit }.maxOfOrNull { it.heightM } ?: 0.0
            if (t <= top) add("travel altitude %.0f m must be above the mission altitude %.0f m".format(t, top))
            if (t > legalMaxHeightM) add("travel altitude %.0f m exceeds the legal limit $legalMaxHeightM m".format(t))
        }
        plan.waypoints.forEachIndexed { i, w ->
            if (!w.position.isValid || !w.heightM.isFinite()) add("waypoint $i has an invalid position or height")
            if (w.groundM?.isFinite() == false) add("waypoint $i has an invalid terrain height")
            if (w.speedMps != null && (!w.speedMps.isFinite() || w.speedMps <= 0 || w.speedMps > limits.maxSpeedMps)) add("waypoint $i has an invalid speed")
            if (w.headingDeg != null && !w.headingDeg.isFinite()) add("waypoint $i has an invalid heading")
            if (w.gimbalPitchDeg != null && (!w.gimbalPitchDeg.isFinite() || w.gimbalPitchDeg !in -90.0..0.0)) add("waypoint $i has an invalid gimbal pitch")
            if (w.heightM < limits.minHeightM || w.heightM > limits.maxHeightM) add("waypoint $i height ${w.heightM} m is outside the SDK range")
            if (w.groundM == null && w.heightM > legalMaxHeightM) add("waypoint $i height ${w.heightM} m exceeds the legal limit $legalMaxHeightM m")
            if (w.groundM != null && w.aboveGroundM > legalMaxHeightM) add("waypoint $i is %.0f m above the ground, over the legal limit $legalMaxHeightM m".format(w.aboveGroundM))
        }
        plan.waypoints.zipWithNext().forEachIndexed { i, (a, b) ->
            if (Geo.distanceM(a.position, b.position) < limits.minSpacingM) add("waypoints $i and ${i + 1} are closer than ${limits.minSpacingM} m")
        }
    }

    /** Splits a long plan into consecutive missions; each part starts where the previous one ended. */
    fun split(plan: MissionPlan, limits: MissionLimits): List<MissionPlan> {
        if (plan.waypoints.size <= limits.maxWaypoints) return listOf(plan)
        val parts = ArrayList<MissionPlan>()
        var start = 0
        while (start < plan.waypoints.size - 1) {
            val end = minOf(start + limits.maxWaypoints, plan.waypoints.size)
            val last = end == plan.waypoints.size
            parts += plan.copy(
                waypoints = plan.waypoints.subList(start, end),
                gimbalPitchDeg = plan.pitchAt(start),
                finishAction = if (last) plan.finishAction else FinishAction.HOVER,
            )
            start = end - 1
        }
        return parts
    }
}

/** A frame camera, enough to compute ground sample distance and footprints. */
data class CameraModel(
    val name: String,
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    val focalLengthMm: Double,
    val imageWidthPx: Int,
    val imageHeightPx: Int,
) {
    /** Metres per pixel at [heightM] above flat ground. */
    fun gsdM(heightM: Double) = heightM * sensorWidthMm / (focalLengthMm * imageWidthPx)

    companion object {
        /** Phantom 4 Pro V2.0 (FC6310S): 1" sensor, 8.8 mm, 5472 x 3648. Same sensor and lens on the P4 Pro and P4 Advanced. */
        val P4P_V2 = CameraModel("Phantom 4 Pro V2.0", 13.2, 8.8, 8.8, 5472, 3648)

        /** Mini 2 4:3 JPEG profile, derived from DJI's 83-degree diagonal FOV.
         * The 4 x 3 lengths are normalized, not a claim about physical sensor millimetres. */
        val MINI_2 = CameraModel("DJI Mini 2 4:3", 4.0, 3.0, 2.5 / kotlin.math.tan(Math.toRadians(83.0 / 2)), 4000, 3000)

        /** Mavic 2 Pro (Hasselblad L1D-20c): 1" sensor, 10.26 mm (28 mm equivalent), 5472 x 3648. */
        val MAVIC_2_PRO = CameraModel("Mavic 2 Pro", 13.2, 8.8, 10.26, 5472, 3648)

        /** Mavic 3 Enterprise wide camera: 4/3" sensor, 12.29 mm (24 mm equivalent), 5280 x 3956; DJI: GSD = H / 37.5 cm. */
        val MAVIC_3E = CameraModel("Mavic 3 Enterprise", 17.3, 13.0, 12.29, 5280, 3956)

        /** Zenmuse P1 with the DL 35 mm lens: full frame, 8192 x 5460; DJI: GSD = H / 80 cm. */
        val ZENMUSE_P1_35 = CameraModel("Zenmuse P1 35 mm", 35.9, 24.0, 35.0, 8192, 5460)

        /** Zenmuse L2 RGB mapping camera: 4/3" sensor, 24 mm equivalent, 5280 x 3956 (as the Mavic 3E wide camera). */
        val ZENMUSE_L2 = CameraModel("Zenmuse L2 RGB", 17.3, 13.0, 12.29, 5280, 3956)

        /**
         * Mavic 3T wide camera: 1/2" sensor, 48 MP 8000 x 6000, 24 mm equivalent, FOV 84° (DJI specs);
         * the 6.4 x 4.8 mm sensor and 4.44 mm focal length follow from those. GSD is for 48 MP photos
         * (H / 55.5 cm); a 12 MP photo setting doubles it, the overlap stays.
         */
        val MAVIC_3T = CameraModel("Mavic 3T wide", 6.4, 4.8, 4.44, 8000, 6000)

        /**
         * Mavic 3M: spacing from the multispectral cameras (1/2.8", 2592 x 1944, FOV 61.2° x 48.1°;
         * DJI specs), which see less than its RGB camera (the Mavic 3E's), so both get at least the
         * overlap asked for. 4.34 mm and 5.13 x 3.87 mm reproduce DJI's FOV; GSD H / 21.9 cm.
         */
        val MAVIC_3M = CameraModel("Mavic 3M multispectral", 5.13, 3.87, 4.34, 2592, 1944)

        /**
         * Zenmuse H30 / H30T wide camera: 1/1.3" sensor, 6.72 mm (24 mm equivalent), DFOV 82.1°,
         * 48 MP 8064 x 6048 (DJI specs); 9.36 x 7.02 mm follows from the DFOV. GSD for 48 MP photos
         * (H / 58 cm); the 12 MP setting (4032 x 3024) doubles it.
         */
        val ZENMUSE_H30_WIDE = CameraModel("Zenmuse H30 wide", 9.36, 7.02, 6.72, 8064, 6048)
    }
}

/** How a survey flies its area. The default gimbal angle is what the Plan screen sets on choosing one. */
enum class SurveyPattern(val defaultGimbalDeg: Double) {
    /** Parallel lines, camera straight down: orthomosaics and elevation models. */
    GRID(-90.0),
    /** The grid, then the same area again at right angles, camera tilted: 3D models. */
    CROSSHATCH(-70.0),
    /** Once round the area's edge, camera tilted towards its middle: the sides of buildings and trees in it. */
    PERIMETER(-45.0),
    /** Nadir plus four fixed-heading oblique passes; capture planning, not reconstruction. */
    FIVE_DIRECTION(-60.0),
    /** Parallel offsets from an open centreline, including its bends. */
    CORRIDOR(-90.0),
    /** Circular capture layers outside the supplied building footprint. */
    SURROUND(-45.0),
}

data class SurveySpec(
    /** Area to cover; open or closed ring. */
    val polygon: List<LatLng>,
    val camera: CameraModel,
    val heightM: Double,
    val frontOverlap: Double,
    val sideOverlap: Double,
    /** Direction of the flight lines, degrees clockwise from north. */
    val headingDeg: Double,
    val speedMps: Double,
    /** -90 = nadir. GSD and spacing are computed for nadir either way. */
    val gimbalPitchDeg: Double = -90.0,
    val travelHeightM: Double? = null,
    val pattern: SurveyPattern = SurveyPattern.GRID,
    val corridorWidthM: Double = 60.0,
    val orbitStandoffM: Double = 20.0,
    val orbitTopHeightM: Double? = null,
    val orbitLayerSpacingM: Double = 20.0,
)

data class SurveyResult(
    val plan: MissionPlan,
    /** 0 for a perimeter, which has no lines. */
    val lineSpacingM: Double,
    val photoSpacingM: Double,
    val gsdCm: Double,
    /** Survey lines flown; a perimeter counts as one. */
    val lineCount: Int,
) {
    val routeLengthM: Double get() = plan.routeLengthM
    val estimatedFlightTimeS: Double get() = routeLengthM / plan.autoSpeedMps
}

/**
 * Survey routes over a polygon ([SurveyPattern]). Grid lines run along [SurveySpec.headingDeg], with
 * the image's long side across track. Concave scan lines are clipped into separate strips and connectors stay
 * within the simple polygon. Holes must be planned as separate areas. Spacing is computed for a camera pointing straight down; a tilted
 * camera sees further, so its overlap is at least that.
 */
object SurveyPlanner {
    /** Perimeter: at most this much turn towards the middle between two waypoints, and this far apart. */
    const val PERIMETER_MAX_TURN_DEG = 15.0
    const val PERIMETER_MAX_STEP_M = 50.0

    fun plan(spec: SurveySpec): SurveyResult {
        require(spec.frontOverlap in 0.0..0.95 && spec.sideOverlap in 0.0..0.95) { "overlaps must be within 0..0.95" }
        require(spec.heightM > 0 && spec.speedMps > 0)
        require(spec.polygon.all { it.isValid }) { "route contains invalid coordinates" }
        require(spec.polygon.size <= 256) { "use at most 256 input points" }
        require(spec.heightM.isFinite() && spec.speedMps.isFinite())
        require(spec.gimbalPitchDeg.isFinite() && spec.gimbalPitchDeg in -90.0..0.0)
        if (spec.pattern == SurveyPattern.CORRIDOR) return AdvancedSurveys.corridor(spec)
        val ring = Geo.closeRing(spec.polygon)
        require(Geo.bbox(ring).let { it.maxLng - it.minLng < 1 && it.maxLat - it.minLat < 1 }) { "split large or antimeridian-crossing areas into smaller plans" }
        val gsd = spec.camera.gsdM(spec.heightM)
        val lineSpacing = gsd * spec.camera.imageWidthPx * (1 - spec.sideOverlap)
        val photoSpacing = gsd * spec.camera.imageHeightPx * (1 - spec.frontOverlap)

        val waypoints: List<MissionWaypoint>
        val lines: Int
        when (spec.pattern) {
            SurveyPattern.GRID -> {
                waypoints = grid(ring, spec.headingDeg, spec.heightM, lineSpacing)
                lines = waypoints.count { !it.transit } / 2
            }
            SurveyPattern.CROSSHATCH -> {
                val first = grid(ring, spec.headingDeg, spec.heightM, lineSpacing)
                val second = crossing(first.lastOrNull()?.position, ring, spec.headingDeg, spec.heightM, lineSpacing)
                val bridge = if (first.isEmpty() || second.isEmpty()) emptyList() else PolygonRouting(ring).connect(first.last().position, second.first().position).map { MissionWaypoint(it.lat,it.lng,spec.heightM,transit=true) }
                waypoints = first + bridge + second
                lines = (first + second).count { !it.transit } / 2
            }
            SurveyPattern.PERIMETER -> {
                waypoints = perimeter(ring, spec.heightM)
                lines = 1
            }
            SurveyPattern.FIVE_DIRECTION -> {
                val base = grid(ring, spec.headingDeg, spec.heightM, lineSpacing)
                require(base.size >= 2) { "area is too small for the selected camera and overlap" }
                val passes = listOf<Double?>(null, 0.0, 90.0, 180.0, 270.0).flatMapIndexed { pass, facing ->
                    val route = base
                    route.mapIndexed { i, w -> w.copy(headingDeg = facing, gimbalPitchDeg = if (pass == 0) -90.0 else spec.gimbalPitchDeg) }
                }
                val joined = ArrayList<MissionWaypoint>()
                passes.chunked(base.size).forEach { pass ->
                    joined.lastOrNull()?.let { previous ->
                        PolygonRouting(ring).connect(previous.position, pass.first().position).forEach { p ->
                            joined += MissionWaypoint(p.lat,p.lng,spec.heightM,transit=true)
                        }
                    }
                    joined += pass
                }
                waypoints = joined
                lines = base.count { !it.transit } / 2 * 5
            }
            SurveyPattern.SURROUND -> return AdvancedSurveys.surround(spec)
            SurveyPattern.CORRIDOR -> error("handled before closing the centreline")
        }
        val plan = MissionPlan(
            waypoints = waypoints,
            autoSpeedMps = spec.speedMps,
            photoDistanceIntervalM = photoSpacing,
            gimbalPitchDeg = spec.gimbalPitchDeg,
            travelHeightM = spec.travelHeightM?.takeIf { it > spec.heightM },
            cameraName = spec.camera.name,
        )
        return SurveyResult(plan, if (spec.pattern == SurveyPattern.PERIMETER) 0.0 else lineSpacing, photoSpacing, gsd * 100, lines)
    }

    /** Lawnmower lines along [headingDeg], [spacing] apart; two waypoints per line, alternate lines reversed. */
    private fun grid(ring: List<LatLng>, headingDeg: Double, heightM: Double, spacing: Double): List<MissionWaypoint> {
        val plane = LocalPlane(LatLng(ring.dropLast(1).map { it.lat }.average(), ring.dropLast(1).map { it.lng }.average()))
        val h = Math.toRadians(headingDeg)
        val phi = atan2(cos(h), sin(h)) // angle of the line direction (east = x, north = y)
        fun rot(p: XY) = XY(p.x * cos(phi) + p.y * sin(phi), -p.x * sin(phi) + p.y * cos(phi))
        fun unrot(p: XY) = XY(p.x * cos(phi) - p.y * sin(phi), p.x * sin(phi) + p.y * cos(phi))
        val pts = ring.map { rot(plane.toXY(it)) }
        val router = PolygonRouting(ring)

        val minY = pts.minOf { it.y }
        val maxY = pts.maxOf { it.y }
        val lines = max(1, ceil((maxY - minY) / spacing).toInt())
        require(lines <= 2_000) { "survey is too large; split it into smaller areas" }
        val waypoints = ArrayList<MissionWaypoint>()
        for (k in 0 until lines) {
            val y = minY + spacing / 2 + k * spacing
            val xs = pts.zipWithNext().mapNotNull { (a, b) ->
                if ((a.y <= y) == (b.y <= y)) null else a.x + (y - a.y) * (b.x - a.x) / (b.y - a.y)
            }
            if (xs.size < 2) continue
            val sorted = xs.sorted()
            val pairs = sorted.chunked(2).filter { it.size == 2 && it[1] - it[0] >= 0.5 }
            val ordered = if (k % 2 == 0) pairs else pairs.reversed()
            for (pair in ordered) {
                val endpoints = if (k % 2 == 0) pair else pair.reversed()
                val segment = endpoints.map { x -> plane.toLatLng(unrot(XY(x, y))) }
                waypoints.lastOrNull()?.let { previous ->
                    router.connect(previous.position, segment.first()).forEach { p ->
                        waypoints += MissionWaypoint(p.lat, p.lng, heightM, transit = true)
                    }
                }
                for (p in segment) waypoints += MissionWaypoint(p.lat, p.lng, heightM)
            }
        }
        return waypoints
    }

    /**
     * The second pass of a crosshatch: the grid at right angles, flown from whichever of its four
     * possible starts is nearest to where the first pass ended, so the join is short.
     */
    private fun crossing(from: LatLng?, ring: List<LatLng>, headingDeg: Double, heightM: Double, spacing: Double): List<MissionWaypoint> {
        val a = grid(ring, headingDeg + 90, heightM, spacing)
        if (from == null || a.size < 2) return a
        if (a.any { it.transit }) return listOf(a, a.reversed()).minBy { Geo.distanceM(from, it.first().position) }
        // Every line pointing the same way, then the four lawnmowers over them: either end first, either way along the first line.
        val lines = a.chunked(2).mapIndexed { i, l -> if (i % 2 == 0) l else l.reversed() }
        val options = listOf(lines, lines.reversed()).flatMap { order ->
            listOf(false, true).map { flip -> order.flatMapIndexed { i, l -> if ((i % 2 == 0) != flip) l else l.reversed() } }
        }
        val best = options.minBy { Geo.distanceM(from, it.first().position) }
        // Both passes can end and start on the same corner: don't stack two waypoints there.
        return if (Geo.distanceM(from, best.first().position) < 1.0) best.drop(1) else best
    }

    /**
     * Along the area's edge and back to the first corner, each waypoint facing the area's middle
     * (its centroid). Edges are cut so the heading never swings more than [PERIMETER_MAX_TURN_DEG]
     * between waypoints, and no two are more than [PERIMETER_MAX_STEP_M] apart.
     */
    private fun perimeter(ring: List<LatLng>, heightM: Double): List<MissionWaypoint> {
        val open = ring.dropLast(1)
        val plane = LocalPlane(LatLng(open.map { it.lat }.average(), open.map { it.lng }.average()))
        val pts = ring.map(plane::toXY)
        val c = centroid(pts)
        fun facing(p: XY) = Math.toDegrees(atan2(c.x - p.x, c.y - p.y))
        fun wp(p: XY) = plane.toLatLng(p).let { MissionWaypoint(it.lat, it.lng, heightM, headingDeg = facing(p)) }
        val out = ArrayList<MissionWaypoint>()
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val len = kotlin.math.hypot(b.x - a.x, b.y - a.y)
            if (len < 1.0) continue
            fun at(j: Int, n: Int) = XY(a.x + (b.x - a.x) * j / n, a.y + (b.y - a.y) * j / n)
            fun turn(p: XY, q: XY) = kotlin.math.abs(((facing(q) - facing(p)) % 360 + 540) % 360 - 180)
            // The heading swings fastest where the edge passes closest to the middle: add pieces until no step turns too far.
            var pieces = max(1, ceil(len / PERIMETER_MAX_STEP_M).toInt())
            while (pieces < 360 && (0 until pieces).any { j -> turn(at(j, pieces), at(j + 1, pieces)) > PERIMETER_MAX_TURN_DEG }) pieces++
            for (j in 0 until pieces) out += wp(at(j, pieces))
        }
        out += wp(pts.first())
        return out
    }

    /** Area-weighted centroid of a closed ring; the vertex average if it has no area. */
    private fun centroid(pts: List<XY>): XY {
        var a = 0.0
        var cx = 0.0
        var cy = 0.0
        for (i in 0 until pts.size - 1) {
            val cross = pts[i].x * pts[i + 1].y - pts[i + 1].x * pts[i].y
            a += cross
            cx += (pts[i].x + pts[i + 1].x) * cross
            cy += (pts[i].y + pts[i + 1].y) * cross
        }
        if (kotlin.math.abs(a) < 1e-6) return XY(pts.dropLast(1).map { it.x }.average(), pts.dropLast(1).map { it.y }.average())
        return XY(cx / (3 * a), cy / (3 * a))
    }
}

/**
 * Travel legs, added at upload time so a resumed mission gets them too:
 * - **in:** climb straight up over the aircraft to the travel height, fly level to just short of
 *   the first waypoint, descend onto it;
 * - **out:** from the last waypoint, climb to the travel height before the finish action (RTH, whose
 *   own height is then at least the travel height: [WpmlWriter] sets it; on v4 it is the aircraft's setting).
 * The extra points are [MissionWaypoint.transit]: no survey photos on their legs. They are offset
 * [OFFSET_M] horizontally so no two consecutive waypoints share a position (SDK minimum spacing).
 */
object TravelLegs {
    const val OFFSET_M = 2.0

    data class Expanded(val plan: MissionPlan, /** Points added before the first mission waypoint. */ val lead: Int)

    fun expand(plan: MissionPlan, aircraft: LatLng?, intro: Boolean, outro: Boolean): Expanded {
        val t = plan.travelHeightM ?: return Expanded(plan, 0)
        val wps = plan.waypoints
        if (wps.size < 2) return Expanded(plan, 0)
        val before = ArrayList<MissionWaypoint>()
        if (intro) {
            val first = wps[0]
            val approach = offset(first.position, wps[1].position, -OFFSET_M)
            if (aircraft != null && aircraft.isValid && Geo.distanceM(aircraft, approach) >= OFFSET_M) {
                before += MissionWaypoint(aircraft.lat, aircraft.lng, t, headingDeg = first.headingDeg, transit = true)
            }
            before += MissionWaypoint(approach.lat, approach.lng, t, headingDeg = first.headingDeg, transit = true)
        }
        val after = ArrayList<MissionWaypoint>()
        if (outro) {
            val departure = offset(wps.last().position, wps[wps.size - 2].position, -OFFSET_M)
            after += MissionWaypoint(departure.lat, departure.lng, t, headingDeg = wps.last().headingDeg, transit = true)
        }
        return Expanded(plan.copy(waypoints = before + wps + after), before.size)
    }

    /** [distanceM] metres from [p] along the direction towards [toward] (negative: away from it). */
    private fun offset(p: LatLng, toward: LatLng, distanceM: Double): LatLng {
        val plane = LocalPlane(p)
        val q = plane.toXY(toward)
        val len = kotlin.math.hypot(q.x, q.y).takeIf { it > 0 } ?: return p
        return plane.toLatLng(XY(q.x / len * distanceM, q.y / len * distanceM))
    }
}

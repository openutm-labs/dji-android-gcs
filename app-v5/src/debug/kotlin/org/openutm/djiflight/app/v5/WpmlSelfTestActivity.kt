package org.openutm.djiflight.app.v5

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.dji.wpmzsdk.manager.WPMZManager
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.geo.LocalPlane
import org.openutm.djiflight.core.geo.XY
import org.openutm.djiflight.core.mission.CameraModel
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.core.mission.MissionPlan
import org.openutm.djiflight.core.mission.MissionValidator
import org.openutm.djiflight.core.mission.MissionWaypoint
import org.openutm.djiflight.core.mission.SurveyPlanner
import org.openutm.djiflight.core.mission.SurveyPattern
import org.openutm.djiflight.core.mission.SurveySpec
import org.openutm.djiflight.core.mission.WpmlTarget
import org.openutm.djiflight.core.mission.WpmlWriter
import org.openutm.djiflight.core.terrain.TerrainFollow
import org.openutm.djiflight.core.terrain.TerrainModel
import java.io.File

/**
 * Debug builds only: writes KMZs with our [WpmlWriter] and runs DJI's own validator and parser
 * (wpmzsdk, the library MSDK v5 uses) on them, without an aircraft. Results go to the screen and to
 * `adb logcat -s OpenUTMFlight`.
 */
class WpmlSelfTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = TextView(this).apply { setPadding(24, 24, 24, 24); textSize = 13f }
        setContentView(ScrollView(this).apply { addView(out) })
        Thread {
            val report = run()
            report.lines().forEach { Log.i("OpenUTMFlight", "wpml-selftest $it") }
            runOnUiThread { out.text = report }
        }.start()
    }

    private fun run(): String = buildString {
        WPMZManager.getInstance().init(applicationContext)
        val plane = LocalPlane(LatLng(-1.2797, 36.8344))
        val square = listOf(XY(0.0, 0.0), XY(200.0, 0.0), XY(200.0, 200.0), XY(0.0, 200.0)).map(plane::toLatLng)
        val survey = SurveyPlanner.plan(SurveySpec(square, CameraModel.MAVIC_3E, 80.0, 0.75, 0.65, 0.0, 8.0)).plan
        fun pattern(p: SurveyPattern, camera: CameraModel) =
            SurveyPlanner.plan(SurveySpec(square, camera, 80.0, 0.75, 0.65, 0.0, 8.0, gimbalPitchDeg = p.defaultGimbalDeg, pattern = p)).plan
        // Terrain following over a synthetic ridge (60 m high, 300 m east of take-off) on a slope: heights up to ~150 m above take-off.
        val ridge = object : TerrainModel {
            override fun elevationM(p: LatLng) = plane.toXY(p).let { 1650 + it.x / 20 + 60 * maxOf(0.0, 1 - kotlin.math.abs(it.x - 300) / 150) }
            override val sigmaM = 8.0
        }
        val hill = listOf(XY(0.0, 0.0), XY(600.0, 0.0), XY(600.0, 300.0), XY(0.0, 300.0)).map(plane::toLatLng)
        // Lines across the ridge (east-west), then along it (north-south) for upload parts at different heights.
        fun hillSurvey(headingDeg: Double) = SurveyPlanner.plan(SurveySpec(hill, CameraModel.MAVIC_3E, 80.0, 0.75, 0.65, headingDeg, 8.0)).plan
        val followed = TerrainFollow.follow(hillSurvey(90.0), plane.origin, 80.0, ridge, MissionLimits.WPML)!!
        // The first of several uploads, the lines east of the ridge: its RTH height is the whole route's top, above its own waypoints.
        val part = MissionValidator.split(TerrainFollow.follow(hillSurvey(0.0), plane.origin, 80.0, ridge, MissionLimits.WPML)!!, MissionLimits.WAYPOINT_V1.copy(maxWaypoints = 12))[0]
        val cases = listOf(
            Triple("m3e-survey", survey, WpmlTarget.MAVIC_3E),
            Triple("m400-p1-survey", survey, WpmlTarget(WpmlTarget.MATRICE_400, 0, WpmlTarget.PAYLOAD_P1, imageFormat = "visible")),
            Triple("m3t-survey", pattern(SurveyPattern.GRID, CameraModel.MAVIC_3T), WpmlTarget.MAVIC_3T),
            Triple("m3m-survey", pattern(SurveyPattern.GRID, CameraModel.MAVIC_3M), WpmlTarget.MAVIC_3M),
            Triple("m400-h30-survey", pattern(SurveyPattern.GRID, CameraModel.ZENMUSE_H30_WIDE), WpmlTarget(WpmlTarget.MATRICE_400, 0, WpmlTarget.PAYLOAD_H30, imageFormat = "wide")),
            Triple("m400-h30t-survey", pattern(SurveyPattern.GRID, CameraModel.ZENMUSE_H30_WIDE), WpmlTarget(WpmlTarget.MATRICE_400, 0, WpmlTarget.PAYLOAD_H30T, imageFormat = "wide")),
            Triple("m3e-crosshatch", pattern(SurveyPattern.CROSSHATCH, CameraModel.MAVIC_3E), WpmlTarget.MAVIC_3E),
            Triple("m3e-perimeter", pattern(SurveyPattern.PERIMETER, CameraModel.MAVIC_3E), WpmlTarget.MAVIC_3E),
            Triple("m3e-five-direction", pattern(SurveyPattern.FIVE_DIRECTION, CameraModel.MAVIC_3E), WpmlTarget.MAVIC_3E),
            Triple("m3e-corridor", pattern(SurveyPattern.CORRIDOR, CameraModel.MAVIC_3E), WpmlTarget.MAVIC_3E),
            Triple("m3e-surround", pattern(SurveyPattern.SURROUND, CameraModel.MAVIC_3E), WpmlTarget.MAVIC_3E),
            Triple("m3e-terrain-follow", followed, WpmlTarget.MAVIC_3E),
            Triple("m3e-terrain-part", part, WpmlTarget.MAVIC_3E),
            Triple(
                "m3e-waypoint-photos",
                MissionPlan(
                    listOf(
                        MissionWaypoint(-1.2797, 36.8344, 30.0),
                        MissionWaypoint(-1.2790, 36.8350, 40.0, speedMps = 5.0, headingDeg = 90.0, takePhoto = true),
                        MissionWaypoint(-1.2785, 36.8340, 40.0, takePhoto = true),
                    ),
                    autoSpeedMps = 7.0, exitOnRcSignalLost = false,
                ),
                WpmlTarget.MAVIC_3E,
            ),
        )
        val dir = File(cacheDir, "wpml-selftest").apply { mkdirs() }
        for ((name, plan, target) in cases) {
            val kmz = File(dir, "$name.kmz")
            kmz.outputStream().use { WpmlWriter.writeKmz(plan, target, it, System.currentTimeMillis()) }
            val check = runCatching { WPMZManager.getInstance().checkValidation(kmz.absolutePath)?.value }
            val info = runCatching { WPMZManager.getInstance().getKMZInfo(kmz.absolutePath) }
            appendLine("$name: ${plan.waypoints.size} waypoints, ${kmz.length()} bytes, heights %.1f–%.1f m, RTH %.1f m".format(plan.waypoints.minOf { it.heightM }, plan.waypoints.maxOf { it.heightM }, plan.maxHeightM))
            appendLine("  checkValidation: ${check.getOrElse { "threw $it" }}")
            info.onSuccess { i ->
                appendLine("  parsed missionConfig: ${i?.waylineMissionConfigParseInfo}")
                appendLine("  parsed mission: ${i?.waylineMissionParseInfo}")
            }.onFailure { appendLine("  getKMZInfo threw $it") }
        }
    }
}

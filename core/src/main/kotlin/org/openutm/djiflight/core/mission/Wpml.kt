package org.openutm.djiflight.core.mission

import org.openutm.djiflight.core.geo.Geo
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * The aircraft and payload a WPML file is written for (`wpml:droneInfo`, `wpml:payloadInfo`).
 * Values from DJI's WPML spec (Cloud API docs, "Common Element") and, for aircraft newer than the
 * docs (Matrice 400), the enums in DJI's own wpmzsdk 1.0.5.2 (`WaylineDroneType`, `WaylinePayloadType`).
 */
data class WpmlTarget(
    val droneEnum: Int,
    val droneSubEnum: Int,
    val payloadEnum: Int,
    val payloadSubEnum: Int = 0,
    /** 0 = main gimbal (on the M300/M350: front left). */
    val payloadPosition: Int = 0,
    /** `wpml:imageFormat`: which lens(es) the photos are stored from. */
    val imageFormat: String,
) {
    companion object {
        /** Mavic 3 Enterprise: drone 77/0, "Mavic 3E Camera" 66, wide lens for mapping. */
        val MAVIC_3E = WpmlTarget(droneEnum = 77, droneSubEnum = 0, payloadEnum = 66, imageFormat = "wide")

        /** Mavic 3T: drone 77/1, camera 67 (wpmzsdk `WM265T`); the wide lens maps, the thermal one is not stored. */
        val MAVIC_3T = WpmlTarget(droneEnum = 77, droneSubEnum = 1, payloadEnum = 67, imageFormat = "wide")

        /** Mavic 3M: drone 77/2, camera 68 (`WM265M`); stores the RGB and the multispectral photos at each shot. */
        val MAVIC_3M = WpmlTarget(droneEnum = 77, droneSubEnum = 2, payloadEnum = 68, imageFormat = "visible,narrow_band")

        /** Matrice 400 (wpmzsdk `PM440` = 103). */
        const val MATRICE_400 = 103
        /** Payload enums for the Matrice 400's mapping payloads (P1 50, L2 84 = wpmzsdk `EP810`). */
        const val PAYLOAD_P1 = 50
        const val PAYLOAD_L2 = 84
        /** Zenmuse H30 and H30T (wpmzsdk `EP300_E` 82, `EP300_T` 83): mapped with the wide lens. */
        const val PAYLOAD_H30 = 82
        const val PAYLOAD_H30T = 83
    }
}

/**
 * Core [MissionPlan] → a DJI WPML KMZ (`wpmz/template.kml` + `wpmz/waylines.wpml`), the mission
 * format MSDK v5 uploads with `WaypointMissionManager.pushKMZFileToAircraft`. Written by hand
 * from DJI's published spec so it can be unit-tested on any JVM; the v5 adapter also runs DJI's
 * own `WPMZManager.checkValidation` on each file before upload.
 *
 * Mapping (matching what the v4 Waypoint 1.0 mapper does):
 * - heights relative to the take-off point; straight segments, stop at each waypoint;
 * - the gimbal is set to [MissionPlan.gimbalPitchDeg] on reaching the first waypoint;
 * - [MissionPlan.photoDistanceIntervalM] → one `takePhoto` group with a `multipleDistance`
 *   trigger over the whole route; [MissionWaypoint.takePhoto] → `takePhoto` on reaching it;
 * - signal loss → leave the mission and fly home (`executeLostAction` + `goBack`), as v4's
 *   "exit mission on RC signal lost" hands over to the aircraft's failsafe.
 */
object WpmlWriter {
    const val NAMESPACE = "http://www.dji.com/wpmz/1.0.2"
    const val TEMPLATE_ENTRY = "wpmz/template.kml"
    const val WAYLINES_ENTRY = "wpmz/waylines.wpml"
    private const val TURN_MODE = "toPointAndStopWithDiscontinuityCurvature"

    fun writeKmz(plan: MissionPlan, target: WpmlTarget, out: OutputStream, nowMs: Long) {
        ZipOutputStream(out).use { zip ->
            for ((name, body) in listOf(TEMPLATE_ENTRY to template(plan, target, nowMs), WAYLINES_ENTRY to waylines(plan, target))) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    fun template(plan: MissionPlan, target: WpmlTarget, nowMs: Long): String = document {
        line(2, "<wpml:author>OpenUTM Flight</wpml:author>")
        line(2, "<wpml:createTime>$nowMs</wpml:createTime>")
        line(2, "<wpml:updateTime>$nowMs</wpml:updateTime>")
        missionConfig(plan, target)
        line(2, "<Folder>")
        line(3, "<wpml:templateType>waypoint</wpml:templateType>")
        line(3, "<wpml:templateId>0</wpml:templateId>")
        line(3, "<wpml:waylineCoordinateSysParam>")
        line(4, "<wpml:coordinateMode>WGS84</wpml:coordinateMode>")
        line(4, "<wpml:heightMode>relativeToStartPoint</wpml:heightMode>")
        line(3, "</wpml:waylineCoordinateSysParam>")
        line(3, "<wpml:autoFlightSpeed>${num(plan.autoSpeedMps)}</wpml:autoFlightSpeed>")
        line(3, "<wpml:globalHeight>${num(plan.waypoints.first().heightM)}</wpml:globalHeight>")
        line(3, "<wpml:caliFlightEnable>0</wpml:caliFlightEnable>")
        line(3, "<wpml:gimbalPitchMode>manual</wpml:gimbalPitchMode>")
        line(3, "<wpml:globalWaypointHeadingParam>")
        heading(4, null)
        line(3, "</wpml:globalWaypointHeadingParam>")
        line(3, "<wpml:globalWaypointTurnMode>$TURN_MODE</wpml:globalWaypointTurnMode>")
        line(3, "<wpml:globalUseStraightLine>1</wpml:globalUseStraightLine>")
        line(3, "<wpml:payloadParam>")
        line(4, "<wpml:payloadPositionIndex>${target.payloadPosition}</wpml:payloadPositionIndex>")
        line(4, "<wpml:imageFormat>${xml(target.imageFormat)}</wpml:imageFormat>")
        line(3, "</wpml:payloadParam>")
        plan.waypoints.forEachIndexed { i, w ->
            line(3, "<Placemark>")
            point(4, w)
            line(4, "<wpml:index>$i</wpml:index>")
            line(4, "<wpml:useGlobalHeight>0</wpml:useGlobalHeight>")
            // Informational with relativeToStartPoint; DJI's own sample writes the same value in both.
            line(4, "<wpml:ellipsoidHeight>${num(w.heightM)}</wpml:ellipsoidHeight>")
            line(4, "<wpml:height>${num(w.heightM)}</wpml:height>")
            line(4, "<wpml:useGlobalSpeed>${if (w.speedMps == null) 1 else 0}</wpml:useGlobalSpeed>")
            w.speedMps?.let { line(4, "<wpml:waypointSpeed>${num(it)}</wpml:waypointSpeed>") }
            line(4, "<wpml:useGlobalHeadingParam>${if (w.headingDeg == null) 1 else 0}</wpml:useGlobalHeadingParam>")
            w.headingDeg?.let {
                line(4, "<wpml:waypointHeadingParam>")
                heading(5, it)
                line(4, "</wpml:waypointHeadingParam>")
            }
            line(4, "<wpml:useGlobalTurnParam>1</wpml:useGlobalTurnParam>")
            actionGroups(4, plan, target, i)
            line(3, "</Placemark>")
        }
        line(2, "</Folder>")
    }

    fun waylines(plan: MissionPlan, target: WpmlTarget): String = document {
        missionConfig(plan, target)
        line(2, "<Folder>")
        line(3, "<wpml:templateId>0</wpml:templateId>")
        line(3, "<wpml:executeHeightMode>relativeToStartPoint</wpml:executeHeightMode>")
        line(3, "<wpml:waylineId>0</wpml:waylineId>")
        line(3, "<wpml:distance>${num(plan.routeLengthM)}</wpml:distance>")
        line(3, "<wpml:duration>${num(durationS(plan))}</wpml:duration>")
        line(3, "<wpml:autoFlightSpeed>${num(plan.autoSpeedMps)}</wpml:autoFlightSpeed>")
        plan.waypoints.forEachIndexed { i, w ->
            line(3, "<Placemark>")
            point(4, w)
            line(4, "<wpml:index>$i</wpml:index>")
            line(4, "<wpml:executeHeight>${num(w.heightM)}</wpml:executeHeight>")
            line(4, "<wpml:waypointSpeed>${num(w.speedMps ?: plan.autoSpeedMps)}</wpml:waypointSpeed>")
            line(4, "<wpml:waypointHeadingParam>")
            heading(5, w.headingDeg)
            line(4, "</wpml:waypointHeadingParam>")
            line(4, "<wpml:waypointTurnParam>")
            line(5, "<wpml:waypointTurnMode>$TURN_MODE</wpml:waypointTurnMode>")
            line(5, "<wpml:waypointTurnDampingDist>0</wpml:waypointTurnDampingDist>")
            line(4, "</wpml:waypointTurnParam>")
            line(4, "<wpml:useStraightLine>1</wpml:useStraightLine>")
            actionGroups(4, plan, target, i)
            line(3, "</Placemark>")
        }
        line(2, "</Folder>")
    }

    /**
     * Safe take-off height (climb straight up to it first): the travel height if the plan has one,
     * else 20 m but never above the mission's highest point. DJI's range is 1.2..1500 m.
     */
    fun takeOffSecurityHeightM(plan: MissionPlan): Double = (plan.travelHeightM ?: min(20.0, plan.maxHeightM)).coerceIn(1.2, 1500.0)

    /** Segment lengths at each segment's speed; no allowance for stops. */
    fun durationS(plan: MissionPlan): Double = plan.waypoints.zipWithNext { a, b ->
        Geo.distanceM(a.position, b.position) / (a.speedMps ?: plan.autoSpeedMps)
    }.sum()

    private fun Xml.missionConfig(plan: MissionPlan, target: WpmlTarget) {
        val securityHeight = takeOffSecurityHeightM(plan)
        line(2, "<wpml:missionConfig>")
        line(3, "<wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode>")
        line(3, "<wpml:finishAction>${finishAction(plan.finishAction)}</wpml:finishAction>")
        line(3, "<wpml:exitOnRCLost>${if (plan.exitOnRcSignalLost) "executeLostAction" else "goContinue"}</wpml:exitOnRCLost>")
        // The spec requires this only with executeLostAction, but DJI's validator (wpmzsdk 1.0.5.0)
        // rejects goContinue without it (InvalidRCLoastBehavior, seen on the emulator): always written.
        line(3, "<wpml:executeRCLostAction>goBack</wpml:executeRCLostAction>")
        line(3, "<wpml:takeOffSecurityHeight>${num(securityHeight)}</wpml:takeOffSecurityHeight>")
        line(3, "<wpml:globalTransitionalSpeed>${num(min(plan.autoSpeedMps, 15.0))}</wpml:globalTransitionalSpeed>")
        // Return-to-home climbs to this height: keep it inside the declared volume (the mission's top).
        line(3, "<wpml:globalRTHHeight>${num(max(plan.maxHeightM, securityHeight))}</wpml:globalRTHHeight>")
        line(3, "<wpml:droneInfo>")
        line(4, "<wpml:droneEnumValue>${target.droneEnum}</wpml:droneEnumValue>")
        line(4, "<wpml:droneSubEnumValue>${target.droneSubEnum}</wpml:droneSubEnumValue>")
        line(3, "</wpml:droneInfo>")
        line(3, "<wpml:payloadInfo>")
        line(4, "<wpml:payloadEnumValue>${target.payloadEnum}</wpml:payloadEnumValue>")
        line(4, "<wpml:payloadSubEnumValue>${target.payloadSubEnum}</wpml:payloadSubEnumValue>")
        line(4, "<wpml:payloadPositionIndex>${target.payloadPosition}</wpml:payloadPositionIndex>")
        line(3, "</wpml:payloadInfo>")
        line(2, "</wpml:missionConfig>")
    }

    private fun Xml.heading(indent: Int, headingDeg: Double?) {
        if (headingDeg == null) {
            line(indent, "<wpml:waypointHeadingMode>followWayline</wpml:waypointHeadingMode>")
            line(indent, "<wpml:waypointHeadingAngle>0</wpml:waypointHeadingAngle>")
            line(indent, "<wpml:waypointHeadingAngleEnable>0</wpml:waypointHeadingAngleEnable>")
        } else {
            line(indent, "<wpml:waypointHeadingMode>smoothTransition</wpml:waypointHeadingMode>")
            line(indent, "<wpml:waypointHeadingAngle>${num(normalizeDeg(headingDeg))}</wpml:waypointHeadingAngle>")
            line(indent, "<wpml:waypointHeadingAngleEnable>1</wpml:waypointHeadingAngleEnable>")
        }
        line(indent, "<wpml:waypointPoiPoint>0.000000,0.000000,0.000000</wpml:waypointPoiPoint>")
        line(indent, "<wpml:waypointHeadingPathMode>followBadArc</wpml:waypointHeadingPathMode>")
    }

    /**
     * The action groups that start at waypoint [index], numbered across the file:
     * 0 = gimbal pitch at the first waypoint, 1 = distance-interval photos (if any) from the first to
     * the last survey waypoint (travel legs excluded), then one per waypoint photo in waypoint order.
     */
    private fun Xml.actionGroups(indent: Int, plan: MissionPlan, target: WpmlTarget, index: Int) {
        val surveyStart = plan.waypoints.indexOfFirst { !it.transit }.coerceAtLeast(0)
        val surveyEnd = plan.waypoints.indexOfLast { !it.transit }.takeIf { it >= 0 } ?: plan.waypoints.lastIndex
        val interval = plan.photoDistanceIntervalM?.takeIf { it > 0 && surveyEnd > surveyStart }
        if (index == 0) {
            group(indent, id = 0, start = 0, end = 0, trigger = "reachPoint", triggerParam = null) {
                gimbalPitch(indent + 2, 0, target, plan.gimbalPitchDeg)
            }
        }
        if (index == surveyStart && interval != null) {
            group(indent, id = 1, start = surveyStart, end = surveyEnd, trigger = "multipleDistance", triggerParam = interval) {
                takePhoto(indent + 2, 0, target)
            }
        }
        if (plan.waypoints[index].takePhoto) {
            val firstId = if (interval != null) 2 else 1
            val id = firstId + plan.waypoints.subList(0, index).count { it.takePhoto }
            group(indent, id = id, start = index, end = index, trigger = "reachPoint", triggerParam = null) {
                takePhoto(indent + 2, 0, target)
            }
        }
    }

    private fun Xml.group(indent: Int, id: Int, start: Int, end: Int, trigger: String, triggerParam: Double?, actions: Xml.() -> Unit) {
        line(indent, "<wpml:actionGroup>")
        line(indent + 1, "<wpml:actionGroupId>$id</wpml:actionGroupId>")
        line(indent + 1, "<wpml:actionGroupStartIndex>$start</wpml:actionGroupStartIndex>")
        line(indent + 1, "<wpml:actionGroupEndIndex>$end</wpml:actionGroupEndIndex>")
        line(indent + 1, "<wpml:actionGroupMode>sequence</wpml:actionGroupMode>")
        line(indent + 1, "<wpml:actionTrigger>")
        line(indent + 2, "<wpml:actionTriggerType>$trigger</wpml:actionTriggerType>")
        triggerParam?.let { line(indent + 2, "<wpml:actionTriggerParam>${num(it)}</wpml:actionTriggerParam>") }
        line(indent + 1, "</wpml:actionTrigger>")
        actions()
        line(indent, "</wpml:actionGroup>")
    }

    private fun Xml.gimbalPitch(indent: Int, id: Int, target: WpmlTarget, pitchDeg: Double) {
        line(indent - 1, "<wpml:action>")
        line(indent, "<wpml:actionId>$id</wpml:actionId>")
        line(indent, "<wpml:actionActuatorFunc>gimbalRotate</wpml:actionActuatorFunc>")
        line(indent, "<wpml:actionActuatorFuncParam>")
        line(indent + 1, "<wpml:gimbalHeadingYawBase>north</wpml:gimbalHeadingYawBase>")
        line(indent + 1, "<wpml:gimbalRotateMode>absoluteAngle</wpml:gimbalRotateMode>")
        line(indent + 1, "<wpml:gimbalPitchRotateEnable>1</wpml:gimbalPitchRotateEnable>")
        line(indent + 1, "<wpml:gimbalPitchRotateAngle>${num(pitchDeg)}</wpml:gimbalPitchRotateAngle>")
        line(indent + 1, "<wpml:gimbalRollRotateEnable>0</wpml:gimbalRollRotateEnable>")
        line(indent + 1, "<wpml:gimbalRollRotateAngle>0</wpml:gimbalRollRotateAngle>")
        line(indent + 1, "<wpml:gimbalYawRotateEnable>0</wpml:gimbalYawRotateEnable>")
        line(indent + 1, "<wpml:gimbalYawRotateAngle>0</wpml:gimbalYawRotateAngle>")
        line(indent + 1, "<wpml:gimbalRotateTimeEnable>0</wpml:gimbalRotateTimeEnable>")
        line(indent + 1, "<wpml:gimbalRotateTime>0</wpml:gimbalRotateTime>")
        line(indent + 1, "<wpml:payloadPositionIndex>${target.payloadPosition}</wpml:payloadPositionIndex>")
        line(indent, "</wpml:actionActuatorFuncParam>")
        line(indent - 1, "</wpml:action>")
    }

    private fun Xml.takePhoto(indent: Int, id: Int, target: WpmlTarget) {
        line(indent - 1, "<wpml:action>")
        line(indent, "<wpml:actionId>$id</wpml:actionId>")
        line(indent, "<wpml:actionActuatorFunc>takePhoto</wpml:actionActuatorFunc>")
        line(indent, "<wpml:actionActuatorFuncParam>")
        line(indent + 1, "<wpml:payloadPositionIndex>${target.payloadPosition}</wpml:payloadPositionIndex>")
        line(indent + 1, "<wpml:fileSuffix>openutm</wpml:fileSuffix>")
        line(indent + 1, "<wpml:useGlobalPayloadLensIndex>1</wpml:useGlobalPayloadLensIndex>")
        line(indent, "</wpml:actionActuatorFuncParam>")
        line(indent - 1, "</wpml:action>")
    }

    private fun Xml.point(indent: Int, w: MissionWaypoint) {
        line(indent, "<Point>")
        line(indent + 1, "<coordinates>${coord(w.lng)},${coord(w.lat)}</coordinates>")
        line(indent, "</Point>")
    }

    private fun finishAction(a: FinishAction) = when (a) {
        FinishAction.GO_HOME -> "goHome"
        FinishAction.HOVER -> "noAction"
        FinishAction.AUTO_LAND -> "autoLand"
        FinishAction.GO_FIRST_WAYPOINT -> "gotoFirstWaypoint"
    }

    private fun normalizeDeg(d: Double) = ((d + 180) % 360 + 360) % 360 - 180

    /** Up to 3 decimals, no trailing zeros, '.' decimal separator whatever the device locale. */
    private fun num(v: Double): String = String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
    private fun coord(v: Double): String = String.format(Locale.ROOT, "%.10f", v)
    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private class Xml {
        val sb = StringBuilder()
        fun line(indent: Int, text: String) {
            repeat(indent) { sb.append("  ") }
            sb.append(text).append('\n')
        }
    }

    private fun document(body: Xml.() -> Unit): String = Xml().apply {
        line(0, """<?xml version="1.0" encoding="UTF-8"?>""")
        line(0, """<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:wpml="$NAMESPACE">""")
        line(1, "<Document>")
        body()
        line(1, "</Document>")
        line(0, "</kml>")
    }.sb.toString()
}

package org.openutm.djiflight.core

import org.openutm.djiflight.core.geo.LocalPlane
import org.openutm.djiflight.core.geo.XY
import org.openutm.djiflight.core.mission.CameraModel
import org.openutm.djiflight.core.mission.FinishAction
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.core.mission.MissionPlan
import org.openutm.djiflight.core.mission.MissionValidator
import org.openutm.djiflight.core.mission.MissionWaypoint
import org.openutm.djiflight.core.mission.SurveyPlanner
import org.openutm.djiflight.core.mission.SurveySpec
import org.openutm.djiflight.core.mission.WpmlTarget
import org.openutm.djiflight.core.mission.WpmlWriter
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WpmlTest {
    private val ns = WpmlWriter.NAMESPACE
    private val plane = LocalPlane(TestData.NAIROBI)
    private fun square(sideM: Double) = listOf(XY(0.0, 0.0), XY(sideM, 0.0), XY(sideM, sideM), XY(0.0, sideM)).map(plane::toLatLng)

    private fun survey() = SurveyPlanner.plan(SurveySpec(square(200.0), CameraModel.MAVIC_3E, heightM = 80.0, frontOverlap = 0.75, sideOverlap = 0.65, headingDeg = 0.0, speedMps = 8.0))

    private fun parse(xml: String): Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray())).documentElement

    private fun Element.all(name: String): List<Element> = getElementsByTagNameNS(ns, name).let { l -> (0 until l.length).map { l.item(it) as Element } }
    private fun Element.text(name: String): String = all(name).first().textContent.trim()
    private fun Element.placemarks(): List<Element> = getElementsByTagName("Placemark").let { l -> (0 until l.length).map { l.item(it) as Element } }

    @Test
    fun `camera profiles give DJI's published ground sample distances`() {
        assertEquals(100 / 37.5, CameraModel.MAVIC_3E.gsdM(100.0) * 100, 0.01) // DJI: M3E GSD = H / 37.5 cm
        assertEquals(100 / 80.0, CameraModel.ZENMUSE_P1_35.gsdM(100.0) * 100, 0.01) // DJI: P1 35 mm GSD = H / 80 cm
        assertEquals(2.35, CameraModel.MAVIC_2_PRO.gsdM(100.0) * 100, 0.01)
    }

    @Test
    fun `a Mavic 3E survey becomes one wayline with the same waypoints, heights and photo interval`() {
        val r = survey()
        val plan = r.plan
        assertTrue(MissionValidator.validate(plan, MissionLimits.WPML, 120.0).isEmpty())
        assertEquals(1, MissionValidator.split(plan, MissionLimits.WPML).size)

        val wl = parse(WpmlWriter.waylines(plan, WpmlTarget.MAVIC_3E))
        assertEquals("77", wl.text("droneEnumValue"))
        assertEquals("0", wl.text("droneSubEnumValue"))
        assertEquals("66", wl.text("payloadEnumValue"))
        assertEquals("relativeToStartPoint", wl.text("executeHeightMode"))
        assertEquals("goHome", wl.text("finishAction"))
        assertEquals("executeLostAction", wl.text("exitOnRCLost"))
        assertEquals("goBack", wl.text("executeRCLostAction"))
        assertEquals("20", wl.text("takeOffSecurityHeight"))
        assertEquals("80", wl.text("globalRTHHeight"))

        val pms = wl.placemarks()
        assertEquals(plan.waypoints.size, pms.size)
        pms.forEachIndexed { i, pm ->
            val w = plan.waypoints[i]
            assertEquals(i.toString(), pm.text("index"))
            assertEquals(w.heightM, pm.text("executeHeight").toDouble(), 1e-3)
            val (lng, lat) = pm.getElementsByTagName("coordinates").item(0).textContent.trim().split(",").map { it.toDouble() }
            assertEquals(w.lng, lng, 1e-9) // KML order: longitude, latitude
            assertEquals(w.lat, lat, 1e-9)
        }

        // Group 0: gimbal to -90 at the first waypoint. Group 1: a photo every photoSpacing metres, whole route.
        val groups = wl.all("actionGroup")
        assertEquals(listOf("0", "1"), groups.map { it.text("actionGroupId") })
        assertEquals("gimbalRotate", groups[0].text("actionActuatorFunc"))
        assertEquals("-90", groups[0].text("gimbalPitchRotateAngle"))
        assertEquals("multipleDistance", groups[1].text("actionTriggerType"))
        assertEquals(r.photoSpacingM, groups[1].text("actionTriggerParam").toDouble(), 1e-3)
        assertEquals("0", groups[1].text("actionGroupStartIndex"))
        assertEquals((plan.waypoints.size - 1).toString(), groups[1].text("actionGroupEndIndex"))
        assertEquals("takePhoto", groups[1].text("actionActuatorFunc"))
        assertEquals("%.3f".format(Locale.ROOT, plan.routeLengthM).trimEnd('0').trimEnd('.'), wl.text("distance"))
    }

    @Test
    fun `template carries the same waypoints, the payload's lens and the heading overrides`() {
        val base = survey().plan
        val plan = base.copy(
            waypoints = base.waypoints.mapIndexed { i, w -> if (i == 1) w.copy(headingDeg = 270.0, speedMps = 5.0, takePhoto = true) else w },
            photoDistanceIntervalM = null, finishAction = FinishAction.HOVER, exitOnRcSignalLost = false,
        )
        val t = parse(WpmlWriter.template(plan, WpmlTarget.MAVIC_3E, nowMs = 1_760_000_000_000))
        assertEquals("waypoint", t.text("templateType"))
        assertEquals("relativeToStartPoint", t.text("heightMode"))
        assertEquals("wide", t.text("imageFormat"))
        assertEquals("noAction", t.text("finishAction"))
        assertEquals("goContinue", t.text("exitOnRCLost"))
        assertEquals("goBack", t.text("executeRCLostAction")) // DJI's validator wants it even with goContinue
        val pms = t.placemarks()
        assertEquals(plan.waypoints.size, pms.size)
        // Waypoint 1: own heading (270 → -90), own speed, and a photo on arrival as group 1.
        assertEquals("0", pms[1].text("useGlobalHeadingParam"))
        assertEquals("smoothTransition", pms[1].text("waypointHeadingMode"))
        assertEquals("-90", pms[1].text("waypointHeadingAngle"))
        assertEquals("5", pms[1].text("waypointSpeed"))
        val g = pms[1].all("actionGroup").single()
        assertEquals("1", g.text("actionGroupId"))
        assertEquals("reachPoint", g.text("actionTriggerType"))
        assertEquals("takePhoto", g.text("actionActuatorFunc"))
        assertEquals("1", pms[0].text("useGlobalHeadingParam"))
    }

    @Test
    fun `numbers use a dot in any device locale, and the KMZ holds the two wpmz entries`() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val plan = MissionPlan(listOf(MissionWaypoint(-1.28, 36.82, 45.5), MissionWaypoint(-1.281, 36.821, 45.5)), autoSpeedMps = 7.5)
            val out = ByteArrayOutputStream()
            WpmlWriter.writeKmz(plan, WpmlTarget(WpmlTarget.MATRICE_400, 0, WpmlTarget.PAYLOAD_P1, imageFormat = "visible"), out, nowMs = 0)
            val entries = LinkedHashMap<String, String>()
            ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { z ->
                generateSequence { z.nextEntry }.forEach { e -> entries[e.name] = z.readBytes().toString(Charsets.UTF_8) }
            }
            assertEquals(listOf(WpmlWriter.TEMPLATE_ENTRY, WpmlWriter.WAYLINES_ENTRY), entries.keys.toList())
            val wl = parse(entries.getValue(WpmlWriter.WAYLINES_ENTRY))
            assertEquals("103", wl.text("droneEnumValue"))
            assertEquals("50", wl.text("payloadEnumValue"))
            assertEquals("7.5", wl.text("autoFlightSpeed"))
            assertEquals("45.5", wl.placemarks()[0].text("executeHeight"))
            assertEquals("-1.2800000000", wl.placemarks()[0].getElementsByTagName("coordinates").item(0).textContent.trim().split(",")[1])
            // A low mission: the safe take-off height and RTH height never exceed its top.
            assertEquals("20", wl.text("takeOffSecurityHeight"))
            assertEquals(45.5, wl.text("globalRTHHeight").toDouble())
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `a mission lower than 20 m caps the safe take-off height at its top`() {
        val plan = MissionPlan(listOf(MissionWaypoint(-1.28, 36.82, 12.0), MissionWaypoint(-1.281, 36.821, 12.0)), autoSpeedMps = 5.0)
        assertEquals(12.0, WpmlWriter.takeOffSecurityHeightM(plan))
        val wl = parse(WpmlWriter.waylines(plan, WpmlTarget.MAVIC_3E))
        assertEquals("12", wl.text("takeOffSecurityHeight"))
        assertEquals("12", wl.text("globalRTHHeight"))
    }
}

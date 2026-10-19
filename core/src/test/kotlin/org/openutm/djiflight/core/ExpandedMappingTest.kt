package org.openutm.djiflight.core

import org.openutm.djiflight.core.geo.*
import org.openutm.djiflight.core.mission.*
import java.nio.file.Files
import kotlin.test.*

class ExpandedMappingTest {
    private val plane = LocalPlane(TestData.NAIROBI)
    private val area = listOf(XY(0.0,0.0), XY(100.0,0.0), XY(100.0,100.0), XY(0.0,100.0)).map(plane::toLatLng)
    private fun spec(pattern: SurveyPattern) = SurveySpec(area, CameraModel.MINI_2, 40.0, 0.75, 0.65, 0.0, 3.0, pattern.defaultGimbalDeg, pattern = pattern)

    @Test fun `five direction writes all five camera orientations to WPML`() {
        val r = SurveyPlanner.plan(spec(SurveyPattern.FIVE_DIRECTION))
        assertTrue(MissionValidator.validate(r.plan, MissionLimits.WPML, 120.0).isEmpty())
        assertEquals(setOf(0.0,90.0,180.0,270.0), r.plan.waypoints.mapNotNull { it.headingDeg }.toSet())
        assertEquals(setOf(-90.0,-60.0),r.plan.waypoints.mapNotNull { it.gimbalPitchDeg }.toSet())
        val xml = WpmlWriter.waylines(r.plan, WpmlTarget.MAVIC_3E)
        assertTrue(xml.contains("<wpml:gimbalPitchRotateAngle>-90</wpml:gimbalPitchRotateAngle>"))
        assertTrue(xml.contains("<wpml:gimbalPitchRotateAngle>-60</wpml:gimbalPitchRotateAngle>"))
    }
    @Test fun `corridor covers both sides of a bent centreline and has no zero length joins`() {
        val s = spec(SurveyPattern.CORRIDOR).copy(polygon = listOf(XY(0.0,0.0), XY(100.0,0.0), XY(150.0,100.0)).map(plane::toLatLng), corridorWidthM = 60.0)
        val r = SurveyPlanner.plan(s)
        assertTrue(r.lineCount > 1)
        assertTrue(r.plan.waypoints.map { plane.toXY(it.position).y }.let { it.min() < 0 && it.max() > 0 })
        assertTrue(MissionValidator.validate(r.plan, MissionLimits.WPML, 120.0).isEmpty())
        assertFailsWith<IllegalArgumentException> { SurveyPlanner.plan(s.copy(polygon = listOf(XY(0.0,0.0),XY(100.0,0.0),XY(0.0,0.0)).map(plane::toLatLng))) }
    }
    @Test fun `surround stays outside footprint and includes bottom and top layers`() {
        val r = SurveyPlanner.plan(spec(SurveyPattern.SURROUND).copy(orbitTopHeightM = 85.0, orbitLayerSpacingM = 20.0))
        assertEquals(4,r.lineCount)
        assertEquals(40.0,r.plan.waypoints.minOf { it.heightM })
        assertEquals(85.0,r.plan.waypoints.maxOf { it.heightM })
        val center = plane.toLatLng(XY(50.0,50.0))
        assertTrue(r.plan.waypoints.all { Geo.distanceM(center,it.position) > 90 })
        assertTrue(MissionValidator.validate(r.plan,MissionLimits.WPML,120.0).isEmpty())
    }
    @Test fun `concave surveys route around recesses rather than across them`() {
        val ring=listOf(XY(0.0,0.0),XY(200.0,0.0),XY(200.0,200.0),XY(140.0,200.0),XY(140.0,60.0),XY(60.0,60.0),XY(60.0,200.0),XY(0.0,200.0)).map(plane::toLatLng)
        for(pattern in listOf(SurveyPattern.GRID,SurveyPattern.CROSSHATCH,SurveyPattern.FIVE_DIRECTION)) {
            val r=SurveyPlanner.plan(spec(pattern).copy(polygon=ring,headingDeg=90.0))
            for((a,b) in r.plan.waypoints.zipWithNext()) for(i in 0..20) {
                val f=i/20.0;val point=LatLng(a.lat+(b.lat-a.lat)*f,a.lng+(b.lng-a.lng)*f)
                assertTrue(Geo.contains(Geo.closeRing(ring),point) || Geo.distanceToRingM(point,Geo.closeRing(ring))<0.02,"$pattern leaves the polygon at $point")
            }
            assertTrue(MissionValidator.validate(r.plan,MissionLimits.WPML,120.0).isEmpty())
        }
    }
    @Test fun `self crossing polygons and unsupported KML holes are rejected`() {
        val bow=listOf(XY(0.0,0.0),XY(200.0,200.0),XY(0.0,200.0),XY(200.0,0.0)).map(plane::toLatLng)
        assertFailsWith<IllegalArgumentException> { SurveyPlanner.plan(spec(SurveyPattern.GRID).copy(polygon=bow)) }
        assertFailsWith<IllegalArgumentException> { org.openutm.djiflight.core.kml.Kml.parse("<kml><Placemark><Polygon><innerBoundaryIs/></Polygon></Placemark></kml>") }
    }
    private fun draft() = SavedSurvey(name="Field",points=area,pattern=SurveyPattern.GRID,heightM=40.0,frontOverlap=0.75,sideOverlap=0.65,headingDeg=0.0,speedMps=3.0,gimbalPitchDeg=-90.0)
    @Test fun `plan library survives reopen and stores planning inputs without account configuration`() {
        val dir=Files.createTempDirectory("plans").toFile()
        try {
            val p=draft(); val a=PlanLibrary(dir); a.save(p)
            val b=PlanLibrary(dir); assertEquals(listOf(p),b.list())
            assertFalse(b.encode(p).contains("client_secret"))
            assertEquals(p,b.decode(b.encode(p)))
            b.delete(p); assertTrue(a.list().isEmpty())
        } finally { dir.deleteRecursively() }
    }
    @Test fun `import rejects future schemas unsafe file identifiers and invalid geometry`() {
        assertFailsWith<IllegalArgumentException> { draft().copy(id="../../credential").validate() }
        assertFailsWith<IllegalArgumentException> { draft().copy(version=2).validate() }
        assertFailsWith<IllegalArgumentException> { draft().copy(heightM=Double.NaN).validate() }
        assertFailsWith<IllegalArgumentException> { draft().copy(points=listOf(LatLng(Double.NaN,1.0))).validate() }
    }
    @Test fun `Mini 2 stop and shoot spacing is never larger than requested and preserves original indices`() {
        val plan=MissionPlan(listOf(MissionWaypoint(area[0].lat,area[0].lng,40.0),MissionWaypoint(area[1].lat,area[1].lng,40.0)),3.0,photoDistanceIntervalM=20.0)
        val samples=VirtualStickRoute.sample(plan)
        assertEquals(listOf(0,1),samples.filter { it.original }.map { it.originalIndex })
        assertTrue(samples.zipWithNext { a,b -> Geo.distanceM(a.waypoint.position,b.waypoint.position) }.all { it <= 20.001 })
        assertTrue(samples.all { it.shoot })
        assertFailsWith<IllegalArgumentException> { VirtualStickRoute.sample(plan.copy(photoDistanceIntervalM=0.0)) }
    }

    @Test fun `later upload parts preserve an earlier inherited camera pitch`() {
        val points = (0..6).map { i -> plane.toLatLng(XY(i*10.0,0.0)).let {
            MissionWaypoint(it.lat,it.lng,40.0,gimbalPitchDeg=if(i==1) -60.0 else null)
        } }
        val plan = MissionPlan(points,3.0)
        val parts = MissionValidator.split(plan,MissionLimits.WAYPOINT_V1.copy(maxWaypoints=3))
        assertEquals(listOf(-90.0,-60.0,-60.0),parts.map { it.gimbalPitchDeg })
        assertEquals(-60.0,plan.pitchAt(4))
        assertTrue(WpmlWriter.waylines(parts[1],WpmlTarget.MAVIC_3E).contains("<wpml:gimbalPitchRotateAngle>-60</wpml:gimbalPitchRotateAngle>"))
    }

    @Test fun `nonfinite travel return and terrain inputs cannot pass upload validation`() {
        val plan = SurveyPlanner.plan(spec(SurveyPattern.GRID)).plan
        for(p in listOf(plan.copy(travelHeightM=Double.NaN),plan.copy(returnHomeHeightM=Double.POSITIVE_INFINITY),
            plan.copy(terrainFollowM=Double.NaN),plan.copy(waypoints=plan.waypoints.map { it.copy(groundM=Double.NaN) }))) {
            assertTrue(MissionValidator.validate(p,MissionLimits.WPML,120.0).isNotEmpty())
        }
    }
}

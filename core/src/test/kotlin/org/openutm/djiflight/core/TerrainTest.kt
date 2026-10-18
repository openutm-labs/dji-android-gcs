package org.openutm.djiflight.core

import org.openutm.djiflight.core.advisory.AdvisorInput
import org.openutm.djiflight.core.advisory.FlightAdvisor
import org.openutm.djiflight.core.advisory.Level
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.geo.LocalPlane
import org.openutm.djiflight.core.geo.XY
import org.openutm.djiflight.core.mission.CameraModel
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.core.mission.MissionValidator
import org.openutm.djiflight.core.mission.MissionWaypoint
import org.openutm.djiflight.core.mission.SurveyPlanner
import org.openutm.djiflight.core.mission.SurveySpec
import org.openutm.djiflight.core.terrain.ByteSource
import org.openutm.djiflight.core.terrain.PmTiles
import org.openutm.djiflight.core.terrain.Raster
import org.openutm.djiflight.core.terrain.TerrainCheck
import org.openutm.djiflight.core.terrain.TerrainFollow
import org.openutm.djiflight.core.terrain.TerrainModel
import org.openutm.djiflight.core.terrain.TerrariumTerrain
import org.openutm.djiflight.core.terrain.TileDecoder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerrainTest {
    private val z = 12
    private val size = 64

    /** A hillside: 200 m at the west edge of the tiles, rising 1 m every 10 m eastwards. */
    private fun heightAt(lng: Double): Double = 200.0 + (lng - 36.80) * 111_320 * kotlin.math.cos(Math.toRadians(-1.28)) / 10

    private fun tileX(lng: Double) = floor((lng + 180) / 360 * (1 shl z)).toInt()
    private fun tileY(lat: Double): Int { val r = Math.toRadians(lat); return floor((1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * (1 shl z)).toInt() }
    private fun lngOf(px: Double) = px / (1 shl z) * 360 - 180
    private fun latOf(py: Double) = Math.toDegrees(atan(kotlin.math.sinh(PI * (1 - 2 * py / (1 shl z)))))

    private fun terrariumPng(tx: Int, ty: Int): ByteArray {
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until size) for (x in 0 until size) {
            val h = heightAt(lngOf(tx + (x + 0.5) / size)) + 32768
            val r = (h / 256).toInt()
            val g = (h - r * 256).toInt()
            val b = ((h - r * 256 - g) * 256).toInt()
            img.setRGB(x, y, (r shl 16) or (g shl 8) or b)
        }
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    /** A PMTiles v3 archive written from the spec: one gzip root directory, tiles in id order. */
    private fun archive(tiles: Map<Triple<Int, Int, Int>, ByteArray>, bounds: List<Double>): ByteArray {
        val sorted = tiles.entries.map { (k, v) -> PmTiles.tileId(k.first, k.second, k.third) to v }.sortedBy { it.first }
        fun varint(out: ByteArrayOutputStream, v0: Long) { var v = v0; while (v >= 0x80) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }; out.write(v.toInt()) }
        val dir = ByteArrayOutputStream()
        varint(dir, sorted.size.toLong())
        var last = 0L
        sorted.forEach { varint(dir, it.first - last); last = it.first }
        sorted.forEach { varint(dir, 1) }
        sorted.forEach { varint(dir, it.second.size.toLong()) }
        var off = 0L
        sorted.forEachIndexed { i, t -> varint(dir, if (i == 0) off + 1 else 0); off += t.second.size }
        val root = ByteArrayOutputStream().also { GZIPOutputStream(it).use { g -> g.write(dir.toByteArray()) } }.toByteArray()
        val data = ByteArrayOutputStream().also { o -> sorted.forEach { o.write(it.second) } }.toByteArray()
        val h = ByteBuffer.allocate(PmTiles.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        h.put("PMTiles".toByteArray()).put(3)
        h.putLong(127).putLong(root.size.toLong()) // root
        h.putLong(127L + root.size).putLong(0) // metadata
        h.putLong(127L + root.size).putLong(0) // leaves
        h.putLong(127L + root.size).putLong(data.size.toLong()) // tile data
        h.putLong(sorted.size.toLong()).putLong(sorted.size.toLong()).putLong(sorted.size.toLong())
        h.put(1).put(PmTiles.COMPRESSION_GZIP.toByte()).put(PmTiles.COMPRESSION_NONE.toByte()).put(PmTiles.TYPE_PNG.toByte())
        h.put(z.toByte()).put(z.toByte())
        bounds.forEach { h.putInt((it * 1e7).toInt()) }
        h.put(z.toByte()).putInt(0).putInt(0)
        return h.array() + root + data
    }

    private val pngDecoder = TileDecoder { bytes ->
        ImageIO.read(bytes.inputStream())?.let { img -> Raster(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width)) }
    }

    private fun hillside(): TerrainModel {
        val (minLng, maxLng, minLat, maxLat) = listOf(36.80, 36.86, -1.30, -1.26)
        val tiles = HashMap<Triple<Int, Int, Int>, ByteArray>()
        for (x in tileX(minLng)..tileX(maxLng)) for (y in tileY(maxLat)..tileY(minLat)) tiles[Triple(z, x, y)] = terrariumPng(x, y)
        val bytes = archive(tiles, listOf(minLng, minLat, maxLng, maxLat))
        return TerrariumTerrain(PmTiles(ByteSource { o, n -> bytes.copyOfRange(o.toInt(), o.toInt() + n) }), pngDecoder)
    }

    @Test
    fun `tile ids follow the PMTiles spec's Hilbert curve`() {
        assertEquals(0, PmTiles.tileId(0, 0, 0))
        assertEquals(listOf(1L, 2L, 3L, 4L), listOf(PmTiles.tileId(1, 0, 0), PmTiles.tileId(1, 0, 1), PmTiles.tileId(1, 1, 1), PmTiles.tileId(1, 1, 0)))
        assertEquals(5, PmTiles.tileId(2, 0, 0))
        assertEquals(21, PmTiles.tileId(3, 0, 0))
    }

    @Test
    fun `heights come back from Terrarium tiles, interpolated, and nothing outside the file`() {
        val t = hillside()
        for (lng in listOf(36.81, 36.8237, 36.84, 36.8512)) {
            assertEquals(heightAt(lng), t.elevationM(LatLng(-1.28, lng))!!, 0.5, "at $lng")
        }
        assertNull(t.elevationM(LatLng(-1.28, 37.5)))
    }

    @Test
    fun `a survey up the hillside reports its clearance and the height above ground on the way down`() {
        val t = hillside()
        val plane = LocalPlane(LatLng(-1.28, 36.82))
        val takeoff = plane.toLatLng(XY(0.0, 0.0))
        // 600 m east is 60 m higher: at 80 m above take-off the route clears it by only about 20 m.
        val square = listOf(XY(0.0, 0.0), XY(600.0, 0.0), XY(600.0, 300.0), XY(0.0, 300.0)).map(plane::toLatLng)
        val r = SurveyPlanner.plan(SurveySpec(square, CameraModel.MAVIC_3E, 80.0, 0.75, 0.65, headingDeg = 90.0, speedMps = 8.0))
        val rep = assertNotNull(TerrainCheck.route(t, takeoff, r.plan.waypoints))
        assertEquals(60.0, rep.highestM, 2.0)
        assertEquals(20.0, rep.minClearanceM!!, 2.0)
        assertEquals(listOf("TERRAIN_LOW_CLEARANCE"), TerrainCheck.advisories(rep, 120.0).map { it.code })
        // Higher than the ridge: blocked.
        val into = TerrainCheck.route(t, takeoff, listOf(MissionWaypoint(takeoff.lat, takeoff.lng, 30.0), plane.toLatLng(XY(600.0, 0.0)).let { MissionWaypoint(it.lat, it.lng, 30.0) }))!!
        assertEquals(Level.CRITICAL, TerrainCheck.advisories(into, 120.0).single().level)
        // From the top of the hill, 100 m above take-off is 160 m above the bottom: over the legal limit.
        val down = TerrainCheck.area(t, plane.toLatLng(XY(600.0, 0.0)), square, 100.0)!!
        assertEquals(-60.0, down.lowestM, 2.0)
        assertTrue(TerrainCheck.advisories(down, 120.0).any { it.code == "TERRAIN_ABOVE_LEGAL" })
    }

    @Test
    fun `following the terrain file keeps the same survey 80 m above the hillside`() {
        val t = hillside()
        val plane = LocalPlane(LatLng(-1.28, 36.82))
        val takeoff = plane.toLatLng(XY(0.0, 0.0))
        val square = listOf(XY(0.0, 0.0), XY(600.0, 0.0), XY(600.0, 300.0), XY(0.0, 300.0)).map(plane::toLatLng)
        val flat = SurveyPlanner.plan(SurveySpec(square, CameraModel.MAVIC_3E, 80.0, 0.75, 0.65, headingDeg = 90.0, speedMps = 8.0)).plan
        val r = assertNotNull(TerrainFollow.follow(flat, takeoff, 80.0, t, MissionLimits.WAYPOINT_V1))
        // An even slope in the tiles: the line ends move, nothing is added.
        assertEquals(flat.waypoints.size, r.waypoints.size)
        assertEquals(140.0, r.maxHeightM, 2.0)
        val rep = assertNotNull(TerrainCheck.route(t, takeoff, r.waypoints))
        assertEquals(80.0, rep.minClearanceM!!, 2.0)
        assertEquals(80.0, rep.maxAboveGroundM, 2.0)
        assertEquals(emptyList(), TerrainCheck.advisories(rep, 120.0))
        assertEquals(emptyList(), MissionValidator.validate(r, MissionLimits.WAYPOINT_V1, 120.0))
    }

    @Test
    fun `in flight, the terrain turns height above take-off into height above ground`() {
        val plan = TestData.plan(1_792_142_000_000L)
        val snap = org.openutm.djiflight.core.session.FlightSnapshot("f", plan = plan)
        fun advise(h: Double, below: Double?) = FlightAdvisor.advise(
            AdvisorInput(snap, TestData.state(0, h = h, flying = true), telemetryStale = false, zones = null, nowMs = 0, groundBelowTakeoffM = below),
        ).map { it.code }
        assertTrue("ABOVE_LEGAL_AGL" in advise(100.0, 50.0))
        assertTrue("ABOVE_LEGAL_AGL" !in advise(100.0, 10.0))
        assertTrue("TERRAIN_CLOSE" in advise(60.0, -45.0))
        assertTrue(advise(60.0, null).none { it == "TERRAIN_CLOSE" || it == "ABOVE_LEGAL_AGL" })
    }
}

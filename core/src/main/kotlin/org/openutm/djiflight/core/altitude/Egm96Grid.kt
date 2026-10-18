package org.openutm.djiflight.core.altitude

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * EGM96 geoid undulation N on a regular grid, bilinearly interpolated.
 *
 * ellipsoid (WGS-84) height = orthometric (AMSL) height + N.
 *
 * The bundled `egm96-15.bin` is NGA's 15' grid as PROJ evaluates it (`tools/build_egm96_grid.py`),
 * so the app and the connector convert the same way. 721 x 1441 int16 cm, about 2 MB.
 */
class Egm96Grid private constructor(
    private val rows: Int,
    private val cols: Int,
    private val lat0: Double,
    private val lon0: Double,
    private val step: Double,
    private val cm: ShortArray,
) {
    fun undulationM(lat: Double, lon: Double): Double {
        require(lat.isFinite() && lon.isFinite()) { "non-finite position" }
        val la = lat.coerceIn(lat0 - step * (rows - 1), lat0)
        val lo = lon0 + ((lon - lon0) % 360.0 + 360.0) % 360.0
        val r = (lat0 - la) / step
        val c = (lo - lon0) / step
        val r0 = floor(r).toInt().coerceIn(0, rows - 2)
        val c0 = floor(c).toInt().coerceIn(0, cols - 2)
        val fr = r - r0
        val fc = c - c0
        fun v(i: Int, j: Int) = cm[i * cols + j] / 100.0
        return v(r0, c0) * (1 - fr) * (1 - fc) + v(r0, c0 + 1) * (1 - fr) * fc +
            v(r0 + 1, c0) * fr * (1 - fc) + v(r0 + 1, c0 + 1) * fr * fc
    }

    fun mslToEllipsoid(lat: Double, lon: Double, mslM: Double) = mslM + undulationM(lat, lon)
    fun ellipsoidToMsl(lat: Double, lon: Double, ellipsoidM: Double) = ellipsoidM - undulationM(lat, lon)

    companion object {
        private val MAGIC = "EGM96G1\u0000".toByteArray(Charsets.US_ASCII)
        const val RESOURCE = "/egm96-15.bin"

        fun load(input: InputStream): Egm96Grid = DataInputStream(input.buffered()).use { s ->
            val magic = ByteArray(MAGIC.size).also(s::readFully)
            require(magic.contentEquals(MAGIC)) { "not an EGM96G1 grid" }
            val rows = s.readInt()
            val cols = s.readInt()
            val lat0 = s.readDouble()
            val lon0 = s.readDouble()
            val step = s.readDouble()
            val bytes = ByteArray(rows * cols * 2).also(s::readFully)
            val cm = ShortArray(rows * cols)
            ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).asShortBuffer().get(cm)
            Egm96Grid(rows, cols, lat0, lon0, step, cm)
        }

        /** The grid bundled in core's resources. Load once and keep it; it is 2 MB. */
        fun bundled(): Egm96Grid = load(
            requireNotNull(Egm96Grid::class.java.getResourceAsStream(RESOURCE)) { "$RESOURCE missing from classpath" },
        )
    }
}

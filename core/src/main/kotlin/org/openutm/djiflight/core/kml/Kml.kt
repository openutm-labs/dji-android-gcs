package org.openutm.djiflight.core.kml

import org.openutm.djiflight.core.geo.LatLng
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** One KML Placemark: whichever of polygon (outer ring), line or point it carries. */
data class Placemark(val name: String?, val polygon: List<LatLng>?, val line: List<LatLng>?, val point: LatLng?)

/**
 * Reads site boundaries and routes from KML or KMZ (Google Earth, QGIS, DJI / Litchi exports).
 * Only geometry and names; styles and extended data are ignored.
 */
object Kml {
    fun read(bytes: ByteArray): List<Placemark> =
        if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) parseKmz(bytes) else parse(bytes.toString(Charsets.UTF_8))

    fun parseKmz(bytes: ByteArray): List<Placemark> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && e.name.endsWith(".kml", ignoreCase = true)) entries[e.name] = zip.readBytes()
            }
        }
        val kml = entries["doc.kml"] ?: entries.values.firstOrNull() ?: throw IllegalArgumentException("KMZ has no .kml inside")
        return parse(kml.toString(Charsets.UTF_8))
    }

    fun parse(text: String): List<Placemark> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // No external entities or DTDs (XXE); not every parser supports every feature.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        }
        val doc = runCatching { factory.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))) }
            .getOrElse { throw IllegalArgumentException("not valid KML: ${it.message}") }
        val placemarks = doc.getElementsByTagNameNS("*", "Placemark")
        return (0 until placemarks.length).map { i ->
            val pm = placemarks.item(i) as Element
            val name = pm.child("name")?.textContent?.trim()?.takeIf { it.isNotEmpty() }
            val polygon = pm.firstDescendant("Polygon")?.let { poly ->
                (poly.firstDescendant("outerBoundaryIs") ?: poly).firstDescendant("coordinates")?.let { coords(it.textContent) }
            }
            val line = pm.firstDescendant("LineString")?.firstDescendant("coordinates")?.let { coords(it.textContent) }
            val point = pm.firstDescendant("Point")?.firstDescendant("coordinates")?.let { coords(it.textContent).firstOrNull() }
            Placemark(name, polygon?.takeIf { it.size >= 3 }, line?.takeIf { it.size >= 2 }, point)
        }.filter { it.polygon != null || it.line != null || it.point != null }
    }

    /** `lon,lat[,alt] lon,lat[,alt] …` */
    fun coords(text: String): List<LatLng> = text.trim().split(Regex("\\s+")).mapNotNull { tuple ->
        val parts = tuple.split(",")
        val lng = parts.getOrNull(0)?.toDoubleOrNull()
        val lat = parts.getOrNull(1)?.toDoubleOrNull()
        if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) LatLng(lat, lng) else null
    }

    private fun Element.child(local: String): Element? {
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n is Element && (n.localName ?: n.nodeName) == local) return n
        }
        return null
    }

    private fun Element.firstDescendant(local: String): Element? = getElementsByTagNameNS("*", local).item(0) as? Element
}

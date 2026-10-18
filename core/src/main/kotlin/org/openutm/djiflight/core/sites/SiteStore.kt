package org.openutm.djiflight.core.sites

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.openutm.djiflight.core.declaration.GroundEstimate
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng
import java.io.File

/** A place the lab flies from, with the best ground height measured there so far. */
@Serializable
data class Site(val name: String, val center: LatLng, val ground: GroundEstimate, val updatedMs: Long)

/**
 * Saved sites, so a flight can be declared away from the site (the day before, at the office)
 * with a ground height measured on an earlier flight instead of a guess (docs/12, Declaration volume).
 */
class SiteStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val serializer = ListSerializer(Site.serializer())

    @Synchronized
    fun all(): List<Site> = runCatching { if (file.isFile) json.decodeFromString(serializer, file.readText()) else emptyList() }.getOrDefault(emptyList())

    /** Replaces the site with the same name, or the one within [mergeWithinM]; otherwise adds it. */
    @Synchronized
    fun upsert(site: Site, mergeWithinM: Double = 200.0) {
        val rest = all().filterNot { it.name == site.name || Geo.distanceM(it.center, site.center) <= mergeWithinM }
        val out = (rest + site).sortedBy { it.name.lowercase() }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(serializer, out))
        check(tmp.renameTo(file)) { "could not replace $file" }
    }

    fun nearest(p: LatLng, withinM: Double = 300.0): Site? =
        all().map { it to Geo.distanceM(it.center, p) }.filter { it.second <= withinM }.minByOrNull { it.second }?.first
}

package org.openutm.djiflight.core.provisioning

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

/** Public raster map configuration, independent of Flight Blender's private device credentials. */
@Serializable
data class OnlineBasemap(
    val name: String = "OpenStreetMap",
    @SerialName("tile_url") val tileUrl: String = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
    val attribution: String = "© OpenStreetMap contributors",
    @SerialName("attribution_url") val attributionUrl: String = "https://www.openstreetmap.org/copyright",
    @SerialName("max_zoom") val maxZoom: Int = 19,
) {
    fun problems(): List<String> = buildList {
        if (name.isBlank() || attribution.isBlank()) add("online_map name and attribution are required")
        if (!https(tileUrl.replace("{z}", "0").replace("{x}", "0").replace("{y}", "0"))) add("online_map.tile_url must be an https URL without user credentials or fragment")
        if (listOf("{z}", "{x}", "{y}").any { token -> tileUrl.windowed(token.length).count { it == token } != 1 }) add("online_map.tile_url needs one each of {z}, {x}, {y}")
        if (!https(attributionUrl)) add("online_map.attribution_url must be an https URL without user credentials or fragment")
        if (maxZoom !in 0..22) add("online_map.max_zoom must be 0..22")
    }

    private fun https(value: String): Boolean = runCatching {
        val u = URI(value)
        u.scheme == "https" && !u.host.isNullOrBlank() && u.rawUserInfo == null && u.rawFragment == null
    }.getOrDefault(false)
}

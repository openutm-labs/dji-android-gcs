package org.openutm.djiflight.core.mission

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.openutm.djiflight.core.geo.LatLng
import java.io.File
import java.util.UUID

/** Portable planning inputs, deliberately excluding credentials, pilot accounts and declarations. */
@Serializable
data class SavedSurvey(
    val version: Int = 1,
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val points: List<LatLng>,
    val pattern: SurveyPattern,
    val heightM: Double,
    val frontOverlap: Double,
    val sideOverlap: Double,
    val headingDeg: Double,
    val speedMps: Double,
    val gimbalPitchDeg: Double,
    val travelHeightM: Double? = null,
    val followTerrain: Boolean = false,
    val widthM: Double = 60.0,
    val topHeightM: Double? = null,
    val layerSpacingM: Double = 20.0,
) {
    fun validate() {
        require(version == 1) { "unsupported plan version" }
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "invalid plan identifier" }
        require(name.isNotBlank() && name.length <= 120)
        require(points.size in (if (pattern == SurveyPattern.CORRIDOR) 2 else 3)..256 && points.all { it.isValid })
        require(heightM.isFinite() && heightM in 1.0..500.0)
        require(frontOverlap in 0.0..0.95 && sideOverlap in 0.0..0.95)
        require(headingDeg.isFinite() && speedMps.isFinite() && speedMps in 0.1..15.0 && gimbalPitchDeg in -90.0..0.0)
        require(travelHeightM == null || travelHeightM.isFinite() && travelHeightM in 1.0..500.0)
        require(widthM.isFinite() && widthM in 5.0..500.0)
        require(topHeightM == null || topHeightM.isFinite() && topHeightM in heightM..500.0)
        require(layerSpacingM.isFinite() && layerSpacingM in 5.0..500.0)
    }
}

class PlanLibrary(private val dir: File) {
    private val json = Json { encodeDefaults = true }
    fun list(): List<SavedSurvey> = dir.listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull {
        runCatching { decode(it.readText()) }.getOrNull()
    }.sortedBy { it.name.lowercase() }

    fun save(plan: SavedSurvey) {
        plan.validate()
        check(dir.exists() || dir.mkdirs()) { "cannot create plan library" }
        val target = File(dir, "${plan.id}.json")
        val temp = File(dir, "${plan.id}.tmp")
        temp.writeText(encode(plan))
        check(temp.renameTo(target)) { "cannot save plan" }
    }
    fun delete(plan: SavedSurvey) { plan.validate(); check(File(dir, "${plan.id}.json").delete()) { "cannot delete plan" } }
    fun encode(plan: SavedSurvey): String { plan.validate(); return json.encodeToString(plan) }
    fun decode(text: String): SavedSurvey {
        require(text.length <= 512_000) { "plan file is too large" }
        return json.decodeFromString<SavedSurvey>(text).also { it.validate() }
    }
}

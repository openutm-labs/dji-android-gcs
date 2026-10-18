package org.openutm.djiflight.core.zones

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.openutm.djiflight.core.geo.BBox
import org.openutm.djiflight.core.sync.BackendResult
import org.openutm.djiflight.core.sync.UtmBackend
import org.openutm.djiflight.core.time.Clock
import java.io.File

/**
 * Blender's restriction zones, fetched when online and kept on the device for offline use.
 * Countries' zones are loaded into Blender by admins, so a new country needs no app release.
 */
class ZoneService(
    private val backend: UtmBackend,
    private val cacheFile: File,
    private val clock: Clock,
) {
    @Serializable
    private data class Cache(val fetchedAtMs: Long, val body: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val _index = MutableStateFlow(load())
    val index: StateFlow<ZoneIndex?> = _index.asStateFlow()

    fun ageMs(): Long? = _index.value?.let { clock.nowMs() - it.fetchedAtMs }

    /** Zones around [area] (grown by [marginM]) valid from now to [daysAhead] days ahead. */
    suspend fun refresh(area: BBox, marginM: Double = 5_000.0, daysAhead: Int = 2): BackendResult<ZoneIndex> {
        val now = clock.nowMs()
        return when (val r = backend.zones(area.expandedBy(marginM), now, now + daysAhead * 86_400_000L)) {
            is BackendResult.Ok -> {
                val idx = runCatching { ZoneIndex.parseBlender(r.value, now) }.getOrElse { return BackendResult.Rejected(200, "zone list unreadable: ${it.message}") }
                save(Cache(now, r.value))
                _index.value = idx
                BackendResult.Ok(idx)
            }
            is BackendResult.Rejected -> r
            is BackendResult.Transient -> r
            is BackendResult.Unauthorized -> r
            is BackendResult.WindowClosed -> r
            is BackendResult.NotActivated -> r
        }
    }

    private fun load(): ZoneIndex? = runCatching {
        if (!cacheFile.isFile) return null
        val c = json.decodeFromString(Cache.serializer(), cacheFile.readText())
        ZoneIndex.parseBlender(c.body, c.fetchedAtMs)
    }.getOrNull()

    private fun save(c: Cache) {
        cacheFile.parentFile?.mkdirs()
        val tmp = File(cacheFile.path + ".tmp")
        tmp.writeText(json.encodeToString(Cache.serializer(), c))
        check(tmp.renameTo(cacheFile)) { "could not replace $cacheFile" }
    }
}

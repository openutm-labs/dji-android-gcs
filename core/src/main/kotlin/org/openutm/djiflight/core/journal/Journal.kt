package org.openutm.djiflight.core.journal

import kotlinx.serialization.json.Json
import org.openutm.djiflight.core.model.TelemetrySample
import org.openutm.djiflight.core.session.FlightEvent
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32

/**
 * Append-only text file of `<crc32-hex8> <json>\n` lines.
 *
 * A line torn by a crash or power loss has no newline or a bad CRC and is ignored on read; the next
 * writer cuts a torn tail off before appending, so it can't corrupt the following line.
 */
class CrcLineFile(val file: File) {
    data class ReadResult(val lines: List<String>, val endOffset: Long, val corruptLines: Int)

    init {
        file.parentFile?.mkdirs()
        repairTail()
    }

    @Synchronized
    fun append(lines: List<String>, sync: Boolean) {
        if (lines.isEmpty()) return
        val sb = StringBuilder()
        for (l in lines) {
            require('\n' !in l) { "journal lines must not contain newlines" }
            sb.append(crcHex(l)).append(' ').append(l).append('\n')
        }
        FileOutputStream(file, true).use { out ->
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            out.flush()
            if (sync) out.fd.sync()
        }
    }

    /** Valid lines from [fromOffset] to the last complete line; [ReadResult.endOffset] is where to resume. */
    @Synchronized
    fun read(fromOffset: Long = 0): ReadResult {
        if (!file.exists()) return ReadResult(emptyList(), 0, 0)
        val bytes = RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (fromOffset >= len) return ReadResult(emptyList(), fromOffset, 0)
            raf.seek(fromOffset)
            ByteArray((len - fromOffset).toInt()).also { raf.readFully(it) }
        }
        val lines = ArrayList<String>()
        var corrupt = 0
        var start = 0
        var consumed = 0
        for (i in bytes.indices) {
            if (bytes[i] != '\n'.code.toByte()) continue
            val raw = String(bytes, start, i - start, Charsets.UTF_8)
            val payload = if (raw.length > 9 && raw[8] == ' ') raw.substring(9) else null
            if (payload != null && crcHex(payload) == raw.substring(0, 8)) lines += payload else corrupt++
            start = i + 1
            consumed = i + 1
        }
        return ReadResult(lines, fromOffset + consumed, corrupt)
    }

    private fun repairTail() {
        if (!file.exists() || file.length() == 0L) return
        RandomAccessFile(file, "rw").use { raf ->
            var pos = raf.length() - 1
            while (pos >= 0) {
                raf.seek(pos)
                if (raf.read() == '\n'.code) break
                pos--
            }
            if (pos + 1 < raf.length()) raf.setLength(pos + 1)
        }
    }

    companion object {
        fun crcHex(s: String): String {
            val c = CRC32()
            c.update(s.toByteArray(Charsets.UTF_8))
            return "%08x".format(c.value)
        }
    }
}

/**
 * One flight on disk:
 *   events.log   every state change (the session's only source of truth)
 *   samples.log  recorded TelemetrySamples
 *   cursor       last sample seq Blender accepted (owned by the sync engine)
 *
 * Writes to events and samples come only from the flight's single writer (FlightRecorder).
 * Open each flight once per process and share the instance: opening repairs a torn tail, which
 * must not race a writer that is mid-append.
 */
class FlightJournal(val dir: File, private val json: Json = JournalJson) {
    val flightId: String = dir.name
    /** Immutable destination association; malformed metadata must never behave as unassigned. */
    val serverId: String? = File(dir, "server-id").takeIf { it.exists() }?.readText()?.trim()?.let {
        if (it.matches(Regex("[a-f0-9]{64}"))) it else "invalid-or-unassigned"
    }
    private val events = CrcLineFile(File(dir, "events.log"))
    private val samples = CrcLineFile(File(dir, "samples.log"))
    private val cursorFile = File(dir, "cursor")
    private val exportedFile = File(dir, "exported")

    /** Set once the flight has been exported to a file; pruning only removes exported flights. */
    val exported: Boolean get() = exportedFile.exists()

    fun markExported(atMs: Long) = exportedFile.writeText(atMs.toString())

    fun deleteAll(): Boolean = dir.deleteRecursively()

    fun appendEvent(e: FlightEvent) = events.append(listOf(json.encodeToString(FlightEvent.serializer(), e)), sync = true)

    fun readEvents(): List<FlightEvent> = events.read().lines.map { json.decodeFromString(FlightEvent.serializer(), it) }

    fun appendSamples(s: List<TelemetrySample>, sync: Boolean) =
        samples.append(s.map { json.encodeToString(TelemetrySample.serializer(), it) }, sync)

    fun readSamples(): List<TelemetrySample> = samples.read().lines.map { json.decodeFromString(TelemetrySample.serializer(), it) }

    /** A reader that remembers how far it got, so each sync reads only new lines. */
    fun sampleReader() = SampleReader(samples, json)

    /** Independent archive progress and exact pending payload survive process death. */
    var archiveCursor: Long
        get() = File(dir, "archive-cursor").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: -1
        set(value) = saveArchiveFile("archive-cursor", value.toString())
    var archiveSession: kotlinx.serialization.json.JsonObject?
        get() = readArchiveFile("archive-session")
        set(value) = saveArchiveFile("archive-session", value?.toString())
    var archivePending: kotlinx.serialization.json.JsonObject?
        get() = readArchiveFile("archive-pending")
        set(value) = saveArchiveFile("archive-pending", value?.toString())

    private fun readArchiveFile(name: String): kotlinx.serialization.json.JsonObject? =
        File(dir, name).takeIf { it.exists() }?.readText()?.let { Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject }

    private fun saveArchiveFile(name: String, value: String?) {
        val file = File(dir, name)
        if (value == null) { check(!file.exists() || file.delete()) { "could not remove $file" }; return }
        val tmp = File(dir, "$name.tmp")
        FileOutputStream(tmp).use { out -> out.write(value.toByteArray(Charsets.UTF_8)); out.fd.sync() }
        check(tmp.renameTo(file)) { "could not replace $file" }
    }

    /** Last sample seq Blender accepted; -1 before the first upload. */
    var cursor: Long
        get() = cursorFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: -1
        set(value) {
            val tmp = File(dir, "cursor.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(value.toString().toByteArray())
                out.fd.sync()
            }
            check(tmp.renameTo(cursorFile)) { "could not replace $cursorFile" }
        }
}

class SampleReader internal constructor(private val file: CrcLineFile, private val json: Json) {
    private val buffer = ArrayDeque<TelemetrySample>()
    private var offset = 0L

    /** Up to [limit] samples with seq > [afterSeq], oldest first. */
    fun after(afterSeq: Long, limit: Int): List<TelemetrySample> {
        while (buffer.isNotEmpty() && buffer.first().seq <= afterSeq) buffer.removeFirst()
        if (buffer.size < limit) {
            val r = file.read(offset)
            offset = r.endOffset
            r.lines.map { json.decodeFromString(TelemetrySample.serializer(), it) }
                .filter { it.seq > afterSeq }
                .forEach(buffer::addLast)
        }
        return buffer.take(limit)
    }
}

/** All flights under one directory (app-private storage on Android). */
class FlightRepository(val root: File, val currentServerId: () -> String? = { null }) {
    init {
        root.mkdirs()
    }

    fun create(flightId: String): FlightJournal {
        val dir = File(root, flightId)
        require(!dir.exists()) { "flight $flightId already exists" }
        check(dir.mkdirs()) { "could not create flight directory" }
        currentServerId()?.let { bind(dir, it) }
        return FlightJournal(dir)
    }

    /**
     * One-time upgrade, BEFORE replacing the saved setup. Unknown legacy origins stay blocked.
     * Never opens event/sample writers and never overwrites an existing association.
     */
    fun migrateLegacy(serverId: String?) {
        // DJI may initialize the Application in its accessory process too.
        RandomAccessFile(File(root, ".server-binding.lock"), "rw").use { lockFile ->
            lockFile.channel.lock().use {
                val marker = File(root, ".server-binding-v1")
                if (marker.exists()) return
                if (serverId != null) root.listFiles { f -> f.isDirectory }?.forEach { dir ->
                    if (!File(dir, "server-id").exists()) bind(dir, serverId)
                }
                atomicWrite(marker, "1")
            }
        }
    }

    private fun bind(dir: File, serverId: String) {
        require(serverId.matches(Regex("[a-f0-9]{64}"))) { "invalid server identity" }
        check(!File(dir, "server-id").exists()) { "flight server already assigned" }
        atomicWrite(File(dir, "server-id"), serverId)
    }

    private fun atomicWrite(file: File, value: String) {
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(value.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        check(tmp.renameTo(file)) { "could not save flight server association" }
    }

    fun open(flightId: String): FlightJournal = FlightJournal(File(root, flightId).also { require(it.isDirectory) { "no flight $flightId" } })

    fun list(): List<FlightJournal> = root.listFiles { f -> f.isDirectory }?.sortedBy { it.name }?.map { FlightJournal(it) } ?: emptyList()
}

val JournalJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    classDiscriminator = "type"
}

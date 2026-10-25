package org.openutm.djiflight.core.sync

import kotlinx.serialization.json.*
import org.openutm.djiflight.core.journal.FlightJournal
import org.openutm.djiflight.core.model.TelemetrySample
import org.openutm.djiflight.core.session.FlightEvent
import org.openutm.djiflight.core.session.FlightSnapshot
import org.openutm.djiflight.core.time.Rfc3339
import kotlin.math.hypot

/** The same vendor-neutral archive contract used by the desktop adapter. */
data class ArchiveReceipt(val complete: Boolean, val receivedSampleCount: Long, val finalSampleCount: Long?)

/** Separate durable outbox: live RID acceptance must never acknowledge historical storage. */
class ArchiveUploader {
    suspend fun sync(
        snapshot: FlightSnapshot,
        journal: FlightJournal,
        lastSeq: Long,
        backend: UtmBackend,
        maxBatches: Int,
        record: suspend (FlightEvent) -> FlightSnapshot,
        now: Long,
    ): SyncOutcome {
        if (snapshot.archiveComplete) return SyncOutcome(if (snapshot.serverClosed) SyncStatus.DONE else SyncStatus.UP_TO_DATE)
        val plan = snapshot.plan ?: return SyncOutcome(SyncStatus.IDLE)
        val declarationId = snapshot.declarationId ?: return SyncOutcome(SyncStatus.IDLE)
        val events = journal.readEvents()
        val template = journal.archiveSession ?: buildJsonObject {
            put("schemaVersion", 2)
            put("id", snapshot.flightId)
            put("source", "dji")
            putJsonObject("sourceData") { put("registrationID", plan.registrationId) }
            putJsonObject("assignment") {
                put("declarationID", declarationId)
                put("vehicleID", plan.aircraftSerial)
                put("operatorID", plan.operatorId)
                putJsonObject("pilot") {
                    put("id", plan.pilotId)
                    put("name", plan.pilot?.name ?: plan.pilotSignIn?.name ?: plan.pilotId)
                    plan.pilot?.license?.takeIf { it.isNotBlank() }?.let { put("license", it) }
                    plan.pilot?.contact?.takeIf { it.isNotBlank() }?.let { put("contact", it) }
                }
            }
            val plannedAt = events.filterIsInstance<FlightEvent.Planned>().first().at
            val firstAt = journal.sampleReader().after(-1, 1).firstOrNull()?.t ?: plannedAt
            put("startedAt", Rfc3339.format(minOf(plannedAt, firstAt)))
            put("endedAt", JsonNull)
            put("finalSampleCount", JsonNull)
            put("revision", 1)
        }.also { journal.archiveSession = it }
        val reader = journal.sampleReader()
        repeat(maxBatches) {
            val pending = journal.archivePending ?: run {
                val samples = reader.after(journal.archiveCursor, 200)
                val final = snapshot.serverClosed && (samples.lastOrNull()?.seq ?: journal.archiveCursor) >= lastSeq
                if (samples.isEmpty() && !final) return SyncOutcome(SyncStatus.UP_TO_DATE)
                val session = if (final) JsonObject(template + buildJsonObject {
                    val terminalAt = events.firstOrNull { it is FlightEvent.FinishRequested || it is FlightEvent.CancelRequested || it is FlightEvent.WindowExpired }?.at
                        ?: events.filterIsInstance<FlightEvent.ServerClosed>().first().at
                    val lastAt = journal.readSamples().maxOfOrNull { it.t } ?: 0
                    put("endedAt", Rfc3339.format(maxOf(terminalAt, lastAt, events.filterIsInstance<FlightEvent.Planned>().first().at)))
                    put("finalSampleCount", lastSeq + 1)
                    put("revision", 2)
                }) else template
                buildJsonObject {
                    put("session", session)
                    put("samples", JsonArray(samples.map { sample(it, snapshot, events) }))
                }.also { journal.archivePending = it } // fsync before HTTP; replay the exact body after a crash.
            }
            when (val result = backend.sendArchive(pending)) {
                is BackendResult.Ok -> {
                    val sent = pending.getValue("samples").jsonArray
                    val expectedFinal = pending.getValue("session").jsonObject["finalSampleCount"]?.jsonPrimitive?.longOrNull
                    if (expectedFinal != null && (!result.value.complete || result.value.finalSampleCount != expectedFinal || result.value.receivedSampleCount != expectedFinal)) {
                        return SyncOutcome(SyncStatus.BLOCKED, message = "Archive is missing samples; local records retained")
                    }
                    if (sent.isNotEmpty()) journal.archiveCursor = sent.last().jsonObject.getValue("sequence").jsonPrimitive.long
                    // A crash before removing the pending file only causes an identical retry.
                    journal.archivePending = null
                    if (expectedFinal != null) {
                        record(FlightEvent.ArchiveCompleted(now, expectedFinal))
                        return SyncOutcome(SyncStatus.DONE)
                    }
                }
                is BackendResult.Transient -> return SyncOutcome(SyncStatus.BACKOFF, message = "Archive: ${result.message}")
                else -> return SyncOutcome(SyncStatus.BLOCKED, message = "Archive upload refused: $result. Local records retained; check server support, credentials and pilot metadata.")
            }
        }
        return SyncOutcome(SyncStatus.UP_TO_DATE)
    }

    private fun sample(s: TelemetrySample, snapshot: FlightSnapshot, events: List<FlightEvent>): JsonObject = buildJsonObject {
        val ref = events.filterIsInstance<FlightEvent.TakeoffReferenceResolved>().lastOrNull { it.at <= s.t }?.reference?.chosen
            ?: snapshot.plan?.ground?.let { org.openutm.djiflight.core.altitude.AltitudeCandidate(it.source, it.ellipsoidM, it.sigmaM) }
        put("sequence", s.seq)
        put("recordedAt", Rfc3339.format(s.t))
        putJsonObject("position") {
            put("latitude", s.lat); put("longitude", s.lng)
            put("relativeAltitude", s.h)
            ref?.let {
                put("altitudeWGS84", it.ellipsoidM + s.h)
                put("altitudeSource", it.source.name); put("altitudeSigma", it.sigmaM)
            }
            put("velocityNorth", s.vn); put("velocityEast", s.ve); put("velocityDown", s.vd)
            put("speed", hypot(s.vn, s.ve)); put("heading", ((s.yaw % 360) + 360) % 360)
            put("flying", s.flying); put("motorsOn", s.motors)
            s.sats?.let { put("satellites", it) }
        }
        put("sourceData", Json.encodeToJsonElement(TelemetrySample.serializer(), s))
    }
}

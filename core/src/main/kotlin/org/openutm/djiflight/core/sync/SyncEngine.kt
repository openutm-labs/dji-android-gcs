package org.openutm.djiflight.core.sync

import org.openutm.djiflight.core.declaration.DeclarationInput
import org.openutm.djiflight.core.declaration.DeclarationPlanner
import org.openutm.djiflight.core.journal.FlightJournal
import org.openutm.djiflight.core.journal.SampleReader
import org.openutm.djiflight.core.rid.FlightIdentity
import org.openutm.djiflight.core.session.FlightEvent
import org.openutm.djiflight.core.session.FlightSnapshot
import org.openutm.djiflight.core.time.Clock
import kotlin.math.min

data class SyncConfig(
    /** States per set_telemetry request. */
    val batchSize: Int = 300,
    /** Most batches in one pass, so one flight's backlog can't starve the others. */
    val maxBatchesPerPass: Int = 20,
    val minBackoffMs: Long = 2_000,
    val maxBackoffMs: Long = 60_000,
    val syncGraceMs: Long = DeclarationPlanner.DEFAULT_SYNC_GRACE_MS,
    val declareLeadMs: Long = DeclarationPlanner.MIN_LEAD_MS,
    /** Allowance for the device and Blender clocks disagreeing at the window's edges. */
    val clockSkewMs: Long = 5_000,
)

enum class SyncStatus {
    /** Nothing to do now. */
    IDLE,

    /** Everything that could be sent was accepted. */
    UP_TO_DATE,

    /** A call failed transiently; try again at [SyncOutcome.retryAtMs]. */
    BACKOFF,

    /** Needs a person or an upstream change (declare rejected, window expired, auth refused). */
    BLOCKED,

    /** Flight closed and all supported delivery channels acknowledged completion. */
    DONE,
}

data class SyncOutcome(val status: SyncStatus, val uploadedSamples: Int = 0, val retryAtMs: Long? = null, val message: String? = null)

/**
 * Store-and-forward for one flight at a time (docs/12, Sync engine).
 *
 * The engine owns the independent live and archive upload cursors. Every other result is handed to [record], which goes
 * to the flight's single writer; it never saves a flight snapshot (the cause of launch-risks H1).
 * Samples are never dropped: they stay in the journal until Blender accepts them.
 */
class SyncEngine(
    private val backend: UtmBackend,
    private val clock: Clock,
    private val config: SyncConfig = SyncConfig(),
) {
    private class Backoff(var delayMs: Long = 0, var nextAtMs: Long = 0)

    val supportsArchive: Boolean get() = backend.snapshot().supportsArchive
    private val archive = ArchiveUploader()

    private val backoff = HashMap<String, Backoff>()
    private val readers = HashMap<String, SampleReader>()

    suspend fun syncOnce(
        snapshot: FlightSnapshot,
        journal: FlightJournal,
        lastRecordedSeq: Long,
        record: suspend (FlightEvent) -> FlightSnapshot,
    ): SyncOutcome {
        val destination = backend.snapshot()
        if (journal.serverId != destination.serverId) return SyncOutcome(
            SyncStatus.BLOCKED, message = "Flight belongs to another server or has no verified server association. Restore its original device setup to sync; the record is kept on this device.",
        )
        val now = clock.nowMs()
        val b = backoff.getOrPut(snapshot.flightId) { Backoff() }
        if (now < b.nextAtMs) return SyncOutcome(SyncStatus.BACKOFF, retryAtMs = b.nextAtMs)
        val outcome = try {
            val live = step(snapshot, journal, lastRecordedSeq, record, now, destination)
            val current = FlightSnapshot.fold(snapshot.flightId, journal.readEvents())
            if (destination.supportsArchive && current.declarationId != null) {
                val archived = archive.sync(current, journal, lastRecordedSeq, destination, config.maxBatchesPerPass, record, now)
                when {
                    archived.status == SyncStatus.BLOCKED || archived.status == SyncStatus.BACKOFF -> archived
                    current.serverClosed -> archived
                    else -> live
                }
            } else live
        } catch (e: IllegalArgumentException) {
            SyncOutcome(SyncStatus.BLOCKED, message = e.message)
        }
        if (outcome.status == SyncStatus.BACKOFF) {
            b.delayMs = if (b.delayMs == 0L) config.minBackoffMs else min(b.delayMs * 2, config.maxBackoffMs)
            b.nextAtMs = now + b.delayMs
            return outcome.copy(retryAtMs = b.nextAtMs)
        }
        b.delayMs = 0
        b.nextAtMs = 0
        return outcome
    }

    private suspend fun step(
        start: FlightSnapshot,
        journal: FlightJournal,
        lastRecordedSeq: Long,
        record: suspend (FlightEvent) -> FlightSnapshot,
        now: Long,
        backend: UtmBackend,
    ): SyncOutcome {
        var s = start
        val plan = s.plan ?: return SyncOutcome(SyncStatus.IDLE)
        if (s.serverClosed) return if (s.windowExpired != null && !s.archiveComplete && !backend.supportsArchive)
            SyncOutcome(SyncStatus.BLOCKED, message = "Flight ended with unarchived samples retained on this device")
        else SyncOutcome(SyncStatus.DONE)

        // 1. Declare (or find the declaration an unanswered attempt may have created).
        if (s.declarationId == null) {
            if (s.cancelRequested && !s.everFlown) return SyncOutcome(SyncStatus.DONE, message = "cancelled before declaring")
            if (s.declareRejected != null) return SyncOutcome(SyncStatus.BLOCKED, message = "declaration rejected: ${s.declareRejected!!.message}")
            if (s.declareUnanswered) {
                val dp = s.declarationPlan!!
                when (val r = backend.findDeclaration(dp.clientRef, dp.startMs, dp.endMs)) {
                    is BackendResult.Ok -> s = record(if (r.value != null) FlightEvent.Declared(now, r.value) else FlightEvent.DeclareNotFound(now))
                    else -> return failure(r)
                }
            }
            if (s.declarationId == null) {
                val dp = DeclarationPlanner.plan(
                    DeclarationInput(
                        clientRef = plan.flightId, ring = plan.area, ground = plan.ground,
                        plannedMaxHeightM = plan.plannedMaxHeightM, legalMaxHeightM = plan.legalMaxHeightM,
                        missionMaxHeightM = plan.missionMaxHeightM, groundRiseM = plan.mission?.groundRiseM ?: 0.0, plannedStartMs = plan.plannedStartMs,
                        plannedEndMs = plan.plannedEndMs, nowMs = now, syncGraceMs = config.syncGraceMs, minLeadMs = config.declareLeadMs,
                        aircraftSerial = plan.aircraftSerial, originatingParty = plan.lab, submittedBy = "${plan.operatorId}/${plan.pilotId}",
                    ),
                )
                s = record(FlightEvent.DeclareAttempted(now, dp))
                s = when (val r = backend.declare(dp)) {
                    is BackendResult.Ok -> record(FlightEvent.Declared(clock.nowMs(), r.value))
                    is BackendResult.Rejected -> {
                        record(FlightEvent.DeclareRejected(clock.nowMs(), r.status, r.message))
                        return SyncOutcome(SyncStatus.BLOCKED, message = "declaration rejected: ${r.message}")
                    }
                    else -> return failure(r)
                }
            }
        }
        val id = s.declarationId!!

        // 2. Cancelled before take-off: close it on the server and stop.
        if (s.cancelRequested && !s.everFlown) {
            return when (val r = backend.setState(id, OperationState.CANCELLED)) {
                is BackendResult.Ok -> { record(FlightEvent.ServerClosed(clock.nowMs(), OperationState.CANCELLED)); SyncOutcome(SyncStatus.DONE) }
                else -> failure(r)
            }
        }

        // 3. Activate once the aircraft has flown (telemetry is refused before that).
        if (s.everFlown && !s.activated && s.windowExpired == null) {
            when (val r = backend.setState(id, OperationState.ACTIVATED)) {
                is BackendResult.Ok -> s = record(FlightEvent.Activated(clock.nowMs()))
                else -> return failure(r)
            }
        }

        // 4. Upload everything after the cursor, oldest first. Blender refuses telemetry before the
        //    window opens with the same message as after it closes, so wait for the start.
        var uploaded = 0
        val windowStart = s.declarationPlan?.startMs ?: 0
        if (s.activated && s.windowExpired == null && now < windowStart + config.clockSkewMs) {
            return SyncOutcome(SyncStatus.BACKOFF, message = "declared window opens at ${org.openutm.djiflight.core.time.Rfc3339.format(windowStart)}")
        }
        if (s.activated && s.windowExpired == null) {
            val reference = s.altitudeReference ?: return SyncOutcome(SyncStatus.BLOCKED, message = "no altitude reference")
            val identity = FlightIdentity(id, plan.aircraftSerial, plan.registrationId, plan.operatorId, plan.description.ifBlank { "${plan.lab} ${plan.kind}" })
            val reader = readers.getOrPut(s.flightId) { journal.sampleReader() }
            for (pass in 0 until config.maxBatchesPerPass) {
                val batch = reader.after(journal.cursor, config.batchSize)
                if (batch.isEmpty()) break
                when (val r = backend.sendTelemetry(identity, batch, reference)) {
                    is BackendResult.Ok -> {
                        journal.cursor = batch.last().seq
                        uploaded += batch.size
                    }
                    is BackendResult.WindowClosed -> {
                        val end = s.declarationPlan?.endMs ?: 0
                        if (clock.nowMs() < end - config.clockSkewMs) {
                            // Not past our declared end: most likely the clocks disagree near the start. Retry.
                            return SyncOutcome(SyncStatus.BACKOFF, uploaded, message = r.message)
                        }
                        // Keep the samples (export, later import) and go on to close the operation.
                        s = record(FlightEvent.WindowExpired(clock.nowMs(), batch.first().seq, r.message))
                        break
                    }
                    is BackendResult.NotActivated -> {
                        record(FlightEvent.ActivationLost(clock.nowMs(), r.message))
                        return SyncOutcome(SyncStatus.BACKOFF, uploaded, message = r.message)
                    }
                    else -> return failure(r, uploaded)
                }
            }
        }

        // 5. Close: after the pilot finished (or the window passed) and the backlog is in.
        val backlogEmpty = journal.cursor >= lastRecordedSeq
        val windowOver = s.declarationPlan?.let { now > it.endMs } ?: false
        if ((s.finishRequested || windowOver) && (backlogEmpty || s.windowExpired != null)) {
            val state = if (s.everFlown) OperationState.ENDED else OperationState.WITHDRAWN
            return when (val r = backend.setState(id, state)) {
                is BackendResult.Ok -> {
                    record(FlightEvent.ServerClosed(clock.nowMs(), state))
                    SyncOutcome(if (s.windowExpired != null && !backend.supportsArchive) SyncStatus.BLOCKED else SyncStatus.DONE, uploaded)
                }
                else -> failure(r, uploaded)
            }
        }
        if (s.windowExpired != null) return SyncOutcome(SyncStatus.BLOCKED, uploaded, message = "window closed with samples left on the device")
        return SyncOutcome(if (uploaded > 0 || backlogEmpty) SyncStatus.UP_TO_DATE else SyncStatus.IDLE, uploaded)
    }

    private fun failure(r: BackendResult<*>, uploaded: Int = 0): SyncOutcome = when (r) {
        is BackendResult.Transient -> SyncOutcome(SyncStatus.BACKOFF, uploaded, message = r.message)
        is BackendResult.Unauthorized -> SyncOutcome(SyncStatus.BLOCKED, uploaded, message = "not authorised: ${r.message}")
        is BackendResult.Rejected -> SyncOutcome(SyncStatus.BLOCKED, uploaded, message = "HTTP ${r.status}: ${r.message}")
        is BackendResult.WindowClosed -> SyncOutcome(SyncStatus.BLOCKED, uploaded, message = r.message)
        is BackendResult.NotActivated -> SyncOutcome(SyncStatus.BACKOFF, uploaded, message = r.message)
        is BackendResult.Ok -> error("not a failure")
    }
}

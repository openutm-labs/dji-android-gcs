package org.openutm.djiflight.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.openutm.djiflight.core.adapter.DroneAdapter
import org.openutm.djiflight.core.adapter.DroneState
import org.openutm.djiflight.core.adapter.LinkState
import org.openutm.djiflight.core.altitude.Egm96Grid
import org.openutm.djiflight.core.altitude.GnssFix
import org.openutm.djiflight.core.journal.FlightJournal
import org.openutm.djiflight.core.journal.FlightRepository
import org.openutm.djiflight.core.model.FlightPlan
import org.openutm.djiflight.core.session.FlightActor
import org.openutm.djiflight.core.session.FlightEvent
import org.openutm.djiflight.core.session.FlightRecorder
import org.openutm.djiflight.core.session.FlightSnapshot
import org.openutm.djiflight.core.session.Phase
import org.openutm.djiflight.core.session.RecorderConfig
import org.openutm.djiflight.core.sync.SyncEngine
import org.openutm.djiflight.core.sync.SyncOutcome
import org.openutm.djiflight.core.sync.SyncStatus
import org.openutm.djiflight.core.time.Clock

data class ServiceConfig(
    /** Sync interval while a flight is in the air or landed with a backlog (live view in Spotlight). */
    val liveSyncIntervalMs: Long = 2_000,
    /** Retry interval for flights waiting on the network; nothing runs when nothing is pending. */
    val idleSyncIntervalMs: Long = 30_000,
    /** Connected but no state for this long: telemetry is stale (launch-risks B4). */
    val staleAfterMs: Long = 3_000,
    val resubscribeEveryMs: Long = 5_000,
    val recorder: RecorderConfig = RecorderConfig(),
)

/** Photos taken during one flight: [firstMs]/[lastMs] help find them on the SD card (UTC). */
data class PhotoStats(val count: Int, val firstMs: Long?, val lastMs: Long?) {
    companion object {
        /**
         * Sums the increases of the running count. The first sample's count is the baseline (photos
         * from earlier flights in the same session); a drop means the app restarted and counts from 0 again.
         */
        fun of(samples: List<org.openutm.djiflight.core.model.TelemetrySample>): PhotoStats {
            var prev: Int? = null
            var count = 0
            var first: Long? = null
            var last: Long? = null
            for (s in samples) {
                val n = s.photos ?: continue
                val p = prev
                if (p != null && n > p) {
                    count += n - p
                    if (first == null) first = s.t
                    last = s.t
                } else if (p != null && n < p && n > 0) {
                    count += n
                    if (first == null) first = s.t
                    last = s.t
                }
                prev = n
            }
            return PhotoStats(count, first, last)
        }
    }
}

data class ServiceStatus(
    val link: LinkState = LinkState.Disconnected,
    val telemetryStale: Boolean = false,
    val online: Boolean = true,
    val activeFlight: FlightSnapshot? = null,
    val lastState: DroneState? = null,
    val lastSync: SyncOutcome? = null,
    val pendingFlights: Int = 0,
)

/**
 * Wires one adapter to the flight journals and the sync engine. Android-free, so the whole
 * pipeline runs in JVM tests with the fake adapter (adapter-fake) against a real Blender.
 *
 * [clock] should be a [org.openutm.djiflight.core.time.CorrectedClock] (also given to the
 * [SyncEngine]); adapters stamp states with the device's own clock and the recorder corrects them.
 */
class FlightService(
    private val adapter: DroneAdapter,
    private val repo: FlightRepository,
    private val sync: SyncEngine,
    private val clock: Clock,
    private val grid: Egm96Grid,
    private val config: ServiceConfig = ServiceConfig(),
    parent: CoroutineScope,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private class Flight(val journal: FlightJournal, val recorder: FlightRecorder, val actor: FlightActor)

    private val flights = java.util.concurrent.CopyOnWriteArrayList<Flight>()
    @Volatile private var active: Flight? = null
    private val syncMutex = Mutex()
    private val kick = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var lastStateAtMs = 0L
    @Volatile private var lastResubscribeMs = 0L

    private val _status = MutableStateFlow(ServiceStatus())
    val status: StateFlow<ServiceStatus> = _status.asStateFlow()

    @Volatile private var started = false
    @Volatile private var adapterStarted = false

    /**
     * Opens the journals and starts syncing. [withAdapter] = false is for background sync jobs:
     * uploads continue without touching the drone SDK; call [startAdapter] when a screen opens.
     */
    @Synchronized
    fun start(withAdapter: Boolean = true) {
        if (started) {
            if (withAdapter) startAdapter()
            return
        }
        started = true
        // Open each journal once: a second instance would be a second writer.
        for (j in repo.list()) if (flights.none { it.journal.flightId == j.flightId }) open(j)
        if (active == null) {
            active = flights.lastOrNull { !it.recorder.snapshot.recordingClosed && it.journal.serverId == repo.currentServerId() }
            active?.let(::watch)
        }
        scope.launch { adapter.link.collect(::onLink) }
        scope.launch { adapter.states.collect(::onState) }
        scope.launch { watchdog() }
        scope.launch { syncLoop() }
        if (withAdapter) startAdapter()
    }

    @Synchronized
    fun startAdapter() {
        if (adapterStarted) return
        adapterStarted = true
        adapter.start()
    }

    /** Flights whose operation or durable archive still needs synchronization. */
    fun pendingFlights(): Int = pendingCount()

    fun requiresArchive(): Boolean = sync.supportsArchive

    fun stop() {
        adapter.stop()
        scope.cancel()
    }

    /** Opens a new flight and makes it the one the aircraft's states are recorded into. */
    suspend fun plan(plan: FlightPlan): FlightSnapshot {
        val f = open(repo.create(plan.flightId))
        val snap = f.actor.record(FlightEvent.Planned(clock.nowMs(), plan))
        check(f.journal.serverId == repo.currentServerId()) { "UTM server changed while saving the plan. Review the selected server and create a new plan." }
        active = f
        watch(f)
        (adapter.link.value as? LinkState.Connected)?.let { f.actor.offerAircraft(it.identity, clock.nowMs()) }
        kick.trySend(Unit)
        return snap
    }

    /** Records the lab checklist against [flightId] (the pilot ticks it on site, before take-off). */
    suspend fun completeChecklist(flightId: String, items: List<String>, pilotId: String?): FlightSnapshot? =
        find(flightId)?.actor?.record(FlightEvent.ChecklistCompleted(clock.nowMs(), items, pilotId))

    /**
     * The pilot's pick between take-off heights that disagree (controller GNSS or the aircraft's);
     * null if the flight has no such disagreement or no candidate from [source].
     */
    suspend fun chooseTakeoffReference(flightId: String, source: org.openutm.djiflight.core.altitude.AltitudeSource): FlightSnapshot? {
        val a = find(flightId)?.actor ?: return null
        val ref = a.current().takeoffReference ?: return null
        val chosen = org.openutm.djiflight.core.altitude.TakeoffReferenceResolver.choose(ref, source) ?: return null
        return a.record(FlightEvent.TakeoffReferenceResolved(clock.nowMs(), chosen))
    }

    /** Records that the flight's photos went to the processing service; works on closed flights too. */
    suspend fun markImageryHandedOff(flightId: String, service: String, name: String, photos: Int): FlightSnapshot? =
        find(flightId)?.actor?.record(FlightEvent.ImageryHandedOff(clock.nowMs(), service, name, photos))

    /** Photos taken during the flight, from the running count in its samples. */
    fun photoStats(flightId: String): PhotoStats = PhotoStats.of(samples(flightId))

    suspend fun finish() = activeCommand { FlightEvent.FinishRequested(it) }
    suspend fun cancel() = activeCommand { FlightEvent.CancelRequested(it) }

    private suspend fun activeCommand(make: (Long) -> FlightEvent): FlightSnapshot? {
        val f = currentActive() ?: return null
        val snap = f.actor.record(make(clock.nowMs()))
        kick.trySend(Unit)
        return snap
    }

    fun onDeviceFix(fix: GnssFix) {
        (clock as? org.openutm.djiflight.core.time.CorrectedClock)?.offsetMs = fix.gnssTimeMs - fix.deviceTimeMs
        currentActive()?.actor?.offerFix(fix)
    }

    fun setOnline(online: Boolean) {
        _status.update { it.copy(online = online) }
        if (online) kick.trySend(Unit)
    }

    /** Keep history, but never record a new server's aircraft states into an old server's flight. */
    fun backendChanged() {
        if (active?.journal?.serverId != repo.currentServerId()) {
            active = null
            _status.update { it.copy(activeFlight = null, lastSync = null) }
        }
        syncSoon()
    }

    fun syncSoon() {
        kick.trySend(Unit)
    }

    /** One sync pass over every flight that isn't finished on the server. */
    suspend fun syncAll(): List<SyncOutcome> = syncMutex.withLock {
        val out = ArrayList<SyncOutcome>()
        var activeOutcome: SyncOutcome? = null
        for (f in flights) {
            val snap = f.actor.current()
            if (snap.plan == null || (snap.serverClosed && (!sync.supportsArchive || snap.archiveComplete))) continue
            val outcome = sync.syncOnce(snap, f.journal, f.recorder.lastRecordedSeq, f.actor::record)
            out += outcome
            if (currentActive() === f) activeOutcome = outcome
        }
        _status.update { s -> s.copy(lastSync = activeOutcome ?: out.lastOrNull() ?: s.lastSync, pendingFlights = pendingCount()) }
        out
    }

    fun snapshots(): List<FlightSnapshot> = flights.map { it.actor.snapshot.value }

    fun snapshot(flightId: String): FlightSnapshot? = find(flightId)?.actor?.snapshot?.value

    /** Recorded samples, oldest first (for export and the map's track). */
    fun samples(flightId: String): List<org.openutm.djiflight.core.model.TelemetrySample> = find(flightId)?.journal?.readSamples() ?: emptyList()

    /** Samples recorded but not yet accepted by Blender. */
    fun pendingSamples(flightId: String): Long = find(flightId)?.let { (it.recorder.lastRecordedSeq - (if (sync.supportsArchive) it.journal.archiveCursor else it.journal.cursor)).coerceAtLeast(0) } ?: 0

    fun isExported(flightId: String): Boolean = find(flightId)?.journal?.exported ?: false

    fun markExported(flightId: String) {
        find(flightId)?.journal?.markExported(clock.nowMs())
    }

    /** Deletes a flight that is finished on the server, cancelled or expired (never the active one in progress). */
    fun delete(flightId: String): Boolean {
        val f = find(flightId) ?: return false
        val s = f.actor.snapshot.value
        val finished = s.serverClosed || s.windowExpired != null || (s.cancelRequested && !s.everFlown) || s.plan == null
        if (!finished) return false
        if (active === f) active = null
        f.actor.close()
        flights.remove(f)
        return f.journal.deleteAll()
    }

    /** Removes exported, finished flights whose last event is older than [maxAgeMs]. */
    fun prune(maxAgeMs: Long = 90L * 86_400_000L): Int {
        val cutoff = clock.nowMs() - maxAgeMs
        return flights.filter { it.journal.exported && (!sync.supportsArchive || it.actor.snapshot.value.archiveComplete) && it.actor.snapshot.value.lastEventAt < cutoff }.count { delete(it.journal.flightId) }
    }

    private fun currentActive(): Flight? = active?.takeIf { it.journal.serverId == repo.currentServerId() }

    private fun find(flightId: String) = flights.firstOrNull { it.journal.flightId == flightId }

    private fun open(j: FlightJournal): Flight {
        val rec = FlightRecorder(j, grid, config.recorder)
        val f = Flight(j, rec, FlightActor(scope, rec))
        flights += f
        return f
    }

    private fun watch(f: Flight) {
        scope.launch {
            var phase: Phase? = null
            f.actor.snapshot.collect { snap ->
                if (currentActive() === f) _status.update { it.copy(activeFlight = snap) }
                // Take-off, landing, finish: the sync loop may be asleep with nothing pending; wake it.
                if (snap.phase != phase) {
                    phase = snap.phase
                    kick.trySend(Unit)
                }
            }
        }
    }

    private fun onLink(link: LinkState) {
        _status.update { it.copy(link = link) }
        if (link is LinkState.Connected) currentActive()?.actor?.offerAircraft(link.identity, clock.nowMs())
    }

    /** Staleness is measured on a monotonic clock, so a wall-clock correction can't fake or hide it. */
    private fun monoMs() = System.nanoTime() / 1_000_000

    private fun onState(s: DroneState) {
        lastStateAtMs = monoMs()
        currentActive()?.actor?.offerState(s)
        _status.update { it.copy(lastState = s, telemetryStale = false) }
    }

    private suspend fun watchdog() {
        adapter.link.collectLatest { link ->
            if (link !is LinkState.Connected) return@collectLatest
            lastStateAtMs = monoMs()
            while (scope.isActive) {
                delay(1_000)
                val now = monoMs()
                if (now - lastStateAtMs > config.staleAfterMs) {
                    _status.update { it.copy(telemetryStale = true) }
                    if (now - lastResubscribeMs > config.resubscribeEveryMs) {
                        lastResubscribeMs = now
                        adapter.resubscribe()
                    }
                }
            }
        }
    }

    private suspend fun syncLoop() {
        while (scope.isActive) {
            val online = _status.value.online
            val outcomes = if (online) syncAll() else emptyList()
            val live = flights.any { it.actor.snapshot.value.phase.let { p -> p == Phase.ACTIVE || p == Phase.LANDED } }
            val waiting = outcomes.any { it.status == SyncStatus.BACKOFF || it.status == SyncStatus.UP_TO_DATE } || (!online && pendingCount() > 0)
            val wait = when {
                live && online -> config.liveSyncIntervalMs
                waiting -> outcomes.mapNotNull { it.retryAtMs }.minOrNull()?.let { (it - clock.nowMs()).coerceIn(500, config.idleSyncIntervalMs) }
                    ?: config.idleSyncIntervalMs
                else -> null // nothing pending: sleep until something changes
            }
            if (wait == null) kick.receive() else withTimeoutOrNull(wait) { kick.receive() }
        }
    }

    private fun pendingCount() = flights.count {
        val s = it.actor.snapshot.value
        s.plan != null && s.declareRejected == null && (!s.serverClosed || (sync.supportsArchive && !s.archiveComplete))
    }
}

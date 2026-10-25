package org.openutm.djiflight.core.session

import org.openutm.djiflight.core.adapter.AircraftIdentity
import org.openutm.djiflight.core.altitude.AltitudeCandidate
import org.openutm.djiflight.core.declaration.DeclarationPlan
import org.openutm.djiflight.core.model.FlightPlan

/** What the pilot sees (docs/12, Flight session). */
enum class Phase { PLANNED, DECLARED, ACTIVE, LANDED, CLOSED, EXPIRED_UNSYNCED, UNDECLARED, CANCELLED }

/** A flight's state, derived only by folding its events. */
data class FlightSnapshot(
    val flightId: String,
    val plan: FlightPlan? = null,
    /** Last plan sent (or about to be sent) to Blender. */
    val declarationPlan: DeclarationPlan? = null,
    val declarationId: String? = null,
    /** A declare went out with no answer recorded yet: look it up before declaring again. */
    val declareUnanswered: Boolean = false,
    val declareRejected: FlightEvent.DeclareRejected? = null,
    val aircraft: AircraftIdentity? = null,
    val takeoffReference: org.openutm.djiflight.core.altitude.TakeoffReference? = null,
    val airborne: Boolean = false,
    val everFlown: Boolean = false,
    val activated: Boolean = false,
    val finishRequested: Boolean = false,
    val cancelRequested: Boolean = false,
    val serverClosed: Boolean = false,
    val archiveComplete: Boolean = false,
    val windowExpired: FlightEvent.WindowExpired? = null,
    val checklist: FlightEvent.ChecklistCompleted? = null,
    val imageryHandoff: FlightEvent.ImageryHandedOff? = null,
    val lastEventAt: Long = 0,
) {
    val phase: Phase
        get() = when {
            cancelRequested && !everFlown -> Phase.CANCELLED
            windowExpired != null && !archiveComplete -> Phase.EXPIRED_UNSYNCED
            serverClosed -> Phase.CLOSED
            declarationId == null && everFlown -> Phase.UNDECLARED
            airborne -> Phase.ACTIVE
            everFlown -> Phase.LANDED
            declarationId != null -> Phase.DECLARED
            else -> Phase.PLANNED
        }

    /** No more samples should be recorded. */
    val recordingClosed: Boolean
        get() = finishRequested || (cancelRequested && !everFlown) || serverClosed || windowExpired != null

    /** Height of the take-off point used for every sample's altitude. */
    val altitudeReference: AltitudeCandidate?
        get() = takeoffReference?.chosen ?: plan?.ground?.let { AltitudeCandidate(it.source, it.ellipsoidM, it.sigmaM) }

    /** Declared serial differs from the aircraft actually connected. */
    val aircraftMismatch: Boolean
        get() = aircraft != null && plan != null && aircraft.serial != plan.aircraftSerial

    fun apply(e: FlightEvent): FlightSnapshot {
        val s = when (e) {
            is FlightEvent.Planned -> copy(plan = e.plan)
            is FlightEvent.DeclareAttempted -> copy(declarationPlan = e.plan, declareUnanswered = true, declareRejected = null)
            is FlightEvent.Declared -> copy(declarationId = e.declarationId, declareUnanswered = false)
            is FlightEvent.DeclareNotFound -> copy(declareUnanswered = false)
            is FlightEvent.DeclareRejected -> copy(declareRejected = e, declareUnanswered = false)
            is FlightEvent.AircraftConnected -> copy(aircraft = e.identity)
            is FlightEvent.TakeoffReferenceResolved -> copy(takeoffReference = e.reference)
            is FlightEvent.TookOff -> copy(airborne = true, everFlown = true)
            is FlightEvent.Landed -> copy(airborne = false)
            is FlightEvent.Activated -> copy(activated = true)
            is FlightEvent.ActivationLost -> copy(activated = false)
            is FlightEvent.FinishRequested -> copy(finishRequested = true)
            is FlightEvent.CancelRequested -> copy(cancelRequested = true)
            is FlightEvent.ServerClosed -> copy(serverClosed = true)
            is FlightEvent.ArchiveCompleted -> copy(archiveComplete = true)
            is FlightEvent.WindowExpired -> copy(windowExpired = e)
            is FlightEvent.ChecklistCompleted -> copy(checklist = e)
            is FlightEvent.ImageryHandedOff -> copy(imageryHandoff = e)
        }
        return s.copy(lastEventAt = maxOf(lastEventAt, e.at))
    }

    companion object {
        fun fold(flightId: String, events: List<FlightEvent>) = events.fold(FlightSnapshot(flightId)) { s, e -> s.apply(e) }
    }
}

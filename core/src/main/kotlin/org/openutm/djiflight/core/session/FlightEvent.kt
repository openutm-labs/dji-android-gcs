package org.openutm.djiflight.core.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.openutm.djiflight.core.adapter.AircraftIdentity
import org.openutm.djiflight.core.altitude.TakeoffReference
import org.openutm.djiflight.core.declaration.DeclarationPlan
import org.openutm.djiflight.core.model.FlightPlan
import org.openutm.djiflight.core.sync.OperationState

/**
 * Everything that happens to a flight, appended to its journal by the flight's single writer.
 * The flight's state is a fold over these, so a restart reproduces it exactly (no H1-style
 * stale snapshot can overwrite a newer one: nothing ever saves a snapshot).
 */
@Serializable
sealed class FlightEvent {
    /** Epoch ms UTC (GNSS-corrected device time). */
    abstract val at: Long

    @Serializable @SerialName("planned")
    data class Planned(override val at: Long, val plan: FlightPlan) : FlightEvent()

    /** Written before the declare request goes out, so an unanswered request is looked up, not repeated. */
    @Serializable @SerialName("declare_attempted")
    data class DeclareAttempted(override val at: Long, val plan: DeclarationPlan) : FlightEvent()

    @Serializable @SerialName("declared")
    data class Declared(override val at: Long, val declarationId: String) : FlightEvent()

    /** Blender looked for an earlier attempt and found none; the next pass may declare again. */
    @Serializable @SerialName("declare_not_found")
    data class DeclareNotFound(override val at: Long) : FlightEvent()

    @Serializable @SerialName("declare_rejected")
    data class DeclareRejected(override val at: Long, val status: Int, val message: String) : FlightEvent()

    @Serializable @SerialName("aircraft")
    data class AircraftConnected(override val at: Long, val identity: AircraftIdentity) : FlightEvent()

    @Serializable @SerialName("takeoff_reference")
    data class TakeoffReferenceResolved(override val at: Long, val reference: TakeoffReference) : FlightEvent()

    @Serializable @SerialName("took_off")
    data class TookOff(override val at: Long, val seq: Long) : FlightEvent()

    @Serializable @SerialName("landed")
    data class Landed(override val at: Long, val seq: Long) : FlightEvent()

    /** Blender accepted the Activated state. */
    @Serializable @SerialName("activated")
    data class Activated(override val at: Long) : FlightEvent()

    /** Blender says the operation isn't active any more (state changed elsewhere); activate again. */
    @Serializable @SerialName("activation_lost")
    data class ActivationLost(override val at: Long, val message: String) : FlightEvent()

    @Serializable @SerialName("finish_requested")
    data class FinishRequested(override val at: Long) : FlightEvent()

    @Serializable @SerialName("cancel_requested")
    data class CancelRequested(override val at: Long) : FlightEvent()

    /** Blender accepted the closing state (Ended, or Cancelled for a flight that never took off). */
    @Serializable @SerialName("server_closed")
    data class ServerClosed(override val at: Long, val state: OperationState) : FlightEvent()

    /** Server verified the complete contiguous archive, independent of live telemetry. */
    @Serializable @SerialName("archive_completed")
    data class ArchiveCompleted(override val at: Long, val sampleCount: Long) : FlightEvent()

    /** The pilot ticked every item of the lab's pre-flight checklist (from the device setup). */
    @Serializable @SerialName("checklist_completed")
    data class ChecklistCompleted(override val at: Long, val items: List<String>, val pilotId: String? = null) : FlightEvent()

    /** The pilot confirmed the flight's photos are on their way to the lab's processing service (e.g. DroneDeploy). */
    @Serializable @SerialName("imagery_handed_off")
    data class ImageryHandedOff(override val at: Long, val service: String, val name: String, val photos: Int) : FlightEvent()

    /** Blender's window closed with samples still on the device; they stay here for export / later import. */
    @Serializable @SerialName("window_expired")
    data class WindowExpired(override val at: Long, val firstUnsentSeq: Long, val message: String) : FlightEvent()
}

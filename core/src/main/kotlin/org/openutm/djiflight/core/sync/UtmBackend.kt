package org.openutm.djiflight.core.sync

import kotlinx.serialization.Serializable
import org.openutm.djiflight.core.altitude.AltitudeCandidate
import org.openutm.djiflight.core.declaration.DeclarationPlan
import org.openutm.djiflight.core.geo.BBox
import org.openutm.djiflight.core.model.TelemetrySample
import org.openutm.djiflight.core.rid.FlightIdentity

/** Operation states the app sets, with Flight Blender's numeric codes. */
@Serializable
enum class OperationState(val blenderCode: Int) {
    ACCEPTED(1),
    ACTIVATED(2),
    ENDED(5),
    WITHDRAWN(6),
    CANCELLED(7),
}

/** Outcome of one backend call, already classified for the sync engine. */
sealed interface BackendResult<out T> {
    data class Ok<T>(val value: T) : BackendResult<T>

    /** The server refused the request; retrying the same request won't help. */
    data class Rejected(val status: Int, val message: String) : BackendResult<Nothing>

    /** Telemetry refused because the declared window is over (Blender: "does not match any current operation"). */
    data class WindowClosed(val message: String) : BackendResult<Nothing>

    /** Telemetry refused because the operation isn't Activated. */
    data class NotActivated(val message: String) : BackendResult<Nothing>

    data class Unauthorized(val message: String) : BackendResult<Nothing>

    /** No usable answer (offline, timeout, 5xx). Safe to retry later. */
    data class Transient(val message: String) : BackendResult<Nothing>
}

/**
 * The only way the app talks to a UTM service. Kept narrow so a connector or UTM-adapter backend
 * could be added beside [org.openutm.djiflight.core.blender.BlenderBackend] (docs/12, decision 2).
 */
interface UtmBackend {
    suspend fun declare(plan: DeclarationPlan): BackendResult<String>

    /** The id of an earlier declaration made with [clientRef], or null if there is none. */
    suspend fun findDeclaration(clientRef: String, startMs: Long, endMs: Long): BackendResult<String?>

    suspend fun setState(declarationId: String, state: OperationState): BackendResult<Unit>

    /** Samples are in seq order and belong to one flight. */
    suspend fun sendTelemetry(
        flight: FlightIdentity,
        samples: List<TelemetrySample>,
        reference: AltitudeCandidate,
    ): BackendResult<Unit>

    /** Restriction zones as the server's raw JSON, for [org.openutm.djiflight.core.zones.ZoneIndex]. */
    suspend fun zones(area: BBox, startMs: Long, endMs: Long): BackendResult<String>
}

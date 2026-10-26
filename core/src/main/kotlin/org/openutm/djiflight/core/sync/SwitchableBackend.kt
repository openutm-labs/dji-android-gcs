package org.openutm.djiflight.core.sync

import org.openutm.djiflight.core.altitude.AltitudeCandidate
import org.openutm.djiflight.core.declaration.DeclarationPlan
import org.openutm.djiflight.core.geo.BBox
import org.openutm.djiflight.core.model.TelemetrySample
import org.openutm.djiflight.core.rid.FlightIdentity

/**
 * The backend the app holds for its whole life; the real one is swapped in when the device is
 * provisioned (or re-provisioned) without restarting the flight service. Unprovisioned calls are
 * transient failures, so recorded flights simply wait.
 */
class SwitchableBackend(@Volatile var target: UtmBackend? = null) : UtmBackend {
    override val supportsArchive: Boolean get() = target?.supportsArchive ?: false
    override suspend fun sendArchive(batch: kotlinx.serialization.json.JsonObject) = target?.sendArchive(batch) ?: none()
    override val serverId: String? get() = target?.serverId
    override fun snapshot(): UtmBackend = target?.snapshot() ?: Unconfigured

    private object Unconfigured : UtmBackend by SwitchableBackend() {
        override fun snapshot(): UtmBackend = this
    }

    private fun none() = BackendResult.Transient("device not provisioned")

    override suspend fun declare(plan: DeclarationPlan) = target?.declare(plan) ?: none()
    override suspend fun findDeclaration(clientRef: String, startMs: Long, endMs: Long) = target?.findDeclaration(clientRef, startMs, endMs) ?: none()
    override suspend fun setState(declarationId: String, state: OperationState) = target?.setState(declarationId, state) ?: none()
    override suspend fun sendTelemetry(flight: FlightIdentity, samples: List<TelemetrySample>, reference: AltitudeCandidate) =
        target?.sendTelemetry(flight, samples, reference) ?: none()
    override suspend fun zones(area: BBox, startMs: Long, endMs: Long) = target?.zones(area, startMs, endMs) ?: none()
}

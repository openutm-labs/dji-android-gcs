package org.openutm.djiflight.core

import org.openutm.djiflight.core.adapter.DroneState
import org.openutm.djiflight.core.altitude.AltitudeSource
import org.openutm.djiflight.core.declaration.GroundEstimate
import org.openutm.djiflight.core.geo.Geo
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.model.FlightPlan
import org.openutm.djiflight.core.model.OperationKind
import java.util.UUID

object TestData {
    /** Nairobi, roughly where Kenya Flying Labs works. */
    val NAIROBI = LatLng(-1.2921, 36.8219)

    fun plan(
        nowMs: Long,
        center: LatLng = NAIROBI,
        groundEllipsoidM: Double = 1661.0 - 16.06,
        startInMs: Long = 5_000,
        durationMs: Long = 2 * 3_600_000L,
        flightId: String = UUID.randomUUID().toString(),
    ) = FlightPlan(
        flightId = flightId,
        kind = OperationKind.MANUAL,
        area = Geo.circle(center, 500.0),
        ground = GroundEstimate(groundEllipsoidM, 5.0, AltitudeSource.DEVICE_GNSS),
        plannedMaxHeightM = 100.0,
        plannedStartMs = nowMs + startInMs,
        plannedEndMs = nowMs + startInMs + durationMs,
        aircraftSerial = "TESTP4PV2-0001",
        registrationId = "KE-TEST-0001",
        pilotId = "pilot-1",
        lab = "Kenya Flying Labs (test)",
        operatorId = "OPR-KE-TEST",
        description = "dji-flight core test",
    )

    fun state(
        timeMs: Long,
        lat: Double = NAIROBI.lat,
        lng: Double = NAIROBI.lng,
        h: Double = 0.0,
        flying: Boolean = false,
        motors: Boolean = flying,
        vn: Double = 0.0,
        ve: Double = 0.0,
        vd: Double = 0.0,
        mode: String? = "P-GPS",
        takeoffMslM: Double? = 1661.0,
    ) = DroneState(
        timeMs = timeMs, lat = lat, lng = lng, heightAboveTakeoffM = h,
        velocityNorthMps = vn, velocityEastMps = ve, velocityDownMps = vd, yawDeg = 90.0,
        isFlying = flying, motorsOn = motors, gpsLevel = 5, satellites = 14, flightMode = mode,
        home = NAIROBI, takeoffMslM = takeoffMslM,
    )
}

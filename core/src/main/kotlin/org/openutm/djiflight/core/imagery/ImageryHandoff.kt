package org.openutm.djiflight.core.imagery

import org.openutm.djiflight.core.PhotoStats
import org.openutm.djiflight.core.provisioning.ImageryService
import org.openutm.djiflight.core.session.FlightSnapshot
import org.openutm.djiflight.core.time.Rfc3339
import java.util.TimeZone

/**
 * Getting a flight's photos to the lab's processing service (docs/13, Phase A): the photos stay on
 * the aircraft's SD card and go up from a computer, as most DroneDeploy surveys already do. The app
 * names the map so it can be traced back to the flight and its declaration, and says which photos.
 */
object ImageryHandoff {
    /**
     * Site (or lab), the planned date on the pilot's clock and the flight's short id, e.g.
     * "Ngong forest 2026-10-17 c497ad07". The local date, not UTC's: a 07:00 flight in Manila is
     * still the day before in UTC, and the pilot looks for the map by the day they flew.
     */
    fun mapName(f: FlightSnapshot, zone: TimeZone = TimeZone.getDefault()): String {
        val p = f.plan ?: return f.flightId.take(8)
        val place = p.siteName?.trim()?.takeIf { it.isNotEmpty() } ?: p.lab.trim()
        val local = p.plannedStartMs + zone.getOffset(p.plannedStartMs)
        return "$place ${Rfc3339.format(local).substring(0, 10)} ${f.flightId.take(8)}"
    }

    /** Finished, took photos, not handed off yet. */
    fun pending(f: FlightSnapshot, photos: PhotoStats): Boolean =
        f.everFlown && f.recordingClosed && f.imageryHandoff == null && photos.count > 0

    /** What to do, in the pilot's words; [time] formats an epoch ms for the pilot's clock. */
    fun steps(service: ImageryService, name: String, photos: PhotoStats, time: (Long) -> String): String {
        val span = if (photos.firstMs != null && photos.lastMs != null) ", taken ${time(photos.firstMs)}–${time(photos.lastMs)}" else ""
        val where = service.project?.let { " → $it" } ?: ""
        return buildString {
            appendLine("${photos.count} photos$span.")
            appendLine("1. Keep them on the aircraft's SD card until you're at a computer; don't format it.")
            appendLine("2. Open ${service.service}$where and create a map named:")
            appendLine("   $name")
            appendLine("3. Upload these photos from the card's DCIM folder (several flights can go in one upload).")
            append("4. Tap Done here.")
        }
    }
}

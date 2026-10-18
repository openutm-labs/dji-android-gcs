package org.openutm.djiflight.app

import android.content.Context
import org.openutm.djiflight.core.advisory.Advisory
import org.openutm.djiflight.core.altitude.AltitudeSource
import org.openutm.djiflight.core.session.Phase

/**
 * A warning in the device's language: the app's string for its key (values-<lang>/strings_advisories.xml),
 * filled with its arguments; the core's English when there is none or the arguments don't fit.
 */
fun Context.advisoryText(a: Advisory): String {
    val id = advisoryString(a.key) ?: return a.message
    return runCatching { getString(id, *a.args.toTypedArray()) }.getOrDefault(a.message)
}

/** Where a ground or take-off height came from, in the pilot's words. */
fun Context.altitudeSourceText(s: AltitudeSource): String = getString(
    when (s) {
        AltitudeSource.RTK -> R.string.plan_source_rtk
        AltitudeSource.DEVICE_GNSS -> R.string.plan_source_gnss
        AltitudeSource.DJI_TAKEOFF_MSL -> R.string.plan_source_aircraft
        AltitudeSource.SITE_ESTIMATE -> R.string.plan_source_site
        AltitudeSource.TERRAIN_MODEL -> R.string.plan_source_terrain
    },
)

/** A flight's phase, as the notification names it. */
fun Context.phaseText(p: Phase): String = getString(
    when (p) {
        Phase.PLANNED -> R.string.bg_phase_planned
        Phase.DECLARED -> R.string.bg_phase_declared
        Phase.ACTIVE -> R.string.bg_phase_active
        Phase.LANDED -> R.string.bg_phase_landed
        Phase.CLOSED -> R.string.bg_phase_closed
        Phase.EXPIRED_UNSYNCED -> R.string.bg_phase_expired_unsynced
        Phase.UNDECLARED -> R.string.bg_phase_undeclared
        Phase.CANCELLED -> R.string.bg_phase_cancelled
    },
)

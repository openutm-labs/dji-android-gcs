package org.openutm.djiflight.app

import android.content.Context
import org.openutm.djiflight.core.zones.ZoneSummary

/** Shared by the Plan summary and its declaration confirmation. */
fun Context.zoneDescription(zone: ZoneSummary): String {
    val kinds = zone.categories.map { category ->
        getString(when (category) {
            "AUTHORIZATION" -> R.string.plan_zone_authorization
            "RESTRICTED" -> R.string.plan_zone_restricted
            "WARNING" -> R.string.plan_zone_warning
            "ENHANCED_WARNING" -> R.string.plan_zone_enhanced_warning
            "ALTITUDE" -> R.string.plan_zone_altitude
            else -> R.string.plan_zone_unknown
        })
    }.distinct().joinToString(", ")
    return getString(R.string.plan_zone_description, zone.name, listOf(zone.source, kinds).filter { it.isNotBlank() }.joinToString(" · "))
}

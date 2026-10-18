package org.openutm.djiflight.core.time

import java.util.Locale

/** Wall-clock source. Times are epoch milliseconds, UTC. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/** A clock tests move by hand. */
class ManualClock(var now: Long) : Clock {
    override fun nowMs(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}

/**
 * RFC 3339 UTC timestamps with milliseconds, e.g. `2026-10-16T07:33:02.785Z`.
 *
 * Written without java.time, which Android only has from API 26 (the Smart Controller runs 7.x).
 */
object Rfc3339 {
    private const val DAY_MS = 86_400_000L

    fun format(epochMs: Long): String {
        val days = epochMs.floorDiv(DAY_MS)
        val msOfDay = epochMs.mod(DAY_MS)
        val (y, m, d) = civilFromDays(days)
        return String.format(
            Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ",
            y, m, d, msOfDay / 3_600_000, (msOfDay / 60_000) % 60, (msOfDay / 1000) % 60, msOfDay % 1000,
        )
    }

    /** `YYYY-MM-DD`, the date format Blender's list endpoints take. */
    fun date(epochMs: Long): String = format(epochMs).substring(0, 10)

    /** Howard Hinnant's civil_from_days. */
    private fun civilFromDays(daysSinceEpoch: Long): Triple<Long, Long, Long> {
        val z = daysSinceEpoch + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return Triple(y, m, d)
    }
}

/**
 * [base] corrected to GNSS time. Field controllers can sit offline for days and drift; declaration
 * windows and sample times must use the same, correct clock. Updated from device GNSS fixes.
 */
class CorrectedClock(private val base: Clock = Clock.SYSTEM) : Clock {
    @Volatile
    var offsetMs: Long = 0

    override fun nowMs(): Long = base.nowMs() + offsetMs
}

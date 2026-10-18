package org.openutm.djiflight.core.altitude

import kotlinx.serialization.Serializable
import org.openutm.djiflight.core.geo.LatLng
import kotlin.math.abs

/** Where a take-off (ground) height came from. */
@Serializable
enum class AltitudeSource {
    /** Aircraft RTK, fixed solution: ellipsoid height directly. */
    RTK,

    /** The phone's or controller's own GNSS. Android reports height above the WGS-84 ellipsoid. */
    DEVICE_GNSS,

    /** DJI `takeoffLocationAltitude` ("relative to sea level"; datum unverified) + EGM96. */
    DJI_TAKEOFF_MSL,

    /** Estimated when planning away from the site: a saved site, or an elevation the pilot entered. */
    SITE_ESTIMATE,

    /** The lab's terrain file (a surface model, e.g. Copernicus GLO-30) + EGM96, when planning away from the site. */
    TERRAIN_MODEL,
}

/** One candidate height of the take-off point above the WGS-84 ellipsoid, with a 1-sigma uncertainty. */
@Serializable
data class AltitudeCandidate(val source: AltitudeSource, val ellipsoidM: Double, val sigmaM: Double)

/** The take-off reference a flight's altitudes are computed from, and the evidence behind it. */
@Serializable
data class TakeoffReference(
    val chosen: AltitudeCandidate,
    val candidates: List<AltitudeCandidate>,
    /** |device GNSS - DJI|, when both exist. */
    val disagreementM: Double? = null,
    /** Device GNSS and DJI disagree by more than [TakeoffReferenceResolver.DISAGREEMENT_LIMIT_M]; show both to the pilot. */
    val pilotCheckNeeded: Boolean = false,
    /** The pilot chose [chosen]'s source after a disagreement; later resolutions keep that source. */
    val pilotChosen: Boolean = false,
)

object TakeoffReferenceResolver {
    const val DISAGREEMENT_LIMIT_M = 15.0

    /** Default 1-sigma for DJI's value until a field test settles its datum and accuracy. */
    const val DJI_SIGMA_M = 10.0

    /** RTK (fixed) wins; otherwise device GNSS, cross-checked against DJI; DJI alone is the last resort. */
    fun resolve(rtk: AltitudeCandidate?, device: AltitudeCandidate?, dji: AltitudeCandidate?): TakeoffReference? {
        val candidates = listOfNotNull(rtk, device, dji)
        val chosen = rtk ?: device ?: dji ?: return null
        val disagreement = if (device != null && dji != null) abs(device.ellipsoidM - dji.ellipsoidM) else null
        return TakeoffReference(
            chosen = chosen,
            candidates = candidates,
            disagreementM = disagreement,
            pilotCheckNeeded = rtk == null && disagreement != null && disagreement > DISAGREEMENT_LIMIT_M,
        )
    }

    /** The pilot's pick after a disagreement: the same evidence, [source] chosen, nothing left to check. */
    fun choose(ref: TakeoffReference, source: AltitudeSource): TakeoffReference? {
        val c = ref.candidates.firstOrNull { it.source == source } ?: return null
        return ref.copy(chosen = c, pilotCheckNeeded = false, pilotChosen = true)
    }

    /** A new resolution that keeps the pilot's earlier pick, if that source is still a candidate. */
    fun keepChoice(previous: TakeoffReference?, fresh: TakeoffReference): TakeoffReference =
        previous?.takeIf { it.pilotChosen }?.let { choose(fresh, it.chosen.source) } ?: fresh

    fun djiCandidate(takeoffMslM: Double?, at: LatLng, grid: Egm96Grid, sigmaM: Double = DJI_SIGMA_M): AltitudeCandidate? {
        if (takeoffMslM == null || !takeoffMslM.isFinite() || !at.isValid) return null
        return AltitudeCandidate(AltitudeSource.DJI_TAKEOFF_MSL, grid.mslToEllipsoid(at.lat, at.lng, takeoffMslM), sigmaM)
    }
}

/** One fix from the device's own GNSS receiver (Android `Location`). */
@Serializable
data class GnssFix(
    /** GNSS time of the fix (`Location.getTime()`), epoch ms UTC. */
    val gnssTimeMs: Long,
    /** Device wall clock when the fix arrived (`System.currentTimeMillis()`). */
    val deviceTimeMs: Long,
    val lat: Double,
    val lng: Double,
    /** `Location.getAltitude()`: metres above the WGS-84 ellipsoid. */
    val ellipsoidM: Double?,
    /** `Location.getVerticalAccuracyMeters()` (API 26+), null if unknown. */
    val verticalAccuracyM: Double? = null,
)

/**
 * Averages device fixes taken at the take-off point.
 *
 * Phone GNSS height errors are strongly correlated over tens of seconds, so averaging removes noise
 * but not bias: the reported sigma is the median of the fixes' own accuracies, not sigma/sqrt(n).
 */
class GnssAverager(private val windowMs: Long = 20_000, private val minFixes: Int = 5, private val unknownSigmaM: Double = 15.0) {
    private val fixes = ArrayDeque<GnssFix>()

    fun add(fix: GnssFix) {
        if (fix.ellipsoidM == null || !fix.ellipsoidM.isFinite()) return
        fixes.addLast(fix)
        while (fixes.isNotEmpty() && fix.gnssTimeMs - fixes.first().gnssTimeMs > windowMs) fixes.removeFirst()
    }

    fun candidate(): AltitudeCandidate? {
        if (fixes.size < minFixes) return null
        val sigmas = fixes.map { it.verticalAccuracyM?.takeIf { s -> s > 0 } ?: unknownSigmaM }
        val weights = sigmas.map { 1.0 / (it * it) }
        val mean = fixes.zip(weights).sumOf { (f, w) -> f.ellipsoidM!! * w } / weights.sum()
        return AltitudeCandidate(AltitudeSource.DEVICE_GNSS, mean, sigmas.sorted()[sigmas.size / 2])
    }

    fun clear() = fixes.clear()
}

/**
 * Some controllers' GNSS reports height above sea level where Android promises the WGS-84 ellipsoid
 * (a vendor bug; docs/12, Open items). Where the geoid sits far from the ellipsoid (Europe about
 * +45 m, India -60 to -100 m), an independent sea-level height of the same ground, from the terrain
 * model or a survey, tells the two apart. Near the ellipsoid (East Africa, about -15 m) it can't.
 */
object GnssDatumCheck {
    enum class Verdict { ELLIPSOID, LIKELY_SEA_LEVEL, UNCLEAR }

    /**
     * [deviceM]: the device's reading at the ground; [groundMslM]: the same ground above sea level;
     * [geoidN]: ellipsoid minus sea level here (EGM96); [toleranceM]: the two readings' combined error.
     */
    fun check(deviceM: Double, groundMslM: Double, geoidN: Double, toleranceM: Double): Verdict {
        if (abs(geoidN) < 2 * toleranceM) return Verdict.UNCLEAR
        val asEllipsoid = abs(deviceM - (groundMslM + geoidN))
        val asSeaLevel = abs(deviceM - groundMslM)
        return when {
            asEllipsoid <= toleranceM && asSeaLevel > toleranceM -> Verdict.ELLIPSOID
            asSeaLevel <= toleranceM && asEllipsoid > toleranceM -> Verdict.LIKELY_SEA_LEVEL
            else -> Verdict.UNCLEAR
        }
    }
}

package org.openutm.djiflight.core.adapter

/** Both SDKs report these sensor states, including interference that hasError alone misses. */
object CompassHealth {
    fun status(states: List<String>, hasError: Boolean?, calibrating: Boolean = false): SensorStatus {
        if (calibrating || "CALIBRATING" in states) return SensorStatus(Health.WARNING, "Calibrating")
        if (hasError == true || states.any { it in errors }) return SensorStatus(Health.ERROR, "Abnormal")
        if (states.any { it == "WEAK_MODULUS" || it == "SERIOUS_MODULUS" }) return SensorStatus(Health.WARNING, "Magnetic interference")
        if ("NORMAL_MODULUS" in states) return SensorStatus(Health.OK, "Normal")
        return SensorStatus()
    }

    private val errors = setOf("DISCONNECTED", "UNCALIBRATED", "DATA_EXCEPTION", "CALIBRATION_FAILED", "DIRECTION_EXCEPTION")
}

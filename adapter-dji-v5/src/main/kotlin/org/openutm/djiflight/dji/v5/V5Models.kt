package org.openutm.djiflight.dji.v5

import dji.sdk.keyvalue.value.camera.CameraType
import dji.sdk.keyvalue.value.product.ProductType
import org.openutm.djiflight.core.mission.CameraModel
import org.openutm.djiflight.core.mission.WpmlTarget

/** What we can fly with one aircraft + main camera combination. */
data class V5Aircraft(
    val model: String,
    /** Non-null = mapping missions are offered: the KMZ target and the survey camera. */
    val wpml: WpmlTarget?,
    val camera: CameraModel?,
)

/**
 * MSDK v5 product and camera types → names, WPML targets and survey cameras.
 *
 * Missions are offered only where both the WPML enums and the camera geometry are known:
 * the Mavic 3E, 3T and 3M, and the Matrice 400 carrying a Zenmuse P1, L2, H30 or H30T. Every other
 * aircraft is flown manually and still declared and tracked.
 */
object V5Models {
    fun describe(product: ProductType?, camera: CameraType?, position: Int): V5Aircraft = when (product) {
        ProductType.DJI_MAVIC_3_ENTERPRISE_SERIES -> when (camera) {
            CameraType.M3E -> V5Aircraft("Mavic 3 Enterprise", WpmlTarget.MAVIC_3E, CameraModel.MAVIC_3E)
            CameraType.M3T -> V5Aircraft("Mavic 3 Thermal", WpmlTarget.MAVIC_3T, CameraModel.MAVIC_3T)
            CameraType.M3M -> V5Aircraft("Mavic 3 Multispectral", WpmlTarget.MAVIC_3M, CameraModel.MAVIC_3M)
            else -> V5Aircraft("Mavic 3 Enterprise series", null, null)
        }
        ProductType.DJI_MATRICE_400 -> when (camera) {
            CameraType.ZENMUSE_P1 -> V5Aircraft(
                "Matrice 400 + Zenmuse P1",
                WpmlTarget(WpmlTarget.MATRICE_400, 0, WpmlTarget.PAYLOAD_P1, payloadPosition = position, imageFormat = "visible"),
                CameraModel.ZENMUSE_P1_35,
            )
            CameraType.ZENMUSE_L2 -> V5Aircraft(
                "Matrice 400 + Zenmuse L2",
                WpmlTarget(WpmlTarget.MATRICE_400, 0, WpmlTarget.PAYLOAD_L2, payloadPosition = position, imageFormat = "visible"),
                CameraModel.ZENMUSE_L2,
            )
            CameraType.ZENMUSE_H30, CameraType.ZENMUSE_H30T -> {
                val t = camera == CameraType.ZENMUSE_H30T
                V5Aircraft(
                    "Matrice 400 + Zenmuse H30${if (t) "T" else ""}",
                    WpmlTarget(WpmlTarget.MATRICE_400, 0, if (t) WpmlTarget.PAYLOAD_H30T else WpmlTarget.PAYLOAD_H30, payloadPosition = position, imageFormat = "wide"),
                    CameraModel.ZENMUSE_H30_WIDE,
                )
            }
            null -> V5Aircraft("Matrice 400", null, null)
            else -> V5Aircraft("Matrice 400 + ${humanize(camera.name)}", null, null)
        }
        ProductType.M30_SERIES -> V5Aircraft("Matrice 30 series", null, null)
        ProductType.M300_RTK -> V5Aircraft("Matrice 300 RTK", null, null)
        ProductType.M350_RTK -> V5Aircraft("Matrice 350 RTK", null, null)
        ProductType.DJI_MATRICE_4_SERIES -> V5Aircraft("Matrice 4 series", null, null)
        ProductType.DJI_MATRICE_4D_SERIES -> V5Aircraft("Matrice 4D series", null, null)
        ProductType.DJI_MINI_3 -> V5Aircraft("Mini 3", null, null)
        ProductType.DJI_MINI_3_PRO -> V5Aircraft("Mini 3 Pro", null, null)
        ProductType.DJI_MINI_4_PRO -> V5Aircraft("Mini 4 Pro", null, null)
        null -> V5Aircraft("DJI aircraft", null, null)
        else -> V5Aircraft(humanize(product.name), null, null)
    }

    /** `ZENMUSE_H30T` → "Zenmuse H30T", `DJI_MAVIC_3` → "Mavic 3". */
    fun humanize(enumName: String): String = enumName.removePrefix("DJI_").split('_').joinToString(" ") { part ->
        if (part.any { it.isDigit() } || part.length <= 2) part else part.lowercase().replaceFirstChar { it.uppercase() }
    }
}

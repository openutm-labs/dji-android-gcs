package org.openutm.djiflight.core.adapter

/** SDK model enum names. A flight assistant can exist for positioning without obstacle sensing. */
object ObstacleSensing {
    fun supportsV4(model: String?): Boolean = model in models
    fun supportsV5(model: String?): Boolean = model in v5Models

    private val v5Models = setOf(
        "DJI_MAVIC_3_ENTERPRISE_SERIES", "M30_SERIES", "M300_RTK", "M350_RTK", "DJI_MATRICE_400",
        "DJI_MATRICE_4_SERIES", "DJI_MATRICE_4D_SERIES", "DJI_MINI_3_PRO", "DJI_MINI_4_PRO",
    )

    private val models = setOf(
        "PHANTOM_4", "PHANTOM_4_PRO", "PHANTOM_4_ADVANCED", "PHANTOM_4_PRO_V2", "PHANTOM_4_RTK", "P_4_MULTISPECTRAL",
        "INSPIRE_2", "MAVIC_PRO", "Spark", "MAVIC_AIR", "MAVIC_AIR_2", "DJI_AIR_2S",
        "MAVIC_2", "MAVIC_2_ZOOM", "MAVIC_2_PRO", "MAVIC_2_ENTERPRISE", "MAVIC_2_ENTERPRISE_DUAL", "MAVIC_2_ENTERPRISE_ADVANCED",
        "MATRICE_200", "MATRICE_210", "MATRICE_210_RTK", "MATRICE_200_V2", "MATRICE_210_V2", "MATRICE_210_RTK_V2",
        "MATRICE_300_RTK", "MATRICE_350_RTK",
    )
}

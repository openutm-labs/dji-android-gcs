package org.openutm.djiflight.app

import android.content.Context
import android.content.Intent
import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import org.openutm.djiflight.core.adapter.DroneAdapter
import org.openutm.djiflight.core.altitude.GnssFix
import org.openutm.djiflight.core.mission.CameraModel
import org.openutm.djiflight.core.mission.MissionLimits

/**
 * What each APK supplies to the shared screens: `app-v4` (flavours `dji` and `sim`) and `app-v5`.
 * The screens, the core and everything below never see an SDK type.
 */
interface Platform {
    /** Shown in logs and the Fly screen's status, e.g. "dji-v4". */
    val label: String
    val versionCode: Int
    val versionName: String
    /** Debug build: accepts the dev token and shows "Use local dev stack". */
    val debug: Boolean
    /** Debug builds only: the local Blender for "Use local dev stack". */
    val devBlenderUrl: String

    /** Used to plan and split a mission before an aircraft has connected and reported its own. */
    val defaultMissionLimits: MissionLimits
    /** Survey planning camera when the connected aircraft doesn't report one. */
    val defaultCamera: CameraModel

    fun createAdapter(context: Context, scope: CoroutineScope): DroneAdapter

    /** Simulated pilot positions instead of the device's GNSS; null = use the device's own. */
    fun deviceFixes(adapter: DroneAdapter): Flow<GnssFix>? = null

    /** One extra status line for the Fly screen (SDK registration, replay state). */
    fun extraStatus(adapter: DroneAdapter): String? = null

    fun video(context: Context): VideoPreview

    /**
     * The vendor's own status widgets for the Fly screen's top bar, as pilots know them from DJI's
     * apps (v4 `dji`: DJI's UI Library). Null: the app's own aircraft pill and chips.
     */
    fun statusWidgets(context: Context): StatusWidgets? = null

    /**
     * The vendor's own aircraft settings and calibration screens, offered in Pre-flight next to the
     * app's own (v5: DJI Pilot 2's settings panel, from DJI's UI Library). Null: none in this build.
     */
    fun vendorSettings(context: Context): Intent? = null
}

/** [leading] replaces the aircraft status pill (left), [trailing] the GNSS and battery chips (right). */
class StatusWidgets(val leading: View, val trailing: View)

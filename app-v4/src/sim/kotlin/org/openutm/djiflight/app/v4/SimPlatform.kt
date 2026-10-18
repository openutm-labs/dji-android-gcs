package org.openutm.djiflight.app.v4

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import org.openutm.djiflight.app.FlightApp
import org.openutm.djiflight.app.NoVideo
import org.openutm.djiflight.app.Platform
import org.openutm.djiflight.app.VideoPreview
import org.openutm.djiflight.core.adapter.DroneAdapter
import org.openutm.djiflight.core.altitude.GnssFix
import org.openutm.djiflight.core.mission.CameraModel
import org.openutm.djiflight.core.mission.MissionLimits
import org.openutm.djiflight.fake.ReplayDroneAdapter
import org.openutm.djiflight.fake.Track

/** `sim` flavour: replays a recorded track; no DJI code in this APK. */
object SimPlatform : Platform {
    private const val TRACK = "sitl-auto-mission.jsonl"

    /** On the ground this long before the recorded take-off: time to plan and declare. */
    const val GROUND_LEAD_MS = 60_000L

    override val label = "sim"
    override val versionCode = BuildConfig.VERSION_CODE
    override val versionName: String = BuildConfig.VERSION_NAME
    override val debug = BuildConfig.DEBUG
    override val devBlenderUrl: String = BuildConfig.DEV_BLENDER_URL
    override val defaultMissionLimits = MissionLimits.WAYPOINT_V1
    override val defaultCamera = CameraModel.P4P_V2

    /** One waypoint a second, so a mission's Pause, Stop and Resume can be tried on an emulator. */
    private const val MISSION_STEP_MS = 1_000L

    override fun createAdapter(context: Context, scope: CoroutineScope): DroneAdapter =
        ReplayDroneAdapter(Track.bundled(TRACK).withGroundLead(GROUND_LEAD_MS), scope, missionStepMs = MISSION_STEP_MS)

    /** The emulator's location is meaningless here: put the "pilot" at the track's take-off point. */
    override fun deviceFixes(adapter: DroneAdapter): Flow<GnssFix>? = (adapter as ReplayDroneAdapter).simulatedDeviceFixes()

    override fun extraStatus(adapter: DroneAdapter): String? = "replaying $TRACK" + if ((adapter as ReplayDroneAdapter).finished) " (finished)" else ""

    override fun video(context: Context): VideoPreview = object : VideoPreview by NoVideo {
        override val labelRes = org.openutm.djiflight.app.R.string.fly_video_sim
    }
}

class SimFlightApp : FlightApp() {
    override fun platform(): Platform = SimPlatform
}

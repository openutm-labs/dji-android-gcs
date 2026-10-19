package org.openutm.djiflight.app.v4

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dji.common.Stick
import dji.common.flightcontroller.simulator.InitializationData
import dji.common.model.LocationCoordinate2D
import dji.keysdk.FlightControllerKey
import dji.keysdk.KeyManager
import dji.keysdk.RemoteControllerKey
import dji.sdk.products.Aircraft
import dji.sdk.sdkmanager.DJISDKManager
import org.openutm.djiflight.app.FlightApp
import org.openutm.djiflight.core.provisioning.AuthConfig

/** No takeoff, motor, Virtual Stick or camera writes. Simulator changes require an on-screen action. */
class Mini2BenchActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var propsRemoved: CheckBox
    private lateinit var start: Button
    private lateinit var stop: Button
    private var pending = false
    private var allowSimulator = false
    private var outcome = "No simulator change requested"
    private var lastReport = ""
    private fun aircraft() = (DJISDKManager.getInstance().product as? Aircraft)?.takeIf { it.isConnected }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DEBUG)
        allowSimulator = intent.getBooleanExtra("allow_simulator",false)
        (application as FlightApp).graph.adapter.start()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        body.addView(TextView(this).apply {
            text = if (allowSimulator) "Mini 2 SDK simulator bench\n\nKeep propellers removed throughout simulator testing. This screen does not take off, arm motors or fly a mission. Simulator acknowledgement is separate from flight qualification. Starting simulation requires local development setup; stop it before changing setup."
                else "Mini 2 read-only bench\n\nKeep the aircraft grounded and the controller sticks neutral. This screen only reads status. Simulator controls are hidden. It sends no flight, motor, camera or settings commands."
            textSize = 18f
        })
        status = TextView(this).apply { textSize = 15f; setPadding(0,24,0,24) }
        body.addView(status)
        propsRemoved = CheckBox(this).apply { text = "I have physically removed all propellers" }
        body.addView(propsRemoved)
        start = Button(this).apply { text = "Start DJI SDK simulator"; setOnClickListener { changeSimulator(true) } }
        stop = Button(this).apply { text = "Stop DJI SDK simulator"; setOnClickListener { changeSimulator(false) } }
        body.addView(start); body.addView(stop)
        if (!allowSimulator) {
            propsRemoved.visibility = android.view.View.GONE
            start.visibility = android.view.View.GONE
            stop.visibility = android.view.View.GONE
            outcome = "Read-only bench: simulator changes are disabled for this session"
        }
        body.addView(Button(this).apply { text = "Return to app"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(body) })
    }

    private val poll = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this,1_000) }
    }
    override fun onResume() { super.onResume(); handler.post(poll) }
    override fun onPause() { handler.removeCallbacks(poll); super.onPause() }

    private fun refresh() {
        val a = aircraft()
        val fc = a?.flightController
        val keys = KeyManager.getInstance()
        val left = keys?.getValue(RemoteControllerKey.create(RemoteControllerKey.LEFT_STICK_VALUE)) as? Stick
        val right = keys?.getValue(RemoteControllerKey.create(RemoteControllerKey.RIGHT_STICK_VALUE)) as? Stick
        fun stick(s: Stick?) = s?.let { "${it.horizontalPosition}, ${it.verticalPosition}" } ?: "unavailable"
        val graph = (application as FlightApp).graph
        val state = fc?.state
        val report = buildString {
            appendLine("Model: ${a?.model?.displayName ?: "disconnected"}")
            appendLine("Firmware: ${a?.firmwarePackageVersion ?: "unknown"}")
            appendLine("App: ${BuildConfig.VERSION_NAME}; experimental executor: ${BuildConfig.MINI2_SURVEY_EXPERIMENTAL}")
            appendLine("Mapping camera: ${graph.adapter.capabilities.value?.camera?.name ?: "unknown"}")
            appendLine("Mission support: ${graph.adapter.capabilities.value?.missionSupport ?: "unknown"}")
            appendLine("Flying: ${state?.isFlying}; motors: ${state?.areMotorsOn()}")
            appendLine("GPS: ${state?.gpsSignalLevel}; SDK simulator active: ${fc?.simulator?.isSimulatorActive}")
            appendLine("Local development setup for simulation: ${localDevelopment()}")
            appendLine("Left stick: ${stick(left)}; right stick: ${stick(right)}")
            appendLine("Virtual Stick enabled key: ${keys?.getValue(FlightControllerKey.create(FlightControllerKey.VIRTUAL_STICK_CONTROL_MODE_ENABLED)) ?: "unavailable"}")
            appendLine("Virtual Stick available: ${fc?.isVirtualStickControlModeAvailable}")
            appendLine("RTH height: ${keys?.getValue(FlightControllerKey.create(FlightControllerKey.GO_HOME_HEIGHT_IN_METERS)) ?: "unavailable"}")
            appendLine("RC-loss action: ${keys?.getValue(FlightControllerKey.create(FlightControllerKey.CONNECTION_FAIL_SAFE_BEHAVIOR)) ?: "unavailable"}")
            appendLine(outcome)
        }
        status.text = report
        if (report != lastReport) { Log.i("OpenUTMFlight", "mini2-bench $report"); lastReport = report }
        val mini = a?.model?.displayName in setOf("DJI Mini 2","Mini 2")
        val grounded = state != null && !state.isFlying && !state.areMotorsOn()
        start.isEnabled = !pending && mini && grounded && localDevelopment() && fc?.simulator != null && !fc.simulator.isSimulatorActive
        stop.isEnabled = !pending && mini && grounded && fc?.simulator?.isSimulatorActive == true
    }

    private fun changeSimulator(enable: Boolean) {
        val a = aircraft() ?: return
        val fc = a.flightController ?: return
        if (!allowSimulator) return
        if (enable && !localDevelopment()) {
            outcome = "Use local development setup first; simulated positions must not reach an operational server"
            refresh(); return
        }
        if (!propsRemoved.isChecked || a.model.displayName !in setOf("DJI Mini 2","Mini 2") || fc.state.isFlying || fc.state.areMotorsOn() || pending) {
            outcome = "Remove propellers and confirm; aircraft must be grounded with motors off"
            refresh(); return
        }
        val simulator = fc.simulator ?: return
        pending = true
        outcome = "Waiting for DJI simulator ${if (enable) "start" else "stop"} acknowledgement"
        refresh()
        val timeout = Runnable {
            outcome = "DJI simulator acknowledgement timed out; inspect active status before continuing"
            // Keep changes disabled until this screen is reopened; a late SDK callback may still arrive.
            refresh()
        }
        handler.postDelayed(timeout,8_000)
        val callback = dji.common.util.CommonCallbacks.CompletionCallback<dji.common.error.DJIError> { error ->
            runOnUiThread {
                handler.removeCallbacks(timeout)
                pending = false
                outcome = if (error != null) "DJI refused simulator change: ${error.description}"
                    else "DJI acknowledged simulator ${if (enable) "start" else "stop"}; verify active status above"
                refresh()
            }
        }
        // Synthetic SDK simulation origin, never an actual flight position or a declaration.
        if (enable) simulator.start(InitializationData.createInstance(LocationCoordinate2D(-1.2797,36.8344),10,12),callback)
        else simulator.stop(callback)
    }

    private fun localDevelopment(): Boolean {
        val p = (application as FlightApp).graph.provisioning.value ?: return false
        return p.auth == AuthConfig.Dev && runCatching { java.net.URI(p.blenderUrl).host in setOf("127.0.0.1","localhost","10.0.2.2","[::1]","::1") }.getOrDefault(false)
    }
}

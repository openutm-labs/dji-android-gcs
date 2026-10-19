package org.openutm.djiflight.test

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

/** Fresh-install release smoke check. Never enters credentials or submits network/flight data. */
@android.annotation.TargetApi(34)
class PanamaSetupSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val mapPreferences = targetContext.getSharedPreferences("map-display", android.content.Context.MODE_PRIVATE)
        val originalMode = mapPreferences.getString("mode", null)
        // Automated UI checks must never request tiles from the public OSM service.
        mapPreferences.edit().putString("mode", "OFFLINE").commit()
        val result = Bundle()
        var resultCode = Activity.RESULT_CANCELED
        try {
            val activity = startActivitySync(Intent().setClassName(targetContext.packageName, "org.openutm.djiflight.app.SetupActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            runOnMainSync {
                activity.setTurnScreenOn(true)
                activity.setShowWhenLocked(true)
                val labels = views(activity.window.decorView).filterIsInstance<TextView>()
                check(labels.any { it.text.toString() == "https://blender.panama.openutm.net" }) { "default server absent" }
                check(labels.none { it.text.toString().contains("OPR-VIB") }) { "personal operator present on fresh install" }
                val label = labels.first { it.text.toString() == "Enter Panama device setup" }
                var button: View = label
                while (!button.isClickable) button = button.parent as View
                check(button.performClick()) { "setup button did not open" }
            }
            waitForIdleSync()
            val connect = waitFor("Connect to Panama")
            check(nodes(uiAutomation.rootInActiveWindow).any { it.isPassword }) { "secret field is not masked" }
            var button = connect
            while (!button.isClickable) button = button.parent ?: error("connect button unavailable")
            check(button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("Enter the pilot")
            uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(targetContext.getExternalFilesDir(null), "panama-setup-smoke.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitForIdleSync()
            uiAutomation.grantRuntimePermission(targetContext.packageName, android.Manifest.permission.CAMERA)
            val scannerMonitor = addMonitor("org.openutm.djiflight.app.ProvisioningQrActivity", null, false)
            runOnMainSync {
                var scan: View = views(activity.window.decorView).filterIsInstance<TextView>()
                    .first { it.text.toString() == "Scan QR code" }
                while (!scan.isClickable) scan = scan.parent as View
                check(scan.performClick()) { "scan button did not open" }
            }
            val scanner = waitForMonitorWithTimeout(scannerMonitor, 8_000) ?: error("QR scanner did not start")
            waitForIdleSync()
            runOnMainSync {
                // Both outer and nested views must be JourneyApps views, never DJI's same-named layouts.
                val classes = views(scanner.window.decorView).map { it.javaClass.name }
                for (view in listOf("DecoratedBarcodeView", "BarcodeView", "ViewfinderView")) {
                    check("com.journeyapps.barcodescanner.$view" in classes) { "QR view absent or replaced: $view" }
                }
                check(classes.none { it.startsWith("com.dji.scan.qr.") }) { "DJI scanner layout collision" }
            }
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitForIdleSync()
            check(scanner.isFinishing || scanner.isDestroyed) { "QR cancellation did not close scanner" }
            waitFor("Scan QR code")
            removeMonitor(scannerMonitor)
            checkInsetPanel()
            result.putString("checks", "Panama default; empty personal identity; setup form opens; masked secret; missing pilot rejected before connection; actual QR tile launches JourneyApps outer and nested views; cancel returns to setup; preflight opened from camera inset expands to full stage without replacing camera tree")
            result.putString("result", "PASS")
            resultCode = Activity.RESULT_OK
        } catch (e: Throwable) {
            uiAutomation.takeScreenshot()?.let { bitmap ->
                java.io.File(targetContext.getExternalFilesDir(null), "panama-smoke-failure.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            result.putString("result", "FAIL")
            result.putString("error", e.javaClass.simpleName + ": " + e.message)
        } finally {
            mapPreferences.edit().apply { if (originalMode == null) remove("mode") else putString("mode", originalMode) }.commit()
        }
        finish(resultCode, result)
    }

    /** Read-only SDK checklist; never taps its calibration, travel-mode or flight controls. */
    private fun checkInsetPanel() {
        for (permission in listOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.POST_NOTIFICATIONS)) {
            uiAutomation.grantRuntimePermission(targetContext.packageName, permission)
        }
        val setupMonitor = addMonitor("org.openutm.djiflight.app.SetupActivity", null, false)
        val flyMonitor = addMonitor("org.openutm.djiflight.app.FlyActivity", null, false)
        runOnMainSync {
            targetContext.startActivity(Intent().setClassName(targetContext.packageName, "org.openutm.djiflight.app.FlyActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        val fly = waitForMonitorWithTimeout(flyMonitor, 8_000) ?: error("Fly did not start")
        removeMonitor(flyMonitor)
        // A fresh device opens setup automatically; close only that setup activity to inspect Fly.
        val setup = waitForMonitorWithTimeout(setupMonitor, 8_000) ?: error("fresh Fly did not open setup")
        runOnMainSync { setup.finish() }
        removeMonitor(setupMonitor)
        waitForIdleSync()
        fun id(name: String) = targetContext.resources.getIdentifier(name, "id", targetContext.packageName)
        val stage = fly.findViewById<View>(id("stage"))
        val primary = fly.findViewById<ViewGroup>(id("primary"))
        val camera = primary.getChildAt(0)
        val inset = fly.findViewById<ViewGroup>(id("inset"))
        runOnMainSync { check(inset.getChildAt(inset.childCount - 1).performClick()) }
        waitForIdleSync()
        runOnMainSync {
            check(primary.width < stage.width / 2 && camera.scaleX < 1f) { "camera was not in inset" }
            // Reproduce an external SDK request without requiring SDK registration or an aircraft
            // on the emulator. The real panel, global-layout listener and Fly activity handle it.
            fly.findViewById<View>(id("pre_flight_check_list")).visibility = View.VISIBLE
        }
        val end = SystemClock.uptimeMillis() + 8_000
        var expanded = false
        var details = ""
        while (SystemClock.uptimeMillis() < end && !expanded) {
            runOnMainSync {
                val panel = fly.findViewById<View>(id("pre_flight_check_list"))
                expanded = panel.isShown && primary.width == stage.width && camera.scaleX == 1f && !inset.isShown
                details = "panelShown=${panel.isShown}, visibility=${panel.visibility}, primary=${primary.width}, stage=${stage.width}, scale=${camera.scaleX}, insetShown=${inset.isShown}"
            }
            if (!expanded) SystemClock.sleep(100)
        }
        check(expanded) { "SDK checklist did not expand: $details" }
        runOnMainSync {
            check(primary.getChildAt(0) === camera) { "camera tree replaced during expansion" }
            fly.finish()
        }
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = node?.let {
        listOf(it) + (0 until it.childCount).flatMap { index -> nodes(it.getChild(index)) }
    } ?: emptyList()
    private fun waitFor(text: String): AccessibilityNodeInfo {
        val end = SystemClock.uptimeMillis() + 8_000
        while (SystemClock.uptimeMillis() < end) {
            val current = nodes(uiAutomation.rootInActiveWindow)
            current.firstOrNull { it.text?.toString()?.contains(text) == true }?.let { return it }
            // Android's first-launch immersive-mode tutorial temporarily covers the app.
            current.firstOrNull { it.text?.toString()?.equals("GOT IT", ignoreCase = true) == true }?.let { notice ->
                var action = notice
                while (!action.isClickable && action.parent != null) action = action.parent
                action.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            SystemClock.sleep(100)
        }
        error("screen text missing: $text")
    }
}

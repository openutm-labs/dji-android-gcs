package org.openutm.djiflight.test

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

/** Fresh-install release smoke check. Never enters credentials or submits network/flight data. */
@android.annotation.TargetApi(34)
class PanamaSetupSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
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
            result.putString("checks", "Panama default; empty personal identity; setup form opens; masked secret; missing pilot rejected before connection")
            result.putString("result", "PASS")
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            result.putString("result", "FAIL")
            result.putString("error", e.javaClass.simpleName + ": " + e.message)
            finish(Activity.RESULT_CANCELED, result)
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

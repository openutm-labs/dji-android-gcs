package org.openutm.djiflight.app.v4

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.openutm.djiflight.app.FlightApp
import org.openutm.djiflight.app.PlanActivity
import org.openutm.djiflight.core.geo.LatLng
import org.openutm.djiflight.core.geo.LocalPlane
import org.openutm.djiflight.core.geo.XY
import org.openutm.djiflight.core.mission.*
import java.io.File

/** Shell-only, simulation-only visual smoke fixture. Never declares or starts a flight. */
class PlanSmokeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DEBUG && packageName.endsWith(".sim"))
        val graph = (application as FlightApp).graph
        check(graph.provision(graph.devProvisioning()).isEmpty())
        val pattern = SurveyPattern.valueOf(intent.getStringExtra("pattern") ?: "GRID")
        val mode = intent.getStringExtra("mode") ?: "plan"
        val plane = LocalPlane(LatLng(-1.2797,36.8344))
        val points = if (pattern == SurveyPattern.CORRIDOR) listOf(XY(0.0,0.0),XY(100.0,0.0),XY(150.0,100.0))
            else listOf(XY(0.0,0.0),XY(100.0,0.0),XY(100.0,100.0),XY(0.0,100.0))
        val draft = SavedSurvey(name="Demo ${pattern.name}",points=points.map(plane::toLatLng),pattern=pattern,
            heightM=40.0,frontOverlap=0.75,sideOverlap=0.65,headingDeg=0.0,speedMps=3.0,gimbalPitchDeg=pattern.defaultGimbalDeg,
            widthM=30.0,topHeightM=80.0)
        PlanLibrary(File(filesDir,"plans")).save(draft)
        val handler = Handler(Looper.getMainLooper())
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity !is PlanActivity) return
                application.unregisterActivityLifecycleCallbacks(this)
                handler.postDelayed({
                    runCatching {
                        PlanActivity::class.java.getDeclaredMethod("loadDraft",SavedSurvey::class.java).apply { isAccessible=true }.invoke(activity,draft)
                        if (mode=="roundtrip") {
                            val library = PlanLibrary(File(filesDir,"plans"))
                            val input = File(filesDir,"smoke-import.json").apply { writeText(library.encode(draft)) }
                            val result = PlanActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java).apply { isAccessible=true }
                            val count = library.list().size
                            result.invoke(activity,8,RESULT_OK,Intent().setData(android.net.Uri.fromFile(input)))
                            check(library.list().size==count+1) { "import overwrote an existing saved plan" }
                            PlanActivity::class.java.getDeclaredField("exporting").apply { isAccessible=true }.set(activity,draft)
                            val output = File(filesDir,"smoke-export.json")
                            result.invoke(activity,9,RESULT_OK,Intent().setData(android.net.Uri.fromFile(output)))
                            check(library.decode(output.readText())==draft) { "export did not preserve the plan" }
                            Log.i("OpenUTMFlight","saved-plan Android URI import/export smoke passed")
                        }
                        if (mode=="library") PlanActivity::class.java.getDeclaredMethod("openDrafts").apply { isAccessible=true }.invoke(activity)
                        handler.postDelayed({
                            val view=activity.window.decorView
                            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                            view.draw(Canvas(bitmap))
                            val file=File(filesDir,"smoke-${pattern.name}.png")
                            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                            Log.i("OpenUTMFlight","planning smoke saved ${file.name}")
                        },2_000)
                    }.onFailure { Log.e("OpenUTMFlight","planning smoke failed",it) }
                },1_000)
            }
            override fun onActivityCreated(a:Activity,s:Bundle?)=Unit
            override fun onActivityStarted(a:Activity)=Unit
            override fun onActivityPaused(a:Activity)=Unit
            override fun onActivityStopped(a:Activity)=Unit
            override fun onActivitySaveInstanceState(a:Activity,s:Bundle)=Unit
            override fun onActivityDestroyed(a:Activity)=Unit
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        startActivity(Intent(this,PlanActivity::class.java));finish()
    }
}

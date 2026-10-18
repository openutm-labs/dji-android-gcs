package org.openutm.djiflight.app.background

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.openutm.djiflight.app.FlightApp
import org.openutm.djiflight.app.FlyActivity
import org.openutm.djiflight.app.R
import org.openutm.djiflight.app.phaseText

/**
 * Keeps the process (recording, live upload) alive while a flight is in progress, with the screen
 * off or another app in front. Started when a flight is planned; stops itself when nothing is left.
 */
class FlightForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_flight), NotificationManager.IMPORTANCE_LOW))
        val n = notification(getString(R.string.bg_flight_in_progress), "")
        if (Build.VERSION.SDK_INT >= 29) {
            val type = if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(ID, n, type)
        } else {
            startForeground(ID, n)
        }
        val graph = (application as FlightApp).graph
        scope.launch {
            while (true) {
                val s = graph.service.status.value
                val f = s.activeFlight
                val pending = graph.service.pendingFlights()
                if (f == null || (!graph.flightInProgress(f) && pending == 0)) {
                    stopSelf()
                    break
                }
                val text = listOfNotNull(f.declarationId?.let { getString(R.string.bg_declared) } ?: getString(R.string.bg_not_declared_yet), s.lastSync?.status?.name?.lowercase(), if (!s.online) getString(R.string.bg_offline) else null).joinToString(" · ")
                nm.notify(ID, notification(getString(R.string.bg_title, phaseText(f.phase), f.plan?.siteName ?: f.flightId.take(8)), text))
                delay(5_000)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        // Anything not uploaded yet goes to the background sync job.
        SyncJobService.schedule(this)
        super.onDestroy()
    }

    private fun notification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, FlyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_stat_flight).setContentTitle(title).setContentText(text).setOngoing(true).setContentIntent(open).build()
    }

    companion object {
        private const val CHANNEL = "flight"
        private const val ID = 42

        fun start(context: Context) {
            val i = Intent(context, FlightForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (e: Exception) {
                // Android 12+ refuses to start it from the background; the sync job covers that case.
                Log.w("OpenUTMFlight", "foreground service not started: ${e.message}")
                SyncJobService.schedule(context)
            }
        }
    }
}

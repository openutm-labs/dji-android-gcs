package org.openutm.djiflight.app.background

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.openutm.djiflight.app.FlightApp

/**
 * Uploads what is still on the device after the app closed: runs when the system sees a network,
 * without starting the drone SDK. Rescheduled until every flight is closed or expired.
 */
class SyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val graph = (application as FlightApp).graph
        graph.startBackground(this)
        job = scope.launch {
            var pending = graph.service.pendingFlights()
            withTimeoutOrNull(8 * 60_000L) {
                while (pending > 0) {
                    graph.service.syncAll()
                    pending = graph.service.pendingFlights()
                    if (pending > 0) delay(10_000)
                }
            }
            Log.i("OpenUTMFlight", "background sync done, $pending flight(s) still pending")
            jobFinished(params, pending > 0)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }

    companion object {
        private const val JOB_ID = 4242

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, SyncJobService::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setBackoffCriteria(60_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build(),
            )
        }
    }
}

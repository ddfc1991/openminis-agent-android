package com.openminis.app.plus

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [Mines-38.10 cloud_brain] While a cloud task is open, the system wakes Mines every ~15 min (network required) to read
 * what the cloud did since the last cursor: a task that now waits for the user posts its notice + notification even if
 * Mines was frozen or killed. The work itself never depends on this — it runs on the server.
 */
class CloudBrainJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        scope.launch {
            runCatching {
                ChatHome.init(applicationContext); CloudBrain.init(applicationContext)
                withTimeoutOrNull(45_000) { CloudBrain.catchUpOnce() }
                if (!CloudBrain.anyOpen()) unschedule(applicationContext)
            }
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 73110

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            runCatching {
                js.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, CloudBrainJob::class.java))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(15 * 60 * 1000L, 5 * 60 * 1000L)
                    .setPersisted(false)
                    .build())
            }
        }

        fun unschedule(context: Context) { runCatching { context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID) } }
    }
}

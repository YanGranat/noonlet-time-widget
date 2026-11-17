package app.noonlet

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal object AlarmRefreshScheduler {
    const val JOB = 4103

    fun reconcile(context: Context, rearm: Boolean = false): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val bound = BaseWidgetProvider.boundIds(context, false)
        val enabled = WidgetSettingsStore.configuredIds(context, bound, false).any {
            WidgetSettingsStore.load(context, it, false).showAlarm
        }
        if (!enabled) {
            scheduler.cancel(JOB)
            return true
        }
        if (!rearm && scheduler.getPendingJob(JOB) != null) return true
        val job = JobInfo.Builder(JOB, ComponentName(context, AlarmRefreshJobService::class.java))
            .addTriggerContentUri(JobInfo.TriggerContentUri(DeviceAlarmClock.uri, 0))
            .setTriggerContentUpdateDelay(500)
            .setTriggerContentMaxDelay(2_000)
            .build()
        return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
    }
}

class AlarmRefreshJobService : JobService() {
    private val main = Handler(Looper.getMainLooper())
    private var active: Any? = null
    private var work: Future<*>? = null

    override fun onStartJob(params: JobParameters): Boolean {
        cancelWork()
        val token = Any()
        active = token
        work = publisher.submit {
            var failed = false
            try {
                failed = !BaseWidgetProvider.updateAll(applicationContext, false)
            } catch (_: Exception) {
                failed = true
            }
            main.post {
                if (active === token) {
                    active = null
                    work = null
                    // Rescheduling transfers content changes collected during publication.
                    if (failed || !AlarmRefreshScheduler.reconcile(this, rearm = true))
                        jobFinished(params, true)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        cancelWork()
        return true
    }

    override fun onDestroy() {
        cancelWork()
        super.onDestroy()
    }

    private fun cancelWork() {
        active = null
        work?.cancel(true)
        work = null
    }

    companion object {
        private val publisher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "alarm-widgets").apply { isDaemon = true }
        }
    }
}

package app.noonlet

import android.app.job.JobScheduler
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36])
class AlarmRefreshSchedulerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val scheduler: JobScheduler get() = context.getSystemService(JobScheduler::class.java)

    @Before fun prepare() {
        scheduler.cancelAll()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
    }

    @Test fun onlyPlacedClockWidgetsWithAlarmEnabledNeedAContentWatch() {
        AlarmRefreshScheduler.reconcile(context)
        assertNull(scheduler.getPendingJob(AlarmRefreshScheduler.JOB))
        WidgetSettingsStore.save(context, 0, false, WidgetSettings.defaults(false).apply { showAlarm = true })
        AlarmRefreshScheduler.reconcile(context)
        assertNull(scheduler.getPendingJob(AlarmRefreshScheduler.JOB))
        bind(83)
        WidgetSettingsStore.save(context, 83, false, WidgetSettings.defaults(false).apply { showAlarm = true })
        assertTrue(AlarmRefreshScheduler.reconcile(context))
        val job = requireNotNull(scheduler.getPendingJob(AlarmRefreshScheduler.JOB))
        assertFalse(job.isPeriodic)
        assertFalse(job.isPersisted)
        val triggers = requireNotNull(job.triggerContentUris)
        assertEquals(1, triggers.size)
        assertEquals(android.provider.Settings.System.getUriFor("next_alarm_clock_formatted"), triggers.single().uri)
        assertEquals(ComponentName(context, AlarmRefreshJobService::class.java), job.service)
        assertTrue(AlarmRefreshScheduler.reconcile(context))
        assertSame(job, scheduler.getPendingJob(AlarmRefreshScheduler.JOB))
        WidgetSettingsStore.save(context, 83, false, WidgetSettings.defaults(false))
        assertTrue(AlarmRefreshScheduler.reconcile(context))
        assertNull(scheduler.getPendingJob(AlarmRefreshScheduler.JOB))
    }

    @Test fun rearmingReplacesTheConsumedWatchAndAnEmptyWorkspaceCancelsIt() {
        bind(83)
        WidgetSettingsStore.save(context, 83, false, WidgetSettings.defaults(false).apply { showAlarm = true })
        AlarmRefreshScheduler.reconcile(context)
        val original = scheduler.getPendingJob(AlarmRefreshScheduler.JOB)
        assertTrue(AlarmRefreshScheduler.reconcile(context, rearm = true))
        assertNotSame(original, scheduler.getPendingJob(AlarmRefreshScheduler.JOB))
        WidgetSettingsStore.delete(context, 83, false)
        AlarmRefreshScheduler.reconcile(context)
        assertNull(scheduler.getPendingJob(AlarmRefreshScheduler.JOB))
    }

    private fun bind(id: Int) {
        val manager = AppWidgetManager.getInstance(context)
        val component = BaseWidgetProvider.component(context, false)
        val info = AppWidgetProviderInfo().apply { provider = component }
        Shadows.shadowOf(manager).addInstalledProvider(info)
        manager.bindAppWidgetIdIfAllowed(id, component, Bundle())
    }
}

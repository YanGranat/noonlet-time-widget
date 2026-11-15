package app.noonlet

import android.app.job.JobInfo
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
@Config(sdk = [34, 36])
class WeatherRefreshSchedulerTest {
    private lateinit var context: Context
    private lateinit var scheduler: JobScheduler

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancelAll()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
    }

    @Test
    fun freshWeatherAvoidsInitialWorkWhileMissingPeriodicWorkIsRecreated() {
        val previous = WeatherRepository.store
        val directory = java.io.File(context.cacheDir, "scheduler-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): java.io.File = directory
        }
        val time = 1_870_000_000_000L
        val settings = enabled()
        var calls = 0
        val store = WeatherStore(isolated, java.util.concurrent.Executor { it.run() }, weatherClient {
            calls++
            """{"timezone":"GMT","utc_offset_seconds":0,
            "current_units":{"time":"unixtime","interval":"seconds","temperature_2m":"°C","weather_code":"wmo code","is_day":""},
            "current":{"time":${time / 1000},"interval":900,"temperature_2m":14,"weather_code":2,"is_day":1}}"""
        }, now = { time })
        try {
            WeatherRepository.store = store
            bind(31, false)
            WidgetSettingsStore.save(isolated, 31, false, settings)
            assertEquals(WeatherStatus.FRESH, store.refresh(WeatherRequest.capture(settings)).get().status)
            WeatherRefreshScheduler.reconcile(isolated)
            assertNotNull(scheduler.getPendingJob(WeatherRefreshScheduler.PERIODIC_JOB))
            assertNull(scheduler.getPendingJob(WeatherRefreshScheduler.IMMEDIATE_JOB))
            scheduler.cancel(WeatherRefreshScheduler.PERIODIC_JOB)
            WeatherRefreshScheduler.reconcile(isolated)
            assertNotNull(scheduler.getPendingJob(WeatherRefreshScheduler.PERIODIC_JOB))
            assertNull(scheduler.getPendingJob(WeatherRefreshScheduler.IMMEDIATE_JOB))
            assertEquals(1, calls)
        } finally {
            WeatherRepository.store = previous
            directory.deleteRecursively()
        }
    }

    @Test
    fun unknownBindingsAndWorldSettingsNeverScheduleWeather() {
        bind(1, false)
        bind(2, true)
        WidgetSettingsStore.save(context, 0, false, enabled())
        WidgetSettingsStore.save(context, 2, true, enabled())
        WeatherRefreshScheduler.reconcile(context)
        assertTrue(scheduler.allPendingJobs.isEmpty())
        assertTrue(WeatherRefreshScheduler.configurations(context).isEmpty())
    }

    @Test
    fun configuredEnabledClockGetsPeriodicAndInitialWorkWithoutResizeRescheduling() {
        bind(1, false)
        WidgetSettingsStore.save(context, 1, false, enabled())
        WeatherRefreshScheduler.reconcile(context)
        val periodic = scheduler.getPendingJob(WeatherRefreshScheduler.PERIODIC_JOB)
        val immediate = scheduler.getPendingJob(WeatherRefreshScheduler.IMMEDIATE_JOB)
        assertNotNull(periodic)
        assertNotNull(immediate)
        assertEquals(30 * 60_000L, periodic!!.intervalMillis)
        assertEquals(5 * 60_000L, periodic.flexMillis)
        assertTrue(periodic.isPersisted)
        WeatherRefreshScheduler.reconcile(context)
        assertSame(periodic, scheduler.getPendingJob(WeatherRefreshScheduler.PERIODIC_JOB))
        assertSame(immediate, scheduler.getPendingJob(WeatherRefreshScheduler.IMMEDIATE_JOB))
        ClockWidgetProvider()
            .onAppWidgetOptionsChanged(context, AppWidgetManager.getInstance(context), 1, Bundle())
        assertSame(immediate, scheduler.getPendingJob(WeatherRefreshScheduler.IMMEDIATE_JOB))
    }

    @Test
    fun reconciliationReplacesAnExistingPeriodicJobWithAnOversizedFlexWindow() {
        bind(1, false)
        WidgetSettingsStore.save(context, 1, false, enabled())
        WeatherRefreshScheduler.reconcile(context)
        val immediate = scheduler.getPendingJob(WeatherRefreshScheduler.IMMEDIATE_JOB)
        scheduler.schedule(
            JobInfo.Builder(
                    WeatherRefreshScheduler.PERIODIC_JOB,
                    ComponentName(context, WeatherRefreshJobService::class.java),
                )
                .setPeriodic(WeatherSnapshot.REFRESH_INTERVAL)
                .setPersisted(true)
                .build(),
        )
        WeatherRefreshScheduler.reconcile(context)
        val periodic = scheduler.getPendingJob(WeatherRefreshScheduler.PERIODIC_JOB)!!
        assertEquals(30 * 60_000L, periodic.intervalMillis)
        assertEquals(5 * 60_000L, periodic.flexMillis)
        assertTrue(periodic.isPersisted)
        assertSame(immediate, scheduler.getPendingJob(WeatherRefreshScheduler.IMMEDIATE_JOB))
        WeatherRefreshScheduler.reconcile(context)
        assertSame(periodic, scheduler.getPendingJob(WeatherRefreshScheduler.PERIODIC_JOB))
    }

    @Test
    fun disablingOrDeletingTheFinalClockCancelsAllWeatherJobs() {
        bind(1, false)
        WidgetSettingsStore.save(context, 1, false, enabled())
        WeatherRefreshScheduler.reconcile(context)
        WidgetSettingsStore.save(context, 1, false, WidgetSettings())
        WeatherRefreshScheduler.reconcile(context)
        assertTrue(scheduler.allPendingJobs.isEmpty())
        WidgetSettingsStore.save(context, 1, false, enabled())
        WeatherRefreshScheduler.reconcile(context)
        ClockWidgetProvider().onDeleted(context, intArrayOf(1))
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    private fun enabled() =
        WidgetSettings().apply {
            weatherEnabled = true
            weatherUseDevice = false
            weatherPlace = WeatherPlace("West Harbor", 51_000_000, -1_000_000).encode()
        }

    private fun bind(id: Int, world: Boolean) {
        val manager = AppWidgetManager.getInstance(context)
        val info =
            AppWidgetProviderInfo().apply {
                provider = BaseWidgetProvider.component(context, world)
                initialLayout = if (world) R.layout.world_widget else R.layout.clock_widget
            }
        Shadows.shadowOf(manager).addInstalledProvider(info)
        Shadows.shadowOf(manager).addBoundWidget(id, info)
    }
}

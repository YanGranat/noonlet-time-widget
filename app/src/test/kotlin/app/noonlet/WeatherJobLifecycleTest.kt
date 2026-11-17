package app.noonlet

import android.app.job.JobParameters
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Looper
import android.os.Parcel
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowJobService
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36])
class WeatherJobLifecycleTest {
    private lateinit var context: Context
    private var previous: WeatherStore? = null
    private val time = 1_800_000_000_000L
    private val place = WeatherPlace("West Harbor", 51_000_000, -1_000_000)

    private class Queue : Executor {
        val work = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            work += command
        }

        fun next() {
            work.removeFirst().run()
        }
    }

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        previous = WeatherRepository.store
        val manager = AppWidgetManager.getInstance(context)
        val info =
            AppWidgetProviderInfo().apply {
                provider = BaseWidgetProvider.component(context, false)
                initialLayout = R.layout.clock_widget
            }
        Shadows.shadowOf(manager).addInstalledProvider(info)
        Shadows.shadowOf(manager).addBoundWidget(1, info)
        WidgetSettingsStore.save(context, 1, false, settings())
        // Robolectric initializes themed resources on the first RemoteViews delivery.
        BaseWidgetProvider.updateWidget(context, 1, false)
    }

    @After
    fun clean() {
        WeatherRepository.store = previous
        File(context.noBackupFilesDir, "weather_cache.json").delete()
    }

    @Test
    fun stoppingAJobDoesNotCancelAConcurrentForegroundConsumerOrPublishLate() {
        val queue = Queue()
        val store = store(queue, weatherClient { response() })
        WeatherRepository.store = store
        val foreground = WeatherRepository.refresh(context, WeatherRequest.capture(settings()))
        val controller = Robolectric.buildService(WeatherRefreshJobService::class.java).create()
        val service = controller.get()
        val params = parameters()
        assertTrue(service.onStartJob(params))
        assertTrue(service.onStopJob(params))
        queue.next()
        assertEquals(WeatherStatus.FRESH, foreground.get(2, TimeUnit.SECONDS).status)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(Shadow.extract<ShadowJobService>(service).isJobFinished)
        assertNotNull(store.state(WeatherRequest.capture(settings())).snapshot)
        controller.destroy()
    }

    @Test
    fun periodicJobsFetchFreshWeatherWhileImmediateJobsReuseTheCache() {
        var calls = 0
        val store =
            store(
                Executor { it.run() },
                weatherClient {
                    calls++
                    response()
                },
            )
        WeatherRepository.store = store
        assertEquals(
            WeatherStatus.FRESH,
            WeatherRepository.refresh(context, WeatherRequest.capture(settings())).join().status,
        )
        assertEquals(1, calls)
        for ((jobId, expectedCalls) in
            listOf(
                WeatherRefreshScheduler.IMMEDIATE_JOB to 1,
                WeatherRefreshScheduler.PERIODIC_JOB to 2,
            )) {
            val controller = Robolectric.buildService(WeatherRefreshJobService::class.java).create()
            val service = controller.get()
            assertTrue(service.onStartJob(parameters(jobId)))
            val shadow = awaitFinished(service)
            assertTrue(shadow.isJobFinished)
            assertFalse(shadow.isRescheduleNeeded)
            assertEquals(expectedCalls, calls)
            controller.destroy()
        }
    }

    @Test
    fun onlyTransientNetworkFailuresRequestJobBackoff() {
        val store = store(Executor { it.run() }, weatherClient { throw IOException("Unavailable") })
        WeatherRepository.store = store
        val controller = Robolectric.buildService(WeatherRefreshJobService::class.java).create()
        val service = controller.get()
        assertTrue(service.onStartJob(parameters()))
        val shadow = awaitFinished(service)
        assertTrue(shadow.isJobFinished)
        assertTrue(shadow.isRescheduleNeeded)
        controller.destroy()
    }

    @Test fun serverDirectedBackoffSchedulesOneDelayedJobInsteadOfRapidRetries() {
        WeatherRepository.store = store(Executor { it.run() }, weatherClient { throw WeatherRetry(time + 120_000) })
        val controller = Robolectric.buildService(WeatherRefreshJobService::class.java).create()
        val service = controller.get()
        assertTrue(service.onStartJob(parameters()))
        assertFalse(awaitFinished(service).isRescheduleNeeded)
        val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
        val deferred = requireNotNull(scheduler.getPendingJob(WeatherRefreshScheduler.DEFERRED_JOB))
        assertEquals(120_000L, deferred.minLatencyMillis)
        assertTrue(deferred.isPersisted)
        WidgetSettingsStore.save(context, 1, false, WidgetSettings())
        WeatherRefreshScheduler.reconcile(context)
        assertNull(scheduler.getPendingJob(WeatherRefreshScheduler.DEFERRED_JOB))
        controller.destroy()
    }

    @Test
    fun permissionFailuresFinishWithoutBackoffOrNetwork() {
        val settings = WidgetSettings().apply { weatherEnabled = true }
        WidgetSettingsStore.save(context, 1, false, settings)
        val store =
            WeatherStore(
                context,
                Executor { it.run() },
                weatherClient {
                    fail("No HTTP without a location")
                    response()
                },
                now = { time },
                cachedLocation = { null },
                permission = { false },
            )
        WeatherRepository.store = store
        val controller = Robolectric.buildService(WeatherRefreshJobService::class.java).create()
        val service = controller.get()
        assertTrue(service.onStartJob(parameters()))
        val shadow = awaitFinished(service)
        assertTrue(shadow.isJobFinished)
        assertFalse(shadow.isRescheduleNeeded)
        controller.destroy()
    }

    @Test
    @Config(sdk = [26, 34, 36])
    fun periodicDeviceWeatherUsesTheSavedPointWithoutForegroundAuthority() {
        val device = WidgetSettings().apply { weatherEnabled = true }
        WidgetSettingsStore.save(context, 1, false, device)
        var calls = 0
        var locations = 0
        val store = WeatherStore(
            context, Executor { it.run() },
            weatherClient { address ->
                assertTrue(address.contains("latitude=51.000000&longitude=-1.000000"))
                calls++
                response()
            },
            now = { time }, cachedLocation = { place }, permission = { true },
            locate = { foreground ->
                assertNull(foreground)
                locations++
                CompletableFuture.completedFuture(place)
            },
        )
        WeatherRepository.store = store
        val controller = Robolectric.buildService(WeatherRefreshJobService::class.java).create()
        val service = controller.get()
        assertTrue(service.onStartJob(parameters(WeatherRefreshScheduler.PERIODIC_JOB)))
        assertFalse(awaitFinished(service).isRescheduleNeeded)
        assertEquals(1, locations)
        assertEquals(1, calls)
        assertEquals(place, store.state(WeatherRequest.capture(device)).place)
        controller.destroy()
    }

    @Test
    fun foregroundRefreshUsesConfiguredClocksIndependentlyOfTheSelectedEditorTab() {
        val device = WidgetSettings().apply { weatherEnabled = true }
        WidgetSettingsStore.save(context, 1, false, device)
        val manager = AppWidgetManager.getInstance(context)
        for ((id, world) in listOf(2 to false, 3 to false, 4 to true)) {
            val info = AppWidgetProviderInfo().apply {
                provider = BaseWidgetProvider.component(context, world)
                initialLayout = if (world) R.layout.world_widget else R.layout.clock_widget
            }
            Shadows.shadowOf(manager).addBoundWidget(id, info)
        }
        val manual = settings().apply { weatherPlace = place.copy(latitudeE6 = 52_000_000).encode() }
        WidgetSettingsStore.save(context, 2, false, manual)
        WidgetSettingsStore.save(context, 4, true, device)
        context.getSharedPreferences("MainActivity", 0).edit().putBoolean("world_tab", true).commit()
        val before = context.getSharedPreferences("noonlet_widgets", 0).getString("clock.1.state", null)
        var acquisitions = 0
        var calls = 0
        val store = WeatherStore(
            context, Executor { it.run() },
            weatherClient { calls++; response() }, now = { time },
            cachedLocation = { null }, permission = { true },
            locate = { foreground ->
                assertNotNull(foreground)
                assertTrue(foreground!!.isActive)
                acquisitions++
                CompletableFuture.completedFuture(place)
            },
        )
        WeatherRepository.store = store
        WeatherLocation.openForeground(context).use { foreground ->
            WeatherRefreshScheduler.refreshPlacedWidgets(context, foreground).get(5, TimeUnit.SECONDS)
        }
        assertEquals(1, acquisitions)
        assertEquals(2, calls)
        assertEquals(place, store.state(WeatherRequest.capture(device)).place)
        assertFalse(WidgetSettingsStore.hasOwnSettings(context, 3, false))
        assertEquals(before, context.getSharedPreferences("noonlet_widgets", 0).getString("clock.1.state", null))
        assertTrue(context.getSharedPreferences("MainActivity", 0).getBoolean("world_tab", false))
    }

    private fun awaitFinished(service: WeatherRefreshJobService): ShadowJobService {
        val shadow = Shadow.extract<ShadowJobService>(service)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!shadow.isJobFinished && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (!shadow.isJobFinished) Thread.sleep(5)
        }
        assertTrue("The asynchronous job completes before its deadline", shadow.isJobFinished)
        return shadow
    }

    private fun store(executor: Executor, client: WeatherClient) =
        WeatherStore(
            context,
            executor,
            client,
            now = { time },
            cachedLocation = { null },
            permission = { true },
        )

    private fun settings() =
        WidgetSettings().apply {
            weatherEnabled = true
            weatherUseDevice = false
            weatherPlace = place.encode()
        }

    private fun response(): String =
        """{"timezone":"GMT","utc_offset_seconds":0,
            "current_units":{"time":"unixtime","interval":"seconds","temperature_2m":"°C","weather_code":"wmo code","is_day":""},
            "current":{"time":${time / 1000},"interval":900,"temperature_2m":12.5,"weather_code":2,"is_day":1}}"""

    private fun parameters(jobId: Int = WeatherRefreshScheduler.IMMEDIATE_JOB): JobParameters {
        val constructor =
            JobParameters::class.java.declaredConstructors.first {
                it.parameterTypes.none { type -> type == Parcel::class.java }
            }
        constructor.isAccessible = true
        val arguments: Array<Any?> =
            constructor.parameterTypes
                .map { type ->
                    val argument: Any? =
                        when (type) {
                            Boolean::class.javaPrimitiveType -> false
                            Int::class.javaPrimitiveType -> 0
                            Long::class.javaPrimitiveType -> 0L
                            else -> null
                        }
                    argument
                }
                .toTypedArray()
        return (constructor.newInstance(*arguments) as JobParameters).also {
            ReflectionHelpers.setField(it, "jobId", jobId)
        }
    }
}

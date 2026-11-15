package app.noonlet

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36])
class WeatherErasureTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private val sessions = mutableListOf<WeatherLocation.ForegroundSession>()
    private val time = 1_800_000_000_000L
    private val place = WeatherPlace("West Harbor", 51_123_456, -1_654_321)

    private class Queue : Executor {
        val work = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) { work.add(command) }

        fun next() { work.removeFirst().run() }
    }

    @Before
    fun prepare() {
        val app = RuntimeEnvironment.getApplication() as Context
        directory = File(app.cacheDir, "weather-erasure-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this

            override fun getNoBackupFilesDir(): File = directory

            override fun checkSelfPermission(permission: String): Int =
                if (permission == Manifest.permission.ACCESS_COARSE_LOCATION) PackageManager.PERMISSION_GRANTED
                else PackageManager.PERMISSION_DENIED
        }
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
    }

    @After
    fun clean() {
        sessions.forEach { it.close() }
        directory.deleteRecursively()
    }

    @Test
    fun clearingForecastsPreservesTheRememberedPointAndWidgetSettings() {
        val locationFile = File(directory, "weather_location.json")
        locationFile.writeText(place.encode())
        val settings = configuredSettings()
        val saved = context.getSharedPreferences("noonlet_widgets", 0).all.toMap()
        var calls = 0
        val store = WeatherStore(context, Executor { it.run() }, weatherClient {
            calls++
            response()
        }, now = { time }, permission = { true })
        val request = WeatherRequest.capture(settings)
        assertEquals(0L, store.erasureRevision())
        assertNotNull(store.refresh(request).join().snapshot)
        assertTrue(File(directory, "weather_cache.json").isFile)
        File(directory, "weather_cache.json.bak").writeText("backup")
        File(directory, "weather_cache.json.new").writeText("unfinished")

        store.clearForecastCache().get(3, TimeUnit.SECONDS)

        assertEquals(1L, store.erasureRevision())
        assertFalse(File(directory, "weather_cache.json").exists())
        assertFalse(File(directory, "weather_cache.json.bak").exists())
        assertFalse(File(directory, "weather_cache.json.new").exists())
        assertEquals(place, WeatherLocation.cached(context))
        assertEquals(place.encode(), locationFile.readText())
        assertEquals(saved, context.getSharedPreferences("noonlet_widgets", 0).all)
        val cleared = store.state(request)
        assertNull(cleared.snapshot)
        assertNull(cleared.failureStatus)
        assertEquals(WeatherStatus.UNAVAILABLE, cleared.status)
        assertEquals(1, calls)
    }

    @Test
    fun forgettingTheDevicePointErasesForecastsWithoutChangingManualSelections() {
        File(directory, "weather_location.json").writeText(place.encode())
        val settings = configuredSettings()
        val saved = context.getSharedPreferences("noonlet_widgets", 0).all.toMap()
        var calls = 0
        val store = WeatherStore(context, Executor { it.run() }, weatherClient {
            calls++
            response()
        }, now = { time }, permission = { true })
        assertNotNull(store.refresh(WeatherRequest.capture(settings)).join().snapshot)
        val foreground = foreground()

        store.clearDeviceLocation().get(3, TimeUnit.SECONDS)

        assertTrue(foreground.isActive)
        assertFalse(File(directory, "weather_location.json").exists())
        assertFalse(File(directory, "weather_cache.json").exists())
        assertNull(WeatherLocation.cached(context))
        assertEquals(saved, context.getSharedPreferences("noonlet_widgets", 0).all)
        assertEquals(place.encode(), WidgetSettingsStore.load(context, 7, false).weatherPlace)
        assertNull(store.state(WeatherRequest(true, true, "")).place)
        assertNull(store.state(WeatherRequest.capture(settings)).snapshot)
        assertEquals(1, calls)
    }

    @Test
    fun aCompletedHttpRequestCannotRestoreAnErasedCache() = verifyLateHttpIsDiscarded(false)

    @Test
    fun deviceWeatherCannotRestoreItsPointOrForecastAfterErasure() = verifyLateHttpIsDiscarded(true)

    private fun verifyLateHttpIsDiscarded(forgetDevice: Boolean) {
        File(directory, "weather_location.json").writeText(place.encode())
        val network = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val storage = Queue()
        var calls = 0
        val store = WeatherStore(context, network, weatherClient {
            calls++
            started.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            response()
        }, now = { time }, locate = { CompletableFuture.completedFuture(place) },
            permission = { true }, eraser = storage)
        val request = WeatherRequest(true, forgetDevice, if (forgetDevice) "" else place.encode())
        try {
            val previous = store.refresh(request, if (forgetDevice) foreground() else null)
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val cleared = if (forgetDevice) store.clearDeviceLocation() else store.clearForecastCache()
            assertEquals(WeatherStatus.UNAVAILABLE, previous.join().status)
            storage.next()
            cleared.get(3, TimeUnit.SECONDS)
            release.countDown()
            network.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(1, calls)
            assertNull(store.state(request).snapshot)
            assertNull(store.state(request).failureStatus)
            assertFalse(File(directory, "weather_cache.json").exists())
            if (forgetDevice) {
                assertNull(store.state(request).place)
                assertNull(WeatherLocation.cached(context))
                assertFalse(File(directory, "weather_location.json").exists())
            }
        } finally {
            release.countDown()
            network.shutdownNow()
            assertTrue(network.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test
    fun queuedHttpWorkAndPendingLocationContinuationsCannotRestartAfterErasure() {
        val network = Queue()
        val storage = Queue()
        val location = CompletableFuture<WeatherPlace>()
        var acquisitions = 0
        var calls = 0
        val store = WeatherStore(context, network, weatherClient {
            calls++
            response()
        }, now = { time }, locate = {
            acquisitions++
            location
        }, permission = { true }, eraser = storage)
        val manual = WeatherRequest(true, false, place.encode())
        val queued = store.refresh(manual)
        val request = WeatherRequest(true, true, "")
        val original = foreground()
        val previous = store.refresh(request, original)
        original.close()
        val joined = store.refresh(request, foreground(), forceRefresh = true)
        assertEquals(1, acquisitions)

        val erased = store.clearForecastCache()
        assertEquals(WeatherStatus.UNAVAILABLE, queued.join().status)
        assertEquals(WeatherStatus.UNAVAILABLE, previous.join().status)
        assertEquals(WeatherStatus.UNAVAILABLE, joined.join().status)
        storage.next()
        erased.get(3, TimeUnit.SECONDS)
        location.complete(place)
        network.next()

        assertEquals(1, acquisitions)
        assertEquals(0, calls)
        assertTrue(network.work.isEmpty())
        assertNull(store.state(request).place)
        assertNull(store.state(request).failureStatus)
        assertFalse(File(directory, "weather_cache.json").exists())
    }

    @Test
    fun anExplicitRefreshWaitsForErasureAndThenCreatesFreshData() {
        val network = Queue()
        val storage = Queue()
        var calls = 0
        val store = WeatherStore(context, network, weatherClient {
            response((++calls).toDouble())
        }, now = { time }, eraser = storage)
        val request = WeatherRequest(true, false, place.encode())
        val initial = store.refresh(request)
        network.next()
        assertEquals(1.0, initial.join().snapshot!!.temperatureC, 0.0)
        val erased = store.clearForecastCache()
        val next = store.refresh(request, forceRefresh = true)
        assertFalse(erased.isDone)
        assertFalse(next.isDone)
        assertTrue(network.work.isEmpty())
        storage.next()
        erased.get(3, TimeUnit.SECONDS)
        assertEquals(1, network.work.size)
        network.next()
        assertEquals(2.0, next.join().snapshot!!.temperatureC, 0.0)
        assertTrue(File(directory, "weather_cache.json").isFile)
    }

    @Test
    fun erasureBetweenAdmissionAndAcquisitionPreventsANewLocationRequest() {
        var acquisitions = 0
        var calls = 0
        var eraseOnChange = true
        lateinit var erased: CompletableFuture<Void>
        lateinit var store: WeatherStore
        store = WeatherStore(context, Executor { it.run() }, weatherClient {
            calls++
            response()
        }, now = { time }, locate = {
            acquisitions++
            CompletableFuture.completedFuture(place)
        }, permission = { true }, changed = {
            if (eraseOnChange) {
                eraseOnChange = false
                erased = store.clearDeviceLocation()
            }
        })
        val request = WeatherRequest(true, true, "")
        val previous = store.refresh(request, foreground())
        erased.get(3, TimeUnit.SECONDS)
        assertEquals(WeatherStatus.UNAVAILABLE, previous.join().status)
        assertEquals(0, acquisitions)
        assertEquals(0, calls)
        assertNull(store.state(request).place)
        assertFalse(File(directory, "weather_location.json").exists())
        assertFalse(File(directory, "weather_cache.json").exists())
    }

    @Test
    fun forgettingTheDevicePointUpgradesAnUnfinishedCacheErasure() {
        File(directory, "weather_location.json").writeText(place.encode())
        val network = Queue()
        val storage = Queue()
        var calls = 0
        val store = WeatherStore(context, network, weatherClient {
            calls++
            response()
        }, now = { time }, eraser = storage)
        val request = WeatherRequest(true, false, place.encode())
        val first = store.clearForecastCache()
        assertEquals(1L, store.erasureRevision())
        val second = store.clearDeviceLocation()
        assertEquals(2L, store.erasureRevision())
        val repeated = store.clearForecastCache()
        assertEquals(2L, store.erasureRevision())
        val next = store.refresh(request)
        assertNull(WeatherLocation.cached(context))
        storage.next()
        first.get(3, TimeUnit.SECONDS)
        assertFalse(second.isDone)
        assertFalse(repeated.isDone)
        assertFalse(next.isDone)
        assertTrue(network.work.isEmpty())
        storage.next()
        second.get(3, TimeUnit.SECONDS)
        repeated.get(3, TimeUnit.SECONDS)
        assertFalse(File(directory, "weather_location.json").exists())
        assertEquals(1, network.work.size)
        network.next()
        assertEquals(WeatherStatus.FRESH, next.join().status)
        assertEquals(1, calls)
    }

    @Test
    fun failedDiskDeletionIsNotReportedAsSuccessfulErasure() {
        val file = File(directory, "weather_cache.json").apply { mkdirs() }
        File(file, "entry").writeText("unavailable")
        val store = WeatherStore(context, Executor { it.run() }, weatherClient { response() }, now = { time })
        assertThrows(Exception::class.java) { store.clearForecastCache().join() }
        assertTrue(file.exists())
    }

    private fun foreground() = WeatherLocation.openForeground(context).also { sessions += it }

    private fun configuredSettings() = WidgetSettings().apply {
        weatherEnabled = true
        weatherUseDevice = false
        weatherPlace = place.encode()
        textSize = 61
        dateOffsetX = -3
        opacity = 73
        WidgetSettingsStore.save(context, 7, false, this)
    }

    private fun response(temperature: Double = 12.5) = JSONObject()
        .put("utc_offset_seconds", 0).put("timezone", "GMT")
        .put("current_units", JSONObject().put("time", "unixtime").put("interval", "seconds")
            .put("temperature_2m", "°C").put("weather_code", "wmo code").put("is_day", ""))
        .put("current", JSONObject().put("time", time / 1000).put("interval", 900)
            .put("temperature_2m", temperature).put("weather_code", 2).put("is_day", 1)).toString()
}

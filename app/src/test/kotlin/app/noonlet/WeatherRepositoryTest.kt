package app.noonlet

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.function.Function
import org.json.JSONArray
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
@Config(sdk = [34, 36])
class WeatherRepositoryTest {
    private var time = 1_800_000_000_000L
    private lateinit var context: Context
    private val sessions = mutableListOf<WeatherLocation.ForegroundSession>()
    private lateinit var directory: File
    private val first = WeatherPlace("West Harbor", 51_123_456, -1_654_321)
    private val second = WeatherPlace("East Harbor", 52_000_001, 2_000_001)

    private class Queue : Executor {
        val work = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            work.add(command)
        }

        fun next() {
            work.removeFirst().run()
        }

        fun last() {
            work.removeLast().run()
        }
    }

    @Before
    fun prepare() {
        val app = RuntimeEnvironment.getApplication() as Context
        directory = File(app.cacheDir, "weather-${UUID.randomUUID()}").apply { mkdirs() }
        context =
            object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this

                override fun getNoBackupFilesDir(): File = directory
            }
    }

    @After
    fun clean() {
        sessions.forEach { it.close() }
        directory.deleteRecursively()
    }

    @Test
    fun cacheRetainsTheServerReferenceWhenTheDeviceClockDiffers() {
        val serverTime = time
        val payload = response(validTime = serverTime - 10 * 60_000)
        time -= 20 * 60_000
        val request = manual(first)
        val client = WeatherClient { WeatherResponse(payload, serverTime) }
        val original = store(Executor { it.run() }, client)
        val loaded = original.refresh(request).get()
        assertEquals(WeatherStatus.FRESH, loaded.status)
        val restored = store(Executor { it.run() }, weatherClient { error("Cached data must not request HTTP") })
        assertEquals(loaded.snapshot, restored.state(request).snapshot)
        assertEquals(WeatherStatus.FRESH, restored.state(request).status)
        time += WeatherSnapshot.REFRESH_INTERVAL
        assertEquals(WeatherStatus.STALE, restored.state(request).status)
    }

    @Test
    fun disabledSettingsDoNotResolveLocationOrRequestWeather() {
        var calls = 0
        val store =
            WeatherStore(
                context,
                Executor { it.run() },
                weatherClient {
                    calls++
                    response()
                },
                now = { time },
                locate = {
                    calls++
                    CompletableFuture.completedFuture(first)
                },
                permission = { true },
            )
        assertEquals(
            WeatherStatus.UNAVAILABLE,
            store.state(WeatherRequest.capture(WidgetSettings())).status,
        )
        assertEquals(
            WeatherStatus.UNAVAILABLE,
            store.refresh(WeatherRequest.capture(WidgetSettings()), null).join().status,
        )
        assertEquals(
            WeatherStatus.UNAVAILABLE,
            store.refresh(WeatherRequest.capture(WidgetSettings()), null, forceRefresh = true)
                .join().status,
        )
        assertEquals(0, calls)
        assertFalse(File(directory, "weather_cache.json").exists())
    }

    @Test
    fun cacheOnlyReadsNeverScheduleNetworkAndExpireBothTimestamps() {
        val queue = Queue()
        var calls = 0
        val store =
            store(
                queue,
                weatherClient {
                    calls++
                    response()
                },
            )
        val settings = manual(first)
        assertNull(store.state(settings).snapshot)
        assertTrue(queue.work.isEmpty())
        val future = store.refresh(settings, null)
        assertFalse(future.isDone)
        queue.next()
        assertEquals(WeatherStatus.FRESH, future.join().status)
        assertEquals(1, calls)
        assertEquals(WeatherStatus.FRESH, store.refresh(settings, null).join().status)
        assertTrue(queue.work.isEmpty())
        time += WeatherSnapshot.REFRESH_INTERVAL
        assertEquals(WeatherStatus.STALE, store.state(settings).status)
        assertNull(store.state(settings).failureStatus)
        time += WeatherSnapshot.MAX_AGE
        assertNull(store.state(settings).snapshot)
        assertTrue(queue.work.isEmpty())
    }

    @Test
    fun forcedRefreshFetchesBeforeCacheExpiryAndSharesPendingWork() {
        val queue = Queue()
        var calls = 0
        val store = store(queue, weatherClient { response((++calls).toDouble()) })
        val settings = manual(first)
        val initial = store.refresh(settings, null)
        queue.next()
        assertEquals(1.0, initial.join().snapshot!!.temperatureC, 0.0)
        time += WeatherSnapshot.REFRESH_INTERVAL - 5_000L

        assertEquals(WeatherStatus.FRESH, store.refresh(settings, null).join().status)
        assertTrue(queue.work.isEmpty())
        val refresh = store.refresh(settings, null, forceRefresh = true)
        val concurrent = store.refresh(settings, null, forceRefresh = true)
        assertFalse(refresh.isDone)
        assertEquals(1, queue.work.size)
        queue.next()
        assertEquals(2.0, refresh.join().snapshot!!.temperatureC, 0.0)
        assertEquals(refresh.join(), concurrent.join())
        assertEquals(2, calls)
        val foreground = store.refresh(settings, null, forceRefresh = true)
        assertFalse(foreground.isDone)
        queue.next()
        assertEquals(3.0, foreground.join().snapshot!!.temperatureC, 0.0)
        assertEquals(3, calls)
    }

    @Test
    fun forcedRefreshUpgradesAnExistingLocationRequestBeforeItsCacheLookup() {
        val queue = Queue()
        val location = CompletableFuture<WeatherPlace>()
        var calls = 0
        var locationCalls = 0
        val store =
            WeatherStore(
                context,
                queue,
                weatherClient { response((++calls).toDouble()) },
                now = { time },
                locate = {
                    locationCalls++
                    location
                },
                cachedLocation = { first },
                permission = { true },
            )
        val initial = store.refresh(manual(first), null)
        queue.next()
        assertEquals(WeatherStatus.FRESH, initial.join().status)
        val device = WeatherRequest.capture(WidgetSettings().apply { weatherEnabled = true })
        val foreground = store.refresh(device, foregroundSession())
        val periodic = store.refresh(device, null, forceRefresh = true)
        assertEquals(1, locationCalls)
        assertTrue(queue.work.isEmpty())
        location.complete(first)
        assertEquals(1, queue.work.size)
        queue.next()
        assertEquals(2.0, periodic.join().snapshot!!.temperatureC, 0.0)
        assertEquals(periodic.join(), foreground.join())
        assertEquals(2, calls)
    }

    @Test
    fun forcedRefreshAfterCacheSelectionStillFetchesItsCapturedRequest() {
        val queue = Queue()
        var afterLookup: (() -> Unit)? = null
        val location =
            object : CompletableFuture<WeatherPlace>() {
                    override fun <U> thenCompose(
                        fn: Function<in WeatherPlace, out CompletionStage<U>>,
                    ): CompletableFuture<U> =
                        super.thenCompose(fn).also {
                            val callback = afterLookup
                            afterLookup = null
                            callback?.invoke()
                        }
                }
                .apply { complete(first) }
        var calls = 0
        val store =
            WeatherStore(
                context,
                queue,
                weatherClient { response((++calls).toDouble()) },
                now = { time },
                locate = { location },
                cachedLocation = { first },
                permission = { true },
            )
        val initial = store.refresh(manual(first), null)
        queue.next()
        assertEquals(WeatherStatus.FRESH, initial.join().status)
        val settings = WidgetSettings().apply { weatherEnabled = true }
        val device = WeatherRequest.capture(settings)
        lateinit var periodic: CompletableFuture<WeatherState>
        afterLookup = {
            periodic = store.refresh(device, null, forceRefresh = true)
            settings.weatherUseDevice = false
            settings.weatherPlace = second.encode()
        }
        val foreground = store.refresh(device, foregroundSession())
        assertEquals(1.0, foreground.join().snapshot!!.temperatureC, 0.0)
        assertFalse(periodic.isDone)
        assertEquals(1, queue.work.size)
        queue.next()
        assertEquals(first, periodic.join().place)
        assertEquals(2.0, periodic.join().snapshot!!.temperatureC, 0.0)
        assertEquals(2, calls)
    }

    @Test
    fun cancellingOneConsumerDoesNotCancelAnotherOrDiscardTheCache() {
        val queue = Queue()
        var calls = 0
        val store =
            store(
                queue,
                weatherClient {
                    calls++
                    response()
                },
            )
        val one = store.refresh(manual(first), null)
        val two = store.refresh(manual(first), null)
        one.cancel(true)
        queue.next()
        assertTrue(one.isCancelled)
        assertEquals(WeatherStatus.FRESH, two.join().status)
        assertEquals(1, calls)
        assertEquals(12.5, store.state(manual(first)).snapshot!!.temperatureC, 0.0)
    }

    @Test
    fun manualAndDeviceSelectionsAtTheSamePointShareOneRequest() {
        val queue = Queue()
        var calls = 0
        val store =
            WeatherStore(
                context,
                queue,
                weatherClient {
                    calls++
                    response()
                },
                now = { time },
                locate = { CompletableFuture.completedFuture(first.copy(name = "")) },
                cachedLocation = { first.copy(name = "") },
                permission = { true },
            )
        val manual = store.refresh(manual(first), null)
        val device = store.refresh(
            WeatherRequest.capture(WidgetSettings().apply { weatherEnabled = true }), foregroundSession(),
        )
        assertEquals(1, queue.work.size)
        queue.next()
        assertEquals(first.name, manual.join().place!!.name)
        assertEquals("", device.join().place!!.name)
        assertEquals(1, calls)
    }

    @Test
    @Config(sdk = [26, 34, 36])
    fun foregroundRefreshFollowsPendingBackgroundWeatherWithANewDevicePoint() {
        val queue = Queue()
        var acquisitions = 0
        var calls = 0
        val store = WeatherStore(
            context, queue,
            weatherClient { address ->
                calls++
                response(if (address.contains("latitude=51.")) 5.0 else 20.0)
            },
            now = { time },
            locate = { session ->
                if (session == null) CompletableFuture.completedFuture(first)
                else { acquisitions++; CompletableFuture.completedFuture(second) }
            },
            cachedLocation = { first }, permission = { true },
        )
        val request = WeatherRequest(true, true, "")
        val background = store.refresh(request, forceRefresh = true)
        val foreground = store.refresh(request, foregroundSession())
        assertEquals(1, queue.work.size)
        assertEquals(0, acquisitions)
        assertFalse(foreground.isDone)
        queue.next()
        assertEquals(5.0, background.join().snapshot!!.temperatureC, 0.0)
        assertEquals(1, acquisitions)
        assertEquals(1, queue.work.size)
        queue.next()
        val result = foreground.join()
        assertEquals(second, result.place)
        assertEquals(20.0, result.snapshot!!.temperatureC, 0.0)
        assertEquals(2, calls)
        assertTrue(queue.work.isEmpty())
    }

    @Test
    @Config(sdk = [26, 34, 36])
    fun aResumedSessionDoesNotAdoptAClosedSessionsPendingDeviceRequest() {
        val queue = Queue()
        var acquisitions = 0
        val store = WeatherStore(
            context, queue,
            weatherClient { address -> response(if (address.contains("latitude=51.")) 5.0 else 20.0) },
            now = { time },
            locate = {
                CompletableFuture.completedFuture(if (++acquisitions == 1) first else second)
            },
            cachedLocation = { first }, permission = { true },
        )
        val request = WeatherRequest(true, true, "")
        val oldSession = foregroundSession()
        val previous = store.refresh(request, oldSession)
        oldSession.close()
        val resumed = store.refresh(request, foregroundSession())
        assertEquals(1, acquisitions)
        assertFalse(resumed.isDone)
        queue.next()
        assertEquals(5.0, previous.join().snapshot!!.temperatureC, 0.0)
        assertEquals(2, acquisitions)
        assertFalse(resumed.isDone)
        queue.next()
        assertEquals(second, resumed.join().place)
        assertEquals(20.0, resumed.join().snapshot!!.temperatureC, 0.0)
        assertNull(resumed.join().failureStatus)
    }

    @Test
    fun cancelledAcquisitionPreservesThePreviousFailureAndUsableWeather() {
        var calls = 0
        var location = CompletableFuture.completedFuture(first)
        val store = WeatherStore(
            context, Executor { it.run() },
            weatherClient { if (++calls == 1) response() else throw IOException("Unavailable") },
            now = { time }, locate = { location }, cachedLocation = { first }, permission = { true },
        )
        val request = WeatherRequest(true, true, "")
        val session = foregroundSession()
        val saved = store.refresh(request, session).join().snapshot
        location = CompletableFuture()
        val interrupted = store.refresh(request, session, forceRefresh = true)
        assertNull(store.state(request).failureStatus)
        location.cancel(true)
        val unchanged = interrupted.join()
        assertEquals(WeatherStatus.FRESH, unchanged.status)
        assertEquals(saved, unchanged.snapshot)
        assertNull(unchanged.failureStatus)
        assertFalse(store.shouldRetry(request))
        assertEquals(1, calls)
        location = CompletableFuture.completedFuture(first)
        val failure = store.refresh(request, session, forceRefresh = true).join()
        assertEquals(WeatherStatus.UNAVAILABLE, failure.failureStatus)
        assertTrue(store.shouldRetry(request))
        location = CompletableFuture()
        val pending = store.refresh(request, session, forceRefresh = true)
        assertEquals(WeatherStatus.LOADING, store.state(request).status)
        assertEquals(WeatherStatus.UNAVAILABLE, store.state(request).failureStatus)
        assertFalse(store.shouldRetry(request))
        session.close()
        location.cancel(true)
        val restored = pending.join()
        assertEquals(saved, restored.snapshot)
        assertEquals(failure.status, restored.status)
        assertEquals(failure.failureStatus, restored.failureStatus)
        assertTrue(store.shouldRetry(request))
        assertEquals(2, calls)
    }

    @Test
    fun lateResultsRemainAssociatedWithTheirCapturedPlace() {
        val queue = Queue()
        val store =
            store(
                queue,
                weatherClient { url -> response(if (url.contains("latitude=51.")) 5.0 else 20.0) },
            )
        val settings = WidgetSettings().apply {
            weatherEnabled = true
            weatherUseDevice = false
            weatherPlace = first.encode()
        }
        val old = store.refresh(WeatherRequest.capture(settings), null)
        settings.weatherPlace = second.encode()
        val request = WeatherRequest.capture(settings)
        val current = store.refresh(request, null)
        queue.last()
        assertEquals(20.0, current.join().snapshot!!.temperatureC, 0.0)
        queue.next()
        assertEquals(5.0, old.join().snapshot!!.temperatureC, 0.0)
        assertEquals(second, store.state(request).place)
        assertEquals(20.0, store.state(request).snapshot!!.temperatureC, 0.0)
    }

    @Test
    fun transientFailureRetainsUsableWeatherAndRequestsBackoff() {
        var fail = false
        val store =
            store(
                Executor { it.run() },
                weatherClient { if (fail) throw IOException("Unavailable") else response() },
            )
        val settings = manual(first)
        store.refresh(settings, null).join()
        time += WeatherSnapshot.REFRESH_INTERVAL
        fail = true
        val stale = store.refresh(settings, null).join()
        assertEquals(WeatherStatus.STALE, stale.status)
        assertEquals(12.5, stale.snapshot!!.temperatureC, 0.0)
        assertTrue(store.shouldRetry(settings))
        assertEquals(WeatherStatus.UNAVAILABLE, store.refresh(manual(null), null).join().status)
        assertFalse(store.shouldRetry(manual(null)))
    }

    @Test
    fun pendingRefreshKeepsUsableWeatherWhileReportingLoading() {
        val queue = Queue()
        val store = store(queue, weatherClient { response() })
        val settings = manual(first)
        val initial = store.refresh(settings, null)
        queue.next()
        assertEquals(WeatherStatus.FRESH, initial.join().status)
        time += WeatherSnapshot.REFRESH_INTERVAL
        val refresh = store.refresh(settings, null)
        val loading = store.state(settings)
        assertEquals(WeatherStatus.LOADING, loading.status)
        assertEquals(12.5, loading.snapshot!!.temperatureC, 0.0)
        assertFalse(refresh.isDone)
        queue.next()
        assertEquals(WeatherStatus.FRESH, refresh.join().status)
    }

    @Test
    fun permanentHttpFailuresPreserveWeatherWithoutTransientBackoff() {
        var status = 200
        val store =
            store(
                Executor { it.run() },
                weatherClient {
                    WeatherClient.requireSuccess(status)
                    response()
                },
            )
        val settings = manual(first)
        val saved = store.refresh(settings, null).join().snapshot
        assertNotNull(saved)
        time += WeatherSnapshot.REFRESH_INTERVAL
        for ((code, retry) in
            listOf(
                400 to false,
                401 to false,
                403 to false,
                408 to true,
                429 to true,
                503 to true,
            )) {
            status = code
            val state = store.refresh(settings, null).join()
            assertEquals(WeatherStatus.STALE, state.status)
            assertEquals(saved, state.snapshot)
            assertEquals("HTTP $code", retry, store.shouldRetry(settings))
        }
    }

    @Test
    fun permissionFailuresPreserveCacheWithoutRetryingOrCallingTheClient() {
        var permission = true
        var calls = 0
        val store =
            WeatherStore(
                context,
                Executor { it.run() },
                weatherClient {
                    calls++
                    response()
                },
                now = { time },
                locate = { CompletableFuture.completedFuture(first) },
                cachedLocation = { first },
                permission = { permission },
            )
        val settings = WeatherRequest.capture(WidgetSettings().apply { weatherEnabled = true })
        store.refresh(settings, foregroundSession()).join()
        permission = false
        val value = store.refresh(settings, null, forceRefresh = true).join()
        assertEquals(WeatherStatus.PERMISSION_REQUIRED, value.status)
        assertNotNull(value.snapshot)
        assertEquals(1, calls)
        assertFalse(store.shouldRetry(settings))
    }

    @Test
    fun durableCacheRestoresOnlyValidBoundedEntriesOutsidePreferences() {
        val settings = manual(first)
        val store = store(Executor { it.run() }, weatherClient { response() })
        store.refresh(settings, null).join()
        val loaded =
            store(
                Executor { fail("Cache lookup must not schedule work") },
                weatherClient {
                    fail("No HTTP")
                    response()
                },
            )
        assertEquals(WeatherStatus.FRESH, loaded.state(settings).status)
        val saved = JSONObject(File(directory, "weather_cache.json").readText())
        assertEquals("open-meteo", saved.getString("provider"))
        val entries = saved.getJSONArray("entries")
        assertEquals(time, entries.getJSONObject(0).getLong("validAt"))
        entries.getJSONObject(0).put("fetchedAt", time + 1000)
        File(directory, "weather_cache.json").writeText(saved.toString())
        val invalid = store(Executor { it.run() }, weatherClient { response() })
        assertNull(invalid.state(settings).snapshot)
        assertFalse(context.getSharedPreferences("noonlet_widgets", 0).contains("weather_cache"))
    }

    @Test
    fun cachesWithoutTheCurrentProviderMarkerAreIgnoredBeforeTheFirstFetch() {
        val file = File(directory, "weather_cache.json")
        val entries =
            JSONArray()
                .put(
                    JSONObject()
                        .put("place", first.encode())
                        .put("temperatureC", -7.0)
                        .put("weatherCode", 2)
                        .put("isDay", true)
                        .put("validAt", time)
                        .put("fetchedAt", time),
                )
        for (saved in
            listOf(
                entries.toString(),
                JSONObject().put("provider", "other").put("entries", entries).toString(),
                "{",
                JSONObject().put("provider", "open-meteo").put("entries", "invalid").toString(),
            )) {
            file.writeText(saved)
            val queue = Queue()
            var calls = 0
            val store =
                store(
                    queue,
                    weatherClient {
                        calls++
                        response()
                    },
                )
            val settings = manual(first)
            assertNull(store.state(settings).snapshot)
            assertFalse(file.exists())
            assertTrue(queue.work.isEmpty())
            val result = store.refresh(settings, null)
            assertEquals(1, queue.work.size)
            queue.next()
            assertEquals(1, calls)
            assertEquals(12.5, result.join().snapshot!!.temperatureC, 0.0)
            assertEquals(time, result.join().snapshot!!.validAt)
            assertEquals("open-meteo", JSONObject(file.readText()).getString("provider"))
        }
    }

    @Test
    fun aLaterFetchCannotReplaceANewerValidTime() {
        var validTime = time
        var temperature = 12.5
        val store = store(Executor { it.run() }, weatherClient { response(temperature, validTime) })
        val settings = manual(first)
        val initial = store.refresh(settings, null).join().snapshot!!
        time += 5 * 60_000
        validTime = initial.validAt - 15 * 60_000
        temperature = 7.0
        val older = store.refresh(settings, null, forceRefresh = true).join().snapshot!!
        assertEquals(initial, older)
        val saved = JSONObject(File(directory, "weather_cache.json").readText())
            .getJSONArray("entries").getJSONObject(0)
        assertEquals(initial.validAt, saved.getLong("validAt"))
        assertEquals(initial.temperatureC, saved.getDouble("temperatureC"), 0.0)

        validTime = initial.validAt
        temperature = 13.0
        val corrected = store.refresh(settings, null, forceRefresh = true).join().snapshot!!
        assertEquals(13.0, corrected.temperatureC, 0.0)
        assertEquals(initial.validAt, corrected.validAt)
        assertEquals(time, corrected.fetchedAt)
    }

    @Test
    fun cacheRecordsRequireAValidTimeBeforeTheyCanBeRestored() {
        val file = File(directory, "weather_cache.json")
        for (validTime in listOf<Any?>(null, JSONObject.NULL, "invalid", 1.5, time + 1)) {
            val entry = JSONObject()
                .put("place", first.encode()).put("temperatureC", 12.5)
                .put("weatherCode", 2).put("isDay", true).put("fetchedAt", time)
            if (validTime != null) entry.put("validAt", validTime)
            file.writeText(JSONObject().put("provider", "open-meteo")
                .put("entries", JSONArray().put(entry)).toString())
            val restored = store(Executor { fail("Cache lookup cannot schedule work") },
                weatherClient { fail("Cache lookup cannot request HTTP"); response() })
            assertNull(restored.state(manual(first)).snapshot)
            assertFalse(file.exists())
        }
    }

    private fun foregroundSession() =
        WeatherLocation.openForeground(context).also { sessions += it }

    private fun manual(place: WeatherPlace?) = WeatherRequest(true, false, place?.encode().orEmpty())

    private fun store(executor: Executor, client: WeatherClient) =
        WeatherStore(
            context,
            executor,
            client,
            now = { time },
            cachedLocation = { null },
            permission = { true },
        )

    private fun response(value: Double = 12.5, validTime: Long = time): String =
        JSONObject()
            .put("timezone", "GMT")
            .put("utc_offset_seconds", 0)
            .put("current_units", JSONObject().put("time", "unixtime")
                .put("interval", "seconds").put("temperature_2m", "°C")
                .put("weather_code", "wmo code").put("is_day", ""))
            .put(
                "current",
                JSONObject().put("time", validTime / 1000).put("interval", 900)
                    .put("temperature_2m", value).put("weather_code", 2).put("is_day", 1),
            )
            .toString()
}

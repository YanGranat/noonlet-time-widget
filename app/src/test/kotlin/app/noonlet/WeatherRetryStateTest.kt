package app.noonlet

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36], shadows = [ReplacingAtomicFileShadow::class])
class WeatherRetryStateTest {
    @Test fun serverDelaySurvivesStoreRecreationAndAppliesToOtherPlacesAndForcedRefreshes() {
        val context = isolated()
        var time = 1_860_000_000_000L
        val until = time + 120_000
        var calls = 0
        val client = weatherClient {
            calls++
            if (calls == 2) throw WeatherRetry(until)
            response(time)
        }
        fun store() = WeatherStore(context, Executor { it.run() }, client, now = { time })
        val request = request()
        val first = store()
        assertEquals(WeatherStatus.FRESH, first.refresh(request).join().status)
        val old = first.state(request).snapshot
        assertEquals(WeatherStatus.STALE, first.refresh(request, forceRefresh = true).join().status)
        assertEquals(old, first.state(request).snapshot)
        assertTrue(first.shouldRetry(request))
        val other = request.copy(encodedPlace = WeatherPlace("South Bay", 36_000_000, 11_000_000).encode())
        first.refresh(other, forceRefresh = true).join()
        assertEquals(2, calls)
        assertEquals(120_000L, first.retryDelay())
        val restored = store()
        restored.refresh(other, forceRefresh = true).join()
        assertEquals(2, calls)
        assertTrue(restored.shouldRetry(other))
        time = until
        assertEquals(0L, restored.retryDelay())
        assertEquals(WeatherStatus.FRESH, restored.refresh(request, forceRefresh = true).join().status)
        assertEquals(3, calls)
        assertEquals(time, restored.state(request).snapshot!!.fetchedAt)
        context.noBackupFilesDir.deleteRecursively()
    }

    @Test fun retryingANetworkFailureFetchesDataInsteadOfClearingTheErrorWithACachedSnapshot() {
        val context = isolated()
        val time = 1_860_000_000_000L
        var calls = 0
        val client = weatherClient {
            calls++
            if (calls == 2) throw IOException("Network unavailable")
            response(time)
        }
        val store = WeatherStore(context, Executor { it.run() }, client, now = { time })
        val request = request()
        store.refresh(request).join()
        assertEquals(WeatherStatus.STALE, store.refresh(request, forceRefresh = true).join().status)
        assertEquals(WeatherStatus.FRESH, store.refresh(request).join().status)
        assertEquals(3, calls)
        context.noBackupFilesDir.deleteRecursively()
    }

    private fun isolated(): Context {
        val base = RuntimeEnvironment.getApplication() as Context
        val directory = File(base.cacheDir, "weather-retry-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(base) { override fun getNoBackupFilesDir() = directory }
    }

    private fun request() = WeatherRequest(true, false, WeatherPlace("East Bay", 35_000_000, 10_000_000).encode())

    private fun response(time: Long) = """{"timezone":"GMT","utc_offset_seconds":0,
        "current_units":{"time":"unixtime","interval":"seconds","temperature_2m":"°C","weather_code":"wmo code","is_day":""},
        "current":{"time":${time / 1000},"interval":900,"temperature_2m":14,"weather_code":2,"is_day":1}}"""
}

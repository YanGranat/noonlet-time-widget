package app.noonlet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.roundToInt

object WeatherLocation {
    internal val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "weather-location").apply { isDaemon = true }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    // File operations acquire diskLock before inspecting state under lock.
    private val diskLock = Any()
    private var directory: File? = null
    private var remembered: WeatherPlace? = null
    private class ForegroundGeneration(val directory: File) {
        val sessions = mutableSetOf<ForegroundSession>()
        var epoch = Any()
    }

    internal class ForegroundSession internal constructor(
        internal val directory: File,
        internal val generation: Any,
    ) : AutoCloseable {
        internal val isActive: Boolean
            get() = synchronized(lock) {
                foreground[directory]?.sessions?.contains(this) == true
            }

        override fun close() {
            val cancelled = synchronized(lock) {
                val current = foreground[directory] ?: return
                if (!current.sessions.remove(this) || current.sessions.isNotEmpty()) return
                foreground.remove(directory)
                pending[directory]?.takeIf { it.generation === current }?.result
            }
            cancelled?.cancel(true)
        }
    }

    private class Acquisition(
        val generation: ForegroundGeneration,
        val result: CompletableFuture<WeatherPlace>,
    ) {
        val epoch = generation.epoch
    }

    private val foreground = mutableMapOf<File, ForegroundGeneration>()
    private val pending = mutableMapOf<File, Acquisition>()
    private val erasures = mutableMapOf<File, CompletableFuture<Void>>()

    internal fun openForeground(context: Context): ForegroundSession {
        val locationDirectory = context.applicationContext.noBackupFilesDir
        return synchronized(lock) {
            val generation = foreground.getOrPut(locationDirectory) {
                ForegroundGeneration(locationDirectory)
            }
            ForegroundSession(locationDirectory, generation).also { generation.sessions += it }
        }
    }

    fun hasForegroundPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    internal fun cached(context: Context): WeatherPlace? {
        val target = context.applicationContext.noBackupFilesDir
        synchronized(lock) {
            if (erasures.containsKey(target)) return null
            if (directory == target) return remembered
        }
        synchronized(diskLock) {
            synchronized(lock) {
                if (erasures.containsKey(target)) return null
                if (directory == target) return remembered
            }
            val loaded = runCatching {
                val file = File(target, "weather_location.json")
                if (!file.isFile || file.length() > 2048) null
                else WeatherPlace.decode(
                    AtomicFile(file).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() },
                )
            }.getOrNull()
            return synchronized(lock) {
                if (erasures.containsKey(target)) return null
                if (directory == target) return remembered
                directory = target
                remembered = loaded
                loaded
            }
        }
    }

    internal fun clearRemembered(context: Context): CompletableFuture<Void> {
        val target = context.applicationContext.noBackupFilesDir
        val result: CompletableFuture<Void>
        val cancelled: CompletableFuture<WeatherPlace>?
        synchronized(lock) {
            erasures[target]?.let { return it.thenApply { value -> value } }
            result = CompletableFuture()
            erasures[target] = result
            foreground[target]?.epoch = Any()
            cancelled = pending.remove(target)?.result
            directory = target
            remembered = null
        }
        cancelled?.cancel(true)
        try {
            worker.execute {
                val error = runCatching {
                    synchronized(diskLock) {
                        val file = File(target, "weather_location.json")
                        AtomicFile(file).delete()
                        val variants = listOf(file, File(file.path + ".bak"), File(file.path + ".new"))
                        variants.filter { it.exists() }.forEach { it.delete() }
                        check(variants.none { it.exists() }) {
                            "Device location could not be cleared"
                        }
                    }
                }.exceptionOrNull()
                synchronized(lock) {
                    if (erasures[target] === result) erasures.remove(target)
                }
                if (error == null) result.complete(null) else result.completeExceptionally(error)
            }
        } catch (error: RuntimeException) {
            synchronized(lock) {
                if (erasures[target] === result) erasures.remove(target)
            }
            result.completeExceptionally(error)
        }
        return result.thenApply { it }
    }

    internal fun resolve(
        context: Context,
        session: ForegroundSession? = null,
    ): CompletableFuture<WeatherPlace> {
        val app = context.applicationContext
        if (!hasForegroundPermission(app)) return failed(WeatherStatus.PERMISSION_REQUIRED)
        val cachedPlace = cached(app)
        synchronized(lock) {
            erasures[app.noBackupFilesDir]?.let {
                return it.handle { _, _ -> null }.thenCompose { resolve(app, session) }
            }
            val previous = if (directory == app.noBackupFilesDir) remembered else cachedPlace
            if (session == null)
                return previous?.let { CompletableFuture.completedFuture(it) }
                    ?: failed(WeatherStatus.UNAVAILABLE)
            val generation = foreground[app.noBackupFilesDir]
            if (generation == null || generation !== session.generation ||
                !generation.sessions.contains(session)) {
                return CompletableFuture<WeatherPlace>().apply { cancel(false) }
            }
            pending[app.noBackupFilesDir]?.takeIf { it.generation === generation }
                ?.let { return it.result }
            val result = CompletableFuture<WeatherPlace>()
            val acquisition = Acquisition(generation, result)
            pending[app.noBackupFilesDir] = acquisition
            result.whenComplete { _, _ ->
                synchronized(lock) {
                    if (pending[app.noBackupFilesDir] === acquisition)
                        pending.remove(app.noBackupFilesDir)
                }
            }
            worker.execute { locate(app, previous, acquisition) }
            return result
        }
    }

    private fun active(acquisition: Acquisition): Boolean =
        !acquisition.result.isDone &&
            foreground[acquisition.generation.directory] === acquisition.generation &&
            acquisition.generation.epoch === acquisition.epoch &&
            acquisition.generation.sessions.isNotEmpty()

    @android.annotation.SuppressLint("MissingPermission")
    private fun locate(
        context: Context,
        previous: WeatherPlace?,
        acquisition: Acquisition,
    ) {
        val result = acquisition.result
        var immediate: Location? = null
        var fallback: WeatherPlace? = null
        var failure: WeatherStatus? = null
        if (!synchronized(lock) { active(acquisition) }) return
        try {
            val manager = context.getSystemService(LocationManager::class.java)
                ?: throw WeatherFailure(WeatherStatus.UNAVAILABLE)
            if (!enabled(manager)) throw WeatherFailure(WeatherStatus.LOCATION_DISABLED)
            val fused =
                if (Build.VERSION.SDK_INT >= 31 &&
                    manager.hasProvider(LocationManager.FUSED_PROVIDER) &&
                    manager.isProviderEnabled(LocationManager.FUSED_PROVIDER))
                    LocationManager.FUSED_PROVIDER
                else null
            immediate = listOfNotNull(
                fused,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
                LocationManager.GPS_PROVIDER,
            ).mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }.filter(::recent).sortedWith(
                compareByDescending<Location> { it.elapsedRealtimeNanos }.thenBy {
                    if (it.hasAccuracy() && it.accuracy.isFinite() && it.accuracy >= 0)
                        it.accuracy
                    else Float.MAX_VALUE
                },
            ).firstOrNull()
            if (immediate == null) {
                val provider = fused ?: LocationManager.NETWORK_PROVIDER.takeIf {
                    manager.isProviderEnabled(it)
                }
                if (provider == null) {
                    fallback = previous ?: throw WeatherFailure(WeatherStatus.UNAVAILABLE)
                } else {
                    val cancellation = CancellationSignal()
                    val listener = if (Build.VERSION.SDK_INT < 30) object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            worker.execute { complete(context, location, acquisition) }
                        }

                        override fun onProviderEnabled(provider: String) = Unit

                        override fun onProviderDisabled(provider: String) {
                            fallbackOrFail(acquisition, previous,
                                if (enabled(manager)) WeatherStatus.UNAVAILABLE
                                else WeatherStatus.LOCATION_DISABLED)
                        }

                        @Deprecated("Platform callback")
                        override fun onStatusChanged(
                            provider: String?,
                            status: Int,
                            extras: android.os.Bundle?,
                        ) = Unit
                    } else null
                    val timeout = Runnable {
                        fallbackOrFail(acquisition, previous, WeatherStatus.UNAVAILABLE)
                    }
                    result.whenComplete { _, _ ->
                        handler.removeCallbacks(timeout)
                        cancellation.cancel()
                        listener?.let { runCatching { manager.removeUpdates(it) } }
                    }
                    handler.postDelayed(timeout, 10_000L)
                    if (result.isDone) {
                        handler.removeCallbacks(timeout)
                        return
                    }
                    if (Build.VERSION.SDK_INT >= 30) {
                        manager.getCurrentLocation(provider, cancellation, worker) { location ->
                            if (location == null)
                                fallbackOrFail(acquisition, previous, WeatherStatus.UNAVAILABLE)
                            else complete(context, location, acquisition)
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        manager.requestSingleUpdate(provider, requireNotNull(listener), Looper.getMainLooper())
                        if (result.isDone) manager.removeUpdates(listener)
                    }
                }
            }
        } catch (error: WeatherFailure) {
            failure = error.status
        } catch (_: SecurityException) {
            failure = WeatherStatus.PERMISSION_REQUIRED
        } catch (_: RuntimeException) {
            failure = WeatherStatus.UNAVAILABLE
        }
        immediate?.let { complete(context, it, acquisition); return }
        fallback?.let { result.complete(it); return }
        failure?.let { fallbackOrFail(acquisition, previous, it) }
    }

    private fun fallbackOrFail(acquisition: Acquisition, previous: WeatherPlace?, status: WeatherStatus) {
        if (previous != null && status != WeatherStatus.PERMISSION_REQUIRED &&
            synchronized(lock) { active(acquisition) }) {
            acquisition.result.complete(previous)
        } else acquisition.result.completeExceptionally(WeatherFailure(status))
    }

    private fun recent(location: Location): Boolean {
        val elapsed = android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        return valid(location) &&
            elapsed >= 0 &&
            elapsed <= WeatherSnapshot.REFRESH_INTERVAL * 1_000_000L
    }

    private fun valid(location: Location): Boolean =
        location.latitude.isFinite() &&
            location.longitude.isFinite() &&
            location.latitude in -90.0..90.0 &&
            location.longitude in -180.0..180.0

    private fun enabled(manager: LocationManager): Boolean =
        if (Build.VERSION.SDK_INT >= 28) manager.isLocationEnabled
        else
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER)

    private fun complete(
        context: Context,
        location: Location,
        acquisition: Acquisition,
    ) {
        val result = acquisition.result
        val failure = when {
            !hasForegroundPermission(context) -> WeatherStatus.PERMISSION_REQUIRED
            !recent(location) -> WeatherStatus.UNAVAILABLE
            else -> null
        }
        if (failure != null) {
            fallbackOrFail(acquisition,
                if (failure == WeatherStatus.UNAVAILABLE) cached(context) else null, failure)
            return
        }
        val accepted = WeatherPlace(
            "",
            (location.latitude * 1_000_000).roundToInt(),
            (location.longitude * 1_000_000).roundToInt(),
        )
        val target = context.noBackupFilesDir
        synchronized(diskLock) {
            synchronized(lock) {
                if (!active(acquisition)) return
                directory = target
                remembered = accepted
            }
            val file = AtomicFile(File(target, "weather_location.json"))
            runCatching {
                val output = file.startWrite()
                try {
                    output.write(accepted.encode().toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (error: Exception) {
                    file.failWrite(output)
                    throw error
                }
            }
        }
        result.complete(accepted)
    }

    private fun failed(status: WeatherStatus) =
        CompletableFuture<WeatherPlace>().apply {
            completeExceptionally(WeatherFailure(status))
        }
}

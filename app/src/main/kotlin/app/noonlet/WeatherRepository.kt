package app.noonlet

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

object WeatherRepository {
    private val worker: Executor =
        ThreadPoolExecutor(
            2,
            2,
            30,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(32),
            { task -> Thread(task, "weather-data").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    private val main = Handler(Looper.getMainLooper())
    private val storage = Executors.newSingleThreadExecutor { task ->
        Thread(task, "weather-storage").apply { isDaemon = true }
    }
    private val listeners: MutableSet<Runnable> = Collections.newSetFromMap(WeakHashMap())
    internal var store: WeatherStore? = null
    private var notificationPending = false

    private fun data(context: Context): WeatherStore =
        synchronized(this) {
            val app = context.applicationContext
            val current = store
            if (current != null && current.directory == app.noBackupFilesDir) current
            else WeatherStore(app, worker, changed = ::notifyChanged, eraser = storage).also { store = it }
        }

    internal fun state(context: Context, request: WeatherRequest): WeatherState =
        data(context).state(request)

    internal fun refresh(
        context: Context,
        request: WeatherRequest,
        foreground: WeatherLocation.ForegroundSession? = null,
        forceRefresh: Boolean = false,
    ): CompletableFuture<WeatherState> = data(context).refresh(request, foreground, forceRefresh)

    internal fun clearForecastCache(context: Context): CompletableFuture<Void> =
        data(context).clearForecastCache()

    internal fun clearDeviceLocation(context: Context): CompletableFuture<Void> =
        data(context).clearDeviceLocation()

    internal fun erasureRevision(context: Context): Long = data(context).erasureRevision()

    fun search(query: String, language: String): CompletableFuture<List<WeatherPlace>> =
        try {
            CompletableFuture.supplyAsync({ WeatherClient().search(query, language) }, worker)
        } catch (error: RuntimeException) {
            CompletableFuture<List<WeatherPlace>>().apply { completeExceptionally(error) }
        }

    fun observe(listener: Runnable) {
        synchronized(listeners) { listeners += listener }
    }

    fun removeObserver(listener: Runnable) {
        synchronized(listeners) { listeners -= listener }
    }

    internal fun shouldRetry(context: Context, request: WeatherRequest): Boolean =
        data(context).shouldRetry(request)

    internal fun notifyChanged() {
        synchronized(listeners) {
            if (notificationPending) return
            notificationPending = true
        }
        main.post {
            val current =
                synchronized(listeners) {
                    notificationPending = false
                    listeners.toTypedArray()
                }
            current.forEach(Runnable::run)
        }
    }
}

internal class WeatherStore(
    private val context: Context,
    private val executor: Executor,
    private val client: WeatherClient = WeatherClient(),
    private val now: () -> Long = System::currentTimeMillis,
    private val locate: (WeatherLocation.ForegroundSession?) -> CompletableFuture<WeatherPlace> = {
        WeatherLocation.resolve(context, it)
    },
    private val cachedLocation: () -> WeatherPlace? = { WeatherLocation.cached(context) },
    private val permission: () -> Boolean = { WeatherLocation.hasForegroundPermission(context) },
    private val changed: () -> Unit = {},
    private val eraser: Executor = executor,
) {
    val directory: File = context.noBackupFilesDir
    private val lock = Any()
    private val diskLock = Any()
    private val cache = LinkedHashMap<String, Pair<WeatherPlace, WeatherSnapshot>>()

    private data class Failure(val status: WeatherStatus, val retry: Boolean)

    private val errors = linkedMapOf<String, Failure>()

    private class Refresh(
        val result: CompletableFuture<WeatherState>,
        var force: Boolean,
        val foregroundGeneration: Any?,
        val previousFailure: Failure?,
        val request: WeatherRequest,
        val epoch: Any,
    ) {
        var reusedCache = false
    }

    private val pending = mutableMapOf<String, Refresh>()
    private val pointRequests = mutableMapOf<String, CompletableFuture<WeatherSnapshot>>()
    private var loaded = false
    private var autoPlace: WeatherPlace? = null
    private var cacheEpoch = Any()

    private class Erasure(val forgetDevice: Boolean) {
        val result = CompletableFuture<Void>()
    }

    private var erasure: Erasure? = null
    private var erasureRevision = 0L

    fun erasureRevision(): Long = synchronized(lock) { erasureRevision }

    fun state(request: WeatherRequest): WeatherState =
        synchronized(lock) {
            if (!request.enabled) return WeatherState(status = WeatherStatus.UNAVAILABLE)
            load()
            val place = if (request.useDevice) autoPlace ?: cachedLocation() else request.place
            val time = now()
            val snapshot = place?.let { cache[it.key]?.second }?.takeIf { it.usable(time) }
            val status =
                when {
                    request.useDevice && !permission() -> WeatherStatus.PERMISSION_REQUIRED
                    pending.containsKey(request.key) -> WeatherStatus.LOADING
                    errors[request.key]?.status == WeatherStatus.PERMISSION_REQUIRED ->
                        WeatherStatus.PERMISSION_REQUIRED
                    errors[request.key]?.status == WeatherStatus.LOCATION_DISABLED ->
                        WeatherStatus.LOCATION_DISABLED
                    snapshot != null && errors.containsKey(request.key) -> WeatherStatus.STALE
                    snapshot != null && snapshot.fresh(time) -> WeatherStatus.FRESH
                    snapshot != null -> WeatherStatus.STALE
                    else -> WeatherStatus.UNAVAILABLE
                }
            val failure = errors[request.key] ?: pending[request.key]?.previousFailure
            WeatherState(snapshot, status, place, failure?.status)
        }

    fun refresh(
        request: WeatherRequest,
        foreground: WeatherLocation.ForegroundSession? = null,
        forceRefresh: Boolean = false,
    ): CompletableFuture<WeatherState> {
        if (!request.enabled)
            return CompletableFuture.completedFuture(
                WeatherState(status = WeatherStatus.UNAVAILABLE),
            )
        if (request.useDevice && foreground != null &&
            (!foreground.isActive || foreground.directory != directory))
            return CompletableFuture.completedFuture(state(request))
        val operation: Refresh
        synchronized(lock) {
            load()
            erasure?.let {
                val epoch = cacheEpoch
                return it.result.handle { _, _ -> null }.thenCompose {
                    continueRefresh(request, foreground, forceRefresh, epoch)
                }
            }
            pending[request.key]?.let { current ->
                if (request.useDevice && foreground != null &&
                    current.foregroundGeneration !== foreground.generation)
                    return current.result.handle { _, _ -> null }.thenCompose {
                        continueRefresh(request, foreground, forceRefresh, current.epoch)
                    }
                if (forceRefresh && current.reusedCache)
                    return current.result.thenCompose { continueRefresh(request, foreground, true, current.epoch) }
                current.force = current.force || forceRefresh
                return current.result.thenApply { value -> value }
            }
            val before = state(request)
            if (
                !forceRefresh &&
                    (!request.useDevice || foreground == null) &&
                    before.status == WeatherStatus.FRESH
            )
                return CompletableFuture.completedFuture(before)
            operation = Refresh(
                CompletableFuture(), forceRefresh,
                if (request.useDevice) foreground?.generation else null,
                errors[request.key],
                request, cacheEpoch,
            )
            pending[request.key] = operation
            errors.remove(request.key)
        }
        changed()
        val location = synchronized(lock) {
            if (operation.epoch !== cacheEpoch)
                CompletableFuture<WeatherPlace>().apply { cancel(false) }
            else if (request.useDevice) {
                if (!permission())
                    CompletableFuture<WeatherPlace>().apply {
                        completeExceptionally(WeatherFailure(WeatherStatus.PERMISSION_REQUIRED))
                    }
                else
                    runCatching { locate(foreground) }
                        .getOrElse { error ->
                            CompletableFuture<WeatherPlace>().apply { completeExceptionally(error) }
                        }
            } else
                request.place?.let { CompletableFuture.completedFuture(it) }
                    ?: CompletableFuture<WeatherPlace>().apply {
                        completeExceptionally(WeatherFailure(WeatherStatus.UNAVAILABLE))
                    }
        }
        val work = location.thenCompose { place ->
            synchronized(lock) {
                if (operation.epoch !== cacheEpoch)
                    return@thenCompose CompletableFuture<WeatherSnapshot>().apply { cancel(false) }
                if (request.useDevice) autoPlace = place
                fetch(place, operation)
            }
        }
        work.whenComplete { _, error ->
            synchronized(lock) {
                if (pending[request.key] !== operation) return@whenComplete
                pending.remove(request.key)
                val cancelled = generateSequence(error) { it.cause }
                    .any { it is CancellationException }
                if (cancelled) {
                    operation.previousFailure?.let { errors[request.key] = it }
                } else if (error != null) {
                    val cause =
                        generateSequence(error) { it.cause }
                            .filterIsInstance<WeatherFailure>()
                            .firstOrNull()
                    val retry = generateSequence(error) { it.cause }.any { it is IOException }
                    errors[request.key] = Failure(cause?.status ?: WeatherStatus.UNAVAILABLE, retry)
                    while (errors.size > 32) errors.remove(errors.keys.first())
                } else errors.remove(request.key)
            }
            operation.result.complete(state(request))
            changed()
        }
        return operation.result.thenApply { it }
    }

    private fun continueRefresh(
        request: WeatherRequest,
        foreground: WeatherLocation.ForegroundSession?,
        forceRefresh: Boolean,
        epoch: Any,
    ): CompletableFuture<WeatherState> = synchronized(lock) {
        if (epoch !== cacheEpoch) CompletableFuture.completedFuture(state(request))
        else refresh(request, foreground, forceRefresh)
    }

    fun clearForecastCache(): CompletableFuture<Void> = erase(false)

    fun clearDeviceLocation(): CompletableFuture<Void> = erase(true)

    private fun erase(forgetDevice: Boolean): CompletableFuture<Void> {
        val operation: Erasure
        val abandoned: List<Refresh>
        val requests: List<CompletableFuture<WeatherSnapshot>>
        synchronized(lock) {
            erasure?.takeIf { !forgetDevice || it.forgetDevice }?.let {
                return it.result.thenApply { value -> value }
            }
            cacheEpoch = Any()
            erasureRevision++
            cache.clear()
            errors.clear()
            loaded = true
            if (forgetDevice) autoPlace = null
            abandoned = pending.values.toList()
            pending.clear()
            requests = pointRequests.values.toList()
            pointRequests.clear()
            operation = Erasure(forgetDevice)
            erasure = operation
        }
        val location = if (forgetDevice) WeatherLocation.clearRemembered(context)
            else CompletableFuture.completedFuture<Void>(null)
        requests.forEach { it.cancel(false) }
        abandoned.forEach { it.result.complete(state(it.request)) }
        changed()
        val cache = try {
            CompletableFuture.runAsync({
                synchronized(diskLock) {
                    val file = File(directory, "weather_cache.json")
                    AtomicFile(file).delete()
                    val variants = listOf(file, File(file.path + ".bak"), File(file.path + ".new"))
                    variants.filter { it.exists() }.forEach { it.delete() }
                    check(variants.none { it.exists() }) {
                        "Weather cache could not be cleared"
                    }
                }
            }, eraser)
        } catch (error: RuntimeException) {
            CompletableFuture<Void>().apply { completeExceptionally(error) }
        }
        CompletableFuture.allOf(cache, location).whenComplete { _, error ->
            synchronized(lock) {
                if (erasure === operation) erasure = null
            }
            if (error == null) operation.result.complete(null)
            else operation.result.completeExceptionally(error)
            changed()
        }
        return operation.result.thenApply { it }
    }

    fun shouldRetry(request: WeatherRequest): Boolean =
        synchronized(lock) { request.enabled && errors[request.key]?.retry == true }

    private fun fetch(place: WeatherPlace, operation: Refresh): CompletableFuture<WeatherSnapshot> {
        val result: CompletableFuture<WeatherSnapshot>
        synchronized(lock) {
            cache[place.key]
                ?.second
                ?.takeIf { !operation.force && it.fresh(now()) }
                ?.let {
                    operation.reusedCache = true
                    return CompletableFuture.completedFuture(it)
                }
            pointRequests[place.key]?.let {
                return it
            }
            result = CompletableFuture()
            pointRequests[place.key] = result
        }
        try {
            executor.execute {
                try {
                    synchronized(lock) {
                        if (operation.epoch !== cacheEpoch) return@execute
                    }
                    val snapshot = client.current(place, now)
                    val accepted = synchronized(lock) {
                        if (operation.epoch !== cacheEpoch) return@synchronized false
                        val current = cache[place.key]?.second
                        val newer =
                            current == null || snapshot.validAt > current.validAt ||
                                snapshot.validAt == current.validAt &&
                                    snapshot.fetchedAt >= current.fetchedAt
                        if (newer) {
                            cache[place.key] = place to snapshot
                            trim()
                        }
                        true
                    }
                    if (accepted) persist(operation.epoch)
                    result.complete(snapshot)
                } catch (error: Exception) {
                    result.completeExceptionally(error)
                } finally {
                    synchronized(lock) {
                        if (pointRequests[place.key] === result) pointRequests.remove(place.key)
                    }
                }
            }
        } catch (error: RuntimeException) {
            synchronized(lock) { pointRequests.remove(place.key) }
            result.completeExceptionally(error)
        }
        return result
    }

    private fun load() {
        if (loaded) return
        loaded = true
        runCatching {
            val file = File(directory, "weather_cache.json")
            if (!file.isFile || file.length() > 64 * 1024) return
            val atomic = AtomicFile(file)
            val text = atomic.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
            val saved = runCatching { JSONObject(text) }.getOrNull()
            val entries = saved?.optJSONArray("entries")
            if (saved?.optString("provider") != WeatherClient.SOURCE || entries == null) {
                atomic.delete()
                return
            }
            for (index in 0 until minOf(entries.length(), 16)) runCatching {
                val data = entries.getJSONObject(index)
                val place = WeatherPlace.decode(data.getString("place")) ?: return@runCatching
                val snapshot =
                    WeatherSnapshot(
                        data.number("temperatureC"),
                        Math.toIntExact(data.wholeNumber("weatherCode")),
                        data.getBoolean("isDay"),
                        data.wholeNumber("validAt"),
                        data.wholeNumber("fetchedAt"),
                        if (data.has("serverTimeAtFetch")) data.wholeNumber("serverTimeAtFetch")
                        else data.wholeNumber("fetchedAt"),
                    )
                if (snapshot.usable(now())) cache[place.key] = place to snapshot
            }
            if (entries.length() > 0 && cache.isEmpty()) atomic.delete()
        }
    }

    private fun trim() {
        val time = now()
        cache.entries.removeAll { !it.value.second.usable(time) }
        while (cache.size > 16) cache.remove(cache.minBy { it.value.second.fetchedAt }.key)
    }

    private fun persist(epoch: Any) {
        synchronized(diskLock) {
            val entries =
                synchronized(lock) {
                    if (epoch !== cacheEpoch) return
                    JSONObject()
                        .put("provider", WeatherClient.SOURCE)
                        .put(
                            "entries",
                            JSONArray().apply {
                                cache.values.forEach { (place, snapshot) ->
                                    put(
                                        JSONObject()
                                            .put("place", place.encode())
                                            .put("temperatureC", snapshot.temperatureC)
                                            .put("weatherCode", snapshot.weatherCode)
                                            .put("isDay", snapshot.isDay)
                                            .put("validAt", snapshot.validAt)
                                            .put("fetchedAt", snapshot.fetchedAt)
                                            .put("serverTimeAtFetch", snapshot.serverTimeAtFetch),
                                    )
                                }
                            },
                        )
                        .toString()
                        .toByteArray(Charsets.UTF_8)
                }
            val file = AtomicFile(File(directory, "weather_cache.json"))
            runCatching {
                val output = file.startWrite()
                try {
                    output.write(entries)
                    file.finishWrite(output)
                } catch (error: Exception) {
                    file.failWrite(output)
                    throw error
                }
            }
            Unit
        }
    }
}

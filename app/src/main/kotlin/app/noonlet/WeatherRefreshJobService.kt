package app.noonlet

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors

object WeatherRefreshScheduler {
    internal const val PERIODIC_JOB = 4101
    internal const val IMMEDIATE_JOB = 4102
    private const val REFRESH_FLEX = 5 * 60_000L
    private var signature = ""
    private val publisher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "weather-widgets").apply { isDaemon = true }
    }
    private val publicationLock = Any()
    private var publication: CompletableFuture<Void>? = null
    private var publishAgain = false

    internal fun configurations(context: Context): List<Pair<Int, WeatherRequest>> {
        val bound = BaseWidgetProvider.boundIds(context, false)
        return WidgetSettingsStore.configuredIds(context, bound, false).asIterable().mapNotNull { id
            ->
            WeatherRequest.capture(WidgetSettingsStore.load(context, id, false))
                .takeIf { it.enabled }
                ?.let { id to it }
        }
    }

    @Synchronized
    fun reconcile(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val settings = configurations(context)
        if (settings.isEmpty()) {
            scheduler.cancel(PERIODIC_JOB)
            scheduler.cancel(IMMEDIATE_JOB)
            signature = ""
            return
        }
        val current =
            settings
                .sortedBy { it.first }
                .joinToString("|") { (id, value) ->
                    "$id:${value.useDevice}:${value.encodedPlace}"
                }
        val periodic = scheduler.getPendingJob(PERIODIC_JOB)
        val missing = periodic == null
        if (
            periodic == null ||
                periodic.intervalMillis != WeatherSnapshot.REFRESH_INTERVAL ||
                periodic.flexMillis != REFRESH_FLEX
        ) {
            scheduler.schedule(
                builder(context, PERIODIC_JOB)
                    .setPeriodic(WeatherSnapshot.REFRESH_INTERVAL, REFRESH_FLEX)
                    .setPersisted(true)
                    .build(),
            )
        }
        if (missing || current != signature) {
            signature = current
            if (settings.any { WeatherRepository.state(context, it.second).status != WeatherStatus.FRESH })
                scheduler.schedule(builder(context, IMMEDIATE_JOB).build())
        }
    }

    fun updateWidgets(context: Context): CompletableFuture<Void> =
        synchronized(publicationLock) {
            publication?.let {
                publishAgain = true
                return it.thenApply { value -> value }
            }
            val result = CompletableFuture<Void>()
            publication = result
            val app = context.applicationContext
            publisher.execute {
                try {
                    while (true) {
                        synchronized(publicationLock) { publishAgain = false }
                        configurations(app).forEach { (id, _) ->
                            BaseWidgetProvider.updateWidget(app, id, false)
                        }
                        val repeat =
                            synchronized(publicationLock) {
                                if (publishAgain) true
                                else {
                                    publication = null
                                    false
                                }
                            }
                        if (!repeat) break
                    }
                    result.complete(null)
                } catch (error: Exception) {
                    synchronized(publicationLock) { if (publication === result) publication = null }
                    result.completeExceptionally(error)
                }
            }
            result.thenApply { it }
        }

    internal fun refreshPlacedWidgets(
        context: Context,
        foreground: WeatherLocation.ForegroundSession,
    ): CompletableFuture<Void> {
        val app = context.applicationContext
        val changes = configurations(app).map { (_, request) ->
            val before = WeatherRepository.state(app, request)
            WeatherRepository.refresh(app, request, foreground).thenApply {
                it != before || before.snapshot == null
            }
        }
        return CompletableFuture.allOf(*changes.toTypedArray()).thenCompose {
            if (changes.any { it.getNow(false) }) updateWidgets(app)
            else CompletableFuture.completedFuture<Void>(null)
        }
    }

    internal fun refreshAfterImport(context: Context): CompletableFuture<Void> {
        val app = context.applicationContext
        val placed = configurations(app)
        val pending = if (placed.isEmpty())
            ImportedWidgetConfigurations.all(app).firstOrNull { !it.world }?.configuration?.settings else null
        val editor = pending ?: WidgetSettingsStore.loadUnplaced(app, false)
        val requests = (placed.map { it.second } + WeatherRequest.capture(editor)).filter { it.enabled }.distinct()
        val updates = requests.map { WeatherRepository.refresh(app, it) }
        return CompletableFuture.allOf(*updates.toTypedArray()).thenCompose { updateWidgets(app) }
    }

    fun clearForecastCache(context: Context): CompletableFuture<Void> {
        val app = context.applicationContext
        return WeatherRepository.clearForecastCache(app).thenCompose { updateWidgets(app) }
    }

    fun erasureRevision(context: Context): Long = WeatherRepository.erasureRevision(context)

    fun clearDeviceLocation(context: Context): CompletableFuture<Void> {
        val app = context.applicationContext
        return WeatherRepository.clearDeviceLocation(app).thenCompose { updateWidgets(app) }
    }

    private fun builder(context: Context, id: Int) =
        JobInfo.Builder(id, ComponentName(context, WeatherRefreshJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
}

class WeatherRefreshJobService : JobService() {
    private class Run(
        val requests: List<WeatherRequest>,
        val futures: List<CompletableFuture<WeatherState>>,
    ) {
        val completion: CompletableFuture<Void> = CompletableFuture.allOf(*futures.toTypedArray())

        fun cancel() {
            futures.forEach { it.cancel(true) }
            completion.cancel(true)
        }
    }

    private val active = mutableMapOf<Int, Run>()
    private val main = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        val configurations = WeatherRefreshScheduler.configurations(this)
        if (configurations.isEmpty()) {
            WeatherRefreshScheduler.reconcile(this)
            return false
        }
        val requests = configurations.map { it.second }
        val run =
            Run(
                requests,
                requests.map {
                    WeatherRepository.refresh(
                        this,
                        it,
                        forceRefresh = params.jobId == WeatherRefreshScheduler.PERIODIC_JOB,
                    )
                },
            )
        active.put(params.jobId, run)?.cancel()
        run.completion.whenComplete { _, _ ->
            main.post {
                if (active[params.jobId] !== run) return@post
                val retry = run.requests.any { WeatherRepository.shouldRetry(this, it) }
                WeatherRefreshScheduler.updateWidgets(this).whenComplete { _, error ->
                    main.post {
                        if (active[params.jobId] === run) {
                            active.remove(params.jobId)
                            jobFinished(params, retry || error != null)
                        }
                    }
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        active.remove(params.jobId)?.cancel()
        return WeatherRefreshScheduler.configurations(this).isNotEmpty()
    }

    override fun onDestroy() {
        active.values.forEach(Run::cancel)
        active.clear()
        super.onDestroy()
    }
}

package app.noonlet

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLocationManager
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36])
class WeatherLocationTest {
    private lateinit var context: Context
    private lateinit var directory: File
    private val grants = mutableSetOf<String>()
    private val sessions = mutableListOf<WeatherLocation.ForegroundSession>()
    private var locationServiceReads = 0
    private var beforeLocationService: (() -> Unit)? = null

    @Before
    fun prepare() {
        val app = RuntimeEnvironment.getApplication() as Context
        directory = File(app.cacheDir, "weather-location-${UUID.randomUUID()}").apply { mkdirs() }
        context =
            object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this

                override fun getNoBackupFilesDir(): File = directory

                override fun getSystemService(name: String): Any? {
                    if (name == Context.LOCATION_SERVICE) {
                        locationServiceReads++
                        beforeLocationService?.invoke()
                    }
                    return super.getSystemService(name)
                }

                override fun checkSelfPermission(permission: String): Int =
                    if (permission in grants) PackageManager.PERMISSION_GRANTED
                    else PackageManager.PERMISSION_DENIED
            }
    }

    @After
    fun clean() {
        sessions.forEach { it.close() }
        drainWorker()
        directory.deleteRecursively()
    }

    @Test
    fun locationPermissionDoesNotReplaceAnActiveForegroundSession() {
        assertFalse(WeatherLocation.hasForegroundPermission(context))
        grants += Manifest.permission.ACCESS_COARSE_LOCATION
        assertTrue(WeatherLocation.hasForegroundPermission(context))
        val failure = assertThrows(Exception::class.java) {
            WeatherLocation.resolve(context).get(2, TimeUnit.SECONDS)
        }
        assertEquals(WeatherStatus.UNAVAILABLE, (failure.cause as WeatherFailure).status)
        assertEquals(0, locationServiceReads)
        val permissions = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty()
        assertFalse(Manifest.permission.ACCESS_BACKGROUND_LOCATION in permissions)
    }

    @Test
    fun unavailableForegroundLocationReusesTheSavedPointWithoutRewritingIt() {
        grants += Manifest.permission.ACCESS_COARSE_LOCATION
        val saved = WeatherPlace("", 43_200_000, 6_400_000)
        val file = File(directory, "weather_location.json").apply { writeText(saved.encode()) }
        val manager = context.getSystemService(LocationManager::class.java)
        Shadows.shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        Shadows.shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, false)
        if (android.os.Build.VERSION.SDK_INT >= 31)
            Shadows.shadowOf(manager).setProviderEnabled(LocationManager.FUSED_PROVIDER, false)
        assertEquals(saved, WeatherLocation.resolve(context, foreground()).get(2, TimeUnit.SECONDS))
        assertEquals(saved, WeatherPlace.decode(file.readText()))
        grants.clear()
        val failure = assertThrows(Exception::class.java) {
            WeatherLocation.resolve(context, foreground()).get(2, TimeUnit.SECONDS)
        }
        assertEquals(WeatherStatus.PERMISSION_REQUIRED, (failure.cause as WeatherFailure).status)
    }

    @Test
    fun backgroundRefreshUsesOnlyTheSavedPointEvenWithAnExistingLocationGrant() {
        grants += Manifest.permission.ACCESS_COARSE_LOCATION
        grants += Manifest.permission.ACCESS_BACKGROUND_LOCATION
        val saved = WeatherPlace("", 51_123_456, -1_654_321)
        File(directory, "weather_location.json").writeText(saved.encode())
        val manager = context.getSystemService(LocationManager::class.java)
        Shadows.shadowOf(manager).setLastKnownLocation(
            LocationManager.NETWORK_PROVIDER,
            fix(LocationManager.NETWORK_PROVIDER, 52.0, 0, 1000f),
        )
        locationServiceReads = 0
        assertEquals(saved, WeatherLocation.resolve(context).get(2, TimeUnit.SECONDS))
        assertEquals(0, locationServiceReads)
        assertEquals(saved, WeatherPlace.decode(File(directory, "weather_location.json").readText()))
        grants.clear()
        val error =
            assertThrows(Exception::class.java) { WeatherLocation.resolve(context).get() }
        assertTrue(error.cause is WeatherFailure)
        assertEquals(WeatherStatus.PERMISSION_REQUIRED, (error.cause as WeatherFailure).status)
    }

    @Test
    fun foregroundUsesARecentCoarseFixAndStoresItOutsideBackup() {
        grants += Manifest.permission.ACCESS_COARSE_LOCATION
        val manager = context.getSystemService(LocationManager::class.java)
        Shadows.shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val fix =
            Location(LocationManager.NETWORK_PROVIDER).apply {
                latitude = 51.123456
                longitude = -1.654321
                accuracy = 100f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
        Shadows.shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix)
        val place = WeatherLocation.resolve(context, foreground()).get(3, TimeUnit.SECONDS)
        assertEquals(51_123_456, place.latitudeE6)
        assertEquals(-1_654_321, place.longitudeE6)
        assertEquals(
            place,
            WeatherPlace.decode(File(directory, "weather_location.json").readText()),
        )
    }

    @Test
    fun aNewerCoarseFixWinsOverAnOlderMoreAccurateFix() {
        grants += Manifest.permission.ACCESS_COARSE_LOCATION
        ShadowSystemClock.advanceBy(30, TimeUnit.MINUTES)
        val manager = context.getSystemService(LocationManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        shadow.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadow.setLastKnownLocation(
            LocationManager.GPS_PROVIDER,
            fix(LocationManager.GPS_PROVIDER, 50.0, 25, 20f),
        )
        shadow.setLastKnownLocation(
            LocationManager.NETWORK_PROVIDER,
            fix(LocationManager.NETWORK_PROVIDER, 51.0, 1, 2000f),
        )
        assertEquals(
            51_000_000,
            WeatherLocation.resolve(context, foreground()).get(3, TimeUnit.SECONDS).latitudeE6,
        )
    }

    @Test
    fun unknownAccuracyDoesNotWinATimestampTie() {
        grants += Manifest.permission.ACCESS_COARSE_LOCATION
        ShadowSystemClock.advanceBy(30, TimeUnit.MINUTES)
        val manager = context.getSystemService(LocationManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val unknown = fix(LocationManager.GPS_PROVIDER, 50.0, 1, 20f).apply { removeAccuracy() }
        shadow.setLastKnownLocation(LocationManager.GPS_PROVIDER, unknown)
        shadow.setLastKnownLocation(
            LocationManager.NETWORK_PROVIDER,
            fix(LocationManager.NETWORK_PROVIDER, 51.0, 1, 2000f),
        )
        assertEquals(
            51_000_000,
            WeatherLocation.resolve(context, foreground()).get(3, TimeUnit.SECONDS).latitudeE6,
        )
    }

    @Test
    @Config(sdk = [34, 36])
    fun coarseCurrentRequestUsesFusedWhenNetworkIsUnavailable() {
        grants += Manifest.permission.ACCESS_COARSE_LOCATION
        val manager = context.getSystemService(LocationManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        shadow.setLocationEnabled(true)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, false)
        shadow.setProviderProperties(
            LocationManager.FUSED_PROVIDER,
            ShadowLocationManager.ProviderProperties(
                Criteria().apply {
                    accuracy = Criteria.ACCURACY_COARSE
                    powerRequirement = Criteria.POWER_LOW
                },
            ),
        )
        shadow.setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        val future = WeatherLocation.resolve(context, foreground())
        awaitRequest(shadow, LocationManager.FUSED_PROVIDER, future)
        shadow.simulateLocation(
            LocationManager.FUSED_PROVIDER,
            fix(LocationManager.FUSED_PROVIDER, 52.0, 0, 1000f),
        )
        assertEquals(52_000_000, future.get(3, TimeUnit.SECONDS).latitudeE6)
        assertTrue(shadow.getLocationRequests(LocationManager.NETWORK_PROVIDER).isEmpty())
    }

    @Test
    @Config(sdk = [34, 36])
    fun missingFusedProviderFallsBackToACoarseNetworkRequest() {
        grants += Manifest.permission.ACCESS_COARSE_LOCATION
        val manager = context.getSystemService(LocationManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        shadow.setLocationEnabled(true)
        shadow.removeProvider(LocationManager.FUSED_PROVIDER)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val future = WeatherLocation.resolve(context, foreground())
        awaitRequest(shadow, LocationManager.NETWORK_PROVIDER, future)
        shadow.simulateLocation(
            LocationManager.NETWORK_PROVIDER,
            fix(LocationManager.NETWORK_PROVIDER, 53.0, 0, 1000f),
        )
        assertEquals(53_000_000, future.get(3, TimeUnit.SECONDS).latitudeE6)
    }

    private fun fix(provider: String, latitude: Double, ageMinutes: Int, accuracy: Float) =
        Location(provider).apply {
            this.latitude = latitude
            longitude = -1.0
            this.accuracy = accuracy
            time = System.currentTimeMillis() - ageMinutes * 60_000L
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - ageMinutes * 60_000_000_000L
        }

    @Test
    @Config(sdk = [26])
    fun legacyCoarseOneShotReleasesCancelledListenersAndCompletesOnTheNextFix() {
        grants += Manifest.permission.ACCESS_COARSE_LOCATION
        val manager = context.getSystemService(LocationManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        shadow.setLocationMode(android.provider.Settings.Secure.LOCATION_MODE_BATTERY_SAVING)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val firstSession = foreground()
        val cancelled = WeatherLocation.resolve(context, firstSession)
        awaitRequest(shadow, LocationManager.NETWORK_PROVIDER, cancelled)
        val late = shadow.getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).single()
        firstSession.close()
        assertTrue(cancelled.isCancelled)
        assertTrue(shadow.getLocationRequests(LocationManager.NETWORK_PROVIDER).isEmpty())
        late.onLocationChanged(fix(LocationManager.NETWORK_PROVIDER, 52.0, 0, 1000f))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        drainWorker()
        assertNull(WeatherLocation.cached(context))
        assertFalse(File(directory, "weather_location.json").exists())
        manager.allProviders.forEach { shadow.setLastKnownLocation(it, null) }

        val completed = WeatherLocation.resolve(context, foreground())
        awaitRequest(shadow, LocationManager.NETWORK_PROVIDER, completed)
        late.onLocationChanged(fix(LocationManager.NETWORK_PROVIDER, 52.0, 0, 1000f))
        drainWorker()
        assertFalse(completed.isDone)
        assertNull(WeatherLocation.cached(context))
        assertFalse(File(directory, "weather_location.json").exists())
        shadow.simulateLocation(
            LocationManager.NETWORK_PROVIDER,
            fix(LocationManager.NETWORK_PROVIDER, 53.0, 0, 1000f),
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(53_000_000, completed.get(3, TimeUnit.SECONDS).latitudeE6)
        assertTrue(shadow.getLocationRequests(LocationManager.NETWORK_PROVIDER).isEmpty())
        assertEquals(53_000_000, WeatherLocation.cached(context)!!.latitudeE6)
    }

    @Test
    fun closingTheLastSessionBeforeAcquisitionStartsDoesNotReadTheLocationService() {
        grants += Manifest.permission.ACCESS_COARSE_LOCATION
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = worker()
        val blocked = worker.submit {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val session = foreground()
            val pending = WeatherLocation.resolve(context, session)
            session.close()
            assertTrue(pending.isCancelled)
            release.countDown()
            blocked.get(3, TimeUnit.SECONDS)
            drainWorker()
            assertEquals(0, locationServiceReads)
            assertNull(WeatherLocation.cached(context))
            assertFalse(File(directory, "weather_location.json").exists())
        } finally {
            release.countDown()
        }
    }

    @Test
    @Config(sdk = [26])
    fun closingOneSessionKeepsAnotherActiveAcquisitionAlive() {
        grants += Manifest.permission.ACCESS_COARSE_LOCATION
        val manager = context.getSystemService(LocationManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        shadow.setLocationMode(android.provider.Settings.Secure.LOCATION_MODE_BATTERY_SAVING)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val first = foreground()
        val second = foreground()
        val one = WeatherLocation.resolve(context, first)
        val two = WeatherLocation.resolve(context, second)
        awaitRequest(shadow, LocationManager.NETWORK_PROVIDER, two)
        first.close()
        assertFalse(one.isDone)
        assertFalse(two.isDone)
        assertFalse(shadow.getLocationRequests(LocationManager.NETWORK_PROVIDER).isEmpty())
        shadow.simulateLocation(
            LocationManager.NETWORK_PROVIDER,
            fix(LocationManager.NETWORK_PROVIDER, 54.0, 0, 1000f),
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(54_000_000, two.get(3, TimeUnit.SECONDS).latitudeE6)
        assertEquals(54_000_000, WeatherLocation.cached(context)!!.latitudeE6)
    }

    @Test
    fun erasingAQueuedAcquisitionKeepsTheSessionButPreventsLocationServiceAccess() {
        grants += Manifest.permission.ACCESS_COARSE_LOCATION
        val saved = WeatherPlace("", 51_000_000, -1_000_000)
        File(directory, "weather_location.json").writeText(saved.encode())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blocked = worker().submit {
            entered.countDown()
            check(release.await(3, TimeUnit.SECONDS))
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val session = foreground()
            val acquisition = WeatherLocation.resolve(context, session)
            val erased = WeatherLocation.clearRemembered(context)
            assertTrue(session.isActive)
            assertTrue(acquisition.isCancelled)
            assertNull(WeatherLocation.cached(context))
            assertFalse(erased.isDone)
            release.countDown()
            blocked.get(3, TimeUnit.SECONDS)
            erased.get(3, TimeUnit.SECONDS)
            assertEquals(0, locationServiceReads)
            assertFalse(File(directory, "weather_location.json").exists())
        } finally {
            release.countDown()
        }
    }

    @Test
    @Config(sdk = [26])
    fun erasingAnActiveOneShotRejectsLateFixesAndAllowsANewExplicitAcquisition() {
        grants += Manifest.permission.ACCESS_COARSE_LOCATION
        val manager = context.getSystemService(LocationManager::class.java)
        val shadow = Shadows.shadowOf(manager)
        shadow.setLocationMode(android.provider.Settings.Secure.LOCATION_MODE_BATTERY_SAVING)
        shadow.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
        val session = foreground()
        val previous = WeatherLocation.resolve(context, session)
        awaitRequest(shadow, LocationManager.NETWORK_PROVIDER, previous)
        val late = shadow.getLocationUpdateListeners(LocationManager.NETWORK_PROVIDER).single()

        WeatherLocation.clearRemembered(context).get(3, TimeUnit.SECONDS)
        assertTrue(session.isActive)
        assertTrue(previous.isCancelled)
        assertTrue(shadow.getLocationRequests(LocationManager.NETWORK_PROVIDER).isEmpty())
        late.onLocationChanged(fix(LocationManager.NETWORK_PROVIDER, 52.0, 0, 1000f))
        drainWorker()
        assertNull(WeatherLocation.cached(context))
        assertFalse(File(directory, "weather_location.json").exists())

        val next = WeatherLocation.resolve(context, session)
        awaitRequest(shadow, LocationManager.NETWORK_PROVIDER, next)
        late.onLocationChanged(fix(LocationManager.NETWORK_PROVIDER, 52.0, 0, 1000f))
        drainWorker()
        assertFalse(next.isDone)
        shadow.simulateLocation(LocationManager.NETWORK_PROVIDER,
            fix(LocationManager.NETWORK_PROVIDER, 53.0, 0, 1000f))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(53_000_000, next.get(3, TimeUnit.SECONDS).latitudeE6)
        assertEquals(53_000_000, WeatherLocation.cached(context)!!.latitudeE6)
    }

    @Test
    fun rememberedLocationCanBeErasedAfterPermissionIsRevoked() {
        val saved = WeatherPlace("", 51_000_000, -1_000_000)
        File(directory, "weather_location.json").writeText(saved.encode())
        assertEquals(saved, WeatherLocation.cached(context))
        File(directory, "weather_location.json.bak").writeText("backup")
        File(directory, "weather_location.json.new").writeText("unfinished")
        assertFalse(WeatherLocation.hasForegroundPermission(context))
        WeatherLocation.clearRemembered(context).get(3, TimeUnit.SECONDS)
        assertNull(WeatherLocation.cached(context))
        assertFalse(File(directory, "weather_location.json").exists())
        assertFalse(File(directory, "weather_location.json.bak").exists())
        assertFalse(File(directory, "weather_location.json.new").exists())
        assertEquals(0, locationServiceReads)
    }

    @Test
    fun aBlockedLocationServiceDoesNotBlockCachedReadsOrSessionClosure() {
        grants += Manifest.permission.ACCESS_COARSE_LOCATION
        val saved = WeatherPlace("", 48_000_000, 9_000_000)
        File(directory, "weather_location.json").writeText(saved.encode())
        assertEquals(saved, WeatherLocation.cached(context))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        beforeLocationService = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val session = foreground()
        val acquisition = WeatherLocation.resolve(context, session)
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(saved, CompletableFuture.supplyAsync { WeatherLocation.cached(context) }
                .get(1, TimeUnit.SECONDS))
            CompletableFuture.runAsync { session.close() }.get(1, TimeUnit.SECONDS)
            assertTrue(acquisition.isCancelled)
        } finally {
            release.countDown()
            beforeLocationService = null
            drainWorker()
        }
        assertEquals(saved, WeatherLocation.cached(context))
        assertEquals(saved, WeatherPlace.decode(File(directory, "weather_location.json").readText()))
    }

    private fun foreground() = WeatherLocation.openForeground(context).also { sessions += it }

    private fun worker(): ExecutorService = WeatherLocation.worker

    private fun drainWorker() {
        worker().submit {}.get(3, TimeUnit.SECONDS)
    }

    private fun awaitRequest(
        shadow: ShadowLocationManager,
        provider: String,
        future: java.util.concurrent.CompletableFuture<WeatherPlace>,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (
            shadow.getLocationRequests(provider).isEmpty() &&
                !future.isDone &&
                System.nanoTime() < deadline
        ) Thread.sleep(5)
        assertFalse(future.isDone)
        assertFalse(shadow.getLocationRequests(provider).isEmpty())
    }
}

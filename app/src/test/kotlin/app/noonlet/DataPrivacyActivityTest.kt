package app.noonlet

import android.content.Context
import android.content.DialogInterface
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
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
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
class DataPrivacyActivityTest {
    private lateinit var context: Context
    private lateinit var cache: File
    private var previousStore: WeatherStore? = null
    private val work = ArrayDeque<Runnable>()

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        previousStore = WeatherRepository.store
        AppLocales.setLanguage(context, "en")
        AppPreferences(context).setThemeMode(ThemeMode.LIGHT)
        cache = File(context.noBackupFilesDir, "weather_cache.json")
        cache.deleteRecursively()
        val store = WeatherStore(context, Executor { error("No network work is expected") },
            eraser = Executor { work.add(it) })
        WeatherRepository.store = store
        ShadowToast.reset()
    }

    @After
    fun close() {
        try {
            while (work.isNotEmpty()) work.removeFirst().run()
            WeatherRefreshScheduler.updateWidgets(context).get(10, TimeUnit.SECONDS)
            idle()
        } finally {
            WeatherRepository.store = previousStore
            cache.deleteRecursively()
            File(cache.path + ".bak").delete()
            File(cache.path + ".new").delete()
        }
    }

    @Test
    fun pendingErasureSurvivesRecreationAndReportsSuccessOnce() {
        cache.writeText("cached forecast")
        Robolectric.buildActivity(DataPrivacyActivity::class.java).setup().visible().use { controller ->
            val previous = controller.get()
            val completion = clear(previous)
            assertBusy(previous)
            controller.recreate()
            val current = controller.get()
            assertNotSame(previous, current)
            assertTrue(previous.isDestroyed)
            assertBusy(current)
            assertEquals(1, work.size)

            work.removeFirst().run()
            completion.get(10, TimeUnit.SECONDS)
            idle()

            assertReady(current)
            assertFalse(cache.exists())
            assertEquals(context.getString(R.string.data_cleared), ShadowToast.getTextOfLatestToast())
            assertEquals(1, ShadowToast.shownToastCount())
            controller.pause().resume().visible()
            controller.recreate()
            assertReady(controller.get())
            assertEquals(1, ShadowToast.shownToastCount())
        }
    }

    @Test
    fun completionBetweenActivityInstancesIsDeliveredOnlyToTheResumedScreen() {
        cache.writeText("cached forecast")
        val previousController = Robolectric.buildActivity(DataPrivacyActivity::class.java).setup().visible()
        val previous = previousController.get()
        val oldRow = root(previous).findViewWithTag<View>("clear-weather-cache")
        val completion = clear(previous)
        previousController.pause().stop()
        val retained = previous.onRetainNonConfigurationInstance()
        previousController.destroy()
        assertTrue(previous.isDestroyed)

        work.removeFirst().run()
        completion.get(10, TimeUnit.SECONDS)
        idle()
        assertFalse(oldRow.isEnabled)
        assertEquals(0, ShadowToast.shownToastCount())

        Robolectric.buildActivity(DataPrivacyActivity::class.java).use { controller ->
            Shadows.shadowOf(controller.get()).setLastNonConfigurationInstance(retained)
            controller.setup().visible()
            assertReady(controller.get())
            assertEquals(context.getString(R.string.data_cleared), ShadowToast.getTextOfLatestToast())
            assertEquals(1, ShadowToast.shownToastCount())
            controller.pause().resume().visible()
            assertEquals(1, ShadowToast.shownToastCount())
        }
    }

    @Test
    fun failedErasureAfterRecreationReportsFailureAndAllowsRetry() {
        cache.mkdirs()
        val entry = File(cache, "entry").apply { writeText("unavailable") }
        Robolectric.buildActivity(DataPrivacyActivity::class.java).setup().visible().use { controller ->
            val completion = clear(controller.get())
            controller.recreate()
            assertBusy(controller.get())
            work.removeFirst().run()
            assertThrows(java.util.concurrent.ExecutionException::class.java) {
                completion.get(10, TimeUnit.SECONDS)
            }
            idle()
            assertReady(controller.get())
            assertTrue(cache.exists())
            assertEquals(context.getString(R.string.data_clear_failed), ShadowToast.getTextOfLatestToast())
            assertEquals(1, ShadowToast.shownToastCount())

            assertTrue(entry.delete())
            assertTrue(cache.delete())
            val retry = clear(controller.get())
            assertBusy(controller.get())
            work.removeFirst().run()
            retry.get(10, TimeUnit.SECONDS)
            idle()
            assertReady(controller.get())
            assertEquals(context.getString(R.string.data_cleared), ShadowToast.getTextOfLatestToast())
            assertEquals(2, ShadowToast.shownToastCount())
        }
    }

    @Test
    fun themeRebuildPreservesPendingErasureAndUpdatesTheCurrentControls() {
        Robolectric.buildActivity(DataPrivacyActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val oldRoot = root(activity)
            val completion = clear(activity)
            AppPreferences(context).setThemeMode(ThemeMode.DARK)
            activity.onConfigurationChanged(Configuration(activity.resources.configuration))
            assertNotSame(oldRoot, root(activity))
            assertBusy(activity)
            work.removeFirst().run()
            completion.get(10, TimeUnit.SECONDS)
            idle()
            assertReady(activity)
            assertEquals(1, ShadowToast.shownToastCount())
        }
    }

    private fun clear(activity: DataPrivacyActivity): CompletableFuture<Void> {
        assertTrue(root(activity).findViewWithTag<View>("clear-weather-cache").performClick())
        val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
        assertTrue(dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick())
        idle()
        val operation = ReflectionHelpers.getField<Any>(activity, "operation")
        return ReflectionHelpers.getField(operation, "future")
    }

    private fun assertBusy(activity: DataPrivacyActivity) {
        for (tag in listOf("clear-weather-cache", "forget-device-location"))
            assertFalse(root(activity).findViewWithTag<View>(tag).isEnabled)
    }

    private fun assertReady(activity: DataPrivacyActivity) {
        for (tag in listOf("clear-weather-cache", "forget-device-location"))
            assertTrue(root(activity).findViewWithTag<View>(tag).isEnabled)
    }

    private fun root(activity: DataPrivacyActivity): View {
        idle()
        return activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).apply {
            measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY))
            layout(0, 0, 400, 850)
        }
    }

    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()
}

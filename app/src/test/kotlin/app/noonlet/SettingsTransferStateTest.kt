package app.noonlet

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executor
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
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsTransferStateTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var oldStore: WeatherStore? = null
    private var calls = 0
    private val time = 1_880_000_000_000L
    private val place = WeatherPlace("North Point", 54_200_000, -2_100_000)

    @Before fun prepare() {
        for (name in listOf("noonlet_widgets", "widget_drafts", "widget_configuration", "MainActivity",
            "noonlet_app", "noonlet_imported_widgets"))
            context.getSharedPreferences(name, 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
        oldStore = WeatherRepository.store
        File(context.noBackupFilesDir, "weather_cache.json").delete()
        WeatherRepository.store = WeatherStore(context, Executor { it.run() }, weatherClient {
            calls++
            """{"timezone":"GMT","utc_offset_seconds":0,
            "current_units":{"time":"unixtime","interval":"seconds","temperature_2m":"°C","weather_code":"wmo code","is_day":""},
            "current":{"time":${time / 1000},"interval":900,"temperature_2m":17,"weather_code":2,"is_day":1}}"""
        }, now = { time })
        ShadowToast.reset()
    }

    @After fun clean() {
        WeatherRepository.store = oldStore
        File(context.noBackupFilesDir, "weather_cache.json").delete()
    }

    @Test fun returningFromImportRefreshesBothPreviewSizesAndAlarmState() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { editor ->
            val activity = editor.get()
            val saved = contents()
            root(activity).findViewWithTag<View>("settings-trigger").performClick()
            editor.pause()
            import(saved)
            editor.resume().visible()
            assertTrue(activity.session.settings.showAlarm)
            assertEquals(19, activity.session.settings.alarmSize)
            assertEquals(WidgetSize.FOUR_BY_TWO, activity.preview.requestedSize())
            assertTrue(root(activity).findViewWithTag<View>("preview-size:3").isSelected)
            assertTrue(findText(root(activity), "World clock")!!.performClick())
            assertEquals(WidgetSize.FOUR_BY_ONE, activity.preview.requestedSize())
            editor.recreate()
            assertEquals(WidgetSize.FOUR_BY_ONE, editor.get().preview.requestedSize())
            assertEquals(WidgetSize.FOUR_BY_ONE.ordinal, EditorPreferences(context).previewSize(true, 4))
        }
    }

    @Test fun returningToAnExplicitWidgetEditorUsesRestoredSettings() {
        bind(7610)
        WidgetSettingsStore.save(context, 7610, false, WidgetSettings())
        Robolectric.buildActivity(MainActivity::class.java, EditorIntents.edit(context, 7610, false))
            .setup().visible().use { editor ->
                val activity = editor.get()
                val saved = contents().let { it.copy(clock = it.clock.copy(widgets = listOf(it.clock.draft))) }
                root(activity).findViewWithTag<View>("settings-trigger").performClick()
                editor.pause()
                import(saved)
                editor.resume().visible()
                assertTrue(activity.session.settings.showAlarm)
                assertEquals(19, activity.session.settings.alarmSize)
                assertEquals(WidgetSize.FOUR_BY_TWO, activity.preview.requestedSize())
                assertTrue(WidgetSettingsStore.load(context, 7610, false).showAlarm)
            }
    }

    @Test fun importedManualWeatherLoadsWithoutARefreshButtonTap() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { editor ->
            val activity = editor.get()
            root(activity).findViewWithTag<View>("settings-trigger").performClick()
            editor.pause()
            val saved = contents()
            saved.clock.draft.settings.apply {
                weatherEnabled = true
                weatherUseDevice = false
                weatherPlace = place.encode()
            }
            import(saved)
            waitUntil { calls > 0 }
            editor.resume().visible()
            val state = WeatherRepository.state(context, WeatherRequest.capture(activity.session.settings))
            assertEquals(place, state.place)
            assertNotNull(state.snapshot)
        }
    }

    @Test fun anUnplacedImportedWidgetRemainsEditableWithoutReplacingTheNewWidgetDraft() {
        val saved = contents()
        val draft = SettingsArchive.Configuration(WidgetSize.FOUR_BY_TWO, WidgetSettings())
        SettingsArchive.restore(context, saved.copy(clock = SettingsArchive.Group(draft, listOf(saved.clock.draft))))
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { editor ->
            val activity = editor.get()
            assertTrue(activity.session.settings.showAlarm)
            assertEquals(19, activity.session.settings.alarmSize)
            assertFalse(WidgetSettingsStore.loadUnplaced(context, false).showAlarm)
            activity.session.settings.alarmSize = 21
            activity.session.apply()
            assertFalse(WidgetSettingsStore.loadUnplaced(context, false).showAlarm)
            val pending = ImportedWidgetConfigurations.all(context).single()
            assertEquals(21, pending.configuration.settings.alarmSize)
            assertEquals(pending.token, activity.session.importedConfiguration?.token)
            assertTrue(findText(root(activity), "World clock")!!.performClick())
            assertTrue(findText(root(activity), "Clock")!!.performClick())
            assertEquals(21, activity.session.settings.alarmSize)
            editor.recreate()
            assertEquals(21, editor.get().session.settings.alarmSize)
            val exported = SettingsArchive.capture(context)
            assertFalse(exported.clock.draft.settings.showAlarm)
            assertTrue(exported.clock.widgets.single().settings.showAlarm)
            assertEquals(21, exported.clock.widgets.single().settings.alarmSize)
        }
    }

    @Test fun recreationAfterImportDiscardsAnEarlierEditorSnapshot() {
        val state = Bundle()
        val editor = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        editor.pause().saveInstanceState(state).stop().destroy()
        import(contents())
        Robolectric.buildActivity(MainActivity::class.java).setup(state).visible().use { restored ->
            assertTrue(restored.get().session.settings.showAlarm)
            assertEquals(19, restored.get().session.settings.alarmSize)
            assertEquals(WidgetSize.FOUR_BY_TWO, restored.get().preview.requestedSize())
            assertTrue(root(restored.get()).findViewWithTag<View>("preview-size:3").isSelected)
        }
        assertTrue(WidgetSettingsStore.loadUnplaced(context, false).showAlarm)
        assertEquals(WidgetSize.FOUR_BY_TWO.ordinal, EditorPreferences(context).previewSize(false, 4))
    }

    @Test fun aPausedEditorCannotWriteItsPreviousValuesOverAnImport() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { editor ->
            root(editor.get()).findViewWithTag<View>("settings-trigger").performClick()
            editor.pause()
            import(contents())
            editor.saveInstanceState(Bundle())
            assertTrue(WidgetSettingsStore.loadUnplaced(context, false).showAlarm)
            assertEquals(19, WidgetSettingsStore.loadUnplaced(context, false).alarmSize)
            editor.resume().visible()
            assertTrue(editor.get().session.settings.showAlarm)
            assertEquals(WidgetSize.FOUR_BY_TWO, editor.get().preview.requestedSize())
        }
    }

    private fun contents(): SettingsArchive.Contents {
        val original = SettingsArchive.capture(context)
        val clock = WidgetSettings().apply { showAlarm = true; alarmSize = 19; alarmOffsetX = 8; textSize = 60 }
        val world = WidgetSettings.defaults(true).apply { cities = arrayOf("Asia/Tokyo"); textSize = 57 }
        return original.copy(
            clock = SettingsArchive.Group(SettingsArchive.Configuration(WidgetSize.FOUR_BY_TWO, clock), emptyList()),
            world = SettingsArchive.Group(SettingsArchive.Configuration(WidgetSize.FOUR_BY_ONE, world), emptyList()),
        )
    }

    private fun import(contents: SettingsArchive.Contents) {
        val uri = Uri.parse("content://settings.example/complete.json")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, SettingsArchive.encode(contents).byteInputStream())
        Robolectric.buildActivity(AppSettingsActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            root(activity).findViewWithTag<View>("import-settings").performClick()
            val request = Shadows.shadowOf(activity).nextStartedActivityForResult
            Shadows.shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
            waitUntil { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitUntil { ShadowToast.getTextOfLatestToast() == "Settings imported" }
        }
    }

    private fun bind(id: Int) {
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(context, ClockWidgetProvider::class.java)
            initialLayout = R.layout.clock_widget
        }
        Shadows.shadowOf(AppWidgetManager.getInstance(context)).apply {
            addInstalledProvider(info)
            addBoundWidget(id, info)
        }
    }

    private fun root(activity: Activity): View = activity.findViewById(android.R.id.content)

    private fun findText(view: View, text: String): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) for (index in 0 until view.childCount)
            findText(view.getChildAt(index), text)?.let { return it }
        return null
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue(predicate())
    }
}

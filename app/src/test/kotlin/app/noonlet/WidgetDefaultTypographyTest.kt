package app.noonlet

import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.time.Duration
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
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class WidgetDefaultTypographyTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        for (name in arrayOf("noonlet_widgets", "MainActivity", "widget_drafts")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        AppLocales.setLanguage(context, "en")
    }

    @Test
    fun freshClockAndWorldSettingsUseTheirOwnTypographyDefaults() {
        assertDefaults(WidgetSettings(), false)
        for (world in booleanArrayOf(false, true)) {
            assertDefaults(WidgetSettings.defaults(world), world)
            assertDefaults(WidgetSettingsStore.load(context, 41001, world), world)
            assertDefaults(WidgetSettingsStore.loadUnplaced(context, world), world)
        }
    }

    @Test
    fun savedTypographyChoicesArePreserved() {
        for (world in booleanArrayOf(false, true)) {
            for (custom in booleanArrayOf(false, true)) {
                val id = 42000 + (if (world) 10 else 0) + (if (custom) 1 else 0)
                val saved =
                    WidgetSettings.defaults(world).apply {
                        fontFamily =
                            if (custom) "serif"
                            else if (world) "sans-serif-medium" else "sans-serif-light"
                        fontWeight = if (custom) 617 else if (world) 350 else 300
                        timeFontWeight = if (custom) 725 else 300
                        dateFontWeight = if (custom) 675 else 450
                        alarmOffsetX = if (custom) 17 else -4
                        alarmOffsetY = if (custom) -11 else 3
                        showAlarm = true
                        showAlarmIcon = false
                        alarmFormat = "h:mm a"
                        alarmSize = 21
                        alarmAlignment = WidgetAlignment.RIGHT
                        dateOffsetX = if (custom) -9 else 2
                    }
                WidgetSettingsStore.save(context, id, world, saved)
                WidgetSettingsStore.saveUnplaced(context, world, saved)
                val bundle = Bundle()
                SettingsCodec.writeEditorState(saved, bundle)
                val restored = WidgetSettings.defaults(world)
                WidgetSettingsStore.restoreEditorState(context, id, world, restored, bundle)
                for (actual in
                    arrayOf(
                        WidgetSettingsStore.load(context, id, world),
                        WidgetSettingsStore.loadUnplaced(context, world),
                        restored,
                    )) {
                    assertEquals(saved.fontFamily, actual.fontFamily)
                    assertEquals(saved.fontWeight, actual.fontWeight)
                    assertEquals(saved.timeFontWeight, actual.timeFontWeight)
                    assertEquals(saved.dateFontWeight, actual.dateFontWeight)
                    assertEquals(saved.alarmOffsetX, actual.alarmOffsetX)
                    assertEquals(saved.alarmOffsetY, actual.alarmOffsetY)
                    assertEquals(saved.showAlarm, actual.showAlarm)
                    assertEquals(saved.showAlarmIcon, actual.showAlarmIcon)
                    assertEquals(saved.alarmFormat, actual.alarmFormat)
                    assertEquals(saved.alarmSize, actual.alarmSize)
                    assertEquals(saved.alarmAlignment, actual.alarmAlignment)
                    assertEquals(saved.dateOffsetX, actual.dateOffsetX)
                }
            }
        }
    }

    @Test
    fun explicitAlarmValuesOverrideFactoryValues() {
        val saved = WidgetSettings.defaults(false).apply {
            showAlarm = true
            timeFontWeight = 400
            alarmSize = 16
            alarmOffsetX = 0
            alarmOffsetY = 0
        }
        WidgetSettingsStore.save(context, 42003, false, saved)
        WidgetSettingsStore.saveUnplaced(context, false, saved)
        val bundle = Bundle()
        SettingsCodec.writeEditorState(saved, bundle)
        val restored = WidgetSettings.defaults(false)
        WidgetSettingsStore.restoreEditorState(context, 42003, false, restored, bundle)
        for (actual in listOf(WidgetSettingsStore.load(context, 42003, false),
            WidgetSettingsStore.loadUnplaced(context, false), restored)) {
            assertTrue(actual.showAlarm)
            assertEquals(400, actual.timeFontWeight)
            assertEquals(16, actual.alarmSize)
            assertEquals(0, actual.alarmOffsetX)
            assertEquals(0, actual.alarmOffsetY)
        }
    }

    @Test
    fun aPlacedEditorsWholeResetUsesItsTypeDefaultsAndKeepsPlaceSelections() {
        for (world in booleanArrayOf(false, true)) {
            val id = if (world) 43002 else 43001
            val chosen =
                WidgetSettings.defaults(world).apply {
                    fontFamily = "serif"
                    fontWeight = 617
                    timeFontWeight = 725
                    dateFontWeight = 675
                    worldCardCornerScale = 50
                    worldBackdropCornerScale = 150
                    alarmOffsetX = 17
                    alarmOffsetY = -11
                    showNoAlarm = true
                    showAlarm = true
                    showAlarmIcon = false
                    showDate = false
                    weatherEnabled = true
                    weatherUseDevice = false
                    weatherPlace = "custom-location"
                    advancedTap = true
                    timeTapAction = "none"
                    dateTapAction = "clock"
                    alarmTapAction = "settings"
                    dateOffsetX = -9
                    zoneId = "Asia/Kathmandu"
                    cities = arrayOf("Asia/Tokyo", "Europe/London")
                    tapAction = "none"
                }
            WidgetSettingsStore.save(context, id, world, chosen)
            bind(id, world)
            val intent =
                Intent(context, MainActivity::class.java)
                    .setData(Uri.parse("noonlet://edit/$world/$id"))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra("world", world)
            Robolectric.buildActivity(MainActivity::class.java, intent).setup().visible().use {
                controller ->
                var activity = controller.get()
                layout(activity)
                controller.recreate()
                activity = controller.get()
                val root = layout(activity)
                val before = WidgetSettingsStore.load(context, id, world)
                assertEquals("serif", before.fontFamily)
                assertEquals(725, before.timeFontWeight)
                assertEquals(-9, before.dateOffsetX)
                if (
                    root.findViewWithTag<View>("section-content:appearance").visibility !=
                        View.VISIBLE
                ) {
                    assertTrue(
                        root.findViewWithTag<View>("section-toggle:appearance").performClick(),
                    )
                    layout(activity)
                }
                val reset = checkNotNull(findText(root, "Reset appearance"))
                var action: View? = reset
                while (action != null && !action.isClickable) action = action.parent as? View
                val resetRow = checkNotNull(action)
                assertTrue(resetRow.performClick())
                val cancel = DialogTestFixtures.latest()
                assertEquals(725, WidgetSettingsStore.load(context, id, world).timeFontWeight)
                assertTrue(cancel.getButton(AlertDialog.BUTTON_NEGATIVE).performClick())
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertFalse(cancel.isShowing)
                assertEquals(725, WidgetSettingsStore.load(context, id, world).timeFontWeight)
                assertTrue(resetRow.performClick())
                val confirm = DialogTestFixtures.latest()
                assertTrue(confirm.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertFalse(confirm.isShowing)
                val actual = WidgetSettingsStore.load(context, id, world)
                assertDefaults(actual, world, chosen)
                assertEquals(chosen.zoneId, actual.zoneId)
                assertArrayEquals(chosen.cities, actual.cities)
                assertEquals(chosen.weatherEnabled, actual.weatherEnabled)
                assertEquals(chosen.weatherUseDevice, actual.weatherUseDevice)
                assertEquals(chosen.weatherPlace, actual.weatherPlace)
                assertEquals(chosen.showDate, actual.showDate)
                assertEquals(chosen.advancedTap, actual.advancedTap)
                assertEquals(chosen.tapAction, actual.tapAction)
                assertEquals(chosen.timeTapAction, actual.timeTapAction)
                assertEquals(chosen.dateTapAction, actual.dateTapAction)
                assertEquals(chosen.alarmTapAction, actual.alarmTapAction)
                assertNotNull(root.findViewWithTag<View>("action-save-changes"))
            }
        }
    }

    @Test
    fun storedTypographyInheritsAnAbsentDateWeightAndClampsSizeLimits() {
        val stored = context.getSharedPreferences("noonlet_widgets", Context.MODE_PRIVATE)
        for (world in booleanArrayOf(false, true)) {
            val id = if (world) 44002 else 44001
            val prefix = "${if (world) "world" else "clock"}.$id."
            stored
                .edit()
                .putBoolean(prefix + "saved", true)
                .putString(
                    prefix + "state",
                    """{"cfg_fontWeight":500,"cfg_timeFontWeight":650,"cfg_size":97,"cfg_dateSize":52}""",
                )
                .commit()
            val settings = WidgetSettingsStore.load(context, id, world)
            assertEquals(500, settings.dateFontWeight)
            assertEquals(500, settings.fontWeight)
            assertEquals(650, settings.timeFontWeight)
            assertEquals(65, settings.textSize)
            assertEquals(35, settings.dateSize)
            settings.dateFontWeight = 250
            settings.alarmOffsetX = 17
            settings.alarmOffsetY = -11
            WidgetSettingsStore.save(context, id, world, settings)
            val actual = WidgetSettingsStore.load(context, id, world)
            assertEquals(250, actual.dateFontWeight)
            assertEquals(500, actual.fontWeight)
            assertEquals(650, actual.timeFontWeight)
            assertEquals(17, actual.alarmOffsetX)
            assertEquals(-11, actual.alarmOffsetY)
        }
    }

    @Test
    fun editorSnapshotsAndDraftsKeepIndependentWeightsAndBoundedOffsets() {
        val settings =
            WidgetSettings().apply {
                textSize = 120
                dateSize = 64
                dateFontWeight = 650
                timeFontWeight = 400
                fontWeight = 300
                alarmOffsetX = 60
                alarmOffsetY = -60
            }
        val bundle = Bundle()
        SettingsCodec.writeEditorState(settings, bundle)
        val restored = WidgetSettings()
        WidgetSettingsStore.restoreEditorState(context, 0, false, restored, bundle)
        WidgetSettingsStore.saveUnplaced(context, false, settings)
        for (actual in arrayOf(restored, WidgetSettingsStore.loadUnplaced(context, false))) {
            assertEquals(65, actual.textSize)
            assertEquals(35, actual.dateSize)
            assertEquals(650, actual.dateFontWeight)
            assertEquals(400, actual.timeFontWeight)
            assertEquals(300, actual.fontWeight)
            assertEquals(40, actual.alarmOffsetX)
            assertEquals(-40, actual.alarmOffsetY)
        }
    }

    private fun assertDefaults(settings: WidgetSettings, world: Boolean,
        content: WidgetSettings? = null) {
        assertEquals(100, settings.worldCardCornerScale)
        assertEquals(100, settings.worldBackdropCornerScale)
        assertEquals(if (world) "sans-serif-bold" else "sans-serif-medium", settings.fontFamily)
        assertEquals(if (world) 450 else 350, settings.fontWeight)
        assertEquals(450, settings.timeFontWeight)
        assertEquals(350, settings.dateFontWeight)
        assertEquals(-4, settings.alarmOffsetX)
        assertEquals(-24, settings.alarmOffsetY)
        assertEquals("HH:mm", settings.alarmFormat)
        assertEquals(content?.showAlarm ?: false, settings.showAlarm)
        assertEquals(content?.showNoAlarm ?: false, settings.showNoAlarm)
        assertEquals(content?.showAlarmIcon ?: true, settings.showAlarmIcon)
        assertEquals(14, settings.alarmSize)
        assertEquals(WidgetAlignment.CENTER, settings.alarmAlignment)
        assertEquals(4, settings.dateOffsetX)
        assertEquals(56, settings.textSize)
        assertEquals(16, settings.dateSize)
    }

    private fun bind(id: Int, world: Boolean) {
        val manager = AppWidgetManager.getInstance(context)
        val info =
            AppWidgetProviderInfo().apply {
                provider =
                    ComponentName(
                        context,
                        if (world) WorldWidgetProvider::class.java
                        else ClockWidgetProvider::class.java,
                    )
                initialLayout = if (world) R.layout.world_widget else R.layout.clock_widget
            }
        Shadows.shadowOf(manager).addInstalledProvider(info)
        Shadows.shadowOf(manager).addBoundWidget(id, info)
        manager.updateAppWidgetOptions(
            id,
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, if (world) 328 else 170)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, if (world) 328 else 170)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, if (world) 174 else 90)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, if (world) 174 else 90)
            },
        )
        BaseWidgetProvider.updateWidget(context, id, world)
    }

    private fun layout(activity: MainActivity): View {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        return activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 400, 850)
        }
    }

    private fun findText(root: View, label: String): TextView? {
        if (root is TextView && label.contentEquals(root.text)) return root
        if (root is ViewGroup)
            for (i in 0 until root.childCount) {
                findText(root.getChildAt(i), label)?.let {
                    return it
                }
            }
        return null
    }
}

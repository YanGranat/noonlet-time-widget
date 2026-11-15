package app.noonlet

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import app.noonlet.SettingsTestFixtures.assertSettings
import app.noonlet.SettingsTestFixtures.sample
import java.util.Locale
import org.json.JSONObject
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReferenceSettingsTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
        Locale.setDefault(Locale.ENGLISH)
    }

    @Test
    @Throws(Exception::class)
    fun everySettingPersistsAndSnapshotsCopyCityArrays() {
        val expected: WidgetSettings = sample()
        WidgetSettingsStore.save(context, 1201, false, expected)
        assertSettings(expected, WidgetSettingsStore.load(context, 1201, false))
        WidgetSettingsStore.save(context, 1202, true, expected)
        assertSettings(expected, WidgetSettingsStore.load(context, 1202, true))
        val snapshot: Bundle = Bundle()
        SettingsCodec.writeTo(expected, snapshot)
        val restored: WidgetSettings = WidgetSettings()
        SettingsCodec.readFrom(restored, snapshot)
        assertSettings(expected, restored)
        expected.cities[0] = "Asia/Tokyo"
        assertEquals("Europe/London", restored.cities[0])
    }

    @Test
    fun historicalJsonPreservesSupportedValuesAndWritesTheCurrentSchema() {
        val historical =
            JSONObject(
                """
                {
                  "cfg_zone": "Asia/Kathmandu",
                  "cfg_style": "dark",
                  "cfg_align": "right",
                  "cfg_size": 47,
                  "cfg_opacity": 62,
                  "cfg_padding": 7,
                  "cfg_date": false,
                  "cfg_24": false,
                  "cfg_outline": false,
                  "cfg_bg": true,
                  "cfg_cities": ["Europe/London", "Asia/Kathmandu"],
                  "cfg_fontFamily": "serif",
                  "cfg_orientation": "horizontal",
                  "cfg_timeFormat": "HH:mm:ss",
                  "cfg_dateFormat": "dd.MM.yyyy",
                  "cfg_alarmFormat": "HH:mm",
                  "cfg_dateAlignment": "center",
                  "cfg_alarmAlignment": "left",
                  "cfg_tapAction": "settings",
                  "cfg_timeTapAction": "calendar",
                  "cfg_dateTapAction": "none",
                  "cfg_alarmTapAction": "clock",
                  "cfg_showTime": false,
                  "cfg_showAlarm": true,
                  "cfg_showAlarmIcon": false,
                  "cfg_advancedTap": true,
                  "cfg_timeColor": -15553740,
                  "cfg_dateColor": -12246067,
                  "cfg_alarmColor": -6768095,
                  "cfg_backgroundColor": -15718088,
                  "cfg_dateSize": 19,
                  "cfg_alarmSize": 21,
                  "cfg_clockBackgroundOpacity": 123,
                  "cfg_paddingTop": 3,
                  "cfg_paddingBottom": 8,
                  "cfg_paddingLeft": 12,
                  "cfg_paddingRight": 17,
                  "cfg_timeOffsetX": 11,
                  "cfg_timeOffsetY": -7,
                  "cfg_dateOffsetX": -8,
                  "cfg_dateOffsetY": 13,
                  "cfg_timeDateGap": 9,
                  "cfg_fontWeight": 700,
                  "cfg_timeFontWeight": 900,
                  "cfg_worldDayTextColor": -15654349,
                  "cfg_worldNightTextColor": -5588020,
                  "cfg_worldDayBackgroundColor": -12298906,
                  "cfg_worldNightBackgroundColor": -2232577,
                  "cfg_worldBackdropColor": 2133017959
                }
                """
                    .trimIndent(),
            )
        val prefs = context.getSharedPreferences("noonlet_widgets", Context.MODE_PRIVATE)
        prefs
            .edit()
            .putBoolean("clock.1203.saved", true)
            .putString("clock.1203.state", historical.toString())
            .commit()

        val loaded = WidgetSettingsStore.load(context, 1203, false)
        assertSettings(
            sample().apply {
                showNoAlarm = false
                dateFontWeight = 700
                weatherFontWeight = 700
                weatherColor = dateColor
                weatherTextSize = 13
                weatherIconSize = 52
                weatherIconGap = 0
                weatherTextFormat = WeatherTextFormat.CONDITION_TEMPERATURE
                weatherOffsetX = 0
                weatherOffsetY = 0
                alarmOffsetX = -4
                alarmOffsetY = -24
                worldCardCornerScale = 100
                worldBackdropCornerScale = 100
            },
            loaded,
        )
        WidgetSettingsStore.save(context, 1203, false, loaded)
        val saved = JSONObject(requireNotNull(prefs.getString("clock.1203.state", null)))
        val currentKeys =
            historical.keys().asSequence().toSet() -
                setOf(
                    "cfg_bg",
                    "cfg_showTime",
                    "cfg_backgroundColor",
                    "cfg_clockBackgroundOpacity",
                ) +
                setOf(
                    "cfg_showNoAlarm",
                    "cfg_dateFontWeight",
                    "cfg_alarmOffsetX",
                    "cfg_alarmOffsetY",
                    "cfg_worldCardCornerScale",
                    "cfg_worldBackdropCornerScale",
                    "cfg_weatherEnabled",
                    "cfg_weatherUseDevice",
                    "cfg_weatherPlace",
                    "cfg_weatherFontFamily",
                    "cfg_weatherFontWeight",
                    "cfg_weatherColor",
                    "cfg_weatherTextSize",
                    "cfg_weatherIconSize",
                    "cfg_weatherIconGap",
                    "cfg_weatherTextFormat",
                    "cfg_weatherOffsetX",
                    "cfg_weatherOffsetY",
                )
        assertEquals(currentKeys, saved.keys().asSequence().toSet())
    }

    @Test
    fun cornerScalesUseAdaptiveDefaultsAndClampDecodedValuesIndependently() {
        val defaults = WidgetSettings.defaults(true)
        SettingsCodec.readFrom(defaults, Bundle())
        assertEquals(100, defaults.worldCardCornerScale)
        assertEquals(100, defaults.worldBackdropCornerScale)
        val input =
            Bundle().apply {
                putInt("cfg_worldCardCornerScale", -10)
                putInt("cfg_worldBackdropCornerScale", 250)
            }
        SettingsCodec.readFrom(defaults, input)
        assertEquals(0, defaults.worldCardCornerScale)
        assertEquals(200, defaults.worldBackdropCornerScale)
        input.putInt("cfg_worldCardCornerScale", 250)
        input.putInt("cfg_worldBackdropCornerScale", -10)
        SettingsCodec.readFrom(defaults, input)
        assertEquals(200, defaults.worldCardCornerScale)
        assertEquals(0, defaults.worldBackdropCornerScale)
        WidgetSettingsStore.save(context, 1204, true, defaults)
        assertSettings(defaults, WidgetSettingsStore.load(context, 1204, true))
    }

    @Test
    fun installedLegacyWidgetMigratesWithoutResettingOtherWidgets() {
        val prefs: SharedPreferences =
            context.getSharedPreferences("noonlet_widgets", Context.MODE_PRIVATE)
        prefs
            .edit()
            .putBoolean("clock.1301.saved", true)
            .putString("clock.1301.zone", "Europe/London")
            .putString("clock.1301.style", "dark")
            .putString("clock.1301.alignment", "right")
            .putInt("clock.1301.size", 51)
            .putInt("clock.1301.opacity", 64)
            .putInt("clock.1301.padding", 4)
            .putBoolean("clock.1301.24h", false)
            .putString("world.1302.marker", "preserve")
            .commit()
        val migrated: WidgetSettings = WidgetSettingsStore.load(context, 1301, false)
        assertEquals("Europe/London", migrated.zoneId)
        assertEquals("h:mm a", migrated.timeFormat)
        assertEquals(-0x1000000, migrated.timeColor)
        assertEquals(WidgetAlignment.RIGHT, migrated.dateAlignment)
        assertEquals(4, migrated.paddingTop)
        assertEquals(4, migrated.paddingBottom)
        assertEquals(4, migrated.paddingLeft)
        assertEquals(4, migrated.paddingRight)
        WidgetSettingsStore.save(context, 1301, false, migrated)
        assertTrue(prefs.contains("clock.1301.state"))
        assertFalse(prefs.contains("clock.1301.zone"))
        assertEquals("preserve", prefs.getString("world.1302.marker", ""))
    }

    @Test
    @Throws(Exception::class)
    fun rotationPreservesAllUnsavedControls() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .use(
                { controller ->
                    val expected: WidgetSettings = sample()
                    SettingsCodec.readFrom(
                        controller.get().session.settings,
                        SettingsCodec.snapshot(expected),
                    )
                    controller.recreate()
                    assertSettings(
                        expected,
                        controller.get().session.settings,
                    )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun launcherConfigurationSavePreservesAllControls() {
        val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
        val provider: AppWidgetProviderInfo = AppWidgetProviderInfo()
        provider.provider = ComponentName(context, ClockWidgetProvider::class.java)
        provider.initialLayout = R.layout.clock_widget
        Shadows.shadowOf(manager).addInstalledProvider(provider)
        Shadows.shadowOf(manager).addBoundWidget(911, provider)
        val configure: android.content.Intent =
            android.content
                .Intent(context, MainActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 911)
        Robolectric.buildActivity(MainActivity::class.java, configure)
            .setup()
            .use(
                { controller ->
                    val expected: WidgetSettings = sample()
                    SettingsCodec.readFrom(
                        controller.get().session.settings,
                        SettingsCodec.snapshot(expected),
                    )
                    WidgetTestViews.completeEditor(controller.get())
                    Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    val ids: IntArray = manager.getAppWidgetIds(provider.provider)
                    assertEquals(1, ids.size)
                    assertEquals(911, ids[0])
                    assertSettings(expected, WidgetSettingsStore.load(context, 911, false))
                    assertEquals(
                        android.app.Activity.RESULT_OK,
                        Shadows.shadowOf(controller.get()).getResultCode(),
                    )
                },
            )
    }
}

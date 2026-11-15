package app.noonlet

import android.content.Context
import android.os.Bundle
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36])
class WeatherSettingsTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        for (name in listOf("noonlet_widgets", "MainActivity", "widget_drafts")) context
            .getSharedPreferences(name, 0)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun inheritedWeatherAppearanceIsCopiedOnceAndRemainsIndependentAfterSaving() {
        val settings = WidgetSettings()
        SettingsCodec.readFrom(settings, Bundle().apply {
            putString("cfg_fontFamily", "serif")
            putInt("cfg_dateFontWeight", 650)
            putInt("cfg_dateColor", 0xff123456.toInt())
        })
        assertEquals("serif", settings.weatherFontFamily)
        assertEquals(650, settings.weatherFontWeight)
        assertEquals(0xff123456.toInt(), settings.weatherColor)
        settings.fontFamily = "monospace"
        settings.dateFontWeight = 200
        settings.dateColor = 0xffabcdef.toInt()
        WidgetSettingsStore.save(context, 7005, false, settings)
        val stored = WidgetSettingsStore.load(context, 7005, false)
        assertEquals("serif", stored.weatherFontFamily)
        assertEquals(650, stored.weatherFontWeight)
        assertEquals(0xff123456.toInt(), stored.weatherColor)
    }

    @Test
    fun existingSettingsKeepWeatherDisabledWithoutChangingClockValues() {
        val prefs = context.getSharedPreferences("noonlet_widgets", 0)
        prefs
            .edit()
            .putBoolean("clock.7001.saved", true)
            .putString(
                "clock.7001.state",
                JSONObject()
                    .apply {
                        put("cfg_size", 49)
                        put("cfg_dateSize", 18)
                        put("cfg_dateOffsetX", 9)
                        put("cfg_timeFormat", "HH:mm:ss")
                    }
                    .toString(),
            )
            .commit()
        val loaded = WidgetSettingsStore.load(context, 7001, false)
        assertFalse(loaded.weatherEnabled)
        assertTrue(loaded.weatherUseDevice)
        assertEquals("", loaded.weatherPlace)
        assertEquals(49, loaded.textSize)
        assertEquals(18, loaded.dateSize)
        assertEquals(9, loaded.dateOffsetX)
        assertEquals("HH:mm:ss", loaded.timeFormat)
    }

    @Test
    fun manualCoordinatesRoundTripThroughBundleAndStoredJsonWithoutLosingPrecision() {
        val place = WeatherPlace("Sample place", 12_345_678, -45_678_901)
        val settings =
            WidgetSettings().apply {
                weatherEnabled = true
                weatherUseDevice = false
                weatherPlace = place.encode()
            }
        val restored = WidgetSettings()
        SettingsCodec.readFrom(restored, SettingsCodec.snapshot(settings))
        assertWeather(settings, restored, place)
        WidgetSettingsStore.save(context, 7002, false, settings)
        val stored = WidgetSettingsStore.load(context, 7002, false)
        assertWeather(settings, stored, place)
        val state =
            JSONObject(
                context
                    .getSharedPreferences("noonlet_widgets", 0)
                    .getString("clock.7002.state", "{}")!!,
            )
        assertTrue(state.get("cfg_weatherPlace") is String)
        assertFalse(WidgetSettingsStore.load(context, 7002, true).weatherEnabled)
    }

    @Test
    fun invalidManualPlaceDoesNotSilentlySwitchToDeviceLocation() {
        val restored = WidgetSettings()
        SettingsCodec.readFrom(
            restored,
            Bundle().apply {
                putBoolean("cfg_weatherEnabled", true)
                putBoolean("cfg_weatherUseDevice", false)
                putString("cfg_weatherPlace", "invalid")
            },
        )
        assertTrue(restored.weatherEnabled)
        assertFalse(restored.weatherUseDevice)
        assertEquals("", restored.weatherPlace)
    }

    @Test
    fun capturedWeatherSelectionIsIndependentAndRetainsEncodedIdentity() {
        val place = WeatherPlace("Sample place", 12_345_678, -45_678_901)
        val encoded = place.encode()
        val settings = WidgetSettings().apply {
            weatherEnabled = true
            weatherUseDevice = false
            weatherPlace = encoded
        }
        val request = WeatherRequest.capture(settings)
        settings.timeFormat = "HH:mm:ss"
        settings.textSize = 42
        assertEquals(request, WeatherRequest.capture(settings))

        settings.weatherPlace = JSONObject(encoded).toString(2)
        val reformatted = WeatherRequest.capture(settings)
        assertEquals(request.place, reformatted.place)
        assertNotEquals(request, reformatted)

        settings.weatherEnabled = false
        settings.weatherUseDevice = true
        settings.weatherPlace = ""
        assertTrue(request.enabled)
        assertFalse(request.useDevice)
        assertEquals(encoded, request.encodedPlace)
        assertEquals(place, request.place)
        assertNotEquals(
            WeatherRequest(true, false, "invalid"),
            WeatherRequest(true, false, "different invalid selection"),
        )
    }

    @Test
    fun artworkSizeUsesTheDefaultOnlyWhenNoValueWasSaved() {
        assertEquals(52, WidgetSettings.defaults(false).weatherIconSize)
        val settings = WidgetSettings.defaults(false)
        SettingsCodec.readFrom(settings, Bundle.EMPTY)
        assertEquals(52, settings.weatherIconSize)
        settings.weatherIconSize = 67
        WidgetSettingsStore.save(context, 7006, false, settings)
        assertEquals(67, WidgetSettingsStore.load(context, 7006, false).weatherIconSize)
    }

    @Test
    fun appearanceResetPreservesWeatherAndTheExplicitLocationChoice() {
        val session = EditorSession(context, android.content.Intent(), null)
        session.settings.weatherEnabled = true
        session.settings.weatherUseDevice = false
        val place = WeatherPlace("Sample place", 12_345_678, 45_678_901).encode()
        session.settings.weatherPlace = place
        session.settings.weatherIconSize = 67
        session.resetAppearance()
        assertTrue(session.settings.weatherEnabled)
        assertFalse(session.settings.weatherUseDevice)
        assertEquals(place, session.settings.weatherPlace)
        assertEquals(52, session.settings.weatherIconSize)
    }

    private fun assertWeather(
        expected: WidgetSettings,
        actual: WidgetSettings,
        place: WeatherPlace,
    ) {
        assertEquals(expected.weatherEnabled, actual.weatherEnabled)
        assertEquals(expected.weatherUseDevice, actual.weatherUseDevice)
        assertEquals(expected.weatherPlace, actual.weatherPlace)
        val decoded = requireNotNull(WeatherPlace.decode(actual.weatherPlace))
        assertEquals(place.name, decoded.name)
        assertEquals(place.latitudeE6, decoded.latitudeE6)
        assertEquals(place.longitudeE6, decoded.longitudeE6)
    }
}

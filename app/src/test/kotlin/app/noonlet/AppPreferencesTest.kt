package app.noonlet

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36])
class AppPreferencesTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        for (name in listOf("noonlet_app", "noonlet_widgets", "MainActivity", "widget_drafts", "widget_configuration"))
            context.getSharedPreferences(name, 0).edit().clear().commit()
    }

    @Test
    fun freshThemeUsesDarkAndRemainsDarkAfterEditorStateIsCreated() {
        val preferences = AppPreferences(context)
        assertEquals(ThemeMode.DARK, preferences.themeMode())
        val light = android.content.res.Configuration(context.resources.configuration).apply {
            uiMode = android.content.res.Configuration.UI_MODE_NIGHT_NO
        }
        assertTrue(AppTheme.palette(context.createConfigurationContext(light)).isDark)
        context.getSharedPreferences("noonlet_widgets", 0).edit().putString("language", "en").commit()
        assertEquals(ThemeMode.DARK, AppPreferences(context).themeMode())
        assertEquals("dark", context.getSharedPreferences("noonlet_app", 0).getString("theme_mode", null))
    }

    @Test
    fun eachExistingStorageOwnerMigratesToDarkWithoutChangingItsData() {
        for (name in listOf("noonlet_widgets", "MainActivity", "widget_drafts", "widget_configuration")) {
            prepare()
            val stored = context.getSharedPreferences(name, 0)
            stored.edit().putString("fixture", "value").commit()
            val before = stored.all.toMap()
            assertEquals(ThemeMode.DARK, AppPreferences(context).themeMode())
            assertEquals(before, stored.all)
            stored.edit().clear().commit()
            assertEquals(ThemeMode.DARK, AppPreferences(context).themeMode())
        }
    }

    @Test
    fun unitReadDoesNotInitializeThemeOrOverwriteWidgetPreferences() {
        val stored = context.getSharedPreferences("noonlet_widgets", 0)
        stored.edit().putString("fixture", "value").commit()
        val before = stored.all.toMap()
        val preferences = AppPreferences(context)
        assertEquals(TemperatureUnit.CELSIUS, preferences.temperatureUnit())
        assertTrue(preferences.setTemperatureUnit(TemperatureUnit.FAHRENHEIT))
        assertEquals(TemperatureUnit.FAHRENHEIT, AppPreferences(context).temperatureUnit())
        assertFalse(context.getSharedPreferences("noonlet_app", 0).contains("theme_mode"))
        assertEquals(before, stored.all)
        assertEquals(ThemeMode.DARK, preferences.themeMode())
    }

    @Test
    fun explicitSelectionsPersistAndReportOnlyEffectiveChanges() {
        val preferences = AppPreferences(context)
        assertFalse(preferences.setThemeMode(ThemeMode.DARK))
        assertTrue(preferences.setThemeMode(ThemeMode.SYSTEM))
        assertFalse(preferences.setThemeMode(ThemeMode.SYSTEM))
        assertTrue(preferences.setThemeMode(ThemeMode.LIGHT))
        assertFalse(preferences.setThemeMode(ThemeMode.LIGHT))
        assertTrue(preferences.setThemeMode(ThemeMode.DARK))
        assertEquals(ThemeMode.DARK, AppPreferences(context).themeMode())
        assertFalse(preferences.setTemperatureUnit(TemperatureUnit.CELSIUS))
        assertEquals("celsius", context.getSharedPreferences("noonlet_app", 0).getString("temperature_unit", null))
        assertTrue(preferences.setTemperatureUnit(TemperatureUnit.FAHRENHEIT))
        assertFalse(preferences.setTemperatureUnit(TemperatureUnit.FAHRENHEIT))
        assertTrue(preferences.setTemperatureUnit(TemperatureUnit.CELSIUS))
    }

    @Test
    fun malformedPreferencesUseDefaultsAndCanBeReplaced() {
        val stored = context.getSharedPreferences("noonlet_app", 0)
        val preferences = AppPreferences(context)
        for (invalid in listOf("unknown", "", "LIGHT")) {
            stored.edit().putString("theme_mode", invalid).putString("temperature_unit", invalid).commit()
            assertEquals(ThemeMode.DARK, preferences.themeMode())
            assertEquals(TemperatureUnit.CELSIUS, preferences.temperatureUnit())
        }
        stored.edit().putInt("theme_mode", 4).putBoolean("temperature_unit", true).commit()
        assertEquals(ThemeMode.DARK, preferences.themeMode())
        assertEquals(TemperatureUnit.CELSIUS, preferences.temperatureUnit())
        assertTrue(preferences.setThemeMode(ThemeMode.SYSTEM))
        assertFalse(preferences.setTemperatureUnit(TemperatureUnit.CELSIUS))
        assertEquals("system", stored.getString("theme_mode", null))
        assertEquals("celsius", stored.getString("temperature_unit", null))
    }
}

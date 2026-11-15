package app.noonlet

import android.content.Context
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

internal enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");
}

internal enum class TemperatureUnit(val key: String, val symbol: String) {
    CELSIUS("celsius", "°C"),
    FAHRENHEIT("fahrenheit", "°F");

    fun formatCelsius(temperatureC: Double, locale: Locale): String {
        require(temperatureC.isFinite())
        val value = if (this == FAHRENHEIT) temperatureC * 9 / 5 + 32 else temperatureC
        require(value.isFinite())
        return NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 0
            isGroupingUsed = false
        }.format(value.roundToInt()) + symbol
    }
}

internal class AppPreferences(context: Context) {
    private val app = context.applicationContext
    private val stored = app.getSharedPreferences("noonlet_app", Context.MODE_PRIVATE)

    fun themeMode(): ThemeMode {
        val value = string("theme_mode")
        if (stored.contains("theme_mode"))
            return ThemeMode.entries.firstOrNull { it.key == value } ?: ThemeMode.DARK
        return ThemeMode.DARK.also {
            stored.edit().putString("theme_mode", it.key).apply()
        }
    }

    fun setThemeMode(mode: ThemeMode): Boolean {
        val changed = themeMode() != mode
        if (string("theme_mode") != mode.key)
            stored.edit().putString("theme_mode", mode.key).apply()
        return changed
    }

    fun temperatureUnit(): TemperatureUnit {
        val value = string("temperature_unit")
        return TemperatureUnit.entries.firstOrNull { it.key == value } ?: TemperatureUnit.CELSIUS
    }

    fun setTemperatureUnit(unit: TemperatureUnit): Boolean {
        val changed = temperatureUnit() != unit
        if (string("temperature_unit") != unit.key)
            stored.edit().putString("temperature_unit", unit.key).apply()
        return changed
    }

    private fun string(key: String): String? =
        try {
            stored.getString(key, null)
        } catch (_: ClassCastException) {
            null
        }
}

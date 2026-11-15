package app.noonlet

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class TemperatureUnitTest {
    @Test
    fun knownValuesConvertBeforeRounding() {
        val expectations = listOf(
            Triple(-40.0, "-40°C", "-40°F"),
            Triple(0.0, "0°C", "32°F"),
            Triple(20.0, "20°C", "68°F"),
            Triple(70.0, "70°C", "158°F"),
            Triple(.4, "0°C", "33°F"),
            Triple(-.4, "0°C", "31°F"),
            Triple(-12.8, "-13°C", "9°F"),
            Triple(-100.0, "-100°C", "-148°F"),
        )
        for ((temperature, celsius, fahrenheit) in expectations) {
            assertEquals(celsius, TemperatureUnit.CELSIUS.formatCelsius(temperature, Locale.US))
            assertEquals(fahrenheit, TemperatureUnit.FAHRENHEIT.formatCelsius(temperature, Locale.US))
        }
    }

    @Test
    fun everySupportedLocaleHasWholeTemperaturesAndNoNegativeZero() {
        for (tag in listOf("en", "de", "es", "fr", "it", "pl", "pt", "ru", "uk")) {
            val locale = Locale.forLanguageTag(tag)
            assertEquals("0°C", TemperatureUnit.CELSIUS.formatCelsius(-.1, locale))
            assertEquals("33°F", TemperatureUnit.FAHRENHEIT.formatCelsius(.4, locale))
            assertEquals("158°F", TemperatureUnit.FAHRENHEIT.formatCelsius(70.0, locale))
        }
    }

    @Test
    fun nonFiniteInputIsRejected() {
        for (unit in TemperatureUnit.entries)
            for (temperature in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
                try {
                    unit.formatCelsius(temperature, Locale.US)
                    fail("Non-finite temperatures cannot be formatted")
                } catch (_: IllegalArgumentException) { }
            }
        try {
            TemperatureUnit.FAHRENHEIT.formatCelsius(Double.MAX_VALUE, Locale.US)
            fail("Conversion overflow cannot be formatted")
        } catch (_: IllegalArgumentException) { }
    }
}

package app.noonlet

enum class WeatherTextFormat(val wireValue: String) {
    CONDITION_TEMPERATURE("condition_temperature"),
    TEMPERATURE_CONDITION("temperature_condition"),
    TEMPERATURE("temperature");

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wireValue == value } ?: CONDITION_TEMPERATURE
    }
}

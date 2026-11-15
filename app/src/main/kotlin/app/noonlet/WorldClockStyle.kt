package app.noonlet

enum class WorldClockStyle(val wireValue: String) {
    AUTO("auto"), LIGHT("light"), DARK("dark");

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wireValue == value } ?: AUTO
    }
}

package app.noonlet

internal enum class EditorSection(val key: String, val titleResource: Int) {
    TIME("time", R.string.time),
    DATE("date", R.string.date),
    WEATHER("weather", R.string.weather),
    APPEARANCE("appearance", R.string.appearance),
    PADDING("padding", R.string.padding),
    ALARM("alarm", R.string.alarm),
    ACTIONS("actions", R.string.tap_actions),
    CITIES("cities", R.string.cities_and_countries),
    FOREGROUND("foreground", R.string.text_colors),
    WORLD_BACKGROUND("worldBackground", R.string.card_colors);

    companion object {
        fun fromKey(key: String): EditorSection? = values().firstOrNull { it.key == key }
    }
}

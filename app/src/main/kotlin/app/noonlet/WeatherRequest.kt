package app.noonlet

internal data class WeatherRequest(
    val enabled: Boolean,
    val useDevice: Boolean,
    val encodedPlace: String,
) {
    val place = WeatherPlace.decode(encodedPlace)

    val key: String
        get() = if (useDevice) "device" else "manual:${place?.key ?: ""}"

    companion object {
        fun capture(settings: WidgetSettings) =
            WeatherRequest(settings.weatherEnabled, settings.weatherUseDevice, settings.weatherPlace)
    }
}

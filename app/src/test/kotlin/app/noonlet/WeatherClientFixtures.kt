package app.noonlet

internal fun weatherClient(read: (String) -> String) =
    WeatherClient { address -> WeatherResponse(read(address)) }

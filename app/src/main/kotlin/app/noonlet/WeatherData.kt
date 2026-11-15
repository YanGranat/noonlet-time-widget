package app.noonlet

import org.json.JSONObject

internal fun JSONObject.wholeNumber(name: String): Long {
    val value = get(name)
    require(value is Number)
    return java.math.BigDecimal(value.toString()).longValueExact()
}

internal fun JSONObject.number(name: String): Double {
    val value = get(name)
    require(value is Number)
    return value.toDouble().also { require(it.isFinite()) }
}

data class WeatherPlace(val name: String, val latitudeE6: Int, val longitudeE6: Int) {
    init {
        require(latitudeE6 in -90_000_000..90_000_000)
        require(longitudeE6 in -180_000_000..180_000_000)
        require(name.length <= 200 && name.none(Char::isISOControl))
    }

    internal val key: String
        get() = "$latitudeE6:$longitudeE6"

    fun encode(): String =
        JSONObject()
            .put("name", name)
            .put("latitudeE6", latitudeE6)
            .put("longitudeE6", longitudeE6)
            .toString()

    companion object {
        fun decode(encoded: String): WeatherPlace? =
            if (encoded.length > 2048) null
            else
                runCatching {
                    val data = JSONObject(encoded)
                    require(data.get("name") is String)
                    WeatherPlace(
                        data.getString("name"),
                        Math.toIntExact(data.wholeNumber("latitudeE6")),
                        Math.toIntExact(data.wholeNumber("longitudeE6")),
                    )
                }
                    .getOrNull()
    }
}

data class WeatherSnapshot(
    val temperatureC: Double,
    val weatherCode: Int,
    val isDay: Boolean,
    val validAt: Long,
    val fetchedAt: Long,
    val serverTimeAtFetch: Long = fetchedAt,
) {
    internal fun usable(now: Long): Boolean =
        temperatureC.isFinite() &&
            temperatureC in -100.0..70.0 &&
            WeatherCondition.fromCode(weatherCode) != null &&
            fetchedAt > 0 &&
            fetchedAt <= now &&
            validAt > 0 &&
            serverTimeAtFetch > 0 &&
            validAt <= serverTimeAtFetch &&
            serverTimeAtFetch - validAt <= MAX_AGE - (now - fetchedAt) &&
            now - fetchedAt <= MAX_AGE

    internal fun fresh(now: Long): Boolean =
        usable(now) &&
            now - fetchedAt < REFRESH_INTERVAL &&
            serverTimeAtFetch - validAt + (now - fetchedAt) < MAX_FRESH_VALID_TIME_AGE

    companion object {
        internal const val REFRESH_INTERVAL = 30 * 60 * 1000L
        private const val MAX_FRESH_VALID_TIME_AGE = 45 * 60 * 1000L
        internal const val MAX_AGE = 6 * 60 * 60 * 1000L
    }
}

enum class WeatherStatus {
    LOADING,
    FRESH,
    STALE,
    UNAVAILABLE,
    PERMISSION_REQUIRED,
    LOCATION_DISABLED,
}

data class WeatherState(
    val snapshot: WeatherSnapshot? = null,
    val status: WeatherStatus = WeatherStatus.LOADING,
    val place: WeatherPlace? = null,
    val failureStatus: WeatherStatus? = null,
)

internal class WeatherFailure(val status: WeatherStatus) : Exception()

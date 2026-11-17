package app.noonlet

import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToInt
import org.json.JSONObject

internal data class WeatherResponse(val body: String, val serverTime: Long? = null)

internal class WeatherClient(private val read: (String) -> WeatherResponse = WeatherHttp.shared::read) {
    fun current(place: WeatherPlace, now: () -> Long): WeatherSnapshot {
        val latitude = place.latitudeE6 / 1_000_000.0
        val longitude = place.longitudeE6 / 1_000_000.0
        val response = read(
                "https://api.open-meteo.com/v1/forecast?latitude=${coordinate(latitude)}&longitude=${coordinate(longitude)}" +
                    "&current=temperature_2m,weather_code,is_day&temperature_unit=celsius" +
                    "&timezone=GMT&timeformat=unixtime&forecast_days=1",
            )
        val fetchedAt = now()
        return parseCurrent(response.body, fetchedAt, response.serverTime ?: fetchedAt)
    }

    fun search(query: String, language: String): List<WeatherPlace> {
        val text = query.trim().take(100)
        if (text.length < 2) return emptyList()
        val locale =
            language.lowercase(Locale.ROOT).takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "en"
        val encoded = URLEncoder.encode(text, StandardCharsets.UTF_8.name())
        return parsePlaces(
            read(
                "https://geocoding-api.open-meteo.com/v1/search?name=$encoded" +
                    "&count=10&language=$locale&format=json",
            ).body,
        )
    }

    companion object {
        internal const val SOURCE = "open-meteo"

        private fun coordinate(value: Double) = String.format(Locale.ROOT, "%.6f", value)

        internal fun parseCurrent(
            response: String,
            now: Long,
            serverTimeAtFetch: Long = now,
        ): WeatherSnapshot {
            val root = responseObject(response)
            require(root.getString("timezone") == "GMT")
            require(root.wholeNumber("utc_offset_seconds") == 0L)
            val units = root.getJSONObject("current_units")
            require(units.getString("time") == "unixtime")
            require(units.getString("interval") == "seconds")
            require(units.getString("temperature_2m") == "\u00b0C")
            require(units.getString("weather_code") == "wmo code")
            require(units.getString("is_day").isEmpty())
            val current = root.getJSONObject("current")
            require(current.wholeNumber("interval") > 0L)
            val day = current.wholeNumber("is_day")
            require(day == 0L || day == 1L)
            return WeatherSnapshot(
                    current.number("temperature_2m"),
                    Math.toIntExact(current.wholeNumber("weather_code")),
                    day == 1L,
                    Math.multiplyExact(current.wholeNumber("time"), 1000L),
                    now,
                    serverTimeAtFetch,
                )
                .also { require(it.usable(now)) }
        }

        internal fun parsePlaces(response: String): List<WeatherPlace> {
            val results = responseObject(response).optJSONArray("results") ?: return emptyList()
            return (0 until minOf(results.length(), 10))
                .mapNotNull { index ->
                    runCatching {
                        val place = results.getJSONObject(index)
                        val featureCode =
                            if (place.isNull("feature_code")) ""
                            else place.getString("feature_code").trim()
                        require(featureCode.isEmpty() || featureCode.startsWith("PPL"))
                        val latitude = place.number("latitude")
                        val longitude = place.number("longitude")
                        require(latitude.isFinite() && latitude in -90.0..90.0)
                        require(longitude.isFinite() && longitude in -180.0..180.0)
                        val name = place.getString("name").trim()
                        require(name.isNotEmpty())
                        val details =
                            listOf(place.optString("admin1"), place.optString("country"))
                                .filter { it.isNotBlank() && it != name }
                                .distinct()
                        WeatherPlace(
                            (listOf(name) + details).joinToString(", ").take(200),
                            (latitude * 1_000_000).roundToInt(),
                            (longitude * 1_000_000).roundToInt(),
                        )
                    }
                        .getOrNull()
                }
                .distinctBy { it.key }
        }

        internal fun requireSuccess(status: Int) {
            if (status == HttpsURLConnection.HTTP_OK) return
            if (status == 408 || status == 429 || status in 500..599)
                throw IOException("Weather request failed")
            throw WeatherFailure(WeatherStatus.UNAVAILABLE)
        }

        private fun responseObject(response: String): JSONObject {
            try {
                JsonEnvelope.validate(response, 256 * 1024, 32)
            } catch (error: IllegalArgumentException) {
                throw IOException("Invalid weather response", error)
            }
            return JSONObject(response)
        }
    }
}

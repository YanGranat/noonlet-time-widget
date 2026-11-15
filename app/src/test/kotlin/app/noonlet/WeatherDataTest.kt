package app.noonlet

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36])
class WeatherDataTest {
    private val now = 1_800_000_000_000L

    @Test
    fun nestingGuardIgnoresQuotedBracketsAndEscapedQuotes() {
        val json = JSONObject().put("results", org.json.JSONArray().put(
            JSONObject().put("name", "[".repeat(50) + "\"quoted\"")
                .put("latitude", 12).put("longitude", 34))).toString()
        assertEquals("[".repeat(50) + "\"quoted\"", WeatherClient.parsePlaces(json).single().name)
    }

    @Test
    fun nonstandardJsonAndOversizedBodiesAreRejected() {
        for (body in listOf("{'results':[]}", "{/* comment */\"results\":[]}", " ".repeat(262145))) {
            assertThrows(java.io.IOException::class.java) { WeatherClient.parsePlaces(body) }
        }
    }

    @Test
    fun excessiveJsonNestingIsRejectedBeforeRecursiveParsing() {
        val nested = "[".repeat(10_000) + "0" + "]".repeat(10_000)
        for (payload in listOf("{\"current\":$nested}", "{\"results\":$nested}")) {
            assertThrows(java.io.IOException::class.java) { WeatherClient.parseCurrent(payload, now) }
            assertThrows(java.io.IOException::class.java) { WeatherClient.parsePlaces(payload) }
        }
    }

    @Test
    fun geographicSelectionRetainsMicrodegreesAndRejectsInvalidCoordinates() {
        val place = WeatherPlace("Riverton", 51_123_456, -1_654_321)
        assertEquals(place, WeatherPlace.decode(place.encode()))
        assertNull(
            WeatherPlace.decode(
                "{\"name\":\"Riverton\",\"latitudeE6\":51123456.7,\"longitudeE6\":0}",
            ),
        )
        assertNull(
            WeatherPlace.decode(
                "{\"name\":\"Riverton\",\"latitudeE6\":90000001,\"longitudeE6\":0}",
            ),
        )
        assertNull(
            WeatherPlace.decode(
                "{\"name\":\"Riverton\",\"latitudeE6\":0,\"longitudeE6\":4294967296}",
            ),
        )
        assertNull(WeatherPlace.decode("{"))
    }

    @Test
    fun serverTimeKeepsFreshnessIndependentOfTheDeviceClockOffset() {
        for (offset in listOf(-20 * 60_000L, 75 * 60_000L)) {
            val client = WeatherClient { WeatherResponse(response(), now) }
            val localTime = now + offset
            val snapshot = client.current(WeatherPlace("Meadow", 500, -700)) { localTime }
            assertEquals(now - 10 * 60_000L, snapshot.validAt)
            assertEquals(localTime, snapshot.fetchedAt)
            assertEquals(now, snapshot.serverTimeAtFetch)
            assertTrue(snapshot.fresh(localTime))
            assertFalse(snapshot.fresh(localTime + WeatherSnapshot.REFRESH_INTERVAL))
            assertFalse(snapshot.usable(localTime + WeatherSnapshot.MAX_AGE + 1))
        }
    }

    @Test
    fun nearZeroCoordinatesUseFixedDecimalDegrees() {
        var requested = ""
        val client = weatherClient { requested = it; response() }
        client.current(WeatherPlace("Meadow", 500, -700)) { now }
        assertTrue(requested.contains("latitude=0.000500&longitude=-0.000700"))
    }

    @Test
    fun currentWeatherReadsCelsiusAndUnixValidityTime() {
        val snapshot = WeatherClient.parseCurrent(response(), now)
        assertEquals(12.5, snapshot.temperatureC, 0.0)
        assertEquals(61, snapshot.weatherCode)
        assertTrue(snapshot.isDay)
        assertEquals(now - 10 * 60_000L, snapshot.validAt)
        assertEquals(now, snapshot.fetchedAt)
        val night = JSONObject(response()).apply { getJSONObject("current").put("is_day", 0) }
        assertFalse(WeatherClient.parseCurrent(night.toString(), now).isDay)
    }

    @Test
    fun integerFieldsRejectStringsFractionsMissingValuesAndOverflow() {
        for (field in listOf("weather_code", "is_day", "time", "interval")) {
            val malformed = listOf(1.5, "1", true, JSONObject.NULL)
            val values = if (field == "interval") malformed else malformed + Long.MAX_VALUE
            for (value in values) {
                val root = JSONObject(response())
                root.getJSONObject("current").put(field, value)
                assertThrows("$field=$value", Exception::class.java) {
                    WeatherClient.parseCurrent(root.toString(), now)
                }
            }
            val root = JSONObject(response())
            root.getJSONObject("current").remove(field)
            assertThrows(Exception::class.java) { WeatherClient.parseCurrent(root.toString(), now) }
        }
    }

    @Test
    fun invalidCurrentPayloadsNeverBecomeDefaultWeather() {
        for (change in
            listOf<(JSONObject) -> Unit>(
                { it.remove("current") },
                { it.put("current", JSONObject.NULL) },
                { it.getJSONObject("current").remove("temperature_2m") },
                { it.getJSONObject("current").put("temperature_2m", "NaN") },
                { it.getJSONObject("current").put("temperature_2m", "Infinity") },
                { it.getJSONObject("current").put("temperature_2m", "12.5") },
                { it.getJSONObject("current").put("temperature_2m", 120) },
                { it.getJSONObject("current").put("temperature_2m", -101) },
                { it.getJSONObject("current").put("temperature_2m", true) },
                { it.getJSONObject("current").put("weather_code", 4) },
                { it.getJSONObject("current").put("is_day", 2) },
                { it.getJSONObject("current").put("is_day", -1) },
                { it.getJSONObject("current").put("interval", 0) },
                { it.getJSONObject("current").put("interval", -1) },
            )) {
            val root = JSONObject(response()).also(change)
            assertThrows(Exception::class.java) { WeatherClient.parseCurrent(root.toString(), now) }
        }
    }

    @Test
    fun unitsAndUtcMetadataMustMatchTheRequestedFormat() {
        for (change in
            listOf<(JSONObject) -> Unit>(
                { it.remove("current_units") },
                { it.remove("timezone") },
                { it.remove("utc_offset_seconds") },
                { it.put("timezone", "Europe/Paris") },
                { it.put("utc_offset_seconds", 3600) },
                { it.put("utc_offset_seconds", 0.5) },
                { it.put("utc_offset_seconds", "0") },
                { it.getJSONObject("current_units").remove("temperature_2m") },
                { it.getJSONObject("current_units").put("temperature_2m", "\u00b0F") },
                { it.getJSONObject("current_units").put("time", "iso8601") },
                { it.getJSONObject("current_units").put("interval", "milliseconds") },
                { it.getJSONObject("current_units").put("weather_code", "condition") },
                { it.getJSONObject("current_units").put("is_day", "boolean") },
            )) {
            val root = JSONObject(response()).also(change)
            assertThrows(Exception::class.java) { WeatherClient.parseCurrent(root.toString(), now) }
        }
    }

    @Test
    fun futureExpiredAndOverflowingValidityTimesAreRejected() {
        val seconds = now / 1000L
        for (time in listOf(0L, -1L, seconds + 1, seconds - 6 * 3600L - 1, Long.MAX_VALUE)) {
            val root = JSONObject(response())
            root.getJSONObject("current").put("time", time)
            assertThrows("time=$time", Exception::class.java) {
                WeatherClient.parseCurrent(root.toString(), now)
            }
        }
        val boundary = JSONObject(response())
        boundary.getJSONObject("current").put("time", seconds - 6 * 3600L)
        val snapshot = WeatherClient.parseCurrent(boundary.toString(), now)
        assertEquals(now - WeatherSnapshot.MAX_AGE, snapshot.validAt)
        assertFalse(snapshot.fresh(now))
    }

    @Test
    fun freshnessPreservesValidityBoundsAndFetchAge() {
        val value = WeatherSnapshot(12.5, 2, true, now - 14 * 60_000, now)
        assertTrue(value.fresh(now + 29 * 60_000))
        assertFalse(value.fresh(now + 30 * 60_000))
        assertFalse(value.copy(validAt = now - 45 * 60_000).fresh(now))
        assertTrue(value.copy(validAt = now - 44 * 60_000).fresh(now))
        assertFalse(value.copy(validAt = now + 1).usable(now))
        assertFalse(value.copy(fetchedAt = now + 1).usable(now))
        assertTrue(value.copy(validAt = now - WeatherSnapshot.MAX_AGE).usable(now))
        assertFalse(value.copy(validAt = now - WeatherSnapshot.MAX_AGE - 1).usable(now))
    }

    @Test
    fun aNewFetchDoesNotMakeAnOldModelValueFresh() {
        val value = WeatherSnapshot(12.5, 2, true, now - 2 * 60 * 60_000L, now)
        assertTrue(value.usable(now))
        assertFalse(value.fresh(now))
        assertTrue(value.copy(validAt = now).fresh(now))
        assertTrue(value.copy(validAt = now).usable(now + WeatherSnapshot.MAX_AGE))
        assertFalse(value.copy(validAt = now).usable(now + WeatherSnapshot.MAX_AGE + 1))
        assertFalse(value.copy(fetchedAt = now + 1).usable(now))
        assertFalse(value.copy(fetchedAt = 0).usable(now))
        assertFalse(value.copy(validAt = 0).usable(now))
    }

    @Test
    fun currentRequestPreservesCoordinatePrecisionAndFractionalTemperature() {
        var address = ""
        val selected = WeatherPlace("Riverton", 51_123_456, -1_654_321)
        val client = weatherClient {
            address = it
            response(12.345)
        }
        val value = client.current(selected) { now }
        assertEquals(
            "https://api.open-meteo.com/v1/forecast?latitude=51.123456&longitude=-1.654321" +
                "&current=temperature_2m,weather_code,is_day&temperature_unit=celsius" +
                "&timezone=GMT&timeformat=unixtime&forecast_days=1",
            address,
        )
        assertEquals(12.345, value.temperatureC, 0.0)
        assertEquals(now - 10 * 60_000L, value.validAt)
        assertEquals(now, value.fetchedAt)
        assertEquals(-0.25, WeatherClient.parseCurrent(response(-0.25), now).temperatureC, 0.0)
    }

    @Test
    fun documentedWmoConditionCodesArePreservedWithoutRemapping() {
        val codes = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65, 66, 67,
            71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 97, 99)
        for (code in codes) {
            val root = JSONObject(response())
            root.getJSONObject("current").put("weather_code", code)
            val value = WeatherClient.parseCurrent(root.toString(), now)
            assertEquals("Condition $code", code, value.weatherCode)
        }
        for (code in listOf(-1, 4, 68, 69, 79, 100, 113)) {
            val root = JSONObject(response())
            root.getJSONObject("current").put("weather_code", code)
            assertThrows(Exception::class.java) { WeatherClient.parseCurrent(root.toString(), now) }
        }
    }

    @Test
    fun searchPreservesLocationIdentityAndDisambiguatesNames() {
        val places =
            WeatherClient.parsePlaces(
                """{"results":[
            {"name":"Riverton","admin1":"North Coast","country":"Example Republic","latitude":51.123456,"longitude":-1.654321},
            {"name":"Riverton","admin1":"Riverton","country":"Example Republic","latitude":52.000001,"longitude":-1.000001},
            {"name":"Invalid","latitude":91,"longitude":0}
        ]}""",
            )
        assertEquals(2, places.size)
        assertEquals("Riverton, North Coast, Example Republic", places[0].name)
        assertEquals("Riverton, Example Republic", places[1].name)
        assertEquals(51_123_456, places[0].latitudeE6)
        assertEquals(-1_654_321, places[0].longitudeE6)
        assertNotEquals(places[0].key, places[1].key)
    }

    @Test
    fun settlementSearchExcludesAirportsAndLandmarksWithoutDroppingUnclassifiedPlaces() {
        val places =
            WeatherClient.parsePlaces(
                """{"results":[
            {"name":"Riverton","admin1":"North Coast","country":"Example Republic","feature_code":"PPLA","latitude":51.123456,"longitude":-1.654321},
            {"name":"Riverton","admin1":"North Coast","country":"Example Republic","feature_code":"AIRP","latitude":51.222222,"longitude":-1.654321},
            {"name":"Riverton","admin1":"North Coast","country":"Example Republic","feature_code":"AIRF","latitude":51.333333,"longitude":-1.654321},
            {"name":"Hilltop","feature_code":"MT","latitude":52.5,"longitude":2},
            {"name":"Cedar City","feature_code":"PPLC","latitude":52.000001,"longitude":-1.000001},
            {"name":"North Bay","latitude":53.5,"longitude":-1.7},
            {"name":"South Bay","feature_code":null,"latitude":-53.5,"longitude":-1.7}
        ]}""",
            )
        assertEquals(4, places.size)
        assertEquals("Riverton, North Coast, Example Republic", places[0].name)
        assertEquals(51_123_456, places[0].latitudeE6)
        assertEquals(-1_654_321, places[0].longitudeE6)
        assertEquals("Cedar City", places[1].name)
        assertEquals(52_000_001, places[1].latitudeE6)
        assertEquals("North Bay", places[2].name)
        assertEquals(53_500_000, places[2].latitudeE6)
        assertEquals("South Bay", places[3].name)
        assertEquals(-53_500_000, places[3].latitudeE6)
    }

    private fun response(temperature: Double = 12.5): String =
        JSONObject()
            .put("timezone", "GMT")
            .put("utc_offset_seconds", 0)
            .put(
                "current_units",
                JSONObject()
                    .put("time", "unixtime")
                    .put("interval", "seconds")
                    .put("temperature_2m", "\u00b0C")
                    .put("weather_code", "wmo code")
                    .put("is_day", ""),
            )
            .put(
                "current",
                JSONObject()
                    .put("time", now / 1000L - 10 * 60)
                    .put("interval", 900)
                    .put("temperature_2m", temperature)
                    .put("weather_code", 61)
                    .put("is_day", 1),
            )
            .toString()
}

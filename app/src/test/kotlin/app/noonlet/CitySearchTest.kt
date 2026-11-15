package app.noonlet

import android.content.Context
import android.content.res.Configuration
import android.icu.text.TimeZoneNames
import java.util.ArrayList
import java.util.Locale
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36])
class CitySearchTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    private val languages = listOf("de", "en", "es", "fr", "it", "pl", "pt", "ru", "uk")

    @Before
    fun prepareSearchProfiles() {
        for (language in languages) {
            val locale = Locale.forLanguageTag(language)
            val future = CityCatalog.prepare(context, locale)
            val ready = future.get(30, TimeUnit.SECONDS)
            val warm = CityCatalog.prepare(context, locale)
            assertSame(
                "A completed locale profile must reuse its city list",
                ready,
                warm.get(30, TimeUnit.SECONDS),
            )
        }
    }

    @Test
    fun queriesKeepLocalizedNamesPunctuationAndIanaAliases() {
        for (query: String in
            arrayOf<String>("San Francisco", "SAN-FRANCISCO", "сан франциско", "САН-ФРАНЦИСКО")) {
            val found: List<Place> = CityCatalog.search(context, query, Locale.ENGLISH)
            assertEquals(1, found.size)
            assertEquals(CityCatalog.SAN_FRANCISCO, found.get(0).id)
        }
        val shared: List<Place> = CityCatalog.search(context, "America/Los_Angeles", Locale.ENGLISH)
        assertTrue(shared.stream().anyMatch({ c -> c.id.equals(CityCatalog.SAN_FRANCISCO) }))
        assertTrue(shared.stream().anyMatch({ c -> c.id.equals("America/Los_Angeles") }))
        assertTrue(
            CityCatalog.search(context, "Not an existing place 731", Locale.ENGLISH).isEmpty(),
        )
    }

    @Test
    fun searchPreservesNameMatchesAndOrderWhileAddingCountryAndZoneAliases() {
        val original: MutableList<Place> = ArrayList(CityCatalog.all())
        val ordered = CityCatalog.prepare(context, Locale.ENGLISH).get(30, TimeUnit.SECONDS)
        for (query: String in
            arrayOf<String>(
                "",
                " - / ",
                "rIyAdH",
                "Эр-Рияд",
                "UTC",
                "country:",
                "Japan",
                "япония",
                "New_York",
            )) {
            val needle: String = normalize(query)
            val expected: MutableList<Place> = ArrayList()
            for (city: Place in ordered) {
                val fields =
                    (listOf(city.id, city.zoneId) +
                            languages.map {
                                CityCatalog.name(context, city.id, Locale.forLanguageTag(it))
                            })
                        .joinToString(" ")
                if (normalize(fields).contains(needle)) expected.add(city)
            }
            val actual: List<Place> = CityCatalog.search(context, query, Locale.ENGLISH)
            assertEquals(query, expected, actual.filter { it in expected })
            assertEquals(original, CityCatalog.all())
        }
    }

    @Test
    fun catalogRemovesZoneAliasesWhileSearchAcceptsCountriesAndUnaccentedNames() {
        val zones = CityCatalog.all().filter { it.id == it.zoneId && it.id != "UTC" }
        assertTrue(zones.isNotEmpty())
        for (place in zones) {
            val canonical = android.icu.util.TimeZone.getCanonicalID(place.id)
            if (canonical != null && canonical in java.time.ZoneId.getAvailableZoneIds())
                assertEquals("Canonical zone for ${place.id}", place.id, canonical)
            assertEquals(place.id, java.time.ZoneId.of(place.id).id)
        }
        assertTrue(CityCatalog.isValidSelection("US/Pacific"))
        assertTrue(CityCatalog.search(context, "US/Pacific", Locale.ENGLISH)
            .any { it.id == "America/Los_Angeles" })
        assertTrue(CityCatalog.search(context, "Osterreich", Locale.GERMAN)
            .any { it.zoneId == "Europe/Vienna" })
        for (zone in listOf("Europe/Lisbon", "Atlantic/Azores"))
            assertTrue(CityCatalog.search(context, "Portugal", Locale.ENGLISH).any { it.zoneId == zone })
    }

    @Test
    fun everyLocalizedCityAliasRemainsSearchableInEveryApplicationLanguage() {
        for (city in listOf(CityCatalog.SAN_FRANCISCO, "Asia/Riyadh")) {
            for (nameLanguage in languages) {
                val name = CityCatalog.name(context, city, Locale.forLanguageTag(nameLanguage))
                for (uiLanguage in languages) {
                    val locale = Locale.forLanguageTag(uiLanguage)
                    assertTrue(
                        "$nameLanguage alias in $uiLanguage search: $name",
                        CityCatalog.search(context, name, locale).any { it.id == city },
                    )
                }
            }
        }
    }

    @Test
    fun localizedLabelsMatchNativeCityAndCountryNamesAndTheCanonicalCityResource() {
        val germany = CityCatalog.all().first { it.id.startsWith("country:DE:") }
        for (language in languages) {
            val locale = Locale.forLanguageTag(language)
            val riyadh = TimeZoneNames.getInstance(locale).getExemplarLocationName("Asia/Riyadh")
            assertNotNull("Native exemplar name for $language", riyadh)
            if (language != "en") {
                assertNotEquals(
                    "The $language fixture must distinguish the city name",
                    "Riyadh",
                    riyadh,
                )
            }
            assertEquals(riyadh, CityCatalog.name(context, "Asia/Riyadh", locale))
            val country = Locale.Builder().setRegion("DE").build().getDisplayCountry(locale)
            assertEquals(country, CountryCatalog.name(germany.id, locale))
            assertEquals(country, CityCatalog.name(context, germany.id, locale))
            val configuration =
                Configuration(context.resources.configuration).apply { setLocale(locale) }
            val sanFrancisco =
                context
                    .createConfigurationContext(configuration)
                    .getString(R.string.city_san_francisco)
            assertEquals(sanFrancisco, CityCatalog.name(context, CityCatalog.SAN_FRANCISCO, locale))
        }
    }

    private fun normalize(input: String): String {
        val result: StringBuilder = StringBuilder()
        for (point: Int in input.lowercase(Locale.ROOT).codePoints().toArray()) if (
            Character.isLetterOrDigit(point)
        )
            result.appendCodePoint(point)
        return result.toString()
    }
}

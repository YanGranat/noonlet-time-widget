package app.noonlet

import android.content.Context
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransition
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneRules
import java.util.Arrays
import java.util.Collections
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36])
class CountryRulesTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun isoRegionsWithOneFutureCivilClockAreSelectableAndMultiZoneRegionsAreNot() {
        val entries: List<Place> = CountryCatalog.entries(NOW)
        for (code: String in arrayOf<String>("GE", "JP", "PL", "DE", "AR")) {
            val country: Place =
                entries
                    .stream()
                    .filter({ c -> c.id.startsWith("country:" + code + ":") })
                    .findFirst()
                    .get()
            assertTrue(country.country)
            assertEquals(country.zoneId, CityCatalog.timeZoneId(country.id))
            assertEquals(
                Locale("", code).getDisplayCountry(Locale.ENGLISH),
                CityCatalog.name(context, country.id, Locale.ENGLISH),
            )
            assertEquals(
                Locale("", code).getDisplayCountry(Locale("ru")),
                CityCatalog.name(context, country.id, Locale("ru")),
            )
        }
        for (code: String in arrayOf<String>("US", "RU", "CN")) assertFalse(
            code,
            entries.stream().anyMatch({ c -> c.id.startsWith("country:" + code + ":") }),
        )
    }

    @Test
    fun aliasAndHistoricalDifferencesDoNotManufactureSeveralCurrentClocks() {
        assertNotNull(
            CountryCatalog.representativeZone(
                arrayOf<String>("Europe/Berlin", "Europe/Busingen"),
                NOW,
            ),
        )
        assertNotNull(
            CountryCatalog.representativeZone(
                arrayOf<String>("America/Argentina/Buenos_Aires", "America/Argentina/San_Luis"),
                NOW,
            ),
        )
        val old: ZoneOffsetTransition =
            ZoneOffsetTransition.of(LocalDateTime.of(2000, 1, 1, 0, 0), ZoneOffset.UTC, ONE)
        val historical: ZoneRules =
            ZoneRules.of(
                ZoneOffset.UTC,
                ZoneOffset.UTC,
                Collections.emptyList(),
                Collections.singletonList(old),
                Collections.emptyList(),
            )
        assertFalse(historical.equals(ZoneRules.of(ONE)))
        assertTrue(CountryCatalog.sameCivilTimeFrom(historical, ZoneRules.of(ONE), NOW))
    }

    @Test
    fun oneUnknownMemberRejectsTheEntireCandidateRegion() {
        assertNull(
            CountryCatalog.representativeZone(
                arrayOf<String>("Europe/Berlin", "Unknown/Not_A_Zone"),
                NOW,
            ),
        )
        assertNull(CountryCatalog.representativeZone(arrayOf<String?>("Europe/Berlin", null), NOW))
        assertNull(CountryCatalog.representativeZone(arrayOfNulls<String>(0), NOW))
        assertNull(
            CountryCatalog.representativeZone(
                arrayOf<String>("America/New_York", "America/Los_Angeles"),
                NOW,
            ),
        )
    }

    @Test
    fun canonicalizationCannotHideAResolvableRegionalClockDifference() {
        assertNull(
            CountryCatalog.representativeZone(
                arrayOf<String>("Europe/Berlin", "Asia/Tokyo"),
                NOW,
                { id -> "Europe/Berlin" },
            ),
        )
    }

    @Test
    fun equalRecurringMetadataDoesNotMakeUnseededRulesObserveDst() {
        val active: ZoneRules = seasonal(-1, -1)
        val unseeded: ZoneRules =
            ZoneRules.of(
                ONE,
                ONE,
                Collections.emptyList(),
                Collections.emptyList(),
                active.getTransitionRules(),
            )
        assertEquals(active.getOffset(NOW), unseeded.getOffset(NOW))
        assertEquals(active.getTransitionRules(), unseeded.getTransitionRules())
        assertNull(unseeded.nextTransition(NOW))
        assertNotNull(active.nextTransition(NOW))
        assertNotEquals(
            active.getOffset(Instant.parse("2026-07-15T12:00:00Z")),
            unseeded.getOffset(Instant.parse("2026-07-15T12:00:00Z")),
        )
        assertFalse(CountryCatalog.sameCivilTimeFrom(unseeded, active, NOW))
    }

    @Test
    fun anExplicitFutureChangeCannotBeHiddenByTodayOrTheNextFewYears() {
        val future: ZoneOffsetTransition =
            ZoneOffsetTransition.of(LocalDateTime.of(2040, 6, 1, 0, 0), ONE, TWO)
        val changing: ZoneRules =
            ZoneRules.of(
                ONE,
                ONE,
                Collections.emptyList(),
                Collections.singletonList(future),
                Collections.emptyList(),
            )
        assertEquals(ZoneRules.of(ONE).getOffset(NOW), changing.getOffset(NOW))
        assertEquals(ONE, changing.getOffset(Instant.parse("2030-01-01T00:00:00Z")))
        assertFalse(CountryCatalog.sameCivilTimeFrom(ZoneRules.of(ONE), changing, NOW))
    }

    @Test
    fun matchingWinterAndTheWholeFirstYearDoNotProveMatchingRecurringRules() {
        val a: ZoneRules = seasonal(-1, -1)
        val b: ZoneRules = seasonal(-1, 24)
        for (date: String in
            arrayOf<String>(
                "2026-01-15T12:00:00Z",
                "2026-07-15T12:00:00Z",
                "2026-12-15T12:00:00Z",
            )) assertEquals(a.getOffset(Instant.parse(date)), b.getOffset(Instant.parse(date)))
        assertFalse(CountryCatalog.sameCivilTimeFrom(a, b, NOW))
        assertFalse(CountryCatalog.sameCivilTimeFrom(a, ZoneRules.of(ONE), NOW))
    }

    @Test
    fun differentRecurringExpressionsCanDescribeTheSameCompleteFutureClock() {
        val lastSunday: ZoneRules = seasonal(-1, -1)
        val sundayOnOrAfter25: ZoneRules = seasonal(25, 25)
        assertNotEquals(lastSunday.getTransitionRules(), sundayOnOrAfter25.getTransitionRules())
        assertTrue(CountryCatalog.sameCivilTimeFrom(lastSunday, sundayOnOrAfter25, NOW))
    }

    @Test
    fun aSavedCountryIdKeepsItsCapturedZoneIndependentlyOfCurrentEligibility() {
        val historical: String = CountryCatalog.selectionId("DE", "Europe/Busingen")
        assertEquals("Europe/Busingen", CityCatalog.timeZoneId(historical))
        assertEquals("Germany", CityCatalog.name(context, historical, Locale.ENGLISH))
        assertEquals("Германия", CityCatalog.name(context, historical, Locale("ru")))
        // A data update can make a previously uniform region multi-zone. The captured clock remains
        // valid.
        assertTrue(
            CityCatalog.isValidSelection(CountryCatalog.selectionId("US", "America/New_York")),
        )
        assertFalse(CityCatalog.isValidSelection("country:ZZ:Europe/Berlin"))
        assertFalse(CityCatalog.isValidSelection("country:DE:Unknown/Not_A_Zone"))
    }

    companion object {
        private val NOW: Instant = Instant.parse("2026-01-15T12:00:00Z")
        private val ONE: ZoneOffset = ZoneOffset.ofHours(1)
        private val TWO: ZoneOffset = ZoneOffset.ofHours(2)

        private fun seasonal(springDay: Int, autumnDay: Int): ZoneRules {
            val spring: ZoneOffsetTransitionRule =
                ZoneOffsetTransitionRule.of(
                    Month.MARCH,
                    springDay,
                    DayOfWeek.SUNDAY,
                    LocalTime.of(2, 0),
                    false,
                    ZoneOffsetTransitionRule.TimeDefinition.WALL,
                    ONE,
                    ONE,
                    TWO,
                )
            val autumn: ZoneOffsetTransitionRule =
                ZoneOffsetTransitionRule.of(
                    Month.OCTOBER,
                    autumnDay,
                    DayOfWeek.SUNDAY,
                    LocalTime.of(3, 0),
                    false,
                    ZoneOffsetTransitionRule.TimeDefinition.WALL,
                    ONE,
                    TWO,
                    ONE,
                )
            return ZoneRules.of(
                ONE,
                ONE,
                Collections.emptyList(),
                Arrays.asList(spring.createTransition(2025), autumn.createTransition(2025)),
                Arrays.asList(spring, autumn),
            )
        }
    }
}

package app.noonlet

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneOffsetTransition
import java.time.zone.ZoneRules
import java.util.Locale
import java.util.TreeSet

/** ISO country/region selections whose installed time-zone rules agree from now onward. */
object CountryCatalog {
    private val ISO_CODES = Locale.getISOCountries().toSet()
    private val ZONE_IDS = ZoneId.getAvailableZoneIds().toSet()

    fun entries(reference: Instant): List<Place> = ISO_CODES.mapNotNull { code ->
        representativeZone(android.icu.util.TimeZone.getAvailableIDs(code), reference)?.let { zone
            ->
            Place(
                selectionId(code, zone),
                zone,
            )
        }
    }

    fun selectionId(country: String, zoneId: String): String =
        "${Place.COUNTRY_PREFIX}${country.uppercase(Locale.ROOT)}:$zoneId".also { timeZoneId(it) }

    /**
     * The zone is captured in the ID, so a later tzdata change cannot discard an existing
     * selection.
     */
    fun timeZoneId(selectionId: String): String {
        val separator = selectionId.indexOf(':', Place.COUNTRY_PREFIX.length)
        if (!selectionId.startsWith(Place.COUNTRY_PREFIX) || separator < 0)
            throw DateTimeException("Invalid country selection")
        val code =
            selectionId.substring(Place.COUNTRY_PREFIX.length, separator).uppercase(Locale.ROOT)
        val zone = selectionId.substring(separator + 1)
        if (code !in ISO_CODES || zone !in ZONE_IDS)
            throw DateTimeException("Unknown country or time zone")
        return ZoneId.of(zone).id
    }

    fun name(selectionId: String, locale: Locale): String {
        timeZoneId(selectionId)
        val code =
            selectionId.substring(
                Place.COUNTRY_PREFIX.length,
                selectionId.indexOf(':', Place.COUNTRY_PREFIX.length),
            )
        return Locale("", code).getDisplayCountry(locale)
    }

    /**
     * An unresolvable member rejects the whole region; it must not create a false single-zone
     * result.
     */
    fun representativeZone(rawIds: Array<out String?>?, reference: Instant): String? =
        representativeZone(rawIds, reference) { android.icu.util.TimeZone.getCanonicalID(it) }

    fun representativeZone(
        rawIds: Array<out String?>?,
        reference: Instant,
        canonicalizer: (String) -> String?,
    ): String? {
        if (rawIds.isNullOrEmpty()) return null
        val canonical = TreeSet<String>()
        val observed = mutableSetOf<String>()
        for (raw in rawIds) {
            if (raw.isNullOrEmpty()) return null
            val id = canonicalizer(raw) ?: return null
            if (id !in ZONE_IDS) return null
            canonical += id
            observed += id
            // ICU aliases and java.time rules can come from different data revisions.
            if (raw in ZONE_IDS) observed += raw
        }
        val first = canonical.firstOrNull() ?: return null
        val common = ZoneId.of(first).rules
        return first.takeIf {
            observed.all { sameCivilTimeFrom(common, ZoneId.of(it).rules, reference) }
        }
    }

    fun sameCivilTimeFrom(first: ZoneRules, second: ZoneRules, reference: Instant): Boolean {
        if (first == second) return true
        if (first.getOffset(reference) != second.getOffset(reference)) return false
        val futureFirst = futureTransitions(first, reference)
        val futureSecond = futureTransitions(second, reference)
        if (
            futureFirst == futureSecond &&
                first.transitionRules == second.transitionRules &&
                first.nextTransition(reference) == second.nextTransition(reference)
        )
            return true

        var finalYear = reference.atOffset(ZoneOffset.UTC).year
        for (transition in futureFirst + futureSecond) finalYear =
            maxOf(finalYear, transition.instant.atOffset(ZoneOffset.UTC).year)
        // After finite transitions, 400 complete years cover the Gregorian recurring cycle.
        val horizon = LocalDate.of(finalYear + 401, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
        var cursor = reference
        while (true) {
            val a = first.nextTransition(cursor)
            val b = second.nextTransition(cursor)
            val aInRange = a != null && a.instant.isBefore(horizon)
            val bInRange = b != null && b.instant.isBefore(horizon)
            if (!aInRange || !bInRange) return aInRange == bInRange
            if (a.instant != b.instant || a.offsetAfter != b.offsetAfter) return false
            cursor = a.instant.plusNanos(1)
        }
    }

    private fun futureTransitions(
        rules: ZoneRules,
        reference: Instant,
    ): List<ZoneOffsetTransition> = rules.transitions.filter { it.instant.isAfter(reference) }
}

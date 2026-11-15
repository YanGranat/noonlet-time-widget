package app.noonlet

import android.content.Context
import android.icu.text.TimeZoneNames
import java.text.Collator
import java.text.Normalizer
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

object CityCatalog {
    const val SAN_FRANCISCO = "city:san-francisco"
    private val ZONE_IDS = ZoneId.getAvailableZoneIds()

    private class NamedPlace(val place: Place, val name: Int)

    private val namedPlaces =
        listOf(NamedPlace(Place(SAN_FRANCISCO, "America/Los_Angeles"), R.string.city_san_francisco))
            .associateBy { it.place.id }

    private object Entries {
        val zoneAliases = ZONE_IDS.groupBy(::canonicalZone)
        val all = build()
    }

    private class Names(val locale: Locale) {
        val zones: TimeZoneNames = TimeZoneNames.getInstance(locale)
        val values = ConcurrentHashMap<String, String>()
    }

    private class Profile(val cities: List<Place>, val searchKeys: Map<String, String>)

    private val nameCaches = LinkedHashMap<String, Names>(10, .75f, true)
    private val profiles = LinkedHashMap<String, CompletableFuture<Profile>>(10, .75f, true)
    private var aliases: CompletableFuture<Map<String, String>>? = null

    fun all(): List<Place> = Entries.all

    fun prepare(
        context: Context,
        locale: Locale = AppLocales.resourceLocale(context),
    ): CompletableFuture<List<Place>> = profile(context, locale).thenApply { it.cities }

    private fun build(): List<Place> {
        val cities = mutableListOf(Place("UTC"))
        cities += namedPlaces.values.map { it.place }
        for (id in ZONE_IDS) {
            if ('/' !in id || id.startsWith("Etc/") || id.startsWith("SystemV/")) continue
            if (canonicalZone(id) != id) continue
            cities += Place(id)
        }
        cities += CountryCatalog.entries(Instant.now())
        cities.sortBy { it.id }
        return Collections.unmodifiableList(cities)
    }

    private fun searchAliases(context: Context): CompletableFuture<Map<String, String>> {
        synchronized(profiles) {
            aliases?.let {
                return it
            }
            val application = context.applicationContext
            return CompletableFuture.supplyAsync {
                    val languages = AppLocales.supportedTags.map(Locale::forLanguageTag)
                    val countries = mutableMapOf<String, String>()
                    val keys = LinkedHashMap<String, String>()
                    for (city in all()) {
                        val names = languages.map { name(application, city.id, it) }.distinct()
                        val zone = canonicalZone(city.zoneId)
                        val aliases = Entries.zoneAliases[zone].orEmpty().joinToString(" ")
                        val region = runCatching { android.icu.util.TimeZone.getRegion(zone) }.getOrNull()
                        val country = if (region == null || region == "001") "" else
                            countries.getOrPut(region) {
                                val countryLocale = Locale.Builder().setRegion(region).build()
                                languages.map { countryLocale.getDisplayCountry(it) }.distinct().joinToString(" ")
                            }
                        keys[city.id] =
                            searchText("${city.id} ${city.zoneId} $aliases $country ${names.joinToString(" ")}")
                    }
                    Collections.unmodifiableMap(keys)
                }
                .also { aliases = it }
        }
    }

    private fun profile(context: Context, locale: Locale): CompletableFuture<Profile> {
        val key = locale.toLanguageTag()
        synchronized(profiles) {
            profiles[key]?.let {
                return it
            }
            val application = context.applicationContext
            val future =
                searchAliases(application).thenApplyAsync { shared ->
                    val entries = all()
                    val labels = entries.associate { it.id to name(application, it.id, locale) }
                    val collator = Collator.getInstance(locale)
                    val sorted = entries.sortedWith { first, second ->
                        val compared =
                            collator.compare(labels.getValue(first.id), labels.getValue(second.id))
                        if (compared == 0) first.id.compareTo(second.id) else compared
                    }
                    val keys = entries.associate {
                        it.id to shared.getValue(it.id) + searchText(labels.getValue(it.id))
                    }
                    Profile(Collections.unmodifiableList(sorted), Collections.unmodifiableMap(keys))
                }
            profiles[key] = future
            if (profiles.size > AppLocales.supportedTags.size + 1)
                profiles.remove(profiles.keys.first())
            return future
        }
    }

    fun timeZoneId(selectionId: String): String =
        when {
            selectionId in namedPlaces -> namedPlaces.getValue(selectionId).place.zoneId
            selectionId.startsWith(Place.COUNTRY_PREFIX) -> CountryCatalog.timeZoneId(selectionId)
            else -> ZoneId.of(selectionId).id
        }

    fun isValidSelection(selectionId: String?): Boolean {
        if (selectionId.isNullOrEmpty()) return false
        return try {
            timeZoneId(selectionId)
            true
        } catch (_: DateTimeException) {
            false
        }
    }

    /** A pending profile is represented by the picker loading state, never a main-thread wait. */
    fun search(
        context: Context,
        query: String,
        locale: Locale = AppLocales.resourceLocale(context),
    ): List<Place> {
        val prepared = profile(context, locale)
        if (!prepared.isDone || prepared.isCompletedExceptionally) return emptyList()
        val ready = prepared.getNow(null) ?: return emptyList()
        val needle = searchText(query)
        return if (needle.isEmpty()) ready.cities
        else ready.cities.filter { needle in ready.searchKeys.getValue(it.id) }
    }

    private fun searchText(value: String): String = buildString {
        Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .codePoints().filter(Character::isLetterOrDigit).forEach {
            appendCodePoint(it)
        }
    }

    private fun canonicalZone(id: String): String =
        android.icu.util.TimeZone.getCanonicalID(id)?.takeIf { it in ZONE_IDS } ?: id

    internal fun selectionKey(id: String): String =
        if (id in ZONE_IDS && id != "UTC") canonicalZone(id) else id

    fun name(context: Context, selectionId: String?, locale: Locale): String {
        val id = selectionId?.takeIf(String::isNotEmpty) ?: ZoneId.systemDefault().id
        if (id == "UTC") return "UTC"
        namedPlaces[id]?.let {
            return AppLocales.text(context, locale, it.name)
        }
        if (id.startsWith(Place.COUNTRY_PREFIX)) return CountryCatalog.name(id, locale)
        val key = locale.toLanguageTag()
        val existing = synchronized(nameCaches) { nameCaches[key] }
        val names =
            existing
                ?: Names(locale).let { created ->
                    synchronized(nameCaches) {
                        nameCaches[key]
                            ?: created.also {
                                nameCaches[key] = it
                                if (nameCaches.size > AppLocales.supportedTags.size + 1)
                                    nameCaches.remove(nameCaches.keys.first())
                            }
                    }
                }
        return names.values.computeIfAbsent(id) {
            names.zones.getExemplarLocationName(canonicalZone(it)) ?: it.substringAfterLast('/').replace('_', ' ')
        }
    }
}

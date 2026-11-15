package app.noonlet

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.LinkedHashMap
import java.util.Locale
import java.util.WeakHashMap

/** Resource locales are separate from the phone locale used by native clocks. */
object AppLocales {
    val supportedTags: List<String> = listOf("de", "en", "es", "fr", "it", "pl", "pt", "ru", "uk")

    private class ResourceCache(val configuration: Configuration) {
        val locales = LinkedHashMap<String, Resources>(supportedTags.size + 1, .75f, true)
    }

    private val resourceCaches = WeakHashMap<Resources, ResourceCache>()

    fun isSupported(tag: String): Boolean =
        Locale.forLanguageTag(tag.replace('_', '-')).language in supportedTags

    fun uiLanguage(locale: Locale): String = locale.language.takeIf { it in supportedTags } ?: "en"

    fun resourceLocale(context: Context, phoneLocale: Locale? = null): Locale {
        val selected = language(context)
        if (isSupported(selected)) return appLocale(context, phoneLocale)
        val system = Resources.getSystem().configuration.locales
        if (phoneLocale != null && isSupported(phoneLocale.language)) return phoneLocale
        for (index in 0 until system.size()) {
            val candidate = system[index]
            if (isSupported(candidate.language)) return candidate
        }
        return Locale.ENGLISH
    }

    fun text(context: Context, id: Int, vararg args: Any): String =
        text(context, resourceLocale(context), id, *args)

    fun text(context: Context, locale: Locale, id: Int, vararg args: Any): String =
        resources(context, locale).let {
            if (args.isEmpty()) it.getString(id) else it.getString(id, *args)
        }

    fun resources(context: Context, locale: Locale): Resources {
        val target = if (isSupported(locale.language)) locale else Locale.ENGLISH
        val base = context.resources
        val configuration = base.configuration
        if (!configuration.locales.isEmpty && configuration.locales[0] == target) return base
        synchronized(resourceCaches) {
            var cache = resourceCaches[base]
            if (cache == null || cache.configuration != configuration) {
                cache = ResourceCache(Configuration(configuration))
                resourceCaches[base] = cache
            }
            val key = target.toLanguageTag()
            cache.locales[key]?.let {
                return it
            }
            val localized = Configuration(configuration).apply { setLocale(target) }
            val result = context.createConfigurationContext(localized).resources
            cache.locales[key] = result
            if (cache.locales.size > supportedTags.size + 1)
                cache.locales.remove(cache.locales.keys.first())
            return result
        }
    }

    private const val DELIVERED_WIDGET_LANGUAGE = "delivered_widget_language"
    private const val LANGUAGE_TAG = "language_tag"
    private const val LOCALE_MANAGER_MIGRATED = "locale_manager_migrated"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("noonlet_widgets", Context.MODE_PRIVATE)

    fun language(context: Context): String =
        prefs(context).getString("language", "system") ?: "system"

    /** Refresh the cheap language mirror at lifecycle boundaries, never for each translated row. */
    fun refreshLanguage(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val manager = context.getSystemService(LocaleManager::class.java) ?: return false
        val preferences = prefs(context)
        var requested = manager.applicationLocales
        if (!preferences.getBoolean(LOCALE_MANAGER_MIGRATED, false)) {
            val previous = language(context)
            if (requested.isEmpty && isSupported(previous)) {
                requested = LocaleList.forLanguageTags(appLocale(context).toLanguageTag())
                manager.applicationLocales = requested
            }
            preferences.edit().putBoolean(LOCALE_MANAGER_MIGRATED, true).apply()
        }
        var actual = if (requested.isEmpty) "system" else "en"
        var tag = if (requested.isEmpty) "" else "en"
        for (index in 0 until requested.size()) {
            val candidate = requested[index]
            if (AppLocales.isSupported(candidate.language)) {
                actual = candidate.language
                tag = candidate.toLanguageTag()
                break
            }
        }
        if (actual == language(context) && tag == preferences.getString(LANGUAGE_TAG, ""))
            return false
        preferences.edit().putString("language", actual).putString(LANGUAGE_TAG, tag).apply()
        return true
    }

    fun appLocale(context: Context, resolvedPhoneLocale: Locale? = null): Locale {
        val selected = language(context)
        if (!AppLocales.isSupported(selected)) return resolvedPhoneLocale ?: phoneLocale(context)
        val saved = prefs(context).getString(LANGUAGE_TAG, selected) ?: selected
        val full = Locale.forLanguageTag(saved.replace('_', '-'))
        return if (full.language == selected) full else Locale.forLanguageTag(selected)
    }

    fun phoneLocale(context: Context): Locale =
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java)?.systemLocales?.get(0)
                ?: android.content.res.Resources.getSystem().configuration.locales[0]
        } else android.content.res.Resources.getSystem().configuration.locales[0]

    internal fun selectedLanguageTag(context: Context): String {
        val language = language(context)
        if (language == "system") return language
        return prefs(context).getString(LANGUAGE_TAG, language)?.ifEmpty { language } ?: language
    }

    fun setLanguage(context: Context, language: String) {
        val selected = Locale.forLanguageTag(language.replace('_', '-'))
        val lang = selected.language.takeIf(AppLocales::isSupported) ?: "system"
        val tag = if (lang == "system") "" else selected.toLanguageTag()
        prefs(context).edit().putString("language", lang).putString(LANGUAGE_TAG, tag)
            .putBoolean(LOCALE_MANAGER_MIGRATED, Build.VERSION.SDK_INT >= 33).apply()
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(LocaleManager::class.java)
            val requested =
                if (lang == "system") LocaleList.getEmptyLocaleList()
                else LocaleList.forLanguageTags(tag)
            if (manager != null && requested != manager.applicationLocales)
                manager.applicationLocales = requested
        }
    }

    /**
     * Includes the full app locale: alarm formats use its region and calendar, not only UI labels.
     */
    fun widgetLanguageSignature(context: Context): String {
        val phone = phoneLocale(context)
        val app = appLocale(context, phone)
        val ui = resourceLocale(context, phone)
        val localizedDates = ui.language == phone.language
        return "${app.toLanguageTag()}|${phone.toLanguageTag()}|${ui.toLanguageTag()}|$localizedDates"
    }

    fun widgetLanguageDelivered(context: Context, signature: String) =
        signature == prefs(context).getString(DELIVERED_WIDGET_LANGUAGE, null)

    fun markWidgetLanguageDelivered(context: Context, signature: String) {
        if (signature == widgetLanguageSignature(context))
            prefs(context).edit().putString(DELIVERED_WIDGET_LANGUAGE, signature).apply()
    }
}

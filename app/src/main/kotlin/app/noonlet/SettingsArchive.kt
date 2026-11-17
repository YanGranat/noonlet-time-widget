package app.noonlet

import android.appwidget.AppWidgetManager
import android.content.Context
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

internal object SettingsArchive {
    const val MAX_BYTES = 256 * 1024
    data class Configuration(val size: WidgetSize, val settings: WidgetSettings, val binding: String? = null)
    data class Group(val draft: Configuration, val widgets: List<Configuration>)
    data class Contents(val language: String, val theme: ThemeMode, val unit: TemperatureUnit,
        val worldTab: Boolean, val clock: Group, val world: Group)

    fun capture(context: Context): Contents {
        val editor = EditorPreferences(context)
        val imported = ImportedWidgetConfigurations.all(context)
        fun group(world: Boolean): Group {
            val preset = editor.previewSize(world, WidgetSize.entries.size).coerceAtLeast(0)
            val ids = WidgetInstances.manageableIds(context, BaseWidgetProvider.boundIds(context, world), world)
            val placed = ids.map { Configuration(widgetSize(context, it, world), WidgetSettingsStore.load(context, it, world),
                WidgetSettingsStore.identity(context, it, world)) }
            return Group(Configuration(WidgetSize.entries[preset], WidgetSettingsStore.loadUnplaced(context, world)),
                placed + imported.filter { it.world == world }.map { it.configuration })
        }
        val preferences = AppPreferences(context)
        return Contents(AppLocales.selectedLanguageTag(context), preferences.themeMode(), preferences.temperatureUnit(),
            editor.worldTab(), group(false), group(true))
    }

    private fun widgetSize(context: Context, id: Int, world: Boolean): WidgetSize {
        val manager = AppWidgetManager.getInstance(context)
        val options = manager.getAppWidgetOptions(id)
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
        if (width > 0 && height > 0)
            return WidgetSize.entries.first { it.columns == (if (width >= 245) 4 else 2) &&
                it.rows == (if (height >= 136) 2 else 1) }
        val provider = manager.getAppWidgetInfo(id)?.provider
        return WidgetSize.entries.firstOrNull { BaseWidgetProvider.component(context, world, it) == provider }
            ?: WidgetSize.TWO_BY_ONE
    }

    fun encode(contents: Contents): String {
        fun group(value: Group) = JSONObject().put("draft", configurationJson(value.draft))
            .put("widgets", JSONArray(value.widgets.map(::configurationJson)))
        val text = JSONObject().put("format", "noonlet.settings").put("version", 1)
            .put("application", JSONObject().put("language", contents.language).put("theme", contents.theme.key)
                .put("temperatureUnit", contents.unit.key).put("worldTab", contents.worldTab))
            .put("clock", group(contents.clock)).put("world", group(contents.world)).toString(2)
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        return text
    }

    fun read(input: InputStream): Contents {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_BYTES) throw IOException("Settings file is too large")
            output.write(buffer, 0, count)
        }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(output.toByteArray())).toString().removePrefix("\uFEFF")
        return decode(text)
    }

    fun decode(text: String): Contents {
        checkEnvelope(text)
        val tokenizer = JSONTokener(text.removePrefix("\uFEFF"))
        val root = tokenizer.nextValue() as? JSONObject ?: error("Invalid settings object")
        require(tokenizer.nextClean() == '\u0000')
        require(root.getString("format") == "noonlet.settings" && root.get("version") == 1)
        val application = root.getJSONObject("application")
        val language = application.get("language") as? String ?: error("Invalid language")
        require(language.length <= 64 && (language == "system" || AppLocales.isSupported(Locale.forLanguageTag(language).language)))
        val theme = ThemeMode.entries.first { it.key == application.get("theme") }
        val unit = TemperatureUnit.entries.first { it.key == application.get("temperatureUnit") }
        val worldTab = application.get("worldTab") as? Boolean ?: error("Invalid editor tab")
        fun group(key: String, world: Boolean): Group {
            val value = root.getJSONObject(key)
            val items = value.getJSONArray("widgets")
            require(items.length() <= 64)
            val widgets = (0 until items.length()).map { decodeConfiguration(items.getJSONObject(it), world) }
            val bindings = widgets.mapNotNull { it.binding }
            require(bindings.distinct().size == bindings.size)
            return Group(decodeConfiguration(value.getJSONObject("draft"), world), widgets)
        }
        return Contents(language, theme, unit, worldTab, group("clock", false), group("world", true))
    }

    internal fun configurationJson(value: Configuration): JSONObject =
        JSONObject().put("size", value.size.name).put("settings", SettingsCodec.encode(value.settings))
            .apply { value.binding?.let { put("binding", it) } }

    internal fun decodeConfiguration(value: JSONObject, world: Boolean): Configuration {
        val size = WidgetSize.valueOf(value.getString("size"))
        val binding = if (value.has("binding")) value.getString("binding").also {
            require(it.matches(Regex("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")))
        } else null
        val settings = WidgetSettings.defaults(world)
        val data = value.getJSONObject("settings")
        val schema = SettingsCodec.encode(settings)
        require(data.length() > 0)
        for (key in data.keys()) {
            require(schema.has(key))
            val expected = schema.get(key)
            val field = data.get(key)
            when (expected) {
                is Boolean -> require(field is Boolean)
                is Int -> require(field is Int)
                is String -> require(field is String && field.length <= 2048)
                is JSONArray -> {
                    require(field is JSONArray && field.length() in 1..4)
                    val places = (0 until field.length()).map { field.get(it) }
                    require(places.all { it is String && CityCatalog.isValidSelection(it) })
                    require(places.map { CityCatalog.selectionKey(it as String) }.distinct().size == places.size)
                }
                else -> error("Unsupported setting")
            }
        }
        val place = data.optString("cfg_weatherPlace")
        if (place.isNotEmpty()) {
            checkEnvelope(place)
            require(WeatherPlace.decode(place) != null)
        }
        SettingsCodec.decode(settings, data)
        require(ClockPattern.validate(settings.timeFormat) && ClockPattern.validate(settings.dateFormat))
        require(settings.alarmFormat.trim { it <= ' ' }.isNotEmpty() && settings.alarmFormat.length <= 96)
        SimpleDateFormat(settings.alarmFormat, Locale.ROOT)
        return Configuration(size, settings, binding)
    }

    fun restore(context: Context, contents: Contents) {
        val names = listOf("noonlet_widgets", "widget_drafts", "widget_configuration", "MainActivity",
            "noonlet_app", "noonlet_imported_widgets")
        val stores = names.associateWith { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        val previous = stores.mapValues { it.value.all.toMap() }
        val oldLanguage = AppLocales.selectedLanguageTag(context)
        try {
            val pending = mutableListOf<Pair<Boolean, Configuration>>()
            val editor = EditorPreferences(context)
            for ((world, group) in listOf(false to contents.clock, true to contents.world)) {
                WidgetSettingsStore.save(context, 0, world, group.draft.settings)
                WidgetSettingsStore.saveUnplaced(context, world, group.draft.settings)
                editor.selectPreviewSize(world, group.draft.size.ordinal)
                val ids = WidgetInstances.manageableIds(context, BaseWidgetProvider.boundIds(context, world), world)
                val bindings = ids.associateBy { WidgetSettingsStore.identity(context, it, world) }
                group.widgets.forEach { configuration ->
                    val id = configuration.binding?.let(bindings::get)
                    if (id != null) WidgetSettingsStore.save(context, id, world, configuration.settings)
                    else pending += world to configuration
                }
            }
            ImportedWidgetConfigurations.replace(context, pending)
            stores.getValue("MainActivity").edit().putBoolean("world_tab", contents.worldTab).apply()
            AppPreferences(context).apply {
                setThemeMode(contents.theme)
                setTemperatureUnit(contents.unit)
            }
            editor.recordImport()
            AppLocales.setLanguage(context, contents.language)
            check(stores.values.all { it.edit().commit() })
        } catch (error: Exception) {
            for ((name, values) in previous) {
                val edit = stores.getValue(name).edit().clear()
                for ((key, value) in values) when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
                edit.commit()
            }
            AppLocales.setLanguage(context, oldLanguage)
            throw error
        }
    }

    private fun checkEnvelope(text: String) {
        JsonEnvelope.validate(text, MAX_BYTES, 12)
    }
}

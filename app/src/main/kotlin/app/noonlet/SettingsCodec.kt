package app.noonlet

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import java.util.Objects
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object SettingsCodec {
    const val TAP_DEFAULT_VERSION = 1
    const val TAP_DEFAULT_VERSION_KEY = "tapDefaultVersion"
    const val EDITOR_TAP_DEFAULT_VERSION = "editor_tap_default_version"

    fun writeEditorState(settings: WidgetSettings, out: Bundle) {
        writeTo(settings, out)
        out.putInt(EDITOR_TAP_DEFAULT_VERSION, TAP_DEFAULT_VERSION)
    }

    fun sameEditorSettings(first: Bundle?, second: Bundle?): Boolean {
        if (first === second) return true
        if (first == null || second == null) return false
        for (key in first.keySet()) if (
            key != EDITOR_TAP_DEFAULT_VERSION &&
                (!second.containsKey(key) || !Objects.deepEquals(first.get(key), second.get(key)))
        )
            return false
        for (key in second.keySet()) if (
            key != EDITOR_TAP_DEFAULT_VERSION && !first.containsKey(key)
        )
            return false
        return true
    }

    fun snapshot(settings: WidgetSettings): Bundle =
        Bundle().also { writeEditorState(settings, it) }

    private class Setting(
        val key: String,
        val write: (WidgetSettings, Bundle) -> Unit,
        val read: (WidgetSettings, Bundle) -> Unit,
    )

    private fun string(
        name: String,
        get: (WidgetSettings) -> String,
        set: (WidgetSettings, String) -> Unit,
    ): Setting {
        val key = "cfg_$name"
        return Setting(
            key,
            { settings, out -> out.putString(key, get(settings)) },
            { settings, input -> set(settings, input.getString(key) ?: get(settings)) },
        )
    }

    private fun integer(
        name: String,
        get: (WidgetSettings) -> Int,
        set: (WidgetSettings, Int) -> Unit,
    ): Setting {
        val key = "cfg_$name"
        return Setting(
            key,
            { settings, out -> out.putInt(key, get(settings)) },
            { settings, input -> set(settings, input.getInt(key, get(settings))) },
        )
    }

    private fun boolean(
        name: String,
        get: (WidgetSettings) -> Boolean,
        set: (WidgetSettings, Boolean) -> Unit,
    ): Setting {
        val key = "cfg_$name"
        return Setting(
            key,
            { settings, out -> out.putBoolean(key, get(settings)) },
            { settings, input -> set(settings, input.getBoolean(key, get(settings))) },
        )
    }

    private val schema =
        listOf(
            string("zone", { it.zoneId }, { settings, value -> settings.zoneId = value }),
            string("style", { it.style.wireValue }, { settings, value -> settings.style = WorldClockStyle.fromWire(value) }),
            string("align", { it.alignment.wireValue }, { settings, value -> settings.alignment = WidgetAlignment.fromWire(value) }),
            integer("size", { it.textSize }, { settings, value -> settings.textSize = value }),
            integer("opacity", { it.opacity }, { settings, value -> settings.opacity = value }),
            integer("padding", { it.padding }, { settings, value -> settings.padding = value }),
            boolean("date", { it.showDate }, { settings, value -> settings.showDate = value }),
            boolean("24", { it.hour24 }, { settings, value -> settings.hour24 = value }),
            boolean("outline", { it.outline }, { settings, value -> settings.outline = value }),
            Setting(
                "cfg_cities",
                { settings, out -> out.putStringArray("cfg_cities", settings.cities.copyOf()) },
                { settings, input ->
                    input.getStringArray("cfg_cities")?.let { settings.cities = it.copyOf() }
                },
            ),
            string(
                "fontFamily",
                { it.fontFamily },
                { settings, value -> settings.fontFamily = value },
            ),
            string(
                "orientation",
                { it.orientation.wireValue },
                { settings, value -> settings.orientation = WidgetOrientation.fromWire(value) },
            ),
            string(
                "timeFormat",
                { it.timeFormat },
                { settings, value -> settings.timeFormat = value },
            ),
            string(
                "dateFormat",
                { it.dateFormat },
                { settings, value -> settings.dateFormat = value },
            ),
            string(
                "alarmFormat",
                { it.alarmFormat },
                { settings, value -> settings.alarmFormat = value },
            ),
            string(
                "dateAlignment",
                { it.dateAlignment.wireValue },
                { settings, value -> settings.dateAlignment = WidgetAlignment.fromWire(value) },
            ),
            string(
                "alarmAlignment",
                { it.alarmAlignment.wireValue },
                { settings, value -> settings.alarmAlignment = WidgetAlignment.fromWire(value) },
            ),
            string(
                "tapAction",
                { it.tapAction },
                { settings, value -> settings.tapAction = value },
            ),
            string(
                "timeTapAction",
                { it.timeTapAction },
                { settings, value -> settings.timeTapAction = value },
            ),
            string(
                "dateTapAction",
                { it.dateTapAction },
                { settings, value -> settings.dateTapAction = value },
            ),
            string(
                "alarmTapAction",
                { it.alarmTapAction },
                { settings, value -> settings.alarmTapAction = value },
            ),
            boolean(
                "showAlarm",
                { it.showAlarm },
                { settings, value -> settings.showAlarm = value },
            ),
            boolean(
                "showNoAlarm",
                { it.showNoAlarm },
                { settings, value -> settings.showNoAlarm = value },
            ),
            boolean(
                "showAlarmIcon",
                { it.showAlarmIcon },
                { settings, value -> settings.showAlarmIcon = value },
            ),
            boolean(
                "advancedTap",
                { it.advancedTap },
                { settings, value -> settings.advancedTap = value },
            ),
            integer(
                "timeColor",
                { it.timeColor },
                { settings, value -> settings.timeColor = value },
            ),
            integer(
                "dateColor",
                { it.dateColor },
                { settings, value -> settings.dateColor = value },
            ),
            integer(
                "alarmColor",
                { it.alarmColor },
                { settings, value -> settings.alarmColor = value },
            ),
            integer("dateSize", { it.dateSize }, { settings, value -> settings.dateSize = value }),
            integer(
                "alarmSize",
                { it.alarmSize },
                { settings, value -> settings.alarmSize = value },
            ),
            integer(
                "paddingTop",
                { it.paddingTop },
                { settings, value -> settings.paddingTop = value },
            ),
            integer(
                "paddingBottom",
                { it.paddingBottom },
                { settings, value -> settings.paddingBottom = value },
            ),
            integer(
                "paddingLeft",
                { it.paddingLeft },
                { settings, value -> settings.paddingLeft = value },
            ),
            integer(
                "paddingRight",
                { it.paddingRight },
                { settings, value -> settings.paddingRight = value },
            ),
            integer(
                "timeOffsetX",
                { it.timeOffsetX },
                { settings, value -> settings.timeOffsetX = value },
            ),
            integer(
                "timeOffsetY",
                { it.timeOffsetY },
                { settings, value -> settings.timeOffsetY = value },
            ),
            integer(
                "dateOffsetX",
                { it.dateOffsetX },
                { settings, value -> settings.dateOffsetX = value },
            ),
            integer(
                "dateOffsetY",
                { it.dateOffsetY },
                { settings, value -> settings.dateOffsetY = value },
            ),
            integer(
                "alarmOffsetX",
                { it.alarmOffsetX },
                { settings, value -> settings.alarmOffsetX = value },
            ),
            integer(
                "alarmOffsetY",
                { it.alarmOffsetY },
                { settings, value -> settings.alarmOffsetY = value },
            ),
            integer(
                "timeDateGap",
                { it.timeDateGap },
                { settings, value -> settings.timeDateGap = value },
            ),
            integer(
                "fontWeight",
                { it.fontWeight },
                { settings, value -> settings.fontWeight = value },
            ),
            integer(
                "timeFontWeight",
                { it.timeFontWeight },
                { settings, value -> settings.timeFontWeight = value },
            ),
            integer(
                "dateFontWeight",
                { it.dateFontWeight },
                { settings, value -> settings.dateFontWeight = value },
            ),
            integer(
                "worldDayTextColor",
                { it.worldDayTextColor },
                { settings, value -> settings.worldDayTextColor = value },
            ),
            integer(
                "worldNightTextColor",
                { it.worldNightTextColor },
                { settings, value -> settings.worldNightTextColor = value },
            ),
            integer(
                "worldDayBackgroundColor",
                { it.worldDayBackgroundColor },
                { settings, value -> settings.worldDayBackgroundColor = value },
            ),
            integer(
                "worldNightBackgroundColor",
                { it.worldNightBackgroundColor },
                { settings, value -> settings.worldNightBackgroundColor = value },
            ),
            integer(
                "worldBackdropColor",
                { it.worldBackdropColor },
                { settings, value -> settings.worldBackdropColor = value },
            ),
            integer(
                "worldCardCornerScale",
                { it.worldCardCornerScale },
                { settings, value -> settings.worldCardCornerScale = value },
            ),
            integer(
                "worldBackdropCornerScale",
                { it.worldBackdropCornerScale },
                { settings, value -> settings.worldBackdropCornerScale = value },
            ),
            boolean(
                "weatherEnabled",
                { it.weatherEnabled },
                { settings, value -> settings.weatherEnabled = value },
            ),
            boolean(
                "weatherUseDevice",
                { it.weatherUseDevice },
                { settings, value -> settings.weatherUseDevice = value },
            ),
            string(
                "weatherPlace",
                { it.weatherPlace },
                { settings, value -> settings.weatherPlace = value },
            ),
            string("weatherFontFamily", { it.weatherFontFamily }, { settings, value -> settings.weatherFontFamily = value }),
            integer("weatherFontWeight", { it.weatherFontWeight }, { settings, value -> settings.weatherFontWeight = value }),
            integer("weatherColor", { it.weatherColor }, { settings, value -> settings.weatherColor = value }),
            integer("weatherTextSize", { it.weatherTextSize }, { settings, value -> settings.weatherTextSize = value }),
            integer("weatherIconSize", { it.weatherIconSize }, { settings, value -> settings.weatherIconSize = value }),
            integer("weatherIconGap", { it.weatherIconGap }, { settings, value -> settings.weatherIconGap = value }),
            string("weatherTextFormat", { it.weatherTextFormat.wireValue }, { settings, value -> settings.weatherTextFormat = WeatherTextFormat.fromWire(value) }),
            integer("weatherOffsetX", { it.weatherOffsetX }, { settings, value -> settings.weatherOffsetX = value }),
            integer("weatherOffsetY", { it.weatherOffsetY }, { settings, value -> settings.weatherOffsetY = value }),
        )

    fun writeTo(config: WidgetSettings, out: Bundle) {
        schema.forEach { it.write(config, out) }
    }

    fun readFrom(config: WidgetSettings, input: Bundle?) {
        if (input == null) return
        if (input.containsKey("cfg_24") && !input.containsKey("cfg_timeFormat"))
            config.timeFormat = if (input.getBoolean("cfg_24", true)) "HH:mm" else "h:mm a"
        if (input.containsKey("cfg_style") && !input.containsKey("cfg_timeColor")) {
            config.timeColor =
                if (input.getString("cfg_style") == "dark") Color.BLACK else Color.WHITE
            config.dateColor = config.timeColor
            config.alarmColor = config.timeColor
        }
        if (input.containsKey("cfg_padding") && !input.containsKey("cfg_paddingTop")) {
            val padding = input.getInt("cfg_padding")
            config.paddingTop = padding
            config.paddingBottom = padding
            config.paddingLeft = padding
            config.paddingRight = padding
        }
        if (input.containsKey("cfg_align") && !input.containsKey("cfg_dateAlignment"))
            config.dateAlignment = WidgetAlignment.fromWire(input.getString("cfg_align", "left") ?: "left")
        for (setting in schema) if (input.containsKey(setting.key)) setting.read(config, input)
        if (!input.containsKey("cfg_dateFontWeight") && input.containsKey("cfg_fontWeight"))
            config.dateFontWeight = config.fontWeight
        if (!input.containsKey("cfg_weatherFontFamily")) config.weatherFontFamily = config.fontFamily
        if (!input.containsKey("cfg_weatherFontWeight")) config.weatherFontWeight = config.dateFontWeight
        if (!input.containsKey("cfg_weatherColor")) config.weatherColor = config.dateColor
        normalize(config)
    }

    fun loadInto(
        config: WidgetSettings,
        prefs: SharedPreferences,
        prefix: String,
        legacy: Boolean = false,
    ) {
        if (legacy)
            with(config) {
                zoneId =
                    prefs.getString(prefix + "zone", "").takeIf(CityCatalog::isValidSelection) ?: ""
                style = WorldClockStyle.fromWire(prefs.getString(prefix + "style", "auto") ?: "auto")
                alignment = WidgetAlignment.fromWire(prefs.getString(prefix + "alignment", "left") ?: "left")
                textSize =
                    prefs
                        .getInt(prefix + "size", textSize)
                        .coerceIn(8, WidgetSettings.MAX_TIME_SIZE)
                opacity = prefs.getInt(prefix + "opacity", 100).coerceIn(0, 100)
                padding = prefs.getInt(prefix + "padding", 0).coerceIn(0, 24)
                showDate = prefs.getBoolean(prefix + "date", true)
                hour24 = prefs.getBoolean(prefix + "24h", true)
                outline = prefs.getBoolean(prefix + "outline", true)
                val zones =
                    (prefs.getString(prefix + "cities", "Europe/London")
                            ?: "Europe/London")
                        .split('|')
                        .filter(CityCatalog::isValidSelection)
                        .distinct()
                        .take(4)
                if (zones.isNotEmpty()) cities = zones.toTypedArray()
            }
        config.timeFormat = if (config.hour24) "HH:mm" else "h:mm a"
        config.timeColor = if (config.style == WorldClockStyle.DARK) Color.BLACK else Color.WHITE
        config.dateColor = config.timeColor
        config.alarmColor = config.timeColor
        config.paddingTop = config.padding
        config.paddingBottom = config.padding
        config.paddingLeft = config.padding
        config.paddingRight = config.padding
        config.dateAlignment = config.alignment
        val state = prefs.getString(prefix + "state", null)
        if (state == null) {
            normalize(config)
            return
        }
        try {
            decode(config, JSONObject(state))
        } catch (_: JSONException) {
            normalize(config)
        }
    }

    fun save(prefs: SharedPreferences, prefix: String, config: WidgetSettings, world: Boolean) {
        normalize(config)
        val json = encode(config)
        prefs
            .edit()
            .apply {
                prefs.all.keys.filter { it.startsWith(prefix) }.forEach(::remove)
                putBoolean(prefix + "saved", true)
                putString(prefix + "state", json.toString())
                if (world)
                    putInt(prefix + TAP_DEFAULT_VERSION_KEY, TAP_DEFAULT_VERSION)
            }
            .apply()
    }

    internal fun decode(config: WidgetSettings, json: JSONObject) {
        val bundle = Bundle()
        for (key in json.keys()) {
            when (val value = json.get(key)) {
                is Boolean -> bundle.putBoolean(key, value)
                is Number -> bundle.putInt(key, value.toInt())
                is String -> bundle.putString(key, value)
                is JSONArray -> bundle.putStringArray(key, Array(value.length()) { value.getString(it) })
            }
        }
        readFrom(config, bundle)
    }

    internal fun encode(config: WidgetSettings): JSONObject {
        val bundle = Bundle()
        writeTo(config, bundle)
        val json = JSONObject()
        for (key in bundle.keySet()) {
            val value = bundle.get(key)
            json.put(key, if (value is Array<*>) JSONArray(value.toList()) else value)
        }
        return json
    }

    private fun normalize(settings: WidgetSettings) =
        with(settings) {
            textSize = textSize.coerceIn(8, WidgetSettings.MAX_TIME_SIZE)
            dateSize = dateSize.coerceIn(6, WidgetSettings.MAX_DATE_SIZE)
            alarmSize = alarmSize.coerceIn(6, 64)
            opacity = opacity.coerceIn(0, 100)
            paddingTop = paddingTop.coerceIn(0, 80)
            paddingBottom = paddingBottom.coerceIn(0, 80)
            paddingLeft = paddingLeft.coerceIn(0, 80)
            paddingRight = paddingRight.coerceIn(0, 80)
            timeOffsetX = timeOffsetX.coerceIn(-40, 40)
            timeOffsetY = timeOffsetY.coerceIn(-40, 40)
            dateOffsetX = dateOffsetX.coerceIn(-40, 40)
            dateOffsetY = dateOffsetY.coerceIn(-40, 40)
            alarmOffsetX = alarmOffsetX.coerceIn(-40, 40)
            alarmOffsetY = alarmOffsetY.coerceIn(-40, 40)
            timeDateGap = timeDateGap.coerceIn(0, 32)
            fontWeight = fontWeight.coerceIn(100, 900)
            timeFontWeight = timeFontWeight.coerceIn(100, 900)
            dateFontWeight = dateFontWeight.coerceIn(100, 900)
            weatherFontWeight = weatherFontWeight.coerceIn(100, 900)
            weatherTextSize = weatherTextSize.coerceIn(6, 35)
            weatherIconSize = weatherIconSize.coerceIn(16, 80)
            weatherIconGap = weatherIconGap.coerceIn(0, 24)
            weatherOffsetX = weatherOffsetX.coerceIn(-40, 40)
            weatherOffsetY = weatherOffsetY.coerceIn(-40, 40)
            worldCardCornerScale =
                worldCardCornerScale.coerceIn(0, WidgetSettings.MAX_WORLD_CORNER_SCALE)
            worldBackdropCornerScale =
                worldBackdropCornerScale.coerceIn(0, WidgetSettings.MAX_WORLD_CORNER_SCALE)
            cities =
                cities
                    .orEmpty()
                    .filter(CityCatalog::isValidSelection)
                    .distinct()
                    .take(4)
                    .ifEmpty { listOf("Europe/London") }
                    .toTypedArray()
            if (zoneId.isNotEmpty() && !CityCatalog.isValidSelection(zoneId)) zoneId = ""
            if (weatherPlace.isNotEmpty() && WeatherPlace.decode(weatherPlace) == null)
                weatherPlace = ""
        }
}

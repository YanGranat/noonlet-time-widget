package app.noonlet

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import java.lang.reflect.Modifier
import java.util.Objects
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36])
class SettingsArchiveTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun prepare() {
        for (name in listOf("noonlet_widgets", "widget_drafts", "widget_configuration", "MainActivity",
            "noonlet_app", "noonlet_imported_widgets"))
            context.getSharedPreferences(name, 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
    }

    internal fun contents(): SettingsArchive.Contents {
        fun configuration(size: WidgetSize, text: Int, cities: Array<String> = arrayOf("Europe/Lisbon")) =
            SettingsArchive.Configuration(size, WidgetSettings().apply {
                textSize = text
                dateSize = 19
                alarmOffsetX = 12
                this.cities = cities
            })
        return SettingsArchive.Contents("de-DE", ThemeMode.LIGHT, TemperatureUnit.FAHRENHEIT, true,
            SettingsArchive.Group(configuration(WidgetSize.FOUR_BY_ONE, 58), listOf(
                configuration(WidgetSize.FOUR_BY_ONE, 61), configuration(WidgetSize.TWO_BY_TWO, 54))),
            SettingsArchive.Group(configuration(WidgetSize.FOUR_BY_TWO, 52), listOf(
                configuration(WidgetSize.FOUR_BY_TWO, 60),
                configuration(WidgetSize.TWO_BY_ONE, 55, arrayOf("Asia/Tokyo")))))
    }

    @Test fun roundTripIncludesApplicationBothDraftsAndEveryConfiguration() {
        val original = contents()
        val text = SettingsArchive.encode(original)
        val restored = SettingsArchive.read(text.byteInputStream())
        assertEquals(original.language, restored.language)
        assertEquals(original.theme, restored.theme)
        assertEquals(original.unit, restored.unit)
        assertTrue(restored.worldTab)
        for ((before, after) in listOf(original.clock to restored.clock, original.world to restored.world)) {
            assertEquals(before.draft.size, after.draft.size)
            assertEquals(before.widgets.map { it.size }, after.widgets.map { it.size })
            for ((a,b) in (before.widgets + before.draft).zip(after.widgets + after.draft)) {
                assertTrue(SettingsCodec.sameEditorSettings(SettingsCodec.snapshot(a.settings), SettingsCodec.snapshot(b.settings)))
                assertNotSame(a.settings.cities, b.settings.cities)
            }
        }
        assertEquals(setOf("format", "version", "application", "clock", "world"), JSONObject(text).keys().asSequence().toSet())
        assertEquals(original.language, SettingsArchive.read(("\uFEFF"+text).byteInputStream()).language)
    }

    @Test fun everyPublicSettingSurvivesTransferWithANondefaultValue() {
        val fields = WidgetSettings::class.java.fields.filter { !Modifier.isStatic(it.modifiers) }
        val settings = WidgetSettings().apply {
            for (field in fields) {
                val value = field.get(this)
                val alternative: Any = when (value) {
                    is Boolean -> !value
                    is Int -> when {
                        field.name.endsWith("Color") -> 0xffa1b2c3.toInt()
                        field.name.endsWith("Weight") -> 500
                        else -> 17
                    }.let { if (it == value) it + 1 else it }
                    is String -> when (field.name) {
                        "zoneId" -> listOf("Asia/Tokyo", "Europe/Paris")
                        "timeFormat" -> listOf("h:mm a", "HH:mm")
                        "dateFormat" -> listOf("yyyy-MM-dd", "EEE, d MMM")
                        "alarmFormat" -> listOf("HH:mm:ss.SSS", "HH:mm")
                        "weatherPlace" -> listOf(WeatherPlace("North Point", 54_200_000, -2_100_000).encode(), "")
                        "fontFamily", "weatherFontFamily" -> listOf("serif", "monospace")
                        else -> listOf("settings", "clock")
                    }.first { it != value }
                    is Array<*> -> arrayOf("Europe/Paris", "Asia/Tokyo").let {
                        if (Objects.deepEquals(value, it)) it.reversedArray() else it
                    }
                    is Enum<*> -> field.type.enumConstants.first { it != value }
                    else -> error("Unsupported setting: ${field.name}")
                }
                assertFalse(field.name, Objects.deepEquals(value, alternative))
                field.set(this, alternative)
            }
        }
        val configuration = SettingsArchive.Configuration(WidgetSize.FOUR_BY_TWO, settings)
        val group = SettingsArchive.Group(configuration, listOf(configuration))
        val original = contents().copy(clock = group, world = group)
        SettingsArchive.restore(context, SettingsArchive.read(SettingsArchive.encode(original).byteInputStream()))
        val restored = SettingsArchive.capture(context)
        for (item in listOf(restored.clock.draft, restored.world.draft,
            restored.clock.widgets.single(), restored.world.widgets.single())) {
            assertEquals(configuration.size, item.size)
            for (field in fields)
                assertTrue(field.name, Objects.deepEquals(field.get(settings), field.get(item.settings)))
        }
    }

    @Test fun restoringMapsLocalBindingsAndPreservesConfigurationsWithoutAHost() {
        bind(6540, false)
        bind(6541, false)
        bind(6542, true)
        SettingsArchive.restore(context, contents())
        assertEquals(61, WidgetSettingsStore.load(context, 6540, false).textSize)
        assertEquals(54, WidgetSettingsStore.load(context, 6541, false).textSize)
        assertEquals(60, WidgetSettingsStore.load(context, 6542, true).textSize)
        assertEquals(58, WidgetSettingsStore.loadUnplaced(context, false).textSize)
        assertEquals(52, WidgetSettingsStore.loadUnplaced(context, true).textSize)
        assertEquals("de-DE", AppLocales.selectedLanguageTag(context))
        assertEquals(ThemeMode.LIGHT, AppPreferences(context).themeMode())
        assertEquals(TemperatureUnit.FAHRENHEIT, AppPreferences(context).temperatureUnit())
        assertTrue(EditorPreferences(context).worldTab())
        val pending = ImportedWidgetConfigurations.all(context)
        assertEquals(1, pending.size)
        assertTrue(pending.single().world)
        assertEquals(55, pending.single().configuration.settings.textSize)
        assertArrayEquals(arrayOf("Asia/Tokyo"), pending.single().configuration.settings.cities)
        val exported = SettingsArchive.encode(SettingsArchive.capture(context))
        assertFalse(exported.contains("6540"))
        assertFalse(exported.contains(pending.single().token))
        assertEquals(2, SettingsArchive.decode(exported).clock.widgets.size)
        assertEquals(2, SettingsArchive.decode(exported).world.widgets.size)
    }

    @Test fun customAlarmFormatsRoundTripWithTheSameVocabularyAsTheAlarmRenderer() {
        for (pattern in listOf("HH:mm:ss.SSS", "HH:mm Z", "YYYY-MM-dd", "'Alarm' HH:mm")) {
            val original = contents()
            for (group in listOf(original.clock, original.world))
                for (configuration in group.widgets + group.draft)
                    configuration.settings.alarmFormat = pattern
            val restored = SettingsArchive.read(SettingsArchive.encode(original).byteInputStream())
            for (group in listOf(restored.clock, restored.world))
                for (configuration in group.widgets + group.draft) {
                    assertEquals(pattern, configuration.settings.alarmFormat)
                    val trigger = 1_900_000_000_000L
                    assertEquals(
                        java.text.SimpleDateFormat(pattern, AppLocales.appLocale(context)).format(java.util.Date(trigger)),
                        SystemAlarmClock.format(context, configuration.settings.alarmFormat, trigger),
                    )
                }
        }
    }

    @Test fun invalidAlarmFormatsAndUnsupportedNativeClockFormatsAreRejected() {
        for ((field, patterns) in listOf(
            "cfg_alarmFormat" to listOf("", "   ", "HH:mm '", "HH:mm q", "H".repeat(97)),
            "cfg_timeFormat" to listOf("HH:mm:ss.SSS", "HH:mm Z"),
            "cfg_dateFormat" to listOf("YYYY-MM-dd", "yyyy-MM-dd Z"),
        )) for (pattern in patterns) {
            val document = JSONObject(SettingsArchive.encode(contents()))
            document.getJSONObject("clock").getJSONObject("draft").getJSONObject("settings")
                .put(field, pattern)
            assertThrows("$field: $pattern", IllegalArgumentException::class.java) {
                SettingsArchive.decode(document.toString())
            }
        }
    }

    @Test fun aNewInstallationRetainsAllWidgetsForLaterPlacement() {
        SettingsArchive.restore(context, contents())
        assertEquals(4, ImportedWidgetConfigurations.all(context).size)
        val exported = SettingsArchive.capture(context)
        assertEquals(listOf(61,54), exported.clock.widgets.map { it.settings.textSize })
        assertEquals(listOf(60,55), exported.world.widgets.map { it.settings.textSize })
        assertEquals(listOf(WidgetSize.FOUR_BY_ONE, WidgetSize.TWO_BY_TWO), exported.clock.widgets.map { it.size })
    }

    @Test fun invalidDocumentsAreRejectedWithoutChangingApplicationSettings() {
        fun document() = JSONObject(SettingsArchive.encode(contents()))
        val bad = listOf(document().put("version", 2), document().put("version", "1"),
            document().put("format", "other"),
            document().apply { getJSONObject("application").put("theme", "unknown") },
            document().apply { getJSONObject("application").put("worldTab", "true") },
            document().apply { getJSONObject("application").put("language", "invalid") },
            document().apply { getJSONObject("world").getJSONArray("widgets").getJSONObject(1)
                .getJSONObject("settings").put("cfg_dateSize", "22") },
            document().apply { getJSONObject("clock").getJSONObject("draft").put("size", "1x1") })
        for (value in bad) assertThrows(Exception::class.java) { SettingsArchive.decode(value.toString()) }
        assertThrows(Exception::class.java) { SettingsArchive.decode("[".repeat(10000)) }
        assertThrows(Exception::class.java) { SettingsArchive.read(ByteArray(SettingsArchive.MAX_BYTES+1).inputStream()) }
        assertThrows(Exception::class.java) { SettingsArchive.read(byteArrayOf(0xc3.toByte(),0x28).inputStream()) }
        assertEquals(ThemeMode.DARK, AppPreferences(context).themeMode())
        assertTrue(ImportedWidgetConfigurations.all(context).isEmpty())
    }

    private fun bind(id: Int, world: Boolean) {
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(context, if (world) WorldWidgetProvider::class.java else ClockWidgetProvider::class.java)
            initialLayout = if (world) R.layout.world_widget else R.layout.clock_widget
        }
        val shadow = Shadows.shadowOf(AppWidgetManager.getInstance(context))
        shadow.addInstalledProvider(info)
        shadow.addBoundWidget(id, info)
        WidgetSettingsStore.save(context, id, world, WidgetSettings.defaults(world))
    }
}

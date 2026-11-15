package app.noonlet

import android.content.Context
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36], qualifiers = "en-rUS-mdpi")
class AppLocalesTest {
    private lateinit var context: Context
    private val languages = listOf("de", "en", "es", "fr", "it", "pl", "pt", "ru", "uk")
    private val placeholders = Regex("""%(\d+)\$([a-zA-Z])""")

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
    }

    @Test
    fun everyLanguageHasEveryStringAndTheSameFormatArgumentTypes() {
        val english = catalogue("en")
        assertTrue(english.isNotEmpty())
        for (language in languages) {
            val translated = catalogue(language)
            assertEquals("String keys for $language", english.keys, translated.keys)
            for ((name, value) in translated) {
                assertTrue("Empty $language string: $name", value.isNotBlank())
                assertEquals(
                    "Format arguments for $language/$name",
                    signature(english.getValue(name)),
                    signature(value),
                )
            }
        }
    }

    @Test
    fun compiledResourcesResolveAndFormatEveryStringInEveryLanguage() {
        for (language in languages) {
            val locale = Locale.forLanguageTag(language)
            val resources = AppLocales.resources(context, locale)
            val expected = catalogue(language)
            assertEquals(
                "The widget label must use the requested language",
                expected.getValue("clock_widget_name"),
                resources.getString(R.string.clock_widget_name),
            )
            for ((name, source) in expected) {
                val id = R.string::class.java.getField(name).getInt(null)
                val arguments = arguments(source)
                val formatted =
                    if (arguments.isEmpty()) resources.getString(id)
                    else resources.getString(id, *arguments)
                assertTrue("Empty formatted $language/$name", formatted.isNotBlank())
            }
        }
    }

    @Test
    fun androidLocaleDeclarationAndApplicationChoicesCoverTheSameNineLanguages() {
        assertEquals(languages.toSet(), AppLocales.supportedTags.toSet())
        val declared = mutableSetOf<String>()
        context.resources.getXml(R.xml.locales_config).use { xml ->
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "locale") {
                    declared +=
                        requireNotNull(
                            xml.getAttributeValue(
                                "http://schemas.android.com/apk/res/android",
                                "name",
                            ),
                        )
                }
                xml.next()
            }
        }
        assertEquals(languages.toSet(), declared)
    }

    @Test
    fun explicitChoicesPersistAndResolveTheirOwnResources() {
        for (language in languages) {
            AppLocales.setLanguage(context, language)
            assertEquals(language, AppLocales.language(context))
            assertEquals(language, AppLocales.appLocale(context).language)
            assertEquals(
                catalogue(language).getValue("clock_widget_name"),
                AppLocales.text(context, R.string.clock_widget_name),
            )
        }
    }

    @Test
    fun regionalLocalesUseTheirSupportedLanguageAndUnsupportedUiUsesEnglish() {
        for (tag in
            listOf(
                "de-AT",
                "en-GB",
                "es-MX",
                "fr-CA",
                "it-CH",
                "pl-PL",
                "pt-BR",
                "ru-RU",
                "uk-UA",
            )) {
            val locale = Locale.forLanguageTag(tag)
            assertEquals(locale.language, AppLocales.uiLanguage(locale))
            assertEquals(
                catalogue(locale.language).getValue("clock_widget_name"),
                AppLocales.text(context, locale, R.string.clock_widget_name),
            )
        }
        for (tag in listOf("ja-JP", "ar-EG", "zz-ZZ")) {
            val locale = Locale.forLanguageTag(tag)
            assertEquals("en", AppLocales.uiLanguage(locale))
            assertEquals("Clock", AppLocales.text(context, locale, R.string.clock_widget_name))
        }
    }

    private fun catalogue(language: String): Map<String, String> {
        val directory = if (language == "en") "values" else "values-$language"
        val source = File("src/main/res/$directory/strings.xml")
        assertTrue("Missing $language catalogue", source.isFile)
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(source)
        val entries = xml.getElementsByTagName("string")
        val result = linkedMapOf<String, String>()
        for (index in 0 until entries.length) {
            val entry = entries.item(index)
            val name = entry.attributes.getNamedItem("name").nodeValue
            assertNull("Duplicate $language string: $name", result.put(name, entry.textContent))
        }
        return result
    }

    private fun signature(text: String): List<Pair<Int, Char>> =
        placeholders
            .findAll(text)
            .map { it.groupValues[1].toInt() to it.groupValues[2].single() }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .toList()

    private fun arguments(text: String): Array<Any> {
        val signature = signature(text)
        val result = Array<Any>(signature.maxOfOrNull { it.first } ?: 0) { "Sample" }
        for ((index, type) in signature) {
            result[index - 1] =
                when (type.lowercaseChar()) {
                    'd',
                    'o',
                    'x' -> 42
                    'e',
                    'f',
                    'g',
                    'a' -> 42.5
                    'c' -> 'A'
                    'b' -> true
                    else -> "Sample"
                }
        }
        return result
    }
}

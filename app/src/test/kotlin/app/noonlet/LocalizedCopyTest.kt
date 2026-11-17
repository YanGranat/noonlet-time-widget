package app.noonlet

import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36])
class LocalizedCopyTest {
    @Test fun everyPackagedLocaleHasReadableEscapesAndFormatsItsMessages() {
        val context = RuntimeEnvironment.getApplication()
        val placeholders = Regex("""%(\d+)\${'$'}(?:\.\d+)?([dsf])""")
        val fields = R.string::class.java.declaredFields.filter { it.type == Int::class.javaPrimitiveType }
        assertTrue(fields.isNotEmpty())
        for (tag in AppLocales.supportedTags) {
            val resources = AppLocales.resources(context, Locale.forLanguageTag(tag))
            for (field in fields) {
                val id = field.getInt(null)
                val text = resources.getString(id)
                assertFalse("$tag/${field.name}: $text", text.contains('\\'))
                val slots = placeholders.findAll(text).toList()
                if (slots.isNotEmpty()) {
                    val args = Array<Any>(slots.maxOf { it.groupValues[1].toInt() }) { "Example" }
                    for (slot in slots) args[slot.groupValues[1].toInt() - 1] = when (slot.groupValues[2]) {
                        "d" -> 30
                        "f" -> 12.5
                        else -> "Example"
                    }
                    assertTrue(resources.getString(id, *args).isNotEmpty())
                }
            }
        }
    }
}

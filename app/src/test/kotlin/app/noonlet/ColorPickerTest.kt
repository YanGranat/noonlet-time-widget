package app.noonlet

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
class ColorPickerTest {
    @Test
    fun sampleTextContrastsWithTheCompositedColor() {
        val context = RuntimeEnvironment.getApplication()
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            AppPreferences(context).setThemeMode(theme)
            Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
                val cases = listOf(
                    Color.TRANSPARENT to if (theme == ThemeMode.LIGHT) Color.BLACK else Color.WHITE,
                    0x00ffffff to if (theme == ThemeMode.LIGHT) Color.BLACK else Color.WHITE,
                    Color.BLACK to Color.WHITE,
                    Color.WHITE to Color.BLACK,
                    0x20ffffff to if (theme == ThemeMode.LIGHT) Color.BLACK else Color.WHITE,
                    0x20000000 to if (theme == ThemeMode.LIGHT) Color.BLACK else Color.WHITE,
                )
                for ((color, foreground) in cases) {
                    ColorPicker.show(controller.get(), "Color", color) {}
                    val dialog = ShadowAlertDialog.getLatestAlertDialog()
                    val root = requireNotNull(dialog.window).decorView
                    val sample = descendants(root).filterIsInstance<TextView>().single {
                        it !is EditText && it.text.toString() == "#%08X".format(color)
                    }
                    assertEquals("$theme: ${sample.text}", foreground, sample.currentTextColor)
                    dialog.dismiss()
                }
            }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}

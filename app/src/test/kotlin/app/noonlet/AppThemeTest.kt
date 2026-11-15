package app.noonlet

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Looper
import android.view.View
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
class AppThemeTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        for (name in listOf("noonlet_app", "noonlet_widgets", "MainActivity", "widget_drafts", "widget_configuration"))
            context.getSharedPreferences(name, 0).edit().clear().commit()
        AppPreferences(context).setThemeMode(ThemeMode.SYSTEM)
    }

    @Test
    fun systemModeResolvesEachContextConfigurationIndependently() {
        val light = configuration(false)
        val dark = configuration(true)
        val lightPalette = AppTheme.palette(light)
        val darkPalette = AppTheme.palette(dark)
        assertFalse(lightPalette.isDark)
        assertTrue(darkPalette.isDark)
        assertNotEquals(lightPalette.background, darkPalette.background)
        assertEquals(lightPalette, AppTheme.palette(light))
        assertEquals(darkPalette, AppTheme.palette(dark))
        val preferences = AppPreferences(context)
        preferences.setThemeMode(ThemeMode.LIGHT)
        assertEquals(lightPalette, AppTheme.palette(dark))
        preferences.setThemeMode(ThemeMode.DARK)
        assertEquals(darkPalette, AppTheme.palette(light))
        preferences.setThemeMode(ThemeMode.SYSTEM)
        assertEquals(lightPalette, AppTheme.palette(light))
        assertEquals(darkPalette, AppTheme.palette(dark))
    }

    @Test
    fun textAndPrimaryActionsRemainReadableOnTheirSurfaces() {
        for (dark in listOf(false, true)) {
            val palette = AppTheme.palette(configuration(dark))
            for (surface in listOf(palette.background, palette.surface, palette.input, palette.selectionSurface)) {
                assertTrue("Text contrast must meet 4.5:1", contrast(palette.text, surface) >= 4.5)
                assertTrue("Secondary text contrast must meet 4.5:1", contrast(palette.muted, surface) >= 4.5)
            }
            assertTrue(contrast(palette.onAccent, palette.accent) >= 4.5)
            assertTrue(contrast(Color.WHITE, palette.switchChecked) >= 3.0)
            assertTrue(contrast(palette.switchUncheckedThumb, palette.switchUnchecked) >= 3.0)
        }
    }

    @Test
    fun windowAndNativeDialogControlsFollowExplicitThemeChanges() {
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val preferences = AppPreferences(context)
            for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.LIGHT)) {
                preferences.setThemeMode(mode)
                AppTheme.applyToWindow(activity)
                val palette = AppTheme.palette(activity)
                assertSame(palette, AppTheme.palette(activity))
                assertEquals(mode == ThemeMode.DARK, palette.isDark)
                @Suppress("DEPRECATION")
                val lightStatus = activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0
                assertEquals(!palette.isDark, lightStatus)
                @Suppress("DEPRECATION")
                val lightNavigation = activity.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR != 0
                assertEquals(!palette.isDark, lightNavigation)
                if (Build.VERSION.SDK_INT >= 27) {
                    val bars = activity.obtainStyledAttributes(intArrayOf(android.R.attr.windowLightNavigationBar))
                    try { assertEquals(!palette.isDark, bars.getBoolean(0, false)) }
                    finally { bars.recycle() }
                }
                if (Build.VERSION.SDK_INT >= 29) assertFalse(activity.window.decorView.isForceDarkAllowed)
                val input = EditText(activity).apply { hint = "Example value" }
                val dialog = AppDialogBuilder(activity)
                    .setTitle("Appearance")
                    .setView(input)
                    .setPositiveButton("Apply", null)
                    .setNegativeButton("Cancel", null)
                    .show()
                try {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(palette.text, input.currentTextColor)
                    assertEquals(palette.muted, input.currentHintTextColor)
                    assertEquals(palette.onAccent, dialog.getButton(-1).currentTextColor)
                    assertEquals(palette.text, dialog.getButton(-2).currentTextColor)
                    val native = dialog.context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
                    try { assertEquals(palette.text, native.getColorStateList(0)!!.defaultColor) }
                    finally { native.recycle() }
                    assertEquals(palette, AppTheme.palette(dialog.context))
                } finally { dialog.dismiss() }
            }
        }
    }

    private fun configuration(dark: Boolean): Context = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        },
    )

    private fun contrast(first: Int, second: Int): Double {
        fun luminance(color: Int): Double {
            fun channel(value: Int): Double {
                val number = value / 255.0
                return if (number <= .04045) number / 12.92 else ((number + .055) / 1.055).pow(2.4)
            }
            return .2126 * channel(Color.red(color)) + .7152 * channel(Color.green(color)) +
                .0722 * channel(Color.blue(color))
        }
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
    }
}

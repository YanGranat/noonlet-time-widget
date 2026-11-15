package app.noonlet

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.View
import android.view.WindowInsetsController

internal data class AppPalette(
    val isDark: Boolean,
    val background: Int,
    val surface: Int,
    val controlSurface: Int,
    val selectedSurface: Int,
    val sectionHeader: Int,
    val input: Int,
    val text: Int,
    val muted: Int,
    val secondaryText: Int,
    val accent: Int,
    val onAccent: Int,
    val border: Int,
    val secondary: Int,
    val selectionSurface: Int,
    val divider: Int,
    val swatchBorder: Int,
    val switchChecked: Int,
    val switchUnchecked: Int,
    val switchDisabledChecked: Int,
    val switchDisabledUnchecked: Int,
    val switchDisabledThumb: Int,
    val switchUncheckedThumb: Int,
    val ripple: Int,
    val hoverRipple: Int,
)

internal object AppTheme {
    private data class PaletteCache(val configuration: Configuration, val palette: AppPalette)
    private val attributes = intArrayOf(
        R.attr.appColorBackground, R.attr.appColorSurface, R.attr.appColorControlSurface,
        R.attr.appColorSelectedSurface, R.attr.appColorSectionHeader, R.attr.appColorInput,
        R.attr.appColorText, R.attr.appColorMuted, R.attr.appColorSecondaryText,
        R.attr.appColorAccent, R.attr.appColorOnAccent, R.attr.appColorBorder,
        R.attr.appColorSecondary, R.attr.appColorSelectionSurface, R.attr.appColorDivider,
        R.attr.appColorSwatchBorder, R.attr.appColorSwitchChecked, R.attr.appColorSwitchUnchecked,
        R.attr.appColorSwitchDisabledChecked, R.attr.appColorSwitchDisabledUnchecked,
        R.attr.appColorSwitchDisabledThumb, R.attr.appColorSwitchUncheckedThumb,
        R.attr.appColorRipple, R.attr.appColorHoverRipple,
    )

    private fun isDark(context: Context): Boolean = when (AppPreferences(context).themeMode()) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun themedContext(context: Context): Context = ContextThemeWrapper(
        context, if (isDark(context)) R.style.AppTheme else R.style.AppThemeLight,
    )

    fun palette(context: Context): AppPalette {
        val dark = isDark(context)
        val configuration = context.resources.configuration
        val decor = activity(context)?.window?.decorView
        val cached = decor?.getTag(R.id.app_palette_cache) as? PaletteCache
        if (cached?.palette?.isDark == dark && cached.configuration == configuration)
            return cached.palette
        val themed = ContextThemeWrapper(context, if (dark) R.style.AppTheme else R.style.AppThemeLight)
        val values = themed.obtainStyledAttributes(attributes)
        try {
            fun color(index: Int): Int {
                check(values.hasValue(index))
                return values.getColor(index, 0)
            }
            return AppPalette(
                dark, color(0), color(1), color(2), color(3), color(4), color(5), color(6),
                color(7), color(8), color(9), color(10), color(11), color(12), color(13),
                color(14), color(15), color(16), color(17), color(18), color(19), color(20),
                color(21), color(22), color(23),
            ).also { decor?.setTag(R.id.app_palette_cache, PaletteCache(Configuration(configuration), it)) }
        } finally { values.recycle() }
    }

    private fun activity(context: Context): Activity? {
        var candidate = context
        while (candidate is ContextWrapper) {
            if (candidate is Activity) return candidate
            val base = candidate.baseContext
            if (base === candidate) return null
            candidate = base
        }
        return null
    }

    fun dialogStyle(context: Context): Int =
        if (isDark(context)) R.style.AppDialogTheme else R.style.AppDialogThemeLight

    fun protectColors(view: View) {
        if (Build.VERSION.SDK_INT >= 29) view.isForceDarkAllowed = false
    }

    @Suppress("DEPRECATION")
    fun applyToWindow(activity: Activity) {
        activity.setTheme(if (isDark(activity)) R.style.AppTheme else R.style.AppThemeLight)
        val palette = palette(activity)
        activity.window.apply {
            setBackgroundDrawable(ColorDrawable(palette.background))
            statusBarColor = palette.background
            navigationBarColor = palette.background
            val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            decorView.systemUiVisibility =
                (decorView.systemUiVisibility and light.inv()) or if (palette.isDark) 0 else light
            protectColors(decorView)
            if (Build.VERSION.SDK_INT >= 30) {
                val appearance = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                insetsController?.setSystemBarsAppearance(if (palette.isDark) 0 else appearance, appearance)
            }
        }
    }
}

package app.noonlet

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.text.style.TextAppearanceSpan
import android.text.style.TypefaceSpan
import android.util.LruCache
import android.view.ContextThemeWrapper
import java.util.WeakHashMap

/** Portable font descriptions: the launcher creates its own weighted Typeface. */
object WidgetFont {
    enum class Scope { PORTABLE, LOCAL }

    const val LIGHT_WEIGHT = 300
    const val MEDIUM_WEIGHT = 350
    const val BOLD_WEIGHT = 450
    private val AXIS_SUPPORT = LruCache<Typeface, Boolean>(8)
    private data class FontKey(val base: Typeface, val weight: Int)
    private val TYPEFACES = LruCache<FontKey, Typeface>(64)
    // Three families and seventeen styles; values retain no Context or Resources.
    private val APPEARANCES = WeakHashMap<Resources, FontAppearances>()
    private val WEIGHT_STYLES =
        intArrayOf(
            R.style.Weight100,
            R.style.Weight150,
            R.style.Weight200,
            R.style.Weight250,
            R.style.Weight300,
            R.style.Weight350,
            R.style.Weight400,
            R.style.Weight450,
            R.style.Weight500,
            R.style.Weight550,
            R.style.Weight600,
            R.style.Weight650,
            R.style.Weight700,
            R.style.Weight750,
            R.style.Weight800,
            R.style.Weight850,
            R.style.Weight900,
        )

    fun presetWeight(family: String): Int? =
        when (family) {
            "sans-serif" -> 400
            "sans-serif-light" -> LIGHT_WEIGHT
            "sans-serif-medium" -> MEDIUM_WEIGHT
            "sans-serif-bold" -> BOLD_WEIGHT
            else -> null
        }

    fun styled(context: Context, text: CharSequence, family: String, weight: Int,
        scope: Scope = Scope.PORTABLE): CharSequence =
        SpannableString(text).apply {
            if (isNotEmpty()) {
                if (scope == Scope.LOCAL && Build.VERSION.SDK_INT >= 29)
                    setSpan(TypefaceSpan(typeface(family, 100 + 50 * weightIndex(weight))), 0, length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                else if (Build.VERSION.SDK_INT >= 29)
                    setSpan(
                        appearance(context, family, weight),
                        0,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                else {
                    setSpan(
                        TypefaceSpan(legacyFamily(family, weight)),
                        0,
                        length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    if (weight >= 600)
                        setSpan(
                            StyleSpan(Typeface.BOLD),
                            0,
                            length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                }
            }
        }

    private fun appearance(context: Context, family: String, weight: Int): TextAppearanceSpan {
        val resources = context.resources
        synchronized(APPEARANCES) {
            val configuration = resources.configuration
            val packageName = context.packageName
            val cached =
                APPEARANCES[resources]?.takeIf {
                    it.configuration == configuration && it.packageName == packageName
                }
                    ?: FontAppearances(configuration, packageName).also {
                        APPEARANCES[resources] = it
                    }
            val familyIndex =
                when (family) {
                    "serif" -> 1
                    "monospace" -> 2
                    else -> 0
                }
            val styleIndex = weightIndex(weight)
            return cached.spans[familyIndex][styleIndex]
                ?: run {
                    val restricted =
                        try {
                            context.createPackageContext(packageName, Context.CONTEXT_RESTRICTED)
                        } catch (error: PackageManager.NameNotFoundException) {
                            throw IllegalStateException(
                                "Application resources are unavailable",
                                error,
                            )
                        }
                    TextAppearanceSpan(
                            ContextThemeWrapper(restricted, familyTheme(family)),
                            weightStyle(weight),
                        )
                        .also {
                            cached.spans[familyIndex][styleIndex] = it
                        }
                }
        }
    }

    private class FontAppearances(configuration: Configuration, val packageName: String) {
        val configuration = Configuration(configuration)
        val spans = Array(3) { arrayOfNulls<TextAppearanceSpan>(17) }
    }

    fun typeface(family: String, weight: Int): Typeface {
        val bounded = weight.coerceIn(100, 900)
        val base = Typeface.create(renderFamily(family), Typeface.NORMAL)
        if (Build.VERSION.SDK_INT >= 29) {
            val key = FontKey(base, bounded)
            synchronized(TYPEFACES) {
                TYPEFACES.get(key)?.let { return it }
                val weighted = Typeface.create(base, bounded, false)
                return Paint().apply {
                    typeface = weighted
                    fontVariationSettings = variationSettings(bounded)
                }.typeface.also { TYPEFACES.put(key, it) }
            }
        }
        return Typeface.create(
            legacyFamily(family, weight),
            if (weight >= 600) Typeface.BOLD else Typeface.NORMAL,
        )
    }

    fun supportsSmoothWeight(family: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val base = Typeface.create(renderFamily(family), Typeface.NORMAL)
        synchronized(AXIS_SUPPORT) {
            return AXIS_SUPPORT.get(base)
                ?: glyphAxesDiffer(base).also { AXIS_SUPPORT.put(base, it) }
        }
    }

    fun renderFamily(family: String) =
        if (family == "serif" || family == "monospace") family else "sans-serif"

    private fun glyphAxesDiffer(base: Typeface): Boolean {
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { typeface = base }
        if (!paint.setFontVariationSettings(variationSettings(400))) return false
        paint.color = Color.WHITE
        paint.textSize = 48f
        val pixels = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(pixels)
            val masks = Array(2) { IntArray(128 * 128) }
            for (index in 0..1) {
                pixels.eraseColor(Color.TRANSPARENT)
                paint.typeface = base
                paint.fontVariationSettings = variationSettings(if (index == 0) 350 else 450)
                canvas.drawText("08", 4f, 52f, paint)
                canvas.drawText("М", 4f, 116f, paint)
                pixels.getPixels(masks[index], 0, 128, 0, 0, 128, 128)
            }
            var digits = false
            var cyrillic = false
            for (index in masks[0].indices) {
                if (Color.alpha(masks[0][index]) == Color.alpha(masks[1][index])) continue
                if (index < 128 * 64) digits = true else cyrillic = true
            }
            return digits && cyrillic
        } finally {
            pixels.recycle()
        }
    }

    fun variationSettings(weight: Int) = "'wght' ${weight.coerceIn(100, 900)}"

    private fun legacyFamily(family: String, weight: Int): String =
        when {
            family == "serif" || family == "monospace" -> family
            weight <= 200 -> "sans-serif-thin"
            weight <= 300 -> "sans-serif-light"
            weight <= 400 -> "sans-serif"
            weight <= 500 -> "sans-serif-medium"
            weight >= 800 -> "sans-serif-black"
            else -> "sans-serif"
        }

    private fun familyTheme(family: String): Int =
        when (family) {
            "serif" -> R.style.WidgetFontSerif
            "monospace" -> R.style.WidgetFontMonospace
            else -> R.style.WidgetFontSans
        }

    private fun weightStyle(weight: Int) = WEIGHT_STYLES[weightIndex(weight)]

    private fun weightIndex(weight: Int) = ((weight + 25) / 50 - 2).coerceIn(0, 16)
}

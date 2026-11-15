package app.noonlet

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.TypedArray
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Xml
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import org.xmlpull.v1.XmlPullParser

/** Exercises the provider's localized image preview path across legacy and current hosts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34, 35, 36], qualifiers = "en-rUS-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetPickerPreviewTest {
    private val languages = arrayOf("en", "ru", "uk", "de", "es", "fr", "it", "pl", "pt")

    @Test
    @Throws(Exception::class)
    fun receiverMetadataLoadsClockAndWorldArtworkWithShortLocalizedNames() {
        try {
            for (language: String in languages) {
                val context: Context = phoneLanguage(language)
                for (world: Boolean in booleanArrayOf(false, true)) {
                    val provider: AppWidgetProviderInfo = provider(context, world)
                    assertEquals(
                        context.getString(
                            if (world) R.string.world_widget_name else R.string.clock_widget_name,
                        ),
                        provider.loadLabel(context.getPackageManager()),
                    )
                    assertTrue(
                        "A missing previewImage makes older and OEM pickers show the app icon",
                        provider.previewImage != 0,
                    )
                    assertNotEquals(context.getApplicationInfo().icon, provider.previewImage)
                    if (Build.VERSION.SDK_INT >= 31)
                        assertEquals(
                            "The image path must also serve modern launchers",
                            0,
                            provider.previewLayout,
                        )
                    val artwork: Drawable = provider.loadPreviewImage(context, 0)
                    assertNotNull("The actual provider preview must load", artwork)
                    assertEquals(WIDTH, artwork.getIntrinsicWidth())
                    assertEquals(HEIGHT, artwork.getIntrinsicHeight())
                    val bitmap: Bitmap = draw(artwork, Color.TRANSPARENT)
                    if (world) {
                        assertTrue(
                            "The world preview must contain a white city card, not an icon",
                            brightPixels(bitmap, 16, 60, 184, 96) > 4500,
                        )
                    } else {
                        assertTrue(
                            "Clock numerals must be visible",
                            brightPixels(bitmap, 12, 18, 184, 68) > 250,
                        )
                        assertDateContrast(bitmap, language)
                        assertTrue(
                            "A clock preview must not look like the world's white card",
                            brightPixels(bitmap, 16, 60, 184, 96) < 1500,
                        )
                    }
                    bitmap.recycle()
                }
            }
        } finally {
            phoneLanguage("en")
        }
    }

    @Test
    @Throws(Exception::class)
    fun imagePreviewScalesThroughTheRestrictedProviderContext() {
        try {
            for (language: String in languages) {
                val context: Context = phoneLanguage(language)
                val restricted: Context =
                    context.createPackageContext(
                        context.getPackageName(),
                        Context.CONTEXT_RESTRICTED,
                    )
                for (world: Boolean in booleanArrayOf(false, true)) {
                    val provider: AppWidgetProviderInfo = provider(context, world)
                    var previousCoverage: Double = -1.0
                    for (width: Int in intArrayOf(100, 200, 400)) {
                        val height: Int = width * HEIGHT / WIDTH
                        val artwork: Drawable = provider.loadPreviewImage(restricted, 0)
                        assertNotNull(artwork)
                        val bitmap: Bitmap = draw(artwork, Color.TRANSPARENT, width, height)
                        var visible: Int = 0
                        for (y: Int in 0 until height) for (x: Int in 0 until width) if (
                            Color.alpha(bitmap.getPixel(x, y)) > 32
                        )
                            visible++
                        val coverage: Double = visible / (width * height).toDouble()
                        assertTrue(
                            "The whole preview must scale into its bounds",
                            coverage > .85 && coverage < 1,
                        )
                        if (previousCoverage >= 0)
                            assertEquals(
                                "Scaling must preserve the filled contour",
                                previousCoverage,
                                coverage,
                                .02,
                            )
                        previousCoverage = coverage
                        val white: Double =
                            brightPixels(bitmap, 0, 0, width, height) / (width * height).toDouble()
                        assertTrue(
                            "Scaled artwork must retain clock glyphs or the world's white card",
                            if (world) white > .2 else white > .01 && white < .15,
                        )
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            phoneLanguage("en")
        }
    }

    @Test
    @Throws(Exception::class)
    fun previewsRemainRecognizableOnBothPickerThemesAndHaveLocalizedArtwork() {
        var englishClock: Bitmap? = null
        var englishWorld: Bitmap? = null
        try {
            for (language: String in languages) {
                val context: Context = phoneLanguage(language)
                for (world: Boolean in booleanArrayOf(false, true)) {
                    val provider: AppWidgetProviderInfo = provider(context, world)
                    for (surface: Int in intArrayOf(-0x90906, -0xefeeeb)) {
                        val bitmap: Bitmap = draw(provider.loadPreviewImage(context, 0), surface)
                        var visible: Int = 0
                        for (y: Int in 0 until HEIGHT) for (x: Int in 0 until WIDTH) {
                            val pixel: Int = bitmap.getPixel(x, y)
                            if (
                                ((Math.abs(Color.red(pixel) - Color.red(surface)) +
                                    Math.abs(Color.green(pixel) - Color.green(surface)) +
                                    Math.abs(Color.blue(pixel) - Color.blue(surface))) > 50)
                            )
                                visible++
                        }
                        assertTrue(
                            "The sample must stay visible on light and dark picker backgrounds",
                            visible > WIDTH.toDouble() * HEIGHT.toDouble() * .45,
                        )
                        bitmap.recycle()
                    }
                    val sample: Bitmap =
                        draw(provider.loadPreviewImage(context, 0), Color.TRANSPARENT)
                    if ("en".equals(language)) {
                        if (world) englishWorld = sample else englishClock = sample
                    } else {
                        assertFalse(
                            "Localized artwork must contain localized date/city outlines: $language",
                            sample.sameAs(if (world) englishWorld else englishClock),
                        )
                        sample.recycle()
                    }
                }
            }
            assertFalse(
                "Clock and world clock need different, recognizable previews",
                englishClock!!.sameAs(englishWorld),
            )
        } finally {
            if (englishClock != null) englishClock!!.recycle()
            if (englishWorld != null) englishWorld!!.recycle()
            phoneLanguage("en")
        }
    }

    companion object {
        private val WIDTH: Int = 200
        private val HEIGHT: Int = 112

        private fun phoneLanguage(language: String): Context {
            // Model the phone's resource configuration; production never changes the process
            // Locale.
            RuntimeEnvironment.setQualifiers(
                if (language == "en") "en-rUS-mdpi" else "$language-mdpi",
            )
            return RuntimeEnvironment.getApplication()
        }

        @Throws(Exception::class)
        private fun provider(context: Context, world: Boolean): AppWidgetProviderInfo {
            val component: ComponentName =
                ComponentName(
                    context,
                    if (world) WorldWidgetProvider::class.java else ClockWidgetProvider::class.java,
                )
            val receiver: ActivityInfo =
                context.getPackageManager().getReceiverInfo(component, PackageManager.GET_META_DATA)
            val provider: AppWidgetProviderInfo = AppWidgetProviderInfo()
            provider.provider = component
            provider.label = receiver.loadLabel(context.getPackageManager()).toString()
            ReflectionHelpers.setField(provider, "providerInfo", receiver)
            receiver
                .loadXmlMetaData(
                    context.getPackageManager(),
                    AppWidgetManager.META_DATA_APPWIDGET_PROVIDER,
                )
                .use(
                    { xml ->
                        assertNotNull(xml)
                        while (
                            xml.next() != XmlPullParser.START_TAG &&
                                xml.getEventType() != XmlPullParser.END_DOCUMENT
                        ) {}
                        assertEquals("appwidget-provider", xml.getName())
                        assertNull(
                            "The optional preview layout must stay absent on every API",
                            xml.getAttributeValue(
                                "http://schemas.android.com/apk/res/android",
                                "previewLayout",
                            ),
                        )
                        val attrs: IntArray =
                            if (Build.VERSION.SDK_INT >= 31)
                                intArrayOf(
                                    android.R.attr.previewImage,
                                    android.R.attr.previewLayout,
                                )
                            else intArrayOf(android.R.attr.previewImage)
                        val values: TypedArray =
                            context.getResources().obtainAttributes(Xml.asAttributeSet(xml), attrs)
                        try {
                            provider.previewImage = values.getResourceId(0, 0)
                            if (Build.VERSION.SDK_INT >= 31)
                                provider.previewLayout = values.getResourceId(1, 0)
                        } finally {
                            values.recycle()
                        }
                    },
                )
            return provider
        }

        private fun draw(
            artwork: Drawable,
            surface: Int,
            width: Int = WIDTH,
            height: Int = HEIGHT,
        ): Bitmap {
            val bitmap: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas: Canvas = Canvas(bitmap)
            canvas.drawColor(surface)
            artwork.setBounds(0, 0, width, height)
            artwork.draw(canvas)
            return bitmap
        }

        private fun brightPixels(
            bitmap: Bitmap,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
        ): Int {
            var count: Int = 0
            for (y: Int in top until bottom) for (x: Int in left until right) {
                val color: Int = bitmap.getPixel(x, y)
                if (
                    Color.alpha(color) > 240 &&
                        Math.min(
                            Color.red(color),
                            Math.min(Color.green(color), Color.blue(color)),
                        ) > 190
                )
                    count++
            }
            return count
        }

        private fun assertDateContrast(bitmap: Bitmap, language: String) {
            // The 16sp Light date is gray and antialiased. Counting near-white pixels
            // incorrectly depends on the language's stroke lengths and pixel alignment.
            val stage: Int = bitmap.getPixel(18, 70)
            var left: Int = WIDTH
            var right: Int = -1
            var top: Int = HEIGHT
            var bottom: Int = -1
            var maxContrast: Int = 0
            var coverage: Double = 0.0
            for (y: Int in 70..95) for (x: Int in 16..179) {
                val pixel: Int = bitmap.getPixel(x, y)
                val contrast: Int =
                    Math.max(
                        Math.abs(Color.red(pixel) - Color.red(stage)),
                        Math.max(
                            Math.abs(Color.green(pixel) - Color.green(stage)),
                            Math.abs(Color.blue(pixel) - Color.blue(stage)),
                        ),
                    )
                coverage += Math.min(1.0, contrast / 178.0) // #E2E2E6 foreground on #303037 stage.
                maxContrast = Math.max(maxContrast, contrast)
                if (contrast > 12) {
                    left = Math.min(left, x)
                    right = Math.max(right, x)
                    top = Math.min(top, y)
                    bottom = Math.max(bottom, y)
                }
            }
            val evidence: String =
                (language +
                    " date coverage=" +
                    coverage +
                    " bounds=" +
                    (right - left + 1) +
                    "x" +
                    (bottom - top + 1) +
                    " maxContrast=" +
                    maxContrast)
            assertTrue(
                "The date must contain full-sized glyphs: " + evidence,
                right - left + 1 >= 50 && bottom - top + 1 >= 8,
            )
            assertTrue("The date must retain substantial visible ink: " + evidence, coverage > 80)
            assertTrue("The date needs high-contrast stroke cores: " + evidence, maxContrast >= 140)
        }
    }
}

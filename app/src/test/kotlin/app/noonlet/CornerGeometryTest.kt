package app.noonlet

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LevelListDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.util.ArrayList
import java.util.Arrays
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CornerGeometryTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
    }

    @Test
    @Throws(Exception::class)
    fun compactCardsRetainStraightSides() {
        var id: Int = 8100
        for (count: Int in 1..4) {
            for (size: IntArray in
                arrayOf<IntArray>(
                    intArrayOf(150, 90, 167, 82),
                    intArrayOf(300, 90, 300, 90),
                    intArrayOf(150, 174, 150, 174),
                    intArrayOf(300, 150, 300, 150),
                    intArrayOf(150, 90, 130, 75),
                )) {
                val settings: WidgetSettings = WidgetSettings.defaults(true)
                settings.cities = Arrays.copyOf(CITIES, count)
                settings.style = WorldClockStyle.LIGHT
                val root: View = render(settings, id++, size)
                val backgrounds: MutableList<ImageView> = backgrounds(root)
                assertEquals(count, backgrounds.size)
                val list: Boolean = root.findViewById<View>(R.id.world_list_divider) != null
                for (background: ImageView in backgrounds) {
                    val pixels: Bitmap = draw(background)
                    val height: Int = pixels.getHeight()
                    val width: Int = pixels.getWidth()
                    var straight: Int = 0
                    for (y: Int in 0 until height) if (Color.alpha(pixels.getPixel(0, y)) >= 250)
                        straight++
                    if (list) {
                        assertEquals(
                            "List rows must stay completely filled",
                            Color.WHITE,
                            pixels.getPixel(width / 2, height / 2),
                        )
                        continue
                    }
                    assertTrue(
                        "Home-screen cards retain a straight vertical middle rather than becoming capsules: " +
                            width +
                            "x" +
                            height,
                        straight >= height * .18f,
                    )
                    if (Math.min(width, height) <= 90)
                        assertTrue(
                            "Compact corners must remain visibly rounded rather than almost square",
                            straight <= height * .70f,
                        )
                    assertEquals(0, Color.alpha(pixels.getPixel(0, 0)))
                    assertEquals(Color.WHITE, pixels.getPixel(width / 2, height / 2))
                    val shape: GradientDrawable =
                        background.getDrawable().getCurrent() as GradientDrawable
                    // A quantized 10px radius on a resized 25px row still leaves a 20% straight
                    // edge.
                    assertTrue(
                        ("Corner radius must stay within the compact contour limit; cities=" +
                            count +
                            ", requested=" +
                            size[0] +
                            "x" +
                            size[1] +
                            ", actual=" +
                            size[2] +
                            "x" +
                            size[3] +
                            ", card=" +
                            width +
                            "x" +
                            height +
                            ", radius=" +
                            shape.getCornerRadius()),
                        shape.getCornerRadius() <= Math.min(width, height) * .4f,
                    )
                }
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun homeScreenCardsKeepTheirAspectAndTextOccupancy() {
        // Compact cards preserve a 228:88 aspect ratio and readable label proportions.
        for (count: Int in intArrayOf(1, 2)) {
            val width: Int = if (count == 1) 167 else 300
            val height: Int = if (count == 1) 82 else 90
            val settings: WidgetSettings = WidgetSettings.defaults(true)
            settings.cities = Arrays.copyOf(CITIES, count)
            settings.style = WorldClockStyle.LIGHT
            val root: View =
                render(settings, 8300 + count, intArrayOf(width, height, width, height))
            val frame: ImageView = root.findViewById<ImageView>(R.id.widget_background)
            assertTrue(
                "Compact cards occupy only part of a tall launcher row",
                frame.getHeight() < root.getHeight(),
            )
            for (background: ImageView in backgrounds(root)) {
                assertEquals(
                    228f / 88,
                    background.getWidth().toFloat() / background.getHeight(),
                    .10f,
                )
                val shape: GradientDrawable =
                    background.getDrawable().getCurrent() as GradientDrawable
                assertEquals(30f / 88, shape.getCornerRadius() / background.getHeight(), .045f)
                val card: View = background.getParent() as View
                val name: TextView = card.findViewById<TextView>(R.id.city_name)
                val time: TextView = card.findViewById<TextView>(R.id.city_time)
                val date: TextView = card.findViewById<TextView>(R.id.city_date)
                assertTrue(
                    "The clock should occupy more than half of the card width",
                    time.getPaint().measureText("07:09") / background.getWidth() > .50f,
                )
                assertTrue(
                    "City names should retain their intended size and ellipsize",
                    name.getTextSize() >= 15,
                )
                assertTrue(
                    "The default date must retain a readable size relative to the city name",
                    date.getTextSize() / name.getTextSize() >= .67f,
                )
                assertTrue(
                    "The date caption must remain secondary to the city name",
                    date.getTextSize() <= name.getTextSize(),
                )
                assertEquals(
                    "Default 24-hour captions must retain their complete weekday and UTC offset",
                    0,
                    date.getLayout().getEllipsisCount(0),
                )
            }
        }
    }

    @Test
    fun twelveHourTimesAndFractionalOffsetsRemainUnclipped() {
        val originalLocale: Locale = Locale.getDefault()
        val originalZone: TimeZone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            AppLocales.setLanguage(context, "en")
            for (count: Int in intArrayOf(1, 2)) {
                val settings: WidgetSettings = WidgetSettings.defaults(true)
                settings.hour24 = false
                settings.style = WorldClockStyle.LIGHT
                settings.cities =
                    Arrays.copyOf(arrayOf<String>("Asia/Kathmandu", "America/St_Johns"), count)
                val width: Int = if (count == 1) 167 else 300
                val root: View = render(settings, 8400 + count, intArrayOf(width, 90, width, 90))
                for (background: ImageView in backgrounds(root)) {
                    val card: View = background.getParent() as View
                    val time: TextView = card.findViewById<TextView>(R.id.city_time)
                    val date: TextView = card.findViewById<TextView>(R.id.city_date)
                    assertTrue(time.getLayout().getLineWidth(0) <= time.getWidth() + 1)
                    assertTrue(
                        "Only the caption may fit to its available column",
                        date.getLayout().getLineWidth(0) <= date.getWidth() + 1,
                    )
                    assertEquals(
                        "The fractional UTC offset must remain complete",
                        0,
                        date.getLayout().getEllipsisCount(0),
                    )
                    assertTrue(
                        date.getText().toString().contains(":45") ||
                            date.getText().toString().contains(":30"),
                    )
                }
            }
        } finally {
            Locale.setDefault(originalLocale)
            TimeZone.setDefault(originalZone)
        }
    }

    @Test
    fun backdropFollowsTheCardContourAndRemainsCompletelyFilled() {
        val settings: WidgetSettings = WidgetSettings.defaults(true)
        settings.cities = Arrays.copyOf(CITIES, 2)
        settings.style = WorldClockStyle.LIGHT
        for (size: IntArray in
            arrayOf<IntArray>(
                intArrayOf(150, 90, 167, 82),
                intArrayOf(300, 90, 300, 90),
                intArrayOf(300, 150, 330, 160),
                intArrayOf(300, 90, 330, 160),
            )) {
            val root: View = render(settings, 8200, size)
            val frame: ImageView = root.findViewById<ImageView>(R.id.widget_background)
            if (root.findViewById<View>(R.id.world_list_divider) != null) {
                assertEquals(View.GONE, frame.visibility)
                continue
            }
            assertEquals(View.VISIBLE, frame.visibility)
            val outer: GradientDrawable = frame.getDrawable().getCurrent() as GradientDrawable
            val first: ImageView = backgrounds(root).get(0)
            val inner: GradientDrawable = first.getDrawable().getCurrent() as GradientDrawable
            // Android 11's getCornerRadii throws for a shape declared with a uniform radius.
            var innerRadius: Float = inner.getCornerRadius()
            if (innerRadius == 0f) innerRadius = inner.getCornerRadii()!![0]
            assertEquals(
                "Outer radius follows the actual content inset",
                (innerRadius + root.findViewById<View>(R.id.world_rows).getPaddingLeft()),
                outer.getCornerRadius(),
                .01f,
            )
            val pixels: Bitmap = draw(frame)
            assertEquals(0x65000000, pixels.getPixel(pixels.getWidth() / 2, pixels.getHeight() / 2))
            assertEquals(0x65000000, pixels.getPixel(0, pixels.getHeight() / 2))
            assertEquals(0, Color.alpha(pixels.getPixel(0, 0)))
        }
        settings.worldBackdropColor = -0x6699bbde
        val custom: ImageView =
            render(settings, 8200, intArrayOf(150, 90, 167, 82))
                .findViewById<ImageView>(R.id.widget_background)
        assertEquals(153, Color.alpha(draw(custom).getPixel(80, 40)))
        settings.outline = false
        assertEquals(
            View.GONE,
            render(settings, 8200, intArrayOf(150, 90, 167, 82))
                .findViewById<ImageView>(R.id.widget_background)
                .getVisibility(),
        )
    }

    @Test
    @Config(sdk = [26, 30, 36])
    fun worldKeepsItsManagedRootAndUsesATransparentRectangularOutlineContainer() {
        val root = render(WidgetSettings.defaults(true), 8600, intArrayOf(167, 82, 167, 82))
        assertEquals(R.id.widget_root, root.id)
        assertEquals(1, WidgetTestViews.find(root, R.id.widget_root).size)
        assertEquals(1, WidgetTestViews.find(root, android.R.id.background).size)
        val background = root.findViewById<FrameLayout>(android.R.id.background)
        assertSame(root, background.parent)
        assertEquals(0, background.left)
        assertEquals(0, background.top)
        assertEquals(root.width, background.width)
        assertEquals(root.height, background.height)
        // The XML attribute is recognized from API 31; the programmatic setter predates it.
        assertEquals(Build.VERSION.SDK_INT >= 31, background.clipToOutline)
        context.resources.getXml(R.layout.world_widget).use { xml ->
            var declared = false
            val androidNamespace = "http://schemas.android.com/apk/res/android"
            while (xml.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (
                    xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG &&
                        xml.getAttributeResourceValue(androidNamespace, "id", 0) ==
                            android.R.id.background
                ) {
                    assertTrue(
                        xml.getAttributeBooleanValue(androidNamespace, "clipToOutline", false),
                    )
                    declared = true
                }
                xml.next()
            }
            assertTrue(declared)
        }
        assertEquals(Color.TRANSPARENT, (background.background as ColorDrawable).color)
        draw(root)
        val outline = Outline()
        requireNotNull(background.outlineProvider).getOutline(background, outline)
        val rectangle = Rect()
        assertTrue(outline.getRect(rectangle))
        assertEquals(Rect(0, 0, root.width, root.height), rectangle)
        assertEquals(0f, outline.radius, 0f)
        assertEquals(0f, outline.alpha, 0f)
        assertTrue(outline.canClip())
        assertSame(background, root.findViewById<View>(R.id.widget_background).parent)
        assertSame(background, root.findViewById<View>(R.id.world_rows).parent)
        assertTrue(root.hasOnClickListeners())
        assertFalse(background.hasOnClickListeners())
    }

    @Test
    fun cardAndBackdropRoundnessScaleIndependentlyWithoutMovingContent() {
        var id = 8500
        for (fixture in ROUNDNESS_CASES) {
            val settings =
                WidgetSettings.defaults(true).apply {
                    cities = Arrays.copyOf(CITIES, fixture[4])
                    style = WorldClockStyle.LIGHT
                }
            val dimensions = fixture.copyOfRange(0, 4)
            val baseline = render(settings, id++, dimensions)
            val originalCards = backgrounds(baseline)
            val originalFrame = baseline.findViewById<ImageView>(R.id.widget_background)
            val standardCardShape = originalCards.first().drawable.current as GradientDrawable
            val standardCardRadius =
                if (standardCardShape.cornerRadius != 0f) standardCardShape.cornerRadius
                else standardCardShape.cornerRadii!!.maxOrNull()!!
            assertEquals(fixture[5].toFloat(), standardCardRadius, .01f)
            assertEquals(
                fixture[6].toFloat(),
                (originalFrame.drawable.current as GradientDrawable).cornerRadius,
                .01f,
            )
            for ((cardScale, backdropScale) in arrayOf(0 to 100, 100 to 0, 50 to 150, 200 to 200)) {
                settings.worldCardCornerScale = cardScale
                settings.worldBackdropCornerScale = backdropScale
                val current = render(settings, id++, dimensions)
                val currentCards = backgrounds(current)
                val frame = current.findViewById<ImageView>(R.id.widget_background)
                assertScaledCorners(originalFrame, frame, backdropScale)
                assertSameBounds(originalFrame, frame)
                assertEquals(originalFrame.visibility, frame.visibility)
                for (index in currentCards.indices) {
                    val original = originalCards[index]
                    val card = currentCards[index]
                    assertScaledCorners(original, card, cardScale)
                    assertSameBounds(original, card)
                    for (textId in intArrayOf(R.id.city_name, R.id.city_time, R.id.city_date)) {
                        val before = (original.parent as View).findViewById<TextView>(textId)
                        val after = (card.parent as View).findViewById<TextView>(textId)
                        assertSameBounds(before, after)
                        assertEquals(before.textSize, after.textSize, 0f)
                    }
                    if (cardScale == 0) assertEquals(Color.WHITE, draw(card).getPixel(0, 0))
                    if (cardScale == 100) assertTrue(draw(original).sameAs(draw(card)))
                }
                if (frame.visibility == View.VISIBLE && backdropScale == 0) {
                    val pixels = draw(frame)
                    assertEquals(
                        pixels.getPixel(pixels.width / 2, pixels.height / 2),
                        pixels.getPixel(0, 0),
                    )
                }
                if (frame.visibility == View.VISIBLE && backdropScale == 100)
                    assertTrue(draw(originalFrame).sameAs(draw(frame)))
                if (cardScale == 200)
                    assertFalse(draw(originalCards.first()).sameAs(draw(currentCards.first())))
                if (
                    (currentCards.first().parent as View).findViewById<View>(
                        R.id.world_list_divider,
                    ) != null
                ) {
                    val firstShape = originalCards.first().drawable.current as GradientDrawable
                    val standardRadius =
                        if (firstShape.cornerRadius != 0f) firstShape.cornerRadius
                        else firstShape.cornerRadii!!.maxOrNull()!!
                    val expectedRadius = Math.round(standardRadius * cardScale / 100f).toFloat()
                    val rows = WidgetTestViews.find(current, R.id.world_row)
                    assertEquals(currentCards.size, rows.size)
                    for (index in currentCards.indices) {
                        val card = currentCards[index]
                        val row = rows[index]
                        assertEquals(row.width, card.width)
                        assertEquals(row.height, card.height)
                        assertEquals(0, card.top)
                        assertEquals(0, (card.parent as View).top)
                        if (index > 0) assertEquals(rows[index - 1].bottom, row.top)
                        val shape =
                            GradientDrawable().apply {
                                setColor(Color.WHITE)
                                when {
                                    expectedRadius == 0f -> Unit
                                    currentCards.size == 1 -> cornerRadius = expectedRadius
                                    index == 0 ->
                                        cornerRadii =
                                            FloatArray(8) { if (it < 4) expectedRadius else 0f }
                                    index == currentCards.lastIndex ->
                                        cornerRadii =
                                            FloatArray(8) { if (it >= 4) expectedRadius else 0f }
                                    else -> Unit
                                }
                            }
                        // Level-list containers enable dither on their selected curved drawable.
                        val referenceDrawable =
                            if (
                                expectedRadius > 0f &&
                                    (index == 0 || index == currentCards.lastIndex)
                            )
                                LevelListDrawable().apply { addLevel(0, 10000, shape) }
                            else shape
                        val control =
                            ImageView(context).apply {
                                scaleType = ImageView.ScaleType.FIT_XY
                                setImageDrawable(referenceDrawable)
                                setImageLevel(expectedRadius.toInt())
                                setColorFilter(Color.WHITE)
                                imageAlpha = 255
                            }
                        measure(control, card.width, card.height)
                        val pixels = draw(card)
                        val referencePixels = draw(control)
                        assertTrue(
                            "List surface differs from its native contour: ${fixture.contentToString()}, scale=$cardScale, row=$index",
                            referencePixels.sameAs(pixels),
                        )
                        for (x in 1 until pixels.width - 1) {
                            if (index > 0) assertEquals(Color.WHITE, pixels.getPixel(x, 0))
                            if (index < currentCards.lastIndex)
                                assertEquals(Color.WHITE, pixels.getPixel(x, pixels.height - 1))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun directPreviewClampsScalesAndReapplicationRestoresRoundedSources() {
        for (fixture in ROUNDNESS_CASES) {
            val width = fixture[0]
            val height = fixture[1]
            val settings =
                WidgetSettings.defaults(true).apply {
                    cities = Arrays.copyOf(CITIES, fixture[4])
                    style = WorldClockStyle.LIGHT
                    worldCardCornerScale = -10
                    worldBackdropCornerScale = 250
                }
            val invalid = preview(settings, width, height)
            settings.worldCardCornerScale = 0
            settings.worldBackdropCornerScale = 200
            assertBackgroundPixelsEqual(preview(settings, width, height), invalid)
            settings.worldCardCornerScale = 250
            settings.worldBackdropCornerScale = -10
            val inverted = preview(settings, width, height)
            settings.worldCardCornerScale = 200
            settings.worldBackdropCornerScale = 0
            assertBackgroundPixelsEqual(preview(settings, width, height), inverted)
            settings.worldCardCornerScale = 0
            val current = preview(settings, width, height)
            for ((cardScale, backdropScale) in arrayOf(200 to 50, 0 to 0, 100 to 100)) {
                settings.worldCardCornerScale = cardScale
                settings.worldBackdropCornerScale = backdropScale
                WidgetRenderer.preview(
                        context,
                        -8500,
                        true,
                        settings,
                        width.toFloat(),
                        height.toFloat(),
                    )
                    .reapply(context, current)
                measure(current, width, height)
                assertBackgroundPixelsEqual(preview(settings, width, height), current)
            }
        }
    }

    private fun preview(settings: WidgetSettings, width: Int, height: Int): View {
        val parent = FrameLayout(context)
        val root =
            WidgetRenderer.preview(
                    context,
                    -8500,
                    true,
                    settings,
                    width.toFloat(),
                    height.toFloat(),
                )
                .apply(context, parent)
        parent.addView(root, FrameLayout.LayoutParams(width, height))
        measure(root, width, height)
        return root
    }

    private fun measure(root: View, width: Int, height: Int) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        WidgetTestViews.prepare(root)
    }

    private fun assertBackgroundPixelsEqual(expected: View, actual: View) {
        val first = backgrounds(expected) + expected.findViewById<ImageView>(R.id.widget_background)
        val second = backgrounds(actual) + actual.findViewById<ImageView>(R.id.widget_background)
        assertEquals(first.size, second.size)
        for (index in first.indices) {
            assertEquals(first[index].visibility, second[index].visibility)
            if (first[index].visibility == View.VISIBLE)
                assertTrue(
                    "World contour pixels differ at surface $index",
                    draw(first[index]).sameAs(draw(second[index])),
                )
        }
    }

    private fun assertScaledCorners(original: ImageView, actual: ImageView, scale: Int) {
        fun radii(view: ImageView): FloatArray {
            val shape = view.drawable.current as GradientDrawable
            val uniform = shape.cornerRadius
            if (uniform != 0f) return FloatArray(8) { uniform }
            return try {
                shape.cornerRadii ?: FloatArray(8)
            } catch (_: NullPointerException) {
                // Some platform versions omit the per-corner array for a uniform flat shape.
                FloatArray(8)
            }
        }
        val expected =
            radii(original).map { Math.round(it * scale / 100f).toFloat() }.toFloatArray()
        assertArrayEquals(expected, radii(actual), .01f)
    }

    private fun assertSameBounds(expected: View, actual: View) {
        assertEquals(expected.left, actual.left)
        assertEquals(expected.top, actual.top)
        assertEquals(expected.right, actual.right)
        assertEquals(expected.bottom, actual.bottom)
    }

    private fun render(settings: WidgetSettings, id: Int, dimensions: IntArray): View {
        WidgetSettingsStore.save(context, id, true, settings)
        val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
        val provider: AppWidgetProviderInfo = AppWidgetProviderInfo()
        provider.provider = ComponentName(context, WorldWidgetProvider::class.java)
        Shadows.shadowOf(manager).addBoundWidget(id, provider)
        val options: Bundle = Bundle()
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, dimensions[0])
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, dimensions[1])
        manager.updateAppWidgetOptions(id, options)
        val host: FrameLayout = FrameLayout(context)
        val root: View = WidgetRenderer.world(context, id).apply(context, host)
        host.addView(root, FrameLayout.LayoutParams(dimensions[2], dimensions[3]))
        host.measure(
            View.MeasureSpec.makeMeasureSpec(dimensions[2], View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dimensions[3], View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, dimensions[2], dimensions[3])
        WidgetTestViews.prepare(root)
        return root
    }

    companion object {
        private val ROUNDNESS_CASES =
            arrayOf(
                intArrayOf(167, 82, 167, 82, 2, 10, 14),
                intArrayOf(328, 82, 328, 82, 2, 18, 26),
                intArrayOf(167, 174, 167, 174, 4, 22, 30),
                intArrayOf(328, 174, 328, 174, 3, 28, 28),
                intArrayOf(328, 174, 328, 174, 1, 28, 28),
                intArrayOf(150, 90, 130, 75, 2, 12, 16),
            )
        private val CITIES: Array<String> =
            arrayOf<String>(
                "America/Los_Angeles",
                "Asia/Riyadh",
                "Europe/London",
                "Asia/Hong_Kong",
            )

        private fun backgrounds(root: View): MutableList<ImageView> {
            val result: MutableList<ImageView> = ArrayList()
            if (root.getId() == R.id.card_background) result.add(root as ImageView)
            if (root is ViewGroup)
                for (index: Int in 0 until (root as ViewGroup).getChildCount()) result.addAll(
                    backgrounds((root as ViewGroup).getChildAt(index)),
                )
            return result
        }

        private fun draw(view: View): Bitmap {
            val bitmap: Bitmap =
                Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            return bitmap
        }
    }
}

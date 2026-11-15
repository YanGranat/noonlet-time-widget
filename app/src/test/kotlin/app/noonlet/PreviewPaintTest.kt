package app.noonlet

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextClock
import android.widget.TextView
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Calendar
import java.util.ArrayList
import java.util.Arrays
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class PreviewPaintTest {
    private lateinit var context: Context
    private var previousPaused: Boolean = false
    private var previousDelay: Duration? = null

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        for (file: String in
            arrayOf<String>(
                "noonlet_widgets",
                "MainActivity",
                "widget_drafts",
                "widget_configuration",
            )) context.getSharedPreferences(file, 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
        previousPaused = ShadowChoreographer.isPaused()
        previousDelay = ShadowChoreographer.getFrameDelay()
        ShadowChoreographer.setPaused(true)
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16))
    }

    @After
    fun restoreFrames() {
        ShadowChoreographer.setFrameDelay(previousDelay)
        ShadowChoreographer.setPaused(previousPaused)
    }

    @Test
    fun roundedWorldShapesMatchCanonicalPixelsThroughSquareTransitions() {
        for ((width, height, count) in listOf(Triple(167, 82, 2), Triple(167, 174, 4),
            Triple(328, 174, 3))) {
            val settings = world(count)
            val current = render(settings, true, width, height)
            val retained = TreeSnapshot(current)
            for ((card, backdrop) in listOf(0 to 0, 100 to 100, 37 to 82, 0 to 100, 100 to 0)) {
                settings.worldCardCornerScale = card
                settings.worldBackdropCornerScale = backdrop
                assertTrue(WidgetRenderer.updatePreviewAppearance(current, settings, true))
                retained.assertUnchanged(current)
                assertPaletteEquals(render(settings, true, width, height), current)
            }
        }
    }

    @Test
    fun localWorldReapplyReusesClocksAndRebindsChangedCities() {
        val settings = world(4)
        val parent = FrameLayout(context)
        fun views() = WidgetRenderer.preview(context, -1100, true, settings, 167f, 174f,
            WidgetFont.Scope.LOCAL)
        val root = views().apply(context, parent)
        val before = WidgetTestViews.find(root, R.id.city_time)
        assertEquals(4, before.size)
        settings.fontWeight = 600
        settings.cities = settings.cities.reversedArray()
        views().reapply(context, root)
        val rebound = WidgetTestViews.find(root, R.id.city_time)
        assertEquals(before, rebound)
        rebound.forEachIndexed { index, view ->
            assertEquals(CityCatalog.timeZoneId(settings.cities[index]), (view as TextClock).timeZone)
            assertEquals(600, WidgetTestViews.weight(view))
        }
        settings.cities = settings.cities.take(2).toTypedArray()
        views().reapply(context, root)
        val remaining = WidgetTestViews.find(root, R.id.city_time)
        assertEquals(2, remaining.size)
        views().reapply(context, root)
        assertEquals(remaining, WidgetTestViews.find(root, R.id.city_time))
        remaining.forEachIndexed { index, view ->
            assertEquals(CityCatalog.timeZoneId(settings.cities[index]), (view as TextClock).timeZone)
        }
    }

    @Test
    fun worldPaintMatchesFreshNativePixelsAndForegroundAcrossRepresentativeLayouts() {
        val cases: Array<IntArray> =
            arrayOf<IntArray>(
                intArrayOf(167, 82, 1, 0, 0),
                intArrayOf(167, 82, 2, 37, 1),
                intArrayOf(328, 82, 2, 100, 2),
                intArrayOf(167, 174, 3, 37, 2),
                intArrayOf(328, 174, 1, 100, 1),
                intArrayOf(328, 174, 2, 0, 0),
                intArrayOf(328, 174, 3, 100, 2),
                intArrayOf(328, 174, 4, 37, 1),
            )
        for (value: IntArray in cases) {
            val settings: WidgetSettings = world(value[2])
            settings.style = WorldClockStyle.fromWire(arrayOf<String>("light", "dark", "auto")[value[4]])
            val current: View = render(settings, true, value[0], value[1])
            val retained: TreeSnapshot = TreeSnapshot(current)
            settings.opacity = value[3]
            assertTrue(WidgetRenderer.updatePreviewAppearance(current, settings, true))
            retained.assertUnchanged(current)
            assertPaletteEquals(render(settings, true, value[0], value[1]), current)
        }
    }

    @Test
    fun paletteTransitionRepaintsTextAndListDividersTogetherWithoutTouchingClockSpans() {
        val settings: WidgetSettings = world(4)
        settings.style = WorldClockStyle.LIGHT
        val current: View = render(settings, true, 328, 174)
        val retained: TreeSnapshot = TreeSnapshot(current)
        var previous: Int =
            (WidgetTestViews.find(current, R.id.city_time).get(0) as TextView).getCurrentTextColor()
        for (style: String in arrayOf<String>("dark", "light")) {
            settings.style = WorldClockStyle.fromWire(style)
            settings.opacity = 37
            assertTrue(WidgetRenderer.updatePreviewAppearance(current, settings, true))
            assertPaletteEquals(render(settings, true, 328, 174), current)
            val next: Int =
                (WidgetTestViews.find(current, R.id.city_time).get(0) as TextView)
                    .getCurrentTextColor()
            assertNotEquals(previous, next)
            previous = next
            retained.assertUnchanged(current)
        }
    }

    @Test
    fun automaticDayIncludesSevenAndExcludesNineteen() {
        val settings: WidgetSettings = world(1)
        settings.worldDayBackgroundColor = settings.worldDayBackgroundColor or 0xff000000.toInt()
        settings.worldNightBackgroundColor = settings.worldNightBackgroundColor or 0xff000000.toInt()
        val hours: IntArray = intArrayOf(0, 6, 7, 18, 19, 23)
        val expected: BooleanArray = booleanArrayOf(false, false, true, true, false, false)
        val reference = Instant.now()
        val utc = reference.atOffset(ZoneOffset.UTC)
        val utcMinutes = utc.hour * 60 + utc.minute
        for (style in arrayOf("auto", "light", "dark")) {
            settings.style = WorldClockStyle.fromWire(style)
            for (index in hours.indices) {
                val hour = hours[index]
                // Center fixed-offset local time within the hour without changing either system clock.
                val minutes = Math.floorMod(hour * 60 + 30 - utcMinutes + 720, 1440) - 720
                val offset = ZoneOffset.ofTotalSeconds(minutes * 60)
                val zoneId = if (minutes == 0) "GMT" else "GMT${offset.id}"
                val zone = TimeZone.getTimeZone(zoneId)
                assertEquals(zoneId, zone.id)
                assertTrue(CityCatalog.isValidSelection(zoneId))
                assertEquals(zoneId, CityCatalog.timeZoneId(zoneId))
                val centered = Calendar.getInstance(zone).apply { timeInMillis = reference.toEpochMilli() }
                assertEquals(hour, centered.get(Calendar.HOUR_OF_DAY))
                assertEquals(30, centered.get(Calendar.MINUTE))
                settings.cities = arrayOf(zoneId)
                val configured = WidgetSettings.defaults(true)
                SettingsCodec.readFrom(configured, SettingsCodec.snapshot(settings))
                assertArrayEquals(arrayOf(zoneId), configured.cities)
                assertEquals(hour, Calendar.getInstance(zone).get(Calendar.HOUR_OF_DAY))
                val day = if (style == "auto") expected[index] else style == "light"
                val root = render(configured, true, 328, 82)
                val textColor = if (day) settings.worldDayTextColor else settings.worldNightTextColor
                for (id in intArrayOf(R.id.city_time, R.id.city_name, R.id.city_date)) {
                    val text = root.findViewById<TextView>(id)
                    assertEquals("$style at $hour: text $id", textColor, text.currentTextColor)
                }
                val background = root.findViewById<ImageView>(R.id.card_background)
                assertEquals(255, background.imageAlpha)
                val fill = if (day) settings.worldDayBackgroundColor else settings.worldNightBackgroundColor
                val pixels = WidgetTestViews.draw(background)
                assertEquals(
                    "$style at $hour: card fill",
                    fill or 0xff000000.toInt(),
                    pixels.getPixel(pixels.width / 2, pixels.height / 2),
                )
                assertEquals(hour, Calendar.getInstance(zone).get(Calendar.HOUR_OF_DAY))
            }
        }
    }

    @Test
    fun transparentClockOffsetsRetainTheNativeClocksInEitherOrientation() {
        for (orientation in arrayOf("vertical", "horizontal")) {
            val settings =
                WidgetSettings().apply {
                    this.orientation = WidgetOrientation.fromWire(orientation)
                    fontFamily = "serif"
                    timeFontWeight = 450
                    dateFontWeight = 650
                    timeFormat = "'08:07'"
                    dateFormat = "'27.10.2026'"
                }
            val current = render(settings, false, 328, 174)
            val retained = TreeSnapshot(current)
            settings.timeOffsetX = 7
            settings.dateOffsetY = -9
            settings.alarmOffsetX = -5
            assertTrue(WidgetRenderer.updatePreviewAppearance(current, settings, false))
            val canonical = render(settings, false, 328, 174)
            assertTrue(
                WidgetTestViews.draw(canonical).sameAs(WidgetTestViews.draw(current)),
            )
            retained.assertUnchanged(current)
            assertEquals(
                0,
                android.graphics.Color.alpha(WidgetTestViews.draw(current).getPixel(0, 0)),
            )
        }
    }

    @Test
    fun invalidModelsAndALateMalformedCardFallBackWithoutPartiallyPainting() {
        val settings: WidgetSettings = world(3)
        settings.style = WorldClockStyle.LIGHT
        val current: View = render(settings, true, 328, 174)
        for (invalid: Array<String>? in
            arrayOf<Array<String>?>(
                null,
                emptyArray<String>(),
                Arrays.copyOf(PLACES, 2),
                arrayOf<String>("UTC", "UTC", "UTC", "UTC", "UTC"),
            )) {
            ReflectionHelpers.setField(settings, "cities", invalid)
            settings.opacity = 0
            val before: Bitmap = WidgetTestViews.draw(current)
            assertFalse(WidgetRenderer.updatePreviewAppearance(current, settings, true))
            assertTrue(before.sameAs(WidgetTestViews.draw(current)))
        }
        settings.cities = Arrays.copyOf(PLACES, 3)
        settings.style = WorldClockStyle.DARK
        settings.opacity = 37
        val last: View = WidgetTestViews.find(current, R.id.world_card).get(2)
        val date: TextClock = last.findViewById<TextClock>(R.id.city_date)
        val parent: ViewGroup = date.getParent() as ViewGroup
        val index: Int = parent.indexOfChild(date)
        val layout: ViewGroup.LayoutParams = date.getLayoutParams()
        parent.removeView(date)
        val wrongType: TextView = TextView(context)
        wrongType.setId(R.id.city_date)
        parent.addView(wrongType, index, layout)
        val before: Bitmap = WidgetTestViews.draw(current)
        val firstColor: Int =
            (current.findViewById<View>(R.id.city_name) as TextView).getCurrentTextColor()
        val firstAlpha: Int =
            (current.findViewById<View>(R.id.card_background) as ImageView).getImageAlpha()
        assertFalse(WidgetRenderer.updatePreviewAppearance(current, settings, true))
        assertEquals(
            firstColor,
            (current.findViewById<View>(R.id.city_name) as TextView).getCurrentTextColor(),
        )
        assertEquals(
            firstAlpha,
            (current.findViewById<View>(R.id.card_background) as ImageView).getImageAlpha(),
        )
        assertTrue(before.sameAs(WidgetTestViews.draw(current)))
        assertFalse(WidgetRenderer.updatePreviewAppearance(null, settings, true))
        assertFalse(WidgetRenderer.updatePreviewAppearance(FrameLayout(context), settings, true))
        assertFalse(WidgetRenderer.updatePreviewAppearance(current, settings, false))
        assertFalse(
            WidgetRenderer.updatePreviewAppearance(
                render(WidgetSettings(), false, 167, 82),
                settings,
                true,
            ),
        )
    }

    @Test
    fun worldOpacityControlRetainsTheNativeTextAndFormatObjects() {
        val id = bind(true)
        editor(id, true).use { controller ->
            val activity = controller.get()
            var root = layout(activity)
            open(root, "appearance")
            advance(33)
            root = layout(activity)
            val stage = requireNotNull(findStage(root))
            val content = requireNotNull(stage.getWidgetContent())
            val retained = TreeSnapshot(content)
            progress(root, "Opacity", 37)
            advance(33)
            layout(activity)
            assertSame(content, stage.getWidgetContent())
            retained.assertUnchanged(content)
            assertEquals(94, content.findViewById<ImageView>(R.id.card_background).imageAlpha)
        }
    }

    @Test
    fun pendingWorldFullChangesWinOverOpacityInBothSameFrameOrders() {
        for (fullControl in
            arrayOf("Font weight", "Card roundness", "Backdrop roundness")) for (opacityFirst in
            booleanArrayOf(false, true)) {
            val id = bind(true)
            editor(id, true).use { controller ->
                val activity = controller.get()
                val root = layout(activity)
                open(root, "appearance")
                if (opacityFirst) progress(root, "Opacity", 37)
                progress(root, fullControl, if (fullControl == "Font weight") 600 else 0)
                if (!opacityFirst) progress(root, "Opacity", 37)
                advance(33)
                val content = requireNotNull(findStage(layout(activity))!!.getWidgetContent())
                assertEquals(94, content.findViewById<ImageView>(R.id.card_background).imageAlpha)
                val shown = content.findViewById<TextClock>(R.id.city_time)
                assertEquals(
                    if (fullControl == "Font weight") 600 else 450,
                    WidgetTestViews.weight(shown),
                )
                advance(43)
                val saved = WidgetSettingsStore.load(context, id, true)
                assertEquals(37, saved.opacity)
                assertEquals(if (fullControl == "Font weight") 600 else 450, saved.fontWeight)
                if (fullControl != "Font weight") {
                    val target =
                        if (fullControl == "Card roundness") R.id.card_background
                        else R.id.widget_background
                    val pixels = WidgetTestViews.draw(content.findViewById<ImageView>(target))
                    assertEquals(
                        "The queued shape update must remain visible after opacity",
                        pixels.getPixel(pixels.width / 2, pixels.height / 2),
                        pixels.getPixel(0, 0),
                    )
                    assertEquals(
                        if (fullControl == "Card roundness") 0 else 100,
                        saved.worldCardCornerScale,
                    )
                    assertEquals(
                        if (fullControl == "Backdrop roundness") 0 else 100,
                        saved.worldBackdropCornerScale,
                    )
                }
            }
        }
    }

    private fun world(count: Int): WidgetSettings {
        val settings: WidgetSettings = WidgetSettings.defaults(true)
        settings.cities = Arrays.copyOf(PLACES, count)
        settings.worldDayBackgroundColor = 0x7fc13b63
        settings.worldNightBackgroundColor = -0x33dac8ab
        settings.worldDayTextColor = -0x47edcbaa
        settings.worldNightTextColor = -0x18213457
        settings.worldBackdropColor = 0x4937210f
        return settings
    }

    private fun render(settings: WidgetSettings, world: Boolean, width: Int, height: Int): View {
        val parent: FrameLayout = FrameLayout(context)
        val root: View =
            WidgetRenderer.preview(
                    context,
                    -1100,
                    world,
                    settings,
                    width.toFloat(),
                    height.toFloat(),
                )
                .apply(context, parent)
        parent.addView(root, FrameLayout.LayoutParams(width, height))
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, width, height)
        WidgetTestViews.prepare(root)
        return root
    }

    private fun bind(world: Boolean): Int {
        val id: Int = if (world) 26102 else 26101
        val settings: WidgetSettings =
            if (world) WidgetSettings.defaults(true) else WidgetSettings()
        settings.style = WorldClockStyle.LIGHT
        settings.cities = Arrays.copyOf(PLACES, 2)
        WidgetSettingsStore.save(context, id, world, settings)
        val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
        val info: AppWidgetProviderInfo = AppWidgetProviderInfo()
        info.provider =
            ComponentName(
                context,
                if (world) WorldWidgetProvider::class.java else ClockWidgetProvider::class.java,
            )
        info.initialLayout = if (world) R.layout.world_widget else R.layout.clock_widget
        Shadows.shadowOf(manager).addInstalledProvider(info)
        Shadows.shadowOf(manager).addBoundWidget(id, info)
        return id
    }

    private fun editor(id: Int, world: Boolean): ActivityController<MainActivity> {
        val intent: Intent =
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse("noonlet://edit/" + world + "/" + id))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra("world", world)
        return Robolectric.buildActivity(MainActivity::class.java, intent).setup().visible()
    }

    private class TreeSnapshot(root: View) {
        val views: MutableList<View> = ArrayList()
        val text: MutableList<TextState> = ArrayList()

        init {
            collect(root)
        }

        fun collect(root: View) {
            views.add(root)
            if (root is TextView) text.add(TextState(root as TextView))
            if (root is ViewGroup)
                for (index: Int in 0 until (root as ViewGroup).getChildCount()) collect(
                    (root as ViewGroup).getChildAt(index),
                )
        }

        fun assertUnchanged(root: View) {
            val next: TreeSnapshot = TreeSnapshot(root)
            assertEquals(views, next.views)
            for (value: TextState in text) value.assertUnchanged()
        }
    }

    private class TextState(val view: TextView) {
        val text: CharSequence
        val format12: CharSequence?
        val format24: CharSequence?
        val font: Typeface
        val size: Float
        val left: Int
        val top: Int
        val right: Int
        val bottom: Int

        init {
            text = view.getText()
            font = view.getTypeface()
            size = view.getTextSize()
            left = view.getLeft()
            top = view.getTop()
            right = view.getRight()
            bottom = view.getBottom()
            format12 = if (view is TextClock) (view as TextClock).getFormat12Hour() else null
            format24 = if (view is TextClock) (view as TextClock).getFormat24Hour() else null
        }

        fun assertUnchanged() {
            if (view is TextClock) {
                assertSame(format12, (view as TextClock).getFormat12Hour())
                assertSame(format24, (view as TextClock).getFormat24Hour())
            }
            assertSame(font, view.getTypeface())
            assertEquals(size, view.getTextSize(), 0f)
            assertEquals(left, view.getLeft())
            assertEquals(top, view.getTop())
            assertEquals(right, view.getRight())
            assertEquals(bottom, view.getBottom())
            assertSame(text, view.getText())
        }
    }

    companion object {
        private val PLACES: Array<String> =
            arrayOf<String>("America/Los_Angeles", "Asia/Riyadh", "Asia/Tokyo", "Europe/London")

        private fun assertPaletteEquals(expected: View, actual: View) {
            for (id: Int in
                intArrayOf(R.id.widget_background, R.id.card_background, R.id.world_list_divider)) {
                val first: MutableList<View> = WidgetTestViews.find(expected, id)
                val second: MutableList<View> = WidgetTestViews.find(actual, id)
                assertEquals(first.size, second.size)
                for (index: Int in 0 until first.size) {
                    val a: ImageView = first.get(index) as ImageView
                    val b: ImageView = second.get(index) as ImageView
                    assertEquals(a.getImageAlpha(), b.getImageAlpha())
                    assertEquals(a.getVisibility(), b.getVisibility())
                    if (a.getWidth() > 0 && a.getHeight() > 0)
                        assertTrue(
                            "The actual rounded fill pixels must match " + id,
                            WidgetTestViews.draw(a).sameAs(WidgetTestViews.draw(b)),
                        )
                }
            }
            for (id: Int in intArrayOf(R.id.city_name, R.id.city_time, R.id.city_date)) {
                val first: MutableList<View> = WidgetTestViews.find(expected, id)
                val second: MutableList<View> = WidgetTestViews.find(actual, id)
                assertEquals(first.size, second.size)
                for (index: Int in 0 until first.size) assertEquals(
                    (first.get(index) as TextView).getCurrentTextColor(),
                    (second.get(index) as TextView).getCurrentTextColor(),
                )
            }
        }

        private fun open(root: View, key: String) {
            if (
                root.findViewWithTag<View>("section-content:" + key).getVisibility() != View.VISIBLE
            )
                assertTrue(root.findViewWithTag<View>("section-toggle:" + key).performClick())
        }

        private fun progress(root: View, label: String, value: Int) {
            val bar: SeekBar? = described(root, label)
            assertNotNull(label, bar)
            val arguments: Bundle = Bundle()
            arguments.putFloat(
                AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                value.toFloat(),
            )
            assertTrue(
                bar!!.performAccessibilityAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),
                    arguments,
                ),
            )
        }

        private fun described(root: View, label: String): SeekBar? {
            if (root is SeekBar && label.contentEquals(root.getContentDescription()))
                return root as SeekBar
            if (root is ViewGroup)
                for (index: Int in 0 until (root as ViewGroup).getChildCount()) {
                    val result: SeekBar? = described((root as ViewGroup).getChildAt(index), label)
                    if (result != null) return result
                }
            return null
        }

        private fun findStage(root: View): WidgetPreviewStage? {
            if (root is WidgetPreviewStage) return root as WidgetPreviewStage
            if (root is ViewGroup)
                for (index: Int in 0 until (root as ViewGroup).getChildCount()) {
                    val result: WidgetPreviewStage? =
                        findStage((root as ViewGroup).getChildAt(index))
                    if (result != null) return result
                }
            return null
        }

        private fun layout(activity: MainActivity): View {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val root: View =
                (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
            repeat(2) {
                root.requestLayout()
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY),
                )
                root.layout(0, 0, 400, 850)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
            }
            WidgetTestViews.prepare(root)
            return root
        }

        private fun advance(millis: Int) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis.toLong()))
        }
    }
}

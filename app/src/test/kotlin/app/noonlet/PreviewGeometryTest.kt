package app.noonlet

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextClock
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
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewGeometryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
    }

    @Test
    fun compactClockPreviewFitsItsActualRectangleLikeTheLauncher() {
        val settings: WidgetSettings = WidgetSettings()
        settings.showDate = false
        settings.timeFormat = "HH:mm:ss"
        settings.textSize = WidgetSettings.MAX_TIME_SIZE
        WidgetSettingsStore.save(context, -1001, false, settings)
        WidgetSettingsStore.save(context, 3301, false, settings)
        val editor: View =
            inflate(
                WidgetRenderer.preview(
                    context,
                    -1001,
                    false,
                    WidgetSettingsStore.load(context, -1001, false),
                    167f,
                    82f,
                ),
                167,
                82,
            )
        val launcher: View = placed(3301, false, 167, 82)
        val time: TextClock = editor.findViewById<TextClock>(R.id.clock_time)
        assertEquals(
            (launcher.findViewById<View>(R.id.clock_time) as TextClock).getTextSize(),
            time.getTextSize(),
            0.01f,
        )
        assertTrue(
            "The rendered clock must fit its logical compact rectangle",
            time.getRight() <= 167,
        )
        val oldGeometry: View = inflate(WidgetRenderer.clock(context, -1001), 167, 82)
        assertTrue(
            "A hard-coded 300dp preview would choose oversized text",
            (oldGeometry.findViewById<View>(R.id.clock_time) as TextClock).getTextSize() >
                time.getTextSize() + 1,
        )
    }

    @Test
    fun twoCityPreviewSwitchesBetweenCompactStackAndWideRow() {
        val settings: WidgetSettings = WidgetSettings.defaults(true)
        settings.cities = arrayOf<String>("America/Los_Angeles", "Asia/Riyadh")
        WidgetSettingsStore.save(context, -1002, true, settings)
        WidgetSettingsStore.save(context, 3302, true, settings)
        for (size: IntArray in arrayOf<IntArray>(intArrayOf(167, 82), intArrayOf(328, 82))) {
            val editor: View =
                inflate(
                    WidgetRenderer.preview(
                        context,
                        -1002,
                        true,
                        WidgetSettingsStore.load(context, -1002, true),
                        size[0].toFloat(),
                        size[1].toFloat(),
                    ),
                    size[0],
                    size[1],
                )
            val launcher: View = placed(3302, true, size[0], size[1])
            val editorTimes: List<View> = WidgetTestViews.find(editor, R.id.city_time)
            val launcherTimes: List<View> = WidgetTestViews.find(launcher, R.id.city_time)
            assertEquals(2, editorTimes.size)
            val first: FloatArray = position(editorTimes.get(0))
            val second: FloatArray = position(editorTimes.get(1))
            if (size[0] < 245) assertTrue("2 x 1 must preview stacked cities", second[1] > first[1])
            else assertEquals("4 x 1 must preview cities in a row", first[1], second[1], .01f)
            for (city: Int in 0..1) assertEquals(
                (launcherTimes.get(city) as TextClock).getTextSize(),
                (editorTimes.get(city) as TextClock).getTextSize(),
                0.01f,
            )
        }
    }

    @Test
    fun narrowSceneScalesTheWholePresetWithoutChangingRendererDimensions() {
        val settings: WidgetSettings = WidgetSettings()
        settings.showDate = false
        settings.timeFormat = "HH:mm:ss"
        settings.textSize = WidgetSettings.MAX_TIME_SIZE
        WidgetSettingsStore.save(context, -1001, false, settings)
        WidgetSettingsStore.save(context, 3303, false, settings)
        val stage: WidgetPreviewStage = WidgetPreviewStage(context)
        val rendered: View =
            WidgetRenderer.preview(
                    context,
                    -1001,
                    false,
                    WidgetSettingsStore.load(context, -1001, false),
                    167f,
                    82f,
                )
                .apply(context, stage)
        stage.showWidget(rendered, 167, 82, 2, 1, 1, 0)
        stage.measure(
            View.MeasureSpec.makeMeasureSpec(155, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST),
        )
        stage.layout(0, 0, 155, stage.getMeasuredHeight())
        WidgetTestViews.prepare(stage)
        val widget: FrameLayout = stage.findViewWithTag<FrameLayout>("preview-widget")
        assertEquals(167, widget.getWidth())
        assertEquals(82, widget.getHeight())
        assertEquals(81, stage.getHeight())
        assertEquals(155 / 334f, widget.getScaleX(), .0001f)
        assertEquals(widget.getScaleX(), widget.getScaleY(), .0001f)
        val launcher: View = placed(3303, false, 167, 82)
        assertEquals(
            "Text fitting uses the 167dp model, even in a 155dp scene",
            (launcher.findViewById<View>(R.id.clock_time) as TextClock).getTextSize(),
            (rendered.findViewById<View>(R.id.clock_time) as TextClock).getTextSize(),
            .01f,
        )
        assertTrue(widget.getX() >= 0)
        assertTrue(widget.getX() + widget.getWidth() * widget.getScaleX() <= 155)
    }

    @Test
    fun naturalSceneUsesTheFullBoardForEveryPresetAtNarrowAndNormalWidths() {
        val presets: Array<IntArray> =
            arrayOf<IntArray>(
                intArrayOf(167, 82, 2, 1),
                intArrayOf(328, 82, 4, 1),
                intArrayOf(167, 174, 2, 2),
                intArrayOf(328, 174, 4, 2),
            )
        for (width: Int in intArrayOf(155, 368)) for (preset: IntArray in presets) {
            val stage: WidgetPreviewStage = worldStage(preset, 0, 0)
            measure(stage, width, 1000, View.MeasureSpec.AT_MOST)
            val widget: FrameLayout = stage.findViewWithTag<FrameLayout>("preview-widget")
            assertEquals(
                "Scene height follows the complete four-column, two-row area",
                Math.round(width * 174f / 334),
                stage.getHeight(),
            )
            val grid = minOf(stage.width / 334f, stage.height / 174f)
            assertEquals(Math.round(preset[0] * maxOf(1f, grid)), widget.width)
            assertEquals(Math.round(preset[1] * maxOf(1f, grid)), widget.height)
            assertEquals(
                "A miniature uses one uniform scale",
                widget.getScaleX(),
                widget.getScaleY(),
                .0001f,
            )
            assertEquals(minOf(1f, grid), widget.scaleX, .0001f)
            if (preset[2] == 4) {
                val displayedWidth: Float = widget.getWidth() * widget.getScaleX()
                assertTrue(
                    "A four-column widget must cover at least 98% of the preview width",
                    displayedWidth >= stage.getWidth() * .98f,
                )
                assertTrue(
                    "Only the native content inset may remain on the left",
                    widget.x <= Math.ceil(stage.width * .01).toFloat(),
                )
                assertTrue(
                    "Only the native content inset may remain on the right",
                    stage.width - widget.x - displayedWidth <= Math.ceil(stage.width * .01).toFloat(),
                )
            }
            if (preset[3] == 2)
                assertEquals(
                    stage.getHeight().toFloat(),
                    widget.getHeight() * widget.getScaleY(),
                    .6f,
                )
            val clocks: List<View> = WidgetTestViews.find(widget, R.id.city_time)
            assertEquals(2, clocks.size)
            val first: FloatArray = position(clocks.get(0))
            val second: FloatArray = position(clocks.get(1))
            if (preset[2] == 4 && preset[3] == 1)
                assertEquals(
                    "The wide one-row renderer stays horizontal",
                    first[1],
                    second[1],
                    .01f,
                )
            else
                assertTrue(
                    "The compact and tall renderers keep one row per place",
                    second[1] > first[1],
                )
            inside(stage)
        }
    }

    @Test
    fun twoColumnPresetsCanReachBothSideEdgesAndEveryAvailableRow() {
        for (width: Int in intArrayOf(155, 368)) for (preset: IntArray in
            arrayOf<IntArray>(intArrayOf(167, 82, 2, 1), intArrayOf(167, 174, 2, 2))) {
            val stage: WidgetPreviewStage = worldStage(preset, 0, 0)
            measure(stage, width, 1000, View.MeasureSpec.AT_MOST)
            val widget: FrameLayout = stage.findViewWithTag<FrameLayout>("preview-widget")
            val target: View = stage.findViewWithTag<View>("preview-drag-target")
            val right: Int = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId()
            val left: Int = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.getId()
            val down: Int = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId()
            assertEquals("The first column reaches the visible left edge", 0f, widget.getX(), .1f)
            assertEquals("The first row reaches the top edge", 0f, widget.getY(), .6f)
            assertTrue(target.performAccessibilityAction(right, null))
            assertEquals(
                "One keyboard/accessibility step is one of the four columns",
                width / 4f,
                widget.getX(),
                .1f,
            )
            assertTrue(target.performAccessibilityAction(right, null))
            assertEquals(
                "The last column reaches the visible right edge",
                width.toFloat(),
                widget.getX() + widget.getWidth() * widget.getScaleX(),
                .1f,
            )
            assertFalse(target.performAccessibilityAction(right, null))
            if (preset[3] == 1) {
                assertTrue(target.performAccessibilityAction(down, null))
                assertEquals(
                    "The last row reaches the visible bottom edge",
                    stage.getHeight().toFloat(),
                    widget.getY() + widget.getHeight() * widget.getScaleY(),
                    .6f,
                )
            }
            assertFalse(target.performAccessibilityAction(down, null))
            assertTrue(target.performAccessibilityAction(left, null))
            assertTrue(target.performAccessibilityAction(left, null))
            assertFalse(target.performAccessibilityAction(left, null))
            assertEquals(0f, widget.getX(), .1f)
            inside(stage)
        }
    }

    @Test
    fun explicitAndMaximumHeightConstraintsKeepTheBoardInsideItsParent() {
        for (mode: Int in intArrayOf(View.MeasureSpec.EXACTLY, View.MeasureSpec.AT_MOST)) {
            val stage: WidgetPreviewStage = worldStage(intArrayOf(328, 174, 4, 2), 0, 0)
            measure(stage, 368, 90, mode)
            val widget: FrameLayout = stage.findViewWithTag<FrameLayout>("preview-widget")
            assertEquals(
                "The preview must honor its parent's height constraint",
                90,
                stage.getHeight(),
            )
            assertEquals(328, widget.getWidth())
            assertEquals(174, widget.getHeight())
            assertEquals(widget.getScaleX(), widget.getScaleY(), .0001f)
            assertEquals(90f, widget.getHeight() * widget.getScaleY(), .01f)
            inside(stage)
        }
    }

    private fun worldStage(preset: IntArray, column: Int, row: Int): WidgetPreviewStage {
        val settings: WidgetSettings = WidgetSettings.defaults(true)
        settings.cities = arrayOf<String>("America/Los_Angeles", "Asia/Riyadh")
        val stage: WidgetPreviewStage = WidgetPreviewStage(context)
        val rendered: View =
            WidgetRenderer.preview(
                    context,
                    -1002,
                    true,
                    settings,
                    preset[0].toFloat(),
                    preset[1].toFloat(),
                )
                .apply(context, stage)
        stage.showWidget(rendered, preset[0], preset[1], preset[2], preset[3], column, row)
        return stage
    }

    private fun measure(stage: WidgetPreviewStage, width: Int, height: Int, heightMode: Int) {
        stage.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, heightMode),
        )
        stage.layout(0, 0, stage.getMeasuredWidth(), stage.getMeasuredHeight())
        WidgetTestViews.prepare(stage)
    }

    private fun inside(stage: WidgetPreviewStage) {
        val widget: FrameLayout = stage.findViewWithTag<FrameLayout>("preview-widget")
        assertTrue(widget.getX() >= -.1f)
        assertTrue(widget.getY() >= -.1f)
        assertTrue(widget.getX() + widget.getWidth() * widget.getScaleX() <= stage.getWidth() + .1f)
        assertTrue(
            widget.getY() + widget.getHeight() * widget.getScaleY() <= stage.getHeight() + .1f,
        )
    }

    private fun position(view: View): FloatArray {
        // RemoteViews are measured in a detached host; getLocationOnScreen returns 0 without
        // AttachInfo.
        val result: FloatArray = FloatArray(2)
        var candidate: View? = view
        while (candidate != null) {
            result[0] += candidate!!.getX()
            result[1] += candidate!!.getY()
            candidate =
                if (candidate!!.getParent() is View) candidate!!.getParent() as View else null
        }
        return result
    }

    private fun placed(id: Int, world: Boolean, width: Int, height: Int): View {
        val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
        val info: AppWidgetProviderInfo = AppWidgetProviderInfo()
        info.provider =
            ComponentName(
                context,
                if (world) WorldWidgetProvider::class.java else ClockWidgetProvider::class.java,
            )
        Shadows.shadowOf(manager).addBoundWidget(id, info)
        val options: Bundle = Bundle()
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
        manager.updateAppWidgetOptions(id, options)
        return inflate(
            if (world) WidgetRenderer.world(context, id) else WidgetRenderer.clock(context, id),
            width,
            height,
        )
    }

    private fun inflate(remote: RemoteViews, width: Int, height: Int): View {
        val parent: FrameLayout = FrameLayout(context)
        val view: View = remote.apply(context, parent)
        parent.addView(view, FrameLayout.LayoutParams(width, height))
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, width, height)
        WidgetTestViews.prepare(view)
        return view
    }
}

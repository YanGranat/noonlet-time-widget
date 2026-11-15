package app.noonlet

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.SeekBar
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SliderAccessibilityTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        for (name in listOf("noonlet_widgets", "MainActivity", "widget_drafts"))
            context.getSharedPreferences(name, 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
    }

    @Test
    fun signedOffsetsExposeTheirActualRangeAndClampRealAccessibilityActions() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val root = layout(activity)
            val stage = root.findViewWithTag<View>("preview-stage")
            val bar = slider(root, "Date horizontal offset")
            assertRange(bar, "Date horizontal offset", -40, 40, 4)
            assertTrue(set(bar, -7.4f))
            assertEquals(-7, settings(activity).dateOffsetX)
            assertEquals(33, bar.progress)
            assertRange(bar, "Date horizontal offset", -40, 40, -7)
            assertTrue(set(bar, 130f))
            assertEquals(40, settings(activity).dateOffsetX)
            assertFalse(scroll(bar, true))
            assertTrue(scroll(bar, false))
            assertEquals(39, settings(activity).dateOffsetX)
            assertTrue(set(bar, -100f))
            assertEquals(-40, settings(activity).dateOffsetX)
            assertFalse(scroll(bar, false))
            assertTrue(scroll(bar, true))
            assertEquals(-39, settings(activity).dateOffsetX)
            reset(root, "Date horizontal offset")
            assertEquals(4, settings(activity).dateOffsetX)
            assertEquals(44, bar.progress)
            assertRange(bar, "Date horizontal offset", -40, 40, 4)
            assertEquals(0, settings(activity).timeOffsetX)
            assertEquals(450, settings(activity).timeFontWeight)
            assertSame(stage, root.findViewWithTag<View>("preview-stage"))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            assertEquals(4, WidgetSettingsStore.loadUnplaced(context, false).dateOffsetX)
        }
    }

    @Test
    fun fontWeightsUseRealValuesAndOneWeightStepForEachScrollAction() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val root = layout(activity)
            val bar = slider(root, "Digit weight")
            assertRange(bar, "Digit weight", 100, 900, 450)
            assertTrue(set(bar, 400f))
            assertTrue(set(bar, 425f))
            assertEquals(450, settings(activity).timeFontWeight)
            assertEquals(7, bar.progress)
            assertRange(bar, "Digit weight", 100, 900, 450)
            assertTrue(set(bar, 619f))
            assertEquals(600, settings(activity).timeFontWeight)
            assertTrue(set(bar, 8f))
            assertEquals(100, settings(activity).timeFontWeight)
            assertFalse(scroll(bar, false))
            assertTrue(set(bar, 10_000f))
            assertEquals(900, settings(activity).timeFontWeight)
            assertFalse(scroll(bar, true))
            assertTrue(scroll(bar, false))
            assertEquals(850, settings(activity).timeFontWeight)
            assertTrue(scroll(bar, true))
            assertEquals(900, settings(activity).timeFontWeight)
            reset(root, "Digit weight")
            assertRange(bar, "Digit weight", 100, 900, 450)
            assertEquals(450, settings(activity).timeFontWeight)
            assertEquals(350, settings(activity).dateFontWeight)
        }
    }

    @Test
    fun sizesRetainTheirActualLimitsAndResetDefault() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val root = layout(activity)
            val bar = slider(root, "Time size")
            assertRange(bar, "Time size", 8, 65, 56)
            assertTrue(set(bar, 60.4f))
            assertEquals(60, settings(activity).textSize)
            assertTrue(scroll(bar, false))
            assertEquals(59, settings(activity).textSize)
            assertTrue(scroll(bar, true))
            assertEquals(60, settings(activity).textSize)
            reset(root, "Time size")
            assertEquals(56, settings(activity).textSize)
            assertRange(bar, "Time size", 8, 65, 56)
        }
    }

    @Test
    fun invalidAndDisabledActionsCannotChangeTheSliderOrSettings() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val root = layout(activity)
            val bar = slider(root, "Date horizontal offset")
            for (value in floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
                assertFalse(set(bar, value))
            assertFalse(bar.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, null))
            assertFalse(bar.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, Bundle()))
            assertEquals(4, settings(activity).dateOffsetX)
            assertEquals(44, bar.progress)
            bar.isEnabled = false
            assertFalse(set(bar, -20f))
            assertFalse(scroll(bar, true))
            assertFalse(scroll(bar, false))
            assertEquals(4, settings(activity).dateOffsetX)
            assertRange(bar, "Date horizontal offset", -40, 40, 4)
        }
    }

    private fun assertRange(bar: SeekBar, label: String, minimum: Int, maximum: Int, current: Int) {
        val info = bar.createAccessibilityNodeInfo()
        val range = requireNotNull(info.rangeInfo)
        assertEquals(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, range.type)
        assertEquals(minimum.toFloat(), range.min, 0f)
        assertEquals(maximum.toFloat(), range.max, 0f)
        assertEquals(current.toFloat(), range.current, 0f)
        if (Build.VERSION.SDK_INT >= 30) {
            assertEquals(label, info.contentDescription.toString())
            assertEquals(current.toString(), info.stateDescription.toString())
        } else assertEquals("$label, $current", info.contentDescription.toString())
    }

    private fun set(bar: SeekBar, value: Float): Boolean =
        bar.performAccessibilityAction(
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
            Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) },
        )

    private fun scroll(bar: SeekBar, forward: Boolean): Boolean =
        bar.performAccessibilityAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            null,
        )

    private fun reset(root: View, label: String) {
        val action = requireNotNull(root.findViewWithTag<View>("slider-reset:$label"))
        EditorTestInteractions.bringIntoView(root, action)
        assertTrue(action.performClick())
    }

    private fun slider(root: View, label: String): SeekBar {
        fun find(view: View): SeekBar? {
            if (view is SeekBar && view.contentDescription?.toString() == label) return view
            if (view is ViewGroup)
                for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        WidgetTestViews.revealAll(root)
        return requireNotNull(find(root)).also { EditorTestInteractions.bringIntoView(root, it) }
    }

    private fun settings(activity: MainActivity): WidgetSettings =
        activity.session.settings

    private fun layout(activity: MainActivity): View {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 850)
        WidgetTestViews.prepare(root)
        return root
    }
}

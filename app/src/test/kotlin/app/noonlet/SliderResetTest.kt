package app.noonlet

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.lang.reflect.Field
import java.time.Duration
import java.util.ArrayList
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
@Config(sdk = [34, 36], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SliderResetTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit()
        context.getSharedPreferences("widget_drafts", 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
    }

    @Test
    @Throws(Exception::class)
    fun everySliderResetsOnlyItsOwnFieldAndAutomaticallyPersistsTheSeparateDraft() {
        verifyAll(false, CLOCK)
        verifyAll(true, WORLD)
    }

    @Test
    @Throws(Exception::class)
    fun resetTargetsAndValuesFitLargeRussianTextWithoutAHeaderUnderline() {
        RuntimeEnvironment.setFontScale(1.3f)
        AppLocales.setLanguage(context, "ru")
        val sample: WidgetSettings = SettingsTestFixtures.sample()
        WidgetSettingsStore.save(context, 0, false, sample)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    var a: MainActivity = c.get()
                    var root: View = layout(a)
                    assertNull(root.findViewWithTag<View>("action-save-changes"))
                    val add: TextView = root.findViewWithTag<TextView>("action-add-widget")
                    assertEquals("Добавить на рабочий стол", add.getText().toString())
                    assertEquals(1, (add.getParent() as ViewGroup).getChildCount())
                    expand(a, "date")
                    root = layout(a)
                    val body: ViewGroup = root.findViewWithTag<ViewGroup>("section-content:date")
                    assertTrue(
                        "A group header must not have a partial-width separator below it",
                        body.getChildAt(0) is ViewGroup,
                    )
                    val reset: ImageButton =
                        root.findViewWithTag<ImageButton>("slider-reset:Сдвиг даты по горизонтали")
                    assertNotNull(reset)
                    click(a, reset)
                    assertEquals(4, settings(a).dateOffsetX)
                    val box: ViewGroup = reset.getParent().getParent() as ViewGroup
                    assertTrue(
                        "Reset keeps its complete touch target",
                        reset.getWidth() >= 48 && reset.getHeight() >= 48,
                    )
                    for (label: TextView in all(box, TextView::class.java)) {
                        assertNotNull(label.getLayout())
                        assertEquals(
                            "Slider labels must not be ellipsized",
                            0,
                            label
                                .getLayout()
                                .getEllipsisCount(label.getLayout().getLineCount() - 1),
                        )
                    }
                    WidgetTestViews.prepare(root)
                    c.recreate()
                    a = c.get()
                    root = layout(a)
                    assertEquals(4, settings(a).dateOffsetX)
                    assertFalse(
                        root
                            .findViewWithTag<View>("slider-reset:Сдвиг даты по горизонтали")
                            .isEnabled(),
                    )
                    assertEquals(
                        sample.dateOffsetX,
                        WidgetSettingsStore.load(context, 0, false).dateOffsetX,
                    )
                },
            )
    }

    @Throws(Exception::class)
    private fun verifyAll(world: Boolean, fields: Array<Array<String>>) {
        val sample: WidgetSettings = SettingsTestFixtures.sample()
        WidgetSettingsStore.save(context, 0, world, sample)
        context
            .getSharedPreferences("MainActivity", 0)
            .edit()
            .putBoolean("world_tab", world)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    var root: View = layout(a)
                    WidgetTestViews.revealAll(root)
                    assertEquals(fields.size, all(root, SeekBar::class.java).size)
                    val defaults: WidgetSettings = WidgetSettings.defaults(world)
                    for (spec: Array<String> in fields) {
                        expand(a, spec[0])
                        root = layout(a)
                        val reset: ImageButton =
                            root.findViewWithTag<ImageButton>("slider-reset:" + spec[1])
                        assertNotNull(spec[1], reset)
                        assertNotNull(reset.getDrawable())
                        assertFalse(reset.getDrawable() is BitmapDrawable)
                        val field: Field = WidgetSettings::class.java.getField(spec[2])
                        val before: Bundle = Bundle()
                        SettingsCodec.writeTo(settings(a), before)
                        val expected: WidgetSettings = WidgetSettings.defaults(world)
                        SettingsCodec.readFrom(expected, before)
                        field.setInt(expected, field.getInt(defaults))
                        assertTrue("The sample must require a reset: " + spec[2], reset.isEnabled())
                        click(a, reset)
                        SettingsTestFixtures.assertSettings(expected, settings(a))
                        assertEquals(spec[2], field.getInt(defaults), field.getInt(settings(a)))
                        assertFalse(reset.isEnabled())
                        assertTrue(reset.getContentDescription().toString().contains(spec[1]))
                        val heading: ViewGroup =
                            (reset.getParent().getParent() as ViewGroup).getChildAt(0) as ViewGroup
                        assertEquals(
                            if (spec[2].endsWith("CornerScale"))
                                AppLocales.text(
                                    a,
                                    R.string.percentage_value,
                                    field.getInt(defaults),
                                )
                            else Integer.toString(field.getInt(defaults)),
                            (heading.getChildAt(1) as TextView).getText().toString(),
                        )
                        assertEquals(
                            "Fallback defaults stay independent: " + spec[2],
                            field.getInt(sample),
                            field.getInt(WidgetSettingsStore.load(context, 0, world)),
                        )
                        assertEquals(
                            "The unplaced draft is automatically persisted: " + spec[2],
                            field.getInt(defaults),
                            field.getInt(WidgetSettingsStore.loadUnplaced(context, world)),
                        )
                    }
                    for (spec: Array<String> in fields) {
                        val field: Field = WidgetSettings::class.java.getField(spec[2])
                        assertEquals(
                            spec[2],
                            field.getInt(defaults),
                            field.getInt(WidgetSettingsStore.loadUnplaced(context, world)),
                        )
                        assertEquals(
                            spec[2],
                            field.getInt(sample),
                            field.getInt(WidgetSettingsStore.load(context, 0, world)),
                        )
                    }
                },
            )
    }

    companion object {
        private val CLOCK: Array<Array<String>> =
            arrayOf<Array<String>>(
                arrayOf<String>("appearance", "Time–date spacing", "timeDateGap"),
                arrayOf<String>("padding", "Top padding", "paddingTop"),
                arrayOf<String>("padding", "Bottom padding", "paddingBottom"),
                arrayOf<String>("padding", "Left padding", "paddingLeft"),
                arrayOf<String>("padding", "Right padding", "paddingRight"),
                arrayOf<String>("time", "Time size", "textSize"),
                arrayOf<String>("time", "Digit weight", "timeFontWeight"),
                arrayOf<String>("time", "Time horizontal offset", "timeOffsetX"),
                arrayOf<String>("time", "Time vertical offset", "timeOffsetY"),
                arrayOf<String>("date", "Date size", "dateSize"),
                arrayOf<String>("date", "Font weight", "dateFontWeight"),
                arrayOf<String>("date", "Date horizontal offset", "dateOffsetX"),
                arrayOf<String>("date", "Date vertical offset", "dateOffsetY"),
                arrayOf<String>("alarm", "Alarm size", "alarmSize"),
                arrayOf<String>("alarm", "Alarm horizontal offset", "alarmOffsetX"),
                arrayOf<String>("alarm", "Alarm vertical offset", "alarmOffsetY"),
            )
        private val WORLD: Array<Array<String>> =
            arrayOf<Array<String>>(
                arrayOf<String>("appearance", "Font weight", "fontWeight"),
                arrayOf<String>("appearance", "Opacity", "opacity"),
                arrayOf<String>("appearance", "Card roundness", "worldCardCornerScale"),
                arrayOf<String>("appearance", "Backdrop roundness", "worldBackdropCornerScale"),
            )

        private fun expand(a: MainActivity, section: String) {
            val root: View = layout(a)
            val body: View = root.findViewWithTag<View>("section-content:" + section)
            if (body.getVisibility() != View.VISIBLE)
                click(a, root.findViewWithTag<View>("section-toggle:" + section))
        }

        @Throws(Exception::class)
        private fun settings(a: MainActivity): WidgetSettings {
            return a.session.settings
        }

        private fun layout(a: MainActivity): View {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            val root: View = (a.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 360, 640)
            WidgetTestViews.prepare(root)
            return root
        }

        private fun click(a: MainActivity, target: View) {
            assertNotNull(target)
            var root: View = layout(a)
            var parent: View = target
            while (parent.getParent() is View) {
                parent = parent.getParent() as View
                if (parent is ScrollView) {
                    val scroll: ScrollView = parent as ScrollView
                    val rect: Rect = Rect()
                    target.getDrawingRect(rect)
                    (scroll.getChildAt(0) as ViewGroup).offsetDescendantRectToMyCoords(target, rect)
                    scroll.scrollTo(0, Math.max(0, rect.top - 8))
                }
            }
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            root = layout(a)
            val rect: Rect = Rect()
            target.getDrawingRect(rect)
            (root as ViewGroup).offsetDescendantRectToMyCoords(target, rect)
            assertTrue(target.isShown())
            assertTrue(rect.centerY() >= 0 && rect.centerY() < root.getHeight())
            val now: Long = SystemClock.uptimeMillis()
            val down: MotionEvent =
                MotionEvent.obtain(
                    now,
                    now,
                    MotionEvent.ACTION_DOWN,
                    rect.centerX().toFloat(),
                    rect.centerY().toFloat(),
                    0,
                )
            val up: MotionEvent =
                MotionEvent.obtain(
                    now,
                    now + 32,
                    MotionEvent.ACTION_UP,
                    rect.centerX().toFloat(),
                    rect.centerY().toFloat(),
                    0,
                )
            assertTrue(root.dispatchTouchEvent(down))
            assertTrue(root.dispatchTouchEvent(up))
            down.recycle()
            up.recycle()
            // Home application is coalesced for 75ms; the local preview updates on a display frame.
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }

        private fun <T : View> all(root: View, kind: Class<T>): MutableList<T> {
            val values: MutableList<T> = ArrayList()
            if (kind.isInstance(root)) values.add(kind.cast(root))
            if (root is ViewGroup)
                for (i: Int in 0 until (root as ViewGroup).getChildCount()) values.addAll(
                    all((root as ViewGroup).getChildAt(i), kind),
                )
            return values
        }
    }
}

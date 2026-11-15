package app.noonlet

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextClock
import android.widget.TextView
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
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorUpdateTest {
    private lateinit var context: Context
    private var manager: AppWidgetManager? = null

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        manager = AppWidgetManager.getInstance(context)
        for (file: String in
            arrayOf<String>(
                "noonlet_widgets",
                "MainActivity",
                "widget_drafts",
                "widget_configuration",
            )) context.getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
        WidgetSettingsStore.save(context, CLOCK, false, WidgetSettings())
        WidgetSettingsStore.save(context, SIBLING, false, WidgetSettings())
        val world: WidgetSettings = WidgetSettings.defaults(true)
        world.cities = arrayOf<String>("America/Los_Angeles", "Asia/Riyadh")
        WidgetSettingsStore.save(context, WORLD, true, world)
        bind(CLOCK, false)
        bind(SIBLING, false)
        bind(WORLD, true)
    }

    @Test
    fun colorAlignmentFormatAndTapChoicesKeepTheSceneAndReopenTheirCurrentSelection() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val a: MainActivity = controller.get()
                    open(a, "time")
                    val original: Surface = surface(a)
                    val date: View = original.root!!.findViewWithTag<View>("section:date")
                    clickRow(a, "Clock color")
                    val scroll: Int = original.scroll!!.getScrollY()
                    var color: AlertDialog = dialog()
                    colorInput(color)!!.setText("#FF123456")
                    assertTrue(color.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
                    assertStable(a, original)
                    assertEquals(scroll, original.scroll!!.getScrollY())
                    assertSame(date, layout(a).findViewWithTag<View>("section:date"))
                    assertEquals(
                        -0xedcbaa,
                        WidgetSettingsStore.load(context, CLOCK, false).timeColor,
                    )
                    assertEquals(
                        -0xedcbaa,
                        (host(CLOCK).findViewById<TextClock>(R.id.clock_time) as TextClock)
                            .getCurrentTextColor(),
                    )
                    assertEquals(
                        WidgetSettings().timeColor,
                        WidgetSettingsStore.load(context, SIBLING, false).timeColor,
                    )
                    assertEquals(
                        WidgetSettings().timeColor,
                        WidgetSettingsStore.load(context, 0, false).timeColor,
                    )
                    clickRow(a, "Clock color")
                    color = dialog()
                    assertEquals(
                        "The next picker opens the value actually applied",
                        "#FF123456",
                        colorInput(color)!!.getText().toString(),
                    )
                    color.cancel()

                    clickRow(a, "Time alignment")
                    choose(dialog(), 2)
                    assertStable(a, original)
                    assertEquals(WidgetAlignment.RIGHT, WidgetSettingsStore.load(context, CLOCK, false).alignment)
                    clickRow(a, "Time alignment")
                    assertEquals(2, dialog().getListView().getCheckedItemPosition())
                    dialog().cancel()

                    clickRow(a, "Time format")
                    choose(dialog(), 4)
                    assertStable(a, original)
                    assertEquals(
                        "HH:mm:ss",
                        WidgetSettingsStore.load(context, CLOCK, false).timeFormat,
                    )
                    clickRow(a, "Time format")
                    assertEquals(4, dialog().getListView().getCheckedItemPosition())
                    dialog().cancel()

                    open(a, "actions")
                    val time: View = layout(a).findViewWithTag<View>("section:time")
                    clickRow(a, "Widget tap")
                    choose(dialog(), 3)
                    assertStable(a, original)
                    assertSame(time, layout(a).findViewWithTag<View>("section:time"))
                    assertEquals("none", WidgetSettingsStore.load(context, CLOCK, false).tapAction)
                    assertEquals(
                        "clock",
                        WidgetSettingsStore.load(context, SIBLING, false).tapAction,
                    )
                    clickRow(a, "Widget tap")
                    assertEquals(3, dialog().getListView().getCheckedItemPosition())
                    dialog().cancel()
                },
            )
    }

    @Test
    fun aFontPresetRefreshesDependentWeightsAndHintsWithoutReplacingOtherSections() {
        val initial = WidgetSettingsStore.load(context, CLOCK, false)
        initial.dateFontWeight = 650
        WidgetSettingsStore.save(context, CLOCK, false, initial)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val a: MainActivity = controller.get()
                    open(a, "appearance")
                    open(a, "time")
                    open(a, "date")
                    val original: Surface = surface(a)
                    val time: View = original.root!!.findViewWithTag<View>("section:time")
                    val date: View = original.root!!.findViewWithTag<View>("section:date")
                    clickRow(a, "Font")
                    choose(dialog(), 2)
                    assertStable(a, original)
                    assertNotSame(time, layout(a).findViewWithTag<View>("section:time"))
                    assertNotSame(date, layout(a).findViewWithTag<View>("section:date"))
                    assertEquals(
                        350,
                        WidgetSettingsStore.load(context, CLOCK, false).timeFontWeight,
                    )
                    assertEquals(5, seek(layout(a), "Digit weight")!!.getProgress())
                    assertEquals(
                        350,
                        WidgetSettingsStore.load(context, CLOCK, false).dateFontWeight,
                    )
                    assertEquals(5, seek(layout(a), "Font weight")!!.getProgress())
                    assertEquals(
                        450,
                        WidgetSettingsStore.load(context, SIBLING, false).timeFontWeight,
                    )
                    clickRow(a, "Font")
                    assertEquals(2, dialog().getListView().getCheckedItemPosition())
                    dialog().cancel()
                },
            )
    }

    @Test
    fun scalarControlsKeepTheirOwnViewsAndUpdateTheSummaryAndPlacedWidget() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val a: MainActivity = controller.get()
                    open(a, "time")
                    val original: Surface = surface(a)
                    val group: View = original.root!!.findViewWithTag<View>("section:time")
                    val size: SeekBar? = seek(original.root, "Time size")
                    val progress: Bundle = Bundle()
                    progress.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 60f)
                    assertTrue(
                        size!!.performAccessibilityAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),
                            progress,
                        ),
                    )
                    assertSame(size, seek(layout(a), "Time size"))
                    assertSame(group, layout(a).findViewWithTag<View>("section:time"))
                    assertTrue(
                        layout(a)
                            .findViewWithTag<View>("section-toggle:time")
                            .getContentDescription()
                            .toString()
                            .contains("Size 60"),
                    )
                    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                    assertStable(a, original)
                    assertEquals(60, WidgetSettingsStore.load(context, CLOCK, false).textSize)
                    verifyNativeSwitchClickResult(a)
                    open(a, "date")
                    val visible: Switch? = toggle(layout(a), "Show date")
                    clickSwitch(a, visible)
                    assertFalse(visible!!.isChecked())
                    assertStable(a, original)
                    assertSame(visible, toggle(layout(a), "Show date"))
                    assertSame(size, seek(layout(a), "Time size"))
                    assertSame(group, layout(a).findViewWithTag<View>("section:time"))
                    assertTrue(
                        layout(a)
                            .findViewWithTag<View>("section-toggle:date")
                            .getContentDescription()
                            .toString()
                            .contains("Hidden"),
                    )
                    assertEquals(
                        View.GONE,
                        host(CLOCK).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                    assertEquals(
                        View.VISIBLE,
                        host(CLOCK).findViewById<View>(R.id.clock_time_row).visibility,
                    )
                    assertTrue(WidgetSettingsStore.load(context, SIBLING, false).showDate)
                },
            )
    }

    @Test
    fun advancedActionsAndTheWholeResetKeepTheRootSceneAndScrollingOwner() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val a: MainActivity = controller.get()
                    open(a, "actions")
                    val original: Surface = surface(a)
                    val time: View = original.root!!.findViewWithTag<View>("section:time")
                    verifyNativeSwitchClickResult(a)
                    clickSwitch(a, toggle(layout(a), "Advanced tap actions"))
                    assertStable(a, original)
                    assertSame(time, layout(a).findViewWithTag<View>("section:time"))
                    assertNotNull(text(layout(a), "Tap time"))
                    assertTrue(WidgetSettingsStore.load(context, CLOCK, false).advancedTap)
                    clickSwitch(a, toggle(layout(a), "Advanced tap actions"))
                    assertFalse(WidgetSettingsStore.load(context, CLOCK, false).advancedTap)
                    assertNull(text(layout(a), "Tap time"))
                    assertStable(a, original)
                    open(a, "appearance")
                    clickRow(a, "Font")
                    choose(dialog(), 3)
                    assertEquals(
                        450,
                        WidgetSettingsStore.load(context, CLOCK, false).timeFontWeight,
                    )
                    clickRow(a, "Reset appearance")
                    assertEquals(450, WidgetSettingsStore.load(context, CLOCK, false).timeFontWeight)
                    assertTrue(dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick())
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    assertStable(a, original)
                    assertEquals(
                        "sans-serif-medium",
                        WidgetSettingsStore.load(context, CLOCK, false).fontFamily,
                    )
                    assertEquals(
                        450,
                        WidgetSettingsStore.load(context, CLOCK, false).timeFontWeight,
                    )
                    assertEquals(
                        View.VISIBLE,
                        layout(a).findViewWithTag<View>("section-content:actions").getVisibility(),
                    )
                    assertEquals(
                        450,
                        WidgetSettingsStore.load(context, SIBLING, false).timeFontWeight,
                    )
                },
            )
    }

    @Test
    fun worldStyleAndCityChangesReplaceOnlyTheirOwnGroupAndPreserveOtherWidgets() {
        context
            .getSharedPreferences("MainActivity", 0)
            .edit()
            .putBoolean("world_tab", true)
            .commit()
        CityCatalog.prepare(context, AppLocales.resourceLocale(context)).join()
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val a: MainActivity = controller.get()
                    open(a, "appearance")
                    val original: Surface = surface(a)
                    val cities: View = original.root!!.findViewWithTag<View>("section:cities")
                    clickRow(a, "Day / night mode")
                    choose(dialog(), 2)
                    assertStable(a, original)
                    assertSame(cities, layout(a).findViewWithTag<View>("section:cities"))
                    assertEquals(WorldClockStyle.DARK, WidgetSettingsStore.load(context, WORLD, true).style)
                    clickRow(a, "Day / night mode")
                    assertEquals(2, dialog().getListView().getCheckedItemPosition())
                    dialog().cancel()

                    open(a, "cities")
                    val appearance: View = layout(a).findViewWithTag<View>("section:appearance")
                    clickRow(a, "Add place")
                    val picker: AlertDialog = dialog()
                    find(requireNotNull(picker.window).getDecorView(), EditText::class.java)!!
                        .setText("Tokyo")
                    idle()
                    val results: ListView? =
                        find(requireNotNull(picker.window).getDecorView(), ListView::class.java)
                    var selected: Int = -1
                    for (index: Int in 0 until results!!.getAdapter().getCount()) {
                        val value: Any = results!!.getAdapter().getItem(index)
                        if (value is Place && "Asia/Tokyo".equals((value as Place).id))
                            selected = index
                    }
                    assertTrue("The real place result must be available", selected >= 0)
                    choose(results, selected)
                    assertStable(a, original)
                    assertSame(appearance, layout(a).findViewWithTag<View>("section:appearance"))
                    assertArrayEquals(
                        arrayOf<String>("America/Los_Angeles", "Asia/Riyadh", "Asia/Tokyo"),
                        WidgetSettingsStore.load(context, WORLD, true).cities,
                    )
                    assertEquals(3, WidgetTestViews.find(host(WORLD), R.id.city_time).size)
                    tap(a, layout(a).findViewWithTag<View>("city-remove:Asia/Tokyo"))
                    assertStable(a, original)
                    assertSame(appearance, layout(a).findViewWithTag<View>("section:appearance"))
                    assertEquals(2, WidgetSettingsStore.load(context, WORLD, true).cities.size)
                    assertTrue(layout(a).findViewWithTag<View>("city-drag:America/Los_Angeles")
                        .performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
                    idle()
                    assertArrayEquals(
                        arrayOf<String>("Asia/Riyadh", "America/Los_Angeles"),
                        WidgetSettingsStore.load(context, WORLD, true).cities,
                    )
                    assertStable(a, original)
                    assertEquals(
                        450,
                        WidgetSettingsStore.load(context, CLOCK, false).timeFontWeight,
                    )
                },
            )
    }

    @Test
    fun aDismissedLocalChoiceCannotEditTheNewConcreteTarget() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { home ->
                    val a: MainActivity = home.get()
                    open(a, "time")
                    clickRow(a, "Clock color")
                    val old: AlertDialog = dialog()
                    colorInput(old)!!.setText("#FF654321")
                    val apply: View = old.getButton(AlertDialog.BUTTON_POSITIVE)
                    home.pause()
                    val edit: Intent =
                        Intent(context, MainActivity::class.java)
                            .setData(Uri.parse("noonlet://edit/false/" + SIBLING))
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, SIBLING)
                    Robolectric.buildActivity(MainActivity::class.java, edit)
                        .setup()
                        .visible()
                        .use({ other -> layout(other.get()) })
                    home.resume()
                    layout(a)
                    assertFalse(old.isShowing())
                    assertTrue(apply.performClick())
                    assertEquals(
                        WidgetSettings().timeColor,
                        WidgetSettingsStore.load(context, CLOCK, false).timeColor,
                    )
                    assertEquals(
                        WidgetSettings().timeColor,
                        WidgetSettingsStore.load(context, SIBLING, false).timeColor,
                    )
                },
            )
    }

    @Test
    fun localInitialConfigurationChangesRemainADraftUntilTheActualAddAction() {
        val fresh: Int = 24104
        bind(fresh, false)
        val configure: Intent =
            Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .setClass(context, MainActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, fresh)
        Robolectric.buildActivity(MainActivity::class.java, configure)
            .setup()
            .visible()
            .use(
                { controller ->
                    val a: MainActivity = controller.get()
                    open(a, "time")
                    val original: Surface = surface(a)
                    clickRow(a, "Time format")
                    choose(dialog(), 4)
                    assertStable(a, original)
                    assertFalse(WidgetSettingsStore.hasOwnSettings(context, fresh, false))
                    assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(a).getResultCode())
                    assertTrue(layout(a).findViewWithTag<View>("action-add-widget").performClick())
                    assertEquals(
                        "HH:mm:ss",
                        WidgetSettingsStore.load(context, fresh, false).timeFormat,
                    )
                    assertEquals(Activity.RESULT_OK, Shadows.shadowOf(a).getResultCode())
                    assertEquals(
                        fresh,
                        Shadows.shadowOf(a)
                            .getResultIntent()
                            .getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0),
                    )
                    assertEquals(
                        "HH:mm",
                        WidgetSettingsStore.load(context, CLOCK, false).timeFormat,
                    )
                },
            )
    }

    private fun bind(id: Int, world: Boolean) {
        val info: AppWidgetProviderInfo = AppWidgetProviderInfo()
        info.provider =
            ComponentName(
                context,
                if (world) WorldWidgetProvider::class.java else ClockWidgetProvider::class.java,
            )
        info.initialLayout = if (world) R.layout.world_widget else R.layout.clock_widget
        Shadows.shadowOf(manager).addInstalledProvider(info)
        Shadows.shadowOf(manager).addBoundWidget(id, info)
        val size: Bundle = Bundle()
        size.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 170)
        size.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 170)
        size.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
        size.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        manager!!.updateAppWidgetOptions(id, size)
        BaseWidgetProvider.updateWidget(context, id, world)
    }

    private fun host(id: Int): View {
        return Shadows.shadowOf(manager).getViewFor(id)
    }

    private class Surface {
        var root: View? = null
        var stage: View? = null
        var dragTarget: View? = null
        var scroll: ScrollView? = null
    }

    companion object {
        private val CLOCK: Int = 24101
        private val SIBLING: Int = 24102
        private val WORLD: Int = 24103

        private fun surface(a: MainActivity): Surface {
            val result: Surface = Surface()
            result.root = layout(a)
            result.stage = result.root!!.findViewWithTag<View>("preview-stage")
            result.dragTarget = result.root!!.findViewWithTag<View>("preview-drag-target")
            result.scroll = find(result.root, ScrollView::class.java)
            return result
        }

        private fun assertStable(a: MainActivity, previous: Surface) {
            val now: Surface = surface(a)
            assertSame(
                "The editor root must not be replaced for a setting",
                previous.root,
                now.root,
            )
            assertSame("The preview scene must remain mounted", previous.stage, now.stage)
            assertSame(previous.dragTarget, now.dragTarget)
            assertSame(previous.scroll, now.scroll)
        }

        private fun open(a: MainActivity, key: String) {
            val root: View = layout(a)
            val body: View = root.findViewWithTag<View>("section-content:" + key)
            if (body.getVisibility() != View.VISIBLE)
                tap(a, root.findViewWithTag<View>("section-toggle:" + key))
        }

        private fun clickRow(a: MainActivity, label: String) {
            var row: View? = text(layout(a), label)
            assertNotNull(label, row)
            while (!row!!.hasOnClickListeners() && row!!.getParent() is View) row =
                row!!.getParent() as View
            tap(a, row)
        }

        private fun dialog(): AlertDialog {
            idle()
            val value: AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog
            assertNotNull(value)
            assertTrue(value.isShowing())
            return value
        }

        private fun colorInput(dialog: AlertDialog): EditText? {
            return find(requireNotNull(dialog.window).getDecorView(), EditText::class.java)
        }

        private fun choose(dialog: AlertDialog, position: Int) {
            choose(dialog.getListView(), position)
        }

        private fun choose(list: ListView?, position: Int) {
            assertNotNull(list)
            assertTrue(
                list!!.performItemClick(
                    list!!.getAdapter().getView(position, null, list),
                    position,
                    position.toLong(),
                ),
            )
            idle()
        }

        private fun seek(view: View?, label: String): SeekBar? {
            return described(view, label, SeekBar::class.java)
        }

        private fun toggle(view: View, label: String): Switch? {
            return described(view, label, Switch::class.java)
        }

        private fun verifyNativeSwitchClickResult(a: MainActivity) {
            val vanilla: Switch = Switch(a)
            val changes: IntArray = intArrayOf(0)
            vanilla.setOnCheckedChangeListener({ button, checked -> changes[0]++ })
            assertFalse(
                "A vanilla switch without an OnClickListener returns false after toggling",
                vanilla.performClick(),
            )
            assertTrue(vanilla.isChecked())
            assertEquals(1, changes[0])
        }

        private fun clickSwitch(a: MainActivity, control: Switch?) {
            assertNotNull(control)
            val previous: Boolean = control!!.isChecked()
            tap(a, control)
            assertNotEquals(
                "The complete touch gesture must toggle its state",
                previous,
                control!!.isChecked(),
            )
        }

        private fun <T : View> described(view: View?, description: String, type: Class<T>): T? {
            if (type.isInstance(view) && description.contentEquals(view!!.getContentDescription()))
                return type.cast(view)
            if (view is ViewGroup)
                for (i: Int in 0 until (view as ViewGroup).getChildCount()) {
                    val found: T? = described((view as ViewGroup).getChildAt(i), description, type)
                    if (found != null) return found
                }
            return null
        }

        private fun <T : View> find(view: View?, type: Class<T>): T? {
            if (type.isInstance(view)) return type.cast(view)
            if (view is ViewGroup)
                for (i: Int in 0 until (view as ViewGroup).getChildCount()) {
                    val found: T? = find((view as ViewGroup).getChildAt(i), type)
                    if (found != null) return found
                }
            return null
        }

        private fun text(view: View, value: String): TextView? {
            if (view is TextView && value.contentEquals((view as TextView).getText()))
                return view as TextView
            if (view is ViewGroup)
                for (i: Int in 0 until (view as ViewGroup).getChildCount()) {
                    val found: TextView? = text((view as ViewGroup).getChildAt(i), value)
                    if (found != null) return found
                }
            return null
        }

        private fun layout(a: MainActivity): View {
            idle()
            val root: View = (a.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
            measure(root)
            return root
        }

        private fun measure(root: View) {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 400, 850)
            WidgetTestViews.prepare(root)
        }

        private fun tap(a: MainActivity, target: View?) {
            assertNotNull(target)
            val root: View = layout(a)
            var parent: View? = target
            while (parent!!.getParent() is View) {
                parent = parent!!.getParent() as View
                if (parent is ScrollView) {
                    val scroll: ScrollView = parent
                    val content: ViewGroup = scroll.getChildAt(0) as ViewGroup
                    val box: Rect = Rect()
                    target!!.getDrawingRect(box)
                    content.offsetDescendantRectToMyCoords(target, box)
                    scroll.scrollTo(0, Math.max(0, box.top - 8))
                    break
                }
            }
            measure(root)
            val bounds: Rect = Rect()
            target!!.getDrawingRect(bounds)
            (root as ViewGroup).offsetDescendantRectToMyCoords(target, bounds)
            assertTrue(target!!.isShown())
            assertTrue(bounds.centerY() >= 0 && bounds.centerY() < root.getHeight())
            val now: Long = SystemClock.uptimeMillis()
            val down: MotionEvent =
                MotionEvent.obtain(
                    now,
                    now,
                    MotionEvent.ACTION_DOWN,
                    bounds.centerX().toFloat(),
                    bounds.centerY().toFloat(),
                    0,
                )
            val up: MotionEvent =
                MotionEvent.obtain(
                    now,
                    now + 32,
                    MotionEvent.ACTION_UP,
                    bounds.centerX().toFloat(),
                    bounds.centerY().toFloat(),
                    0,
                )
            assertTrue(root.dispatchTouchEvent(down))
            assertTrue(root.dispatchTouchEvent(up))
            down.recycle()
            up.recycle()
            idle()
        }

        private fun idle() {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }
}

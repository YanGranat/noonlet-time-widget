package app.noonlet

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import java.util.HashMap
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreviewPersistenceTest {
    private lateinit var context: Context
    private var manager: AppWidgetManager? = null
    private var editorPreferences: SharedPreferences? = null

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
            )) context.getSharedPreferences(file, 0).edit().clear().commit()
        editorPreferences = context.getSharedPreferences("MainActivity", 0)
        AppLocales.setLanguage(context, "en")
        WidgetSettingsStore.save(context, CLOCK, false, WidgetSettings())
        val sibling: WidgetSettings = WidgetSettings()
        sibling.timeColor = -0xcc9967
        WidgetSettingsStore.save(context, SIBLING, false, sibling)
        val world: WidgetSettings = WidgetSettings.defaults(true)
        world.style = WorldClockStyle.LIGHT
        world.cities = arrayOf<String>("America/Los_Angeles", "Asia/Riyadh")
        WidgetSettingsStore.save(context, WORLD, true, world)
        bind(CLOCK, false)
        bind(SIBLING, false)
        bind(WORLD, true)
    }

    @Test
    fun allFourChoicesSurviveACompletelyNewActivityWithoutSavedInstanceState() {
        val placed: PlacedSnapshot = PlacedSnapshot()
        for (world: Boolean in booleanArrayOf(false, true)) for (size: Int in 0..3) {
            val previous: MainActivity
            editor(if (world) WORLD else CLOCK, world, null)
                .use(
                    { controller ->
                        previous = controller.get()
                        choose(previous, size)
                        assertPreset(previous, size)
                    },
                )
            editor(if (world) WORLD else CLOCK, world, null)
                .use(
                    { reopened ->
                        assertNotSame(
                            "This is a fresh editor, not recreation with a retained Bundle",
                            previous,
                            reopened.get(),
                        )
                        assertPreset(reopened.get(), size)
                    },
                )
        }
        placed.assertUnchanged()
    }

    @Test
    fun appTabsAndDirectWidgetEntriesShareOnlyTheirOwnTypeChoice() {
        val placed: PlacedSnapshot = PlacedSnapshot()
        app()
            .use(
                { controller ->
                    val a: MainActivity = controller.get()
                    choose(a, 1)
                    tab(a, "World clock")
                    choose(a, 3)
                },
            )
        app()
            .use(
                { reopened ->
                    val a: MainActivity = reopened.get()
                    assertPreset(a, 3)
                    tab(a, "Clock")
                    assertPreset(a, 1)
                },
            )
        for (id: Int in intArrayOf(CLOCK, SIBLING, WORLD)) {
            val world: Boolean = id == WORLD
            editor(id, world, null)
                .use({ direct -> assertPreset(direct.get(), if (world) 3 else 1) })
        }
        assertEquals(1, editorPreferences!!.getInt("preview_size_clock", -1))
        assertEquals(3, editorPreferences!!.getInt("preview_size_world", -1))
        placed.assertUnchanged()
    }

    @Test
    fun absentOrInvalidValuesUseTheTypeDefaultAndKeepTheWorldAutomaticChoice() {
        val world: WidgetSettings = WidgetSettingsStore.load(context, WORLD, true)
        world.cities = arrayOf<String>("America/Los_Angeles", "Asia/Riyadh", "Asia/Tokyo")
        WidgetSettingsStore.save(context, WORLD, true, world)
        BaseWidgetProvider.updateWidget(context, WORLD, true)
        val placed: PlacedSnapshot = PlacedSnapshot()
        editor(WORLD, true, null).use({ controller -> assertPreset(controller.get(), 2) })
        for (invalid: Any in arrayOf<Any>(-8, 99, "2")) {
            val values: SharedPreferences.Editor = editorPreferences!!.edit()
            for (key: String in arrayOf<String>("preview_size_clock", "preview_size_world")) {
                if (invalid is Int) values.putInt(key, invalid)
                else values.putString(key, invalid as String)
            }
            assertTrue(values.commit())
            editor(CLOCK, false, null).use({ clock -> assertPreset(clock.get(), 0) })
            editor(WORLD, true, null).use({ threePlaces -> assertPreset(threePlaces.get(), 2) })
        }
        editorPreferences!!.edit().putInt("preview_size_world", 0).commit()
        editor(WORLD, true, null).use({ explicit -> assertPreset(explicit.get(), 0) })
        placed.assertUnchanged()
    }

    @Test
    fun anInstanceBundleOverridesTheDurableSizeWithoutWritingAnOldChoiceBack() {
        val placed: PlacedSnapshot = PlacedSnapshot()
        val saved: Bundle = Bundle()
        editor(CLOCK, false, null)
            .use(
                { original ->
                    choose(original.get(), 1)
                    original.saveInstanceState(saved)
                },
            )
        editor(CLOCK, false, null).use({ newer -> choose(newer.get(), 3) })
        editor(CLOCK, false, saved)
            .use(
                { restored ->
                    assertPreset(restored.get(), 1)
                    assertEquals(
                        "Restoring an instance is not another user size choice",
                        3,
                        editorPreferences!!.getInt("preview_size_clock", -1),
                    )
                },
            )
        editor(CLOCK, false, null).use({ cold -> assertPreset(cold.get(), 3) })
        placed.assertUnchanged()
    }

    @Test
    fun sizeIsDurableWhilePoseStillBelongsOnlyToTheSavedInstance() {
        val placed: PlacedSnapshot = PlacedSnapshot()
        val saved: Bundle = Bundle()
        editor(CLOCK, false, null)
            .use(
                { original ->
                    val a: MainActivity = original.get()
                    choose(a, 0)
                    val cover: View = stage(a).findViewWithTag<View>("preview-drag-target")
                    assertTrue(
                        cover.performAccessibilityAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.getId(),
                            null,
                        ),
                    )
                    assertTrue(
                        cover.performAccessibilityAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId(),
                            null,
                        ),
                    )
                    assertPose(a, 3, 2)
                    original.saveInstanceState(saved)
                },
            )
        editor(CLOCK, false, null)
            .use(
                { cold ->
                    assertPreset(cold.get(), 0)
                    assertPose(cold.get(), 2, 1)
                },
            )
        editor(CLOCK, false, saved)
            .use(
                { restored ->
                    assertPreset(restored.get(), 0)
                    assertPose(restored.get(), 3, 2)
                },
            )
        placed.assertUnchanged()
    }

    @Test
    fun pausingAnOlderEditorCannotOverwriteANewerOrInactiveTypeChoice() {
        val placed: PlacedSnapshot = PlacedSnapshot()
        for (newerWorld: Boolean in booleanArrayOf(false, true)) {
            editorPreferences!!
                .edit()
                .remove("preview_size_clock")
                .remove("preview_size_world")
                .commit()
            editor(CLOCK, false, null)
                .use(
                    { older ->
                        choose(older.get(), 1)
                        editor(if (newerWorld) WORLD else CLOCK, newerWorld, null)
                            .use({ newer -> choose(newer.get(), if (newerWorld) 3 else 2) })
                        older.pause()
                        assertEquals(
                            if (newerWorld) 3 else 2,
                            editorPreferences!!.getInt(
                                if (newerWorld) "preview_size_world" else "preview_size_clock",
                                -1,
                            ),
                        )
                        if (newerWorld)
                            assertEquals(1, editorPreferences!!.getInt("preview_size_clock", -1))
                    },
                )
            editor(if (newerWorld) WORLD else CLOCK, newerWorld, null)
                .use({ cold -> assertPreset(cold.get(), if (newerWorld) 3 else 2) })
        }
        placed.assertUnchanged()
    }

    private fun editor(id: Int, world: Boolean, saved: Bundle?): ActivityController<MainActivity> {
        val intent: Intent =
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse("noonlet://edit/" + world + "/" + id))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra("world", world)
        val controller: ActivityController<MainActivity> =
            Robolectric.buildActivity(MainActivity::class.java, intent)
        if (saved == null) return controller.setup().visible()
        return controller.create(saved).start().restoreInstanceState(saved).resume().visible()
    }

    private fun app(): ActivityController<MainActivity> {
        return Robolectric.buildActivity(MainActivity::class.java).setup().visible()
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

    private inner class PlacedSnapshot {
        val records: MutableMap<String, *> =
            HashMap(context.getSharedPreferences("noonlet_widgets", 0).getAll())
        val options: MutableMap<Int, Bundle> = HashMap()
        val views: MutableMap<Int, View> = HashMap()

        init {
            for (id: Int in intArrayOf(CLOCK, WORLD, SIBLING)) {
                options.put(id, Bundle(manager!!.getAppWidgetOptions(id)))
                views.put(id, Shadows.shadowOf(manager).getViewFor(id))
            }
        }

        fun assertUnchanged() {
            assertEquals(
                "Preview size cannot edit any placed widget settings",
                records,
                context.getSharedPreferences("noonlet_widgets", 0).getAll(),
            )
            for (id: Int in intArrayOf(CLOCK, WORLD, SIBLING)) {
                val before: Bundle = checkNotNull(options[id])
                val after: Bundle = manager!!.getAppWidgetOptions(id)
                assertEquals(before.keySet(), after.keySet())
                for (key: String in before.keySet()) assertEquals(
                    "Launcher allocation changed: " + key,
                    before.get(key),
                    after.get(key),
                )
                assertSame(
                    "A preview-only action must not send another placed RemoteViews",
                    views.get(id),
                    Shadows.shadowOf(manager).getViewFor(id),
                )
            }
        }
    }

    companion object {
        private val CLOCK: Int = 27101
        private val WORLD: Int = 27102
        private val SIBLING: Int = 27103
        private val DIMENSIONS: Array<IntArray> =
            arrayOf<IntArray>(
                intArrayOf(167, 82),
                intArrayOf(328, 82),
                intArrayOf(167, 174),
                intArrayOf(328, 174),
            )

        private fun choose(activity: MainActivity, size: Int) {
            val choice: View = layout(activity).findViewWithTag<View>("preview-size:" + size)
            assertNotNull(choice)
            assertTrue(choice.performClick())
            layout(activity)
        }

        private fun tab(activity: MainActivity, name: String) {
            val choice: TextView? = findText(layout(activity), name)
            assertNotNull(name, choice)
            assertTrue(choice!!.performClick())
            layout(activity)
        }

        private fun findText(view: View, text: String): TextView? {
            if (view is TextView && text.contentEquals((view as TextView).getText()))
                return view as TextView
            if (view is ViewGroup)
                for (i: Int in 0 until (view as ViewGroup).getChildCount()) {
                    val found: TextView? = findText((view as ViewGroup).getChildAt(i), text)
                    if (found != null) return found
                }
            return null
        }

        private fun layout(activity: MainActivity): View {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val root: View =
                (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 400, 850)
            WidgetTestViews.prepare(root)
            return root
        }

        private fun stage(activity: MainActivity): WidgetPreviewStage {
            return layout(activity).findViewWithTag<WidgetPreviewStage>("preview-stage")
        }

        private fun assertPreset(activity: MainActivity, size: Int) {
            val root: View = layout(activity)
            for (i: Int in 0..3) assertEquals(
                "The selected size must be visible on its actual control",
                i == size,
                root.findViewWithTag<View>("preview-size:" + i).isSelected(),
            )
            val widget: FrameLayout = root.findViewWithTag<FrameLayout>("preview-widget")
            assertNotNull(widget)
            val stage = root.findViewWithTag<WidgetPreviewStage>("preview-stage")
            val grid = maxOf(1f, minOf(stage.width / 334f, stage.height / 174f))
            assertEquals(if (size == 0) 170 else Math.round(DIMENSIONS[size][0] * grid), widget.width)
            assertEquals(if (size == 0) 90 else Math.round(DIMENSIONS[size][1] * grid), widget.height)
        }

        private fun assertPose(activity: MainActivity, column: Int, row: Int) {
            val cover: View = stage(activity).findViewWithTag<View>("preview-drag-target")
            assertTrue(
                java.lang.String.valueOf(cover.getContentDescription()),
                java.lang.String.valueOf(cover.getContentDescription())
                    .endsWith("Column " + column + ", row " + row),
            )
        }
    }
}

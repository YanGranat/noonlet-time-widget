package app.noonlet

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.time.Duration
import java.util.HashMap
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
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InitialConfigurationIsolationTest {
    private lateinit var context: Context

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
    }

    @Test
    @Throws(Exception::class)
    fun aNormalEditorCannotAutosaveAnUnconfirmedLauncherConfiguration() {
        for (world: Boolean in booleanArrayOf(false, true)) {
            val id: Int = if (world) 2412 else 2411
            bind(id, world)
            Robolectric.buildActivity(MainActivity::class.java, configure(id))
                .setup()
                .visible()
                .use(
                    { initial ->
                        initial.pause()
                        Robolectric.buildActivity(MainActivity::class.java)
                            .setup()
                            .visible()
                            .use(
                                { ordinary ->
                                    toggleDate(ordinary.get(), world)
                                    assertFalse(
                                        "Only the initial Add may commit this assigned ID",
                                        WidgetSettingsStore.hasOwnSettings(context, id, world),
                                    )
                                    assertEquals(0, target(ordinary.get()))
                                    assertNotNull(
                                        layout(ordinary.get())
                                            .findViewWithTag<View>("action-add-widget"),
                                    )
                                    assertFalse(
                                        WidgetSettingsStore.loadUnplaced(context, world).showDate,
                                    )
                                },
                            )
                        val direct: Intent =
                            Intent(context, MainActivity::class.java)
                                .setData(
                                    android.net.Uri.parse("noonlet://edit/" + world + "/" + id),
                                )
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                                .putExtra("world", world)
                        Robolectric.buildActivity(MainActivity::class.java, direct)
                            .setup()
                            .visible()
                            .use(
                                { editor ->
                                    toggleDate(editor.get(), world)
                                    assertFalse(
                                        "A direct edit URI cannot bypass a pending initial configuration",
                                        WidgetSettingsStore.hasOwnSettings(context, id, world),
                                    )
                                    assertNotNull(
                                        "The pending ID still requires Add",
                                        layout(editor.get())
                                            .findViewWithTag<View>("action-add-widget"),
                                    )
                                },
                            )
                    },
                )
        }
    }

    @Test
    @Throws(Exception::class)
    fun pendingConfigurationIsPerTypeAndDoesNotHideAnotherPlacedWidget() {
        WidgetSettingsStore.save(context, 2422, true, WidgetSettings.defaults(true))
        bind(2422, true)
        bind(2421, true)
        WidgetSettingsStore.save(context, 2423, false, WidgetSettings())
        bind(2423, false)
        Robolectric.buildActivity(MainActivity::class.java, configure(2421))
            .setup()
            .visible()
            .use(
                { initial ->
                    initial.pause()
                    Robolectric.buildActivity(MainActivity::class.java)
                        .setup()
                        .visible()
                        .use(
                            { ordinary ->
                                val activity: MainActivity = ordinary.get()
                                toggleDate(activity, true)
                                assertFalse(WidgetSettingsStore.load(context, 2422, true).showDate)
                                assertFalse(WidgetSettingsStore.hasOwnSettings(context, 2421, true))
                                assertEquals(2422, target(activity))
                                assertTrue(text(layout(activity), "Clock")!!.performClick())
                                toggleDate(activity, false)
                                assertFalse(WidgetSettingsStore.load(context, 2423, false).showDate)
                            },
                        )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun aCancelledConfigurationStaysExcludedWhileTheHostHasNotDeletedItsId() {
        bind(2431, false)
        Robolectric.buildActivity(MainActivity::class.java, configure(2431))
            .setup()
            .visible()
            .use({ initial -> })
        // Deliberately leave the ID bound: deletion by the launcher arrives after Activity
        // cancellation.
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { ordinary ->
                    toggleDate(ordinary.get(), false)
                    assertFalse(
                        "Activity destruction is not placement confirmation",
                        WidgetSettingsStore.hasOwnSettings(context, 2431, false),
                    )
                    assertEquals(0, target(ordinary.get()))
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun anAcceptedConfigurationBecomesTheAutomaticallyEditedPlacedWidget() {
        bind(2441, false)
        Robolectric.buildActivity(MainActivity::class.java, configure(2441))
            .setup()
            .visible()
            .use(
                { initial ->
                    assertTrue(
                        layout(initial.get())
                            .findViewWithTag<View>("action-add-widget")
                            .performClick(),
                    )
                },
            )
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { ordinary ->
                    assertEquals(2441, target(ordinary.get()))
                    toggleDate(ordinary.get(), false)
                    assertFalse(WidgetSettingsStore.load(context, 2441, false).showDate)
                    assertNull(layout(ordinary.get()).findViewWithTag<View>("action-add-widget"))
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun unconfirmedBindingsRemainUnplacedAcrossTabsAndRecreation() {
        bind(2451, false)
        bind(2452, true)
        context
            .getSharedPreferences("MainActivity", 0)
            .edit()
            .putInt("clock_target", 2451)
            .putInt("world_target", 2452)
            .commit()
        val detached = WidgetSettings().apply { textSize = 60 }
        WidgetSettingsStore.save(context, 2491, false, detached)
        WidgetSettingsStore.save(context, 2492, true, detached)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { ordinary ->
                    assertUnplaced(ordinary.get())
                    toggleDate(ordinary.get(), false)
                    assertFalse(WidgetSettingsStore.loadUnplaced(context, false).showDate)
                    ordinary.recreate()
                    assertUnplaced(ordinary.get())
                    assertTrue(text(layout(ordinary.get()), "World clock")!!.performClick())
                    assertUnplaced(ordinary.get())
                    toggleDate(ordinary.get(), true)
                    assertFalse(WidgetSettingsStore.loadUnplaced(context, true).showDate)
                    ordinary.recreate()
                    assertUnplaced(ordinary.get())
                    assertTrue(text(layout(ordinary.get()), "Clock")!!.performClick())
                    assertUnplaced(ordinary.get())
                    assertFalse(WidgetSettingsStore.loadUnplaced(context, false).showDate)
                },
            )
        for (world in booleanArrayOf(false, true)) {
            val id = if (world) 2452 else 2451
            assertFalse(WidgetSettingsStore.pendingConfiguration(context, id, world))
            assertFalse(WidgetSettingsStore.hasOwnSettings(context, id, world))
            assertNotNull(AppWidgetManager.getInstance(context).getAppWidgetInfo(id))
            assertEquals(
                60,
                WidgetSettingsStore.load(context, if (world) 2492 else 2491, world).textSize,
            )
        }
    }

    @Test
    @Throws(Exception::class)
    fun aTargetedLegacyEditorCanSaveBeforeItsFirstOrdinaryAppSelection() {
        for (world in booleanArrayOf(false, true)) {
            val id = if (world) 2454 else 2453
            bind(id, world)
            val direct =
                Intent(context, MainActivity::class.java)
                    .setData(Uri.parse("noonlet://edit/$world/$id"))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra("world", world)
            Robolectric.buildActivity(MainActivity::class.java, direct).setup().visible().use {
                editor ->
                assertFalse(WidgetSettingsStore.hasOwnSettings(context, id, world))
                assertNull(layout(editor.get()).findViewWithTag<View>("action-add-widget"))
                toggleDate(editor.get(), world)
                assertTrue(WidgetSettingsStore.hasOwnSettings(context, id, world))
                assertFalse(WidgetSettingsStore.load(context, id, world).showDate)
                assertTrue(
                    layout(editor.get())
                        .findViewWithTag<View>("action-save-changes")
                        .performClick(),
                )
                assertTrue(editor.get().isFinishing)
            }
            Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { ordinary ->
                assertEquals(id, target(ordinary.get()))
                assertNull(layout(ordinary.get()).findViewWithTag<View>("action-add-widget"))
                assertNotNull(layout(ordinary.get()).findViewWithTag<View>("action-save-changes"))
                assertFalse(WidgetSettingsStore.load(context, id, world).showDate)
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun aConfirmedWidgetWinsOverAnUnconfirmedRememberedBinding() {
        for (world in booleanArrayOf(false, true)) {
            val unknown = if (world) 2472 else 2471
            val confirmed = if (world) 2482 else 2481
            bind(unknown, world)
            WidgetSettingsStore.save(context, confirmed, world, WidgetSettings.defaults(world))
            bind(confirmed, world)
            context
                .getSharedPreferences("MainActivity", 0)
                .edit()
                .putBoolean("world_tab", world)
                .putInt(if (world) "world_target" else "clock_target", unknown)
                .commit()
            Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { ordinary ->
                assertEquals(confirmed, target(ordinary.get()))
                assertNull(layout(ordinary.get()).findViewWithTag<View>("action-add-widget"))
                assertNotNull(layout(ordinary.get()).findViewWithTag<View>("action-save-changes"))
                toggleDate(ordinary.get(), world)
                assertFalse(WidgetSettingsStore.load(context, confirmed, world).showDate)
                assertFalse(WidgetSettingsStore.hasOwnSettings(context, unknown, world))
                assertNotNull(AppWidgetManager.getInstance(context).getAppWidgetInfo(unknown))
            }
        }
    }

    @Test
    fun startupRemovesOnlyTheTwoKnownLegacyPreviewRecords() {
        val local: WidgetSettings = WidgetSettings()
        local.textSize = 59
        WidgetSettingsStore.save(context, 2461, false, local)
        bind(2461, false)
        val defaults: WidgetSettings = WidgetSettings()
        defaults.textSize = 64
        WidgetSettingsStore.save(context, 0, false, defaults)
        for (id: Int in intArrayOf(-1001, -1002, -1003)) for (world: Boolean in
            booleanArrayOf(false, true)) {
            val record: WidgetSettings = WidgetSettings.defaults(world)
            record.textSize = 62
            WidgetSettingsStore.save(context, id, world, record)
        }
        val expected: MutableMap<String, Any> =
            HashMap(context.getSharedPreferences("noonlet_widgets", 0).getAll())
        expected.keys.removeIf(
            { key ->
                key.startsWith("clock.-1001.") || key.startsWith("world.-1002.")
            },
        )
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { ordinary ->
                    assertEquals(
                        "Unrelated IDs, defaults and opposite namespaces must remain byte-for-byte intact",
                        expected,
                        context.getSharedPreferences("noonlet_widgets", 0).getAll(),
                    )
                },
            )
    }

    private fun configure(id: Int): Intent {
        return Intent(context, MainActivity::class.java)
            .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
    }

    private fun bind(id: Int, world: Boolean) {
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
    }

    private fun layout(activity: MainActivity): View {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        val root: View =
            (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 400, 850)
        return root
    }

    private fun toggleDate(activity: MainActivity, world: Boolean) {
        val root: View = layout(activity)
        val key: String = if (world) "appearance" else "date"
        if (root.findViewWithTag<View>("section-content:" + key).getVisibility() != View.VISIBLE)
            assertTrue(root.findViewWithTag<View>("section-toggle:" + key).performClick())
        val label: TextView? =
            text(layout(activity), if (world) "Date and time difference" else "Show date")
        assertNotNull(label)
        assertTrue((label!!.getParent() as View).performClick())
        layout(activity)
    }

    @Throws(Exception::class)
    private fun target(activity: MainActivity): Int {
        return activity.session.homeTargetId
    }

    private fun assertUnplaced(activity: MainActivity) {
        assertEquals(0, target(activity))
        val root = layout(activity)
        val add = root.findViewWithTag<TextView>("action-add-widget")
        assertNotNull(add)
        assertEquals("Add to home screen", add.text.toString())
        assertNull(root.findViewWithTag<View>("action-save-changes"))
    }

    private fun text(root: View, value: String): TextView? {
        if (root is TextView && value.contentEquals((root as TextView).getText()))
            return root as TextView
        if (root is ViewGroup)
            for (i: Int in 0 until (root as ViewGroup).getChildCount()) {
                val found: TextView? = text((root as ViewGroup).getChildAt(i), value)
                if (found != null) return found
            }
        return null
    }
}

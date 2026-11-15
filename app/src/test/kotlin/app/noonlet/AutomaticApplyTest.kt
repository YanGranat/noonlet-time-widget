package app.noonlet

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import app.noonlet.PinRequestFixtures.CapturedPin
import app.noonlet.PinRequestFixtures.CapturedPins
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
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34, 36],
    qualifiers = "en-rUS-w400dp-h850dp-mdpi",
    shadows = [CapturedPins::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutomaticApplyTest {
    private lateinit var context: Context
    private var manager: AppWidgetManager? = null

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        PinRequestFixtures.installHome(context)
        manager = AppWidgetManager.getInstance(context)
        for (file: String in
            arrayOf<String>("noonlet_widgets", "MainActivity", "widget_drafts")) context
            .getSharedPreferences(file, 0)
            .edit()
            .clear()
            .commit()
        AppLocales.setLanguage(context, "en")
        CapturedPins.requests.clear()
        CapturedPins.supported = true
        CapturedPins.accepted = true
        CapturedPins.failure = null
    }

    @Test
    @Throws(Exception::class)
    fun placedClockAndWorldApplyControlsImmediatelyAndOfferSaveChanges() {
        for (world: Boolean in booleanArrayOf(false, true)) {
            val id: Int = if (world) 2212 else 2211
            val sibling: Int = if (world) 2222 else 2221
            WidgetSettingsStore.save(context, id, world, WidgetSettings.defaults(world))
            bind(id, world)
            bind(sibling, world)
            context
                .getSharedPreferences("MainActivity", 0)
                .edit()
                .putBoolean("world_tab", world)
                .commit()
            Robolectric.buildActivity(MainActivity::class.java)
                .setup()
                .visible()
                .use(
                    { controller ->
                        val activity: MainActivity = controller.get()
                        val root: View = layout(activity)
                        val save = root.findViewWithTag<TextView>("action-save-changes")
                        assertNotNull(save)
                        assertEquals("Save changes", save.text.toString())
                        assertNull(root.findViewWithTag<View>("action-add-widget"))
                        assertTrue(
                            root
                                .findViewWithTag<View>(
                                    "section-toggle:" + (if (world) "appearance" else "date"),
                                )
                                .performClick(),
                        )
                        val label: TextView? =
                            text(
                                layout(activity),
                                if (world) "Date and time difference" else "Show date",
                            )
                        assertNotNull(label)
                        assertTrue((label!!.getParent() as View).performClick())
                        layout(activity)
                        assertFalse(WidgetSettingsStore.load(context, id, world).showDate)
                        assertEquals(
                            View.GONE,
                            Shadows.shadowOf(manager)
                                .getViewFor(id)
                                .findViewById<View>(
                                    if (world) R.id.city_date else R.id.clock_date_row,
                                )
                                .getVisibility(),
                        )
                        assertTrue(WidgetSettingsStore.load(context, sibling, world).showDate)
                        assertNoFallbackDefaults(world)
                        assertFalse(activity.isFinishing())
                        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
                        assertTrue(CapturedPins.requests.isEmpty())
                    },
                )
        }
    }

    @Test
    @Throws(Exception::class)
    fun directPlacedEditorFlushesQueuedChangesBeforePauseAndKeepsOtherIdsIndependent() {
        WidgetSettingsStore.save(context, 2231, false, WidgetSettings.defaults(false))
        bind(2231, false)
        bind(2232, false)
        val direct: Intent =
            Intent(context, MainActivity::class.java)
                .setData(android.net.Uri.parse("noonlet://edit/false/2231"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 2231)
        Robolectric.buildActivity(MainActivity::class.java, direct)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    settings(activity).timeOffsetX = 17
                    activity.preview.queueFull()
                    controller.pause()
                    assertEquals(17, WidgetSettingsStore.load(context, 2231, false).timeOffsetX)
                    assertEquals(0, WidgetSettingsStore.load(context, 2232, false).timeOffsetX)
                    assertNoFallbackDefaults(false)
                    assertEquals(Activity.RESULT_OK, Shadows.shadowOf(activity).getResultCode())
                    assertEquals(
                        2231,
                        Shadows.shadowOf(activity)
                            .getResultIntent()
                            .getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0),
                    )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun unplacedDraftsSurviveANewActivityWithoutBecomingFallbackWidgetSettings() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    addOnly(activity)
                    settings(activity).textSize = 63
                    settings(activity).dateOffsetX = 17
                    activity.preview.refresh()
                    assertTrue(text(layout(activity), "World clock")!!.performClick())
                    settings(activity).cities =
                        arrayOf<String>(CityCatalog.SAN_FRANCISCO, "Asia/Riyadh")
                    settings(activity).opacity = 73
                    activity.preview.refresh()
                },
            )
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    addOnly(activity)
                    assertEquals(73, settings(activity).opacity)
                    assertArrayEquals(
                        arrayOf<String>(CityCatalog.SAN_FRANCISCO, "Asia/Riyadh"),
                        settings(activity).cities,
                    )
                    assertTrue(text(layout(activity), "Clock")!!.performClick())
                    assertEquals(63, settings(activity).textSize)
                    assertEquals(17, settings(activity).dateOffsetX)
                },
            )
        assertNoFallbackDefaults(false)
        assertNoFallbackDefaults(true)
        assertEquals(56, WidgetSettingsStore.load(context, 0, false).textSize)
        assertEquals(0, manager!!.getAppWidgetIds(provider(false)).size)
    }

    @Test
    @Throws(Exception::class)
    fun initialLauncherConfigurationUsesTheUnplacedDraftButCancelDoesNotApplyIt() {
        val draft: WidgetSettings = WidgetSettings()
        draft.textSize = 63
        WidgetSettingsStore.saveUnplaced(context, false, draft)
        bind(2241, false)
        val initial: Intent =
            Intent(context, MainActivity::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 2241)
        Robolectric.buildActivity(MainActivity::class.java, initial)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    addOnly(activity)
                    assertEquals(63, settings(activity).textSize)
                    settings(activity).textSize = 65
                    activity.preview.refresh()
                    controller.recreate()
                    assertEquals(65, settings(controller.get()).textSize)
                    assertFalse(WidgetSettingsStore.hasOwnSettings(context, 2241, false))
                    assertEquals(
                        Activity.RESULT_CANCELED,
                        Shadows.shadowOf(controller.get()).getResultCode(),
                    )
                },
            )
        assertEquals(63, WidgetSettingsStore.loadUnplaced(context, false).textSize)
        assertFalse(WidgetSettingsStore.hasOwnSettings(context, 2241, false))
    }

    @Test
    @Throws(Exception::class)
    fun pinRequestsHaveIndependentPreviewsMutableCallbacksAndAcceptedSnapshots() {
        for (world: Boolean in booleanArrayOf(false, true)) {
            context
                .getSharedPreferences("MainActivity", 0)
                .edit()
                .putBoolean("world_tab", world)
                .commit()
            Robolectric.buildActivity(MainActivity::class.java)
                .setup()
                .visible()
                .use(
                    { controller ->
                        val activity: MainActivity = controller.get()
                        settings(activity).showDate = false
                        settings(activity).textSize = 61
                        activity.preview.refresh()
                        assertTrue(addOnly(activity).performClick())
                        val first: CapturedPin =
                            CapturedPins.requests.get(CapturedPins.requests.size - 1)
                        settings(activity).textSize = 64
                        settings(activity).showDate = true
                        activity.preview.refresh()
                        activity.onWindowFocusChanged(false)
                        activity.onWindowFocusChanged(true)
                        assertTrue(addOnly(activity).performClick())
                        val second: CapturedPin =
                            CapturedPins.requests.get(CapturedPins.requests.size - 1)
                        assertNotEquals(first.callback, second.callback)
                        assertEquals(provider(world), first.provider)
                        assertFalse(Shadows.shadowOf(first.callback).isImmutable())
                        assertTrue(
                            (Shadows.shadowOf(first.callback).getFlags() and
                                PendingIntent.FLAG_ONE_SHOT) != 0,
                        )
                        val firstPreview =
                            requireNotNull(
                                first.extras.getParcelable<RemoteViews>(
                                    AppWidgetManager.EXTRA_APPWIDGET_PREVIEW,
                                ),
                            )
                        val secondPreview =
                            requireNotNull(
                                second.extras.getParcelable<RemoteViews>(
                                    AppWidgetManager.EXTRA_APPWIDGET_PREVIEW,
                                ),
                            )
                        assertNotNull(firstPreview)
                        assertNotNull(secondPreview)
                        val dateId: Int = if (world) R.id.city_date else R.id.clock_date_row
                        assertEquals(
                            View.GONE,
                            firstPreview
                                .apply(context, FrameLayout(context))
                                .findViewById<View>(dateId)
                                .getVisibility(),
                        )
                        assertEquals(
                            View.VISIBLE,
                            secondPreview
                                .apply(context, FrameLayout(context))
                                .findViewById<View>(dateId)
                                .getVisibility(),
                        )
                        val id: Int = if (world) 2252 else 2251
                        bind(id, world)
                        first.callback.send(
                            context,
                            0,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
                        )
                        idle()
                        assertEquals(61, WidgetSettingsStore.load(context, id, world).textSize)
                        assertFalse(WidgetSettingsStore.load(context, id, world).showDate)
                        assertEquals(
                            View.GONE,
                            Shadows.shadowOf(manager)
                                .getViewFor(id)
                                .findViewById<View>(dateId)
                                .getVisibility(),
                        )
                        assertEquals(
                            "A later draft stays independent from the accepted request",
                            64,
                            WidgetSettingsStore.loadUnplaced(context, world).textSize,
                        )
                        assertNoFallbackDefaults(world)
                        assertEquals(
                            "Completion must retain the accepted snapshot",
                            61,
                            WidgetSettingsStore.load(context, id, world).textSize,
                        )
                        assertCompletedAtHome(activity)
                        try {
                            first.callback.send()
                            fail("The accepted callback is one-shot")
                        } catch (expected: PendingIntent.CanceledException) {}
                    },
                )
        }
    }

    @Test
    @Throws(Exception::class)
    fun cancellingAPinLeavesTheDraftAndDoesNotPretendAWidgetExists() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    settings(activity).textSize = 62
                    activity.preview.refresh()
                    assertTrue(addOnly(activity).performClick())
                    assertEquals(1, CapturedPins.requests.size)
                    assertEquals(0, manager!!.getAppWidgetIds(provider(false)).size)
                    assertEquals(62, WidgetSettingsStore.loadUnplaced(context, false).textSize)
                    assertNoFallbackDefaults(false)
                    addOnly(activity)
                    controller.topActivityResumed(false).pause().resume().topActivityResumed(true)
                    assertFalse(activity.isFinishing)
                    assertNull(Shadows.shadowOf(activity).nextStartedActivity)
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun unsupportedPinExplainsTheSystemMenuWithoutChangingAnyWidgetId() {
        CapturedPins.supported = false
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    settings(activity).textSize = 60
                    activity.preview.refresh()
                    assertTrue(addOnly(activity).performClick())
                    assertTrue(ShadowToast.getTextOfLatestToast().contains("Widgets"))
                    assertTrue(CapturedPins.requests.isEmpty())
                    assertEquals(60, WidgetSettingsStore.loadUnplaced(context, false).textSize)
                    assertNoFallbackDefaults(false)
                    assertFalse(activity.isFinishing)
                    assertNull(Shadows.shadowOf(activity).nextStartedActivity)
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun callbackRejectsTheWrongProviderRatherThanOverwritingItsSettings() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    assertTrue(addOnly(controller.get()).performClick())
                    bind(2261, true)
                    CapturedPins.requests
                        .get(0)
                        .callback
                        .send(
                            context,
                            0,
                            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 2261),
                        )
                    idle()
                    assertFalse(WidgetSettingsStore.hasOwnSettings(context, 2261, false))
                    assertFalse(WidgetSettingsStore.hasOwnSettings(context, 2261, true))
                    assertFalse(controller.get().isFinishing)
                    assertNull(Shadows.shadowOf(controller.get()).nextStartedActivity)
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun aDelayedPinCallbackDoesNotReplaceAnExplicitEditOfTheAssignedWidget() {
        context
            .getSharedPreferences("MainActivity", 0)
            .edit()
            .putBoolean("world_tab", true)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { request ->
                    assertTrue(addOnly(request.get()).performClick())
                    val pending: CapturedPin = CapturedPins.requests.get(0)
                    bind(2271, true)
                    BaseWidgetProvider.updateWidget(context, 2271, true)
                    request.topActivityResumed(false).pause()
                    // World clocks open their concrete-ID editor when tapped, even before a delayed
                    // callback arrives.
                    assertTrue(
                        Shadows.shadowOf(manager)
                            .getViewFor(2271)
                            .findViewById<View>(R.id.widget_root)
                            .performClick(),
                    )
                    val direct: Intent =
                        Shadows.shadowOf(context as android.app.Application?)
                            .getNextStartedActivity()
                    assertNotNull(direct)
                    assertEquals(2271, direct.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
                    Robolectric.buildActivity(MainActivity::class.java, direct)
                        .setup()
                        .visible()
                        .use(
                            { editor ->
                                val activity: MainActivity = editor.get()
                                val root: View = layout(activity)
                                assertNull(root.findViewWithTag<View>("action-add-widget"))
                                assertTrue(
                                    root
                                        .findViewWithTag<View>("section-toggle:appearance")
                                        .performClick(),
                                )
                                val label: TextView? =
                                    text(layout(activity), "Date and time difference")
                                assertNotNull(label)
                                assertTrue((label!!.getParent() as View).performClick())
                                layout(activity)
                                assertFalse(WidgetSettingsStore.load(context, 2271, true).showDate)
                                pending.callback.send(
                                    context,
                                    0,
                                    Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 2271),
                                )
                                idle()
                                assertFalse(activity.isFinishing)
                                assertNull(Shadows.shadowOf(activity).nextStartedActivity)
                            },
                        )
                    assertFalse(
                        "A completed explicit edit is newer than the old pin snapshot",
                        WidgetSettingsStore.load(context, 2271, true).showDate,
                    )
                    assertEquals(
                        View.GONE,
                        Shadows.shadowOf(manager)
                            .getViewFor(2271)
                            .findViewById<View>(R.id.city_date)
                            .getVisibility(),
                    )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun anEarlyResumeCannotCommitALaterUnplacedDraftBeforeThePinCallback() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    settings(activity).textSize = 61
                    activity.preview.refresh()
                    assertTrue(addOnly(activity).performClick())
                    val request: CapturedPin = CapturedPins.requests.get(0)
                    settings(activity).textSize = 64
                    activity.preview.refresh()
                    bind(2281, false)
                    controller.topActivityResumed(false).pause().resume().topActivityResumed(true)
                    addOnly(activity)
                    val committedBeforeCallback: Boolean =
                        WidgetSettingsStore.hasOwnSettings(context, 2281, false)
                    request.callback.send(
                        context,
                        0,
                        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 2281),
                    )
                    idle()
                    assertEquals(
                        "Only the accepted pin snapshot belongs to this assigned ID",
                        61,
                        WidgetSettingsStore.load(context, 2281, false).textSize,
                    )
                    assertCompletedAtHome(activity)
                    assertFalse(
                        "Adoption must not commit an unrelated unplaced draft",
                        committedBeforeCallback,
                    )
                    assertEquals(64, WidgetSettingsStore.loadUnplaced(context, false).textSize)
                },
            )
    }

    private fun assertNoFallbackDefaults(world: Boolean) {
        val prefix: String = if (world) "world.0." else "clock.0."
        for (key: String in
            context.getSharedPreferences("noonlet_widgets", 0).getAll().keys) assertFalse(
            "No fallback key may be introduced: " + key,
            key.startsWith(prefix),
        )
    }

    private fun assertCompletedAtHome(activity: MainActivity) {
        PinRequestFixtures.assertReturnedToLauncher(activity)
    }

    private fun provider(world: Boolean): ComponentName {
        return ComponentName(
            context,
            if (world) WorldWidgetProvider::class.java else ClockWidgetProvider::class.java,
        )
    }

    private fun bind(id: Int, world: Boolean) {
        val info: AppWidgetProviderInfo = AppWidgetProviderInfo()
        info.provider = provider(world)
        info.initialLayout = if (world) R.layout.world_widget else R.layout.clock_widget
        Shadows.shadowOf(manager).addInstalledProvider(info)
        Shadows.shadowOf(manager).addBoundWidget(id, info)
        val options: Bundle = Bundle()
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 167)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 167)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 82)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 82)
        manager!!.updateAppWidgetOptions(id, options)
    }

    private fun addOnly(activity: MainActivity): TextView {
        val root: View = layout(activity)
        assertNull(root.findViewWithTag<View>("action-save-changes"))
        val add: TextView = root.findViewWithTag<TextView>("action-add-widget")
        assertNotNull(add)
        assertEquals("Add to home screen", add.getText().toString())
        return add
    }

    @Throws(Exception::class)
    private fun settings(activity: MainActivity): WidgetSettings {
        return activity.session.settings
    }


    private fun layout(activity: MainActivity): View {
        idle()
        val root: View =
            (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 400, 850)
        return root
    }

    private fun idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
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

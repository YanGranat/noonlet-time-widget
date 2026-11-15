package app.noonlet

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.lang.reflect.Field
import java.util.Locale
import java.util.TimeZone
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
import org.robolectric.shadows.ShadowAppWidgetManager
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FunctionalSaveTest {
    private lateinit var context: Context
    private var manager: AppWidgetManager? = null

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        manager = AppWidgetManager.getInstance(context)
        context.getSharedPreferences("noonlet_widgets", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("widget_drafts", Context.MODE_PRIVATE).edit().clear().commit()
        Locale.setDefault(Locale.ENGLISH)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        AppLocales.setLanguage(context, "en")
    }

    @Test
    @Throws(Exception::class)
    fun controlsApplyToTheSelectedWidgetAndLeaveFallbackWidgetsAlone() {
        WidgetSettingsStore.save(context, 701, false, WidgetSettings())
        bind(701, false)
        bind(702, false)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    assertEquals(701, target(a))
                    assertNotNull(text(layout(a), context.getString(R.string.app_name)))
                    assertNotNull(layout(a).findViewWithTag<View>("section-toggle:time"))
                    assertNotNull(layout(a).findViewWithTag<View>("action-save-changes"))
                    assertNull(layout(a).findViewWithTag<View>("action-add-widget"))
                    toggleDate(a)
                    assertFalse(WidgetSettingsStore.load(context, 701, false).showDate)
                    assertEquals(
                        View.GONE,
                        widget(701).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                    assertTrue(
                        "Other IDs retain their fallback defaults",
                        WidgetSettingsStore.load(context, 702, false).showDate,
                    )
                    assertTrue(WidgetSettingsStore.load(context, 0, false).showDate)
                    assertEquals(
                        View.VISIBLE,
                        widget(702).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                    assertFalse(a.isFinishing())
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun openingSpecificWidgetsSettingsRemembersItsIdWithoutAnotherSelector() {
        WidgetSettingsStore.save(context, 711, false, WidgetSettings())
        WidgetSettingsStore.save(context, 712, false, WidgetSettings())
        bind(711, false)
        bind(712, false)
        val configure: Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 712)
        Robolectric.buildActivity(MainActivity::class.java, configure)
            .setup()
            .visible()
            .use(
                { c ->
                    val root = layout(c.get())
                    assertNotNull(text(root, "Edit widget"))
                    assertNotNull(root.findViewWithTag<View>("section-toggle:time"))
                    assertNotNull(root.findViewWithTag<View>("action-save-changes"))
                },
            )
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    assertEquals(712, target(a))
                    assertNull(Shadows.shadowOf(a).getNextStartedActivity())
                    toggleDate(a)
                    assertTrue(WidgetSettingsStore.load(context, 711, false).showDate)
                    assertFalse(WidgetSettingsStore.load(context, 712, false).showDate)
                    assertEquals(
                        View.VISIBLE,
                        widget(711).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                    assertEquals(
                        View.GONE,
                        widget(712).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                    assertFalse(
                        context
                            .getSharedPreferences("noonlet_widgets", Context.MODE_PRIVATE)
                            .contains("clock.0.state"),
                    )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun eachTypesControlsApplyIndependentlyAcrossSwitchesAndRotation() {
        val first: WidgetSettings = WidgetSettings()
        first.timeOffsetX = 1
        WidgetSettingsStore.save(context, 721, false, first)
        val second: WidgetSettings = WidgetSettings()
        second.dateOffsetY = 2
        WidgetSettingsStore.save(context, 722, false, second)
        val remote: WidgetSettings = WidgetSettings.defaults(true)
        remote.opacity = 90
        WidgetSettingsStore.save(context, 821, true, remote)
        bind(721, false)
        bind(722, false)
        bind(821, true)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    var a: MainActivity = c.get()
                    slider(a, "time", "Time horizontal offset", 17, -40)
                    assertEquals(17, WidgetSettingsStore.load(context, 721, false).timeOffsetX)
                    tap(a, text(layout(a), "World clock"))
                    assertEquals(821, target(a))
                    slider(a, "appearance", "Opacity", 77, 0)
                    assertEquals(77, WidgetSettingsStore.load(context, 821, true).opacity)
                    c.recreate()
                    a = c.get()
                    layout(a)
                    assertEquals(821, target(a))
                    assertEquals(77, settings(a).opacity)
                    tap(a, text(layout(a), "Clock"))
                    assertEquals(721, target(a))
                    assertEquals(17, settings(a).timeOffsetX)
                    assertEquals(17, WidgetSettingsStore.load(context, 721, false).timeOffsetX)
                    assertEquals(2, WidgetSettingsStore.load(context, 722, false).dateOffsetY)
                    assertEquals(77, WidgetSettingsStore.load(context, 821, true).opacity)
                    assertEquals(0, WidgetSettingsStore.load(context, 0, false).timeOffsetX)
                    assertEquals(100, WidgetSettingsStore.load(context, 0, true).opacity)
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun anUnplacedDraftPersistsSeparatelyAndAddExplainsUnsupportedPlacement() {
        Shadows.shadowOf(manager).setRequestPinAppWidgetSupported(false)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    assertEquals(0, target(a))
                    toggleDate(a)
                    assertFalse(WidgetSettingsStore.loadUnplaced(context, false).showDate)
                    assertTrue(WidgetSettingsStore.load(context, 0, false).showDate)
                    assertNull(layout(a).findViewWithTag<View>("action-save-changes"))
                    val add: TextView = layout(a).findViewWithTag<TextView>("action-add-widget")
                    assertEquals("Add to home screen", add.getText().toString())
                    tap(a, add)
                    assertEquals(
                        0,
                        manager!!
                            .getAppWidgetIds(
                                ComponentName(context, ClockWidgetProvider::class.java),
                            )
                            .size,
                    )
                    assertTrue(ShadowToast.getTextOfLatestToast().contains("Widgets"))
                    assertFalse(a.isFinishing())
                    c.recreate()
                    assertFalse(settings(c.get()).showDate)
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun placedEditingOffersSaveWithoutCreatingAnotherWidgetOrWritingDefaults() {
        WidgetSettingsStore.save(context, 731, false, WidgetSettings())
        bind(731, false)
        bind(732, false)
        Shadows.shadowOf(manager).setRequestPinAppWidgetSupported(true)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    val root: View = layout(a)
                    assertNull(root.findViewWithTag<View>("action-add-widget"))
                    val save = root.findViewWithTag<TextView>("action-save-changes")
                    assertNotNull(save)
                    assertEquals("Save changes", save.text.toString())
                    assertEquals(1, (save.parent as ViewGroup).childCount)
                    toggleDate(a)
                    assertEquals(
                        2,
                        manager!!
                            .getAppWidgetIds(
                                ComponentName(context, ClockWidgetProvider::class.java),
                            )
                            .size,
                    )
                    assertFalse(WidgetSettingsStore.load(context, 731, false).showDate)
                    assertTrue(WidgetSettingsStore.load(context, 732, false).showDate)
                    assertTrue(WidgetSettingsStore.load(context, 0, false).showDate)
                    assertEquals(
                        View.GONE,
                        widget(731).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                    assertEquals(
                        View.VISIBLE,
                        widget(732).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun cancellingLauncherConfigurationDoesNotApplyItsDraft() {
        bind(741, false)
        val configure: Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 741)
        Robolectric.buildActivity(MainActivity::class.java, configure)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    toggleDate(a)
                    c.pause().resume()
                    layout(a)
                    assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(a).getResultCode())
                    assertEquals(
                        1,
                        manager!!
                            .getAppWidgetIds(
                                ComponentName(context, ClockWidgetProvider::class.java),
                            )
                            .size,
                    )
                    assertTrue(WidgetSettingsStore.load(context, 741, false).showDate)
                    assertFalse(settings(a).showDate)
                    assertFalse(WidgetSettingsStore.hasOwnSettings(context, 741, false))
                    assertNotNull(layout(a).findViewWithTag<View>("action-add-widget"))
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun removedTargetIsNotResurrectedOrReplacedSilentlyAtTheSameWidgetCount() {
        WidgetSettingsStore.save(context, 751, false, WidgetSettings())
        bind(751, false)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    toggleDate(a)
                    removeBoundWidget(751)
                    WidgetSettingsStore.delete(context, 751, false)
                    WidgetSettingsStore.save(context, 752, false, WidgetSettings())
                    bind(752, false)
                    c.pause().resume()
                    assertEquals(751, target(a))
                    val root: View = layout(a)
                    assertNotNull(root.findViewWithTag<View>("removed-widget-notice"))
                    assertNotNull(root.findViewWithTag<View>("preview-stage"))
                    assertNull(root.findViewWithTag<View>("section-content:date"))
                    assertNull(root.findViewWithTag<View>("action-save-changes"))
                    assertNull(root.findViewWithTag<View>("action-add-widget"))
                    assertFalse(
                        context
                            .getSharedPreferences("noonlet_widgets", Context.MODE_PRIVATE)
                            .contains("clock.751.state"),
                    )
                    assertTrue(WidgetSettingsStore.load(context, 752, false).showDate)
                    assertEquals(
                        View.VISIBLE,
                        widget(752).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                    tap(a, root.findViewWithTag<View>("recover-widget-target"))
                    assertEquals("Recovery requires an explicit choice", 752, target(a))
                    assertNull(layout(a).findViewWithTag<View>("removed-widget-notice"))
                    assertTrue(WidgetSettingsStore.load(context, 752, false).showDate)
                    toggleDate(a)
                    assertFalse(WidgetSettingsStore.load(context, 752, false).showDate)
                    assertEquals(
                        View.GONE,
                        widget(752).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                    assertFalse(WidgetSettingsStore.hasOwnSettings(context, 751, false))
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun aDeletedDirectEditorHasNoInertControlsAndExplicitlyOpensItsSurvivingSibling() {
        WidgetSettingsStore.save(context, 753, false, WidgetSettings())
        WidgetSettingsStore.save(context, 754, false, WidgetSettings())
        bind(753, false)
        bind(754, false)
        val direct: Intent =
            Intent(context, MainActivity::class.java)
                .setData(android.net.Uri.parse("noonlet://edit/false/753"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 753)
        Robolectric.buildActivity(MainActivity::class.java, direct)
            .setup()
            .visible()
            .use(
                { c ->
                    c.pause()
                    removeBoundWidget(753)
                    WidgetSettingsStore.delete(context, 753, false)
                    c.resume()
                    val root: View = layout(c.get())
                    assertNotNull(root.findViewWithTag<View>("removed-widget-notice"))
                    assertNotNull(root.findViewWithTag<View>("preview-stage"))
                    assertNull(root.findViewWithTag<View>("section-content:date"))
                    assertNull(root.findViewWithTag<View>("action-add-widget"))
                    assertNull(root.findViewWithTag<View>("action-save-changes"))
                    assertTrue(WidgetSettingsStore.load(context, 754, false).showDate)
                    tap(c.get(), root.findViewWithTag<View>("recover-widget-target"))
                    val recovered: Intent = Shadows.shadowOf(c.get()).getNextStartedActivity()
                    assertNotNull(recovered)
                    assertEquals(754, recovered.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
                    assertEquals("noonlet://edit/false/754", recovered.getDataString())
                    assertTrue(c.get().isFinishing())
                    Robolectric.buildActivity(MainActivity::class.java, recovered)
                        .setup()
                        .visible()
                        .use(
                            { next ->
                                toggleDate(next.get())
                                assertFalse(WidgetSettingsStore.load(context, 754, false).showDate)
                                assertEquals(
                                    View.GONE,
                                    widget(754)
                                        .findViewById<View>(R.id.clock_date_row)
                                        .getVisibility(),
                                )
                                assertFalse(WidgetSettingsStore.hasOwnSettings(context, 753, false))
                            },
                        )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun multipleRecoveryTargetsWaitForAnExplicitChoiceAndCancellationLeavesThemUnchanged() {
        for (id: Int in intArrayOf(755, 756, 757)) {
            WidgetSettingsStore.save(context, id, false, WidgetSettings())
            bind(id, false)
        }
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    c.pause()
                    removeBoundWidget(755)
                    WidgetSettingsStore.delete(context, 755, false)
                    c.resume()
                    tap(a, layout(a).findViewWithTag<View>("recover-widget-target"))
                    val first: android.app.AlertDialog =
                        org.robolectric.shadows.ShadowDialog.getLatestDialog()
                            as android.app.AlertDialog
                    assertEquals(2, first.getListView().getAdapter().getCount())
                    first.cancel()
                    assertEquals(755, target(a))
                    assertTrue(WidgetSettingsStore.load(context, 756, false).showDate)
                    assertTrue(WidgetSettingsStore.load(context, 757, false).showDate)
                    tap(a, layout(a).findViewWithTag<View>("recover-widget-target"))
                    val choice: android.app.AlertDialog =
                        org.robolectric.shadows.ShadowDialog.getLatestDialog()
                            as android.app.AlertDialog
                    val list: android.widget.ListView = choice.getListView()
                    assertTrue(
                        list.performItemClick(list.getAdapter().getView(1, null, list), 1, 1),
                    )
                    assertEquals(757, target(a))
                    toggleDate(a)
                    assertTrue(WidgetSettingsStore.load(context, 756, false).showDate)
                    assertFalse(WidgetSettingsStore.load(context, 757, false).showDate)
                    assertEquals(
                        View.VISIBLE,
                        widget(756).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                    assertEquals(
                        View.GONE,
                        widget(757).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                    assertFalse(WidgetSettingsStore.hasOwnSettings(context, 755, false))
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun placedConfigurationAppliesImmediatelyAndReturnsItsOwnResultId() {
        WidgetSettingsStore.save(context, 761, false, WidgetSettings())
        bind(761, false)
        val configure: Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 761)
        Robolectric.buildActivity(MainActivity::class.java, configure)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    toggleDate(a)
                    assertEquals(Activity.RESULT_OK, Shadows.shadowOf(a).getResultCode())
                    assertEquals(
                        761,
                        Shadows.shadowOf(a)
                            .getResultIntent()
                            .getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0),
                    )
                    assertFalse(a.isFinishing())
                    assertFalse(WidgetSettingsStore.load(context, 761, false).showDate)
                    assertEquals(
                        View.GONE,
                        widget(761).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                    assertNotNull(layout(a).findViewWithTag<View>("action-save-changes"))
                    assertNull(layout(a).findViewWithTag<View>("action-add-widget"))
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun legacyActivityStateAndAnAnonymousFirstBindingUseTheConcreteWidgetsSettings() {
        WidgetSettingsStore.save(context, 771, false, WidgetSettings())
        bind(771, false)
        val legacy: Bundle = Bundle()
        SettingsCodec.writeTo(WidgetSettings(), legacy)
        legacy.putBoolean("world", false)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup(legacy)
            .visible()
            .use({ c -> assertEquals(771, target(c.get())) })
        removeBoundWidget(771)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    assertEquals(0, target(a))
                    toggleDate(a)
                    val placed: WidgetSettings = WidgetSettings()
                    placed.dateOffsetX = 7
                    WidgetSettingsStore.save(context, 772, false, placed)
                    bind(772, false)
                    c.pause().resume()
                    layout(a)
                    assertEquals(772, target(a))
                    assertEquals(7, settings(a).dateOffsetX)
                    assertTrue(settings(a).showDate)
                    assertEquals(7, WidgetSettingsStore.load(context, 772, false).dateOffsetX)
                    assertEquals(
                        View.VISIBLE,
                        widget(772).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                    assertFalse(WidgetSettingsStore.loadUnplaced(context, false).showDate)
                    toggleDate(a)
                    assertFalse(WidgetSettingsStore.load(context, 772, false).showDate)
                    assertEquals(
                        View.GONE,
                        widget(772).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                    assertFalse(WidgetSettingsStore.loadUnplaced(context, false).showDate)
                    assertEquals(4, WidgetSettingsStore.loadUnplaced(context, false).dateOffsetX)
                    assertTrue(WidgetSettingsStore.load(context, 771, false).showDate)
                    assertTrue(WidgetSettingsStore.load(context, 0, false).showDate)
                    assertFalse(
                        context.getSharedPreferences("noonlet_widgets", 0).contains("clock.0.state"),
                    )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun anInactiveTypeAppearsThenItsControlsApplyToItsPlacedWidget() {
        WidgetSettingsStore.save(context, 781, false, WidgetSettings())
        bind(781, false)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    assertEquals(781, target(a))
                    val remote: WidgetSettings = WidgetSettings.defaults(true)
                    remote.showDate = true
                    WidgetSettingsStore.save(context, 881, true, remote)
                    bind(881, true)
                    c.pause().resume()
                    tap(a, text(layout(a), "World clock"))
                    assertEquals(881, target(a))
                    val root: View = layout(a)
                    if (
                        root.findViewWithTag<View>("section-content:appearance").getVisibility() !=
                            View.VISIBLE
                    )
                        tap(a, root.findViewWithTag<View>("section-toggle:appearance"))
                    val date: TextView? = text(layout(a), "Date and time difference")
                    assertNotNull(date)
                    tap(a, date!!.getParent() as View)
                    assertFalse(WidgetSettingsStore.load(context, 881, true).showDate)
                    assertEquals(
                        View.GONE,
                        widget(881).findViewById<View>(R.id.city_date).getVisibility(),
                    )
                    assertTrue(WidgetSettingsStore.load(context, 781, false).showDate)
                    assertTrue(WidgetSettingsStore.load(context, 0, true).showDate)
                    assertFalse(
                        context
                            .getSharedPreferences("noonlet_widgets", Context.MODE_PRIVATE)
                            .contains("world.0.state"),
                    )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun firstBindingWhileResumedAdoptsTheConcreteSettingsAndRetainsTheUnplacedDraft() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    assertEquals(0, target(a))
                    toggleDate(a)
                    WidgetSettingsStore.save(context, 782, false, WidgetSettings())
                    bind(782, false)
                    // No pause/resume: a floating pin confirmation or another host can finish while
                    // this editor is resumed.
                    tap(a, layout(a).findViewWithTag<View>("action-add-widget"))
                    assertEquals(782, target(a))
                    assertTrue(settings(a).showDate)
                    assertTrue(WidgetSettingsStore.load(context, 782, false).showDate)
                    assertEquals(
                        View.VISIBLE,
                        widget(782).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                    assertFalse(WidgetSettingsStore.loadUnplaced(context, false).showDate)
                    slider(a, "date", "Date horizontal offset", 9, -40)
                    toggleDate(a)
                    assertEquals(9, WidgetSettingsStore.load(context, 782, false).dateOffsetX)
                    assertFalse(WidgetSettingsStore.load(context, 782, false).showDate)
                    assertEquals(
                        View.GONE,
                        widget(782).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                    assertFalse(WidgetSettingsStore.loadUnplaced(context, false).showDate)
                    assertEquals(4, WidgetSettingsStore.loadUnplaced(context, false).dateOffsetX)
                    assertTrue(WidgetSettingsStore.load(context, 0, false).showDate)
                    assertFalse(
                        context
                            .getSharedPreferences("noonlet_widgets", Context.MODE_PRIVATE)
                            .contains("clock.0.state"),
                    )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun returningToAnExistingEditorUsesTheExplicitlyOpenedWidgetForItsType() {
        WidgetSettingsStore.save(context, 791, false, WidgetSettings())
        WidgetSettingsStore.save(context, 792, false, WidgetSettings())
        WidgetSettingsStore.save(context, 891, true, WidgetSettings())
        WidgetSettingsStore.save(context, 892, true, WidgetSettings())
        bind(791, false)
        bind(792, false)
        bind(891, true)
        bind(892, true)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { home ->
                    val a: MainActivity = home.get()
                    assertEquals(791, target(a))
                    home.pause()
                    for (id: Int in intArrayOf(792, 892)) {
                        val configure: Intent =
                            Intent(context, MainActivity::class.java)
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        Robolectric.buildActivity(MainActivity::class.java, configure)
                            .setup()
                            .visible()
                            .use(
                                { other ->
                                    assertEquals(
                                        Activity.RESULT_CANCELED,
                                        Shadows.shadowOf(other.get()).getResultCode(),
                                    )
                                },
                            )
                    }
                    home.resume()
                    assertEquals(792, target(a))
                    toggleDate(a)
                    assertFalse(WidgetSettingsStore.load(context, 792, false).showDate)
                    assertTrue(WidgetSettingsStore.load(context, 791, false).showDate)
                    tap(a, text(layout(a), "World clock"))
                    assertEquals(892, target(a))
                    assertTrue(WidgetSettingsStore.load(context, 891, true).showDate)
                    assertTrue(WidgetSettingsStore.load(context, 892, true).showDate)
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun firstBindingThenResumeLoadsItsOwnSettingsAndKeepsThePrototypeIndependent() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { home ->
                    val a: MainActivity = home.get()
                    assertEquals(0, target(a))
                    slider(a, "date", "Date horizontal offset", 17, -40)
                    toggleDate(a)
                    val placed: WidgetSettings = WidgetSettings()
                    placed.dateOffsetX = 5
                    WidgetSettingsStore.save(context, 793, false, placed)
                    bind(793, false)
                    home.pause().resume()
                    layout(a)
                    assertEquals(793, target(a))
                    assertEquals(5, settings(a).dateOffsetX)
                    assertTrue(settings(a).showDate)
                    assertEquals(5, WidgetSettingsStore.load(context, 793, false).dateOffsetX)
                    assertEquals(
                        View.VISIBLE,
                        widget(793).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                    assertEquals(17, WidgetSettingsStore.loadUnplaced(context, false).dateOffsetX)
                    assertFalse(WidgetSettingsStore.loadUnplaced(context, false).showDate)
                    slider(a, "date", "Date horizontal offset", 9, -40)
                    toggleDate(a)
                    assertEquals(9, WidgetSettingsStore.load(context, 793, false).dateOffsetX)
                    assertFalse(WidgetSettingsStore.load(context, 793, false).showDate)
                    assertEquals(
                        View.GONE,
                        widget(793).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                    assertEquals(17, WidgetSettingsStore.loadUnplaced(context, false).dateOffsetX)
                    assertFalse(WidgetSettingsStore.loadUnplaced(context, false).showDate)
                    assertEquals(4, WidgetSettingsStore.load(context, 0, false).dateOffsetX)
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun aSeparateConfigurationAppliesAndReloadsTheSameIdInTheReturningEditor() {
        val original: WidgetSettings = WidgetSettings()
        original.dateOffsetX = 3
        WidgetSettingsStore.save(context, 794, false, original)
        bind(794, false)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { home ->
                    val a: MainActivity = home.get()
                    assertEquals(794, target(a))
                    slider(a, "date", "Date horizontal offset", 17, -40)
                    home.pause()
                    val configure: Intent =
                        Intent(context, MainActivity::class.java)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 794)
                    Robolectric.buildActivity(MainActivity::class.java, configure)
                        .setup()
                        .visible()
                        .use(
                            { other ->
                                slider(other.get(), "date", "Date horizontal offset", 9, -40)
                                toggleDate(other.get())
                                assertEquals(
                                    Activity.RESULT_OK,
                                    Shadows.shadowOf(other.get()).getResultCode(),
                                )
                            },
                        )
                    home.resume()
                    layout(a)
                    assertEquals(794, target(a))
                    assertEquals(9, settings(a).dateOffsetX)
                    assertFalse(settings(a).showDate)
                    assertEquals(9, WidgetSettingsStore.load(context, 794, false).dateOffsetX)
                    assertEquals(
                        View.GONE,
                        widget(794).findViewById<View>(R.id.clock_date_row).getVisibility(),
                    )
                    assertEquals(4, WidgetSettingsStore.load(context, 0, false).dateOffsetX)
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun recreationReloadsExternalSettingsAndRetainsSubsequentAutomaticChanges() {
        val original: WidgetSettings = WidgetSettings()
        original.dateOffsetX = 3
        WidgetSettingsStore.save(context, 795, false, original)
        bind(795, false)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { home ->
                    slider(home.get(), "date", "Date horizontal offset", 17, -40)
                    val external: WidgetSettings = WidgetSettingsStore.load(context, 795, false)
                    external.dateOffsetX = 9
                    WidgetSettingsStore.save(context, 795, false, external)
                    home.recreate()
                    var a: MainActivity = home.get()
                    layout(a)
                    assertEquals(795, target(a))
                    assertEquals(9, settings(a).dateOffsetX)
                    slider(a, "date", "Date horizontal offset", 13, -40)
                    home.recreate()
                    a = home.get()
                    layout(a)
                    assertEquals(
                        "An automatically applied change survives rotation",
                        13,
                        settings(a).dateOffsetX,
                    )
                    assertEquals(13, WidgetSettingsStore.load(context, 795, false).dateOffsetX)
                    assertEquals(4, WidgetSettingsStore.load(context, 0, false).dateOffsetX)
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun anInactiveTypesCachedSettingsYieldToNewPersistedSettings() {
        val clock: WidgetSettings = WidgetSettings()
        clock.dateOffsetX = 3
        WidgetSettingsStore.save(context, 796, false, clock)
        bind(796, false)
        val remote: WidgetSettings = WidgetSettings.defaults(true)
        remote.opacity = 90
        WidgetSettingsStore.save(context, 896, true, remote)
        bind(896, true)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { home ->
                    val a: MainActivity = home.get()
                    slider(a, "date", "Date horizontal offset", 17, -40)
                    tap(a, text(layout(a), "World clock"))
                    slider(a, "appearance", "Opacity", 77, 0)
                    tap(a, text(layout(a), "Clock"))
                    val external: WidgetSettings = WidgetSettingsStore.load(context, 896, true)
                    external.opacity = 40
                    WidgetSettingsStore.save(context, 896, true, external)
                    tap(a, text(layout(a), "World clock"))
                    assertEquals(896, target(a))
                    assertEquals(40, settings(a).opacity)
                    tap(a, text(layout(a), "Clock"))
                    assertEquals(17, settings(a).dateOffsetX)
                    assertEquals(17, WidgetSettingsStore.load(context, 796, false).dateOffsetX)
                    assertEquals(40, WidgetSettingsStore.load(context, 896, true).opacity)
                    assertEquals(100, WidgetSettingsStore.load(context, 0, true).opacity)
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun firstBindingInAnInactiveTypeLoadsTheConcreteSettingsAndRetainsItsPrototype() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { home ->
                    val a: MainActivity = home.get()
                    tap(a, text(layout(a), "World clock"))
                    assertEquals(0, target(a))
                    slider(a, "appearance", "Opacity", 77, 0)
                    tap(a, text(layout(a), "Clock"))
                    val remote: WidgetSettings = WidgetSettings.defaults(true)
                    remote.opacity = 90
                    WidgetSettingsStore.save(context, 897, true, remote)
                    bind(897, true)
                    home.pause().resume()
                    tap(a, text(layout(a), "World clock"))
                    assertEquals(897, target(a))
                    assertEquals(90, settings(a).opacity)
                    assertEquals(90, WidgetSettingsStore.load(context, 897, true).opacity)
                    assertEquals(77, WidgetSettingsStore.loadUnplaced(context, true).opacity)
                    slider(a, "appearance", "Opacity", 64, 0)
                    assertEquals(64, WidgetSettingsStore.load(context, 897, true).opacity)
                    assertEquals(77, WidgetSettingsStore.loadUnplaced(context, true).opacity)
                    assertEquals(100, WidgetSettingsStore.load(context, 0, true).opacity)
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun anExplicitNewConfigurationWinsOverAnInactiveUnplacedDraft() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { home ->
                    val a: MainActivity = home.get()
                    tap(a, text(layout(a), "World clock"))
                    slider(a, "appearance", "Opacity", 77, 0)
                    tap(a, text(layout(a), "Clock"))
                    val remote: WidgetSettings = WidgetSettings.defaults(true)
                    remote.opacity = 90
                    WidgetSettingsStore.save(context, 898, true, remote)
                    bind(898, true)
                    home.pause()
                    val configure: Intent =
                        Intent(context, MainActivity::class.java)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 898)
                    Robolectric.buildActivity(MainActivity::class.java, configure)
                        .setup()
                        .visible()
                        .use({ other -> slider(other.get(), "appearance", "Opacity", 40, 0) })
                    home.resume()
                    tap(a, text(layout(a), "World clock"))
                    assertEquals(898, target(a))
                    assertEquals(40, settings(a).opacity)
                    assertEquals(40, WidgetSettingsStore.load(context, 898, true).opacity)
                    assertEquals(100, WidgetSettingsStore.load(context, 0, true).opacity)
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
        val options: Bundle = Bundle()
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 170)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 170)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        manager!!.updateAppWidgetOptions(id, options)
        BaseWidgetProvider.updateWidget(context, id, world)
    }

    private fun widget(id: Int): View {
        return Shadows.shadowOf(manager).getViewFor(id)
    }

    @Throws(Exception::class)
    private fun removeBoundWidget(id: Int) {
        // Robolectric exposes binding but no removal API; reset() only resets static defaults.
        val field: Field = ShadowAppWidgetManager::class.java.getDeclaredField("widgetInfos")
        field.setAccessible(true)
        (field.get(Shadows.shadowOf(manager)) as MutableMap<*, *>).remove(id)
    }

    @Throws(Exception::class)
    private fun target(a: MainActivity): Int {
        return a.session.homeTargetId
    }

    @Throws(Exception::class)
    private fun settings(a: MainActivity): WidgetSettings {
        return a.session.settings
    }

    private fun toggleDate(a: MainActivity) {
        val root: View = layout(a)
        val body: View = root.findViewWithTag<View>("section-content:date")
        if (body.getVisibility() != View.VISIBLE)
            tap(a, root.findViewWithTag<View>("section-toggle:date"))
        val label: TextView? = text(layout(a), "Show date")
        assertNotNull(label)
        tap(a, label!!.getParent() as View)
    }

    private fun slider(a: MainActivity, section: String, label: String, value: Int, minimum: Int) {
        val root: View = layout(a)
        val body: View = root.findViewWithTag<View>("section-content:" + section)
        assertNotNull(body)
        if (body.getVisibility() != View.VISIBLE)
            tap(a, root.findViewWithTag<View>("section-toggle:" + section))
        val bar: SeekBar? = slider(layout(a), label)
        assertNotNull(label, bar)
        bar!!.requestRectangleOnScreen(Rect(0, 0, bar!!.getWidth(), bar!!.getHeight()), true)
        idle()
        measure(layout(a))
        val visible: Rect = Rect()
        assertTrue("The slider must be reachable: " + label, bar!!.getGlobalVisibleRect(visible))
        val args: Bundle = Bundle()
        args.putFloat(
            AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
            value.toFloat(),
        )
        assertTrue(
            "The real control must accept progress: " + label,
            bar!!.performAccessibilityAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),
                args,
            ),
        )
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        layout(a)
        assertEquals(value - minimum, bar!!.getProgress())
    }

    private fun slider(view: View, label: String): SeekBar? {
        if (view is SeekBar && label.contentEquals(view.getContentDescription()))
            return view as SeekBar
        if (view is ViewGroup)
            for (i: Int in 0 until (view as ViewGroup).getChildCount()) {
                val found: SeekBar? = slider((view as ViewGroup).getChildAt(i), label)
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
        assertTrue("Actions must be visible", target!!.isShown())
        val bounds: Rect = Rect()
        target!!.getDrawingRect(bounds)
        (root as ViewGroup).offsetDescendantRectToMyCoords(target, bounds)
        assertTrue(
            "Tap must be inside the editor",
            bounds.centerY() >= 0 && bounds.centerY() < root.getHeight(),
        )
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

    private fun text(v: View, value: String): TextView? {
        if (v is TextView && value.contentEquals((v as TextView).getText())) return v as TextView
        if (v is ViewGroup)
            for (i: Int in 0 until (v as ViewGroup).getChildCount()) {
                val found: TextView? = text((v as ViewGroup).getChildAt(i), value)
                if (found != null) return found
            }
        return null
    }

    private fun idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
}

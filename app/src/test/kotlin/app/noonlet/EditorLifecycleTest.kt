package app.noonlet

import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Looper
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
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
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorLifecycleTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        for (file: String in
            arrayOf<String>("noonlet_widgets", "MainActivity", "widget_drafts")) context
            .getSharedPreferences(file, 0)
            .edit()
            .clear()
            .commit()
        AppLocales.setLanguage(context, "en")
    }

    @Test
    fun launcherEntryStartsWithClockAndRecreationRetainsTheCurrentEditor() {
        EditorPreferences(context).selectTarget(0, true)
        val launch = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
        Robolectric.buildActivity(MainActivity::class.java, launch).setup().visible().use { controller ->
            assertFalse(controller.get().session.world)
            assertTrue(findText(layout(controller.get()), "World clock")!!.performClick())
            assertTrue(controller.get().session.world)
            controller.recreate()
            assertTrue(controller.get().session.world)
            controller.newIntent(launch)
            assertFalse(controller.get().session.world)
            assertTrue(findText(layout(controller.get()), "World clock")!!.performClick())
            android.app.Instrumentation().callActivityOnUserLeaving(controller.get())
            controller.pause().stop().restart().start().resume().visible()
            assertFalse(controller.get().session.world)
            assertTrue(findText(layout(controller.get()), "World clock")!!.performClick())
            controller.get().findViewById<View>(android.R.id.content)
                .findViewWithTag<View>("settings-trigger").performClick()
            val settingsIntent = Shadows.shadowOf(controller.get()).nextStartedActivity
            assertTrue(settingsIntent.flags and Intent.FLAG_ACTIVITY_NO_USER_ACTION != 0)
            controller.pause().stop().restart().start().resume().visible()
            assertTrue(controller.get().session.world)
        }
        Robolectric.buildActivity(MainActivity::class.java, EditorIntents.create(context, true))
            .setup().visible().use { assertTrue(it.get().session.world) }
    }

    @Test
    @Throws(Exception::class)
    fun aDialogFromThePreviousTargetIsDismissedAndCannotEditTheNewTarget() {
        val first: WidgetSettings = WidgetSettings.defaults(true)
        first.cities =
            arrayOf<String>("America/Los_Angeles", "Europe/London", "Asia/Riyadh", "Asia/Tokyo")
        WidgetSettingsStore.save(context, 2311, true, first)
        val second: WidgetSettings = WidgetSettings.defaults(true)
        second.cities = arrayOf<String>("Asia/Kathmandu")
        WidgetSettingsStore.save(context, 2312, true, second)
        bind(2311, true)
        bind(2312, true)
        context
            .getSharedPreferences("MainActivity", 0)
            .edit()
            .putBoolean("world_tab", true)
            .putInt("world_target", 2311)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { home ->
                    val activity: MainActivity = home.get()
                    val root: View = layout(activity)
                    assertTrue(root.findViewWithTag<View>("section-toggle:cities").performClick())
                    val place = findText(layout(activity).findViewWithTag("section-content:cities"),
                        CityCatalog.name(context, first.cities[3], AppLocales.appLocale(context)))!!
                    assertTrue((place.parent as View).performClick())
                    val stale: AlertDialog = ShadowAlertDialog.getLatestAlertDialog()
                    assertTrue(stale.isShowing())
                    home.pause()
                    assertTrue(
                        "Ordinary backgrounding preserves the open dialog",
                        stale.isShowing(),
                    )
                    val other: Intent =
                        Intent(context, MainActivity::class.java)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 2312)
                    Robolectric.buildActivity(MainActivity::class.java, other)
                        .setup()
                        .visible()
                        .use({ editor -> })
                    home.resume()
                    layout(activity)
                    assertFalse("A dialog must not follow a changed target", stale.isShowing())
                    // A click already queued by ListView must also be harmless after dismissal.
                    findFirst(stale.window!!.decorView, ListView::class.java)!!.performItemClick(null, 1, 1)
                    assertArrayEquals(second.cities, settings(activity).cities)
                    assertArrayEquals(
                        first.cities,
                        WidgetSettingsStore.load(context, 2311, true).cities,
                    )
                    assertArrayEquals(
                        second.cities,
                        WidgetSettingsStore.load(context, 2312, true).cities,
                    )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun aDirectEditorRebuildsItsControlsAfterExternalSettingsChange() {
        WidgetSettingsStore.save(context, 2321, false, WidgetSettings())
        bind(2321, false)
        val request: Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 2321)
        Robolectric.buildActivity(MainActivity::class.java, request)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    assertTrue(
                        layout(activity)
                            .findViewWithTag<View>("section-toggle:date")
                            .performClick(),
                    )
                    assertTrue(findSwitch(layout(activity), "Show date")!!.isChecked())
                    controller.pause()
                    val changed: WidgetSettings = WidgetSettingsStore.load(context, 2321, false)
                    changed.showDate = false
                    WidgetSettingsStore.save(context, 2321, false, changed)
                    controller.resume()
                    assertFalse(settings(activity).showDate)
                    assertFalse(
                        "The control must match the externally saved value",
                        findSwitch(layout(activity), "Show date")!!.isChecked(),
                    )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun aQueuedSliderRefreshDoesNotReinflateTheHiddenEditorAfterPause() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    val original: View = layout(activity).findViewById<View>(R.id.clock_time)
                    settings(activity).textSize = 63
                    activity.preview.queueFull()
                    controller.pause()
                    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
                    assertEquals(63, WidgetSettingsStore.loadUnplaced(context, false).textSize)
                    assertSame(
                        "Pause flushes settings without rebuilding an invisible preview",
                        original,
                        activity
                            .findViewById<View>(android.R.id.content)
                            .findViewById<View>(R.id.clock_time),
                    )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun renderingAndPinPreviewsDoNotWriteTemporaryWidgetRecords() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    activity.preview.refresh()
                    assertTrue(
                        layout(activity).findViewWithTag<View>("action-add-widget").performClick(),
                    )
                    for (key: String in
                        context.getSharedPreferences("noonlet_widgets", 0).getAll().keys) assertFalse(
                        "Preview has no persistent state: " + key,
                        key.startsWith("clock.-") || key.startsWith("world.-"),
                    )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun aFullRenderConsumesAnAlreadyQueuedSliderRefresh() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    settings(activity).textSize = 63
                    activity.preview.queueFull()
                    invoke(activity, "render")
                    val fresh: View = layout(activity).findViewById<View>(R.id.clock_time)
                    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
                    assertSame(
                        "The full render already displayed the pending value",
                        fresh,
                        activity
                            .findViewById<View>(android.R.id.content)
                            .findViewById<View>(R.id.clock_time),
                    )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun headersCollapseSectionsWithoutARedundantFooterAndSurviveARender() {
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
                        var root: View = layout(activity)
                        assertNull(
                            "The section header is the single disclosure control",
                            findText(root, "Collapse section"),
                        )
                        val keys: Array<String> =
                            if (world)
                                arrayOf<String>(
                                    "cities",
                                    "appearance",
                                    "foreground",
                                    "worldBackground",
                                    "actions",
                                )
                            else
                                arrayOf<String>(
                                    "time",
                                    "date",
                                    "appearance",
                                    "padding",
                                    "alarm",
                                    "actions",
                                )
                        for (key: String in keys) {
                            val heading: View = root.findViewWithTag<View>("section-toggle:" + key)
                            val body: View = root.findViewWithTag<View>("section-content:" + key)
                            assertNotNull(heading)
                            assertNotNull(body)
                            assertTrue(heading.performClick())
                            assertEquals(View.VISIBLE, body.getVisibility())
                            assertTrue(heading.performClick())
                            assertEquals(View.GONE, body.getVisibility())
                        }
                        val key: String = if (world) "cities" else "date"
                        assertTrue(
                            root.findViewWithTag<View>("section-toggle:" + key).performClick(),
                        )
                        invoke(activity, "render")
                        Shadows.shadowOf(Looper.getMainLooper()).idle()
                        root = layout(activity)
                        assertEquals(
                            "Rerender preserves the open section",
                            View.VISIBLE,
                            root.findViewWithTag<View>("section-content:" + key).getVisibility(),
                        )
                        assertTrue(
                            root.findViewWithTag<View>("section-toggle:" + key).performClick(),
                        )
                        assertEquals(
                            View.GONE,
                            root.findViewWithTag<View>("section-content:" + key).getVisibility(),
                        )
                        assertNull(findText(root, "Collapse section"))
                    },
                )
        }
    }

    @Test
    @Throws(Exception::class)
    fun aColdPlacePickerShowsLoadingAndUsesTheLatestQueryWhenReady() {
        CityCatalog.prepare(context, AppLocales.resourceLocale(context)).get(30, TimeUnit.SECONDS)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    val pending: CompletableFuture<List<Place>> = CompletableFuture()
                    setCatalog(activity, pending)
                    val dialog: AlertDialog = openPlacePicker(activity)
                    val body: View = requireNotNull(dialog.getWindow()).getDecorView()
                    val results: ListView? = findFirst(body, ListView::class.java)
                    assertNotNull(findText(body, "Loading places…"))
                    assertEquals(0, results!!.getAdapter().getCount())
                    findFirst(body, EditText::class.java)!!.setText("tokyo")
                    findFirst(body, EditText::class.java)!!.setText("san francisco")
                    assertFalse(pending.isDone())
                    pending.complete(CityCatalog.all())
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(1, results!!.getAdapter().getCount())
                    assertEquals(
                        CityCatalog.SAN_FRANCISCO,
                        (results!!.getAdapter().getItem(0) as Place).id,
                    )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun aDismissedPlacePickerDoesNotRepopulateAfterTheSharedCatalogFinishes() {
        CityCatalog.prepare(context, AppLocales.resourceLocale(context)).get(30, TimeUnit.SECONDS)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    val pending: CompletableFuture<List<Place>> = CompletableFuture()
                    setCatalog(activity, pending)
                    val dialog: AlertDialog = openPlacePicker(activity)
                    val results: ListView? =
                        findFirst(
                            requireNotNull(dialog.getWindow()).getDecorView(),
                            ListView::class.java,
                        )
                    dialog.dismiss()
                    assertFalse(
                        "Closing one picker must not cancel shared catalog work",
                        pending.isCancelled(),
                    )
                    pending.complete(CityCatalog.all())
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(0, results!!.getAdapter().getCount())
                    assertFalse(dialog.isShowing())
                },
            )
    }

    @Test
    fun placeSearchFitsShortResultsAndRetainsSpaceForAnEmptyMessage() {
        CityCatalog.prepare(context, AppLocales.resourceLocale(context)).get(30, TimeUnit.SECONDS)
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val dialog = openPlacePicker(controller.get())
            val root = requireNotNull(dialog.window).decorView
            val results = requireNotNull(findFirst(root, ListView::class.java))
            val search = requireNotNull(findFirst(root, EditText::class.java))
            val frame = results.parent as View
            DialogTestFixtures.layout(root, 400, 850)
            val fullHeight = frame.height
            assertTrue(fullHeight >= 250)
            search.setText("london")
            DialogTestFixtures.layout(root, 400, 850)
            assertTrue(results.count in 1..3)
            assertTrue(frame.height < fullHeight)
            assertTrue(frame.height - results.getChildAt(results.childCount - 1).bottom <= 3)
            search.setText("zzzzzzzz")
            DialogTestFixtures.layout(root, 400, 850)
            assertEquals(0, results.count)
            assertEquals(View.VISIBLE, results.emptyView.visibility)
            assertTrue(frame.height in 48 until fullHeight)
            search.setText("")
            DialogTestFixtures.layout(root, 400, 850)
            assertEquals(fullHeight, frame.height)
            dialog.dismiss()
        }
    }

    @Test
    fun placeSearchRowsAreActionsAndCancelPreservesTheTimeZone() {
        CityCatalog.prepare(context, AppLocales.resourceLocale(context)).get(30, TimeUnit.SECONDS)
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val dialog = openPlacePicker(activity)
            val root = requireNotNull(dialog.window).decorView as ViewGroup
            val results = requireNotNull(findFirst(root, ListView::class.java))
            val search = requireNotNull(findFirst(root, EditText::class.java))
            val session = activity.session
            val original = session.settings.zoneId
            DialogTestFixtures.layout(root, 360, 680)

            val deviceTime = results.getChildAt(0) as ViewGroup
            val node = deviceTime.createAccessibilityNodeInfo()
            assertEquals("android.widget.Button", node.className.toString())
            assertTrue(node.isClickable)
            assertFalse(node.isCheckable)
            val deviceName = requireNotNull(findFirst(deviceTime, TextView::class.java))
            assertTrue(deviceName.compoundDrawablesRelative.all { it == null })
            fun bounds(view: View) = Rect(0, 0, view.width, view.height).also {
                root.offsetDescendantRectToMyCoords(view, it)
            }
            val searchDivider = root.findViewWithTag<View>("place-search-divider")
            assertEquals(bounds(search).bottom + 8, bounds(searchDivider).top)
            assertEquals(bounds(searchDivider).bottom, bounds(results).top)

            search.setText("tokyo")
            DialogTestFixtures.layout(root, 360, 680)
            val cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
            assertEquals(View.VISIBLE, cancel.visibility)
            assertEquals("Cancel", cancel.text.toString())
            assertTrue(bounds(cancel).top >= bounds(results).bottom)
            assertTrue(cancel.performClick())
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertEquals(original, session.settings.zoneId)
        }
    }

    @Test
    @Throws(Exception::class)
    fun aFailedPlaceCatalogHasAnErrorStateInsteadOfAnEmptySearchResult() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    val pending: CompletableFuture<List<Place>> = CompletableFuture()
                    setCatalog(activity, pending)
                    val dialog: AlertDialog = openPlacePicker(activity)
                    pending.completeExceptionally(IllegalStateException("fixture catalog failure"))
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    val body: View = requireNotNull(dialog.getWindow()).getDecorView()
                    assertNotNull(findText(body, "The place list could not be loaded"))
                    assertNull(findText(body, "No places found"))
                    assertEquals(0, findFirst(body, ListView::class.java)!!.getAdapter().getCount())
                },
            )
    }

    @Throws(Exception::class)
    private fun setCatalog(
        activity: MainActivity,
        future: CompletableFuture<List<Place>>,
    ) {
        activity.placePicker.catalog = future
    }

    private fun openPlacePicker(activity: MainActivity): AlertDialog {
        val root: View = layout(activity)
        assertTrue(root.findViewWithTag<View>("section-toggle:time").performClick())
        val label: TextView? = findText(layout(activity), "Time zone")
        assertNotNull(label)
        assertTrue((label!!.getParent().getParent() as View).performClick())
        val dialog: AlertDialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        assertTrue(dialog.isShowing())
        return dialog
    }

    private fun <T : View> findFirst(root: View, type: Class<T>): T? {
        if (type.isInstance(root)) return type.cast(root)
        if (root is ViewGroup)
            for (i: Int in 0 until (root as ViewGroup).getChildCount()) {
                val found: T? = findFirst((root as ViewGroup).getChildAt(i), type)
                if (found != null) return found
            }
        return null
    }

    private fun findText(root: View, value: String): android.widget.TextView? {
        if (
            root is android.widget.TextView &&
                value.contentEquals((root as android.widget.TextView).getText())
        )
            return root as android.widget.TextView
        if (root is ViewGroup)
            for (i: Int in 0 until (root as ViewGroup).getChildCount()) {
                val found: android.widget.TextView? =
                    findText((root as ViewGroup).getChildAt(i), value)
                if (found != null) return found
            }
        return null
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
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val root: View =
            (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 400, 850)
        return root
    }

    @Throws(Exception::class)
    private fun settings(activity: MainActivity): WidgetSettings {
        return activity.session.settings
    }

    @Throws(Exception::class)
    private fun invoke(activity: MainActivity, name: String) {
        val method = MainActivity::class.java.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(activity)
    }

    private fun findSwitch(root: View, description: String): Switch? {
        if (root is Switch && description.contentEquals(root.getContentDescription()))
            return root as Switch
        if (root is ViewGroup)
            for (i: Int in 0 until (root as ViewGroup).getChildCount()) {
                val found: Switch? = findSwitch((root as ViewGroup).getChildAt(i), description)
                if (found != null) return found
            }
        return null
    }
}

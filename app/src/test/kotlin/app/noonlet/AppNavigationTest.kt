package app.noonlet

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
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
@Config(sdk = [26, 34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppNavigationTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        for (name in listOf("noonlet_widgets", "MainActivity", "widget_drafts", "widget_configuration",
            "noonlet_app")) context.getSharedPreferences(name, 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
    }

    @Test
    fun creationRetainsAnIndependentDraftAcrossRecreationForBothWidgetTypes() {
        for (world in listOf(false, true)) {
            val id = if (world) 9202 else 9201
            bind(id, world)
            val existing = WidgetSettings.defaults(world).apply { textSize = 41; dateOffsetX = 12 }
            WidgetSettingsStore.save(context, id, world, existing)
            EditorPreferences(context).selectTarget(id, world)
            val intent = EditorIntents.create(context, world)
            val session = EditorSession(context, intent, null)
            assertTrue(session.creatingNew)
            assertEquals(EditorSession.Target(0, world), session.target)
            session.settings.textSize = 59
            session.settings.dateOffsetX = 9
            assertEquals(EditorSession.ApplyResult.DraftSaved, session.apply())
            assertEquals(EditorSession.Transition.UNCHANGED, session.prepareAdd {
                assertEquals(EditorSession.ApplyResult.DraftSaved, it)
            })
            assertEquals(EditorSession.Transition.UNCHANGED, session.reconcile { fail("No host may be adopted") })
            session.selectTarget(id, world) { fail("Creation must not switch to an existing widget") }
            assertFalse(session.switchTab(!world) { fail("Creation keeps its chosen widget type") })
            val state = Bundle()
            session.saveState(state) { assertEquals(EditorSession.ApplyResult.DraftSaved, it) }
            val restored = EditorSession(context, intent, state)
            assertTrue(restored.creatingNew)
            assertEquals(EditorSession.Target(0, world), restored.target)
            assertEquals(59, restored.settings.textSize)
            assertEquals(9, restored.settings.dateOffsetX)
            assertEquals(41, WidgetSettingsStore.load(context, id, world).textSize)
            assertEquals(12, WidgetSettingsStore.load(context, id, world).dateOffsetX)
            assertEquals(id, EditorPreferences(context).selectedTarget(world))
        }
    }

    @Test
    fun catalogOpensTheExactPlacedInstanceAndRoutesCreationThroughTheTypeGallery() {
        bind(9211, false)
        bind(9212, true)
        WidgetSettingsStore.save(context, 9211, false, WidgetSettings.defaults(false))
        WidgetSettingsStore.save(context, 9212, true, WidgetSettings.defaults(true))
        Robolectric.buildActivity(MyWidgetsActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val root = layout(activity)
            val list = root.findViewWithTag<ListView>("placed-widgets")
            assertEquals(2, list.count)
            val row = list.adapter.getView(1, null, list)
            assertEquals("widget-row:world:9212", row.tag)
            assertTrue(list.performItemClick(row, 1, 9212))
            val edit = requireNotNull(Shadows.shadowOf(activity).nextStartedActivity)
            assertEquals(9212, edit.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
            assertTrue(edit.getBooleanExtra("world", false))
            assertTrue(EditorIntents.isDirectEdit(edit))
            assertEquals(0, edit.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
            assertTrue(root.findViewWithTag<View>("add-widget").performClick())
            val gallery = requireNotNull(Shadows.shadowOf(activity).nextStartedActivity)
            assertEquals(WidgetTypeActivity::class.java.name, gallery.component!!.className)
            assertEquals(0, gallery.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
            assertFalse(activity.isFinishing)
        }
    }

    @Test
    fun catalogExcludesAnonymousBindingsAndKeepsConfirmedInstances() {
        for (world in listOf(false, true)) {
            val id = if (world) 9242 else 9241
            bind(id, world)
            WidgetSettingsStore.save(context, id, world, WidgetSettings.defaults(world))
            bind(id + 10, world)
            bind(id + 20, world)
            WidgetSettingsStore.beginConfiguration(context, id + 20, world)
            bind(id + 30, world)
            WidgetSettingsStore.save(context, id + 30, world, WidgetSettings.defaults(world))
        }
        Robolectric.buildActivity(MyWidgetsActivity::class.java).setup().visible().use { controller ->
            val list = layout(controller.get()).findViewWithTag<ListView>("placed-widgets")
            assertEquals(4, list.count)
            assertEquals(setOf("widget-row:clock:9241", "widget-row:clock:9271",
                "widget-row:world:9242", "widget-row:world:9272"),
                (0 until list.count).map { list.adapter.getView(it, null, list).tag }.toSet())
        }
    }

    @Test
    fun catalogRecoversBoundWidgetsWithoutSettingsAndExcludesPendingConfiguration() {
        for (world in listOf(false, true)) {
            val id = if (world) 9232 else 9231
            val pending = if (world) 9234 else 9233
            bind(id, world)
            WidgetSettingsStore.save(context, id, world, WidgetSettings.defaults(world))
            WidgetInstances.remember(context, id, world)
            context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
            bind(pending, world)
            WidgetSettingsStore.beginConfiguration(context, pending, world)
            assertTrue(WidgetSettingsStore.configuredIds(context, intArrayOf(id, pending), world).isEmpty())
        }
        Robolectric.buildActivity(MyWidgetsActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val list = layout(activity).findViewWithTag<ListView>("placed-widgets")
            assertEquals(2, list.count)
            for ((position, world) in listOf(false, true).withIndex()) {
                val id = if (world) 9232 else 9231
                val row = list.adapter.getView(position, null, list)
                assertEquals("widget-row:${if (world) "world" else "clock"}:$id", row.tag)
                assertTrue(list.performItemClick(row, position, id.toLong()))
                val edit = requireNotNull(Shadows.shadowOf(activity).nextStartedActivity)
                assertEquals(id, edit.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
                val session = EditorSession(context, edit, null)
                assertEquals(EditorSession.Target(id, world), session.target)
                assertTrue(session.apply(true) is EditorSession.ApplyResult.HostUpdate)
                assertTrue(WidgetSettingsStore.hasOwnSettings(context, id, world))
            }
        }
    }

    @Test
    fun typeGallerySnapshotsBothPreviewsAndFinishesAfterIndependentCreation() {
        for (world in listOf(false, true)) {
            Robolectric.buildActivity(WidgetTypeActivity::class.java).setup().visible().use { controller ->
                val activity = controller.get()
                layout(activity)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                val root = layout(activity)
                for (kind in listOf("clock", "world")) {
                    val preview = root.findViewWithTag<WidgetThumbnailView>("widget-type-preview:$kind")
                    assertEquals(1, preview.childCount)
                    val image = preview.getChildAt(0) as android.widget.ImageView
                    val bitmap = (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
                    assertTrue(bitmap.width > 0 && bitmap.height > 0)

                }
                val kind = if (world) "world" else "clock"
                val option = root.findViewWithTag<View>("widget-type:$kind")
                assertTrue(option.isFocusable)
                assertTrue(option.performClick())
                val creation = requireNotNull(Shadows.shadowOf(activity).nextStartedActivity)
                assertEquals(MainActivity::class.java.name, creation.component!!.className)
                assertEquals(EditorIntents.CREATE_WIDGET, creation.action)
                assertEquals(world, creation.getBooleanExtra("world", !world))
                assertFalse(creation.hasExtra(AppWidgetManager.EXTRA_APPWIDGET_ID))
                assertTrue(creation.getBooleanExtra(EditorIntents.FROM_WIDGETS, false))
                assertEquals(0, creation.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP)
                assertTrue(activity.isFinishing)
                val session = EditorSession(context, creation, null)
                assertTrue(session.creatingNew)
                assertEquals(EditorSession.Target(0, world), session.target)
            }
        }
    }

    @Test
    fun typeGalleryBackFinishesWithoutCreatingAWidget() {
        Robolectric.buildActivity(WidgetTypeActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            assertTrue(layout(activity).findViewWithTag<View>("app-screen-back").performClick())
            assertTrue(activity.isFinishing)
            assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        }
    }

    @Test
    fun settingsContainsAppControlsAndAboutContainsOnlyItsThreeProductFields() {
        Robolectric.buildActivity(AppSettingsActivity::class.java).setup().visible().use { controller ->
            val root = layout(controller.get())
            for (tag in listOf("language-trigger", "app-theme", "data-privacy",
                "about-app")) assertNotNull(tag, root.findViewWithTag<View>(tag))
            assertNull(root.findViewWithTag<View>("action-licenses"))
            assertNull(root.findViewWithTag<View>("temperature-unit"))
        }
        Robolectric.buildActivity(AboutActivity::class.java).setup().visible().use { controller ->
            val root = layout(controller.get())
            assertEquals("Noonlet", root.findViewWithTag<TextView>("about-name").text.toString())
            assertEquals(context.getString(R.string.app_description),
                root.findViewWithTag<TextView>("about-description").text.toString())
            assertTrue(root.findViewWithTag<TextView>("about-version").text.toString().startsWith("Version "))
            assertEquals(3, (root.findViewWithTag<View>("about-name").parent as ViewGroup).childCount)
            assertNull(root.findViewWithTag<View>("action-licenses"))
            assertNull(root.findViewWithTag<View>("data-privacy"))
        }
    }

    @Test
    fun changingThemeModeWithoutChangingItsPaletteUpdatesTheSelectedValue() {
        AppPreferences(context).setThemeMode(ThemeMode.LIGHT)
        Robolectric.buildActivity(AppSettingsActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val root = layout(activity)
            assertTrue(root.findViewWithTag<View>("app-theme").performClick())
            val dialog: AlertDialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val choices = dialog.listView
            choices.performItemClick(choices.adapter.getView(0, null, choices), 0, 0)
            assertEquals(ThemeMode.SYSTEM, AppPreferences(context).themeMode())
            assertEquals(context.getString(R.string.theme_system),
                layout(activity).findViewWithTag<TextView>("app-theme:value").text.toString())
        }
    }

    @Test
    fun presentationChangesDismissDialogsAndLanguageMenusFromThePreviousContent() {
        AppPreferences(context).setThemeMode(ThemeMode.LIGHT)
        Robolectric.buildActivity(AppSettingsActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val before = layout(activity)
            before.findViewWithTag<View>("app-theme").performClick()
            val oldDialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val oldChoices = oldDialog.listView
            assertTrue(oldDialog.isShowing)
            AppPreferences(context).setThemeMode(ThemeMode.DARK)
            activity.onConfigurationChanged(android.content.res.Configuration(activity.resources.configuration))
            assertFalse(oldDialog.isShowing)
            assertNotSame(before, layout(activity))
            oldChoices.performItemClick(oldChoices.adapter.getView(0, null, oldChoices), 0, 0)
            assertEquals(ThemeMode.DARK, AppPreferences(context).themeMode())
            val current = layout(activity)
            current.findViewWithTag<View>("app-theme").performClick()
            val currentDialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val choices = currentDialog.listView
            choices.performItemClick(choices.adapter.getView(1, null, choices), 1, 1)
            assertEquals(ThemeMode.LIGHT, AppPreferences(context).themeMode())
            val selected = layout(activity)
            assertEquals(context.getString(R.string.theme_light),
                selected.findViewWithTag<TextView>("app-theme:value").text.toString())
            selected.findViewWithTag<View>("language-trigger").performClick()
            val menu = DialogTestFixtures.latest()
            assertTrue(menu.isShowing)
            AppLocales.setLanguage(activity, "de")
            activity.onConfigurationChanged(android.content.res.Configuration(activity.resources.configuration))
            assertFalse(menu.isShowing)
            assertNotSame(selected, layout(activity))
        }
    }

    @Test
    fun openingSettingsKeepsTheEditorPreviewAndDraftOnReturn() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val root = layout(activity)
            val preview = root.findViewWithTag<View>("preview-stage")
            val session = activity.session
            session.settings.dateOffsetX = 11
            assertTrue(root.findViewWithTag<ImageButton>("settings-trigger").performClick())
            val settings = requireNotNull(Shadows.shadowOf(activity).nextStartedActivity)
            assertEquals(AppSettingsActivity::class.java.name, settings.component!!.className)
            controller.pause()
            Robolectric.buildActivity(AppSettingsActivity::class.java, settings).setup().visible().use { }
            controller.resume().visible()
            assertSame(root, layout(activity))
            assertSame(preview, root.findViewWithTag<View>("preview-stage"))
            assertEquals(11, session.settings.dateOffsetX)
        }
    }

    @Test
    fun choosingALanguageInSettingsUpdatesTheEditorOnReturnWithoutLosingItsDraft() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { main ->
            val activity = main.get()
            val oldRoot = layout(activity)
            val session = activity.session
            session.settings.dateOffsetX = 14
            oldRoot.findViewWithTag<View>("settings-trigger").performClick()
            val intent = requireNotNull(Shadows.shadowOf(activity).nextStartedActivity)
            main.pause()
            Robolectric.buildActivity(AppSettingsActivity::class.java, intent).setup().visible().use { settings ->
                layout(settings.get()).findViewWithTag<View>("language-trigger").performClick()
                val menu = DialogTestFixtures.latest()
                DialogTestFixtures.choose(menu, 1)
                assertEquals("de", AppLocales.language(context))
                assertFalse(menu.isShowing)
            }
            main.resume().visible()
            assertNotSame(oldRoot, layout(activity))
            assertEquals(14, session.settings.dateOffsetX)
            assertEquals("de", AppLocales.resourceLocale(activity).language)
            assertEquals("Einstellungen", layout(activity).findViewWithTag<View>("settings-trigger")
                .contentDescription.toString())
        }
    }

    @Test
    fun thumbnailBoundsOversizedHostOptionsAndReleasesItsNativeClocks() {
        bind(9221, false)
        WidgetSettingsStore.save(context, 9221, false, WidgetSettings.defaults(false))
        AppWidgetManager.getInstance(context).updateAppWidgetOptions(9221, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 20_000)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 20_000)
        })
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val thumbnail = WidgetThumbnailView(activity)
            activity.setContentView(thumbnail)
            thumbnail.bind(9221, false)
            layout(activity)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            layout(activity)
            assertEquals(1, thumbnail.childCount)
            val image = thumbnail.getChildAt(0) as android.widget.ImageView
            val bitmap = (image.drawable as android.graphics.drawable.BitmapDrawable).bitmap
            assertTrue(bitmap.width <= 1024)
            assertTrue(bitmap.height <= 1024)
            assertTrue(bitmap.width * bitmap.height <= 1024 * 1024)

            (thumbnail.parent as ViewGroup).removeView(thumbnail)
            assertEquals(0, thumbnail.childCount)

        }
    }

    private fun bind(id: Int, world: Boolean) {
        val info = AppWidgetProviderInfo().apply {
            provider = BaseWidgetProvider.component(context, world)
            initialLayout = if (world) R.layout.world_widget else R.layout.clock_widget
        }
        Shadows.shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(id, info)
    }

    private fun layout(activity: Activity): View {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0).apply {
            measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY))
            layout(0, 0, 400, 850)
        }
    }
}

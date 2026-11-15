package app.noonlet

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
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
class WorldDefaultActionTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
    }

    @Test
    @Throws(Exception::class)
    fun freshWorldCardAndBackdropOpenThatWidgetsEditorInsteadOfTheAlarmFallback() {
        assertEquals("settings", WidgetSettings.defaults(true).tapAction)
        assertEquals("clock", WidgetSettings.defaults(false).tapAction)
        val id: Int = 991
        val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
        val provider: AppWidgetProviderInfo = AppWidgetProviderInfo()
        provider.provider = ComponentName(context, WorldWidgetProvider::class.java)
        provider.initialLayout = R.layout.world_widget
        Shadows.shadowOf(manager).addInstalledProvider(provider)
        Shadows.shadowOf(manager).addBoundWidget(id, provider)
        val bounds: Bundle = Bundle()
        bounds.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)
        bounds.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 300)
        bounds.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
        bounds.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        manager.updateAppWidgetOptions(id, bounds)
        val host: View = WidgetRenderer.world(context, id).apply(context, FrameLayout(context))
        host.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(90, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, 300, 90)
        WidgetTestViews.prepare(host)
        for (target: Int in intArrayOf(R.id.world_card, R.id.widget_root)) {
            assertTrue(host.findViewById<View>(target).performClick())
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val launched: Intent =
                Shadows.shadowOf(context as Application?).getNextStartedActivity()
            assertNotNull(launched)
            assertEquals(ComponentName(context, MainActivity::class.java), launched.getComponent())
            assertEquals(id, launched.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
            assertTrue(launched.getBooleanExtra("world", false))
            assertFalse(launched.hasExtra(TapActions.ALARM_UNAVAILABLE))
            Robolectric.buildActivity(MainActivity::class.java, launched)
                .setup()
                .use(
                    { c ->
                        assertEquals(id, c.get().session.widgetId)
                        assertTrue(c.get().session.world)
                    },
                )
        }
    }

    @Test
    fun savedExplicitActionsArePreservedAndMissingLegacyActionUsesTheNewDefault() {
        val actions: Array<String> =
            arrayOf<String>("none", "calendar", "clock", "app:example.clock/.MainActivity")
        for (i: Int in actions.indices) {
            val saved: WidgetSettings = WidgetSettings.defaults(true)
            saved.tapAction = actions[i]
            saved.opacity = 71 + i
            WidgetSettingsStore.save(context, 1100 + i, true, saved)
            val loaded: WidgetSettings = WidgetSettingsStore.load(context, 1100 + i, true)
            assertEquals(actions[i], loaded.tapAction)
            assertEquals(71 + i, loaded.opacity)
        }
        context
            .getSharedPreferences("noonlet_widgets", 0)
            .edit()
            .putBoolean("world.1201.saved", true)
            .putString("world.1201.state", "{\"cfg_opacity\":73}")
            .commit()
        val partial: WidgetSettings = WidgetSettingsStore.load(context, 1201, true)
        assertEquals("settings", partial.tapAction)
        assertEquals(73, partial.opacity)
        assertEquals("settings", WidgetSettingsStore.load(context, 0, true).tapAction)
        assertEquals("clock", WidgetSettingsStore.load(context, 0, false).tapAction)
    }

    @Test
    @Throws(Exception::class)
    fun oldWorldDefaultsMigrateWhileNewExplicitChoicesAndLocalClocksKeepTheirActions() {
        val prefs: android.content.SharedPreferences =
            context.getSharedPreferences("noonlet_widgets", 0)
        for (index: Int in 0..1) {
            val id: Int = 1300 + index
            val oldAction: String = if (index == 0) "clock" else "calendar"
            val old: WidgetSettings = SettingsTestFixtures.sample()
            old.tapAction = oldAction
            WidgetSettingsStore.save(context, id, true, old)
            prefs.edit().remove("world." + id + ".tapDefaultVersion").commit()
            val migrated: WidgetSettings = WidgetSettingsStore.load(context, id, true)
            old.tapAction = "settings"
            SettingsTestFixtures.assertSettings(old, migrated)
            assertFalse(
                "Loading must not rewrite the old prefix",
                prefs.contains("world." + id + ".tapDefaultVersion"),
            )
            assertEquals(
                oldAction,
                org.json
                    .JSONObject(prefs.getString("world." + id + ".state", ""))
                    .getString("cfg_tapAction"),
            )
            migrated.tapAction = oldAction
            WidgetSettingsStore.save(context, id, true, migrated)
            assertEquals(1, prefs.getInt("world." + id + ".tapDefaultVersion", 0))
            assertEquals(oldAction, WidgetSettingsStore.load(context, id, true).tapAction)
            assertFalse(
                "Editor-only metadata must not enter the settings JSON",
                requireNotNull(prefs.getString("world." + id + ".state", ""))
                    .contains("tap_default_version"),
            )
            old.tapAction = oldAction
            WidgetSettingsStore.save(context, id, false, old)
            assertEquals(oldAction, WidgetSettingsStore.load(context, id, false).tapAction)
        }
        var id: Int = 1320
        for (action: String in arrayOf<String>("none", "app:example.clock/.MainActivity")) {
            val saved: WidgetSettings = WidgetSettings.defaults(true)
            saved.tapAction = action
            WidgetSettingsStore.save(context, id, true, saved)
            prefs.edit().remove("world." + id + ".tapDefaultVersion").commit()
            assertEquals(action, WidgetSettingsStore.load(context, id, true).tapAction)
            id++
        }
    }

    @Test
    fun fallbackDefaultsUseTheirOwnVersionAndNeverRewriteAnotherWidgetsState() {
        val prefs: android.content.SharedPreferences =
            context.getSharedPreferences("noonlet_widgets", 0)
        val legacy: WidgetSettings = WidgetSettings.defaults(true)
        legacy.tapAction = "calendar"
        legacy.opacity = 64
        WidgetSettingsStore.save(context, 0, true, legacy)
        prefs.edit().remove("world.0.tapDefaultVersion").commit()
        val other: WidgetSettings = WidgetSettings.defaults(true)
        other.tapAction = "none"
        WidgetSettingsStore.save(context, 1401, true, other)
        val untouched: String = requireNotNull(prefs.getString("world.1401.state", ""))
        assertEquals("settings", WidgetSettingsStore.load(context, 1400, true).tapAction)
        assertEquals(64, WidgetSettingsStore.load(context, 1400, true).opacity)
        assertFalse(prefs.contains("world.1400.saved"))
        assertEquals(untouched, prefs.getString("world.1401.state", ""))
        assertEquals("none", WidgetSettingsStore.load(context, 1401, true).tapAction)
        legacy.tapAction = "clock"
        WidgetSettingsStore.save(context, 0, true, legacy)
        assertEquals("clock", WidgetSettingsStore.load(context, 1400, true).tapAction)
        val own: WidgetSettings = WidgetSettings.defaults(true)
        own.tapAction = "clock"
        WidgetSettingsStore.save(context, 1402, true, own)
        prefs.edit().remove("world.1402.tapDefaultVersion").commit()
        assertEquals("settings", WidgetSettingsStore.load(context, 1402, true).tapAction)
        assertEquals("clock", WidgetSettingsStore.load(context, 1400, true).tapAction)
    }

    @Test
    fun overlappingRestoreMigratesLegacySourcesAndPreservesNewExplicitSources() {
        val prefs: android.content.SharedPreferences =
            context.getSharedPreferences("noonlet_widgets", 0)
        val legacy: WidgetSettings = WidgetSettings.defaults(true)
        legacy.tapAction = "calendar"
        legacy.opacity = 61
        WidgetSettingsStore.save(context, 1501, true, legacy)
        prefs.edit().remove("world.1501.tapDefaultVersion").commit()
        val explicit: WidgetSettings = WidgetSettings.defaults(true)
        explicit.tapAction = "clock"
        explicit.opacity = 82
        WidgetSettingsStore.save(context, 1502, true, explicit)
        val local: WidgetSettings = WidgetSettings()
        local.zoneId = "Asia/Kathmandu"
        WidgetSettingsStore.save(context, 1501, false, local)
        bind(1502, true)
        bind(1503, true)
        WorldWidgetProvider().onRestored(context, intArrayOf(1501, 1502), intArrayOf(1502, 1503))
        assertEquals("settings", WidgetSettingsStore.load(context, 1502, true).tapAction)
        assertEquals(61, WidgetSettingsStore.load(context, 1502, true).opacity)
        assertEquals("clock", WidgetSettingsStore.load(context, 1503, true).tapAction)
        assertEquals(82, WidgetSettingsStore.load(context, 1503, true).opacity)
        assertEquals(1, prefs.getInt("world.1502.tapDefaultVersion", 0))
        assertEquals(1, prefs.getInt("world.1503.tapDefaultVersion", 0))
        assertFalse(prefs.contains("world.1501.saved"))
        assertEquals("Asia/Kathmandu", WidgetSettingsStore.load(context, 1501, false).zoneId)
    }

    @Test
    @Throws(Exception::class)
    fun oldActivityAndInactiveDraftsUseTheNewWorldActionWithoutLosingOtherEdits() {
        val old: WidgetSettings = WidgetSettings.defaults(true)
        old.tapAction = "clock"
        old.opacity = 90
        WidgetSettingsStore.save(context, 1601, true, old)
        bind(1601, true)
        val prefs: android.content.SharedPreferences =
            context.getSharedPreferences("noonlet_widgets", 0)
        prefs.edit().remove("world.1601.tapDefaultVersion").commit()
        val activeDraft: WidgetSettings = WidgetSettings.defaults(true)
        activeDraft.tapAction = "calendar"
        activeDraft.opacity = 63
        var state: Bundle = legacyHomeState(activeDraft, 1601, true, 0, 1601)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup(state)
            .visible()
            .use(
                { c ->
                    assertEquals("settings", config(c.get()).tapAction)
                    assertEquals(63, config(c.get()).opacity)
                    assertEquals(
                        "The restored active draft is applied automatically",
                        63,
                        WidgetSettingsStore.load(context, 1601, true).opacity,
                    )
                },
            )
        WidgetSettingsStore.save(context, 1600, false, WidgetSettings())
        bind(1600, false)
        old.tapAction = "calendar"
        WidgetSettingsStore.save(context, 1602, true, old)
        bind(1602, true)
        prefs.edit().remove("world.1602.tapDefaultVersion").commit()
        val clockDraft: WidgetSettings = WidgetSettings()
        clockDraft.dateOffsetX = 17
        state = legacyHomeState(clockDraft, 1600, false, 1600, 1602)
        val remoteDraft: WidgetSettings = WidgetSettings.defaults(true)
        remoteDraft.tapAction = "clock"
        remoteDraft.opacity = 77
        val remoteState: Bundle = Bundle()
        SettingsCodec.writeTo(remoteDraft, remoteState)
        val drafts: Bundle = Bundle()
        drafts.putBundle("world:1602", remoteState)
        state.putBundle("home_drafts", drafts)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup(state)
            .visible()
            .use(
                { c ->
                    assertEquals("clock", config(c.get()).tapAction)
                    assertEquals(17, config(c.get()).dateOffsetX)
                    WidgetTestViews.selectWorldTab(c.get())
                    assertEquals("settings", config(c.get()).tapAction)
                    assertEquals(77, config(c.get()).opacity)
                    assertNotNull(
                        c.get()
                            .findViewById<View>(android.R.id.content)
                            .findViewWithTag<View>("action-save-changes"),
                    )
                    assertEquals(
                        "settings",
                        WidgetSettingsStore.load(context, 1602, true).tapAction,
                    )
                    assertEquals(77, WidgetSettingsStore.load(context, 1602, true).opacity)
                    assertEquals(1, prefs.getInt("world.1602.tapDefaultVersion", 0))
                    assertTrue(
                        "The earlier active draft already saved this prefix",
                        prefs.contains("world.1601.tapDefaultVersion"),
                    )
                    assertEquals("clock", WidgetSettingsStore.load(context, 1600, false).tapAction)
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun newExplicitWorldChoicesSurviveEditorRecreationAndLegacyPartialDrafts() {
        var id: Int = 1700
        for (action: String in arrayOf<String>("clock", "calendar")) {
            val saved: WidgetSettings = WidgetSettings.defaults(true)
            saved.tapAction = action
            WidgetSettingsStore.save(context, id, true, saved)
            bind(id, true)
            val intent: Intent =
                Intent(context, MainActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            Robolectric.buildActivity(MainActivity::class.java, intent)
                .setup()
                .visible()
                .use(
                    { c ->
                        config(c.get()).opacity = 54
                        c.recreate()
                        assertEquals(action, config(c.get()).tapAction)
                        assertEquals(54, config(c.get()).opacity)
                        assertNotNull(
                            c.get()
                                .findViewById<View>(android.R.id.content)
                                .findViewWithTag<View>("action-save-changes"),
                        )
                    },
                )
            assertEquals(action, WidgetSettingsStore.load(context, id, true).tapAction)
            val restored: WidgetSettings = WidgetSettingsStore.load(context, id, true)
            val partial: Bundle = Bundle()
            partial.putInt("cfg_opacity", 73)
            WidgetSettingsStore.restoreEditorState(context, id, true, restored, partial)
            assertEquals(action, restored.tapAction)
            assertEquals(73, restored.opacity)
            partial.putString("cfg_tapAction", if (action.equals("clock")) "calendar" else "clock")
            WidgetSettingsStore.restoreEditorState(context, id, true, restored, partial)
            assertEquals(
                "An older Activity must not replace a newer saved action",
                action,
                restored.tapAction,
            )
            val plain: Bundle = Bundle()
            val versioned: Bundle = Bundle()
            SettingsCodec.writeTo(saved, plain)
            SettingsCodec.writeEditorState(saved, versioned)
            assertTrue(
                "Version metadata does not make an unchanged draft dirty",
                SettingsCodec.sameEditorSettings(plain, versioned),
            )
            assertEquals(plain.size() + 1, versioned.size())
            id++
        }
    }

    @Test
    fun applicationUpgradeRefreshesThePlacedLegacyWidgetToOpenItsSettings() {
        val id: Int = 1800
        val old: WidgetSettings = WidgetSettings.defaults(true)
        old.tapAction = "clock"
        WidgetSettingsStore.save(context, id, true, old)
        bind(id, true)
        val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
        manager.updateAppWidget(id, WidgetRenderer.world(context, id))
        context
            .getSharedPreferences("noonlet_widgets", 0)
            .edit()
            .remove("world." + id + ".tapDefaultVersion")
            .commit()
        UpdateReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        val placed: View = Shadows.shadowOf(manager).getViewFor(id)
        assertNotNull(placed)
        assertTrue(placed.findViewById<View>(R.id.world_card).performClick())
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val launched: Intent = Shadows.shadowOf(context as Application?).getNextStartedActivity()
        assertNotNull(launched)
        assertEquals(ComponentName(context, MainActivity::class.java), launched.getComponent())
        assertEquals(id, launched.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
        assertTrue(launched.getBooleanExtra("world", false))
        assertFalse(launched.hasExtra(TapActions.ALARM_UNAVAILABLE))
    }

    @Test
    @Throws(Exception::class)
    fun newUnsavedWorldActionsSurviveRecreationBeforeTheirFirstSave() {
        var id: Int = 1750
        for (action: String in arrayOf<String>("clock", "calendar")) {
            val saved: WidgetSettings = WidgetSettings.defaults(true)
            WidgetSettingsStore.save(context, id, true, saved)
            bind(id, true)
            val intent: Intent =
                Intent(context, MainActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            Robolectric.buildActivity(MainActivity::class.java, intent)
                .setup()
                .visible()
                .use(
                    { c ->
                        assertEquals("settings", config(c.get()).tapAction)
                        config(c.get()).tapAction = action
                        config(c.get()).opacity = 57
                        assertEquals(
                            "settings",
                            WidgetSettingsStore.load(context, id, true).tapAction,
                        )
                        c.recreate()
                        assertEquals(
                            "An unsaved explicit choice needs the editor Bundle version",
                            action,
                            config(c.get()).tapAction,
                        )
                        assertEquals(57, config(c.get()).opacity)
                        assertEquals(
                            "Recreation retains the automatically applied choice",
                            action,
                            WidgetSettingsStore.load(context, id, true).tapAction,
                        )
                        assertNotNull(
                            c.get()
                                .findViewById<View>(android.R.id.content)
                                .findViewWithTag<View>("action-save-changes"),
                        )
                    },
                )
            assertEquals(action, WidgetSettingsStore.load(context, id, true).tapAction)
            assertEquals(57, WidgetSettingsStore.load(context, id, true).opacity)
            id++
        }
    }

    @Throws(Exception::class)
    private fun config(activity: MainActivity): WidgetSettings {
        return activity.session.settings
    }

    private fun legacyHomeState(
        config: WidgetSettings,
        id: Int,
        world: Boolean,
        clockId: Int,
        worldId: Int,
    ): Bundle {
        val state: Bundle = Bundle()
        SettingsCodec.writeTo(config, state)
        state.putBoolean("world", world)
        state.putInt("home_target_id", id)
        state.putInt("selected_clock_id", clockId)
        state.putInt("selected_world_id", worldId)
        return state
    }

    private fun bind(id: Int, world: Boolean) {
        val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
        val provider: AppWidgetProviderInfo = AppWidgetProviderInfo()
        provider.provider =
            ComponentName(
                context,
                if (world) WorldWidgetProvider::class.java else ClockWidgetProvider::class.java,
            )
        provider.initialLayout = if (world) R.layout.world_widget else R.layout.clock_widget
        Shadows.shadowOf(manager).addInstalledProvider(provider)
        Shadows.shadowOf(manager).addBoundWidget(id, provider)
    }
}

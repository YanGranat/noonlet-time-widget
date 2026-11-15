package app.noonlet

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
import java.time.Duration
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAppWidgetManager

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34, 36],
    qualifiers = "en-rUS-w400dp-h850dp-mdpi",
    shadows = [PinRequestFixtures.CapturedPins::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class EditorCompletionTest {
    private lateinit var context: Context
    private lateinit var manager: AppWidgetManager

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        PinRequestFixtures.installHome(context)
        manager = AppWidgetManager.getInstance(context)
        for (name in
            arrayOf("noonlet_widgets", "MainActivity", "widget_drafts", "widget_configuration")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        AppLocales.setLanguage(context, "en")
        PinRequestFixtures.CapturedPins.requests.clear()
        PinRequestFixtures.CapturedPins.metadataRequests.clear()
        PinRequestFixtures.CapturedPins.supported = true
        PinRequestFixtures.CapturedPins.accepted = true
        PinRequestFixtures.CapturedPins.failure = null
    }

    @Test
    fun repeatedAddTapsShareOneLauncherRequestUntilItReturns() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val add = root(activity).findViewWithTag<View>("action-add-widget")
            add.performClick()
            add.performClick()
            assertEquals(1, PinRequestFixtures.CapturedPins.requests.size)
            activity.onWindowFocusChanged(false)
            activity.onWindowFocusChanged(true)
            add.performClick()
            assertEquals(2, PinRequestFixtures.CapturedPins.requests.size)
        }
    }

    @Test
    fun thePublicEditorIgnoresInternalPinSnapshotExtras() {
        val settings = WidgetSettings.defaults(true).apply { opacity = 67; weatherEnabled = true }
        val intent = EditorIntents.handoff(context, true, settings, WidgetSize.FOUR_BY_TWO)
        val internal = context.packageManager.getActivityInfo(requireNotNull(intent.component), 0)
        assertFalse(internal.exported)
        assertEquals(WidgetPinActivity::class.java.name, internal.name)
        assertNull(internal.targetActivity)
        intent.setComponent(ComponentName(context, MainActivity::class.java))
        Robolectric.buildActivity(MainActivity::class.java, intent).setup().visible().use { controller ->
            val activity = controller.get()
            activity.onWindowFocusChanged(true)
            assertTrue(PinRequestFixtures.CapturedPins.requests.isEmpty())
            assertEquals(100, activity.session.settings.opacity)
            assertFalse(activity.session.settings.weatherEnabled)
        }
    }

    @Test
    fun unsupportedPinningKeepsTheLaunchersConfigurationResultContract() {
        PinRequestFixtures.CapturedPins.supported = false
        bind(37220, true)
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            .setComponent(ComponentName(context, MainActivity::class.java))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 37220)
        Robolectric.buildActivity(MainActivity::class.java, intent).setup().visible().use { controller ->
            val activity = controller.get()
            root(activity).findViewWithTag<View>("preview-size:3").performClick()
            root(activity).findViewWithTag<View>("action-add-widget").performClick()
            assertTrue(activity.isFinishing)
            assertEquals(android.app.Activity.RESULT_OK, Shadows.shadowOf(activity).resultCode)
            assertEquals(37220, Shadows.shadowOf(activity).resultIntent
                .getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
            assertTrue(WidgetSettingsStore.hasOwnSettings(context, 37220, true))
            assertTrue(PinRequestFixtures.CapturedPins.requests.isEmpty())
        }
    }

    @Test
    fun launcherConfigurationReturnsItsAllocationBeforeStartingASizedPin() {
        for (world in listOf(false, true)) for (size in WidgetSize.entries.drop(1)) {
            val originalId = 37000 + size.ordinal + if (world) 10 else 0
            val pinnedId = originalId + 100
            bind(originalId, world)
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .setComponent(ComponentName(context, MainActivity::class.java))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, originalId)
            lateinit var handoff: Intent
            Robolectric.buildActivity(MainActivity::class.java, intent).setup().visible().use { controller ->
                val activity = controller.get()
                assertEquals(world, activity.session.world)
                root(activity).findViewWithTag<View>("preview-size:${size.ordinal}").performClick()
                activity.session.settings.timeColor = android.graphics.Color.CYAN
                val requests = PinRequestFixtures.CapturedPins.requests.size
                root(activity).findViewWithTag<View>("action-add-widget").performClick()
                assertTrue(activity.isFinishing)
                assertEquals(android.app.Activity.RESULT_CANCELED, Shadows.shadowOf(activity).resultCode)
                assertEquals(requests, PinRequestFixtures.CapturedPins.requests.size)
                assertFalse(WidgetSettingsStore.hasOwnSettings(context, originalId, world))
                handoff = requireNotNull(Shadows.shadowOf(activity).nextStartedActivity)
                assertEquals(EditorIntents.CREATE_WIDGET, handoff.action)
                assertTrue(handoff.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                assertTrue(handoff.flags and Intent.FLAG_ACTIVITY_MULTIPLE_TASK != 0)
            }
            (if (world) WorldWidgetProvider() else ClockWidgetProvider())
                .onDeleted(context, intArrayOf(originalId))
            Robolectric.buildActivity(WidgetPinActivity::class.java, handoff).setup().visible().use { controller ->
                val activity = controller.get()
                assertNull(activity.findViewById<View>(R.id.widget_root))
                assertNull(activity.findViewById<ViewGroup>(android.R.id.content)
                    .findViewWithTag<View>("preview-stage"))
                activity.onWindowFocusChanged(true)
                val pending = PinRequestFixtures.CapturedPins.requests.last()
                assertEquals(BaseWidgetProvider.component(context, world, size), pending.provider)
                assertTrue(activity.isFinishing)
                bind(pinnedId, world, size)
                complete(pending, pinnedId)
                assertFalse(WidgetSettingsStore.pendingConfiguration(context, originalId, world))
                assertFalse(WidgetSettingsStore.hasOwnSettings(context, originalId, world))
                assertTrue(WidgetSettingsStore.hasOwnSettings(context, pinnedId, world))
                assertEquals(android.graphics.Color.CYAN, WidgetSettingsStore.load(context, pinnedId, world).timeColor)
                assertTrue(pinnedId in BaseWidgetProvider.boundIds(context, world))
                assertFalse(pinnedId in BaseWidgetProvider.boundIds(context, !world))
                val edit = Intent(context, MainActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pinnedId)
                Robolectric.buildActivity(MainActivity::class.java, edit).setup().visible().use { reopened ->
                    assertEquals(world, reopened.get().session.world)
                    assertEquals(pinnedId, reopened.get().session.target.id)
                    assertFalse(reopened.get().session.initialConfiguration)
                }
            }
        }
    }

    @Test
    fun aHandoffRequestsPinningOnlyOnceAcrossFocusAndRecreation() {
        val settings = WidgetSettings.defaults(true).apply { opacity = 67 }
        val intent = EditorIntents.handoff(context, true, settings, WidgetSize.FOUR_BY_TWO)
        val saved = Bundle()
        Robolectric.buildActivity(WidgetPinActivity::class.java, intent).setup().visible().use { controller ->
            val activity = controller.get()
            activity.onWindowFocusChanged(true)
            val requests = PinRequestFixtures.CapturedPins.requests.size
            assertEquals(1, requests)
            activity.onWindowFocusChanged(false)
            activity.onWindowFocusChanged(true)
            controller.saveInstanceState(saved)
            assertEquals(requests, PinRequestFixtures.CapturedPins.requests.size)
            assertTrue(activity.isFinishing)
        }
        Robolectric.buildActivity(WidgetPinActivity::class.java, intent).setup(saved).visible().use { controller ->
            controller.get().onWindowFocusChanged(true)
            assertTrue(controller.get().isFinishing)
            assertEquals(1, PinRequestFixtures.CapturedPins.requests.size)
        }
    }

    @Test
    fun aLauncherOriginReturnsWithoutDeliveringAnotherLauncherIntent() {
        editor().use { controller ->
            val activity = controller.get()
            activity.intent.putExtra(Intent.EXTRA_REFERRER,
                android.net.Uri.parse("android-app://example.launcher"))
            val pending = request(activity)
            controller.windowFocusChanged(false).topActivityResumed(false)
            bind(37185, false)
            complete(pending, 37185)
            activity.onWindowFocusChanged(true)
            assertTrue(activity.isFinishing)
            assertTrue(Shadows.shadowOf(activity).isTaskMovedToBack)
            assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        }
    }

    @Test
    @Config(sdk = [26, 28, 34, 36])
    fun anExternalOriginReturnsThroughTheSystemHomeIntent() {
        editor().use { controller ->
            val activity = controller.get()
            activity.intent.putExtra(Intent.EXTRA_REFERRER,
                android.net.Uri.parse("android-app://example.reader"))
            val pending = request(activity)
            controller.pause()
            bind(37291, false)
            complete(pending, 37291)
            controller.resume()
            activity.onWindowFocusChanged(true)
            assertCompletedAtHome(activity)
        }
    }

    @Test
    fun eachSelectedSizePinsItsStaticProviderProfile() {
        for (world in listOf(false, true)) {
            editor(world).use { controller ->
                val activity = controller.get()
                for ((index, size) in WidgetSize.entries.withIndex()) {
                    root(activity).findViewWithTag<View>("preview-size:$index").performClick()
                    idle()
                    assertEquals(size, activity.preview.requestedSize())
                    activity.onWindowFocusChanged(false)
                    activity.onWindowFocusChanged(true)
                    val pending = request(activity)
                    assertEquals(BaseWidgetProvider.component(context, world, size), pending.provider)
                    val preview = requireNotNull(pending.extras.getParcelable<android.widget.RemoteViews>(
                        AppWidgetManager.EXTRA_APPWIDGET_PREVIEW)).apply(context, android.widget.FrameLayout(context))
                    assertEquals(255, android.graphics.Color.alpha(
                        (preview.background as android.graphics.drawable.ColorDrawable).color))
                    val home = WidgetRenderer.preview(context, -1, world, activity.session.settings,
                        size.width.toFloat(), size.height.toFloat()).apply(context, android.widget.FrameLayout(context))
                    assertNull(home.background)
                    assertTrue(PinRequestFixtures.CapturedPins.metadataRequests.isEmpty())
                    PinRequestFixtures.CapturedPins.metadataRequests.clear()
                    val receiver = context.packageManager.getReceiverInfo(pending.provider,
                        android.content.pm.PackageManager.GET_META_DATA)
                    val resource = receiver.metaData.getInt(AppWidgetManager.META_DATA_APPWIDGET_PROVIDER)
                    assertTrue(resource != 0)
                    context.resources.getXml(resource).use { xml ->
                        while (xml.eventType != org.xmlpull.v1.XmlPullParser.START_TAG) xml.next()
                        val expected = listOf(2 to 1, 4 to 1, 2 to 2, 4 to 2)[index]
                        val namespace = "http://schemas.android.com/apk/res/android"
                        assertEquals(expected.first, xml.getAttributeIntValue(namespace, "targetCellWidth", 0))
                        assertEquals(expected.second, xml.getAttributeIntValue(namespace, "targetCellHeight", 0))
                        assertEquals(3, xml.getAttributeIntValue(namespace, "resizeMode", 0))
                        val features = xml.getAttributeIntValue(namespace, "widgetFeatures", 0)
                        assertEquals(size != WidgetSize.TWO_BY_ONE,
                            features and AppWidgetProviderInfo.WIDGET_FEATURE_HIDE_FROM_PICKER != 0)
                    }
                    assertFalse(activity.isFinishing)
                }
            }
        }
    }

    @Test
    @Config(sdk = [26])
    fun legacyPinRequestsRetainTheirSupportedPlatformFlow() {
        editor().use { controller ->
            root(controller.get()).findViewWithTag<View>("preview-size:3").performClick()
            request(controller.get())
            assertTrue(PinRequestFixtures.CapturedPins.metadataRequests.isEmpty())
            assertOpen(controller.get())
        }
    }

    @Test
    fun anAcceptedRequestWithoutACompletionKeepsItsEditorOpenAcrossResume() {
        editor().use { controller ->
            val activity = controller.get()
            request(activity)

            assertOpen(activity)
            controller.topActivityResumed(false).pause().resume().topActivityResumed(true)

            assertOpen(activity)
            assertTrue(manager.getAppWidgetIds(provider(false)).isEmpty())
            assertNotNull(root(activity).findViewWithTag<View>("action-add-widget"))
        }
    }

    @Test
    fun aRejectedRequestCancelsItsCallbackAndKeepsTheEditorOpen() {
        PinRequestFixtures.CapturedPins.accepted = false
        editor().use { controller ->
            val pending = request(controller.get())

            assertOpen(controller.get())
            try {
                pending.callback.send()
                fail("A rejected request must release its callback")
            } catch (_: PendingIntent.CanceledException) {}
            assertTrue(manager.getAppWidgetIds(provider(false)).isEmpty())
        }
    }

    @Test
    fun aRequestFailureDoesNotCloseTheEditorOrRetainItsCallback() {
        for (failure in arrayOf(IllegalStateException(), SecurityException())) {
            PinRequestFixtures.CapturedPins.failure = failure
            editor().use { controller ->
                val pending = request(controller.get())

                assertOpen(controller.get())
                try {
                    pending.callback.send()
                    fail("A failed request must release its callback")
                } catch (_: PendingIntent.CanceledException) {}
            }
        }
    }

    @Test
    fun aFocusedOriginCompletesWithoutAnotherTopResumedCallback() {
        editor().use { controller ->
            val activity = controller.get()
            val pending = request(activity)
            controller.windowFocusChanged(false).topActivityResumed(false).pause()
            bind(36061, false)
            complete(pending, 36061)
            assertOpen(activity)
            controller.resume()
            assertOpen(activity)
            controller.windowFocusChanged(true)
            assertCompletedAtHome(activity)
        }
    }

    @Test
    fun aPausedOriginCompletesAtHomeWhenItBecomesTopResumed() {
        for (world in booleanArrayOf(false, true)) {
            val id = if (world) 36002 else 36001
            editor(world).use { controller ->
                val activity = controller.get()
                val pending = request(activity)
                controller.topActivityResumed(false).pause()
                bind(id, world)

                complete(pending, id)

                assertOpen(activity)
                assertTrue(WidgetSettingsStore.hasOwnSettings(context, id, world))
                controller.resume()
                assertOpen(activity)
                controller.topActivityResumed(true)
                assertCompletedAtHome(activity)
            }
        }
    }

    @Test
    fun aCompletionBetweenInstancesIsRestoredAndConsumedOnlyOnce() {
        val saved = Bundle()
        lateinit var pending: PinRequestFixtures.CapturedPin
        editor().use { controller ->
            pending = request(controller.get())
            controller.saveInstanceState(saved)
            controller.topActivityResumed(false)
        }
        bind(36011, false)
        complete(pending, 36011)
        val persisted = context.getSharedPreferences("noonlet_widgets", 0).all.toMap()

        editor(state = saved).use { restored ->
            assertCompletedAtHome(restored.get())
            assertEquals(persisted, context.getSharedPreferences("noonlet_widgets", 0).all)
        }

        editor(state = saved).use { replay ->
            assertOpen(replay.get())
            assertEquals(persisted, context.getSharedPreferences("noonlet_widgets", 0).all)
        }
    }

    @Test
    fun aLateCompletionDoesNotCloseASeparateForegroundEditor() {
        editor().use { origin ->
            val pending = request(origin.get())
            origin.topActivityResumed(false).pause()
            editor().use { unrelated ->
                bind(36021, false)

                complete(pending, 36021)

                assertOpen(unrelated.get())
                assertOpen(origin.get())
                assertTrue(WidgetSettingsStore.hasOwnSettings(context, 36021, false))
                unrelated.topActivityResumed(false)
            }
            origin.resume().topActivityResumed(true)
            assertCompletedAtHome(origin.get())
        }
    }

    @Test
    fun aCallbackWithoutAnAssignedIdDoesNotCompleteItsEditor() {
        editor().use { controller ->
            val pending = request(controller.get())

            pending.callback.send(context, 0, Intent())
            idle()

            assertOpen(controller.get())
            assertTrue(manager.getAppWidgetIds(provider(false)).isEmpty())
        }
    }

    @Test
    fun aRemovedCompletedWidgetDoesNotCloseItsReturningEditor() {
        editor().use { controller ->
            val pending = request(controller.get())
            controller.topActivityResumed(false).pause()
            bind(36031, false)
            complete(pending, 36031)
            val bindings =
                ShadowAppWidgetManager::class.java.getDeclaredField("widgetInfos").apply {
                    isAccessible = true
                }
            (bindings.get(Shadows.shadowOf(manager)) as MutableMap<*, *>).remove(36031)
            WidgetSettingsStore.delete(context, 36031, false)

            controller.resume().topActivityResumed(true)

            assertOpen(controller.get())
            assertFalse(WidgetSettingsStore.hasOwnSettings(context, 36031, false))
            assertNotNull(root(controller.get()).findViewWithTag<View>("action-add-widget"))
        }
    }

    @Test
    fun aResumedOriginKeepsItsCompletionUntilItRegainsTopResumedState() {
        editor().use { origin ->
            val pending = request(origin.get())
            origin.topActivityResumed(false)
            editor().use { sibling ->
                bind(36041, false)

                complete(pending, 36041)

                assertOpen(origin.get())
                assertOpen(sibling.get())
                assertTrue(WidgetSettingsStore.hasOwnSettings(context, 36041, false))
                sibling.topActivityResumed(false)
                origin.topActivityResumed(true)

                assertCompletedAtHome(origin.get())
                assertOpen(sibling.get())
            }
        }
    }

    @Test
    @Config(sdk = [28])
    fun anApi28EditorCompletesItsAcceptedPinOnResume() {
        editor().use { controller ->
            val activity = controller.get()
            val pending = request(activity)
            controller.pause()
            bind(36051, false)

            complete(pending, 36051)

            assertOpen(activity)
            controller.resume()
            assertCompletedAtHome(activity)
            assertTrue(WidgetSettingsStore.hasOwnSettings(context, 36051, false))
        }
    }

    private fun editor(
        world: Boolean = false,
        state: Bundle? = null,
    ): ActivityController<MainActivity> {
        context
            .getSharedPreferences("MainActivity", 0)
            .edit()
            .putBoolean("world_tab", world)
            .commit()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        return if (state == null) controller.setup().visible()
        else controller.setup(state).visible()
    }

    private fun request(activity: MainActivity): PinRequestFixtures.CapturedPin {
        idle()
        val add = checkNotNull(root(activity).findViewWithTag<View>("action-add-widget"))
        val previous = PinRequestFixtures.CapturedPins.requests.size
        assertTrue(add.performClick())
        assertEquals(previous + 1, PinRequestFixtures.CapturedPins.requests.size)
        return PinRequestFixtures.CapturedPins.requests.last()
    }

    private fun complete(pending: PinRequestFixtures.CapturedPin, id: Int) {
        pending.callback.send(
            context,
            0,
            Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
        )
        idle()
    }

    private fun bind(id: Int, world: Boolean, size: WidgetSize = WidgetSize.TWO_BY_ONE) {
        val info =
            AppWidgetProviderInfo().apply {
                provider = BaseWidgetProvider.component(context, world, size)
                initialLayout = if (world) R.layout.world_widget else R.layout.clock_widget
            }
        Shadows.shadowOf(manager).addInstalledProvider(info)
        Shadows.shadowOf(manager).addBoundWidget(id, info)
        manager.updateAppWidgetOptions(
            id,
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 167)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 167)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 82)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 82)
            },
        )
    }

    private fun provider(world: Boolean) =
        ComponentName(
            context,
            if (world) WorldWidgetProvider::class.java else ClockWidgetProvider::class.java,
        )

    private fun root(activity: MainActivity): View =
        activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

    private fun assertOpen(activity: MainActivity) {
        assertFalse(activity.isFinishing)
        assertFalse(Shadows.shadowOf(activity).isTaskMovedToBack)
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
    }

    private fun assertCompletedAtHome(activity: MainActivity) {
        PinRequestFixtures.assertReturnedToLauncher(activity)
    }

    private fun idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
    }
}

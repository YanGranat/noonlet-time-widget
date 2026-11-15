package app.noonlet

import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeatherEditorTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        for (name in listOf("noonlet_widgets", "MainActivity", "widget_drafts", "noonlet_app")) context
            .getSharedPreferences(name, 0)
            .edit()
            .clear()
            .commit()
        AppLocales.setLanguage(context, "en")
    }

    @Test
    fun returningFromAnotherEditorRefreshesTheSharedTemperatureUnitRow() {
        WidgetSettingsStore.saveUnplaced(context, false, WidgetSettings().apply {
            weatherEnabled = true
            weatherUseDevice = false
        })
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val original = layout(activity)
            original.findViewWithTag<View>("section-toggle:weather").performClick()
            val oldPreview = original.findViewWithTag<View>("preview-stage")
            controller.pause()
            AppPreferences(context).setTemperatureUnit(TemperatureUnit.FAHRENHEIT)
            controller.resume().visible()
            val current = layout(activity)
            assertEquals(AppLocales.text(context, R.string.temperature_fahrenheit),
                current.findViewWithTag<TextView>("temperature-unit:value").text.toString())
            assertSame(oldPreview, current.findViewWithTag<View>("preview-stage"))
            assertEquals(View.VISIBLE, current.findViewWithTag<View>("section-content:weather").visibility)
        }
    }

    @Test
    fun weatherIsOptionalAndOnlyAppearsInTheOrdinaryClockEditor() {
        EditorPreferences(context).selectPreviewSize(false, 1)
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val root = layout(activity)
            assertNotNull(root.findViewWithTag<View>("section:weather"))
            assertTrue(root.findViewWithTag<View>("preview-size:1").isSelected)
            root.findViewWithTag<View>("section-toggle:weather").performClick()
            val content = root.findViewWithTag<ViewGroup>("section-content:weather")
            assertEquals(1, switches(content).size)
            assertFalse(switches(content).single().isChecked)
            assertNull(root.findViewWithTag<View>("weather-status"))
            requireNotNull(findText(root, "World clock")).performClick()
            assertNull(layout(activity).findViewWithTag<View>("section:weather"))
            requireNotNull(findText(layout(activity), "Clock")).performClick()
            assertTrue(layout(activity).findViewWithTag<View>("preview-size:1").isSelected)
            controller.recreate()
            assertTrue(layout(controller.get()).findViewWithTag<View>("preview-size:1").isSelected)
        }
    }

    @Test
    fun cancelledWeatherActivationKeepsTheFeatureDisabledAndPreservesThePreview() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val root = layout(activity)
            val stage = root.findViewWithTag<View>("preview-stage")
            root.findViewWithTag<View>("section-toggle:weather").performClick()
            assertFalse(activity.session.settings.showAlarm)
            repeat(2) { attempt ->
                switches(root.findViewWithTag("section-content:weather")).single().isChecked = true
                val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
                assertNotNull(dialog.window!!.decorView.findViewWithTag<View>("weather-location-search"))
                assertFalse(activity.session.settings.weatherEnabled)
                if (attempt == 0) dialog.cancel()
                else dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertFalse(dialog.isShowing)
                assertFalse(activity.session.settings.weatherEnabled)
                assertFalse(switches(root.findViewWithTag("section-content:weather")).single().isChecked)
                assertSame(stage, root.findViewWithTag<View>("preview-stage"))
            }
        }
    }

    @Test
    fun manualWeatherActivationDoesNotRequestDeviceLocation() {
        val place = WeatherPlace("Oslo", 59913900, 10752200)
        WidgetSettingsStore.saveUnplaced(context, false, WidgetSettings().apply {
            weatherUseDevice = false
            weatherPlace = place.encode()
        })
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val root = layout(activity)
            root.findViewWithTag<View>("section-toggle:weather").performClick()
            switches(root.findViewWithTag("section-content:weather")).single().isChecked = true
            val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            dialog.window!!.decorView.findViewWithTag<View>("weather-location-selected").performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertFalse(dialog.isShowing)
            assertSame(dialog, ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(activity.session.settings.weatherEnabled)
            assertFalse(activity.session.settings.weatherUseDevice)
            assertEquals(place, WeatherPlace.decode(activity.session.settings.weatherPlace))
        }
    }

    @Test
    fun automaticWeatherExplainsLocationAccessWithoutReplacingTheEditorOrPreviewStage() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val root = layout(activity)
            val stage = root.findViewWithTag<View>("preview-stage")
            root.findViewWithTag<View>("section-toggle:weather").performClick()
            switches(root.findViewWithTag<ViewGroup>("section-content:weather"))
                .single()
                .isChecked = true
            val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(dialog.isShowing)
            assertNotNull(dialog.window!!.decorView.findViewWithTag<View>("weather-location-search"))
            assertFalse(activity.session.settings.weatherEnabled)
            dialog.window!!.decorView.findViewWithTag<View>("weather-location-device").performClick()
            val permissionDialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val message = permissionDialog.findViewById<TextView>(android.R.id.message).text.toString()
            assertTrue(message.contains("Approximate"))
            assertTrue(message.contains("Open-Meteo"))
            permissionDialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            assertSame(root, layout(activity))
            assertSame(stage, root.findViewWithTag<View>("preview-stage"))
            val session = activity.session
            assertTrue(session.settings.weatherEnabled)
            assertTrue(session.settings.weatherUseDevice)
            assertNull(root.findViewWithTag<View>("weather-status"))
            WidgetTestViews.reveal(root, "weather")
            val action = root.findViewWithTag<View>("weather-refresh")
            assertEquals(
                "Refresh weather data, Updates about every 30 minutes",
                action.contentDescription.toString(),
            )
            assertTrue(action.isClickable)
            assertFalse(action.isEnabled)
            val recovery = root.findViewWithTag<ViewGroup>("weather-recovery")
            assertEquals(View.VISIBLE, recovery.visibility)
            assertEquals("Location permission required", recovery.findViewWithTag<TextView>("weather-recovery-reason").text.toString())
            assertEquals("Location permission required, Open settings", recovery.contentDescription.toString())
            assertTrue(recovery.isEnabled && recovery.isClickable)
            assertTrue(recovery.performClick())
            val permissionSettings = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(permissionSettings.isShowing)
            permissionSettings.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val intent = requireNotNull(Shadows.shadowOf(activity).nextStartedActivity)
            assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
            assertEquals("package:app.noonlet", intent.dataString)
            assertTrue(
                action.findViewWithTag<View>("weather-refresh-icon") is android.widget.ImageView,
            )
            val observer = ReflectionHelpers.getField<Runnable>(activity, "weatherChanged")
            observer.run()
            assertSame(action, root.findViewWithTag<View>("weather-refresh"))
            assertSame(stage, root.findViewWithTag<View>("preview-stage"))
            root.findViewWithTag<View>("settings-trigger").performClick()
            val appSettingsIntent = requireNotNull(Shadows.shadowOf(activity).nextStartedActivity)
            val settingsController = Robolectric.buildActivity(AppSettingsActivity::class.java, appSettingsIntent).setup().visible()
            settingsController.get().findViewById<View>(android.R.id.content)
                .findViewWithTag<View>("data-privacy").performClick()
            val privacyIntent = requireNotNull(Shadows.shadowOf(settingsController.get()).nextStartedActivity)
            Robolectric.buildActivity(DataPrivacyActivity::class.java, privacyIntent).setup().visible().use { privacy ->
                val notices = privacy.get().findViewById<View>(android.R.id.content)
                    .findViewWithTag<TextView>("weather-data-sources")
                val urls = (notices.text as android.text.Spanned)
                    .getSpans(0, notices.text.length, android.text.style.URLSpan::class.java)
                    .map { it.url }
                assertTrue(urls.contains("https://open-meteo.com"))
                assertTrue(urls.contains("https://www.geonames.org"))
                assertTrue(urls.contains("https://creativecommons.org/licenses/by/4.0"))
            }
            settingsController.close()
            assertSame(root, layout(activity))
            assertSame(stage, root.findViewWithTag<View>("preview-stage"))
        }
    }

    @Test
    fun launcherConfirmationSchedulesOnlyAnEnabledWeatherConfiguration() {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val provider =
            android.appwidget.AppWidgetProviderInfo().apply {
                this.provider =
                    android.content.ComponentName(context, ClockWidgetProvider::class.java)
                initialLayout = R.layout.clock_widget
            }
        Shadows.shadowOf(manager).addInstalledProvider(provider)
        for ((index, enabled) in listOf(false, true).withIndex()) {
            val id = 7100 + index
            Shadows.shadowOf(manager).addBoundWidget(id, provider)
            WidgetSettingsStore.saveUnplaced(
                context,
                false,
                WidgetSettings().apply {
                    weatherEnabled = enabled
                },
            )
            val intent =
                android.content
                    .Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                    .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            Robolectric.buildActivity(MainActivity::class.java, intent).setup().visible().use {
                controller ->
                val root = layout(controller.get())
                val scheduler = context.getSystemService(android.app.job.JobScheduler::class.java)
                scheduler.cancelAll()
                assertTrue(
                    requireNotNull(findText(root, context.getString(R.string.add_to_home_screen)))
                        .performClick(),
                )
                assertEquals(enabled, WidgetSettingsStore.load(context, id, false).weatherEnabled)
                assertEquals(
                    if (enabled) 2 else 0,
                    scheduler.allPendingJobs.count {
                        it.service ==
                            android.content.ComponentName(
                                context,
                                WeatherRefreshJobService::class.java,
                            )
                    },
                )
            }
        }
    }

    @Test
    fun refreshInsideTheWeatherSectionFetchesBeforeExpiryAndShowsPendingWork() {
        val previous = WeatherRepository.store
        val work = ArrayDeque<Runnable>()
        var calls = 0
        var fail = false
        var ageOffset = 0L
        val settings =
            WidgetSettings().apply {
                weatherEnabled = true
                weatherUseDevice = false
                weatherPlace =
                    WeatherPlace("Sample place, Sample region", 12_345_678, 45_678_901).encode()
            }
        val request = WeatherRequest.capture(settings)
        val store =
            WeatherStore(
                context,
                java.util.concurrent.Executor { work.add(it) },
                weatherClient {
                    calls++
                    if (fail) throw java.io.IOException("Unavailable")
                    weatherResponse()
                },
                now = { System.currentTimeMillis() + ageOffset },
                cachedLocation = { null },
                permission = { true },
                changed = {
                    WeatherRepository.notifyChanged()
                },
            )
        WeatherRepository.store = store
        WidgetSettingsStore.saveUnplaced(context, false, settings)
        try {
            Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller
                ->
                val activity = controller.get()
                val root = layout(activity)
                assertEquals(1, work.size)
                work.removeFirst().run()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, calls)
                assertEquals(WeatherStatus.FRESH, store.state(request).status)
                val heading = root.findViewWithTag<ViewGroup>("section-toggle:weather")
                val content = root.findViewWithTag<View>("section-content:weather")
                assertEquals(View.GONE, content.visibility)
                heading.performClick()
                layout(activity)
                val action = root.findViewWithTag<View>("weather-refresh")
                assertNull(heading.findViewWithTag<View>("weather-refresh"))
                assertSame(content, action.parent)
                assertEquals(View.VISIBLE, content.visibility)
                assertEquals("Updates about every 30 minutes",
                    action.findViewWithTag<TextView>("weather-refresh-interval").text.toString())
                val recovery = root.findViewWithTag<ViewGroup>("weather-recovery")
                assertEquals(View.GONE, recovery.visibility)
                assertTrue(action.isEnabled)
                assertEquals(content.width, action.width)
                assertTrue(action.height >= 48)
                val down =
                    android.view.MotionEvent.obtain(
                        0,
                        0,
                        android.view.MotionEvent.ACTION_DOWN,
                        action.x + action.width / 2f,
                        action.y + action.height / 2f,
                        0,
                    )
                val up =
                    android.view.MotionEvent.obtain(
                        0,
                        1,
                        android.view.MotionEvent.ACTION_UP,
                        action.x + action.width / 2f,
                        action.y + action.height / 2f,
                        0,
                    )
                try {
                    assertTrue(content.dispatchTouchEvent(down))
                    assertTrue(content.dispatchTouchEvent(up))
                } finally {
                    down.recycle()
                    up.recycle()
                }
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(View.VISIBLE, content.visibility)
                assertEquals(1, work.size)
                assertFalse(action.isEnabled)
                assertEquals(
                    View.VISIBLE,
                    action.findViewWithTag<View>("weather-refresh-progress").visibility,
                )
                assertEquals(
                    View.GONE,
                    action.findViewWithTag<View>("weather-refresh-icon").visibility,
                )
                work.removeFirst().run()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(2, calls)
                assertTrue(action.isEnabled)
                assertEquals(
                    View.GONE,
                    action.findViewWithTag<View>("weather-refresh-progress").visibility,
                )
                assertEquals(
                    View.VISIBLE,
                    action.findViewWithTag<View>("weather-refresh-icon").visibility,
                )
                assertNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
                val retained = store.state(request).snapshot
                fail = true
                assertTrue(action.performClick())
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertFalse(action.isEnabled)
                assertEquals(1, work.size)
                work.removeFirst().run()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(3, calls)
                assertEquals(WeatherStatus.STALE, store.state(request).status)
                assertEquals(retained, store.state(request).snapshot)
                assertTrue(action.isEnabled)
                assertEquals(
                    View.GONE,
                    action.findViewWithTag<View>("weather-refresh-progress").visibility,
                )
                assertEquals(
                    View.VISIBLE,
                    action.findViewWithTag<View>("weather-refresh-icon").visibility,
                )
                assertEquals(
                    context.getString(R.string.weather_unavailable),
                    org.robolectric.shadows.ShadowToast.getTextOfLatestToast(),
                )
                assertNull(root.findViewWithTag<View>("weather-status"))
                assertEquals(View.VISIBLE, recovery.visibility)
                assertEquals("Could not refresh weather data", recovery.findViewWithTag<TextView>("weather-recovery-reason").text.toString())
                assertTrue(heading.contentDescription.toString().contains("Could not refresh weather data"))
                layout(activity)
                EditorTestInteractions.bringIntoView(root, action)
                val scroll = requireNotNull(EditorTestInteractions.first(root, android.widget.ScrollView::class.java))
                val position = scroll.scrollY
                val height = recovery.height
                val stage = root.findViewWithTag<View>("preview-stage")
                val chevron = recovery.findViewWithTag<View>("weather-recovery-chevron")
                assertEquals(View.GONE, chevron.visibility)
                assertFalse(recovery.isClickable)
                assertTrue(action.performClick())
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                layout(activity)
                assertFalse(recovery.isClickable)
                assertFalse(action.isEnabled)
                assertSame(recovery, root.findViewWithTag<View>("weather-recovery"))
                assertEquals(View.VISIBLE, recovery.visibility)
                assertEquals(height, recovery.height)
                assertEquals(position, scroll.scrollY)
                assertSame(stage, root.findViewWithTag<View>("preview-stage"))
                assertEquals(1, work.size)
                work.removeFirst().run()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                layout(activity)
                assertEquals(4, calls)
                assertTrue(action.isEnabled)
                assertEquals(height, recovery.height)
                assertEquals(position, scroll.scrollY)
                fail = false
                assertTrue(action.performClick())
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, work.size)
                work.removeFirst().run()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(5, calls)
                assertEquals(WeatherStatus.FRESH, store.state(request).status)
                assertNull(store.state(request).failureStatus)
                assertEquals(View.GONE, recovery.visibility)
                ageOffset = 46 * 60 * 1000L
                WeatherRepository.notifyChanged()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(WeatherStatus.STALE, store.state(request).status)
                assertNull(store.state(request).failureStatus)
                assertEquals(View.GONE, recovery.visibility)
                org.robolectric.shadows.ShadowToast.reset()
                assertTrue(action.performClick())
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(1, work.size)
                work.removeFirst().run()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(6, calls)
                assertEquals(WeatherStatus.STALE, store.state(request).status)
                assertNull(store.state(request).failureStatus)
                assertNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
                assertEquals(View.GONE, recovery.visibility)
            }
        } finally {
            WeatherRepository.store = previous
            java.io.File(context.noBackupFilesDir, "weather_cache.json").delete()
        }
    }

    @Test
    fun disabledDeviceLocationHasAnExplicitSettingsActionAndKeepsThePreview() {
        val previous = WeatherRepository.store
        val store = WeatherStore(
            context,
            java.util.concurrent.Executor { it.run() },
            locate = { java.util.concurrent.CompletableFuture<WeatherPlace>().apply {
                completeExceptionally(WeatherFailure(WeatherStatus.LOCATION_DISABLED))
            } },
            cachedLocation = { null },
            permission = { true },
            changed = { WeatherRepository.notifyChanged() },
        )
        WeatherRepository.store = store
        WidgetSettingsStore.saveUnplaced(context, false, WidgetSettings().apply { weatherEnabled = true })
        try {
            Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
                val activity = controller.get()
                val root = layout(activity)
                val stage = root.findViewWithTag<View>("preview-stage")
                WidgetTestViews.reveal(root, "weather")
                assertFalse(root.findViewWithTag<View>("weather-refresh").isEnabled)
                val recovery = root.findViewWithTag<ViewGroup>("weather-recovery")
                assertEquals(View.VISIBLE, recovery.visibility)
                assertEquals("Device location is turned off", recovery.findViewWithTag<TextView>("weather-recovery-reason").text.toString())
                EditorTestInteractions.bringIntoView(root, recovery)
                assertTrue(recovery.performClick())
                val intent = requireNotNull(Shadows.shadowOf(activity).nextStartedActivity)
                assertEquals(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS, intent.action)
                assertSame(stage, root.findViewWithTag<View>("preview-stage"))
                assertNotNull(root.findViewWithTag<View>("weather-location-mode"))
                assertNull(root.findViewWithTag<View>("weather-background-status"))
            }
        } finally {
            WeatherRepository.store = previous
        }
    }

    @Test
    fun weatherRecoveryUsesOneFullWidthActionWithACenteredChevron() {
        val originalScale = RuntimeEnvironment.getFontScale()
        try {
            for ((language, scale) in listOf("en" to 1f, "de" to 1.3f, "ru" to 1.3f)) {
                AppLocales.setLanguage(context, language)
                RuntimeEnvironment.setFontScale(scale)
                WidgetSettingsStore.saveUnplaced(context, false, WidgetSettings().apply { weatherEnabled = true })
                Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
                    val activity = controller.get()
                    var root = layout(activity)
                    if (root.findViewWithTag<View>("section-content:weather").visibility != View.VISIBLE)
                        root.findViewWithTag<View>("section-toggle:weather").performClick()
                    root = layout(activity, if (language == "en") 400 else 360)
                    val row = root.findViewWithTag<android.widget.LinearLayout>("weather-recovery")
                    val reason = row.findViewWithTag<TextView>("weather-recovery-reason")
                    val chevron = row.findViewWithTag<View>("weather-recovery-chevron")
                    val stage = root.findViewWithTag<View>("preview-stage")
                    assertEquals(View.VISIBLE, row.visibility)
                    assertTrue(row.height >= 48)
                    assertEquals(2, row.childCount)
                    assertTrue(row.isClickable && row.isFocusable)
                    assertFalse(chevron.isClickable)
                    assertEquals(row.width - row.paddingRight, chevron.right)
                    assertTrue(reason.right + 8 <= chevron.left)
                    assertTrue(kotlin.math.abs(chevron.top + chevron.bottom - row.height) <= 1)
                    for (line in 0 until reason.lineCount) {
                        assertEquals(0, reason.layout.getEllipsisCount(line))
                        val end = reason.layout.getLineEnd(line)
                        if (end in 1 until reason.text.length) assertTrue(
                            reason.text[end - 1].isWhitespace() || reason.text[end].isWhitespace(),
                        )
                    }
                    val height = row.height
                    layout(activity, if (language == "en") 400 else 360)
                    assertEquals(height, row.height)
                    assertSame(stage, root.findViewWithTag<View>("preview-stage"))
                    if (language != "en") {
                        layout(activity, 1000)
                        layout(activity, 360)
                        assertEquals(height, row.height)
                    }
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(originalScale)
        }
    }

    @Test
    fun aFailedRefreshFromAPausedSessionDoesNotToastAfterResume() {
        ManualRefreshFixture().use { fixture ->
            Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
                val activity = controller.get()
                var root = layout(activity)
                fixture.runRequest()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                val before = ReflectionHelpers.getField<WeatherLocation.ForegroundSession>(activity, "weatherForeground")
                org.robolectric.shadows.ShadowToast.reset()
                WidgetTestViews.reveal(root, "weather")
                assertTrue(root.findViewWithTag<View>("weather-refresh").performClick())
                controller.pause()
                assertFalse(before.isActive)
                fixture.currentTime += WeatherSnapshot.REFRESH_INTERVAL + 1
                fixture.fail = true
                fixture.runRequest()
                assertEquals(WeatherStatus.STALE, fixture.store.state(fixture.request).status)
                assertEquals(WeatherStatus.UNAVAILABLE, fixture.store.state(fixture.request).failureStatus)
                assertNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
                controller.resume()
                val after = ReflectionHelpers.getField<WeatherLocation.ForegroundSession>(activity, "weatherForeground")
                assertNotSame(before, after)
                assertTrue(after.isActive)
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                root = layout(activity)
                assertEquals(WeatherStatus.LOADING, fixture.store.state(fixture.request).status)
                assertEquals(WeatherStatus.UNAVAILABLE, fixture.store.state(fixture.request).failureStatus)
                assertEquals(View.VISIBLE, root.findViewWithTag<View>("weather-recovery").visibility)
                assertNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
                fixture.fail = false
                fixture.runRequest()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(WeatherStatus.FRESH, fixture.store.state(fixture.request).status)
                assertNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            }
        }
    }

    @Test
    fun aLateDraftRefreshDoesNotToastAfterAdoptingAPlacedWidgetAtTheSameLocation() {
        ManualRefreshFixture().use { fixture ->
            Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
                val activity = controller.get()
                val root = layout(activity)
                fixture.runRequest()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                val foreground = ReflectionHelpers.getField<WeatherLocation.ForegroundSession>(activity, "weatherForeground")
                assertEquals(0, activity.session.target.id)
                org.robolectric.shadows.ShadowToast.reset()
                WidgetTestViews.reveal(root, "weather")
                assertTrue(root.findViewWithTag<View>("weather-refresh").performClick())
                bindClock(7412, fixture.settings)
                val snapshot = android.os.Bundle().apply { SettingsCodec.writeEditorState(fixture.settings, this) }
                WidgetPinReceiver().onReceive(
                    context,
                    android.content.Intent()
                        .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, 7412)
                        .putExtra("world", false)
                        .putExtra("widget_snapshot", snapshot),
                )
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                val session = activity.session
                assertEquals(7412, session.target.id)
                assertEquals(fixture.request, WeatherRequest.capture(session.settings))
                assertSame(foreground, ReflectionHelpers.getField<WeatherLocation.ForegroundSession>(activity, "weatherForeground"))
                assertTrue(foreground.isActive)
                fixture.fail = true
                fixture.runRequest()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(WeatherStatus.UNAVAILABLE, fixture.store.state(fixture.request).failureStatus)
                assertEquals(View.VISIBLE, layout(activity).findViewWithTag<View>("weather-recovery").visibility)
                assertNull(org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
            }
        }
    }

    @Test
    fun returningFromAppSettingsUsesCachedWeatherUntilANewForegroundVisit() {
        ManualRefreshFixture().use { fixture ->
            Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
                val activity = controller.get()
                fixture.runRequest()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertTrue(layout(activity).findViewWithTag<View>("settings-trigger").performClick())
                controller.pause()
                fixture.currentTime += WeatherSnapshot.REFRESH_INTERVAL + 1
                controller.resume().visible()
                layout(activity)
                assertEquals(0, fixture.pendingWork)
                assertEquals(WeatherStatus.STALE, fixture.store.state(fixture.request).status)
                controller.pause().resume().visible()
                assertEquals(1, fixture.pendingWork)
                fixture.runRequest()
            }
        }
    }

    @Test
    fun changingWeatherUnitsUsesCachedDataForPreviewAndPlacedWidgets() {
        ManualRefreshFixture().use { fixture ->
            val id = 7431
            bindClock(id, fixture.settings)
            EditorPreferences(context).selectTarget(id, false)
            Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
                val activity = controller.get()
                fixture.runRequest()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                val snapshot = fixture.store.state(fixture.request).snapshot
                assertNotNull(snapshot)
                layout(activity).findViewWithTag<View>("section-toggle:weather").performClick()
                for ((index, unit, expected) in listOf(
                    Triple(1, TemperatureUnit.FAHRENHEIT, "55°F"),
                    Triple(0, TemperatureUnit.CELSIUS, "13°C"),
                )) {
                    val current = layout(activity)
                    assertTrue(current.findViewWithTag<View>("temperature-unit").performClick())
                    val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
                    val choices = dialog.listView
                    assertTrue(choices.performItemClick(choices.adapter.getView(index, null, choices), index, index.toLong()))
                    assertFalse(dialog.isShowing)
                    WeatherRefreshScheduler.updateWidgets(context).get(10, java.util.concurrent.TimeUnit.SECONDS)
                    val updated = layout(activity)
                    assertEquals(unit, AppPreferences(context).temperatureUnit())
                    val preview = updated.findViewWithTag<WidgetPreviewStage>("preview-stage")
                    assertTrue(preview.getWidgetContent()!!.findViewById<TextView>(R.id.weather_caption)
                        .text.toString().contains(expected))
                    val placed = Shadows.shadowOf(android.appwidget.AppWidgetManager.getInstance(context)).getViewFor(id)
                    assertTrue(placed.findViewById<TextView>(R.id.weather_caption).text.toString().contains(expected))
                    assertSame(snapshot, fixture.store.state(fixture.request).snapshot)
                    assertEquals(0, fixture.pendingWork)
                }
            }
        }
    }

    @Test
    fun clearingWeatherInSettingsSurvivesReturnAndExplicitRefreshCanDownloadAgain() {
        ManualRefreshFixture().use { fixture ->
            Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
                val activity = controller.get()
                fixture.runRequest()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertTrue(layout(activity).findViewWithTag<View>("settings-trigger").performClick())
                controller.pause()
                val clearing = WeatherRefreshScheduler.clearForecastCache(context)
                fixture.runRequest()
                clearing.get(10, java.util.concurrent.TimeUnit.SECONDS)
                controller.resume().visible()
                val root = layout(activity)
                assertEquals(0, fixture.pendingWork)
                assertNull(fixture.store.state(fixture.request).snapshot)
                WidgetTestViews.reveal(root, "weather")
                assertTrue(root.findViewWithTag<View>("weather-refresh").performClick())
                assertEquals(1, fixture.pendingWork)
                fixture.runRequest()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(WeatherStatus.FRESH, fixture.store.state(fixture.request).status)
            }
        }
    }

    @Test
    fun newlyGrantedLocationPermissionRefreshesOnReturnUnlessDataWasAlsoCleared() {
        val app = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        for (clear in listOf(false, true)) {
            app.denyPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION,
                android.Manifest.permission.ACCESS_FINE_LOCATION)
            ManualRefreshFixture().use { fixture ->
                Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
                    val activity = controller.get()
                    fixture.runRequest()
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    assertTrue(layout(activity).findViewWithTag<View>("settings-trigger").performClick())
                    controller.pause()
                    fixture.currentTime += WeatherSnapshot.REFRESH_INTERVAL + 1
                    if (clear) {
                        val clearing = WeatherRefreshScheduler.clearForecastCache(context)
                        fixture.runRequest()
                        clearing.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    }
                    app.grantPermissions(android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    controller.resume().visible()
                    layout(activity)
                    assertEquals(if (clear) 0 else 1, fixture.pendingWork)
                    if (!clear) fixture.runRequest()
                }
            }
        }
    }

    private inner class ManualRefreshFixture : java.io.Closeable {
        private val previous = WeatherRepository.store
        private val work = ArrayDeque<Runnable>()
        var fail = false
        var currentTime = System.currentTimeMillis()
        val pendingWork get() = work.size
        val settings = WidgetSettings().apply {
            weatherEnabled = true
            weatherUseDevice = false
            weatherPlace = WeatherPlace("North Bay", 31_234_567, -71_234_567).encode()
        }
        val request = WeatherRequest.capture(settings)
        val store = WeatherStore(
            context,
            java.util.concurrent.Executor { work.add(it) },
            weatherClient { if (fail) throw java.io.IOException("Unavailable") else weatherResponse(currentTime) },
            now = { currentTime },
            cachedLocation = { null },
            permission = { true },
            changed = { WeatherRepository.notifyChanged() },
        )

        init {
            java.io.File(context.noBackupFilesDir, "weather_cache.json").delete()
            WeatherRepository.store = store
            WidgetSettingsStore.saveUnplaced(context, false, settings)
        }

        fun runRequest() {
            assertEquals(1, work.size)
            work.removeFirst().run()
        }

        override fun close() {
            try {
                WeatherRefreshScheduler.updateWidgets(context).get(10, java.util.concurrent.TimeUnit.SECONDS)
            } finally {
                try {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                } finally {
                    WeatherRepository.store = previous
                    java.io.File(context.noBackupFilesDir, "weather_cache.json").delete()
                }
            }
        }
    }

    @Test
    fun openingTheWorldEditorRefreshesPlacedDeviceWeatherAndOwnsItsForegroundSession() {
        verifyWorldWeatherRefresh(placed = true)
    }

    @Test
    fun switchingFromWorldToAnUnplacedClockRefreshesItsSavedWeatherDraft() {
        verifyWorldWeatherRefresh(placed = false)
    }

    private fun verifyWorldWeatherRefresh(placed: Boolean) {
        val previous = WeatherRepository.store
        val work = ArrayDeque<Runnable>()
        var acquisitions = 0
        val sessions = mutableListOf<WeatherLocation.ForegroundSession>()
        val place = WeatherPlace("Riverton", 12_345_678, 45_678_901)
        val store = WeatherStore(
            context,
            java.util.concurrent.Executor { work.add(it) },
            weatherClient { weatherResponse() },
            locate = { foreground ->
                val active = requireNotNull(foreground)
                assertTrue(active.isActive)
                sessions += active
                acquisitions++
                java.util.concurrent.CompletableFuture.completedFuture(place)
            },
            cachedLocation = { null },
            permission = { true },
            changed = { WeatherRepository.notifyChanged() },
        )
        val settings = WidgetSettings().apply { weatherEnabled = true }
        WeatherRepository.store = store
        WidgetSettingsStore.saveUnplaced(context, false, settings)
        if (placed) bindClock(7301, settings)
        context.getSharedPreferences("MainActivity", 0).edit().putBoolean("world_tab", true).commit()
        try {
            Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
                val activity = controller.get()
                var root = layout(activity)
                assertNull(root.findViewWithTag<View>("section:weather"))
                assertTrue(activity.session.world)
                assertEquals(if (placed) 1 else 0, acquisitions)
                if (!placed) {
                    assertEquals(0, work.size)
                    assertTrue(requireNotNull(findText(root, "Clock")).performClick())
                    root = layout(activity)
                    assertNotNull(root.findViewWithTag<View>("section:weather"))
                    assertEquals(1, acquisitions)
                }
                assertEquals(1, work.size)
                work.removeFirst().run()
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertEquals(WeatherStatus.FRESH, store.state(WeatherRequest.capture(settings)).status)
                assertEquals(place, store.state(WeatherRequest.capture(settings)).place)
                WeatherRefreshScheduler.updateWidgets(context).get(10, java.util.concurrent.TimeUnit.SECONDS)
                val session = sessions.single()
                assertTrue(session.isActive)
                controller.pause()
                assertFalse(session.isActive)
                assertNull(ReflectionHelpers.getField<WeatherLocation.ForegroundSession?>(activity, "weatherForeground"))
            }
        } finally {
            WeatherRepository.store = previous
            java.io.File(context.noBackupFilesDir, "weather_cache.json").delete()
        }
    }

    private fun bindClock(id: Int, settings: WidgetSettings) {
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val provider = android.appwidget.AppWidgetProviderInfo().apply {
            this.provider = android.content.ComponentName(context, ClockWidgetProvider::class.java)
            initialLayout = R.layout.clock_widget
        }
        Shadows.shadowOf(manager).addInstalledProvider(provider)
        Shadows.shadowOf(manager).addBoundWidget(id, provider)
        WidgetSettingsStore.save(context, id, false, settings)
    }

    private fun weatherResponse(now: Long = System.currentTimeMillis()): String = """{
        "timezone":"GMT","utc_offset_seconds":0,
        "current_units":{"time":"unixtime","interval":"seconds","temperature_2m":"°C","weather_code":"wmo code","is_day":""},
        "current":{"time":${now / 1000},"interval":900,"temperature_2m":12.5,"weather_code":2,"is_day":1}
    }"""

    private fun layout(activity: MainActivity, width: Int = 400): View {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, 850)
        return root
    }

    private fun switches(view: View): List<Switch> =
        when (view) {
            is Switch -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { switches(view.getChildAt(it)) }
            else -> emptyList()
        }

    private fun findText(view: View, value: String): TextView? {
        if (view is TextView && view.text.toString() == value) return view
        if (view is ViewGroup)
            for (i in 0 until view.childCount) findText(view.getChildAt(i), value)?.let {
                return it
            }
        return null
    }
}

package app.noonlet

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfigureFooterTest {
    private lateinit var context: Context
    private var manager: AppWidgetManager? = null

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        PinRequestFixtures.installHome(context)
        manager = AppWidgetManager.getInstance(context)
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit()
        context.getSharedPreferences("widget_drafts", 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
    }

    @Test
    @Throws(Exception::class)
    fun initialConfigurationAlreadyHasABoundIdButConfirmsAddingOnlyThatId() {
        for (world: Boolean in booleanArrayOf(false, true)) {
            val id: Int = if (world) 1802 else 1801
            WidgetSettingsStore.save(context, 0, world, WidgetSettings.defaults(world))
            bind(id, world)
            assertEquals(
                1,
                manager!!
                    .getAppWidgetIds(
                        ComponentName(
                            context,
                            if (world) WorldWidgetProvider::class.java
                            else ClockWidgetProvider::class.java,
                        ),
                    )
                    .size,
            )
            assertFalse(WidgetSettingsStore.hasOwnSettings(context, id, world))
            val request: Intent = configure(id)
            // Some launchers use an explicit component without copying the public action.
            if (world) request.setAction(null)
            Robolectric.buildActivity(MainActivity::class.java, request)
                .setup()
                .visible()
                .use(
                    { c ->
                        val activity: MainActivity = c.get()
                        assertEquals(
                            "Add to home screen",
                            checkNotNull(footer(activity)).getText().toString(),
                        )
                        assertEquals(
                            Activity.RESULT_CANCELED,
                            Shadows.shadowOf(activity).getResultCode(),
                        )
                        settings(activity).showDate = false
                        tapFooter(activity)
                        assertEquals(Activity.RESULT_OK, Shadows.shadowOf(activity).getResultCode())
                        assertEquals(
                            id,
                            Shadows.shadowOf(activity)
                                .getResultIntent()
                                .getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1),
                        )
                        assertTrue(activity.isFinishing())
                        assertTrue(WidgetSettingsStore.hasOwnSettings(context, id, world))
                        assertFalse(WidgetSettingsStore.load(context, id, world).showDate)
                        assertTrue(WidgetSettingsStore.load(context, 0, world).showDate)
                        val host: View = Shadows.shadowOf(manager).getViewFor(id)
                        assertNotNull(host)
                        assertEquals(
                            View.GONE,
                            host
                                .findViewById<View>(
                                    if (world) R.id.city_date else R.id.clock_date_row,
                                )
                                .getVisibility(),
                        )
                        assertEquals(
                            1,
                            manager!!
                                .getAppWidgetIds(
                                    ComponentName(
                                        context,
                                        if (world) WorldWidgetProvider::class.java
                                        else ClockWidgetProvider::class.java,
                                    ),
                                )
                                .size,
                        )
                    },
                )
        }
    }

    @Test
    @Throws(Exception::class)
    fun freshSizesAreSavedWhileLegacySizesOnlyChangeThroughTheirIndividualResets() {
        bind(1861, false)
        Robolectric.buildActivity(MainActivity::class.java, configure(1861))
            .setup()
            .visible()
            .use(
                { c ->
                    assertEquals(56, settings(c.get()).textSize)
                    assertEquals(16, settings(c.get()).dateSize)
                    tapFooter(c.get())
                },
            )
        assertEquals(56, WidgetSettingsStore.load(context, 1861, false).textSize)
        assertEquals(16, WidgetSettingsStore.load(context, 1861, false).dateSize)
        assertFalse(context.getSharedPreferences("noonlet_widgets", 0).contains("clock.0.saved"))
        val legacy: WidgetSettings = WidgetSettings()
        legacy.textSize = 52
        legacy.dateSize = 14
        WidgetSettingsStore.save(context, 1862, false, legacy)
        bind(1862, false)
        Robolectric.buildActivity(MainActivity::class.java, configure(1862))
            .setup()
            .visible()
            .use(
                { c ->
                    val activity: MainActivity = c.get()
                    assertEquals(52, settings(activity).textSize)
                    assertEquals(14, settings(activity).dateSize)
                    assertTrue(
                        layout(activity)
                            .findViewWithTag<View>("section-toggle:time")
                            .performClick(),
                    )
                    assertTrue(
                        layout(activity)
                            .findViewWithTag<View>("slider-reset:Time size")
                            .performClick(),
                    )
                    assertEquals(56, settings(activity).textSize)
                    assertEquals(14, settings(activity).dateSize)
                    layout(activity)
                    assertEquals(56, WidgetSettingsStore.load(context, 1862, false).textSize)
                    assertTrue(
                        layout(activity)
                            .findViewWithTag<View>("section-toggle:date")
                            .performClick(),
                    )
                    assertTrue(
                        layout(activity)
                            .findViewWithTag<View>("slider-reset:Date size")
                            .performClick(),
                    )
                    assertEquals(56, settings(activity).textSize)
                    assertEquals(16, settings(activity).dateSize)
                    layout(activity)
                    assertEquals(16, WidgetSettingsStore.load(context, 1862, false).dateSize)
                    assertNull(footer(activity))
                },
            )
        assertEquals(56, WidgetSettingsStore.load(context, 1862, false).textSize)
        assertEquals(16, WidgetSettingsStore.load(context, 1862, false).dateSize)
    }

    @Test
    fun existingConfigurationAndDirectSettingsEntryOfferSaveChanges() {
        for (world: Boolean in booleanArrayOf(false, true)) {
            val savedId: Int = if (world) 1812 else 1811
            val unsavedId: Int = if (world) 1822 else 1821
            val configured: WidgetSettings = WidgetSettings.defaults(world)
            configured.tapAction = "none"
            WidgetSettingsStore.save(context, savedId, world, configured)
            bind(savedId, world)
            Robolectric.buildActivity(MainActivity::class.java, configure(savedId))
                .setup()
                .use(
                    { c ->
                        assertNull(footer(c.get()))
                        val save = layout(c.get()).findViewWithTag<TextView>("action-save-changes")
                        assertNotNull(save)
                        assertEquals("Save changes", save.text.toString())
                    },
                )
            bind(unsavedId, world)
            val direct: Intent =
                Intent(context, MainActivity::class.java)
                    .setData(Uri.parse("noonlet://edit/" + world + "/" + unsavedId))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, unsavedId)
                    .putExtra("world", world)
            Robolectric.buildActivity(MainActivity::class.java, direct)
                .setup()
                .use(
                    { c ->
                        assertFalse(WidgetSettingsStore.hasOwnSettings(context, unsavedId, world))
                        assertNull(footer(c.get()))
                        val save = layout(c.get()).findViewWithTag<TextView>("action-save-changes")
                        assertNotNull(save)
                        assertEquals("Save changes", save.text.toString())
                        assertTrue(save.performClick())
                        assertTrue(WidgetSettingsStore.hasOwnSettings(context, unsavedId, world))
                        assertNotNull(Shadows.shadowOf(manager).getViewFor(unsavedId))
                        assertEquals(Activity.RESULT_OK, Shadows.shadowOf(c.get()).getResultCode())
                        assertEquals(
                            unsavedId,
                            Shadows.shadowOf(c.get())
                                .getResultIntent()
                                .getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1),
                        )
                        assertTrue(c.get().isFinishing)
                    },
                )
            assertEquals("none", WidgetSettingsStore.load(context, savedId, world).tapAction)
        }
    }

    @Test
    fun initialConfigurationReturnsToItsCallerOrHome() {
        for (hasCaller in listOf(false, true)) for (publicAction in listOf(false, true)) {
            val id = 1861 + (if (hasCaller) 2 else 0) + (if (publicAction) 1 else 0)
            bind(id, false)
            val request = configure(id).apply { if (!publicAction) action = null }
            Robolectric.buildActivity(MainActivity::class.java, request).setup().visible().use { controller ->
                val activity = controller.get()
                val shadow = Shadows.shadowOf(activity)
                if (hasCaller) shadow.setCallingActivity(ComponentName("example.launcher", "example.launcher.Home"))
                tapFooter(activity)
                assertEquals(Activity.RESULT_OK, shadow.resultCode)
                assertTrue(activity.isFinishing)
                if (hasCaller) {
                    assertNull(shadow.nextStartedActivity)
                    assertFalse(shadow.isTaskMovedToBack)
                } else PinRequestFixtures.assertReturnedToLauncher(activity)
            }
        }
    }

    @Test
    fun configurationStageSurvivesRotationInsteadOfBeingReinferredFromChangedPreferences() {
        bind(1831, false)
        Robolectric.buildActivity(MainActivity::class.java, configure(1831))
            .setup()
            .visible()
            .use(
                { c ->
                    assertEquals(
                        "Add to home screen",
                        checkNotNull(footer(c.get())).getText().toString(),
                    )
                    WidgetSettingsStore.save(context, 1831, false, WidgetSettings())
                    c.recreate()
                    assertEquals(
                        "Add to home screen",
                        checkNotNull(footer(c.get())).getText().toString(),
                    )
                },
            )
        WidgetSettingsStore.save(context, 1832, false, WidgetSettings())
        bind(1832, false)
        Robolectric.buildActivity(MainActivity::class.java, configure(1832))
            .setup()
            .visible()
            .use(
                { c ->
                    assertNull(footer(c.get()))
                    WidgetSettingsStore.delete(context, 1832, false)
                    c.recreate()
                    assertNull(footer(c.get()))
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun cancellingInitialConfigurationDoesNotSaveOrAcceptTheWidget() {
        bind(1841, false)
        val activity: MainActivity
        Robolectric.buildActivity(MainActivity::class.java, configure(1841))
            .setup()
            .use(
                { c ->
                    activity = c.get()
                    settings(activity).timeOffsetX = 19
                    assertEquals(
                        "Add to home screen",
                        checkNotNull(footer(activity)).getText().toString(),
                    )
                    activity.finish()
                },
            )
        assertEquals(Activity.RESULT_CANCELED, Shadows.shadowOf(activity).getResultCode())
        assertFalse(WidgetSettingsStore.hasOwnSettings(context, 1841, false))
        assertEquals(0, WidgetSettingsStore.load(context, 1841, false).timeOffsetX)
    }

    @Test
    fun russianAddAndSaveCaptionsFitTheirEditors() {
        AppLocales.setLanguage(context, "ru")
        RuntimeEnvironment.setFontScale(1.3f)
        bind(1851, false)
        Robolectric.buildActivity(MainActivity::class.java, configure(1851))
            .setup()
            .visible()
            .use(
                { c ->
                    val button: TextView = checkNotNull(footer(c.get()))
                    assertEquals("Добавить на рабочий стол", button.getText().toString())
                    assertEquals(1, (button.getParent() as ViewGroup).getChildCount())
                    assertTrue(button.getHeight() >= 48)
                    button.onPreDraw()
                    assertNotNull(button.getLayout())
                    assertEquals(1, button.getLayout().getLineCount())
                    assertTrue(
                        button.getLayout().getLineWidth(0) <=
                            button.getWidth() - button.getPaddingLeft() - button.getPaddingRight(),
                    )
                    tapFooter(c.get())
                },
            )
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .use(
                { c ->
                    assertNull(footer(c.get()))
                    val save = layout(c.get()).findViewWithTag<TextView>("action-save-changes")
                    assertNotNull(save)
                    assertEquals("Сохранить изменения", save.text.toString())
                    assertEquals(1, (save.parent as ViewGroup).childCount)
                    assertTrue(save.height >= 48)
                    save.onPreDraw()
                    assertNotNull(save.layout)
                    assertEquals(1, save.layout.lineCount)
                    assertTrue(
                        save.layout.getLineWidth(0) <=
                            save.width - save.paddingLeft - save.paddingRight,
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
        val info: AppWidgetProviderInfo = AppWidgetProviderInfo()
        info.provider =
            ComponentName(
                context,
                if (world) WorldWidgetProvider::class.java else ClockWidgetProvider::class.java,
            )
        info.initialLayout = if (world) R.layout.world_widget else R.layout.clock_widget
        Shadows.shadowOf(manager).addInstalledProvider(info)
        Shadows.shadowOf(manager).addBoundWidget(id, info)
        val options: android.os.Bundle = android.os.Bundle()
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 170)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 170)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        manager!!.updateAppWidgetOptions(id, options)
    }

    private fun footer(activity: MainActivity): TextView? {
        val root = layout(activity)
        val add = root.findViewWithTag<TextView>("action-add-widget")
        if (add != null) assertNull(root.findViewWithTag<View>("action-save-changes"))
        return add
    }

    @Throws(Exception::class)
    private fun settings(activity: MainActivity): WidgetSettings {
        return activity.session.settings
    }

    private fun layout(activity: MainActivity): View {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        val root: View =
            (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 400, 850)
        return root
    }

    private fun tapFooter(activity: MainActivity) {
        val root: View = layout(activity)
        val button: TextView = root.findViewWithTag<TextView>("action-add-widget")
        val bounds: Rect = Rect()
        button.getDrawingRect(bounds)
        (root as ViewGroup).offsetDescendantRectToMyCoords(button, bounds)
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
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
}

package app.noonlet

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextClock
import android.widget.TextView
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAppWidgetManager
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SaveChangesTest {
    private lateinit var context: Context
    private lateinit var manager: AppWidgetManager

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        manager = AppWidgetManager.getInstance(context)
        for (name in arrayOf("noonlet_widgets", "MainActivity", "widget_drafts")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        AppLocales.setLanguage(context, "en")
    }

    @Test
    fun saveFlushesTheLatestClockSliderBeforeItsAutomaticCommit() {
        val id = 31001
        install(id, false)
        install(id + 1, false)
        install(id + 2, true)
        saveFallbacks()
        Robolectric.buildActivity(MainActivity::class.java, edit(id, false))
            .setup()
            .visible()
            .use { controller ->
                val activity = controller.get()
                val initial = layout(activity)
                openSection(activity, initial, "time")
                val stableRoot = root(activity)
                val otherRecords = unrelatedRecords(id, false)
                val sibling = host(id + 1)
                val otherType = host(id + 2)
                val ids = boundIds(false)
                val slider = checkNotNull(described(stableRoot, "Time size"))
                setProgress(slider, 48)
                assertEquals(
                    "The automatic commit is still pending",
                    56,
                    WidgetSettingsStore.load(context, id, false).textSize,
                )

                val save = saveButton(stableRoot)
                assertEquals("Save changes", save.text.toString())
                assertTrue(save.performClick())

                assertEquals(48, WidgetSettingsStore.load(context, id, false).textSize)
                assertEquals(48f, host(id).findViewById<TextClock>(R.id.clock_time).textSize, 0f)
                assertEquals(otherRecords, unrelatedRecords(id, false))
                assertSame(sibling, host(id + 1))
                assertSame(otherType, host(id + 2))
                assertArrayEquals(ids, boundIds(false))
                assertCompletedAtHome(activity)
                assertEquals(Activity.RESULT_OK, Shadows.shadowOf(activity).resultCode)
                assertEquals(
                    id,
                    checkNotNull(Shadows.shadowOf(activity).resultIntent)
                        .getIntExtra(
                            AppWidgetManager.EXTRA_APPWIDGET_ID,
                            -1,
                        ),
                )
                val delivered = host(id)
                idle(200)
                assertSame("The old debounce must not send another update", delivered, host(id))
            }
    }

    @Test
    fun roundnessControlsUpdateOnlyTheSelectedWorldAndPreserveIndependentContours() {
        val id = 32011
        install(id, true)
        install(id + 1, true)
        install(id + 2, false)
        saveFallbacks()
        val records = unrelatedRecords(id, true)
        val sibling = host(id + 1)
        val clock = host(id + 2)
        fun radius(view: View, viewId: Int): Float {
            val shape =
                view.findViewById<ImageView>(viewId).drawable.current
                    as android.graphics.drawable.GradientDrawable
            if (shape.cornerRadius != 0f) return shape.cornerRadius
            return try {
                shape.cornerRadii?.maxOrNull() ?: 0f
            } catch (_: NullPointerException) {
                0f
            }
        }
        Robolectric.buildActivity(MainActivity::class.java, edit(id, true)).setup().visible().use {
            controller ->
            val activity = controller.get()
            val initial = layout(activity)
            openSection(activity, initial, "appearance")
            val current = root(activity)
            val cardSlider = checkNotNull(described(current, "Card roundness"))
            val backdropSlider = checkNotNull(described(current, "Backdrop roundness"))
            assertEquals(20, cardSlider.max)
            assertEquals(20, backdropSlider.max)
            assertEquals(10, cardSlider.progress)
            assertEquals(10, backdropSlider.progress)
            setProgress(cardSlider, 0)
            idle(33)
            val stage = current.findViewWithTag<WidgetPreviewStage>("preview-stage")
            val preview = checkNotNull(stage.getWidgetContent())
            assertEquals(0f, radius(preview, R.id.card_background), 0f)
            assertTrue(radius(preview, R.id.widget_background) > 0f)
            idle(100)
            assertEquals(0, WidgetSettingsStore.load(context, id, true).worldCardCornerScale)
            assertEquals(100, WidgetSettingsStore.load(context, id, true).worldBackdropCornerScale)
            assertEquals(0f, radius(host(id), R.id.card_background), 0f)
            assertTrue(radius(host(id), R.id.widget_background) > 0f)
            assertFalse(activity.isFinishing)
            assertNull(Shadows.shadowOf(activity).nextStartedActivity)
        }
        for ((cardScale, backdropScale) in arrayOf(200 to 0, 50 to 150)) {
            Robolectric.buildActivity(MainActivity::class.java, edit(id, true))
                .setup()
                .visible()
                .use { controller ->
                    val activity = controller.get()
                    openSection(activity, layout(activity), "appearance")
                    val current = root(activity)
                    val cardSlider = checkNotNull(described(current, "Card roundness"))
                    val backdropSlider = checkNotNull(described(current, "Backdrop roundness"))
                    setProgress(cardSlider, cardScale)
                    setProgress(backdropSlider, backdropScale)
                    assertTrue(saveButton(current).performClick())
                    assertCompletedAtHome(activity)
                    val saved = WidgetSettingsStore.load(context, id, true)
                    assertEquals(cardScale, saved.worldCardCornerScale)
                    assertEquals(backdropScale, saved.worldBackdropCornerScale)
                    assertTrue(radius(host(id), R.id.card_background) > 0f)
                    if (backdropScale == 0)
                        assertEquals(0f, radius(host(id), R.id.widget_background), 0f)
                    else assertTrue(radius(host(id), R.id.widget_background) > 0f)
                }
        }
        assertEquals(records, unrelatedRecords(id, true))
        assertSame(sibling, host(id + 1))
        assertSame(clock, host(id + 2))
        Robolectric.buildActivity(MainActivity::class.java, edit(id, true)).setup().visible().use {
            controller ->
            controller.recreate()
            assertEquals(50, WidgetSettingsStore.load(context, id, true).worldCardCornerScale)
            assertEquals(150, WidgetSettingsStore.load(context, id, true).worldBackdropCornerScale)
            assertFalse(controller.get().isFinishing)
        }
    }

    @Test
    fun saveFlushesWorldOpacityToItsActualHostWithoutTouchingOtherWidgets() {
        val id = 32001
        install(id, true)
        install(id + 1, true)
        install(id + 2, false)
        saveFallbacks()
        Robolectric.buildActivity(MainActivity::class.java, edit(id, true)).setup().visible().use {
            controller ->
            val activity = controller.get()
            val original = layout(activity)
            openSection(activity, original, "appearance")
            val current = root(activity)
            val records = unrelatedRecords(id, true)
            val sibling = host(id + 1)
            val other = host(id + 2)
            val beforeAlpha = host(id).findViewById<ImageView>(R.id.card_background).imageAlpha
            setProgress(checkNotNull(described(current, "Opacity")), 42)
            assertEquals(100, WidgetSettingsStore.load(context, id, true).opacity)

            assertTrue(saveButton(current).performClick())

            assertEquals(42, WidgetSettingsStore.load(context, id, true).opacity)
            assertTrue(
                "The placed card must receive the new background opacity",
                host(id).findViewById<ImageView>(R.id.card_background).imageAlpha < beforeAlpha,
            )
            assertEquals(107, host(id).findViewById<ImageView>(R.id.card_background).imageAlpha)
            assertEquals(records, unrelatedRecords(id, true))
            assertSame(sibling, host(id + 1))
            assertSame(other, host(id + 2))
            assertCompletedAtHome(activity)
        }
    }

    @Test
    fun unchangedClockSettingsAreSentAgainWhenThePlacedHostHasStaleContent() {
        assertUnchangedSettingsRefresh(false)
    }

    @Test
    fun unchangedWorldSettingsAreSentAgainWhenThePlacedHostHasStaleContent() {
        assertUnchangedSettingsRefresh(true)
    }

    private fun assertUnchangedSettingsRefresh(world: Boolean) {
        val id = if (world) 33002 else 33001
        install(id, world)
        install(id + 10, world)
        saveFallbacks()
        Robolectric.buildActivity(MainActivity::class.java, edit(id, world))
            .setup()
            .visible()
            .use { controller ->
                val activity = controller.get()
                val current = layout(activity)
                val persisted = context.getSharedPreferences("noonlet_widgets", 0).all.toMap()
                val sibling = host(id + 10)
                val ids = boundIds(world)
                val oldSettings =
                    WidgetSettingsStore.load(context, id, world).apply {
                        if (world) opacity = 17 else showDate = false
                    }
                val oldPayload =
                    WidgetRenderer.preview(
                        context,
                        id,
                        world,
                        oldSettings,
                        if (world) 328f else 170f,
                        if (world) 174f else 90f,
                    )
                manager.updateAppWidget(id, oldPayload)
                if (world) {
                    assertEquals(
                        43,
                        host(id).findViewById<ImageView>(R.id.card_background).imageAlpha,
                    )
                } else {
                    assertEquals(
                        View.GONE,
                        host(id).findViewById<View>(R.id.clock_date_row).visibility,
                    )
                }
                assertEquals(persisted, context.getSharedPreferences("noonlet_widgets", 0).all)

                assertTrue(saveButton(current).performClick())

                if (world) {
                    assertEquals(
                        "Save must restore the current card opacity",
                        255,
                        host(id).findViewById<ImageView>(R.id.card_background).imageAlpha,
                    )
                } else {
                    assertEquals(
                        "Save must restore the current date setting",
                        View.VISIBLE,
                        host(id).findViewById<View>(R.id.clock_date_row).visibility,
                    )
                }
                assertEquals(persisted, context.getSharedPreferences("noonlet_widgets", 0).all)
                assertSame(sibling, host(id + 10))
                assertArrayEquals(ids, boundIds(world))
                assertCompletedAtHome(activity)
            }
    }

    @Test
    fun saveDoesNotOverwriteAConfigurationSavedByAnotherEditor() {
        val id = 34001
        install(id, false)
        saveFallbacks()
        Robolectric.buildActivity(MainActivity::class.java, edit(id, false))
            .setup()
            .visible()
            .use { controller ->
                val activity = controller.get()
                val initial = layout(activity)
                openSection(activity, initial, "time")
                val current = root(activity)
                setProgress(checkNotNull(described(current, "Time size")), 48)
                val authoritative =
                    WidgetSettingsStore.load(context, id, false).apply {
                        textSize = 63
                        timeFormat = "'EXTERNAL'"
                        dateOffsetX = -9
                    }
                WidgetSettingsStore.save(context, id, false, authoritative)
                BaseWidgetProvider.updateWidget(context, id, false)
                val saved = context.getSharedPreferences("noonlet_widgets", 0).all.toMap()
                val previousToast = ShadowToast.getTextOfLatestToast()

                assertTrue(saveButton(current).performClick())

                assertEquals(saved, context.getSharedPreferences("noonlet_widgets", 0).all)
                assertEquals(63, WidgetSettingsStore.load(context, id, false).textSize)
                assertEquals(
                    "'EXTERNAL'",
                    host(id).findViewById<TextClock>(R.id.clock_time).format24Hour.toString(),
                )
                assertEquals(
                    "A conflict must not report an accepted save",
                    previousToast,
                    ShadowToast.getTextOfLatestToast(),
                )
                assertFalse(activity.isFinishing)
                assertNull(Shadows.shadowOf(activity).nextStartedActivity)
                idle(200)
                assertEquals(saved, context.getSharedPreferences("noonlet_widgets", 0).all)
            }
    }

    @Test
    fun aDeletedSaveTargetDoesNotCloseTheEditorOrUpdateItsSibling() {
        val id = 34011
        install(id, false)
        install(id + 1, false)
        Robolectric.buildActivity(MainActivity::class.java, edit(id, false))
            .setup()
            .visible()
            .use { controller ->
                val activity = controller.get()
                val save = saveButton(layout(activity))
                val bindings =
                    ShadowAppWidgetManager::class.java.getDeclaredField("widgetInfos").apply {
                        isAccessible = true
                    }
                (bindings.get(Shadows.shadowOf(manager)) as MutableMap<*, *>).remove(id)
                WidgetSettingsStore.delete(context, id, false)
                val persisted = context.getSharedPreferences("noonlet_widgets", 0).all.toMap()
                val sibling = host(id + 1)

                assertTrue(save.performClick())

                assertFalse(activity.isFinishing)
                assertNull(Shadows.shadowOf(activity).nextStartedActivity)
                assertEquals(persisted, context.getSharedPreferences("noonlet_widgets", 0).all)
                assertSame(sibling, host(id + 1))
                assertNotNull(root(activity).findViewWithTag<View>("removed-widget-notice"))
            }
    }

    @Test
    fun savingFromTheApplicationEditorCompletesAtHomeForItsSelectedWidget() {
        val id = 34021
        install(id, false)
        install(id + 1, true)
        saveFallbacks()
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val records = unrelatedRecords(id, false)
            val world = host(id + 1)

            assertTrue(saveButton(layout(activity)).performClick())

            assertCompletedAtHome(activity)
            assertEquals(records, unrelatedRecords(id, false))
            assertSame(world, host(id + 1))
        }
    }

    @Test
    fun savingAConfigurationReturnsItsResultToTheCallingLauncher() {
        for (world in booleanArrayOf(false, true)) {
            val id = if (world) 34032 else 34031
            install(id, world)
            val configure =
                Intent(context, MainActivity::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra("world", world)
            Robolectric.buildActivity(MainActivity::class.java, configure).setup().visible().use {
                controller ->
                val activity = controller.get()
                val shadow = Shadows.shadowOf(activity)
                shadow.setCallingActivity(
                    ComponentName("example.launcher", "example.launcher.HomeActivity"),
                )

                assertTrue(saveButton(layout(activity)).performClick())

                assertTrue(activity.isFinishing)
                assertEquals(Activity.RESULT_OK, shadow.resultCode)
                assertEquals(
                    id,
                    checkNotNull(shadow.resultIntent)
                        .getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1),
                )
                assertNull(shadow.nextStartedActivity)
            }
        }
    }

    private fun assertCompletedAtHome(activity: MainActivity) {
        assertTrue(activity.isFinishing)
        val home = checkNotNull(Shadows.shadowOf(activity).nextStartedActivity)
        assertEquals(Intent.ACTION_MAIN, home.action)
        assertTrue(home.hasCategory(Intent.CATEGORY_HOME))
        assertTrue(home.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(Shadows.shadowOf(activity).nextStartedActivity)
    }

    private fun install(id: Int, world: Boolean) {
        WidgetSettings.defaults(world)
            .apply {
                tapAction = "none"
                cities = arrayOf("America/Los_Angeles", "Asia/Riyadh")
            }
            .also { WidgetSettingsStore.save(context, id, world, it) }
        val info =
            AppWidgetProviderInfo().apply {
                provider =
                    ComponentName(
                        context,
                        if (world) WorldWidgetProvider::class.java
                        else ClockWidgetProvider::class.java,
                    )
                initialLayout = if (world) R.layout.world_widget else R.layout.clock_widget
            }
        Shadows.shadowOf(manager).addInstalledProvider(info)
        Shadows.shadowOf(manager).addBoundWidget(id, info)
        manager.updateAppWidgetOptions(
            id,
            Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, if (world) 328 else 170)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, if (world) 328 else 170)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, if (world) 174 else 90)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, if (world) 174 else 90)
            },
        )
        BaseWidgetProvider.updateWidget(context, id, world)
    }

    private fun saveFallbacks() {
        for (world in booleanArrayOf(false, true)) {
            WidgetSettings.defaults(world)
                .apply {
                    fontFamily = "serif"
                    fontWeight = 575
                    timeFontWeight = 625
                    dateOffsetX = -13
                }
                .also { WidgetSettingsStore.save(context, 0, world, it) }
        }
    }

    private fun unrelatedRecords(id: Int, world: Boolean): Map<String, *> {
        val prefix = (if (world) "world." else "clock.") + id + "."
        return context.getSharedPreferences("noonlet_widgets", 0).all.filterKeys {
            !it.startsWith(prefix)
        }
    }

    private fun edit(id: Int, world: Boolean) =
        Intent(context, MainActivity::class.java)
            .setData(Uri.parse("noonlet://edit/$world/$id"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra("world", world)

    private fun root(activity: MainActivity): View =
        activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

    private fun layout(activity: MainActivity): View {
        idle(100)
        return root(activity).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, 400, 850)
        }
    }

    private fun openSection(activity: MainActivity, root: View, section: String) {
        if (root.findViewWithTag<View>("section-content:$section").visibility != View.VISIBLE) {
            assertTrue(root.findViewWithTag<View>("section-toggle:$section").performClick())
            layout(activity)
        }
    }

    private fun saveButton(root: View): TextView =
        checkNotNull(root.findViewWithTag("action-save-changes"))

    private fun described(root: View, label: String): SeekBar? {
        if (root is SeekBar && label.contentEquals(root.contentDescription)) return root
        if (root is ViewGroup)
            for (i in 0 until root.childCount) {
                described(root.getChildAt(i), label)?.let {
                    return it
                }
            }
        return null
    }

    private fun setProgress(bar: SeekBar, progress: Int) {
        assertTrue(
            bar.performAccessibilityAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                Bundle().apply {
                    putFloat(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                        progress.toFloat(),
                    )
                },
            ),
        )
    }

    private fun boundIds(world: Boolean): IntArray =
        manager
            .getAppWidgetIds(
                ComponentName(
                    context,
                    if (world) WorldWidgetProvider::class.java else ClockWidgetProvider::class.java,
                ),
            )
            .sortedArray()

    private fun host(id: Int): View = checkNotNull(Shadows.shadowOf(manager).getViewFor(id))

    private fun idle(milliseconds: Long) =
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
}

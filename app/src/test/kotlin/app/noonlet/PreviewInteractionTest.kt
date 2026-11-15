package app.noonlet

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextClock
import android.widget.TextView
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
class PreviewInteractionTest {
    private lateinit var context: Context
    private var manager: AppWidgetManager? = null

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        manager = AppWidgetManager.getInstance(context)
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
        WidgetSettingsStore.save(context, 1901, false, WidgetSettings())
        val world: WidgetSettings = WidgetSettings.defaults(true)
        world.style = WorldClockStyle.LIGHT
        world.cities = arrayOf<String>("America/Los_Angeles", "Asia/Riyadh")
        WidgetSettingsStore.save(context, 1902, true, world)
        bind(1901, false)
        bind(1902, true)
    }

    @Test
    @Throws(Exception::class)
    fun presetsKeepTopologyAndNativeContentOnOnePlacementGrid() {
        context
            .getSharedPreferences("MainActivity", 0)
            .edit()
            .putBoolean("world_tab", true)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    var common: Float = -1f
                    val original: MutableMap<String, Any?> = realPrefs()
                    val dimensions: Array<IntArray> =
                        arrayOf<IntArray>(
                            intArrayOf(167, 82),
                            intArrayOf(328, 82),
                            intArrayOf(167, 174),
                            intArrayOf(328, 174),
                        )
                    for (i: Int in 0..3) {
                        tap(
                            a,
                            layout(a, 400, 850).findViewWithTag<View>("preview-size:" + i),
                            400,
                            850,
                        )
                        val s: WidgetPreviewStage = stage(a, 400, 850)
                        val w: FrameLayout = widget(s)
                        val grid = minOf(s.width / 334f, s.height / 174f)
                        assertEquals(if (i == 0) 170 else Math.round(dimensions[i][0] * grid), w.width)
                        assertEquals(if (i == 0) 90 else Math.round(dimensions[i][1] * grid), w.height)
                        assertEquals(1f, w.scaleX, .0001f)
                        assertEquals(1f, w.scaleY, .0001f)
                        if (common < 0) common = grid
                        else assertEquals(common, grid, .0001f)
                        assertTrue(
                            layout(a, 400, 850)
                                .findViewWithTag<View>("preview-size:" + i)
                                .isSelected(),
                        )
                        val clocks: MutableList<View> = WidgetTestViews.find(w, R.id.city_time)
                        assertEquals(2, clocks.size)
                        val first: FloatArray = logical(clocks.get(0), w)
                        val second: FloatArray = logical(clocks.get(1), w)
                        if (i == 1)
                            assertEquals(
                                "4x1 stays horizontal",
                                first[1],
                                second[1],
                                .01f,
                            )
                        else assertTrue("Other presets stack the two cities", second[1] > first[1])
                        if (i == 3)
                            for (card: View in
                                WidgetTestViews.find(w, R.id.world_card)) assertEquals(
                                w.width,
                                card.getWidth(),
                            )
                        inside(s)
                        WidgetTestViews.prepare(layout(a, 400, 850))
                    }
                    assertEquals(original, realPrefs())
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun posesSurviveRenderTypeSwitchAndRotationWithoutChangingRealWidgetState() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    var a: MainActivity = c.get()
                    val original: MutableMap<String, Any?> = realPrefs()
                    val options: Bundle = Bundle(manager!!.getAppWidgetOptions(1901))
                    val originalWidget: FrameLayout = widget(stage(a, 400, 850))
                    drag(a, 2000f, -2000f, 400, 850)
                    pose(stage(a, 400, 850), 2, 0)
                    assertSame(
                        "Dragging only translates the current view",
                        originalWidget,
                        widget(stage(a, 400, 850)),
                    )
                    val cover: View =
                        stage(a, 400, 850).findViewWithTag<View>("preview-drag-target")
                    cover.dispatchKeyEvent(
                        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT),
                    )
                    pose(stage(a, 400, 850), 1, 0)
                    assertTrue(
                        cover.performAccessibilityAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId(),
                            null,
                        ),
                    )
                    pose(stage(a, 400, 850), 1, 1)
                    assertFalse(
                        cover.performAccessibilityAction(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.getId(),
                            null,
                        ),
                    )
                    drag(a, 2000f, -2000f, 400, 850)
                    render(a)
                    pose(stage(a, 400, 850), 2, 0)
                    tap(a, text(layout(a, 400, 850), "World clock"), 400, 850)
                    tap(a, layout(a, 400, 850).findViewWithTag<View>("preview-size:2"), 400, 850)
                    drag(a, -2000f, 2000f, 400, 850)
                    pose(stage(a, 400, 850), 0, 0)
                    c.recreate()
                    a = c.get()
                    assertTrue(
                        layout(a, 400, 850).findViewWithTag<View>("preview-size:2").isSelected(),
                    )
                    pose(stage(a, 400, 850), 0, 0)
                    tap(a, text(layout(a, 400, 850), "Clock"), 400, 850)
                    assertTrue(
                        layout(a, 400, 850).findViewWithTag<View>("preview-size:0").isSelected(),
                    )
                    pose(stage(a, 400, 850), 2, 0)
                    assertFalse(
                        widget(stage(a, 400, 850))
                            .findViewById<View>(R.id.clock_time)
                            .performClick(),
                    )
                    assertFalse(
                        widget(stage(a, 400, 850))
                            .findViewById<View>(R.id.widget_root)
                            .performClick(),
                    )
                    assertNull(Shadows.shadowOf(context as Application?).getNextStartedActivity())
                    assertEquals(original, realPrefs())
                    val after: Bundle = manager!!.getAppWidgetOptions(1901)
                    assertEquals(options.keySet(), after.keySet())
                    for (key: String in options.keySet()) assertEquals(
                        options.get(key),
                        after.get(key),
                    )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun sizeChipsUpdateThePreviewWithoutRebuildingTheEditorOrChangingItsModel() {
        context
            .getSharedPreferences("MainActivity", 0)
            .edit()
            .putBoolean("world_tab", true)
            .commit()
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    var root: View = layout(a, 400, 850)
                    tap(a, root.findViewWithTag<View>("section-toggle:appearance"), 400, 850)
                    root = layout(a, 400, 850)
                    val originalStage: WidgetPreviewStage =
                        root.findViewWithTag<WidgetPreviewStage>("preview-stage")
                    val controls: View = root.findViewWithTag<View>("section-content:appearance")
                    val slider: SeekBar? = firstSeekBar(controls)
                    assertNotNull(slider)
                    val scroll: ScrollView? = scrollParent(controls)
                    assertNotNull(scroll)
                    scroll!!.scrollTo(0, 90)
                    layout(a, 400, 850)
                    val originalScroll: Int = scroll!!.getScrollY()
                    assertTrue(
                        "The fixture must have an actual scroll position",
                        originalScroll > 0,
                    )
                    val originalModel: WidgetSettings = settings(a)
                    val originalPreferences: MutableMap<String, Any?> = realPrefs()
                    val before: Bundle = Bundle()
                    c.saveInstanceState(before)
                    val chips: Array<View> =
                        Array(4) { i ->
                            root.findViewWithTag<View>("preview-size:" + i)
                        }
                    for (size: Int in intArrayOf(1, 3, 2, 0)) {
                        tap(a, chips[size], 400, 850)
                        val current: View = layout(a, 400, 850)
                        assertSame(
                            "A size change must retain the existing editor tree",
                            root,
                            current,
                        )
                        assertSame(originalStage, current.findViewWithTag<View>("preview-stage"))
                        assertSame(
                            controls,
                            current.findViewWithTag<View>("section-content:appearance"),
                        )
                        assertSame(slider, firstSeekBar(controls))
                        assertSame(
                            "A preview-only choice must not replace the widget model",
                            originalModel,
                            settings(a),
                        )
                        assertEquals(View.VISIBLE, controls.getVisibility())
                        assertEquals(originalScroll, scroll!!.getScrollY())
                        for (i: Int in chips.indices) {
                            assertSame(chips[i], current.findViewWithTag<View>("preview-size:" + i))
                            assertEquals(i == size, chips[i].isSelected())
                        }
                        assertNull(current.findViewWithTag<View>("preview-hint"))
                        assertEquals(originalPreferences, realPrefs())
                        val after: Bundle = Bundle()
                        c.saveInstanceState(after)
                        assertEquals(
                            java.util.HashSet(before.getStringArrayList("expanded_world")),
                            java.util.HashSet(after.getStringArrayList("expanded_world")),
                        )
                        inside(originalStage)
                    }
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun liveRefreshWaitsForDragAndKeepsTheGestureTarget() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    settings(a).zoneId = "UTC"
                    settings(a).timeFormat = "HH:mm:ss"
                    val initialColor: Int = -0xe192b
                    val nextColor: Int = -0x943017
                    settings(a).timeColor = initialColor
                    val now: Instant = Instant.parse("2026-10-28T12:34:56Z")
                    setTime(a, now)
                    render(a)
                    val s: WidgetPreviewStage = stage(a, 400, 850)
                    val root: View = layout(a, 400, 850)
                    val cover: View = s.findViewWithTag<View>("preview-drag-target")
                    val content: View = requireNotNull(s.getWidgetContent())
                    val wrapper: FrameLayout = widget(s)
                    val old: TextClock = s.findViewById<TextClock>(R.id.clock_time)
                    assertEquals(initialColor, old.getCurrentTextColor())
                    assertEquals("12:34:56", old.getText().toString())
                    val start: FloatArray = point(cover, root)
                    val time: Long = SystemClock.uptimeMillis()
                    event(root, MotionEvent.ACTION_DOWN, start[0], start[1], time, time)
                    assertTrue(s.isDragging())
                    settings(a).timeColor = nextColor
                    setTime(a, now.plusSeconds(1))
                    a.preview.refresh()
                    assertSame(old, s.findViewById<View>(R.id.clock_time))
                    assertSame(content, s.getWidgetContent())
                    assertEquals(
                        "The new appearance must wait until the gesture finishes",
                        initialColor,
                        old.getCurrentTextColor(),
                    )
                    assertEquals(
                        "The preview clock must also defer its new timestamp",
                        "12:34:56",
                        old.getText().toString(),
                    )
                    event(
                        root,
                        MotionEvent.ACTION_MOVE,
                        start[0] + 24,
                        start[1] + 16,
                        time,
                        time + 32,
                    )
                    event(
                        root,
                        MotionEvent.ACTION_UP,
                        start[0] + 24,
                        start[1] + 16,
                        time,
                        time + 64,
                    )
                    layout(a, 400, 850)
                    assertFalse(s.isDragging())
                    assertSame(old, s.findViewById<View>(R.id.clock_time))
                    assertSame(
                        "A deferred refresh reapplies the retained content",
                        content,
                        s.getWidgetContent(),
                    )
                    assertSame(wrapper, widget(s))
                    pose(s, 1, 0)
                    assertEquals("12:34:57", old.getText().toString())
                    assertEquals(nextColor, old.getCurrentTextColor())
                    assertSame(cover, s.findViewWithTag<View>("preview-drag-target"))
                    inside(s)
                },
            )
    }

    @Test
    fun dragFollowsTheFingerAndSnapsOnlyAfterReleaseWithTheOriginalGrabOffset() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    val root: View = layout(a, 400, 850)
                    val s: WidgetPreviewStage = stage(a, 400, 850)
                    pose(s, 1, 0)
                    val w: FrameLayout = widget(s)
                    val cover: View = s.findViewWithTag<View>("preview-drag-target")
                    val scale = minOf(s.width / 334f, s.height / 174f)
                    val pitchX: Float = 83.5f * scale
                    val pitchY: Float = 92 * scale
                    assertTrue(
                        "The fixture must cover a grid wider than its nominal dimensions",
                        scale > 1,
                    )
                    val originX: Float = w.getX()
                    val originY: Float = w.getY()
                    var p: FloatArray = point(cover, root)
                    p[0] -= cover.width / 2f - 5
                    p[1] -= cover.height / 2f - 5
                    var t: Long = SystemClock.uptimeMillis()
                    event(root, MotionEvent.ACTION_DOWN, p[0], p[1], t, t)
                    pose(s, 1, 0)
                    event(
                        root,
                        MotionEvent.ACTION_MOVE,
                        p[0] + pitchX * .25f,
                        p[1] + pitchY * .25f,
                        t,
                        t + 16,
                    )
                    assertEquals(
                        "Small moves follow the finger before a cell threshold",
                        originX + pitchX * .25f,
                        w.getX(),
                        .1f,
                    )
                    assertEquals(originY + pitchY * .25f, w.getY(), .1f)
                    event(
                        root,
                        MotionEvent.ACTION_MOVE,
                        p[0] + pitchX * .51f,
                        p[1] + pitchY * .51f,
                        t,
                        t + 32,
                    )
                    assertEquals(
                        "Crossing the threshold must not teleport the view",
                        originX + pitchX * .51f,
                        w.getX(),
                        .1f,
                    )
                    assertEquals(originY + pitchY * .51f, w.getY(), .1f)
                    val during: Bundle = Bundle()
                    c.saveInstanceState(during)
                    assertEquals(
                        "Only a dropped position is committed",
                        1,
                        requireNotNull(during.getIntArray("preview_columns"))[0],
                    )
                    assertEquals(0, requireNotNull(during.getIntArray("preview_rows"))[0])
                    event(root, MotionEvent.ACTION_MOVE, p[0] - pitchX * .51f, p[1], t, t + 48)
                    assertEquals(originX - pitchX * .51f, w.getX(), .1f)
                    assertEquals(originY, w.getY(), .1f)
                    event(root, MotionEvent.ACTION_UP, p[0] - pitchX * .51f, p[1], t, t + 64)
                    pose(s, 0, 0)
                    assertFalse(s.isDragging())
                    p = point(cover, root)
                    t = SystemClock.uptimeMillis()
                    event(root, MotionEvent.ACTION_DOWN, p[0], p[1], t, t)
                    event(
                        root,
                        MotionEvent.ACTION_UP,
                        p[0] + pitchX * .49f,
                        p[1] + pitchY * .49f,
                        t,
                        t + 32,
                    )
                    pose(s, 0, 0)
                    p = point(cover, root)
                    t = SystemClock.uptimeMillis()
                    event(root, MotionEvent.ACTION_DOWN, p[0], p[1], t, t)
                    event(
                        root,
                        MotionEvent.ACTION_UP,
                        p[0] + pitchX * .51f,
                        p[1] + pitchY * .51f,
                        t,
                        t + 32,
                    )
                    pose(s, 1, 1)
                    assertSame(w, widget(s))
                    assertNull(Shadows.shadowOf(context as Application?).getNextStartedActivity())
                },
            )
    }

    @Test
    fun cancelledDragReturnsToTheCommittedCellAndOvershootKeepsTheGrabOffset() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    val root: View = layout(a, 400, 850)
                    val s: WidgetPreviewStage = stage(a, 400, 850)
                    val w: FrameLayout = widget(s)
                    val cover: View = s.findViewWithTag<View>("preview-drag-target")
                    val origin: Float = w.getX()
                    val pitchX: Float = 83.5f * w.getScaleX()
                    var p: FloatArray = point(cover, root)
                    var t: Long = SystemClock.uptimeMillis()
                    event(root, MotionEvent.ACTION_DOWN, p[0], p[1], t, t)
                    event(root, MotionEvent.ACTION_MOVE, p[0] + 2000, p[1], t, t + 16)
                    pose(s, 2, 0)
                    event(root, MotionEvent.ACTION_MOVE, p[0] + pitchX * .2f, p[1], t, t + 32)
                    assertEquals(
                        "Returning from the edge follows the original touch delta",
                        origin + pitchX * .2f,
                        w.getX(),
                        .1f,
                    )
                    event(root, MotionEvent.ACTION_CANCEL, p[0] + pitchX * .2f, p[1], t, t + 48)
                    assertFalse(s.isDragging())
                    pose(s, 1, 0)
                    p = point(cover, root)
                    t = SystemClock.uptimeMillis()
                    event(root, MotionEvent.ACTION_DOWN, p[0], p[1], t, t)
                    event(root, MotionEvent.ACTION_MOVE, p[0] - pitchX * .7f, p[1], t, t + 16)
                    c.pause()
                    assertFalse(s.isDragging())
                    pose(s, 1, 0)
                },
            )
    }

    @Test
    fun previewDrawsNoGridOrOccupiedCellOverlay() {
        val s: WidgetPreviewStage = WidgetPreviewStage(context)
        val color: Int = -0xdadace
        s.setBackgroundColor(color)
        s.showWidget(View(context), 167, 82, 2, 1, 1, 0)
        s.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY),
        )
        s.layout(0, 0, 400, 180)
        val bitmap: Bitmap = Bitmap.createBitmap(400, 180, Bitmap.Config.ARGB_8888)
        try {
            s.draw(Canvas(bitmap))
            val pixels: IntArray = IntArray(400 * 180)
            bitmap.getPixels(pixels, 0, 400, 0, 0, 400, 180)
            for (pixel: Int in pixels) assertEquals(
                "Only the plain preview background may be visible",
                color,
                pixel,
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun fullGridHasNoMovementActionsAndDoesNotCaptureScrolling() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    tap(a, layout(a, 360, 640).findViewWithTag<View>("preview-size:3"), 360, 640)
                    val root: View = layout(a, 360, 640)
                    val s: WidgetPreviewStage = stage(a, 360, 640)
                    val cover: View = s.findViewWithTag<View>("preview-drag-target")
                    pose(s, 0, 0)
                    val node: AccessibilityNodeInfo = cover.createAccessibilityNodeInfo()
                    for (action: AccessibilityNodeInfo.AccessibilityAction in
                        arrayOf<AccessibilityNodeInfo.AccessibilityAction>(
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT,
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT,
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP,
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN,
                        )) {
                        assertFalse(node.getActionList().contains(action))
                        assertFalse(cover.performAccessibilityAction(action.getId(), null))
                    }
                    val p: FloatArray = point(cover, root)
                    val t: Long = SystemClock.uptimeMillis()
                    event(root, MotionEvent.ACTION_DOWN, p[0], p[1], t, t)
                    assertFalse(s.isDragging())
                    event(root, MotionEvent.ACTION_UP, p[0], p[1], t, t + 32)
                    pose(s, 0, 0)
                    assertNull(root.findViewWithTag<View>("preview-hint"))
                    assertTrue(
                        cover.getContentDescription().toString().contains("fills the preview"),
                    )
                },
            )
    }

    @Test
    fun malformedOrLegacyPreviewStateFallsBackToARealCell() {
        val state: Bundle = Bundle()
        state.putIntArray("preview_presets", intArrayOf(0, 2))
        state.putIntArray("preview_columns", intArrayOf(99, -1))
        state.putIntArray("preview_rows", intArrayOf(-1, 99))
        state.putFloatArray("preview_horizontal", floatArrayOf(.9f, .1f))
        state.putFloatArray("preview_vertical", floatArrayOf(.8f, .4f))
        Robolectric.buildActivity(MainActivity::class.java)
            .setup(state)
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    pose(stage(a, 400, 850), 1, 0)
                    tap(a, text(layout(a, 400, 850), "World clock"), 400, 850)
                    pose(stage(a, 400, 850), 1, 0)
                    assertTrue(
                        layout(a, 400, 850).findViewWithTag<View>("preview-size:2").isSelected(),
                    )
                },
            )
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h640dp-mdpi")
    @Throws(Exception::class)
    fun largeRussianChipsFitTheSmallScrollingScene() {
        AppLocales.setLanguage(context, "ru")
        RuntimeEnvironment.setFontScale(1.3f)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { c ->
                    val a: MainActivity = c.get()
                    var root: View = layout(a, 360, 640)
                    var s: WidgetPreviewStage = stage(a, 360, 640)
                    assertEquals(Math.round(s.getWidth() * 174f / 334), s.getHeight())
                    assertTrue(hasScrollParent(s))
                    assertNull(text(root, "Предпросмотр"))
                    assertNull(root.findViewWithTag<View>("preview-hint"))
                    val sizes = root.findViewWithTag<ViewGroup>("preview-sizes")
                    val card = root.findViewWithTag<ViewGroup>("preview-card")
                    assertSame(card, s.parent)
                    assertSame(card, sizes.parent)
                    assertEquals(s.bottom, sizes.top)
                    assertEquals(card.width, sizes.width)
                    assertEquals(28, sizes.height)
                    assertEquals("Предпросмотр", sizes.contentDescription.toString())
                    for (i: Int in 0..3) {
                        val chip: TextView = root.findViewWithTag<TextView>("preview-size:" + i)
                        chip.onPreDraw()
                        assertTrue(chip.width >= 48)
                        assertEquals(24, chip.height)
                        assertEquals(1, chip.getLayout().getLineCount())
                        assertTrue(chip.getLayout().getLineWidth(0) <= chip.getWidth())
                        if (chip.isSelected) {
                            val background = Bitmap.createBitmap(chip.width, chip.height, Bitmap.Config.ARGB_8888)
                            chip.background.setBounds(0, 0, chip.width, chip.height)
                            chip.background.draw(Canvas(background))
                            assertEquals(24, (0 until background.height).count {
                                background.getPixel(background.width / 2, it) ushr 24 != 0
                            })
                            background.recycle()
                        }
                    }
                    tap(a, root.findViewWithTag<View>("preview-size:3"), 360, 640)
                    pose(stage(a, 360, 640), 0, 0)
                    assertNull(layout(a, 360, 640).findViewWithTag<View>("preview-hint"))
                    assertTrue(
                        stage(a, 360, 640)
                            .findViewWithTag<View>("preview-drag-target")
                            .getContentDescription()
                            .toString()
                            .contains("занимает всю область"),
                    )
                    root = layout(a, 360, 640)
                    s = stage(a, 360, 640)
                    val scroll: ScrollView? = scrollParent(s)
                    assertNotNull(scroll)
                    val p: FloatArray = point(s.findViewWithTag<View>("preview-drag-target"), root)
                    val t: Long = SystemClock.uptimeMillis()
                    val before: Int = scroll!!.getScrollY()
                    event(root, MotionEvent.ACTION_DOWN, p[0], p[1], t, t)
                    assertFalse(s.isDragging())
                    event(root, MotionEvent.ACTION_MOVE, p[0], p[1] - 80, t, t + 32)
                    event(root, MotionEvent.ACTION_MOVE, p[0], p[1] - 110, t, t + 48)
                    event(root, MotionEvent.ACTION_UP, p[0], p[1] - 110, t, t + 64)
                    assertTrue(
                        "The full-grid widget leaves the small editor scrollable",
                        scroll!!.getScrollY() > before,
                    )
                    scroll!!.scrollTo(0, 0)
                    WidgetTestViews.prepare(layout(a, 360, 640))
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
        val b: Bundle = Bundle()
        b.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 170)
        b.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 170)
        b.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
        b.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        manager!!.updateAppWidgetOptions(id, b)
    }

    private fun realPrefs(): MutableMap<String, Any?> {
        val result: MutableMap<String, Any?> = HashMap()
        for (e: Map.Entry<String, *> in
            context.getSharedPreferences("noonlet_widgets", 0).getAll().entries) if (
            !e.key.startsWith("clock.-") && !e.key.startsWith("world.-")
        )
            result.put(e.key, e.value)
        return result
    }

    @Throws(Exception::class)
    private fun settings(a: MainActivity): WidgetSettings {
        return a.session.settings
    }

    @Throws(Exception::class)
    private fun setTime(a: MainActivity, time: Instant) {
        a.preview.clock = Clock.fixed(time, ZoneOffset.UTC)
    }

    @Throws(Exception::class)
    private fun invoke(a: MainActivity, name: String) {
        val method = MainActivity::class.java.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(a)
    }

    @Throws(Exception::class)
    private fun render(a: MainActivity) {
        invoke(a, "render")
        layout(a, 400, 850)
    }

    private fun layout(a: MainActivity, width: Int, height: Int): View {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val root: View = (a.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        WidgetTestViews.prepare(root)
        return root
    }

    private fun stage(a: MainActivity, w: Int, h: Int): WidgetPreviewStage {
        return layout(a, w, h).findViewWithTag<WidgetPreviewStage>("preview-stage")
    }

    private fun widget(s: WidgetPreviewStage): FrameLayout {
        return s.findViewWithTag<FrameLayout>("preview-widget")
    }

    private fun pose(s: WidgetPreviewStage, column: Int, row: Int) {
        val w: FrameLayout = widget(s)
        val scale = minOf(s.width / 334f, s.height / 174f)
        val columns = if (w.width > s.width * .75f) 4 else 2
        val inset = (columns * 83.5f * scale - w.width * w.scaleX) / 2f
        fun anchor(value: Float) = if (scale >= 1f) Math.round(value).toFloat() else value
        assertEquals(
            anchor((s.width - 334 * scale) / 2 + column * 83.5f * scale + inset),
            w.getX(),
            1f,
        )
        assertEquals(anchor((s.height - 174 * scale) / 2 + row * 92f * scale), w.y, .1f)
        inside(s)
    }

    private fun inside(s: WidgetPreviewStage) {
        val w: FrameLayout = widget(s)
        assertTrue(
            "The preview must not cross its left or top edge",
            w.getX() >= -.1f && w.getY() >= -.1f,
        )
        assertTrue(
            "The preview must not cross its right or bottom edge",
            (w.getX() + w.getWidth() * w.getScaleX() <= s.getWidth() + .1f &&
                w.getY() + w.getHeight() * w.getScaleY() <= s.getHeight() + .1f),
        )
    }

    private fun firstSeekBar(view: View): SeekBar? {
        if (view is SeekBar) return view as SeekBar
        if (view is ViewGroup)
            for (i: Int in 0 until (view as ViewGroup).getChildCount()) {
                val result: SeekBar? = firstSeekBar((view as ViewGroup).getChildAt(i))
                if (result != null) return result
            }
        return null
    }

    private fun drag(a: MainActivity, dx: Float, dy: Float, width: Int, height: Int) {
        val root: View = layout(a, width, height)
        val p: FloatArray = point(root.findViewWithTag<View>("preview-drag-target"), root)
        val t: Long = SystemClock.uptimeMillis()
        event(root, MotionEvent.ACTION_DOWN, p[0], p[1], t, t)
        event(root, MotionEvent.ACTION_MOVE, p[0] + dx, p[1] + dy, t, t + 32)
        event(root, MotionEvent.ACTION_UP, p[0] + dx, p[1] + dy, t, t + 64)
        layout(a, width, height)
    }

    private fun tap(a: MainActivity, target: View?, width: Int, height: Int) {
        assertNotNull(target)
        val root: View = layout(a, width, height)
        val p: FloatArray = point(target, root)
        val t: Long = SystemClock.uptimeMillis()
        event(root, MotionEvent.ACTION_DOWN, p[0], p[1], t, t)
        event(root, MotionEvent.ACTION_UP, p[0], p[1], t, t + 32)
        layout(a, width, height)
    }

    private fun event(root: View, action: Int, x: Float, y: Float, down: Long, time: Long) {
        val e: MotionEvent = MotionEvent.obtain(down, time, action, x, y, 0)
        assertTrue(root.dispatchTouchEvent(e))
        e.recycle()
    }

    private fun point(target: View?, root: View): FloatArray {
        val p: FloatArray = floatArrayOf(target!!.getWidth() / 2f, target!!.getHeight() / 2f)
        var v: View? = target
        while (v !== root) {
            v!!.getMatrix().mapPoints(p)
            val parent: View = v!!.getParent() as View
            p[0] += v!!.getLeft() - parent.getScrollX()
            p[1] += v!!.getTop() - parent.getScrollY()
            v = v!!.getParent() as View
        }
        return p
    }

    private fun logical(view: View, stop: View): FloatArray {
        val p: FloatArray = floatArrayOf(0f, 0f)
        var v: View = view
        while (v !== stop) {
            p[0] += v.getLeft()
            p[1] += v.getTop()
            v = v.getParent() as View
        }
        return p
    }

    private fun hasScrollParent(view: View): Boolean {
        var v: View = view
        while (v.getParent() is View) {
            if (v.getParent() is ScrollView) return true
            v = v.getParent() as View
        }
        return false
    }

    private fun scrollParent(view: View): ScrollView? {
        var v: View = view
        while (v.getParent() is View) {
            if (v.getParent() is ScrollView) return v.getParent() as ScrollView
            v = v.getParent() as View
        }
        return null
    }

    private fun text(view: View, value: String): TextView? {
        if (view is TextView && value.contentEquals((view as TextView).getText()))
            return view as TextView
        if (view is ViewGroup)
            for (i: Int in 0 until (view as ViewGroup).getChildCount()) {
                val result: TextView? = text((view as ViewGroup).getChildAt(i), value)
                if (result != null) return result
            }
        return null
    }
}

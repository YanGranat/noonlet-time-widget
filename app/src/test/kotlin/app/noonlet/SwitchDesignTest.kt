package app.noonlet

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextClock
import android.widget.TextView
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.After
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
@Config(sdk = [34, 36], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SwitchDesignTest {
    @Before
    fun setDarkTheme() {
        AppPreferences(RuntimeEnvironment.getApplication()).setThemeMode(ThemeMode.DARK)
    }

    @After
    fun clearTheme() {
        RuntimeEnvironment.getApplication().getSharedPreferences("noonlet_app", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    @Throws(Exception::class)
    fun whiteCircularThumbStaysInsideTrackInBothStates() {
        Robolectric.buildActivity(Activity::class.java)
            .setup()
            .use(
                { controller ->
                    val activity: Activity = controller.get()
                    val plate: LinearLayout = LinearLayout(activity)
                    plate.setOrientation(LinearLayout.VERTICAL)
                    plate.setBackgroundColor(AppTheme.palette(activity).surface)
                    activity.setContentView(plate, ViewGroup.LayoutParams(320, 256))
                    var offThumb: Rect? = null
                    for (index: Int in 0..3) {
                        val checked: Boolean = index % 2 == 1
                        val enabled: Boolean = index < 2
                        val row: LinearLayout = LinearLayout(activity)
                        row.setGravity(Gravity.CENTER_VERTICAL)
                        row.setPadding(16, 0, 16, 0)
                        val label: TextView = TextView(activity)
                        label.setText(
                            (if (enabled) "Enabled: " else "Disabled: ") +
                                (if (checked) "on" else "off"),
                        )
                        label.setTextColor(AppTheme.palette(activity).text)
                        row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
                        val toggle: Switch = styled(activity, checked)
                        toggle.setEnabled(enabled)
                        row.addView(toggle)
                        plate.addView(row, LinearLayout.LayoutParams(-1, 64))
                        layout(plate, 320, 256)
                        toggle.jumpDrawablesToCurrentState()
                        val image: Bitmap = draw(toggle)
                        if (enabled) {
                            val thumb: Rect = whiteBounds(image)
                            assertTrue("The thumb must remain clearly white", thumb.width() >= 22)
                            assertEquals(
                                "A switch thumb must be circular, not stretched to the track height",
                                thumb.width(),
                                thumb.height(),
                            )
                            assertTrue(
                                "The circular thumb must stay approximately 24dp",
                                thumb.width() <= 24,
                            )
                            val track: Rect = toggle.getTrackDrawable().getBounds()
                            assertTrue(
                                "The thumb must fit inside the track horizontally",
                                thumb.left >= track.left + 2 && thumb.right <= track.right - 2,
                            )
                            assertTrue(
                                "The thumb must fit inside the track vertically",
                                thumb.top >= track.top + 2 && thumb.bottom <= track.bottom - 2,
                            )
                            if (!checked) offThumb = thumb
                            else {
                                assertNotNull(offThumb)
                                assertEquals(
                                    "On/off should keep the same circular thumb",
                                    offThumb!!.width(),
                                    thumb.width(),
                                )
                                assertEquals(
                                    "On/off should keep the same vertical position",
                                    offThumb!!.top,
                                    thumb.top,
                                )
                                assertTrue(
                                    "The native switch must move the thumb to the opposite end",
                                    thumb.left - offThumb!!.left >= 22,
                                )
                            }
                        } else {
                            assertTrue(
                                "Disabled controls need a visibly muted thumb",
                                whiteBounds(image).isEmpty(),
                            )
                        }
                        image.recycle()
                    }
                    WidgetTestViews.prepare(plate)
                },
            )
    }

    @Test
    fun nativeTouchKeyboardAndDisabledBehaviorArePreserved() {
        Robolectric.buildActivity(Activity::class.java)
            .setup()
            .use(
                { controller ->
                    val activity: Activity = controller.get()
                    val holder: FrameLayout = FrameLayout(activity)
                    activity.setContentView(holder, ViewGroup.LayoutParams(160, 80))
                    for (custom: Boolean in booleanArrayOf(false, true)) {
                        holder.removeAllViews()
                        val toggle: Switch =
                            if (custom) styled(activity, false) else Switch(activity)
                        holder.addView(toggle, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                        layout(holder, 160, 80)
                        if (custom)
                            assertTrue(
                                "The switch needs a 48dp touch target",
                                toggle.getHeight() >= 48,
                            )
                        val changes: AtomicInteger = AtomicInteger()
                        toggle.setOnCheckedChangeListener(
                            { button, checked ->
                                changes.incrementAndGet()
                            },
                        )
                        tap(toggle)
                        assertTrue(toggle.isChecked())
                        assertEquals(1, changes.get())
                        toggle.setEnabled(false)
                        tap(toggle)
                        assertTrue("A disabled switch must ignore touch", toggle.isChecked())
                        assertEquals(1, changes.get())
                        toggle.setEnabled(true)
                        toggle.setFocusableInTouchMode(true)
                        assertTrue(toggle.requestFocus())
                        toggle.dispatchKeyEvent(
                            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER),
                        )
                        toggle.dispatchKeyEvent(
                            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER),
                        )
                        Shadows.shadowOf(Looper.getMainLooper()).idle()
                        assertFalse(
                            "Keyboard activation must use the native switch behavior",
                            toggle.isChecked(),
                        )
                        assertEquals(2, changes.get())
                        toggle.jumpDrawablesToCurrentState()
                        val beforeDrag: Bitmap = draw(toggle)
                        beforeDrag.recycle()
                        val start: Float = toggle.getThumbDrawable().getBounds().exactCenterX()
                        val move: Float =
                            start +
                                android.view.ViewConfiguration.get(activity).getScaledTouchSlop() +
                                2f
                        val end: Float = toggle.getTrackDrawable().getBounds().right + 24f
                        drag(toggle, floatArrayOf(start, move, end, start, start, start), true)
                        assertFalse(
                            "Returning the dragged knob to its original side must not behave like a click",
                            toggle.isChecked(),
                        )
                        assertEquals(2, changes.get())
                        drag(toggle, floatArrayOf(start, move, end, end), false)
                        assertTrue(
                            "The circular knob must retain the native drag gesture",
                            toggle.isChecked(),
                        )
                        assertEquals(3, changes.get())
                    }
                },
            )
    }

    private fun drag(toggle: Switch, positions: FloatArray, settleBeforeRelease: Boolean) {
        val now: Long = SystemClock.uptimeMillis()
        for (index: Int in positions.indices) {
            // Settle the returning gesture before release so this checks position, independently of
            // fling estimation.
            if (index > 0)
                Shadows.shadowOf(Looper.getMainLooper())
                    .idleFor(
                        Duration.ofMillis(
                            if (settleBeforeRelease && index == positions.size - 2) 250 else 30,
                        ),
                    )
            val action: Int =
                if (index == 0) MotionEvent.ACTION_DOWN
                else if (index == positions.size - 1) MotionEvent.ACTION_UP
                else MotionEvent.ACTION_MOVE
            val event: MotionEvent =
                MotionEvent.obtain(
                    now,
                    SystemClock.uptimeMillis(),
                    action,
                    positions[index],
                    toggle.getHeight() / 2f,
                    0,
                )
            toggle.dispatchTouchEvent(event)
            if (index == 1)
                assertEquals(
                    "The gesture must actually enter native drag mode",
                    2,
                    touchMode(toggle),
                )
            event.recycle()
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        toggle.jumpDrawablesToCurrentState()
    }

    private fun touchMode(toggle: Switch): Int {
        try {
            val field: java.lang.reflect.Field = Switch::class.java.getDeclaredField("mTouchMode")
            field.setAccessible(true)
            return field.getInt(toggle)
        } catch (error: ReflectiveOperationException) {
            throw AssertionError(error)
        }
    }

    @Test
    @Throws(Exception::class)
    fun narrowRussianSettingRowKeepsLabelAndNativeSwitchUsable() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit()
        RuntimeEnvironment.setFontScale(1.3f)
        AppLocales.setLanguage(context, "ru")
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    val root: View = requireNotNull(activity.window).getDecorView()
                    layout(root, 360, 640)
                    EditorTestInteractions.tap(root, "Показывать дату")
                    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                    layout(root, 360, 640)
                    val toggle: Switch? = findSwitch(root, "Показывать дату")
                    assertNotNull(toggle)
                    EditorTestInteractions.bringIntoView(root, toggle)
                    val visible: Rect = Rect()
                    assertTrue(toggle!!.getGlobalVisibleRect(visible))
                    assertEquals(toggle!!.getWidth(), visible.width())
                    assertEquals(toggle!!.getHeight(), visible.height())
                    assertFalse(toggle!!.isChecked())
                    assertEquals(
                        View.GONE,
                        root.findViewById<TextClock>(R.id.clock_date).getVisibility(),
                    )
                    tap(toggle)
                    Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                    layout(root, 360, 640)
                    assertTrue(toggle!!.isChecked())
                    assertEquals(
                        View.VISIBLE,
                        root.findViewById<TextClock>(R.id.clock_date).getVisibility(),
                    )
                    toggle!!.jumpDrawablesToCurrentState()
                    WidgetTestViews.prepare(root)
                },
            )
    }

    private fun styled(context: Context, checked: Boolean): Switch {
        val toggle: Switch = Switch(context)
        AppDialogBuilder.styleSwitch(toggle)
        toggle.setChecked(checked)
        return toggle
    }

    private fun tap(toggle: Switch?) {
        val now: Long = SystemClock.uptimeMillis()
        for (action: Int in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event: MotionEvent =
                MotionEvent.obtain(
                    now,
                    now + (if (action == MotionEvent.ACTION_UP) 20 else 0),
                    action,
                    toggle!!.getWidth() / 2f,
                    toggle!!.getHeight() / 2f,
                    0,
                )
            toggle!!.dispatchTouchEvent(event)
            event.recycle()
        }
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        toggle!!.jumpDrawablesToCurrentState()
    }

    private fun findSwitch(root: View, description: String): Switch? {
        if (root is Switch && description.contentEquals(root.getContentDescription()))
            return root as Switch
        if (root is ViewGroup)
            for (index: Int in 0 until (root as ViewGroup).getChildCount()) {
                val found: Switch? = findSwitch((root as ViewGroup).getChildAt(index), description)
                if (found != null) return found
            }
        return null
    }

    private fun whiteBounds(image: Bitmap): Rect {
        val bounds: Rect = Rect()
        for (y: Int in 0 until image.getHeight()) for (x: Int in 0 until image.getWidth()) {
            val color: Int = image.getPixel(x, y)
            if (
                Color.alpha(color) > 240 &&
                    Color.red(color) > 240 &&
                    Color.green(color) > 240 &&
                    Color.blue(color) > 240
            ) {
                if (bounds.isEmpty()) bounds.set(x, y, x + 1, y + 1)
                else bounds.union(x, y, x + 1, y + 1)
            }
        }
        return bounds
    }

    private fun draw(view: View): Bitmap {
        val bitmap: Bitmap =
            Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888)
        val canvas: Canvas = Canvas(bitmap)
        canvas.drawColor(AppTheme.palette(view.context).surface)
        view.draw(canvas)
        return bitmap
    }

    private fun layout(root: View, width: Int, height: Int) {
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        WidgetTestViews.prepare(root)
    }
}

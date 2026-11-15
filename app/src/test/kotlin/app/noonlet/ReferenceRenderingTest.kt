package app.noonlet

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.graphics.*
import android.os.Bundle
import android.provider.AlarmClock
import android.view.*
import android.view.View
import android.widget.*
import app.noonlet.WidgetTestViews.draw
import app.noonlet.WidgetTestViews.find
import app.noonlet.WidgetTestViews.weight
import java.util.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReferenceRenderingTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
    }

    @Test
    fun independentColorsFormatsPaddingAlignmentOffsetsAndWeightsRender() {
        val s: WidgetSettings = WidgetSettings()
        s.fontFamily = "serif"
        s.fontWeight = 400
        s.timeFontWeight = 900
        s.dateFontWeight = 700
        s.textSize = 40
        s.dateSize = 18
        s.alarmSize = 20
        s.showAlarm = true
        s.showNoAlarm = true
        s.timeColor = -0xedcc
        s.dateColor = -0xed00cc
        s.alarmColor = -0xedcb01
        s.paddingLeft = 11
        s.paddingTop = 3
        s.paddingRight = 17
        s.paddingBottom = 8
        s.alignment = WidgetAlignment.RIGHT
        s.dateAlignment = WidgetAlignment.LEFT
        s.timeFormat = "HH:mm:ss"
        s.dateFormat = "dd.MM.yyyy"
        s.timeOffsetX = 7
        s.timeOffsetY = -3
        s.dateOffsetX = 2
        s.dateOffsetY = 5
        s.alarmOffsetX = -4
        s.alarmOffsetY = 6
        val root: View = render(s, 2001, false, 360, 180, 360, 180)
        val time: TextClock = root.findViewById<TextClock>(R.id.clock_time)
        val date: TextClock = root.findViewById<TextClock>(R.id.clock_date)
        assertEquals(s.timeColor, time.getCurrentTextColor())
        assertEquals(s.dateColor, date.getCurrentTextColor())
        assertEquals(
            s.alarmColor,
            (root.findViewById<View>(R.id.clock_alarm) as TextView).getCurrentTextColor(),
        )
        assertEquals("HH:mm:ss", time.getFormat24Hour().toString())
        assertEquals("dd.MM.yyyy", date.getFormat24Hour().toString())
        assertEquals(
            "Time must align at its row's right edge",
            (time.getParent() as View).getWidth(),
            time.getRight(),
        )
        assertEquals("Date must align at its row's left edge", 0, date.getLeft())
        val c: View = root.findViewById<View>(R.id.clock_content)
        assertEquals(11, c.getPaddingLeft())
        assertEquals(3, c.getPaddingTop())
        assertEquals(17, c.getPaddingRight())
        assertEquals(8, c.getPaddingBottom())
        assertEquals(7f, time.getTranslationX(), 0f)
        assertEquals(-3f, time.getTranslationY(), 0f)
        assertEquals(2f, date.getTranslationX(), 0f)
        assertEquals(5f, date.getTranslationY(), 0f)
        assertTrue(time.getTextSize() > date.getTextSize())
        assertEquals(900, weight(time))
        assertEquals(700, weight(date))
        assertEquals(400, weight(root.findViewById(R.id.clock_alarm)))
        fun position(widget: View, horizontal: Boolean): Float {
            var child = widget.findViewById<View>(R.id.clock_alarm_row)
            var result = 0f
            while (child !== widget) {
                result += if (horizontal) child.x else child.y
                child = child.parent as View
            }
            return result
        }
        s.alarmOffsetX = 0
        val unshiftedX = render(s, 2001, false, 360, 180, 360, 180)
        assertEquals(-4f, position(root, true) - position(unshiftedX, true), .01f)
        s.alarmOffsetX = -4
        s.alarmOffsetY = 0
        val unshiftedY = render(s, 2001, false, 360, 180, 360, 180)
        assertEquals(6f, position(root, false) - position(unshiftedY, false), .01f)
        assertEquals(0, Color.alpha(draw(root).getPixel(0, 0)))
    }

    @Test
    fun orientationVisibilityAndGapAreRendered() {
        val s: WidgetSettings = WidgetSettings()
        s.dateOffsetX = 0
        s.dateOffsetY = 0
        s.timeDateGap = 12
        s.textSize = 44
        s.dateSize = 18
        val vertical: View = render(s, 2002, false, 350, 150, 350, 150)
        fun top(id: Int): Float {
            var view = vertical.findViewById<View>(id)
            var position = 0f
            while (view !== vertical) {
                position += view.y
                view = view.parent as View
            }
            return position
        }
        assertEquals(12f, top(R.id.clock_date) - top(R.id.clock_time) -
            vertical.findViewById<View>(R.id.clock_time).height, 1f)
        s.orientation = WidgetOrientation.HORIZONTAL
        val horizontal: View = render(s, 2002, false, 350, 150, 350, 150)
        val timeBox = android.graphics.Rect()
        val dateBox = android.graphics.Rect()
        horizontal.findViewById<View>(R.id.clock_time_row).getDrawingRect(timeBox)
        horizontal.findViewById<View>(R.id.clock_date_row).getDrawingRect(dateBox)
        (horizontal as ViewGroup).offsetDescendantRectToMyCoords(horizontal.findViewById(R.id.clock_time_row), timeBox)
        horizontal.offsetDescendantRectToMyCoords(horizontal.findViewById(R.id.clock_date_row), dateBox)
        assertTrue(dateBox.left >= timeBox.right)
        s.showDate = false
        s.showAlarm = true
        s.showNoAlarm = true
        s.showAlarmIcon = false
        val hidden: View = render(s, 2002, false, 350, 150, 350, 150)
        assertEquals(View.VISIBLE, hidden.findViewById<View>(R.id.clock_time).getVisibility())
        assertEquals(View.VISIBLE, hidden.findViewById<View>(R.id.clock_time_row).getVisibility())
        assertEquals(View.GONE, hidden.findViewById<View>(R.id.clock_date).getVisibility())
        assertEquals(View.VISIBLE, hidden.findViewById<View>(R.id.clock_alarm_row).getVisibility())
        assertEquals(View.GONE, hidden.findViewById<View>(R.id.clock_alarm_icon).getVisibility())
    }

    @Test
    fun directPreviewClampsTimeAndDateSizesBeforeFittingAndKeepsTimeVisibleOnReapply() {
        for (orientation in arrayOf("vertical", "horizontal")) {
            val settings =
                WidgetSettings().apply {
                    this.orientation = WidgetOrientation.fromWire(orientation)
                    textSize = 120
                    dateSize = 64
                    timeFormat = "'08:07'"
                    dateFormat = "'Mon, 12 Jan'"
                }
            val parent = FrameLayout(context)
            val payload = WidgetRenderer.preview(context, -2005, false, settings, 1600f, 900f)
            val root = payload.apply(context, parent)
            parent.addView(root, FrameLayout.LayoutParams(1600, 900))
            parent.measure(
                View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
            )
            parent.layout(0, 0, 1600, 900)
            val time = root.findViewById<TextClock>(R.id.clock_time)
            val date = root.findViewById<TextClock>(R.id.clock_date)
            assertEquals(65f * context.resources.displayMetrics.scaledDensity, time.textSize, .01f)
            assertEquals(35f * context.resources.displayMetrics.scaledDensity, date.textSize, .01f)
            time.visibility = View.GONE
            root.findViewById<View>(R.id.clock_time_row).visibility = View.GONE
            payload.reapply(context, root)
            assertEquals(View.VISIBLE, time.visibility)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.clock_time_row).visibility)
        }
    }

    @Test
    fun worldColorsAreIndependentAndShapesUseActualHostBounds() {
        val s: WidgetSettings = WidgetSettings.defaults(true)
        s.cities = arrayOf<String>("Europe/London", "Asia/Riyadh")
        s.worldDayTextColor = -0xeeddcd
        s.worldNightTextColor = -0x554434
        s.worldDayBackgroundColor = -0xbb9978
        s.worldNightBackgroundColor = -0xddef00
        s.style = WorldClockStyle.LIGHT
        val day: View = render(s, 2003, true, 150, 90, 167, 82)
        assertEquals(2, find(day, R.id.city_time).size)
        assertEquals(
            s.worldDayTextColor,
            (find(day, R.id.city_time).get(0) as TextView).getCurrentTextColor(),
        )
        val bg: ImageView = find(day, R.id.card_background).get(0) as ImageView
        val pixels: Bitmap = draw(bg)
        assertEquals(
            s.worldDayBackgroundColor,
            pixels.getPixel(pixels.getWidth() / 2, pixels.getHeight() / 2),
        )
        assertEquals(0, Color.alpha(pixels.getPixel(0, 0)))
        assertTrue(bg.getWidth() >= 155)
        s.style = WorldClockStyle.DARK
        val night: View = render(s, 2003, true, 150, 90, 167, 82)
        assertEquals(
            s.worldNightTextColor,
            (find(night, R.id.city_time).get(0) as TextView).getCurrentTextColor(),
        )
        val dark: Bitmap = draw(find(night, R.id.card_background).get(0))
        assertEquals(
            s.worldNightBackgroundColor,
            dark.getPixel(dark.getWidth() / 2, dark.getHeight() / 2),
        )
    }

    @Test
    fun worldBackdropIsFilledTranslucentAndKeepsRoundedHostBounds() {
        val s: WidgetSettings = WidgetSettings.defaults(true)
        s.cities = arrayOf<String>("Europe/London", "Asia/Riyadh")
        for (z: IntArray in
            arrayOf<IntArray>(
                intArrayOf(150, 90, 167, 82),
                intArrayOf(300, 150, 300, 150),
                intArrayOf(150, 90, 130, 75),
                intArrayOf(300, 150, 330, 160),
                intArrayOf(300, 90, 300, 150),
                intArrayOf(300, 90, 330, 160),
            )) {
            val root: View = render(s, 2100, true, z[0], z[1], z[2], z[3])
            val bg: ImageView = root.findViewById<ImageView>(R.id.widget_background)
            if (root.findViewById<View>(R.id.world_list_divider) != null) {
                assertEquals(View.GONE, bg.visibility)
                continue
            }
            val pixels: Bitmap = draw(bg)
            // GradientDrawable.modulateAlpha uses (255 * 102) >> 8, so its raster alpha is 101.
            assertEquals(View.VISIBLE, bg.getVisibility())
            assertEquals(102, bg.getImageAlpha())
            assertEquals(
                "Filled center",
                0x65000000,
                pixels.getPixel(pixels.getWidth() / 2, pixels.getHeight() / 2),
            )
            assertEquals(
                "Full fill at the edge",
                0x65000000,
                pixels.getPixel(0, pixels.getHeight() / 2),
            )
            assertEquals("Transparent rounded corner", 0, Color.alpha(pixels.getPixel(0, 0)))
            assertEquals(z[2], bg.getWidth())
            assertTrue(bg.getHeight() <= z[3])
            assertEquals(bg.getWidth(), bg.getDrawable().getBounds().width())
            assertEquals(bg.getHeight(), bg.getDrawable().getBounds().height())
            val rows: View = root.findViewById<View>(R.id.world_rows)
            assertEquals(
                "The filled backdrop must hug the actual rows",
                rows.getHeight(),
                bg.getHeight(),
            )
            assertEquals(rows.getTop(), bg.getTop())
            assertEquals(
                "Short home-screen cards are centered within the launcher allocation",
                (z[3] - bg.getHeight()) / 2,
                bg.getTop(),
            )
        }
        s.worldBackdropColor = -0x6699bbde
        val custom: Bitmap =
            draw(render(s, 2100, true, 150, 90, 167, 82).findViewById<View>(R.id.widget_background))
        assertEquals(153, Color.alpha(custom.getPixel(83, 41)))
        s.outline = false
        assertEquals(
            View.GONE,
            render(s, 2100, true, 150, 90, 167, 82)
                .findViewById<View>(R.id.widget_background)
                .getVisibility(),
        )
    }

    @Test
    fun nextSystemAlarmIconAndBroadcastRefreshUseRealAlarmState() {
        val s: WidgetSettings = WidgetSettings()
        s.showAlarm = true
        s.showNoAlarm = true
        s.showAlarmIcon = true
        s.alarmFormat = "HH:mm"
        assertEquals("No alarm", WidgetRenderer.alarmText(context, s))
        val manager: AlarmManager = context.getSystemService(AlarmManager::class.java)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val operation: PendingIntent =
            clockAlarm(
                context,
                5,
                Intent(context, UpdateReceiver::class.java).setAction("TEST_ALARM"),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val instant: Long = System.currentTimeMillis() + 3600000
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(instant, operation), operation)
        val expected: String =
            java.text.SimpleDateFormat("HH:mm", AppLocales.appLocale(context)).format(Date(instant))
        val root: View = render(s, 2004, false, 300, 150, 300, 150)
        assertEquals(
            expected,
            (root.findViewById<View>(R.id.clock_alarm) as TextView).getText().toString(),
        )
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.clock_alarm_icon).getVisibility())
        manager.cancel(operation)
        UpdateReceiver().onReceive(context, Intent(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED))
        val updated: View = Shadows.shadowOf(AppWidgetManager.getInstance(context)).getViewFor(2004)
        assertEquals(
            "No alarm",
            (updated.findViewById<View>(R.id.clock_alarm) as TextView).getText().toString(),
        )
    }

    @Test
    fun tapTargetsUseSelectedActivitiesAndAdvancedNoneHasNoParentFallback() {
        val info: ResolveInfo = ResolveInfo()
        info.activityInfo = ActivityInfo()
        info.activityInfo.name = "ClockActivity"
        info.activityInfo.packageName = "example.clock"
        info.activityInfo.exported = true
        info.activityInfo.enabled = info.activityInfo.exported
        info.activityInfo.applicationInfo = android.content.pm.ApplicationInfo()
        info.activityInfo.applicationInfo.enabled = true
        info.activityInfo.applicationInfo.flags = android.content.pm.ApplicationInfo.FLAG_SYSTEM
        Shadows.shadowOf(context.getPackageManager())
            .addResolveInfoForIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS), info)
        val clock: PendingIntent = requireNotNull(TapActions.pending(context, 15, false, "clock"))
        Robolectric.buildActivity(WidgetTapActivity::class.java, Shadows.shadowOf(clock).savedIntent)
            .create().use { controller ->
                assertEquals(AlarmClock.ACTION_SHOW_ALARMS,
                    Shadows.shadowOf(controller.get()).nextStartedActivity.action)
            }
        assertNull(TapActions.pending(context, 15, false, "none"))
        val s: WidgetSettings = WidgetSettings()
        s.advancedTap = true
        s.tapAction = "clock"
        s.timeTapAction = "clock"
        s.dateTapAction = "none"
        s.showAlarm = true
        s.showNoAlarm = true
        s.alarmTapAction = "settings"
        val root: View = render(s, 2005, false, 300, 150, 300, 150)
        assertFalse(root.hasOnClickListeners())
        assertFalse(root.findViewById<View>(R.id.clock_date).hasOnClickListeners())
        assertTrue(root.findViewById<View>(R.id.clock_time).hasOnClickListeners())
        assertTrue(root.findViewById<View>(R.id.clock_alarm_row).hasOnClickListeners())
    }

    private fun render(
        s: WidgetSettings,
        id: Int,
        world: Boolean,
        estimatedWidth: Int,
        estimatedHeight: Int,
        width: Int,
        height: Int,
    ): View {
        WidgetSettingsStore.save(context, id, world, s)
        val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
        val info: AppWidgetProviderInfo = AppWidgetProviderInfo()
        info.provider =
            ComponentName(
                context,
                if (world) WorldWidgetProvider::class.java else ClockWidgetProvider::class.java,
            )
        Shadows.shadowOf(manager).addBoundWidget(id, info)
        val options: Bundle = Bundle()
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, estimatedWidth)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, estimatedHeight)
        manager.updateAppWidgetOptions(id, options)
        val parent: FrameLayout = FrameLayout(context)
        val view: View =
            (if (world) WidgetRenderer.world(context, id) else WidgetRenderer.clock(context, id))
                .apply(context, parent)
        parent.addView(view, FrameLayout.LayoutParams(width, height))
        parent.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        parent.layout(0, 0, width, height)
        return view
    }
}

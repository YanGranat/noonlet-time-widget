package app.noonlet

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import app.noonlet.EditorTestInteractions.first
import app.noonlet.EditorTestInteractions.tap
import app.noonlet.EditorTestInteractions.text
import org.junit.After
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
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowAlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlarmDataTest {
    private lateinit var context: Context
    private lateinit var alarms: AlarmManager
    private lateinit var operation: PendingIntent
    private val previousZone = TimeZone.getDefault()

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        for (file in arrayOf("noonlet_widgets", "MainActivity", "widget_drafts", "widget_configuration"))
            context.getSharedPreferences(file, 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        alarms = context.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        operation = clockAlarm(context, 31002,
            Intent(context, UpdateReceiver::class.java).setAction("DATA_TEST_ALARM"),
            PendingIntent.FLAG_IMMUTABLE)
        WidgetSettingsStore.save(context, 0, false, WidgetSettings.defaults(false).apply {
            showAlarm = true
            zoneId = "Pacific/Auckland"
        })
    }

    @After
    fun restoreZone() {
        android.provider.Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING, null)
        TimeZone.setDefault(previousZone)
    }

    @Test
    fun standardClockUpdatesRemainVisibleWithUnrelatedDeviceText() {
        schedule(FIRST)
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.US, "EHm")
        fun deviceTime(timestamp: Long) {
            val value = SimpleDateFormat(pattern, Locale.US).format(Date(timestamp))
            android.provider.Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING, value)
            context.contentResolver.notifyChange(android.provider.Settings.System.getUriFor("next_alarm_clock_formatted"), null)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        deviceTime(FIRST + 67 * 60_000)
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            assertEquals("07:19", alarm(activity))
            tap(layout(activity), "Alarm time format")
            val picker = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(choice(picker).endsWith("07:19"))
            schedule(SECOND)
            deviceTime(SECOND)
            assertEquals("11:43", alarm(activity))
            assertTrue(choice(picker).endsWith("11:43"))
            assertTrue(picker.isShowing)
            controller.pause()
            schedule(FIRST)
            deviceTime(FIRST)
            assertEquals("11:43", alarm(activity))
            controller.resume()
            assertEquals("07:19", alarm(activity))
            assertTrue(choice(picker).endsWith("07:19"))
        }
    }

    @Test
    fun samplesAndPreviewFollowAlarmChangesAndCancellation() {
        schedule(FIRST)
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            assertEquals("07:19", alarm(activity))
            tap(layout(activity), "Alarm time format")
            val picker = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(choice(picker).endsWith("07:19"))
            schedule(SECOND)
            announce()
            assertEquals("11:43", alarm(activity))
            assertTrue(choice(picker).endsWith("11:43"))
            assertTrue(picker.isShowing)
            alarms.cancel(operation)
            announce()
            assertEquals("", alarm(activity))
            assertEquals(View.GONE, layout(activity).findViewById<View>(R.id.clock_alarm_row).visibility)
            assertTrue(choice(picker).endsWith("No alarm"))
            picker.dismiss()
        }
    }

    @Test
    fun hiddenEmptyAlarmsAppearOnSchedulingAndDisappearOnCancellation() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            assertFalse(activity.session.settings.showNoAlarm)
            assertEquals(View.GONE, layout(activity).findViewById<View>(R.id.clock_alarm_row).visibility)
            schedule(FIRST)
            announce()
            assertEquals("07:19", alarm(activity))
            assertEquals(View.VISIBLE, layout(activity).findViewById<View>(R.id.clock_alarm_row).visibility)
            alarms.cancel(operation)
            announce()
            assertEquals("", alarm(activity))
            assertEquals(View.GONE, layout(activity).findViewById<View>(R.id.clock_alarm_row).visibility)
        }
    }

    @Test
    fun retainedDeviceTextDisappearsOnCancellationWithEitherEmptyLabelPreference() {
        for (showEmpty in listOf(false, true)) {
            WidgetSettingsStore.save(context, 0, false, WidgetSettings.defaults(false).apply {
                showAlarm = true
                showNoAlarm = showEmpty
            })
            schedule(FIRST)
            android.provider.Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING,
                SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(FIRST)))
            Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
                val activity = controller.get()
                assertEquals("07:19", alarm(activity))
                alarms.cancel(operation)
                announce()
                assertEquals(if (showEmpty) "No alarm" else "", alarm(activity))
                assertEquals(if (showEmpty) View.VISIBLE else View.GONE,
                    layout(activity).findViewById<View>(R.id.clock_alarm_row).visibility)
                controller.pause().resume()
                assertEquals(if (showEmpty) "No alarm" else "", alarm(activity))
            }
        }
    }

    @Test
    fun deviceClockUpdatesAndCancellationRefreshBothEmptyLabelPreferences() {
        org.robolectric.shadows.ShadowBuild.setManufacturer("Xiaomi")
        fun publish(timestamp: Long?) {
            val value = timestamp?.let { SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(it)) } ?: ""
            android.provider.Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING, value)
            context.contentResolver.notifyChange(DeviceAlarmClock.uri, null)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        for (showEmpty in listOf(false, true)) {
            WidgetSettingsStore.save(context, 0, false, WidgetSettings.defaults(false).apply {
                showAlarm = true
                showNoAlarm = showEmpty
            })
            publish(FIRST)
            assertNull(alarms.nextAlarmClock)
            Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
                val activity = controller.get()
                assertEquals("07:19", alarm(activity))
                publish(SECOND)
                assertEquals("11:43", alarm(activity))
                publish(null)
                assertEquals(if (showEmpty) "No alarm" else "", alarm(activity))
                controller.pause().resume()
                assertEquals(if (showEmpty) "No alarm" else "", alarm(activity))
            }
        }
    }

    @Test
    fun emptyAlarmLabelTogglePersistsAndRestoresIndependently() {
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            var activity = controller.get()
            tap(layout(activity), "Show “No alarm”")
            assertTrue(activity.session.settings.showNoAlarm)
            assertEquals("No alarm", alarm(activity))
            assertEquals(View.VISIBLE, layout(activity).findViewById<View>(R.id.clock_alarm_row).visibility)
            assertTrue(WidgetSettingsStore.loadUnplaced(context, false).showNoAlarm)
            controller.recreate()
            activity = controller.get()
            assertTrue(activity.session.settings.showNoAlarm)
            tap(layout(activity), "Show “No alarm”")
            assertFalse(activity.session.settings.showNoAlarm)
            assertEquals(View.GONE, layout(activity).findViewById<View>(R.id.clock_alarm_row).visibility)
            assertFalse(WidgetSettingsStore.loadUnplaced(context, false).showNoAlarm)
        }
    }

    @Test
    fun customSampleUsesTheUnsavedPatternAndCurrentAlarm() {
        schedule(FIRST)
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            tap(layout(activity), "Alarm time format")
            val picker = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            picker.listView.performItemClick(null, 2, 2)
            val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val decor = requireNotNull(dialog.window).decorView
            val input = requireNotNull(first(decor, EditText::class.java))
            input.setText("h:mm a")
            assertNotNull(text(decor, "7:19 AM"))
            schedule(SECOND)
            announce()
            assertNotNull(text(decor, "11:43 AM"))
            assertEquals("h:mm a", input.text.toString())
            assertEquals("HH:mm", activity.session.settings.alarmFormat)
            assertEquals("HH:mm", WidgetSettingsStore.loadUnplaced(context, false).alarmFormat)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            schedule(FIRST)
            announce()
            assertFalse(dialog.isShowing)
            assertEquals("HH:mm", activity.session.settings.alarmFormat)
        }
    }

    @Test
    fun alarmEventsDoNotPersistAnActiveAppearanceAdjustment() {
        schedule(FIRST)
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            layout(activity)
            activity.preview.beginAdjustment()
            activity.session.settings.alarmSize = 23
            activity.preview.queueFull()
            schedule(SECOND)
            announce()
            assertEquals("11:43", alarm(activity))
            assertEquals(23, activity.session.settings.alarmSize)
            assertEquals(14, WidgetSettingsStore.loadUnplaced(context, false).alarmSize)
            activity.preview.finishAdjustment()
            assertEquals(23, WidgetSettingsStore.loadUnplaced(context, false).alarmSize)
        }
    }

    @Test
    fun pausedEditorRefreshesOnResumeAndDestroyedEditorHasNoReceiver() {
        schedule(FIRST)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        val activity = controller.get()
        assertEquals("07:19", alarm(activity))
        controller.pause()
        schedule(SECOND)
        announce()
        assertEquals("07:19", alarm(activity))
        controller.resume()
        assertEquals("11:43", alarm(activity))
        val application = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        val receiver = application.registeredReceivers.single {
            it.broadcastReceiver.javaClass.enclosingClass == MainActivity::class.java &&
                it.intentFilter.hasAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }
        controller.pause().stop().destroy()
        assertFalse(application.registeredReceivers.contains(receiver))
        alarms.cancel(operation)
        announce()
    }

    @Test
    fun formattingUsesTheSystemZoneAndAppLocaleWithASafeInvalidPatternFallback() {
        schedule(FIRST)
        AppLocales.setLanguage(context, "de")
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        val settings = WidgetSettings.defaults(false).apply {
            zoneId = "America/Los_Angeles"
            alarmFormat = "EEEE HH:mm"
        }
        val expected = SimpleDateFormat(settings.alarmFormat, Locale.GERMAN).format(Date(FIRST))
        assertEquals(expected, WidgetRenderer.alarmText(context, settings))
        settings.alarmFormat = "invalid ["
        assertEquals("08:19", WidgetRenderer.alarmText(context, settings))
        alarms.cancel(operation)
        assertEquals(AppLocales.text(context, R.string.no_alarm), WidgetRenderer.alarmText(context, settings))
    }

    private fun schedule(trigger: Long) {
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, operation), operation)
    }

    private fun announce() {
        context.sendBroadcast(Intent(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun alarm(activity: MainActivity): String =
        layout(activity).findViewById<TextView>(R.id.clock_alarm).text.toString()

    private fun layout(activity: MainActivity): View {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val root = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        root.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 400, 850)
        WidgetTestViews.prepare(root)
        return root
    }

    private fun choice(dialog: AlertDialog) = dialog.listView.adapter.getItem(0).toString()

    companion object {
        private const val FIRST = 1893568740000L // 2030-01-02 07:19 UTC
        private const val SECOND = FIRST + 264 * 60000L
    }
}

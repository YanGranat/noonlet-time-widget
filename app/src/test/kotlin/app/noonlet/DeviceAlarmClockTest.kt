package app.noonlet

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.icu.text.DateTimePatternGenerator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36], qualifiers = "en-rUS")
class DeviceAlarmClockTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val originalZone = TimeZone.getDefault()
    private val alarms: AlarmManager get() = context.getSystemService(AlarmManager::class.java)
    private lateinit var operation: PendingIntent

    @Before fun prepare() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        operation = clockAlarm(context, 31006,
            Intent(context, UpdateReceiver::class.java).setAction("ALARM_SOURCE_TEST"),
            PendingIntent.FLAG_IMMUTABLE)
        Settings.System.putString(context.contentResolver, "next_alarm_clock_formatted", null)
    }

    @After fun cleanup() {
        alarms.cancel(operation)
        Settings.System.putString(context.contentResolver, "next_alarm_clock_formatted", null)
        TimeZone.setDefault(originalZone)
    }

    @Test fun unrelatedDeviceTextDoesNotHideAStandardClockAlarm() {
        val midnight = Instant.parse("2030-01-02T00:00:00Z").toEpochMilli()
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(midnight, operation), operation)
        val clockTime = midnight + (8 * 60 + 26) * 60_000L
        val pattern = DateTimePatternGenerator.getInstance(Locale.US).getBestPattern("EHm")
        val formatted = SimpleDateFormat(pattern, Locale.US).format(Date(clockTime))
        Settings.System.putString(context.contentResolver, "next_alarm_clock_formatted", formatted)
        assertEquals(midnight, SystemAlarmClock.triggerTime(context))
    }

    @Test fun deviceClockSettingCanPublishAnAlarmWithoutAPlatformEvent() {
        org.robolectric.shadows.ShadowBuild.setManufacturer("Xiaomi")
        val timestamp = Instant.parse("2030-01-02T08:26:00Z").toEpochMilli()
        val pattern = DateTimePatternGenerator.getInstance(Locale.US).getBestPattern("EHm")
        Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING,
            SimpleDateFormat(pattern, Locale.US).format(Date(timestamp)))
        assertEquals("08:26", SystemAlarmClock.format(context, "HH:mm", SystemAlarmClock.triggerTime(context)))
        Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING, "")
        assertNull(SystemAlarmClock.triggerTime(context))
    }

    @Test fun deviceClockStateIsIndependentOfAnUnrelatedPlatformEvent() {
        org.robolectric.shadows.ShadowBuild.setManufacturer("Xiaomi")
        val midnight = Instant.parse("2030-01-02T00:00:00Z").toEpochMilli()
        val unrelated = PendingIntent.getBroadcast(context, 31008, Intent("MAINTENANCE_ALARM"),
            PendingIntent.FLAG_IMMUTABLE)
        try {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(midnight, unrelated), unrelated)
            Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING, "Wed 08:26")
            assertEquals("08:26", SystemAlarmClock.format(context, "HH:mm", SystemAlarmClock.triggerTime(context)))
            Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING, "")
            assertNull(SystemAlarmClock.triggerTime(context))
            assertEquals(midnight, alarms.nextAlarmClock.triggerTime)
        } finally { alarms.cancel(unrelated) }
    }

    @Test fun deviceClockMidnightIsValidAndAnEmptySettingIsAuthoritative() {
        org.robolectric.shadows.ShadowBuild.setBrand("Redmi")
        val midnight = Instant.parse("2030-01-02T00:00:00Z").toEpochMilli()
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(midnight, operation), operation)
        Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING, "Wed 00:00")
        assertEquals("00:00", SystemAlarmClock.format(context, "HH:mm", SystemAlarmClock.triggerTime(context)))
        Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING, "")
        assertNull(SystemAlarmClock.triggerTime(context))
    }

    @Test fun unreadableDeviceTextDoesNotVetoAConfirmedClockAlarm() {
        org.robolectric.shadows.ShadowBuild.setBrand("POCO")
        val timestamp = Instant.parse("2030-01-02T08:26:00Z").toEpochMilli()
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(timestamp, operation), operation)
        for (value in listOf(null, "unrecognized")) {
            Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING, value)
            assertEquals(timestamp, SystemAlarmClock.triggerTime(context))
        }
    }

    @Test fun platformClockSourcesRemainAvailableAcrossDeviceManufacturers() {
        val packageName = "example.device.clock"
        val handler = android.content.pm.ResolveInfo().apply {
            activityInfo = android.content.pm.ActivityInfo().apply {
                this.packageName = packageName
                name = "$packageName.Alarms"
                enabled = true
                exported = true
                applicationInfo = android.content.pm.ApplicationInfo().apply {
                    this.packageName = packageName
                    enabled = true
                    flags = android.content.pm.ApplicationInfo.FLAG_SYSTEM
                }
            }
        }
        org.robolectric.Shadows.shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS).setPackage(packageName), handler)
        org.robolectric.Shadows.shadowOf(operation).setCreatorPackage(packageName)
        val timestamp = Instant.parse("2030-01-02T08:26:00Z").toEpochMilli()
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(timestamp, operation), operation)
        Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING, "Wed 14:12")
        for (manufacturer in listOf("Google", "Samsung", "OnePlus", "Nothing")) {
            org.robolectric.shadows.ShadowBuild.setManufacturer(manufacturer)
            assertEquals(manufacturer, timestamp, SystemAlarmClock.triggerTime(context))
        }
    }

    @Test fun cancellationDoesNotRestoreTheAlarmFromRetainedDeviceText() {
        val timestamp = Instant.parse("2030-01-02T08:26:00Z").toEpochMilli()
        assertEquals("com.android.deskclock", operation.creatorPackage)
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(timestamp, operation), operation)
        val pattern = DateTimePatternGenerator.getInstance(Locale.US).getBestPattern("EHm")
        Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING,
            SimpleDateFormat(pattern, Locale.US).format(Date(timestamp)))
        assertEquals(timestamp, SystemAlarmClock.triggerTime(context))
        alarms.cancel(operation)
        assertNull(SystemAlarmClock.triggerTime(context))
    }

    @Test fun anUnrelatedApplicationAlarmDoesNotBecomeTheDeviceClockAlarm() {
        val midnight = Instant.parse("2030-01-02T00:00:00Z").toEpochMilli()
        val unrelated = PendingIntent.getBroadcast(context, 31007, Intent("UNRELATED_ALARM"),
            PendingIntent.FLAG_IMMUTABLE)
        try {
            alarms.setAlarmClock(AlarmManager.AlarmClockInfo(midnight, unrelated), unrelated)
            assertNull(SystemAlarmClock.triggerTime(context))
            Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING, "Wed 08:26")
            assertNull(SystemAlarmClock.triggerTime(context))
        } finally {
            alarms.cancel(unrelated)
        }
    }

    @Test fun anExpiredTimestampIsNotDisplayedAsAnUpcomingAlarm() {
        val expired = System.currentTimeMillis() - 60_000
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(expired, operation), operation)
        assertNull(SystemAlarmClock.triggerTime(context))
    }

    @Test fun ordinaryMidnightAlarmRemainsValidWithoutADeviceOverride() {
        val midnight = Instant.parse("2030-01-02T00:00:00Z").toEpochMilli()
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(midnight, operation), operation)
        assertEquals(midnight, SystemAlarmClock.triggerTime(context))
        assertEquals("00:00", SystemAlarmClock.format(context, "HH:mm", SystemAlarmClock.triggerTime(context)))
    }

    @Test fun aDeviceAlarmAtMidnightIsAlsoAValidOccurrence() {
        val midnight = Instant.parse("2030-01-02T00:00:00Z").toEpochMilli()
        assertEquals(midnight, parsed(midnight, null, midnight - 3_600_000))
    }

    @Test fun observationIsScopedToItsSettingAndRegistrationLifetime() {
        var changes = 0
        val observer = SystemAlarmClock.observeDevice(context) { changes++ }
        context.contentResolver.notifyChange(Settings.System.getUriFor("next_alarm_clock_formatted"), null)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, changes)
        context.contentResolver.notifyChange(Settings.System.getUriFor("unrelated_alarm_test"), null)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, changes)
        observer.close()
        context.contentResolver.notifyChange(Settings.System.getUriFor("next_alarm_clock_formatted"), null)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, changes)
    }

    @Test fun weekdayTimeParsingKeepsThePlatformDateWhenItAgrees() {
        val timestamp = Instant.parse("2030-01-02T08:26:00Z").toEpochMilli()
        assertEquals(timestamp, parsed(timestamp, timestamp, timestamp - 3_600_000))
    }

    @Test fun anEarlierTimeOnTheSameWeekdayMovesToTheFollowingWeek() {
        val timestamp = Instant.parse("2030-01-02T08:26:00Z").toEpochMilli()
        assertEquals(timestamp + 7 * 86_400_000L, parsed(timestamp, null, timestamp + 60_000))
    }

    @Test fun everySupportedLocaleAndBothHourCyclesPreserveTheClockTime() {
        val timestamp = Instant.parse("2030-01-02T18:26:00Z").toEpochMilli()
        for (tag in AppLocales.supportedTags) for (skeleton in listOf("EHm", "Ehma")) {
            val locale = Locale.forLanguageTag(tag)
            val pattern = DateTimePatternGenerator.getInstance(locale).getBestPattern(skeleton)
            val text = SimpleDateFormat(pattern, locale).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(timestamp))
            assertEquals("$tag $skeleton: $pattern / $text", timestamp,
                DeviceAlarmClock.parse(text, locale, java.time.ZoneOffset.UTC, timestamp - 60_000, null))
        }
    }

    @Test fun missingAndMalformedValuesKeepTheOrdinaryPlatformAlarm() {
        val timestamp = Instant.parse("2030-01-02T18:26:00Z").toEpochMilli()
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(timestamp, operation), operation)
        for (value in listOf(null, "", " ", "unknown", "Wed 28:99", "Wed 08:26 trailing")) {
            Settings.System.putString(context.contentResolver, DeviceAlarmClock.SETTING, value)
            assertEquals(value, timestamp, SystemAlarmClock.triggerTime(context))
        }
        alarms.cancel(operation)
        assertNull(SystemAlarmClock.triggerTime(context))
    }

    @Test fun daylightSavingOverlapUsesTheNextValidOccurrence() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val first = Instant.parse("2030-10-27T00:30:00Z").toEpochMilli()
        val text = SimpleDateFormat(DateTimePatternGenerator.getInstance(Locale.US).getBestPattern("EHm"), Locale.US)
            .apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(first))
        val second = first + 3_600_000
        assertEquals(second, DeviceAlarmClock.parse(text, Locale.US, zone, first + 60_000, null))
        assertEquals(second, DeviceAlarmClock.parse(text, Locale.US, zone, first - 60_000, second))
    }

    @Test fun aNonexistentLocalTimeDoesNotInventADifferentCalendarOccurrence() {
        val zone = java.time.ZoneId.of("Europe/Berlin")
        val now = Instant.parse("2030-03-31T00:10:00Z").toEpochMilli()
        assertNull(DeviceAlarmClock.parse("Sun 02:30", Locale.US, zone, now, null))
    }

    private fun parsed(timestamp: Long, platform: Long?, now: Long): Long? {
        val text = SimpleDateFormat(DateTimePatternGenerator.getInstance(Locale.US).getBestPattern("EHm"), Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(timestamp))
        return DeviceAlarmClock.parse(text, Locale.US, java.time.ZoneOffset.UTC, now, platform)
    }
}

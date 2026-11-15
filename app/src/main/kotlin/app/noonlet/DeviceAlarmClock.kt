package app.noonlet

import android.content.Context
import android.os.Build
import android.icu.text.DateTimePatternGenerator
import android.provider.Settings
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

internal object DeviceAlarmClock {
    const val SETTING = "next_alarm_clock_formatted"
    val uri get() = Settings.System.getUriFor(SETTING)

    fun triggerTime(context: Context, platformTime: Long?): Long? {
        val platform = platformTime?.takeIf { it > System.currentTimeMillis() }
        if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) &&
            !Build.BRAND.equals("Redmi", ignoreCase = true) &&
            !Build.BRAND.equals("POCO", ignoreCase = true)) return platform
        val value = try {
            Settings.System.getString(context.contentResolver, SETTING)
        } catch (_: SecurityException) {
            null
        } ?: return platform
        // The device Clock publishes its own state independently of AlarmManager events.
        if (value.isBlank()) return null
        return parse(value, AppLocales.phoneLocale(context), ZoneId.systemDefault(),
            System.currentTimeMillis(), platform) ?: platform
    }

    fun parse(value: String?, locale: Locale, zone: ZoneId, now: Long, platformTime: Long?): Long? {
        if (value.isNullOrBlank()) return null
        val original = value.trim()
        val utc = TimeZone.getTimeZone("UTC")
        val patterns = DateTimePatternGenerator.getInstance(locale)
        for (skeleton in listOf("EHm", "Ehma")) for (normalizeSpaces in listOf(false, true)) {
            val text = if (normalizeSpaces) spaces(original) else original
            val bestPattern = patterns.getBestPattern(skeleton)
            val pattern = if (normalizeSpaces) spaces(bestPattern) else bestPattern
            val formatter = SimpleDateFormat(pattern, locale).apply {
                timeZone = utc
                // A weekday-only pattern has no complete calendar date; validate by round trip.
                isLenient = true
                if (normalizeSpaces) dateFormatSymbols = dateFormatSymbols.apply {
                    amPmStrings = amPmStrings.map(::spaces).toTypedArray()
                    shortWeekdays = shortWeekdays.map(::spaces).toTypedArray()
                    weekdays = weekdays.map(::spaces).toTypedArray()
                }
            }
            val position = ParsePosition(0)
            val parsed = formatter.parse(text, position) ?: continue
            if (position.index != text.length) continue
            if (!spaces(formatter.format(parsed)).equals(spaces(original), ignoreCase = true)) continue
            val calendar = Calendar.getInstance(utc, locale).apply { time = parsed }
            val weekday = calendar.get(Calendar.DAY_OF_WEEK)
            val day = DayOfWeek.of(if (weekday == Calendar.SUNDAY) 7 else weekday - 1)
            val time = LocalTime.of(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
            // Formatted device settings omit the calendar date.
            platformTime?.takeIf { it >= now }?.let { timestamp ->
                val platform = Instant.ofEpochMilli(timestamp).atZone(zone)
                if (platform.dayOfWeek == day && platform.toLocalTime().withSecond(0).withNano(0) == time)
                    return timestamp
            }
            val current = Instant.ofEpochMilli(now).atZone(zone)
            val date = current.toLocalDate().with(TemporalAdjusters.nextOrSame(day))
            for (candidate in listOf(date, date.plusWeeks(1))) {
                val local = candidate.atTime(time)
                val offsets = zone.rules.getValidOffsets(local)
                if (offsets.isEmpty()) return null
                val next = offsets.map { offset ->
                    local.toInstant(offset).toEpochMilli()
                }.filter { it >= now }.minOrNull()
                if (next != null) return next
            }
        }
        return null
    }

    private fun spaces(value: String) = value.replace('\u00a0', ' ').replace('\u202f', ' ')
}

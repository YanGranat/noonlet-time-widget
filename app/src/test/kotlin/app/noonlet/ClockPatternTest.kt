package app.noonlet

import java.time.Instant
import java.util.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36])
class ClockPatternTest {
    @Test
    fun previewsMatchActualNativeFormatterAcrossTokensAndTimeZones() {
        val previous: Locale = Locale.getDefault()
        try {
            for (locale: Locale in arrayOf<Locale>(Locale.ENGLISH, Locale("ru"), Locale("ar"))) {
                Locale.setDefault(locale)
                for (zoneId: String in
                    arrayOf<String>(
                        "UTC",
                        "America/Los_Angeles",
                        "Asia/Kathmandu",
                        "Europe/Berlin",
                    )) {
                    val zone: TimeZone = TimeZone.getTimeZone(zoneId)
                    for (instant: String in
                        arrayOf<String>(
                            "2026-01-01T00:00:00Z",
                            "2026-07-06T12:34:56Z",
                            "2026-12-31T23:59:59Z",
                            "2006-03-10T09:08:07Z",
                        )) {
                        val timestamp: Long = Instant.parse(instant).toEpochMilli()
                        val calendar: Calendar = GregorianCalendar(zone, locale)
                        calendar.setTimeInMillis(timestamp)
                        for (token: String in
                            arrayOf<String>(
                                "a",
                                "c",
                                "d",
                                "E",
                                "H",
                                "h",
                                "K",
                                "k",
                                "L",
                                "M",
                                "m",
                                "s",
                                "y",
                                "z",
                            )) {
                            for (width: Int in 1..6) {
                                assertNative(locale, zone, timestamp, calendar, token.repeat(width))
                            }
                        }
                        for (pattern: String in
                            arrayOf<String>(
                                "EEE, d MMM",
                                "HH:mm 'o''clock'",
                                "'YYYY' yyyy-MM-dd",
                                "''HH:mm''",
                                "'Русский текст' ccccc LLLLL z zz",
                                "HH':'mm 'in' zzz",
                                "z'UTC'",
                                "'zone'z",
                                "zMMMMMM",
                                "''z''",
                                "'o''clock'z'zone'",
                                "MMMMMM' month'",
                                "''''",
                                "' a 'z' b 'z",
                                "'Year 2026 / 123' yyyy-MM-dd HH:mm:ss",
                            )) assertNative(locale, zone, timestamp, calendar, pattern)
                    }
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun explicitLocaleDoesNotMutateProcessLocale() {
        val previous: Locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            ClockPattern.formatAt("EEEE, d MMMM yyyy", 0, TimeZone.getTimeZone("UTC"), Locale("ru"))
            assertEquals(Locale.ENGLISH, Locale.getDefault())
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun reusedBatchFormatterMatchesNativeAcrossDatesOffsetsAndLiteralChanges() {
        val previous: Locale = Locale.getDefault()
        try {
            for (locale: Locale in arrayOf<Locale>(Locale.ENGLISH, Locale("ru"), Locale("ar"))) {
                Locale.setDefault(locale)
                for (zoneId: String in arrayOf<String>("UTC", "Europe/London", "Asia/Kathmandu")) {
                    val zone: TimeZone = TimeZone.getTimeZone(zoneId)
                    for (pattern: String in
                        arrayOf<String>(
                            "EEEE HH:mm",
                            "MMMMMM 'month' zz",
                            "z'UTC'",
                            "yyyy-MM-dd HH:mm:ss",
                            "'o''clock' EEEEE LLLLL",
                        )) {
                        val formatter: ClockPattern.Formatter =
                            ClockPattern.Formatter(pattern, zone, locale)
                        for (instant: String in
                            arrayOf<String>(
                                "2026-01-01T00:00:00Z",
                                "2026-07-06T12:34:56Z",
                                "2026-12-31T23:59:59Z",
                                "2026-01-01T00:00:00Z",
                            )) {
                            val timestamp: Long = Instant.parse(instant).toEpochMilli()
                            val calendar: Calendar = GregorianCalendar(zone, locale)
                            calendar.setTimeInMillis(timestamp)
                            assertEquals(
                                locale.toString() + " / " + zoneId + " / " + pattern,
                                android.text.format.DateFormat.format(pattern, calendar).toString(),
                                formatter.format(timestamp),
                            )
                        }
                    }
                }
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun nativeYearWidthsDoNotAcquireIcuPadding() {
        val previous: Locale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            val zone: TimeZone = TimeZone.getTimeZone("UTC")
            val calendar: Calendar = GregorianCalendar(zone, Locale.ENGLISH)
            calendar.clear()
            calendar.set(7, Calendar.JANUARY, 2, 0, 0, 0)
            assertEquals(
                "07/07/7/7/7/7",
                android.text.format.DateFormat.format("y/yy/yyy/yyyy/yyyyy/yyyyyy", calendar)
                    .toString(),
            )
            assertNative(
                Locale.ENGLISH,
                zone,
                calendar.getTimeInMillis(),
                calendar,
                "y/yy/yyy/yyyy/yyyyy/yyyyyy",
            )
        } finally {
            Locale.setDefault(previous)
        }
    }

    private fun assertNative(
        locale: Locale,
        zone: TimeZone,
        timestamp: Long,
        calendar: Calendar,
        pattern: String,
    ) {
        val expected: String = android.text.format.DateFormat.format(pattern, calendar).toString()
        assertEquals(
            locale.toString() + " / " + zone.getID() + " / " + timestamp + " / " + pattern,
            expected,
            ClockPattern.formatAt(pattern, timestamp, zone, locale),
        )
    }

    @Test
    fun rejectsNonNativeLettersAndUnbalancedQuotes() {
        for (token: String in
            arrayOf<String>(
                "a",
                "c",
                "d",
                "E",
                "H",
                "h",
                "K",
                "k",
                "L",
                "M",
                "m",
                "s",
                "y",
                "z",
            )) for (width: Int in 1..6) assertTrue(ClockPattern.validate(token.repeat(width)))
        for (invalid: String in
            arrayOf<String>(
                "YYYY-MM-dd",
                "HH:mm:ss.SSS",
                "DDD",
                "ZZZZ",
                "week w",
                "invalid'",
                "'unterminated",
                "текст HH:mm",
                "",
            )) assertFalse(invalid, ClockPattern.validate(invalid))
        assertFalse(ClockPattern.validate(null))
        for (valid: String in
            arrayOf<String>(
                "yyyy-MM-dd",
                "HH:mm 'o''clock'",
                "'YYYY' MM dd",
                "EEE, d MMM",
                "''HH:mm''",
                "'Русский текст' HH:mm",
            )) assertTrue(valid, ClockPattern.validate(valid))
    }
}

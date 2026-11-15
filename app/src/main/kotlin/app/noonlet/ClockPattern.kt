package app.noonlet

import android.icu.text.SimpleDateFormat
import android.text.SpannableString
import android.text.Spanned
import android.text.style.MetricAffectingSpan
import java.util.Calendar
import java.util.Date
import java.util.GregorianCalendar
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone

object ClockPattern {
    private const val TOKENS = "acdEHhKkLMmsyz"
    private val PATTERN_SAMPLES = LinkedHashMap<List<String>, Array<String>>(8, .75f, true)

    fun validate(pattern: String?): Boolean {
        if (pattern == null || pattern.trim { it <= ' ' }.isEmpty() || pattern.length > 96)
            return false
        var quoted = false
        var index = 0
        while (index < pattern.length) {
            val letter = pattern[index]
            if (letter == '\'') {
                if (index + 1 < pattern.length && pattern[index + 1] == '\'') index++
                else quoted = !quoted
            } else if (!quoted && Character.isLetter(letter) && letter !in TOKENS) return false
            index++
        }
        return !quoted
    }

    /** The in-app clock uses phone symbols without changing this process's application locale. */
    fun previewLiteral(
        pattern: CharSequence,
        timestamp: Long,
        zone: TimeZone,
        locale: Locale,
        formatter: Formatter? = null,
    ): CharSequence {
        val value = formatter?.format(timestamp) ?: formatAt(pattern.toString(), timestamp, zone, locale)
        val result = SpannableString(if (value.isEmpty()) "" else "'${value.replace("'", "''")}'")
        if (pattern is Spanned && result.isNotEmpty()) {
            for (span in
                pattern.getSpans(0, pattern.length, MetricAffectingSpan::class.java)) result
                .setSpan(span, 0, result.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return result
    }

    fun hasSeconds(pattern: CharSequence) = hasAnyToken(pattern, "s")

    fun hasAnyToken(pattern: CharSequence, tokens: String): Boolean {
        var quoted = false
        var index = 0
        while (index < pattern.length) {
            val token = pattern[index]
            if (token == '\'') {
                if (index + 1 < pattern.length && pattern[index + 1] == '\'') index++
                else quoted = !quoted
            } else if (!quoted && token in tokens) return true
            index++
        }
        return false
    }

    fun formatAt(pattern: String, timestamp: Long, zone: TimeZone, locale: Locale) =
        Formatter(pattern, zone, locale).format(timestamp)

    @Synchronized
    internal fun fittingSamples(pattern: String, locale: Locale, zone: TimeZone): Array<String> {
        val key = listOf(pattern, locale.toLanguageTag(), zone.id)
        PATTERN_SAMPLES[key]?.let {
            return it
        }
        val sample =
            Calendar.getInstance(zone, locale).apply {
                set(2026, Calendar.SEPTEMBER, 28, 23, 58, 58)
            }
        val dates = hasAnyToken(pattern, "cdELMyz")
        val hours = hasAnyToken(pattern, "aHhKkms")
        val unique = LinkedHashSet<String>()
        val formatter = Formatter(pattern, zone, locale)
        for (day in 0 until (if (dates) 84 else 1)) {
            if (dates) {
                sample.set(Calendar.MONTH, day / 7)
                sample.set(Calendar.DAY_OF_MONTH, 22 + day % 7)
            }
            for (hour in 0 until (if (hours) 24 else 1)) {
                if (hours) sample.set(Calendar.HOUR_OF_DAY, hour)
                unique += formatter.format(sample.timeInMillis)
            }
        }
        val formatted = unique.toTypedArray()
        PATTERN_SAMPLES[key] = formatted
        if (PATTERN_SAMPLES.size > 8) PATTERN_SAMPLES.remove(PATTERN_SAMPLES.keys.first())
        return formatted
    }

    class Formatter(pattern: String, private val zone: TimeZone, private val locale: Locale) {
        private val pattern = pattern.takeIf(::validate)
        private val calendar = GregorianCalendar(zone, locale)
        private val instant = Date()
        private var formatter: SimpleDateFormat? = null
        private var icuZone: android.icu.util.TimeZone? = null
        private var previousPattern: String? = null
        private var previousOffset = Int.MIN_VALUE

        fun format(timestamp: Long): String {
            val source = pattern ?: return ""
            calendar.timeInMillis = timestamp
            val normalized = normalize(source, calendar, locale)
            val active =
                formatter
                    ?: SimpleDateFormat(normalized, locale).also {
                        val digits = android.icu.text.DecimalFormatSymbols(locale)
                        digits.zeroDigit =
                            java.text.DecimalFormatSymbols.getInstance(locale).zeroDigit
                        it.numberFormat = android.icu.text.DecimalFormat("0", digits)
                        val initialZone = android.icu.util.TimeZone.getTimeZone("GMT")
                        icuZone = initialZone
                        it.calendar = android.icu.util.GregorianCalendar(initialZone, locale)
                        formatter = it
                    }
            if (normalized != previousPattern) active.applyPattern(normalized)
            previousPattern = normalized
            // Native TextClock's Calendar remains authoritative over ICU zone-rule versions.
            val offset = zone.getOffset(timestamp)
            if (offset != previousOffset) {
                val effectiveZone = requireNotNull(icuZone)
                effectiveZone.rawOffset = offset
                active.timeZone = effectiveZone
                previousOffset = offset
            }
            instant.time = timestamp
            return active.format(instant)
        }
    }

    private fun normalize(pattern: String, calendar: Calendar, locale: Locale): String {
        val result = StringBuilder()
        val text = StringBuilder()
        var quoted = false
        var index = 0
        while (index < pattern.length) {
            val letter = pattern[index]
            if (letter == '\'') {
                index++
                if (index < pattern.length && pattern[index] == '\'') {
                    text.append('\'')
                    index++
                } else quoted = !quoted
                continue
            }
            if (quoted || letter !in TOKENS) {
                text.append(letter)
                index++
                continue
            }
            var end = index + 1
            while (end < pattern.length && pattern[end] == letter) end++
            val count = end - index
            if ((letter == 'M' || letter == 'L') && count >= 6) {
                text.append(String.format(locale, "%0${count}d", calendar.get(Calendar.MONTH) + 1))
                index = end
                continue
            }
            if (letter == 'z') {
                text.append(zoneText(calendar, locale, count))
                index = end
                continue
            }
            flushLiteral(result, text)
            when (letter) {
                'a' -> result.append('a')
                'k' -> repeat(count) { result.append('H') }
                'y' -> repeat(if (count <= 2) 2 else 1) { result.append('y') }
                'E',
                'c' -> repeat(if (count == 4 || count == 5) count else 3) { result.append(letter) }
                else -> repeat(count) { result.append(letter) }
            }
            index = end
        }
        flushLiteral(result, text)
        return result.toString()
    }

    private fun zoneText(calendar: Calendar, locale: Locale, count: Int): String {
        val zone = calendar.timeZone
        if (count >= 2)
            return zone.getDisplayName(
                calendar.get(Calendar.DST_OFFSET) != 0,
                TimeZone.SHORT,
                locale,
            )
        val offset =
            (calendar.get(Calendar.ZONE_OFFSET) + calendar.get(Calendar.DST_OFFSET)) / 60000
        return String.format(
            locale,
            "%s%02d%02d",
            if (offset < 0) "-" else "+",
            kotlin.math.abs(offset) / 60,
            kotlin.math.abs(offset) % 60,
        )
    }

    private fun flushLiteral(result: StringBuilder, text: StringBuilder) {
        if (text.isEmpty()) return
        var start = 0
        var end = text.length
        while (start < end && text[start] == '\'') {
            result.append("''")
            start++
        }
        while (end > start && text[end - 1] == '\'') end--
        if (start < end)
            result.append('\'').append(text.substring(start, end).replace("'", "''")).append('\'')
        repeat(text.length - end) { result.append("''") }
        text.setLength(0)
    }
}

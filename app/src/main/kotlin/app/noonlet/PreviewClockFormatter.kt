package app.noonlet

import android.view.View
import android.view.ViewGroup
import android.widget.TextClock
import java.util.Locale
import java.util.TimeZone
import android.util.LruCache

/** Formats editor clocks in the phone locale without changing process-wide locale state. */
internal object PreviewClockFormatter {
    private data class FormatKey(val pattern: String, val zone: String, val locale: Locale)
    private val formatters = LruCache<FormatKey, ClockPattern.Formatter>(32)
    fun localize(view: View, timestamp: Long, phoneLocale: Locale): Boolean {
        var seconds = false
        if (view is TextClock) {
            val format = view.format24Hour ?: view.format12Hour
            if (format != null) {
                seconds = ClockPattern.hasSeconds(format)
                val zone = view.timeZone?.let(TimeZone::getTimeZone) ?: TimeZone.getDefault()
                val key = FormatKey(format.toString(), zone.id, phoneLocale)
                val localized = synchronized(formatters) {
                    val formatter = formatters.get(key) ?: ClockPattern.Formatter(key.pattern, zone, phoneLocale)
                        .also { formatters.put(key, it) }
                    ClockPattern.previewLiteral(format, timestamp, zone, phoneLocale, formatter)
                }
                view.format12Hour = localized
                view.format24Hour = localized
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val childSeconds = localize(view.getChildAt(index), timestamp, phoneLocale)
                seconds = seconds || childSeconds
            }
        }
        return seconds
    }
}

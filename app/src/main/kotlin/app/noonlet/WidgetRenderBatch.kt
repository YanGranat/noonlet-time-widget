package app.noonlet

import android.app.PendingIntent
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import java.util.Locale
import java.util.TimeZone

internal data class AlarmPresentation(val text: String, val visible: Boolean)

internal class WidgetRenderBatch(
    val context: Context,
    val id: Int,
    val world: Boolean,
    settings: WidgetSettings,
    val interactive: Boolean,
    val fontScope: WidgetFont.Scope = WidgetFont.Scope.PORTABLE,
    resolvedPhoneLocale: Locale? = null,
    alarmPresentation: AlarmPresentation? = null,
) {
    var naturalSizes: MutableSet<SizeF>? = null

    fun recordNaturalSize(width: Float, height: Float, contentHeight: Float) {
        if (contentHeight.isFinite() && contentHeight > 0 && contentHeight < height)
            naturalSizes?.add(SizeF(width, contentHeight))
    }

    val family = settings.fontFamily
    val weatherTypeface =
        if (!world && settings.weatherEnabled)
            WidgetFont.typeface(settings.weatherFontFamily, settings.weatherFontWeight)
        else Typeface.DEFAULT
    private val alarmTime = if (alarmPresentation == null && !world && settings.showAlarm) SystemAlarmClock.triggerTime(context) else null
    val showAlarm = alarmPresentation?.visible ?: (!world && settings.showAlarm && (alarmTime != null || settings.showNoAlarm))
    val alarm = alarmPresentation?.text ?: if (showAlarm) formatAlarm(context, settings, alarmTime) else ""
    val renderedAt = System.currentTimeMillis()
    val weatherTime = if (!world && settings.weatherEnabled) renderedAt else 0L
    val temperatureUnit =
        if (!world && settings.weatherEnabled) AppPreferences(context).temperatureUnit()
        else TemperatureUnit.CELSIUS
    val weather =
        if (!world && settings.weatherEnabled)
            WeatherRepository.state(context, WeatherRequest.capture(settings))
        else null
    private val typefaces = HashMap<Int, Typeface>()
    private val clockTexts = HashMap<Pair<String, String>, String>()

    fun clockText(pattern: String, zone: TimeZone): String =
        clockTexts.getOrPut(pattern to zone.id) {
            val locale = phoneLocale()
            val key = listOf(pattern, zone.id, locale.toLanguageTag())
            val cache = requireNotNull(formatters.get())
            val formatter = cache[key] ?: ClockPattern.Formatter(pattern, zone, locale).also {
                cache[key] = it
                if (cache.size > 16) cache.remove(cache.keys.first())
            }
            formatter.format(renderedAt)
        }
    private var phoneLocale: Locale? = resolvedPhoneLocale
    private var resourceLocale: Locale? = null
    private val actions = HashMap<String?, PendingIntent?>()

    fun addView(parent: RemoteViews, container: Int, child: RemoteViews, stableId: Int) {
        if (Build.VERSION.SDK_INT >= 31 && fontScope == WidgetFont.Scope.LOCAL)
            parent.addStableView(container, child, stableId)
        else parent.addView(container, child)
    }

    fun typeface(weight: Int): Typeface =
        typefaces.getOrPut(weight) { WidgetFont.typeface(family, weight) }

    fun phoneLocale(): Locale =
        phoneLocale ?: AppLocales.phoneLocale(context).also { phoneLocale = it }

    fun resourceLocale(): Locale =
        resourceLocale
            ?: AppLocales.resourceLocale(
                    context,
                    if (AppLocales.language(context) == "system") phoneLocale() else null,
                )
                .also { resourceLocale = it }

    fun localizedDateSymbols(): Boolean = resourceLocale().language == phoneLocale().language

    fun pending(action: String?): PendingIntent? {
        if (!interactive) return null
        if (!actions.containsKey(action))
            actions[action] = TapActions.pending(context, id, world, action)
        return actions[action]
    }

    private companion object {
        val formatters = ThreadLocal.withInitial {
            java.util.LinkedHashMap<List<String>, ClockPattern.Formatter>(16, .75f, true)
        }
    }
}

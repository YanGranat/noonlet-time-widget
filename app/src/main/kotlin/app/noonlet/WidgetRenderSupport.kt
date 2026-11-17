package app.noonlet

import android.content.Context
import android.graphics.Paint
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.widget.RemoteViews
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

internal fun setClock(
    context: Context,
    views: RemoteViews,
    view: Int,
    zone: String?,
    pattern: String,
    family: String,
    weight: Int,
    fontScope: WidgetFont.Scope,
) {
    views.setString(view, "setTimeZone", zone)
    val styled = WidgetFont.styled(context, pattern, family, weight, fontScope)
    views.setCharSequence(view, "setFormat12Hour", styled)
    views.setCharSequence(view, "setFormat24Hour", styled)
}

internal fun px(context: Context, dp: Float) =
    (dp * context.resources.displayMetrics.density).roundToInt()

internal fun px(context: Context, dp: Int) = px(context, dp.toFloat())

internal fun spPixels(context: Context, size: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, context.resources.displayMetrics)

private fun pixelsToSp(context: Context, pixels: Float): Float =
    if (Build.VERSION.SDK_INT >= 34)
        TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, pixels, context.resources.displayMetrics)
    else pixels / context.resources.displayMetrics.scaledDensity

internal fun gravity(alignment: WidgetAlignment) =
    (when (alignment) {
        WidgetAlignment.RIGHT -> Gravity.END
        WidgetAlignment.CENTER -> Gravity.CENTER_HORIZONTAL
        else -> Gravity.START
    }) or Gravity.CENTER_VERTICAL

internal fun nonEmpty(text: String?, fallback: String) =
    text?.takeIf(String::isNotEmpty) ?: fallback

internal fun measurePaint(context: Context, weight: Int, size: Float, batch: WidgetRenderBatch) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = batch.typeface(weight)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, context.resources.displayMetrics)
    }

internal fun fitAlarm(
    context: Context,
    text: String,
    settings: WidgetSettings,
    width: Float,
    batch: WidgetRenderBatch,
): Float {
    var measured =
        measurePaint(context, settings.fontWeight, settings.alarmSize.toFloat(), batch)
            .measureText(text) / context.resources.displayMetrics.density
    var availableWidth = width
    if (settings.showAlarmIcon) {
        measured += spPixels(context, settings.alarmSize.toFloat()) / context.resources.displayMetrics.density
        availableWidth -= 4
    }
    return maxOf(
        .25f,
        pixelsToSp(context, spPixels(context, settings.alarmSize.toFloat()) *
            minOf(1f, maxOf(1f, availableWidth) / maxOf(1f, measured) * .98f)),
    )
}

internal fun fitPattern(
    context: Context,
    pattern: String,
    weight: Int,
    size: Float,
    width: Float,
    locale: Locale,
    zone: TimeZone,
    batch: WidgetRenderBatch,
): Float {
    val paint = measurePaint(context, weight, size, batch)
    val maximum =
        ClockPattern.fittingSamples(pattern, locale, zone)
            .maxOfOrNull(paint::measureText)
            ?.div(context.resources.displayMetrics.density) ?: 0f
    return maxOf(.25f, pixelsToSp(context, paint.textSize *
        minOf(1f, maxOf(1f, width) / maxOf(1f, maximum) * .98f)))
}

internal fun fontHeight(context: Context, weight: Int, size: Float, batch: WidgetRenderBatch): Float {
    val metrics = measurePaint(context, weight, size, batch).fontMetrics
    return (metrics.descent - metrics.ascent) / context.resources.displayMetrics.density
}

internal fun formatAlarm(context: Context, settings: WidgetSettings, triggerTime: Long?): String {
    return try {
        SystemAlarmClock.format(context, nonEmpty(settings.alarmFormat, "HH:mm"), triggerTime)
    } catch (_: IllegalArgumentException) {
        SystemAlarmClock.format(context, "HH:mm", triggerTime)
    }
}

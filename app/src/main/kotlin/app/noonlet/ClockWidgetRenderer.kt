package app.noonlet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import java.util.TimeZone
import kotlin.math.roundToInt

internal object ClockWidgetRenderer {
    private data class AlarmPlacement(val left: Int, val top: Int, val size: Float)
    private data class Primary(val timeSize: Float, val dateSize: Float, val scene: ClockSceneGeometry.Scene)
    private data class Layout(val primary: Primary, val alarm: AlarmPlacement?,
        val weather: WeatherWidgetRenderer.Layout?, val sideWeather: Boolean,
        val insets: Rect,
        val width: Float, val height: Float, val clockWidth: Float,
        val timeFormat: String, val dateFormat: String)

    private fun measure(context: Context, settings: WidgetSettings, width: Float, height: Float,
        batch: WidgetRenderBatch): Layout {
        val insets = Rect(maxOf(0, settings.paddingLeft), maxOf(0, settings.paddingTop),
            maxOf(0, settings.paddingRight), maxOf(0, settings.paddingBottom))
        val availableWidth = maxOf(1f, width - insets.left - insets.right)
        val availableHeight = maxOf(1f, height - insets.top - insets.bottom)
        val horizontal = settings.orientation == WidgetOrientation.HORIZONTAL
        val compact = batch.weather != null && !horizontal && availableWidth < 160f
        val timeFormat = nonEmpty(settings.timeFormat, if (settings.hour24) "HH:mm" else "h:mm a")
        val dateFormat = nonEmpty(settings.dateFormat, "EEE, d MMM")
        val locale = batch.phoneLocale()
        val zone = TimeZone.getTimeZone(settings.effectiveZone())
        val preferredTime = if (horizontal || compact) 0f else
            ClockPattern.fittingSamples(timeFormat, locale, zone).maxOfOrNull(
                measurePaint(context, settings.timeFontWeight, settings.textSize.toFloat(), batch)::measureText)
                ?.div(context.resources.displayMetrics.density) ?: 0f
        val weather = batch.weather?.let { WeatherWidgetRenderer.layout(context, it, batch.weatherTime,
            availableWidth, availableHeight, horizontal, compact, preferredTime, batch.resourceLocale(),
            batch.weatherTypeface, batch.temperatureUnit, settings) }
        val sideWeather = weather != null && !horizontal && !compact
        val clockWidth = if (sideWeather) WeatherWidgetRenderer.primaryWidth(availableWidth) else availableWidth
        val details = settings.showDate || horizontal && weather != null
        val timeWidth = if (horizontal && details) clockWidth / 2 else clockWidth
        val detailsWidth = if (horizontal && details) maxOf(1f, clockWidth / 2 - 8) else clockWidth
        var timeSize = fitPattern(context, timeFormat, settings.timeFontWeight,
            settings.textSize.coerceIn(8, WidgetSettings.MAX_TIME_SIZE).toFloat(), timeWidth, locale, zone, batch)
        var dateSize = fitPattern(context, dateFormat, settings.dateFontWeight,
            settings.dateSize.coerceIn(6, WidgetSettings.MAX_DATE_SIZE).toFloat(),
            if (sideWeather) availableWidth else detailsWidth, locale, zone, batch)
        val gap = maxOf(0, settings.timeDateGap).toFloat()
        if (sideWeather && weather != null) {
            val clearance = WeatherWidgetRenderer.primaryClearance(context, settings, weather, batch,
                availableWidth, availableHeight, clockWidth, timeSize, dateSize, gap, false)
            if (clearance < clockWidth) timeSize = fitPattern(context, timeFormat, settings.timeFontWeight,
                settings.textSize.toFloat(), clearance, locale, zone, batch)
        }
        val dateWidth = if (sideWeather && weather != null) {
            val clearance = WeatherWidgetRenderer.primaryClearance(context, settings, weather, batch,
                availableWidth, availableHeight, clockWidth, timeSize, dateSize, gap)
            val textWidth = measurePaint(context, settings.dateFontWeight, dateSize, batch)
                .measureText(batch.clockText(dateFormat, zone)) / context.resources.displayMetrics.density
            if (textWidth > clearance) dateSize *= maxOf(.01f, clearance / textWidth)
            clearance
        } else detailsWidth
        val timeHeight = fontHeight(context, settings.timeFontWeight, timeSize, batch)
        val dateHeight = if (settings.showDate) fontHeight(context, settings.dateFontWeight, dateSize, batch) else 0f
        val weatherHeight = if (weather != null && !sideWeather)
            WeatherWidgetRenderer.heightPx(context, weather, batch.weatherTypeface, true) /
                context.resources.displayMetrics.density else 0f
        var timeRatio = 1f
        var dateRatio = 1f
        var gapRatio = 1f
        if (horizontal) {
            timeRatio = ClockTextGeometry.fittingRatio(context, availableHeight, batch.clockText(timeFormat, zone),
                null, batch.typeface(settings.timeFontWeight), batch.typeface(settings.dateFontWeight),
                timeSize, dateSize, 0f)
            if (settings.showDate) dateRatio = minOf(1f,
                maxOf(.1f, availableHeight - weatherHeight - 2) / maxOf(1f, dateHeight))
        } else if (compact) {
            val primaryHeight = timeHeight + dateHeight + gap
            timeRatio = minOf(1f, maxOf(.1f, availableHeight - weatherHeight - 2) / maxOf(1f, primaryHeight))
            dateRatio = timeRatio
            gapRatio = timeRatio
        } else {
            timeRatio = ClockTextGeometry.fittingRatio(context, availableHeight, batch.clockText(timeFormat, zone),
                if (settings.showDate) batch.clockText(dateFormat, zone) else null,
                batch.typeface(settings.timeFontWeight), batch.typeface(settings.dateFontWeight),
                timeSize, dateSize, gap)
            dateRatio = timeRatio
            gapRatio = timeRatio
        }
        val alarmGap = px(context, minOf(3f, availableHeight / 15))
        fun primary(scale: Float): Primary {
            val t = maxOf(.25f, timeSize * timeRatio * scale)
            val d = maxOf(.25f, dateSize * dateRatio * scale)
            val tm = WeatherWidgetRenderer.fontMetrics(context, batch.typeface(settings.timeFontWeight), t)
            val dm = WeatherWidgetRenderer.fontMetrics(context, batch.typeface(settings.dateFontWeight), d)
            return Primary(t, d, ClockSceneGeometry.arrange(px(context, availableWidth), px(context, availableHeight),
                px(context, clockWidth), px(context, dateWidth), tm.descent - tm.ascent,
                if (settings.showDate) dm.descent - dm.ascent else 0,
                if (weather != null && !sideWeather) WeatherWidgetRenderer.heightPx(context, weather, batch.weatherTypeface, true) else 0,
                px(context, gap * gapRatio * scale), horizontal, compact, px(context, 8)))
        }
        var primary = primary(1f)
        val alarmSize = fitAlarm(context, batch.alarm, settings, availableWidth, batch)
        fun placement(primary: Primary) = fitAlarmPlacement(context, settings, availableWidth, availableHeight,
            primary, alarmSize, alarmGap, weather, sideWeather, clockWidth, timeFormat, dateFormat, batch)
        var alarm = if (batch.showAlarm) placement(primary) else null
        if (batch.showAlarm && alarm == null) {
            var low = .01f
            var high = 1f
            var best = primary(low)
            var fitted = placement(best)
            if (fitted != null) {
                repeat(10) {
                    val scale = (low + high) / 2
                    val candidate = primary(scale)
                    val candidateAlarm = placement(candidate)
                    if (candidateAlarm != null) { low = scale; best = candidate; fitted = candidateAlarm }
                    else high = scale
                }
                primary = best
                alarm = fitted
            }
        }
        val natural = if (horizontal) maxOf(timeHeight, dateHeight + weatherHeight +
            if (batch.showAlarm) fontHeight(context, settings.fontWeight, alarmSize, batch) + 3 else 0f)
            else timeHeight + dateHeight + weatherHeight + gap +
                if (batch.showAlarm) fontHeight(context, settings.fontWeight, alarmSize, batch) + 3 else 0f
        batch.recordNaturalSize(width, height, insets.top + insets.bottom + 2 +
            maxOf(natural, if (sideWeather) weather?.height ?: 0f else 0f))
        return Layout(primary, alarm, weather, sideWeather, insets,
            availableWidth, availableHeight, clockWidth, timeFormat, dateFormat)
    }

    private fun fitAlarmPlacement(context: Context, settings: WidgetSettings, width: Float, height: Float,
        primary: Primary, preferredSize: Float, gap: Int, weather: WeatherWidgetRenderer.Layout?,
        sideWeather: Boolean, clockWidth: Float, timeFormat: String, dateFormat: String,
        batch: WidgetRenderBatch): AlarmPlacement? {
        val scene = primary.scene
        val zone = TimeZone.getTimeZone(settings.effectiveZone())
        fun left(width: Int, span: Int, alignment: WidgetAlignment) = when (alignment) {
            WidgetAlignment.CENTER -> (span - width) / 2
            WidgetAlignment.RIGHT -> span - width
            else -> 0
        }
        fun ink(pattern: String, weight: Int, size: Float, row: Rect, alignment: WidgetAlignment,
            x: Int, y: Int): Rect {
            val line = AlarmPlacementGeometry.measure(measurePaint(context, weight, size, batch),
                batch.clockText(pattern, zone))
            return Rect(line.ink).apply { offset(row.left + left(line.width, row.width(), alignment) + px(context, x),
                row.top + px(context, y)) }
        }
        val occupied = mutableListOf(ink(timeFormat, settings.timeFontWeight, primary.timeSize,
            scene.time, settings.alignment, settings.timeOffsetX, settings.timeOffsetY))
        if (settings.showDate) occupied += ink(dateFormat, settings.dateFontWeight, primary.dateSize,
            scene.date, settings.dateAlignment, settings.dateOffsetX, settings.dateOffsetY)
        if (weather != null) occupied += if (sideWeather)
            WeatherWidgetRenderer.obstacles(context, settings, weather, batch, width, height, clockWidth)
            else WeatherWidgetRenderer.inlineObstacles(context, settings, weather, batch.weatherTypeface,
                requireNotNull(scene.weather))
        val viewport = Rect(-px(context, maxOf(0, settings.paddingLeft)), -px(context, maxOf(0, settings.paddingTop)),
            px(context, width + maxOf(0, settings.paddingRight)), px(context, height + maxOf(0, settings.paddingBottom)))
        fun atSize(size: Float): AlarmPlacement? {
            val line = AlarmPlacementGeometry.measure(measurePaint(context, settings.fontWeight, size, batch),
                batch.alarm, if (settings.showAlarmIcon) px(context, size * context.resources.configuration.fontScale) else 0,
                px(context, 4))
            val preferred = Point(scene.alarmLeft + left(line.width, scene.alarmWidth, settings.alarmAlignment) +
                px(context, settings.alarmOffsetX), scene.alarmTop(line.height, gap) + px(context, settings.alarmOffsetY))
            val position = AlarmPlacementGeometry.closest(viewport, line.ink, preferred, occupied, 1) ?: return null
            return AlarmPlacement(position.x, position.y, size)
        }
        atSize(preferredSize)?.let { return it }
        var low = minOf(6f, preferredSize)
        var best = atSize(low) ?: return null
        var high = preferredSize
        repeat(10) {
            val size = (low + high) / 2
            val candidate = atSize(size)
            if (candidate != null) { low = size; best = candidate } else high = size
        }
        return best
    }

    fun render(context: Context, settings: WidgetSettings, width: Float, height: Float,
        batch: WidgetRenderBatch): RemoteViews = with(measure(context, settings, width, height, batch)) {
        val views = RemoteViews(context.packageName, when {
            weather == null -> R.layout.clock_widget_offset_alarm
            sideWeather -> R.layout.clock_widget_weather_offset_alarm
            else -> R.layout.clock_widget_weather_inline_overlay
        })
        applyGeometry(context, settings, this, views, batch)
        val contentHeight = px(context, this.height)
        val alarmSize = alarm?.size ?: settings.alarmSize.toFloat()
        if (weather != null) {
            val reference = if (sideWeather) WeatherWidgetRenderer.reference(context, clockWidth, this.height,
                timeFormat, batch.phoneLocale(), TimeZone.getTimeZone(settings.effectiveZone()), batch) else null
            val presentation = if (reference != null) WeatherWidgetRenderer.scaleCaption(context, weather,
                batch.weatherTypeface, reference.scale) else weather
            WeatherWidgetRenderer.apply(context, views, settings, presentation, !sideWeather,
                if (reference != null) WeatherWidgetRenderer.dateAlignment(context, presentation,
                    batch.weatherTypeface, contentHeight, reference.baseline) else 0, batch.fontScope)
        }
        views.setTextColor(R.id.clock_time, settings.timeColor)
        views.setTextColor(R.id.clock_date, settings.dateColor)
        views.setTextColor(R.id.clock_alarm, settings.alarmColor)
        views.setTextViewTextSize(R.id.clock_time, TypedValue.COMPLEX_UNIT_SP, primary.timeSize)
        views.setTextViewTextSize(R.id.clock_date, TypedValue.COMPLEX_UNIT_SP, primary.dateSize)
        views.setTextViewTextSize(R.id.clock_alarm, TypedValue.COMPLEX_UNIT_SP, alarmSize)
        val zone = settings.zoneId.takeIf(String::isNotEmpty)?.let { settings.effectiveZone() }
        setClock(context, views, R.id.clock_time, zone, timeFormat, settings.fontFamily, settings.timeFontWeight, batch.fontScope)
        setClock(context, views, R.id.clock_date, zone, dateFormat, settings.fontFamily, settings.dateFontWeight, batch.fontScope)
        views.setTextViewText(R.id.clock_alarm, WidgetFont.styled(context, batch.alarm, settings.fontFamily,
            settings.fontWeight, batch.fontScope))
        views.setContentDescription(R.id.clock_alarm, if (alarm != null)
            AppLocales.text(context, batch.resourceLocale(), R.string.alarm) + ", " + batch.alarm else "")
        views.setViewVisibility(R.id.clock_time, View.VISIBLE)
        views.setViewVisibility(R.id.clock_time_row, View.VISIBLE)
        views.setViewVisibility(R.id.clock_date, if (settings.showDate) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.clock_date_row, if (settings.showDate) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.clock_alarm_row, if (alarm != null) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.clock_alarm_icon, if (alarm != null && settings.showAlarmIcon) View.VISIBLE else View.GONE)
        if (alarm != null && settings.showAlarmIcon) {
            val iconSize = alarmSize * context.resources.configuration.fontScale
            views.setImageViewBitmap(R.id.clock_alarm_icon, alarmIcon(settings.alarmColor, iconSize))
            if (Build.VERSION.SDK_INT >= 31) {
                views.setViewLayoutWidth(R.id.clock_alarm_icon, iconSize, TypedValue.COMPLEX_UNIT_DIP)
                views.setViewLayoutHeight(R.id.clock_alarm_icon, iconSize, TypedValue.COMPLEX_UNIT_DIP)
            }
        }
        applyClockTaps(views, settings, batch)
        views
    }

    private fun applyGeometry(context: Context, settings: WidgetSettings, layout: Layout,
        views: RemoteViews, batch: WidgetRenderBatch) = with(layout) {
        val scene = primary.scene
        val contentWidth = px(context, this.width)
        val contentHeight = px(context, this.height)
        views.setViewPadding(R.id.clock_content, if (weather == null) px(context, insets.left) else 0,
            if (weather == null) px(context, insets.top) else 0,
            if (weather == null) px(context, insets.right) else 0,
            if (weather == null) px(context, insets.bottom) else 0)
        if (weather != null) views.setViewPadding(R.id.clock_weather_content,
            px(context, insets.left), px(context, insets.top), px(context, insets.right), px(context, insets.bottom))
        fun position(id: Int, row: Rect, x: Int = 0, y: Int = 0) {
            val top = row.top - (contentHeight - row.height()) / 2 + y
            views.setViewPadding(id, row.left + x, top, contentWidth - row.right - x, -top)
        }
        position(R.id.clock_time_position, scene.time,
            if (Build.VERSION.SDK_INT < 31) px(context, settings.timeOffsetX) else 0,
            if (Build.VERSION.SDK_INT < 31) px(context, settings.timeOffsetY) else 0)
        position(R.id.clock_date_position, scene.date,
            if (Build.VERSION.SDK_INT < 31) px(context, settings.dateOffsetX) else 0,
            if (Build.VERSION.SDK_INT < 31) px(context, settings.dateOffsetY) else 0)
        for ((id, alignment) in listOf(R.id.clock_time_row to settings.alignment, R.id.clock_date_row to settings.dateAlignment)) {
            views.setViewPadding(id, 0, 0, 0, 0)
            views.setInt(id, "setGravity", gravity(alignment))
        }
        views.setInt(R.id.clock_date_row, "setMinimumHeight", 0)
        val alarmSize = alarm?.size ?: settings.alarmSize.toFloat()
        val alarmLine = AlarmPlacementGeometry.measure(measurePaint(context, settings.fontWeight, alarmSize, batch),
            batch.alarm, if (settings.showAlarmIcon) px(context, alarmSize * context.resources.configuration.fontScale) else 0,
            px(context, 4))
        val alarmTop = alarm?.top ?: 0
        position(R.id.clock_alarm_position, Rect(0, alarmTop, contentWidth, alarmTop + alarmLine.height),
            if (Build.VERSION.SDK_INT < 31) alarm?.left ?: 0 else 0)
        views.setInt(R.id.clock_alarm_position, "setGravity", Gravity.CENTER_VERTICAL or Gravity.START)
        views.setInt(R.id.clock_alarm_row, "setGravity", Gravity.CENTER_VERTICAL or Gravity.START)
        views.setViewPadding(R.id.clock_alarm_row, 0, 0, 0, 0)
        views.setViewPadding(R.id.clock_time, 0, 0, 0, 0)
        views.setViewPadding(R.id.clock_date, 0, 0, 0, 0)
        if (Build.VERSION.SDK_INT >= 31) {
            views.setFloat(R.id.clock_time, "setTranslationX", px(context, settings.timeOffsetX).toFloat())
            views.setFloat(R.id.clock_time, "setTranslationY", px(context, settings.timeOffsetY).toFloat())
            views.setFloat(R.id.clock_date, "setTranslationX", px(context, settings.dateOffsetX).toFloat())
            views.setFloat(R.id.clock_date, "setTranslationY", px(context, settings.dateOffsetY).toFloat())
            views.setFloat(R.id.clock_alarm_row, "setTranslationX", (alarm?.left ?: 0).toFloat())
            views.setFloat(R.id.clock_alarm_row, "setTranslationY", 0f)
        }
        if (weather != null && !sideWeather)
            position(R.id.clock_weather_position, requireNotNull(scene.weather))
    }

    fun updateAppearance(root: View, settings: WidgetSettings, phoneLocale: java.util.Locale?): Boolean {
        val context = root.context
        val density = context.resources.displayMetrics.density
        val shownAlarm = AlarmPresentation(root.findViewById<android.widget.TextView>(R.id.clock_alarm).text.toString(),
            settings.showAlarm && root.findViewById<View>(R.id.clock_alarm_row).visibility == View.VISIBLE)
        val batch = WidgetRenderBatch(context, -1, false, settings, false, WidgetFont.Scope.LOCAL, phoneLocale, shownAlarm)
        val layout = measure(context, settings, root.width / density, root.height / density, batch)
        val sizes = listOf(R.id.clock_time to layout.primary.timeSize,
            R.id.clock_date to layout.primary.dateSize,
            R.id.clock_alarm to (layout.alarm?.size ?: settings.alarmSize.toFloat()))
        for ((id, size) in sizes) {
            val view = root.findViewById<android.widget.TextView>(id)
            if (view.visibility == View.VISIBLE && kotlin.math.abs(view.textSize -
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size, context.resources.displayMetrics)) > .1f)
                return false
        }
        if ((root.findViewById<View>(R.id.clock_alarm_row).visibility == View.VISIBLE) != (layout.alarm != null))
            return false
        val views = RemoteViews(context.packageName, R.layout.clock_widget_offset_alarm)
        applyGeometry(context, settings, layout, views, batch)
        views.setTextColor(R.id.clock_time, settings.timeColor)
        views.setTextColor(R.id.clock_date, settings.dateColor)
        views.setTextColor(R.id.clock_alarm, settings.alarmColor)
        if (layout.alarm != null && settings.showAlarmIcon)
            views.setImageViewBitmap(R.id.clock_alarm_icon,
                alarmIcon(settings.alarmColor, layout.alarm.size * context.resources.configuration.fontScale))
        views.reapply(context, root)
        return true
    }

    private fun alarmIcon(color: Int, sizeDp: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        bitmap.density = maxOf(1, (48f * 160 / maxOf(.25f, sizeDp)).roundToInt())
        val canvas = Canvas(bitmap)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                strokeWidth = 3.2f
                strokeCap = Paint.Cap.ROUND
                style = Paint.Style.STROKE
            }
        canvas.drawCircle(24f, 26f, 15f, paint)
        canvas.drawLine(24f, 26f, 24f, 16f, paint)
        canvas.drawLine(24f, 26f, 31f, 30f, paint)
        canvas.drawLine(9f, 10f, 15f, 6f, paint)
        canvas.drawLine(33f, 6f, 39f, 10f, paint)
        canvas.drawLine(14f, 40f, 10f, 44f, paint)
        canvas.drawLine(34f, 40f, 38f, 44f, paint)
        return bitmap
    }

    private fun applyClockTaps(views: RemoteViews, settings: WidgetSettings, batch: WidgetRenderBatch) {
        val root = if (settings.advancedTap) null else batch.pending(settings.tapAction)
        views.setOnClickPendingIntent(R.id.widget_root, root)
        val time = if (settings.advancedTap) batch.pending(settings.timeTapAction) else root
        val date = if (settings.advancedTap) batch.pending(settings.dateTapAction) else root
        val alarm = if (settings.advancedTap) batch.pending(settings.alarmTapAction) else root
        views.setOnClickPendingIntent(R.id.clock_time, time)
        views.setOnClickPendingIntent(R.id.clock_time_row, time)
        views.setOnClickPendingIntent(R.id.clock_date, date)
        views.setOnClickPendingIntent(R.id.clock_date_row, date)
        views.setOnClickPendingIntent(R.id.clock_alarm_row, alarm)
        views.setOnClickPendingIntent(R.id.clock_alarm, alarm)
        views.setOnClickPendingIntent(R.id.clock_alarm_icon, alarm)
    }

}

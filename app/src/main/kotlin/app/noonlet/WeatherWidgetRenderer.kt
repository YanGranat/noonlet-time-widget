package app.noonlet

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

internal object WeatherWidgetRenderer {
    private val defaultClock = WidgetSettings.defaults(false)
    private const val ARTWORK_REFERENCE_SIZE = 51f

    data class Reference(val baseline: Int, val scale: Float)

    fun reference(
        context: Context,
        width: Float,
        height: Float,
        pattern: String,
        locale: Locale,
        zone: TimeZone,
        batch: WidgetRenderBatch? = null,
    ): Reference {
        val defaults = defaultClock
        val timeFace = WidgetFont.typeface(defaults.fontFamily, defaults.timeFontWeight)
        val dateFace = WidgetFont.typeface(defaults.fontFamily, defaults.dateFontWeight)
        val measured = paint(context, timeFace, defaults.textSize.toFloat())
        val maximum = ClockPattern.fittingSamples(pattern, locale, zone)
            .maxOfOrNull(measured::measureText)?.div(context.resources.displayMetrics.density) ?: 1f
        val timeSize = defaults.textSize * minOf(1f, maxOf(1f, width) / maxOf(1f, maximum) * .98f)
        val now = batch?.renderedAt ?: System.currentTimeMillis()
        val timeText = batch?.clockText(pattern, zone) ?: ClockPattern.formatAt(pattern, now, zone, locale)
        val dateText = batch?.clockText(defaults.dateFormat, zone)
            ?: ClockPattern.formatAt(defaults.dateFormat, now, zone, locale)
        val ratio = ClockTextGeometry.fittingRatio(context, height, timeText, dateText,
            timeFace, dateFace, timeSize, defaults.dateSize.toFloat(), 0f)
        val fittedTime = fontMetrics(context, timeFace, timeSize * ratio)
        val fittedDate = fontMetrics(context, dateFace, defaults.dateSize * ratio)
        val timeHeight = fittedTime.descent - fittedTime.ascent
        val dateHeight = fittedDate.descent - fittedDate.ascent
        return Reference((px(context, height) - timeHeight - dateHeight) / 2 + timeHeight -
            fittedDate.ascent + px(context, defaults.dateOffsetY.toFloat()), ratio)
    }

    data class Layout(
        val caption: String,
        val description: String,
        val icon: Int,
        val width: Float,
        val gap: Float,
        val fontSize: Float,
        val iconSize: Float,
        val iconBottomGap: Float,
        val height: Float,
        val maxLines: Int,
    )

    fun layout(
        context: Context,
        state: WeatherState,
        now: Long,
        width: Float,
        height: Float,
        horizontal: Boolean,
        stacked: Boolean,
        preferredTime: Float,
        locale: Locale,
        typeface: Typeface,
        unit: TemperatureUnit = AppPreferences(context).temperatureUnit(),
        settings: WidgetSettings,
    ): Layout {
        val snapshot = state.snapshot
        val inline = horizontal || stacked
        val fontSize = settings.weatherTextSize.toFloat() * if (inline) 12f / 13f else 1f
        fun fontHeight(size: Float): Float {
            val metrics = paint(context, typeface, size).fontMetrics
            return (metrics.descent - metrics.ascent) / context.resources.displayMetrics.density
        }
        val paint = paint(context, typeface, fontSize)
        fun textWidth(text: String) =
            paint.measureText(text) / context.resources.displayMetrics.density + 2f
        val gap = if (inline) 0f else if (width < 190f) 10f else 12f
        val panelLimit =
            if (horizontal) maxOf(1f, width / 2 - 8)
            else if (stacked) width else maxOf(1f, width - preferredTime - gap)
        if (snapshot == null) {
            val description =
                AppLocales.text(
                    context,
                    locale,
                    when (state.status) {
                        WeatherStatus.PERMISSION_REQUIRED -> R.string.weather_permission_required
                        WeatherStatus.LOCATION_DISABLED -> R.string.weather_location_disabled
                        WeatherStatus.LOADING -> R.string.weather_loading
                        else -> R.string.weather_unavailable
                    },
                )
            val message =
                AppLocales.text(
                    context,
                    locale,
                    if (state.status == WeatherStatus.LOADING) R.string.weather_loading_short
                    else R.string.weather_no_data,
                )
            val statusWidth =
                if (inline) panelLimit
                else maxOf(56f, minOf(100f, panelLimit)).coerceAtMost(maxOf(1f, width * .44f))
            val statusSize = fitStatusSize(context, typeface, message, statusWidth, height, fontSize)
            val statusHeight =
                StaticLayout.Builder.obtain(
                        message,
                        0,
                        message.length,
                        TextPaint(paint(context, typeface, statusSize)),
                        maxOf(1, px(context, statusWidth)),
                    )
                    .setIncludePad(false)
                    .setMaxLines(3)
                    .build()
                    .height / context.resources.displayMetrics.density
            return Layout(
                message,
                description,
                0,
                statusWidth,
                gap,
                statusSize,
                0f,
                0f,
                statusHeight + if (inline) 3f else 0f,
                3,
            )
        }
        val temperature = unit.formatCelsius(snapshot.temperatureC, locale)
        val condition = WeatherCondition.fromCode(snapshot.weatherCode)?.let(::conditionAppearance)
        val conditionName = condition?.let { AppLocales.text(context, locale, it.first) }.orEmpty()
        val fullCaption = when {
            conditionName.isEmpty() || settings.weatherTextFormat == WeatherTextFormat.TEMPERATURE -> temperature
            settings.weatherTextFormat == WeatherTextFormat.TEMPERATURE_CONDITION -> "$temperature  $conditionName"
            else -> "$conditionName  $temperature"
        }
        val icon =
            when (condition?.second) {
                R.drawable.weather_clear_day ->
                    if (snapshot.isDay) R.drawable.weather_clear_day
                    else R.drawable.weather_clear_night
                R.drawable.weather_partly_cloudy_day ->
                    if (snapshot.isDay) R.drawable.weather_partly_cloudy_day
                    else R.drawable.weather_partly_cloudy_night
                else -> condition?.second ?: 0
            }
        val minimumColumnWidth =
            if (horizontal) 20f
            else if (stacked) 16f else if (width >= 245f) 51f else if (width < 190f) 28f else 40f
        val baseIcon = if (!inline && width >= 190f) settings.weatherIconSize.toFloat()
            else minimumColumnWidth * settings.weatherIconSize / ARTWORK_REFERENCE_SIZE
        val iconBottomGap = if (inline || icon == 0) 0f
            else settings.weatherIconGap + if (width < 190f) 4f else 0f
        val iconGap = if (inline && icon != 0) baseIcon + 6 + settings.weatherIconGap else 0f
        val fullWidth = textWidth(fullCaption)
        val textLimit = maxOf(1f, panelLimit - iconGap)
        val caption = if (!stacked && fullWidth <= textLimit) fullCaption else temperature
        val stale = state.status == WeatherStatus.STALE || !snapshot.fresh(now)
        val staleLabel =
            if (stale) AppLocales.text(context, locale, R.string.weather_stale_short) else ""
        val captionWidth = textWidth(caption)
        val panelWidth =
            if (inline) textLimit
            else maxOf(minimumColumnWidth, captionWidth).coerceAtMost(maxOf(1f, width - gap - 1))
        val ratio = minOf(1f, panelWidth / maxOf(1f, captionWidth))
        val fittedSize =
            if (Build.VERSION.SDK_INT >= 34)
                TypedValue.deriveDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    paint.textSize * ratio,
                    context.resources.displayMetrics,
                )
            else fontSize * ratio
        val captionHeight = fontHeight(fittedSize)
        val iconSize =
            if (icon == 0) 0f
            else minOf(baseIcon, maxOf(1f, height - captionHeight - iconBottomGap))
        return Layout(
            caption,
            if (stale) "$fullCaption, $staleLabel" else fullCaption,
            icon,
            panelWidth,
            gap,
            fittedSize,
            iconSize,
            iconBottomGap,
            if (inline) maxOf(captionHeight, iconSize) + 3f
            else captionHeight + iconSize + iconBottomGap,
            1,
        )
    }

    private fun conditionAppearance(condition: WeatherCondition): Pair<Int, Int> =
        when (condition) {
            WeatherCondition.CLEAR -> R.string.weather_clear to R.drawable.weather_clear_day
            WeatherCondition.MAINLY_CLEAR ->
                R.string.weather_mainly_clear to R.drawable.weather_partly_cloudy_day
            WeatherCondition.PARTLY_CLOUDY ->
                R.string.weather_partly_cloudy to R.drawable.weather_partly_cloudy_day
            WeatherCondition.OVERCAST -> R.string.weather_overcast to R.drawable.weather_cloudy
            WeatherCondition.FOG -> R.string.weather_fog to R.drawable.weather_fog
            WeatherCondition.DRIZZLE -> R.string.weather_drizzle to R.drawable.weather_rain
            WeatherCondition.FREEZING_DRIZZLE ->
                R.string.weather_freezing_drizzle to R.drawable.weather_sleet
            WeatherCondition.FREEZING_RAIN ->
                R.string.weather_freezing_rain to R.drawable.weather_sleet
            WeatherCondition.RAIN -> R.string.weather_rain to R.drawable.weather_rain
            WeatherCondition.SNOW -> R.string.weather_snow to R.drawable.weather_snow
            WeatherCondition.SNOW_GRAINS -> R.string.weather_snow_grains to R.drawable.weather_snow
            WeatherCondition.RAIN_SHOWERS -> R.string.weather_rain_showers to R.drawable.weather_rain
            WeatherCondition.SNOW_SHOWERS -> R.string.weather_snow_showers to R.drawable.weather_snow
            WeatherCondition.THUNDERSTORM ->
                R.string.weather_thunderstorm to R.drawable.weather_thunderstorm
            WeatherCondition.THUNDERSTORM_HAIL ->
                R.string.weather_thunderstorm_hail to R.drawable.weather_thunderstorm
        }

    fun apply(
        context: Context,
        views: RemoteViews,
        settings: WidgetSettings,
        layout: Layout,
        horizontal: Boolean,
        offsetY: Int = 0,
        fontScope: WidgetFont.Scope = WidgetFont.Scope.PORTABLE,
    ) {
        val paddingOffset = if (!horizontal && Build.VERSION.SDK_INT < 31) offsetY else 0
        val extraIconWidth =
            if (horizontal) 0 else maxOf(0, px(context, layout.iconSize) - px(context, layout.width))
        // Preserve the total pixel allocation for the clock and caption columns.
        val leftPadding = maxOf(0, px(context, layout.gap) - extraIconWidth)
        views.setViewPadding(
            R.id.weather_panel,
            leftPadding,
            if (horizontal) px(context, 3f) else paddingOffset,
            0,
            -paddingOffset,
        )
        if (!horizontal && Build.VERSION.SDK_INT >= 31)
            views.setFloat(R.id.weather_panel, "setTranslationY", offsetY.toFloat())
        views.setInt(R.id.weather_caption, "setWidth", px(context, layout.width))
        views.setInt(R.id.weather_caption, "setMaxLines", layout.maxLines)
        views.setTextViewTextSize(R.id.weather_caption, TypedValue.COMPLEX_UNIT_SP, layout.fontSize)
        views.setTextColor(R.id.weather_caption, settings.weatherColor)
        views.setTextViewText(
            R.id.weather_caption,
            WidgetFont.styled(
                context,
                layout.caption,
                settings.weatherFontFamily,
                settings.weatherFontWeight,
                fontScope,
            ),
        )
        views.setContentDescription(R.id.weather_caption, layout.description)
        views.setViewVisibility(
            R.id.weather_icon,
            if (layout.icon == 0) View.GONE else View.VISIBLE,
        )
        views.setViewPadding(R.id.weather_icon, 0, 0, 0, px(context, layout.iconBottomGap))
        // These ImageView setters are remotely callable from API 26 onward.
        views.setInt(R.id.weather_icon, "setMaxWidth", px(context, layout.iconSize))
        views.setInt(
            R.id.weather_icon,
            "setMaxHeight",
            px(context, layout.iconSize + layout.iconBottomGap),
        )
        if (Build.VERSION.SDK_INT >= 31) {
            views.setViewLayoutWidth(R.id.weather_icon, px(context, layout.iconSize).toFloat(),
                TypedValue.COMPLEX_UNIT_PX)
            views.setViewLayoutHeight(R.id.weather_icon,
                px(context, layout.iconSize + layout.iconBottomGap).toFloat(),
                TypedValue.COMPLEX_UNIT_PX)
        } else {
            // Older APIs need a drawable-bounds change to remeasure a reused image.
            views.setImageViewResource(R.id.weather_icon, 0)
        }
        views.setImageViewResource(R.id.weather_icon, layout.icon)
        val x = px(context, settings.weatherOffsetX.toFloat())
        val y = px(context, settings.weatherOffsetY.toFloat())
        if (Build.VERSION.SDK_INT >= 31) {
            views.setFloat(R.id.weather_panel, "setTranslationX", x.toFloat())
            views.setFloat(R.id.weather_panel, "setTranslationY", (offsetY + y).toFloat())
        } else views.setViewPadding(R.id.weather_panel, leftPadding + x,
            (if (horizontal) px(context, 3f) else offsetY) + y, -x, -offsetY - y)
    }

    fun heightPx(context: Context, layout: Layout, typeface: Typeface, inline: Boolean): Int {
        val caption = captionLayout(context, layout, typeface).height
        return if (inline) maxOf(caption, px(context, layout.iconSize)) + px(context, 3f)
            else caption + px(context, layout.iconSize + layout.iconBottomGap)
    }

    fun inlineObstacles(context: Context, settings: WidgetSettings, layout: Layout,
        typeface: Typeface, row: Rect): List<Rect> {
        val caption = captionLayout(context, layout, typeface)
        val icon = if (layout.icon == 0) 0 else px(context, layout.iconSize)
        val contentHeight = maxOf(caption.height, icon)
        val x = row.left + px(context, settings.weatherOffsetX.toFloat())
        val top = row.top + px(context, 3f + settings.weatherOffsetY)
        val textX = x + if (icon > 0) icon + px(context, 6f) else 0
        val paint = paint(context, typeface, layout.fontSize)
        val result = ArrayList<Rect>()
        for (line in 0 until caption.lineCount) {
            val value = layout.caption.substring(caption.getLineStart(line), caption.getLineEnd(line)).trimEnd()
            result += Rect().apply {
                paint.getTextBounds(value, 0, value.length, this)
                offset(textX, top + (contentHeight - caption.height) / 2 + caption.getLineBaseline(line))
            }
        }
        if (icon > 0) result += Rect(x, top + (contentHeight - icon) / 2,
            x + icon, top + (contentHeight + icon) / 2)
        return result
    }

    fun scaleCaption(context: Context, layout: Layout, typeface: Typeface, ratio: Float): Layout {
        if (ratio >= 1f || layout.icon == 0) return layout
        val scaled = layout.copy(fontSize = maxOf(.25f, layout.fontSize * ratio))
        return scaled.copy(
            height = scaled.iconSize + scaled.iconBottomGap +
                captionLayout(context, scaled, typeface).height /
                context.resources.displayMetrics.density,
        )
    }

    fun dateAlignment(
        context: Context,
        layout: Layout,
        typeface: Typeface,
        height: Int,
        dateBaseline: Int,
    ): Int {
        val caption = captionLayout(context, layout, typeface)
        val aboveCaption =
            if (layout.icon == 0) 0 else px(context, layout.iconSize + layout.iconBottomGap)
        val totalHeight = aboveCaption + caption.height
        if (totalHeight > height) return 0
        val top = (height - totalHeight) / 2
        val baseline = top + aboveCaption + caption.getLineBaseline(caption.lineCount - 1)
        return (dateBaseline - baseline).coerceIn(-top, height - totalHeight - top)
    }

    fun primaryWidth(width: Float): Float =
        maxOf(1f, width - ARTWORK_REFERENCE_SIZE - if (width < 190f) 10f else 12f)

    fun primaryClearance(
        context: Context,
        settings: WidgetSettings,
        weather: Layout,
        batch: WidgetRenderBatch,
        width: Float,
        height: Float,
        clockWidth: Float,
        timeSize: Float,
        dateSize: Float,
        gap: Float,
        dateRow: Boolean = true,
    ): Float {
        val density = context.resources.displayMetrics.density
        val areaWidth = px(context, width)
        val areaHeight = px(context, height)
        val alignment = if (dateRow) settings.dateAlignment else settings.alignment
        val rowWidth = if (dateRow && alignment == WidgetAlignment.LEFT) width else clockWidth
        val time = fontMetrics(context, batch.typeface(settings.timeFontWeight), timeSize)
        val date = paint(context, batch.typeface(settings.dateFontWeight), dateSize)
        val dateMetrics = date.fontMetricsInt
        val primaryHeight = time.descent - time.ascent +
            if (settings.showDate) dateMetrics.descent - dateMetrics.ascent + px(context, gap) else 0
        val text = batch.clockText(if (dateRow) nonEmpty(settings.dateFormat, "EEE, d MMM")
            else nonEmpty(settings.timeFormat, "HH:mm"),
            TimeZone.getTimeZone(settings.effectiveZone()))
        if (!dateRow) date.set(paint(context, batch.typeface(settings.timeFontWeight), timeSize))
        val dateInk = Rect()
        date.getTextBounds(text, 0, text.length, dateInk)
        val textWidth = kotlin.math.ceil(date.measureText(text)).toInt()
        val left = when (alignment) {
            WidgetAlignment.LEFT -> 0
            WidgetAlignment.CENTER -> (px(context, rowWidth) - textWidth) / 2
            WidgetAlignment.RIGHT -> px(context, rowWidth) - textWidth
        } + px(context, (if (dateRow) settings.dateOffsetX else settings.timeOffsetX).toFloat())
        dateInk.offset(left, (areaHeight - primaryHeight) / 2 +
            if (dateRow) time.descent - time.ascent + px(context, gap) - dateMetrics.ascent +
                px(context, settings.dateOffsetY.toFloat())
            else -time.ascent + px(context, settings.timeOffsetY.toFloat()))
        val intersecting = obstacles(context, settings, weather, batch, width, height, clockWidth)
            .filter { Rect.intersects(it, dateInk) }
        if (intersecting.isEmpty()) return rowWidth
        return minOf(rowWidth, (intersecting.minOf { it.left } - left - px(context, 4f)) / density)
            .coerceAtLeast(1f)
    }

    fun obstacles(context: Context, settings: WidgetSettings, weather: Layout,
        batch: WidgetRenderBatch, width: Float, height: Float, clockWidth: Float): List<Rect> {
        val areaWidth = px(context, width)
        val areaHeight = px(context, height)
        val reference = reference(context, clockWidth, height, settings.timeFormat,
            batch.phoneLocale(), TimeZone.getTimeZone(settings.effectiveZone()), batch)
        val layout = scaleCaption(context, weather, batch.weatherTypeface, reference.scale)
        val caption = captionLayout(context, layout, batch.weatherTypeface)
        val above = if (layout.icon == 0) 0 else px(context, layout.iconSize + layout.iconBottomGap)
        val top = (areaHeight - above - caption.height) / 2 +
            dateAlignment(context, layout, batch.weatherTypeface, areaHeight, reference.baseline) +
            px(context, settings.weatherOffsetY.toFloat())
        val shiftX = px(context, settings.weatherOffsetX.toFloat())
        val obstacles = ArrayList<Rect>(4)
        val captionPaint = paint(context, batch.weatherTypeface, layout.fontSize)
        for (line in 0 until caption.lineCount) {
            val value = layout.caption.substring(caption.getLineStart(line), caption.getLineEnd(line)).trimEnd()
            val ink = Rect()
            captionPaint.getTextBounds(value, 0, value.length, ink)
            ink.offset(areaWidth - kotlin.math.ceil(captionPaint.measureText(value)).toInt() + shiftX,
                top + above + caption.getLineBaseline(line))
            obstacles.add(ink)
        }
        if (layout.icon != 0) obstacles.add(Rect(areaWidth - px(context, layout.iconSize) + shiftX,
            top, areaWidth + shiftX, top + px(context, layout.iconSize)))
        return obstacles
    }

    fun fontMetrics(context: Context, typeface: Typeface, size: Float): Paint.FontMetricsInt =
        paint(context, typeface, size).fontMetricsInt

    private fun paint(context: Context, typeface: Typeface, size: Float) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize =
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    size,
                    context.resources.displayMetrics,
                )
        }

    private fun captionLayout(context: Context, layout: Layout, typeface: Typeface): StaticLayout =
        StaticLayout.Builder.obtain(
                layout.caption,
                0,
                layout.caption.length,
                TextPaint(paint(context, typeface, layout.fontSize)),
                maxOf(1, px(context, layout.width)),
            )
            .setIncludePad(false)
            .setMaxLines(layout.maxLines)
            .build()

    private fun fitStatusSize(
        context: Context,
        typeface: Typeface,
        message: String,
        width: Float,
        height: Float,
        maximumSize: Float,
    ): Float {
        val widthPixels = maxOf(1, px(context, width))
        val heightPixels = maxOf(1, px(context, height))
        val words = message.split(Regex("\\s+"))
        val probe = TextPaint(paint(context, typeface, maximumSize))
        fun fits(size: Float): Boolean {
            probe.textSize =
                TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    size,
                    context.resources.displayMetrics,
                )
            if (words.any { probe.measureText(it) > maxOf(1, widthPixels - px(context, 1f)) })
                return false
            return StaticLayout.Builder.obtain(message, 0, message.length, probe, widthPixels)
                .setIncludePad(false)
                .setMaxLines(3)
                .build()
                .height <= heightPixels
        }
        if (fits(maximumSize)) return maximumSize
        var lower = .25f
        var upper = maximumSize
        repeat(9) {
            val middle = (lower + upper) / 2
            if (fits(middle)) lower = middle else upper = middle
        }
        return lower
    }

    private fun px(context: Context, dp: Float) =
        (dp * context.resources.displayMetrics.density).roundToInt()
}

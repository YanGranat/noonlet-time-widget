package app.noonlet

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextClock
import android.widget.TextView
import java.util.Calendar
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/** Builds launcher-owned TextClocks, so minute updates do not depend on this process. */
object WidgetRenderer {
    fun clock(context: Context, id: Int) =
        responsive(context, id, false, WidgetSettingsStore.load(context, id, false))

    fun world(context: Context, id: Int) =
        responsive(context, id, true, WidgetSettingsStore.load(context, id, true))

    /** Renders the current draft without saving a temporary widget ID. */
    fun preview(
        context: Context,
        id: Int,
        world: Boolean,
        settings: WidgetSettings,
        width: Float,
        height: Float,
        fontScope: WidgetFont.Scope = WidgetFont.Scope.PORTABLE,
        resolvedPhoneLocale: Locale? = null,
    ): RemoteViews =
        render(
            context,
            world,
            settings,
            maxOf(1f, width),
            maxOf(1f, height),
            WidgetRenderBatch(context, id, world, settings, false, fontScope, resolvedPhoneLocale),
        )

    /** Reuses the preview hierarchy with the same geometry as a launcher update. */
    fun updatePreviewAppearance(root: View?, settings: WidgetSettings?, world: Boolean,
        resolvedPhoneLocale: Locale? = null): Boolean {
        if (root == null || settings == null || root.id != R.id.widget_root) return false
        if (!world) {
            if (settings.weatherEnabled || root.findViewById<View>(R.id.weather_panel) != null)
                return false
            val alarmRow = root.findViewById<View>(R.id.clock_alarm_row)
            if (
                root.findViewById<View>(R.id.clock_content) !is ViewGroup ||
                    root.findViewById<View>(R.id.clock_time) !is TextClock ||
                    root.findViewById<View>(R.id.clock_date) !is TextClock ||
                    root.findViewById<View>(R.id.clock_time_row) !is ViewGroup ||
                    root.findViewById<View>(R.id.clock_date_row) !is ViewGroup ||
                    root.findViewById<View>(R.id.clock_time_position) !is ViewGroup ||
                    root.findViewById<View>(R.id.clock_date_position) !is ViewGroup ||
                    root.findViewById<View>(R.id.clock_alarm_position) !is ViewGroup ||
                    alarmRow !is ViewGroup ||
                    root.findViewById<View>(R.id.clock_alarm) !is TextView ||
                    root.findViewById<View>(R.id.clock_alarm_icon) !is ImageView ||
                    root.findViewById<View>(R.id.widget_background) != null ||
                    root.findViewById<View>(R.id.world_rows) != null
            )
                return false
            if (root.width <= 0 || root.height <= 0) return false
            return ClockWidgetRenderer.updateAppearance(root, settings, resolvedPhoneLocale)
        }
        if (
            settings.cities.isNullOrEmpty() ||
                settings.cities.size > 4 ||
                root.findViewById<View>(R.id.widget_background) !is ImageView ||
                root.findViewById<View>(R.id.world_rows) !is ViewGroup ||
                root.findViewById<View>(R.id.clock_content) != null
        )
            return false
        val cards = ArrayList<View>(4)
        collectWorldCards(root.findViewById(R.id.world_rows), cards)
        if (cards.size != settings.cities.size) return false
        val list = cards[0].findViewById<View>(R.id.world_list_divider) != null
        for (card in cards) {
            val divider = card.findViewById<View>(R.id.world_list_divider)
            if (
                card !is ViewGroup ||
                    card.findViewById<View>(R.id.card_background) !is ImageView ||
                    card.findViewById<View>(R.id.city_name) !is TextView ||
                    card.findViewById<View>(R.id.city_time) !is TextClock ||
                    card.findViewById<View>(R.id.city_date) !is TextClock ||
                    list != (divider != null) ||
                    divider != null && divider !is ImageView
            )
                return false
        }
        val text = IntArray(cards.size)
        val background = IntArray(cards.size)
        val density = root.resources.displayMetrics.density
        if (root.width <= 0 || root.height <= 0) return false
        val geometry = worldGeometry(cards.size, root.width / density, root.height / density)
        if (list != geometry.list) return false
        val radius = scaledWorldCornerRadius(geometry.baseRadius, settings.worldCardCornerScale)
        val backdropRadius = scaledWorldCornerRadius(
            geometry.baseRadius + geometry.inset, settings.worldBackdropCornerScale)
        for (index in cards.indices) {
            val zone = TimeZone.getTimeZone(CityCatalog.timeZoneId(settings.cities[index]))
            val day = isWorldDay(settings, Calendar.getInstance(zone).get(Calendar.HOUR_OF_DAY))
            text[index] = worldTextColor(settings, day)
            background[index] = worldBackgroundColor(settings, day)
        }
        cards.forEachIndexed { index, card ->
            updateWorldShape(card.findViewById(R.id.card_background),
                worldCardBackground(list, index, geometry.rows, radius), radius)
            tintShape(card.findViewById(R.id.card_background), background[index])
            card.findViewById<TextView>(R.id.city_name).setTextColor(text[index])
            card.findViewById<TextView>(R.id.city_time).setTextColor(text[index])
            card.findViewById<TextView>(R.id.city_date).setTextColor(text[index])
            if (list)
                tintShape(card.findViewById(R.id.world_list_divider), withAlpha(text[index], 18))
        }
        updateWorldShape(root.findViewById(R.id.widget_background),
            if (backdropRadius == 0) R.drawable.widget_list_middle else R.drawable.widget_world_frame,
            backdropRadius)
        tintShape(root.findViewById(R.id.widget_background), settings.worldBackdropColor)
        return true
    }

    private fun updateWorldShape(view: ImageView, resource: Int, radius: Int) {
        if (view.drawable?.level != radius) {
            view.setImageResource(resource)
            view.setImageLevel(radius)
        }
    }

    private fun worldCardBackground(list: Boolean, rowIndex: Int, rowCount: Int, radius: Int): Int =
        when {
            radius == 0 -> R.drawable.widget_list_middle
            !list -> R.drawable.widget_card_fill
            rowCount == 1 -> R.drawable.widget_world_frame
            rowIndex == 0 -> R.drawable.widget_list_top
            rowIndex == rowCount - 1 -> R.drawable.widget_list_bottom
            else -> R.drawable.widget_list_middle
        }

    private fun collectWorldCards(view: View, cards: MutableList<View>) {
        if (view.id == R.id.world_card) cards += view
        if (view is ViewGroup)
            for (index in 0 until view.childCount) collectWorldCards(view.getChildAt(index), cards)
    }

    private fun responsive(
        context: Context,
        id: Int,
        world: Boolean,
        settings: WidgetSettings,
    ): RemoteViews {
        val options =
            if (id < 0) Bundle.EMPTY
            else AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
        val batch = WidgetRenderBatch(context, id, world, settings, true)
        if (id >= 0 && Build.VERSION.SDK_INT >= 31) {
            val sizes =
                options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            if (!sizes.isNullOrEmpty()) {
                val unique = LinkedHashSet<SizeF>()
                var minimumWidth = Float.POSITIVE_INFINITY
                var minimumHeight = Float.POSITIVE_INFINITY
                for (size in sizes) {
                    if (
                        size != null &&
                            size.width > 0 &&
                            size.height > 0 &&
                            size.width.isFinite() &&
                            size.height.isFinite()
                    ) {
                        unique += size
                        minimumWidth = minOf(minimumWidth, size.width)
                        minimumHeight = minOf(minimumHeight, size.height)
                    }
                }
                if (unique.isNotEmpty()) {
                    batch.naturalSizes = LinkedHashSet()
                    val dimensions = unique.toList()
                    val minimum = SizeF(minimumWidth, minimumHeight)
                    val includesMinimum = minimum in dimensions.take(16)
                    // A jointly minimum fallback must fit within the platform's sixteen layouts.
                    val sourceLimit = if (includesMinimum) 16 else 15
                    val layouts = LinkedHashMap<SizeF, RemoteViews>()
                    for (size in dimensions.take(sourceLimit)) layouts[size] =
                        render(context, world, settings, size.width, size.height, batch)
                    if (!includesMinimum)
                        layouts[minimum] =
                            render(
                                context,
                                world,
                                settings,
                                minimum.width,
                                minimum.height,
                                batch,
                            )
                    for (contentSize in batch.naturalSizes.orEmpty().toList()) {
                        if (layouts.size == 16) break
                        val size = SizeF(contentSize.width, maxOf(minimumHeight, contentSize.height))
                        if (size !in layouts)
                            layouts[size] = render(context, world, settings, size.width, size.height, batch)
                    }
                    return RemoteViews(layouts)
                }
            }
        }
        val width =
            if (id < 0) 300f
            else options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 150).toFloat()
        val height =
            if (id < 0) 128f
            else options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90).toFloat()
        val portrait = render(
            context,
            world,
            settings,
            maxOf(60f, width),
            maxOf(35f, height),
            batch,
        )
        if (id < 0) return portrait
        val landscapeWidth = maxOf(60f,
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width.toInt()).toFloat())
        val landscapeHeight = maxOf(35f,
            options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height.toInt()).toFloat())
        if (landscapeWidth == maxOf(60f, width) && landscapeHeight == maxOf(35f, height)) return portrait
        return RemoteViews(render(context, world, settings, landscapeWidth, landscapeHeight, batch), portrait)
    }

    private fun render(
        context: Context,
        world: Boolean,
        settings: WidgetSettings,
        width: Float,
        height: Float,
        batch: WidgetRenderBatch,
    ) =
        if (world) renderWorld(context, settings, width, height, batch)
        else ClockWidgetRenderer.render(context, settings, width, height, batch)

    private data class WorldGeometry(
        val list: Boolean, val columns: Int, val rows: Int, val inset: Int, val gap: Int,
        val cardWidth: Float, val vertical: Boolean, val cardHeight: Float, val baseRadius: Int,
    )

    private fun worldGeometry(count: Int, width: Float, height: Float): WorldGeometry {
        val list = width >= 245 && height >= 136 && width < height * 2.4f
        val columns = if (list || count == 1) 1 else if (width >= 245 || count >= 3) 2 else 1
        val rows = (count + columns - 1) / columns
        val compactStack = !list && columns == 1 && count > 1
        val inset = if (list) 0 else if (compactStack) 4 else 8
        val gap = if (list) 0 else if (compactStack) 4 else 8
        val cardWidth = maxOf(35f, (width - inset * 2 - gap * (columns - 1)) / columns)
        val panelDp = if (list) minOf(height, (width - inset * 2) / 2f + inset * 2) else height
        val availableCardHeight = maxOf(4f, (panelDp - inset * 2 - gap * (rows - 1)) / rows)
        val vertical = cardWidth < 123
        val cardHeight =
            if (list || vertical) availableCardHeight
            else minOf(availableCardHeight, cardWidth / 2.6f)
        val baseRadius = if (list) minOf(28, maxOf(12, (cardHeight * rows / 10).toInt() * 2))
            else worldCornerRadius(cardWidth, cardHeight)
        return WorldGeometry(list, columns, rows, inset, gap, cardWidth, vertical, cardHeight, baseRadius)
    }

    private fun renderWorld(
        context: Context,
        settings: WidgetSettings,
        width: Float,
        height: Float,
        batch: WidgetRenderBatch,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.world_widget)
        val configured = settings.cities.orEmpty().ifEmpty { arrayOf("America/Los_Angeles") }
        val count = minOf(4, configured.size)
        val geometry = worldGeometry(count, width, height)
        val list = geometry.list
        val columns = geometry.columns
        val rows = geometry.rows
        val inset = geometry.inset
        val gap = geometry.gap
        val cardWidth = geometry.cardWidth
        val vertical = geometry.vertical
        val cardHeight = geometry.cardHeight
        val naturalCardHeight = if (vertical && !list)
            minOf(cardHeight, kotlin.math.ceil(34f * 1.15f + 13f * 1.15f + 12f + 10f + 2f))
        else cardHeight
        batch.recordNaturalSize(width, height, naturalCardHeight * rows + gap * (rows - 1) + inset * 2)
        val panelHeight = px(context, cardHeight * rows + gap * (rows - 1) + inset * 2)
        views.removeAllViews(R.id.world_rows)
        views.setViewPadding(
            R.id.world_rows,
            px(context, inset),
            px(context, inset),
            px(context, inset),
            px(context, inset),
        )
        views.setInt(R.id.world_rows, "setMinimumHeight", panelHeight)
        views.setInt(R.id.widget_background, "setMinimumHeight", panelHeight)
        val baseRadius = geometry.baseRadius
        val cardRadius = scaledWorldCornerRadius(baseRadius, settings.worldCardCornerScale)
        val backdropRadius =
            scaledWorldCornerRadius(baseRadius + inset, settings.worldBackdropCornerScale)
        views.setImageViewResource(
            R.id.widget_background,
            if (backdropRadius == 0) R.drawable.widget_list_middle
            else R.drawable.widget_world_frame,
        )
        views.setInt(R.id.widget_background, "setImageLevel", backdropRadius)
        tintShape(views, R.id.widget_background, settings.worldBackdropColor)
        views.setViewVisibility(
            R.id.widget_background,
            if (settings.outline && !list) View.VISIBLE else View.GONE,
        )
        var city = 0
        for (rowIndex in 0 until rows) {
            if (rowIndex > 0 && gap > 0)
                batch.addView(views,
                    R.id.world_rows,
                    RemoteViews(
                        context.packageName,
                        if (gap == 4) R.layout.world_gap_vertical_small
                        else R.layout.world_gap_vertical,
                    ), rowIndex * 2 - 1,
                )
            val row = RemoteViews(context.packageName, R.layout.world_widget_row)
            if (Build.VERSION.SDK_INT >= 31 && batch.fontScope == WidgetFont.Scope.LOCAL)
                row.removeAllViews(R.id.world_row)
            for (column in 0 until columns) {
                if (city >= count) break
                if (column > 0)
                    batch.addView(row,
                        R.id.world_row,
                        RemoteViews(context.packageName, R.layout.world_gap_horizontal), column * 2 - 1,
                    )
                batch.addView(row,
                    R.id.world_row,
                    renderCity(
                        context,
                        settings,
                        configured[city],
                        cardWidth,
                        cardHeight,
                        vertical,
                        list,
                        rowIndex,
                        rows,
                        cardRadius,
                        batch,
                    ), column * 2,
                )
                city++
            }
            batch.addView(views, R.id.world_rows, row, rowIndex * 2)
        }
        views.setOnClickPendingIntent(R.id.widget_root, batch.pending(settings.tapAction))
        return views
    }

    private fun renderCity(
        context: Context,
        settings: WidgetSettings,
        cityId: String,
        width: Float,
        height: Float,
        vertical: Boolean,
        list: Boolean,
        rowIndex: Int,
        rowCount: Int,
        radius: Int,
        batch: WidgetRenderBatch,
    ): RemoteViews {
        val layout =
            if (list) R.layout.world_widget_list_card
            else if (vertical) R.layout.world_widget_card_vertical else R.layout.world_widget_card
        val card = RemoteViews(context.packageName, layout)
        val zone = CityCatalog.timeZoneId(cityId)
        val timeZone = TimeZone.getTimeZone(zone)
        val day = isWorldDay(settings, Calendar.getInstance(timeZone).get(Calendar.HOUR_OF_DAY))
        val text = worldTextColor(settings, day)
        if (list) {
            val background = worldCardBackground(true, rowIndex, rowCount, radius)
            card.setImageViewResource(R.id.card_background, background)
            card.setInt(R.id.card_background, "setImageLevel", radius)
            card.setViewVisibility(
                R.id.world_list_divider,
                if (rowIndex == rowCount - 1) View.GONE else View.VISIBLE,
            )
            tintShape(card, R.id.world_list_divider, withAlpha(text, 18))
        } else {
            card.setImageViewResource(
                R.id.card_background,
                worldCardBackground(false, rowIndex, rowCount, radius),
            )
            card.setInt(R.id.card_background, "setImageLevel", radius)
        }
        tintShape(card, R.id.card_background, worldBackgroundColor(settings, day))
        val name = CityCatalog.name(context, cityId, batch.resourceLocale())
        card.setTextViewText(
            R.id.city_name,
            WidgetFont.styled(context, name, settings.fontFamily, settings.fontWeight, batch.fontScope),
        )
        card.setTextColor(R.id.city_name, text)
        card.setTextColor(R.id.city_time, text)
        card.setTextColor(R.id.city_date, text)
        val scale = context.resources.configuration.fontScale
        val timeOnly = height < 24
        val showDate = settings.showDate && height >= (if (list) 28 else if (vertical) 50 else 32)
        var timeSize: Float
        var nameSize: Float
        when {
            list -> {
                timeSize =
                    minOf(36f, minOf(height * .67f, width / (if (settings.hour24) 7f else 10.5f))) /
                        scale
                nameSize = minOf(18f, maxOf(9f, height * .34f)) / scale
            }
            vertical -> {
                val nameDp = minOf(13f, maxOf(8f, height * .2f))
                val fixedHeight =
                    nameDp * 1.15f + (if (showDate) 12 else 0) + (if (height < 45) 4 else 10) + 2
                timeSize =
                    minOf(
                        minOf(34f, (width - 10) / (if (settings.hour24) 2.9f else 4.3f)),
                        maxOf(10f, height - fixedHeight) / 1.15f,
                    ) / scale
                nameSize = nameDp / scale
            }
            else -> {
                timeSize =
                    minOf(
                        36f,
                        minOf(height * .62f, width / (if (settings.hour24) 4.5f else 7.8f)),
                    ) / scale
                nameSize = minOf(if (width < 180) 16f else 18f, maxOf(9f, height * .34f)) / scale
            }
        }
        timeSize = maxOf(10 / scale, timeSize)
        nameSize = maxOf(8 / scale, nameSize)
        val horizontalPaddingDp = if (list) 16 else if (width < 160) 6 else 9
        val timePattern = if (settings.hour24) "HH:mm" else "h:mm a"
        if (timeOnly)
            timeSize = maxOf(6f,
                minOf(height / 1.15f, (width - 4) / (if (settings.hour24) 2.9f else 4.3f))) / scale
        val timeWidth = width - (if (timeOnly) 4 else horizontalPaddingDp * 2) -
            if (vertical || timeOnly) 0f else 5f + maxOf(16f, nameSize * scale)
        timeSize = fitPattern(context, timePattern, settings.fontWeight, timeSize,
            maxOf(1f, timeWidth), batch.phoneLocale(), timeZone, batch)
        var dateWidth = maxOf(1f, width - horizontalPaddingDp * 2)
        if (!vertical) {
            val measure =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = batch.typeface(settings.fontWeight)
                    textSize = spPixels(context, timeSize)
                }
            dateWidth =
                maxOf(
                    1f,
                    width -
                        horizontalPaddingDp * 2 -
                        5 -
                        ClockPattern.fittingSamples(timePattern, batch.phoneLocale(), timeZone)
                            .maxOf(measure::measureText) /
                            context.resources.displayMetrics.density,
                )
        }
        card.setViewVisibility(R.id.city_name, if (timeOnly) View.GONE else View.VISIBLE)
        card.setTextViewTextSize(R.id.city_time, TypedValue.COMPLEX_UNIT_PX, spPixels(context, timeSize))
        card.setTextViewTextSize(R.id.city_name, TypedValue.COMPLEX_UNIT_PX, spPixels(context, nameSize))
        setClock(
            context,
            card,
            R.id.city_time,
            zone,
            timePattern,
            settings.fontFamily,
            settings.fontWeight,
            batch.fontScope,
        )
        val offset = offsetLabel(timeZone, TimeZone.getDefault(), System.currentTimeMillis())
        var datePattern =
            if (batch.localizedDateSymbols()) {
                if (list) "MMM d EEEE" else "EEE"
            } else {
                if (list) "dd.MM" else "d"
            }
        datePattern += (if (list) "', " else "'  ") + offset.replace("'", "''") + "'"
        var dateSize = maxOf(8 / scale, minOf((if (list) 12 else 14) / scale, nameSize * .82f))
        dateSize =
            fitPattern(
                context,
                datePattern,
                settings.fontWeight,
                dateSize,
                dateWidth,
                batch.phoneLocale(),
                timeZone,
                batch,
            )
        dateSize =
            refineWorldCaption(context, datePattern, settings, dateSize, dateWidth, timeZone, batch)
        card.setTextViewTextSize(
            R.id.city_date,
            TypedValue.COMPLEX_UNIT_PX,
            spPixels(context, maxOf(6 / scale, dateSize)),
        )
        setClock(
            context,
            card,
            R.id.city_date,
            zone,
            datePattern,
            settings.fontFamily,
            settings.fontWeight,
            batch.fontScope,
        )
        card.setViewVisibility(R.id.city_date, if (showDate) View.VISIBLE else View.GONE)
        val horizontalPadding = px(context, if (timeOnly) 2 else horizontalPaddingDp)
        val verticalPadding = px(context, if (timeOnly) 0 else if (height < 45) 2 else 5)
        card.setViewPadding(
            R.id.card_content,
            horizontalPadding,
            verticalPadding,
            horizontalPadding,
            verticalPadding,
        )
        card.setOnClickPendingIntent(R.id.world_card, batch.pending(settings.tapAction))
        return card
    }

    private fun refineWorldCaption(
        context: Context,
        pattern: String,
        settings: WidgetSettings,
        size: Float,
        width: Float,
        zone: TimeZone,
        batch: WidgetRenderBatch,
    ): Float {
        val samples = ClockPattern.fittingSamples(pattern, batch.phoneLocale(), zone)
        var lower = 6 / context.resources.configuration.fontScale
        var upper = maxOf(lower, size)
        val limit = maxOf(1f, width * context.resources.displayMetrics.density - 1)
        val paint = measurePaint(context, settings.fontWeight, upper, batch)
        if (captionFits(paint, samples, limit)) return upper
        repeat(10) {
            val middle = (lower + upper) / 2
            paint.textSize = spPixels(context, middle)
            if (captionFits(paint, samples, limit)) lower = middle else upper = middle
        }
        return lower
    }

    private fun captionFits(paint: Paint, samples: Array<String>, width: Float) = samples.all {
        paint.measureText(it) <= width
    }

    private fun worldCornerRadius(width: Float, height: Float) =
        ((minOf(width, height) / 6f).toInt() * 2).coerceIn(4, 24)

    private fun scaledWorldCornerRadius(radius: Int, scale: Int) =
        (radius * scale.coerceIn(0, WidgetSettings.MAX_WORLD_CORNER_SCALE) / 100f).roundToInt()

    /** Relative UTC offsets include non-integral zones and use current DST rules. */
    fun offsetLabel(there: TimeZone, here: TimeZone, now: Long): String {
        val minutes = (there.getOffset(now) - here.getOffset(now)) / 60000
        val absolute = abs(minutes)
        return (if (minutes < 0) "−" else "+") +
            String.format(
                Locale.ROOT,
                if (absolute % 60 == 0) "%02d" else "%02d:%02d",
                absolute / 60,
                absolute % 60,
            )
    }

    private fun tintShape(views: RemoteViews, view: Int, color: Int) {
        views.setInt(view, "setColorFilter", opaqueRgb(color))
        views.setInt(view, "setImageAlpha", Color.alpha(color))
    }

    private fun tintShape(view: ImageView, color: Int) {
        view.setColorFilter(opaqueRgb(color))
        view.imageAlpha = Color.alpha(color)
    }

    private fun opaqueRgb(color: Int) = 0xff000000.toInt() or (color and 0x00ffffff)

    private fun isWorldDay(settings: WidgetSettings, hour: Int) =
        settings.style == WorldClockStyle.LIGHT || settings.style == WorldClockStyle.AUTO && hour in 7 until 19

    private fun worldTextColor(settings: WidgetSettings, day: Boolean) =
        if (day) settings.worldDayTextColor else settings.worldNightTextColor

    private fun worldBackgroundColor(settings: WidgetSettings, day: Boolean) =
        withOpacity(
            if (day) settings.worldDayBackgroundColor else settings.worldNightBackgroundColor,
            settings.opacity,
        )

    private fun withOpacity(color: Int, percent: Int) =
        withAlpha(color, percent.coerceIn(0, 100) * 255 / 100)

    private fun withAlpha(color: Int, alpha: Int) =
        ((Color.alpha(color) * alpha.coerceIn(0, 255) / 255) shl 24) or (color and 0x00ffffff)

    fun alarmText(context: Context, settings: WidgetSettings): String {
        return formatAlarm(context, settings, SystemAlarmClock.triggerTime(context))
    }


}

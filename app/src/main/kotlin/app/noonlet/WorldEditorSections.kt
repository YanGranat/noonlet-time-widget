package app.noonlet

import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import java.time.ZoneId
import java.time.ZonedDateTime

internal class WorldEditorSections(private val host: EditorHost, private val controls: EditorControls) {
    fun worldActionsControls(content: LinearLayout) {
        val actions = host.section(content, EditorSection.ACTIONS)
        controls.tapRow(
            actions,
            host.ui.tr(R.string.widget_tap),
            host.session.settings.tapAction,
            { value -> host.session.settings.tapAction = value },
        )
    }

    fun worldCitiesControls(content: LinearLayout) {
        val cities = host.section(content, EditorSection.CITIES)
        val list = host.ui.vertical()
        cities.addView(list)
        val ordering = PlaceOrderDrag(list, host.ui.palette.selectedSurface) { from, to ->
            val values = host.session.settings.cities.toMutableList()
            values.add(to, values.removeAt(from))
            host.session.settings.cities = values.toTypedArray()
            host.refreshSettingsSections(EditorSection.CITIES)
        }
        for (index in host.session.settings.cities.indices) {
            val zone = host.session.settings.cities[index]
            val item = host.ui.vertical()
            item.tag = "city-row:$zone"
            val line = host.ui.horizontal()
            line.setPadding(host.ui.dp(4f), host.ui.dp(4f), host.ui.dp(4f), host.ui.dp(4f))
            val remove = host.ui.navigationIcon(R.drawable.ic_delete,
                host.ui.tr(R.string.remove) + ": " + CityCatalog.name(host.activity, zone, host.ui.locale()))
            remove.tag = "city-remove:$zone"
            remove.isEnabled = host.session.settings.cities.size > 1
            remove.alpha = if (remove.isEnabled) 1f else .35f
            line.addView(remove, LinearLayout.LayoutParams(host.ui.dp(48f), host.ui.dp(48f)))
            remove.setOnClickListener {
                host.session.settings.cities = host.session.settings.cities.filter { it != zone }.toTypedArray()
                host.refreshSettingsSections(EditorSection.CITIES)
            }
            val cityText = host.ui.vertical()
            val name = host.ui.text(CityCatalog.name(host.activity, zone, host.ui.locale()), 16f, host.ui.palette.text)
            name.setSingleLine(true)
            name.ellipsize = android.text.TextUtils.TruncateAt.END
            cityText.addView(name)
            val now = ZonedDateTime.now(ZoneId.of(CityCatalog.timeZoneId(zone)))
            val zoneText = host.ui.text("UTC" + now.offset.id.replace("Z", "+00:00"), 12f, host.ui.palette.muted)
            cityText.addView(zoneText)
            cityText.minimumHeight = host.ui.dp(48f)
            line.addView(cityText, LinearLayout.LayoutParams(0, -2, 1f))
            cityText.gravity = Gravity.CENTER_VERTICAL
            cityText.setOnClickListener(
                { v ->
                    host.placePicker.show(
                        false,
                        setOf(zone),
                        { selected ->
                            val position = host.session.settings.cities.indexOf(zone)
                            if (position < 0) return@show
                            if (
                                host.session.settings.cities.withIndex().any { (other, value) ->
                                    other != position && CityCatalog.selectionKey(value) == CityCatalog.selectionKey(selected)
                                }
                            ) {
                                Toast.makeText(
                                        host.activity,
                                        host.ui.tr(R.string.city_already_added),
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                                return@show
                            }
                            host.session.settings.cities[position] = selected
                            host.refreshSettingsSections(EditorSection.CITIES)
                        },
                    )
                },
            )
            val handle = host.ui.navigationIcon(R.drawable.ic_drag_handle,
                host.ui.tr(R.string.reorder_place, name.text))
            handle.tag = "city-drag:$zone"
            handle.isEnabled = host.session.settings.cities.size > 1
            handle.alpha = if (handle.isEnabled) 1f else .35f
            line.addView(handle, LinearLayout.LayoutParams(host.ui.dp(48f), host.ui.dp(48f)))
            item.addView(line)
            host.ui.divider(item)
            list.addView(item)
            ordering.attach(item, handle)
        }
        if (host.session.settings.cities.size < 4) {
            controls.row(
                cities,
                host.ui.tr(R.string.add_place),
                "",
                AppIcons.Kind.ADD,
                {
                    host.placePicker.show(
                        false,
                        host.session.settings.cities.toSet(),
                        { selected ->
                            if (host.session.settings.cities.any {
                                CityCatalog.selectionKey(it) == CityCatalog.selectionKey(selected)
                            }) {
                                Toast.makeText(
                                        host.activity,
                                        host.ui.tr(R.string.city_already_added),
                                        Toast.LENGTH_SHORT,
                                    )
                                    .show()
                                return@show
                            }
                            val values = host.session.settings.cities.toMutableList()
                            values.add(selected)
                            host.session.settings.cities = values.toTypedArray()
                            host.refreshSettingsSections(EditorSection.CITIES)
                        },
                    )
                },
            )
        }
        if (host.session.settings.cities.size > 2) {
            val hint =
                host.ui.text(
                    host.ui.tr(R.string.more_cities_hint),
                    13f,
                    host.ui.palette.muted,
                )
            hint.setPadding(host.ui.dp(16f), host.ui.dp(12f), host.ui.dp(16f), host.ui.dp(12f))
            cities.addView(hint)
        }
    }

    fun worldAppearanceControls(content: LinearLayout, defaults: WidgetSettings) {
        val appearance = host.section(content, EditorSection.APPEARANCE)
        controls.fontRow(appearance)
        host.ui.divider(appearance)
        controls.slider(
            appearance,
            host.ui.tr(R.string.font_weight),
            host.session.settings.fontWeight,
            100,
            900,
            50,
            defaults.fontWeight,
            { value ->
                host.session.settings.fontWeight = value
                host.preview.queueFull()
            },
        )
        controls.fontWeightHint(appearance)
        host.ui.divider(appearance)
        val style =
            if (host.session.settings.style == WorldClockStyle.LIGHT) host.ui.tr(R.string.day)
            else if (host.session.settings.style == WorldClockStyle.DARK) host.ui.tr(R.string.night)
            else host.ui.tr(R.string.automatic)
        controls.row(
            appearance,
            host.ui.tr(R.string.day_night_mode),
            style,
            {
                val styles = WorldClockStyle.entries
                AppDialogBuilder(host.activity)
                    .setTitle(host.ui.tr(R.string.day_night_mode))
                    .setSingleChoiceItems(
                        arrayOf<String>(
                            host.ui.tr(R.string.automatic),
                            host.ui.tr(R.string.day),
                            host.ui.tr(R.string.night),
                        ),
                        styles.indexOf(host.session.settings.style),
                        { dialog, item ->
                            host.session.settings.style = styles[item]
                            dialog.dismiss()
                            host.refreshSettingsSections(EditorSection.APPEARANCE)
                        },
                    )
                    .show()
            },
        )
        host.ui.divider(appearance)
        controls.slider(
            appearance,
            host.ui.tr(R.string.opacity),
            host.session.settings.opacity,
            0,
            100,
            defaults.opacity,
            { value ->
                host.session.settings.opacity = value
                host.preview.queueAppearance()
            },
        )
        host.ui.divider(appearance)
        controls.slider(
            appearance,
            host.ui.tr(R.string.world_card_roundness),
            host.session.settings.worldCardCornerScale,
            0,
            WidgetSettings.MAX_WORLD_CORNER_SCALE,
            10,
            defaults.worldCardCornerScale,
            { value ->
                host.session.settings.worldCardCornerScale = value
                host.preview.queueAppearance()
            },
            valueFormatter = { value -> host.ui.tr(R.string.percentage_value, value) },
        )
        val roundnessHint = host.ui.text(host.ui.tr(R.string.roundness_scale_hint), 12f, host.ui.palette.muted)
        roundnessHint.setPadding(host.ui.dp(16f), 0, host.ui.dp(16f), host.ui.dp(8f))
        appearance.addView(roundnessHint)
        host.ui.divider(appearance)
        controls.toggle(
            appearance,
            host.ui.tr(R.string.translucent_backdrop),
            host.session.settings.outline,
            { value ->
                host.session.settings.outline = value
                host.preview.refresh()
            },
        )
        host.ui.divider(appearance)
        controls.colorRow(
            appearance,
            host.ui.tr(R.string.backdrop_color),
            host.session.settings.worldBackdropColor,
            { value -> host.session.settings.worldBackdropColor = value },
        )
        host.ui.divider(appearance)
        controls.slider(
            appearance,
            host.ui.tr(R.string.world_backdrop_roundness),
            host.session.settings.worldBackdropCornerScale,
            0,
            WidgetSettings.MAX_WORLD_CORNER_SCALE,
            10,
            defaults.worldBackdropCornerScale,
            { value ->
                host.session.settings.worldBackdropCornerScale = value
                host.preview.queueAppearance()
            },
            valueFormatter = { value -> host.ui.tr(R.string.percentage_value, value) },
        )
        host.ui.divider(appearance)
        controls.toggle(
            appearance,
            host.ui.tr(R.string.hour_24_clock),
            host.session.settings.hour24,
            { value ->
                host.session.settings.hour24 = value
                host.session.settings.timeFormat = if (value) "HH:mm" else "h:mm a"
                host.preview.refresh()
            },
        )
        host.ui.divider(appearance)
        controls.toggle(
            appearance,
            host.ui.tr(R.string.date_and_time_difference),
            host.session.settings.showDate,
            { value ->
                host.session.settings.showDate = value
                host.preview.refresh()
            },
        )
        host.ui.divider(appearance)
        controls.resetAppearanceRow(appearance)
    }

    fun worldForegroundControls(content: LinearLayout) {
        val foreground = host.section(content, EditorSection.FOREGROUND)
        controls.colorRow(
            foreground,
            host.ui.tr(R.string.day),
            host.session.settings.worldDayTextColor,
            { value -> host.session.settings.worldDayTextColor = value },
        )
        host.ui.divider(foreground)
        controls.colorRow(
            foreground,
            host.ui.tr(R.string.night),
            host.session.settings.worldNightTextColor,
            { value -> host.session.settings.worldNightTextColor = value },
        )
    }

    fun worldBackgroundControls(content: LinearLayout) {
        val background = host.section(content, EditorSection.WORLD_BACKGROUND)
        controls.colorRow(
            background,
            host.ui.tr(R.string.day),
            host.session.settings.worldDayBackgroundColor,
            { value -> host.session.settings.worldDayBackgroundColor = value },
        )
        host.ui.divider(background)
        controls.colorRow(
            background,
            host.ui.tr(R.string.night),
            host.session.settings.worldNightBackgroundColor,
            { value -> host.session.settings.worldNightBackgroundColor = value },
        )
    }

}

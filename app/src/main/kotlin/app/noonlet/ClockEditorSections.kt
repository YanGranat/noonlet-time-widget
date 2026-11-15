package app.noonlet

import android.widget.LinearLayout

internal class ClockEditorSections(private val host: EditorHost, private val controls: EditorControls) {
    fun clockActionsControls(content: LinearLayout) {
        val actions = host.section(content, EditorSection.ACTIONS)
        controls.tapRow(
            actions,
            host.ui.tr(R.string.widget_tap),
            host.session.settings.tapAction,
            { value -> host.session.settings.tapAction = value },
        )
        host.ui.divider(actions)
        controls.toggle(
            actions,
            host.ui.tr(R.string.advanced_tap_actions),
            host.session.settings.advancedTap,
            { value ->
                host.session.settings.advancedTap = value
                host.refreshSettingsSections(EditorSection.ACTIONS)
            },
        )
        if (host.session.settings.advancedTap) {
            host.ui.divider(actions)
            controls.tapRow(
                actions,
                host.ui.tr(R.string.tap_time),
                host.session.settings.timeTapAction,
                { value -> host.session.settings.timeTapAction = value },
            )
            host.ui.divider(actions)
            controls.tapRow(
                actions,
                host.ui.tr(R.string.tap_date),
                host.session.settings.dateTapAction,
                { value -> host.session.settings.dateTapAction = value },
            )
            host.ui.divider(actions)
            controls.tapRow(
                actions,
                host.ui.tr(R.string.tap_alarm),
                host.session.settings.alarmTapAction,
                { value -> host.session.settings.alarmTapAction = value },
            )
        }
    }

    fun clockAppearanceControls(content: LinearLayout, defaults: WidgetSettings) {
        val appearance = host.section(content, EditorSection.APPEARANCE)
        controls.fontRow(appearance)
        host.ui.divider(appearance)
        controls.row(
            appearance,
            host.ui.tr(R.string.widget_orientation),
            if (host.session.settings.orientation == WidgetOrientation.HORIZONTAL) host.ui.tr(R.string.horizontal)
            else host.ui.tr(R.string.vertical),
            {
                AppDialogBuilder(host.activity)
                    .setTitle(host.ui.tr(R.string.widget_orientation))
                    .setSingleChoiceItems(
                        arrayOf<String>(
                            host.ui.tr(R.string.vertical),
                            host.ui.tr(R.string.horizontal),
                        ),
                        if (host.session.settings.orientation == WidgetOrientation.HORIZONTAL) 1 else 0,
                        { dialog, item ->
                            host.session.settings.orientation =
                                if (item == 0) WidgetOrientation.VERTICAL else WidgetOrientation.HORIZONTAL
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
            host.ui.tr(R.string.time_date_spacing),
            host.session.settings.timeDateGap,
            0,
            32,
            defaults.timeDateGap,
            { value ->
                host.session.settings.timeDateGap = value
                host.preview.queueFull()
            },
        )
        host.ui.divider(appearance)
        controls.resetAppearanceRow(appearance)
    }

    fun clockPaddingControls(content: LinearLayout, defaults: WidgetSettings) {
        val padding = host.section(content, EditorSection.PADDING)
        controls.slider(
            padding,
            host.ui.tr(R.string.top_padding),
            host.session.settings.paddingTop,
            0,
            80,
            defaults.paddingTop,
            { value ->
                host.session.settings.paddingTop = value
                host.preview.queueFull()
            },
        )
        host.ui.divider(padding)
        controls.slider(
            padding,
            host.ui.tr(R.string.bottom_padding),
            host.session.settings.paddingBottom,
            0,
            80,
            defaults.paddingBottom,
            { value ->
                host.session.settings.paddingBottom = value
                host.preview.queueFull()
            },
        )
        host.ui.divider(padding)
        controls.slider(
            padding,
            host.ui.tr(R.string.left_padding),
            host.session.settings.paddingLeft,
            0,
            80,
            defaults.paddingLeft,
            { value ->
                host.session.settings.paddingLeft = value
                host.preview.queueFull()
            },
        )
        host.ui.divider(padding)
        controls.slider(
            padding,
            host.ui.tr(R.string.right_padding),
            host.session.settings.paddingRight,
            0,
            80,
            defaults.paddingRight,
            { value ->
                host.session.settings.paddingRight = value
                host.preview.queueFull()
            },
        )
    }

    fun clockTimeControls(content: LinearLayout, defaults: WidgetSettings) {
        val time = host.section(content, EditorSection.TIME)
        val zone =
            if (host.session.settings.zoneId.isEmpty()) host.ui.tr(R.string.device_time)
            else CityCatalog.name(host.activity, host.session.settings.zoneId, host.ui.locale())
        controls.row(
            time,
            host.ui.tr(R.string.time_zone),
            zone,
            {
                host.placePicker.show(
                    true,
                    setOf(host.session.settings.zoneId),
                    { city ->
                        host.session.settings.zoneId = city
                        host.refreshSettingsSections(EditorSection.TIME)
                    },
                )
            },
        )
        host.ui.divider(time)
        controls.colorRow(
            time,
            host.ui.tr(R.string.clock_color),
            host.session.settings.timeColor,
            { value -> host.session.settings.timeColor = value },
        )
        host.ui.divider(time)
        controls.formatRow(
            time,
            host.ui.tr(R.string.time_format),
            host.session.settings.timeFormat,
            arrayOf<String>("HH:mm", "H:mm", "h:mm a", "hh:mm a", "HH:mm:ss", "h:mm:ss a"),
            { value ->
                host.session.settings.timeFormat = value
                host.session.settings.hour24 = value.contains("H") || value.contains("k")
            },
        )
        host.ui.divider(time)
        controls.slider(
            time,
            host.ui.tr(R.string.time_size),
            host.session.settings.textSize,
            8,
            WidgetSettings.MAX_TIME_SIZE,
            defaults.textSize,
            { value ->
                host.session.settings.textSize = value
                host.preview.queueFull()
            },
        )
        host.ui.divider(time)
        controls.slider(
            time,
            host.ui.tr(R.string.digit_weight),
            host.session.settings.timeFontWeight,
            100,
            900,
            50,
            defaults.timeFontWeight,
            { value ->
                host.session.settings.timeFontWeight = value
                host.preview.queueFull()
            },
        )
        controls.fontWeightHint(time)
        host.ui.divider(time)
        controls.alignmentRow(
            time,
            host.ui.tr(R.string.time_alignment),
            host.session.settings.alignment,
            { value -> host.session.settings.alignment = value },
        )
        host.ui.divider(time)
        controls.slider(
            time,
            host.ui.tr(R.string.time_horizontal_offset),
            host.session.settings.timeOffsetX,
            -40,
            40,
            defaults.timeOffsetX,
            { value ->
                host.session.settings.timeOffsetX = value
                host.preview.queueAppearance()
            },
        )
        host.ui.divider(time)
        controls.slider(
            time,
            host.ui.tr(R.string.time_vertical_offset),
            host.session.settings.timeOffsetY,
            -40,
            40,
            defaults.timeOffsetY,
            { value ->
                host.session.settings.timeOffsetY = value
                host.preview.queueAppearance()
            },
        )
    }

    fun clockDateControls(content: LinearLayout, defaults: WidgetSettings) {
        val date = host.section(content, EditorSection.DATE)
        controls.toggle(
            date,
            host.ui.tr(R.string.show_date),
            host.session.settings.showDate,
            { value ->
                host.session.settings.showDate = value
                host.preview.refresh()
            },
        )
        host.ui.divider(date)
        controls.colorRow(
            date,
            host.ui.tr(R.string.date_color),
            host.session.settings.dateColor,
            { value -> host.session.settings.dateColor = value },
        )
        host.ui.divider(date)
        controls.formatRow(
            date,
            host.ui.tr(R.string.date_format),
            host.session.settings.dateFormat,
            arrayOf<String>(
                "EEE, d MMM",
                "EEEE, d MMMM",
                "d MMMM",
                "dd.MM.yyyy",
                "EEE, dd.MM",
                "yyyy-MM-dd",
                "MMM d",
            ),
            { value -> host.session.settings.dateFormat = value },
        )
        host.ui.divider(date)
        controls.slider(
            date,
            host.ui.tr(R.string.date_size),
            host.session.settings.dateSize,
            6,
            WidgetSettings.MAX_DATE_SIZE,
            defaults.dateSize,
            { value ->
                host.session.settings.dateSize = value
                host.preview.queueFull()
            },
        )
        host.ui.divider(date)
        controls.slider(
            date,
            host.ui.tr(R.string.font_weight),
            host.session.settings.dateFontWeight,
            100,
            900,
            50,
            defaults.dateFontWeight,
            { value ->
                host.session.settings.dateFontWeight = value
                host.preview.queueFull()
            },
        )
        controls.fontWeightHint(date)
        host.ui.divider(date)
        controls.alignmentRow(
            date,
            host.ui.tr(R.string.date_alignment),
            host.session.settings.dateAlignment,
            { value -> host.session.settings.dateAlignment = value },
        )
        host.ui.divider(date)
        controls.slider(
            date,
            host.ui.tr(R.string.date_horizontal_offset),
            host.session.settings.dateOffsetX,
            -40,
            40,
            defaults.dateOffsetX,
            { value ->
                host.session.settings.dateOffsetX = value
                host.preview.queueAppearance()
            },
        )
        host.ui.divider(date)
        controls.slider(
            date,
            host.ui.tr(R.string.date_vertical_offset),
            host.session.settings.dateOffsetY,
            -40,
            40,
            defaults.dateOffsetY,
            { value ->
                host.session.settings.dateOffsetY = value
                host.preview.queueAppearance()
            },
        )
    }

    fun clockAlarmControls(content: LinearLayout, defaults: WidgetSettings) {
        val alarm = host.section(content, EditorSection.ALARM)
        controls.toggle(
            alarm,
            host.ui.tr(R.string.show_alarm),
            host.session.settings.showAlarm,
            { value ->
                host.session.settings.showAlarm = value
                host.preview.refresh()
            },
        )
        host.ui.divider(alarm)
        controls.toggle(
            alarm,
            host.ui.tr(R.string.show_no_alarm),
            host.session.settings.showNoAlarm,
            { value ->
                host.session.settings.showNoAlarm = value
                host.preview.refresh()
            },
        )
        host.ui.divider(alarm)
        controls.toggle(
            alarm,
            host.ui.tr(R.string.show_alarm_icon),
            host.session.settings.showAlarmIcon,
            { value ->
                host.session.settings.showAlarmIcon = value
                host.preview.refresh()
            },
        )
        host.ui.divider(alarm)
        controls.formatRow(
            alarm,
            host.ui.tr(R.string.alarm_time_format),
            host.session.settings.alarmFormat,
            arrayOf<String>("HH:mm", "h:mm a"),
            { value -> host.session.settings.alarmFormat = value },
            false,
        )
        host.ui.divider(alarm)
        controls.colorRow(
            alarm,
            host.ui.tr(R.string.alarm_color),
            host.session.settings.alarmColor,
            { value -> host.session.settings.alarmColor = value },
        )
        host.ui.divider(alarm)
        controls.slider(
            alarm,
            host.ui.tr(R.string.alarm_size),
            host.session.settings.alarmSize,
            6,
            64,
            defaults.alarmSize,
            { value ->
                host.session.settings.alarmSize = value
                host.preview.queueFull()
            },
        )
        host.ui.divider(alarm)
        controls.alignmentRow(
            alarm,
            host.ui.tr(R.string.alarm_alignment),
            host.session.settings.alarmAlignment,
            { value -> host.session.settings.alarmAlignment = value },
        )
        host.ui.divider(alarm)
        controls.slider(
            alarm,
            host.ui.tr(R.string.alarm_horizontal_offset),
            host.session.settings.alarmOffsetX,
            -40,
            40,
            defaults.alarmOffsetX,
            { value ->
                host.session.settings.alarmOffsetX = value
                host.preview.queueAppearance()
            },
        )
        host.ui.divider(alarm)
        controls.slider(
            alarm,
            host.ui.tr(R.string.alarm_vertical_offset),
            host.session.settings.alarmOffsetY,
            -40,
            40,
            defaults.alarmOffsetY,
            { value ->
                host.session.settings.alarmOffsetY = value
                host.preview.queueAppearance()
            },
        )
    }

}

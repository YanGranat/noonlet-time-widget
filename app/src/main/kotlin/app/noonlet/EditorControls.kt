package app.noonlet

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

internal class EditorControls(private val host: EditorHost) {
    private var alarmSampleUpdate: (() -> Unit)? = null

    fun refreshAlarmSamples() {
        alarmSampleUpdate?.invoke()
    }

    private fun watchAlarmSamples(dialog: AlertDialog, update: () -> Unit) {
        alarmSampleUpdate = update
        dialog.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                if (alarmSampleUpdate === update) alarmSampleUpdate = null
            }
        })
    }

    fun resetAppearanceRow(parent: LinearLayout) {
        row(parent, host.ui.tr(R.string.reset_to_defaults), "", AppIcons.Kind.RESET, {
            AppDialogBuilder(host.activity)
                .setTitle(host.ui.tr(R.string.reset_to_defaults))
                .setMessage(host.ui.tr(R.string.reset_to_defaults_message))
                .setNegativeButton(host.ui.tr(R.string.cancel), null)
                .setPositiveButton(host.ui.tr(R.string.reset)) { source, _ ->
                    if ((source as AlertDialog).isShowing) host.resetAppearance()
                }
                .show()
        })
    }

    fun fontRow(parent: LinearLayout, weather: Boolean = false) {
        val labels = FONT_CHOICES.map { host.ui.tr(it.labelResource) }.toTypedArray()
        val current = FONT_CHOICES.indexOfFirst { it.family ==
            if (weather) host.session.settings.weatherFontFamily else host.session.settings.fontFamily }
        row(
            parent,
            host.ui.tr(R.string.font),
            labels[Math.max(0, current)],
            {
                val dialog =
                    AppDialogBuilder(host.activity)
                        .setTitle(host.ui.tr(R.string.font))
                        .setSingleChoiceItems(
                            labels,
                            current,
                            { d, item ->
                                val choice = FONT_CHOICES[item]
                                if (weather) {
                                    host.session.settings.weatherFontFamily = choice.family
                                    WidgetFont.presetWeight(choice.family)?.let { host.session.settings.weatherFontWeight = it }
                                } else {
                                    host.session.settings.fontFamily = choice.family
                                    WidgetFont.presetWeight(choice.family)?.let { weight ->
                                        host.session.settings.fontWeight = weight
                                        host.session.settings.timeFontWeight = weight
                                        host.session.settings.dateFontWeight = weight
                                    }
                                }
                                d.dismiss()
                                if (weather) host.refreshSettingsSections(EditorSection.WEATHER)
                                else host.refreshSettingsSections(
                                    EditorSection.APPEARANCE,
                                    if (host.session.world) null else EditorSection.TIME,
                                    if (host.session.world) null else EditorSection.DATE,
                                )
                            },
                        )
                        .create()
                dialog.show()
                val list = dialog.listView
                val samples = Runnable {
                    for (index in 0 until list.childCount) {
                        val child = list.getChildAt(index)
                        if (child is TextView) {
                            val position = list.firstVisiblePosition + index
                            if (position < FONT_CHOICES.size)
                                child.setTypeface(
                                    WidgetFont.typeface(
                                        FONT_CHOICES[position].family,
                                        WidgetFont.presetWeight(FONT_CHOICES[position].family)
                                            ?: if (weather) host.session.settings.weatherFontWeight else host.session.settings.fontWeight,
                                    ),
                                )
                        }
                    }
                }
                list.setOnScrollListener(
                    object : android.widget.AbsListView.OnScrollListener {
                        override fun onScrollStateChanged(
                            view: android.widget.AbsListView,
                            state: Int,
                        ) {}

                        override fun onScroll(
                            view: android.widget.AbsListView,
                            first: Int,
                            count: Int,
                            total: Int,
                        ) {
                            samples.run()
                        }
                    },
                )
                list.post(samples)
            },
        )
    }

    fun alignmentRow(
        parent: LinearLayout,
        title: String,
        current: WidgetAlignment,
        changed: (WidgetAlignment) -> Unit,
    ) {
        val section = host.settingsSectionKey(parent)
        val keys = WidgetAlignment.entries
        val labels = arrayOf<String>(host.ui.tr(R.string.left), host.ui.tr(R.string.center), host.ui.tr(R.string.right))
        row(
            parent,
            title,
            labels[Math.max(0, keys.indexOf(current))],
            {
                AppDialogBuilder(host.activity)
                    .setTitle(title)
                    .setSingleChoiceItems(
                        labels,
                        keys.indexOf(current),
                        { d, item ->
                            changed(keys[item])
                            d.dismiss()
                            host.refreshSettingsSections(section)
                        },
                    )
                    .show()
            },
        )
    }

    fun colorRow(
        parent: LinearLayout,
        title: String,
        current: Int,
        changed: (Int) -> Unit,
    ) {
        val section = host.settingsSectionKey(parent)
        val line = host.ui.horizontal()
        line.setPadding(host.ui.dp(16f), host.ui.dp(10f), host.ui.dp(16f), host.ui.dp(10f))
        line.minimumHeight = host.ui.dp(64f)
        val labels = host.ui.vertical()
        labels.setPadding(0, 0, host.ui.dp(12f), 0)
        labels.addView(host.ui.text(title, 15f, host.ui.palette.text))
        val value = host.ui.text(String.format(Locale.ROOT, "#%08X", current), 12f, host.ui.palette.muted)
        value.typeface = Typeface.MONOSPACE
        value.setPadding(0, host.ui.dp(4f), 0, 0)
        labels.addView(value)
        line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val swatch = View(host.activity)
        val color = host.ui.shape(current, 12f)
        color.setStroke(host.ui.dp(1f), host.ui.palette.swatchBorder)
        swatch.background = color
        line.addView(swatch, LinearLayout.LayoutParams(host.ui.dp(28f), host.ui.dp(28f)))
        line.background = AppDialogBuilder.ripple(host.activity, Color.TRANSPARENT, 12, false)
        line.setOnClickListener(
            { v ->
                ColorPicker.show(
                    host.activity,
                    title,
                    current,
                    { chosen ->
                        changed(chosen.toInt())
                        host.refreshSettingsSections(section)
                    },
                )
            },
        )
        line.isFocusable = true
        line.contentDescription = title + ", " + String.format(Locale.ROOT, "#%08X", current)
        parent.addView(line)
    }

    fun formatRow(
        parent: LinearLayout,
        title: String,
        current: String,
        formats: Array<String>,
        changed: (String) -> Unit,
        phoneFormat: Boolean = true,
    ) {
        val section = host.settingsSectionKey(parent)
        val commit: (String) -> Unit = { value ->
            changed(value)
            host.refreshSettingsSections(section)
        }
        row(
            parent,
            title,
            current,
            {
                val phoneLocale = if (phoneFormat) AppLocales.phoneLocale(host.activity) else null
                fun samples(): Array<String> {
                    val timestamp = if (phoneFormat) System.currentTimeMillis()
                        else SystemAlarmClock.triggerTime(host.activity)
                    val choices = Array(formats.size + 1) { index ->
                        if (index == formats.size) host.ui.tr(R.string.custom_format)
                        else formats[index] + "   ·   " +
                            formatSample(formats[index], phoneFormat, phoneLocale, timestamp)
                    }
                    if (formats.indexOf(current) < 0)
                        choices[formats.size] = host.ui.tr(R.string.custom_format) + "   ·   " +
                            formatSample(current, phoneFormat, phoneLocale, timestamp)
                    return choices
                }
                val choices = samples()
                val preset = formats.indexOf(current)
                val selected = if (preset >= 0) preset else formats.size
                val picker =
                    AppDialogBuilder(host.activity)
                        .setTitle(title)
                        .setChoiceItems(
                            choices,
                            selected,
                            setOf(formats.size),
                            { dialog, item ->
                                dialog.dismiss()
                                if (item == formats.size)
                                    editFormat(title, current, commit, phoneFormat)
                                else commit(formats[item])
                            },
                        )
                        .create()
                val phoneLanguage = phoneLocale?.language.orEmpty()
                val appLanguage = host.ui.locale().language
                var localized = false
                for (format in formats) if (format.contains("EEE") || format.contains("MMM"))
                    localized = true
                if (phoneFormat && localized && !appLanguage.equals(phoneLanguage)) {
                    val hint =
                        host.ui.text(
                            host.ui.tr(R.string.phone_date_language_hint),
                            12f,
                            host.ui.palette.muted,
                        )
                    hint.setPadding(host.ui.dp(24f), host.ui.dp(12f), host.ui.dp(24f), host.ui.dp(12f))
                    picker.listView.addFooterView(hint, null, false)
                }
                picker.show()
                if (!phoneFormat) watchAlarmSamples(picker) {
                    (picker.listView.adapter as AppDialogBuilder.ChoiceAdapter)
                        .replaceLabels(samples().toList(), true, selected)
                }
            },
        )
    }

    private fun formatSample(
        pattern: String,
        phoneFormat: Boolean,
        phoneLocale: Locale? = if (phoneFormat) AppLocales.phoneLocale(host.activity) else null,
        timestamp: Long? = if (phoneFormat) System.currentTimeMillis()
            else SystemAlarmClock.triggerTime(host.activity),
    ): String {
        if (phoneFormat)
            return ClockPattern.formatAt(
                pattern,
                requireNotNull(timestamp),
                java.util.TimeZone.getTimeZone(host.session.settings.effectiveZone()),
                requireNotNull(phoneLocale),
            )
        try {
            return SystemAlarmClock.format(host.activity, pattern, timestamp)
        } catch (invalid: IllegalArgumentException) {
            return ""
        }
    }

    private fun editFormat(
        title: String,
        current: String,
        changed: (String) -> Unit,
        phoneFormat: Boolean,
    ) {
        val holder = host.ui.vertical()
        holder.setPadding(host.ui.dp(24f), host.ui.dp(16f), host.ui.dp(24f), host.ui.dp(8f))
        val input = EditText(host.activity)
        input.setSingleLine(true)
        input.setTextColor(host.ui.palette.text)
        input.setHintTextColor(host.ui.palette.muted)
        input.textSize = 16f
        input.setText(current)
        input.hint = "HH:mm / EEE, d MMM"
        input.setInputType(
            android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        )
        input.setFilters(
            arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(96)),
        )
        holder.addView(input, LinearLayout.LayoutParams(-1, host.ui.dp(56f)))
        val sample = host.ui.text(formatSample(current, phoneFormat), 16f, host.ui.palette.muted)
        sample.setPadding(0, host.ui.dp(8f), 0, host.ui.dp(8f))
        sample.minimumHeight = host.ui.dp(44f)
        holder.addView(sample, LinearLayout.LayoutParams(-1, -2))
        val help =
            host.ui.text(
                host.ui.tr(R.string.date_pattern_help),
                12f,
                host.ui.palette.muted,
            )
        holder.addView(help)
        input.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    value: CharSequence,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(
                    value: CharSequence,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    sample.text = formatSample(value.toString(), phoneFormat)
                }

                override fun afterTextChanged(value: Editable) {}
            },
        )
        val dialog =
            AppDialogBuilder(host.activity)
                .setTitle(title)
                .setView(holder)
                .setNegativeButton(host.ui.tr(R.string.cancel), null)
                .setPositiveButton(host.ui.tr(R.string.save), null)
                .create()
        dialog.setOnShowListener(
            { d ->
                dialog
                    .getButton(AlertDialog.BUTTON_POSITIVE)
                    .setOnClickListener(
                        { v ->
                            if (!dialog.isShowing) return@setOnClickListener
                            val pattern = input.text.toString()
                            if (pattern.trim { it <= ' ' }.isEmpty()) {
                                input.error = host.ui.tr(R.string.enter_a_format)
                                return@setOnClickListener
                            }
                            if (phoneFormat) {
                                if (!ClockPattern.validate(pattern)) {
                                    input.setError(
                                        host.ui.tr(R.string.unsupported_clock_format),
                                    )
                                    return@setOnClickListener
                                }
                            } else {
                                try {
                                    java.text.SimpleDateFormat(pattern, AppLocales.appLocale(host.activity))
                                } catch (invalid: IllegalArgumentException) {
                                    input.setError(
                                        host.ui.tr(R.string.invalid_format_quotes),
                                    )
                                    return@setOnClickListener
                                }
                            }
                            dialog.dismiss()
                            changed(pattern)
                        },
                    )
            },
        )
        dialog.show()
        if (!phoneFormat) watchAlarmSamples(dialog) {
            sample.text = formatSample(input.text.toString(), false)
        }
        input.setSelection(input.length())
    }

    fun tapRow(
        parent: LinearLayout,
        title: String,
        current: String,
        changed: (String) -> Unit,
    ) {
        val section = host.settingsSectionKey(parent)
        val commit: (String) -> Unit = { value ->
            changed(value)
            host.refreshSettingsSections(section)
        }
        row(
            parent,
            title,
            TapActions.label(host.activity, current, host.ui.locale()),
            {
                val actions = TapActions.builtinChoices
                val custom = TapActions.isCustomApp(current)
                val labels =
                    (actions.map { host.ui.tr(it.label) } + host.ui.tr(R.string.choose_an_app)).toTypedArray()
                if (custom) labels[actions.size] = TapActions.label(host.activity, current, host.ui.locale())
                AppDialogBuilder(host.activity)
                    .setTitle(title)
                    .setChoiceItems(
                        labels,
                        if (custom) actions.size else actions.indexOfFirst { it.value == current },
                        setOf(actions.size),
                        { dialog, item ->
                            dialog.dismiss()
                            if (item == actions.size) {
                                val editor = host.session.settings
                                val kind = host.session.world
                                val target = host.session.target.id
                                AppPickerDialog.show(host.activity, host.ui, title, current,
                                    isCurrent = { host.session.settings === editor &&
                                        host.session.world == kind && host.session.target.id == target },
                                    changed = commit)
                            }
                            else commit(actions[item].value)
                        },
                    )
                    .show()
            },
        )
    }

    fun row(parent: LinearLayout, label: String, value: String, action: () -> Unit) {
        row(parent, label, value, AppIcons.Kind.CHEVRON_RIGHT, action)
    }

    fun row(
        parent: LinearLayout,
        label: String,
        value: String,
        trailingIcon: AppIcons.Kind,
        action: () -> Unit,
        valueTag: String? = null,
    ) {
        val row = host.ui.horizontal()
        row.setPadding(host.ui.dp(16f), host.ui.dp(10f), host.ui.dp(16f), host.ui.dp(10f))
        row.minimumHeight = host.ui.dp((if (value.isEmpty()) 56 else 68).toFloat())
        row.background = AppDialogBuilder.ripple(host.activity, Color.TRANSPARENT, 12, false)
        val labels = host.ui.vertical()
        labels.setPadding(0, 0, host.ui.dp(8f), 0)
        labels.addView(host.ui.text(label, 15f, host.ui.palette.text))
        if (!value.isEmpty()) {
            val current = host.ui.text(value, 13f, host.ui.palette.muted)
            current.tag = valueTag
            current.setPadding(0, host.ui.dp(4f), 0, 0)
            labels.addView(current)
        }
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(host.ui.imageIcon(trailingIcon, host.ui.palette.muted), LinearLayout.LayoutParams(host.ui.dp(24f), host.ui.dp(24f)))
        row.setOnClickListener({ v -> action() })
        row.isFocusable = true
        parent.addView(row)
    }

    fun toggle(
        parent: LinearLayout,
        label: String,
        checked: Boolean,
        change: (Boolean) -> Unit,
    ) {
        val section = host.settingsSectionKey(parent)
        val row = host.ui.horizontal()
        row.setPadding(host.ui.dp(16f), host.ui.dp(8f), host.ui.dp(16f), host.ui.dp(8f))
        row.minimumHeight = host.ui.dp(56f)
        val name = host.ui.text(label, 15f, host.ui.palette.text)
        name.setPadding(0, 0, host.ui.dp(12f), 0)
        row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(host.activity)
        toggle.isChecked = checked
        toggle.contentDescription = label
        AppDialogBuilder.styleSwitch(toggle)
        toggle.setOnCheckedChangeListener(
            { button, value ->
                change(value)
                host.refreshSectionSummary(section)
            },
        )
        row.addView(toggle)
        row.setOnClickListener({ v -> toggle.setChecked(!toggle.isChecked) })
        parent.addView(row)
    }

    fun fontWeightHint(parent: LinearLayout, family: String = host.session.settings.fontFamily) {
        if (WidgetFont.supportsSmoothWeight(family)) return
        val hint =
            host.ui.text(
                host.ui.tr(R.string.discrete_font_weights_hint),
                12f,
                host.ui.palette.muted,
            )
        hint.setPadding(host.ui.dp(16f), 0, host.ui.dp(16f), host.ui.dp(8f))
        parent.addView(hint)
    }

    fun slider(
        parent: LinearLayout,
        label: String,
        current: Int,
        minimum: Int,
        maximum: Int,
        defaultValue: Int,
        change: (Int) -> Unit,
    ) {
        slider(parent, label, current, minimum, maximum, 1, defaultValue, change)
    }

    fun slider(
        parent: LinearLayout,
        label: String,
        current: Int,
        minimum: Int,
        maximum: Int,
        step: Int,
        defaultValue: Int,
        change: (Int) -> Unit,
        valueFormatter: (Int) -> String = { it.toString() },
    ) {
        val section = host.settingsSectionKey(parent)
        val box = host.ui.vertical()
        box.setPadding(host.ui.dp(16f), host.ui.dp(12f), host.ui.dp(16f), 0)
        val heading = host.ui.horizontal()
        heading.addView(host.ui.text(label, 15f, host.ui.palette.text), LinearLayout.LayoutParams(0, -2, 1f))
        val value = host.ui.text(valueFormatter(current), 13f, host.ui.palette.muted)
        value.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        value.gravity = Gravity.END
        value.minWidth = host.ui.dp(40f)
        heading.addView(value)
        box.addView(heading)
        val bar = SeekBar(host.activity)
        bar.max = (maximum - minimum) / step
        bar.progress = Math.max(0, (current - minimum) / step)
        bar.progressTintList = android.content.res.ColorStateList.valueOf(host.ui.palette.accent)
        bar.thumbTintList = android.content.res.ColorStateList.valueOf(host.ui.palette.accent)
        bar.contentDescription = label
        AppDialogBuilder.styleSlider(bar)
        installSliderAccessibility(bar, label, minimum, maximum, step, valueFormatter)
        val adjustment = host.ui.horizontal()
        adjustment.addView(bar, LinearLayout.LayoutParams(0, host.ui.dp(48f), 1f))
        val reset =
            host.ui.icon(
                AppIcons.Kind.RESET,
                host.ui.tr(R.string.reset_setting, label),
            )
        reset.tag = "slider-reset:" + label
        reset.isEnabled = current != defaultValue
        reset.alpha = if (current == defaultValue) 0.35f else 1f
        adjustment.addView(reset, LinearLayout.LayoutParams(host.ui.dp(48f), host.ui.dp(48f)))
        box.addView(adjustment)
        bar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                    value.text = valueFormatter(progress * step + minimum)
                    if (Build.VERSION.SDK_INT >= 30) seek.stateDescription = value.text
                    if (fromUser) {
                        val selected = progress * step + minimum
                        change(selected)
                        host.refreshSectionSummary(section)
                        reset.isEnabled = selected != defaultValue
                        reset.alpha = if (selected == defaultValue) 0.35f else 1f
                    }
                }

                override fun onStartTrackingTouch(seek: SeekBar) = host.preview.beginAdjustment()

                override fun onStopTrackingTouch(seek: SeekBar) = host.preview.finishAdjustment()
            },
        )
        reset.setOnClickListener(
            { v ->
                bar.progress = (defaultValue - minimum) / step
                value.text = valueFormatter(defaultValue)
                change(defaultValue)
                host.refreshSectionSummary(section)
                reset.isEnabled = false
                reset.alpha = 0.35f
            },
        )
        parent.addView(box)
    }

    private fun installSliderAccessibility(
        bar: SeekBar,
        label: String,
        minimum: Int,
        maximum: Int,
        step: Int,
        valueFormatter: (Int) -> String,
    ) {
        if (Build.VERSION.SDK_INT >= 30)
            bar.stateDescription = valueFormatter(bar.progress * step + minimum)
        bar.accessibilityDelegate = object : View.AccessibilityDelegate() {
            @Suppress("DEPRECATION")
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: android.view.accessibility.AccessibilityNodeInfo,
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                val current = bar.progress * step + minimum
                info.rangeInfo =
                    if (Build.VERSION.SDK_INT >= 30)
                        android.view.accessibility.AccessibilityNodeInfo.RangeInfo(
                            android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                            minimum.toFloat(), maximum.toFloat(), current.toFloat(),
                        )
                    else android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(
                        android.view.accessibility.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,
                        minimum.toFloat(), maximum.toFloat(), current.toFloat(),
                    )
                if (Build.VERSION.SDK_INT >= 30) info.stateDescription = valueFormatter(current)
                else info.contentDescription = "$label, ${valueFormatter(current)}"
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (!host.isEnabled) return false
                val index = when (action) {
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id -> {
                        val requested = args?.getFloat(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                            Float.NaN,
                        ) ?: return false
                        if (!requested.isFinite()) return false
                        ((requested.coerceIn(minimum.toFloat(), maximum.toFloat()).toDouble() - minimum) / step)
                            .roundToInt().coerceIn(0, bar.max)
                    }
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ->
                        (bar.progress + 1).coerceAtMost(bar.max)
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD ->
                        (bar.progress - 1).coerceAtLeast(0)
                    else -> return super.performAccessibilityAction(host, action, args)
                }
                val mapped = Bundle().apply {
                    putFloat(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,
                        index.toFloat(),
                    )
                }
                return super.performAccessibilityAction(
                    host,
                    android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                    mapped,
                )
            }
        }
    }

    fun fontSummary(): String {
        val choice = FONT_CHOICES.firstOrNull { it.family == host.session.settings.fontFamily }
            ?: FONT_CHOICES.first()
        return host.ui.tr(choice.summaryResource)
    }

    private class FontChoice(
        val family: String,
        val labelResource: Int,
        val summaryResource: Int = labelResource,
    )

    companion object {
        private val FONT_CHOICES =
            arrayOf(
                FontChoice("sans-serif", R.string.system_font),
                FontChoice("sans-serif-light", R.string.light, R.string.light_font),
                FontChoice("sans-serif-medium", R.string.medium, R.string.medium_font),
                FontChoice("sans-serif-bold", R.string.bold, R.string.bold_font),
                FontChoice("monospace", R.string.monospace),
                FontChoice("serif", R.string.serif),
            )

    }
}

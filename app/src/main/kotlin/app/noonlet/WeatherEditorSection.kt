package app.noonlet

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

internal class WeatherEditorSection(
    private val host: EditorHost,
    private val controls: EditorControls,
    private val rootProvider: () -> View?,
    private val foregroundProvider: () -> WeatherLocation.ForegroundSession?,
    private val unitChanged: (TemperatureUnit) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())

    fun summary(): String =
        if (host.session.settings.weatherEnabled) weatherRecovery(weatherState())?.let { host.ui.tr(it.message) }
            ?: weatherLocationLabel().substringBefore(",")
        else host.ui.tr(R.string.weather_off)

    fun onPermissionResult(requestCode: Int) {
        if (requestCode != WEATHER_LOCATION_PERMISSION) return
        updateStatus()
        refreshWeather()
        WeatherRefreshScheduler.reconcile(host.activity)
    }

    fun buildControls(content: LinearLayout) {
        val weather = host.section(content, EditorSection.WEATHER)
        controls.toggle(weather, host.ui.tr(R.string.show_weather), host.session.settings.weatherEnabled) { enabled ->
            if (enabled) chooseWeatherLocation(enableOnSelection = true)
            else {
                host.session.settings.weatherEnabled = false
                host.refreshSettingsSections(EditorSection.WEATHER)
                WeatherRefreshScheduler.reconcile(host.activity)
                updateStatus()
            }
        }
        if (!host.session.settings.weatherEnabled) return
        val recovery = weatherRecoveryRow()
        weather.addView(recovery)
        updateWeatherRecovery(recovery, weatherState())
        host.ui.divider(weather)
        weather.addView(weatherRefreshAction(), LinearLayout.LayoutParams(-1, -2))
        host.ui.divider(weather)
        controls.row(
            weather,
            host.ui.tr(R.string.weather_location),
            weatherLocationLabel(),
            { chooseWeatherLocation() },
        )
        if (host.session.settings.weatherUseDevice) {
            val mode = host.ui.text(host.ui.tr(R.string.weather_device_location_detail, host.activity.getString(R.string.app_name)), 12f, host.ui.palette.muted)
            mode.tag = "weather-location-mode"
            mode.setPadding(host.ui.dp(16f), 0, host.ui.dp(16f), host.ui.dp(12f))
            weather.addView(mode)
        }
        host.ui.divider(weather)
        controls.row(weather, host.ui.tr(R.string.temperature_units),
            temperatureUnitName(AppPreferences(host.activity).temperatureUnit()),
            AppIcons.Kind.CHEVRON_RIGHT, ::chooseTemperatureUnit, "temperature-unit:value")
        weather.getChildAt(weather.childCount - 1).tag = "temperature-unit"
        weatherAppearanceControls(weather)
    }

    private fun weatherAppearanceControls(parent: LinearLayout) {
        val settings = host.session.settings
        val defaults = WidgetSettings.defaults(false)
        host.ui.divider(parent)
        val formats = WeatherTextFormat.entries
        val labels = arrayOf(host.ui.tr(R.string.weather_condition_temperature),
            host.ui.tr(R.string.weather_temperature_condition), host.ui.tr(R.string.weather_temperature_only))
        controls.row(parent, host.ui.tr(R.string.weather_text_format), labels[formats.indexOf(settings.weatherTextFormat).coerceAtLeast(0)]) {
            AppDialogBuilder(host.activity).setTitle(host.ui.tr(R.string.weather_text_format))
                .setSingleChoiceItems(labels, formats.indexOf(settings.weatherTextFormat)) { dialog, index ->
                    settings.weatherTextFormat = formats[index]
                    dialog.dismiss()
                    host.refreshSettingsSections(EditorSection.WEATHER)
                }.show()
        }
        host.ui.divider(parent)
        controls.colorRow(parent, host.ui.tr(R.string.weather_text_color), settings.weatherColor) { settings.weatherColor = it }
        host.ui.divider(parent)
        controls.fontRow(parent, weather = true)
        fun control(label: Int, value: Int, minimum: Int, maximum: Int, default: Int, change: (Int) -> Unit) {
            host.ui.divider(parent)
            controls.slider(parent, host.ui.tr(label), value, minimum, maximum, default) {
                change(it)
                host.preview.queueFull()
            }
        }
        host.ui.divider(parent)
        controls.slider(parent, host.ui.tr(R.string.font_weight), settings.weatherFontWeight, 100, 900, 50,
            defaults.weatherFontWeight, change = {
            settings.weatherFontWeight = it
            host.preview.queueFull()
        })
        controls.fontWeightHint(parent, settings.weatherFontFamily)
        control(R.string.weather_text_size, settings.weatherTextSize, 6, 35, defaults.weatherTextSize) { settings.weatherTextSize = it }
        control(R.string.weather_icon_size, settings.weatherIconSize, 16, 80, defaults.weatherIconSize) { settings.weatherIconSize = it }
        control(R.string.weather_icon_gap, settings.weatherIconGap, 0, 24, defaults.weatherIconGap) { settings.weatherIconGap = it }
        control(R.string.weather_horizontal_offset, settings.weatherOffsetX, -40, 40, defaults.weatherOffsetX) { settings.weatherOffsetX = it }
        control(R.string.weather_vertical_offset, settings.weatherOffsetY, -40, 40, defaults.weatherOffsetY) { settings.weatherOffsetY = it }
    }

    private fun chooseTemperatureUnit() {
        val preferences = AppPreferences(host.activity)
        val units = TemperatureUnit.entries
        AppDialogBuilder(host.activity)
            .setTitle(host.ui.tr(R.string.temperature_units))
            .setSingleChoiceItems(units.map(::temperatureUnitName).toTypedArray(),
                units.indexOf(preferences.temperatureUnit())) { dialog, index ->
                dialog.dismiss()
                if (preferences.setTemperatureUnit(units[index])) {
                    unitChanged(units[index])
                    WeatherRefreshScheduler.updateWidgets(host.activity.applicationContext)
                    host.refreshSettingsSections(EditorSection.WEATHER)
                }
            }.show()
    }

    private fun temperatureUnitName(unit: TemperatureUnit): String = host.ui.tr(when (unit) {
        TemperatureUnit.CELSIUS -> R.string.temperature_celsius
        TemperatureUnit.FAHRENHEIT -> R.string.temperature_fahrenheit
    })

    private fun weatherRecoveryRow(): LinearLayout = host.ui.horizontal().apply {
        tag = "weather-recovery"
        minimumHeight = host.ui.dp(56f)
        setPadding(host.ui.dp(16f), host.ui.dp(10f), host.ui.dp(16f), host.ui.dp(10f))
        background = AppDialogBuilder.ripple(host.activity, Color.TRANSPARENT, 12, false)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(host.ui.text("", 15f, host.ui.palette.text).apply {
            tag = "weather-recovery-reason"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(host.activity).apply {
            tag = "weather-recovery-chevron"
            setImageResource(R.drawable.ic_chevron_right)
            setColorFilter(host.ui.palette.muted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(host.ui.dp(24f), host.ui.dp(24f)).apply { marginStart = host.ui.dp(8f) })
        accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: android.view.accessibility.AccessibilityNodeInfo,
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = if (host.isClickable) "android.widget.Button" else "android.widget.TextView"
            }
        }
        setOnClickListener {
            if (!it.isShown || !it.isEnabled || !it.isClickable) return@setOnClickListener
            val state = weatherState()
            if (state.status != WeatherStatus.LOADING)
                weatherRecovery(state)?.let(::performWeatherRecovery)
        }
    }

    private fun chooseWeatherLocation(enableOnSelection: Boolean = false) {
        val settings = host.session.settings
        WeatherLocationDialog.show(
            host.activity,
            host.ui.locale(),
            settings.weatherEnabled && settings.weatherUseDevice,
            WeatherPlace.decode(settings.weatherPlace),
            onCancel = {
                if (enableOnSelection && !host.activity.isDestroyed &&
                    host.session.settings === settings)
                    host.refreshSettingsSections(EditorSection.WEATHER)
            },
        ) { place ->
            if (host.activity.isDestroyed || host.session.settings !== settings) return@show
            if (enableOnSelection) settings.weatherEnabled = true
            settings.weatherUseDevice = place == null
            settings.weatherPlace = place?.encode().orEmpty()
            host.refreshSettingsSections(EditorSection.WEATHER)
            WeatherRefreshScheduler.reconcile(host.activity)
            requestWeatherLocation()
        }
    }

    private fun weatherLocationLabel(): String =
        if (host.session.settings.weatherUseDevice) host.ui.tr(R.string.weather_device_location)
        else
            WeatherPlace.decode(host.session.settings.weatherPlace)?.name
                ?: host.ui.tr(R.string.weather_choose_location)

    private fun weatherState(): WeatherState =
        if (host.session.settings.weatherEnabled)
            WeatherRepository.state(host.activity, WeatherRequest.capture(host.session.settings))
        else WeatherState(status = WeatherStatus.UNAVAILABLE)

    private fun weatherRecovery(state: WeatherState): WeatherRecovery? {
        if (!host.session.settings.weatherEnabled) return null
        if (!host.session.settings.weatherUseDevice && WeatherRequest.capture(host.session.settings).place == null)
            return WeatherRecovery.CHOOSE_LOCATION
        return when (state.status) {
            WeatherStatus.PERMISSION_REQUIRED -> WeatherRecovery.PERMISSION
            WeatherStatus.LOCATION_DISABLED -> WeatherRecovery.LOCATION
            else -> when (state.failureStatus) {
                WeatherStatus.PERMISSION_REQUIRED -> WeatherRecovery.PERMISSION
                WeatherStatus.LOCATION_DISABLED -> WeatherRecovery.LOCATION
                WeatherStatus.UNAVAILABLE -> WeatherRecovery.UPDATE_FAILED
                else -> null
            }
        }
    }

    private fun updateWeatherRecovery(row: ViewGroup, state: WeatherState) {
        val recovery = weatherRecovery(state)
        row.visibility = if (recovery == null) View.GONE else View.VISIBLE
        if (recovery == null) return
        val reason = row.findViewWithTag<TextView>("weather-recovery-reason")
        val message = host.ui.tr(recovery.message)
        if (reason.text.toString() != message) reason.text = message
        val actionResource = recovery.action
        val actionable = actionResource != null
        row.isClickable = actionable
        row.isFocusable = actionable
        row.isEnabled = !actionable || state.status != WeatherStatus.LOADING
        row.contentDescription = if (actionResource != null) "$message, ${host.ui.tr(actionResource)}" else message
        row.findViewWithTag<View>("weather-recovery-chevron").visibility =
            if (actionable) View.VISIBLE else View.GONE
    }

    private fun performWeatherRecovery(recovery: WeatherRecovery) {
        when (recovery) {
            WeatherRecovery.PERMISSION -> showWeatherPermissionSettings()
            WeatherRecovery.LOCATION -> openWeatherSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            WeatherRecovery.CHOOSE_LOCATION -> chooseWeatherLocation()
            WeatherRecovery.UPDATE_FAILED -> Unit
        }
    }

    private fun weatherRefreshAction(): View {
        val interval = host.ui.tr(R.string.weather_refresh_interval, WeatherSnapshot.REFRESH_INTERVAL / 60_000L)
        val action =
            host.ui.horizontal().apply {
                tag = "weather-refresh"
                isFocusable = true
                minimumHeight = host.ui.dp(68f)
                setPadding(host.ui.dp(16f), host.ui.dp(10f), host.ui.dp(16f), host.ui.dp(10f))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                background =
                    AppDialogBuilder.ripple(host.activity, Color.TRANSPARENT, 12, false)
                val labels = host.ui.vertical().apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    addView(host.ui.text(host.ui.tr(R.string.weather_refresh), 15f, host.ui.palette.text).apply {
                        tag = "weather-refresh-label"
                    })
                    addView(host.ui.text(interval, 13f, host.ui.palette.muted).apply {
                        tag = "weather-refresh-interval"
                        setPadding(0, host.ui.dp(4f), 0, 0)
                    })
                }
                addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                val status = FrameLayout(host.activity).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
                val icon =
                    ImageView(host.activity).apply {
                        tag = "weather-refresh-icon"
                        setImageResource(R.drawable.ic_refresh)
                        setColorFilter(host.ui.palette.accent)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                status.addView(icon, FrameLayout.LayoutParams(host.ui.dp(24f), host.ui.dp(24f), Gravity.CENTER))
                val progress =
                    android.widget
                        .ProgressBar(host.activity, null, android.R.attr.progressBarStyleSmall)
                        .apply {
                            tag = "weather-refresh-progress"
                            indeterminateTintList =
                                android.content.res.ColorStateList.valueOf(host.ui.palette.accent)
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }
                status.addView(progress, FrameLayout.LayoutParams(host.ui.dp(24f), host.ui.dp(24f), Gravity.CENTER))
                addView(status, LinearLayout.LayoutParams(host.ui.dp(24f), host.ui.dp(24f)).apply {
                    marginStart = host.ui.dp(12f)
                })
                accessibilityDelegate =
                    object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(
                            host: View,
                            info: android.view.accessibility.AccessibilityNodeInfo,
                        ) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = "android.widget.Button"
                        }
                    }
                setOnClickListener { if (it.isEnabled) refreshWeather(forceRefresh = true) }
            }
        updateWeatherRefresh(action, weatherState())
        return action
    }

    fun updateStatus() {
        val root = rootProvider() ?: return
        if (host.session.world) return
        val state = weatherState()
        root.findViewWithTag<View>("weather-refresh")?.let { updateWeatherRefresh(it, state) }
        root.findViewWithTag<ViewGroup>("weather-recovery")?.let {
            updateWeatherRecovery(it, state)
        }
        host.refreshSectionSummary(EditorSection.WEATHER)
    }

    private fun updateWeatherRefresh(action: View, state: WeatherState) {
        val loading = host.session.settings.weatherEnabled && state.status == WeatherStatus.LOADING
        val recovery = weatherRecovery(state)
        action.visibility = if (host.session.settings.weatherEnabled) View.VISIBLE else View.GONE
        action.isEnabled = !loading && (recovery == null || recovery == WeatherRecovery.UPDATE_FAILED)
        val label = host.ui.tr(if (loading) R.string.weather_loading else R.string.weather_refresh)
        action.findViewWithTag<TextView>("weather-refresh-label")
            .setTextColor(if (action.isEnabled || loading) host.ui.palette.text else host.ui.palette.muted)
        val interval = action.findViewWithTag<TextView>("weather-refresh-interval").text
        action.contentDescription = "$label, $interval"
        action.findViewWithTag<View>("weather-refresh-icon").apply {
            visibility = if (loading) View.GONE else View.VISIBLE
            alpha = if (action.isEnabled) 1f else .45f
        }
        action.findViewWithTag<View>("weather-refresh-progress").visibility =
            if (loading) View.VISIBLE else View.GONE
    }

    fun refreshIfActive() {
        if (!host.preview.active || host.activity.isDestroyed || host.session.world || !host.session.settings.weatherEnabled) return
        updateStatus()
        refreshWeather()
    }

    private fun refreshWeather(forceRefresh: Boolean = false) {
        if (host.session.world || !host.session.settings.weatherEnabled) return
        val app = host.activity.applicationContext
        val requested = WeatherRequest.capture(host.session.settings)
        val target = host.session.target
        val foreground = foregroundProvider()
        val before = WeatherRepository.state(app, requested)
        WeatherRepository.refresh(app, requested, foreground = foreground, forceRefresh = forceRefresh).whenComplete {
            after,
            error ->
            if (error == null && after != before)
                WeatherRefreshScheduler.updateWidgets(app).exceptionally { null }
            if (
                forceRefresh &&
                    (error != null || after?.failureStatus != null)
            )
                handler.post {
                    if (
                        !host.activity.isDestroyed &&
                            host.preview.active &&
                            foreground != null && foreground.isActive &&
                            foregroundProvider() === foreground &&
                            host.session.target == target &&
                            host.session.settings.weatherEnabled &&
                            WeatherRequest.capture(host.session.settings) == requested
                    )
                        Toast.makeText(host.activity, host.ui.tr(R.string.weather_unavailable), Toast.LENGTH_SHORT)
                            .show()
                }
        }
    }

    private fun requestWeatherLocation() {
        if (host.session.world || !host.session.settings.weatherEnabled) return
        if (host.session.settings.weatherUseDevice && !WeatherLocation.hasForegroundPermission(host.activity))
            AppDialogBuilder(host.activity)
                .setTitle(host.ui.tr(R.string.weather_location))
                .setMessage(host.ui.tr(R.string.weather_permission_explanation))
                .setNegativeButton(host.ui.tr(R.string.cancel), null)
                .setPositiveButton(host.ui.tr(R.string.weather_allow_location)) { _, _ ->
                    host.activity.requestPermissions(
                        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                        WEATHER_LOCATION_PERMISSION,
                    )
                }
                .show()
        else refreshWeather()
    }

    private fun showWeatherPermissionSettings() {
        AppDialogBuilder(host.activity)
            .setTitle(host.ui.tr(R.string.weather_location))
            .setMessage(host.ui.tr(R.string.weather_permission_explanation))
            .setNegativeButton(host.ui.tr(R.string.cancel), null)
            .setPositiveButton(host.ui.tr(R.string.weather_open_settings)) { _, _ -> openAppSettings() }
            .show()
    }

    private fun openAppSettings() {
        openWeatherSettings(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${host.activity.packageName}")),
        )
    }

    private fun openWeatherSettings(intent: Intent) {
        SettingsNavigation.open(host.activity, intent)
    }

    private enum class WeatherRecovery(val message: Int, val action: Int? = null) {
        PERMISSION(R.string.weather_permission_required, R.string.weather_open_settings),
        LOCATION(R.string.weather_location_disabled, R.string.weather_open_settings),
        CHOOSE_LOCATION(R.string.weather_choose_location, R.string.weather_location),
        UPDATE_FAILED(R.string.weather_update_failed),
    }

    companion object {
        private const val WEATHER_LOCATION_PERMISSION = 101
    }
}

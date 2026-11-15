package app.noonlet

import android.app.Activity
import android.app.AlarmManager
import android.app.Dialog
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import java.util.Locale

internal class MainActivity : Activity(), EditorDialogOwner, EditorHost {
    override val activity: Activity get() = this
    override val ui = EditorUi(this, { palette }, { uiLocale })
    override val placePicker = PlacePickerDialog(this, ui)
    override val preview = EditorPreview(
        this, ui,
        sessionProvider = { if (::session.isInitialized) session else null },
        applySettings = ::applyChangedSettings,
        controlsStale = { editorControlsStale },
        rebuildEditor = ::render,
    )
    private val controls = EditorControls(this)
    private val clockSections = ClockEditorSections(this, controls)
    private val worldSections = WorldEditorSections(this, controls)
    private val weather = WeatherEditorSection(
        this, controls,
        rootProvider = { if (::root.isInitialized) root else null },
        foregroundProvider = { weatherForeground },
        unitChanged = { renderedTemperatureUnit = it },
    )
    override lateinit var session: EditorSession
        private set
    private var editorControlsStale: Boolean = false
    private var renderedMissingTarget: Boolean = false
    private val editorDialogs = mutableListOf<Dialog>()
    private var editorConfiguration: Configuration? = null
    private lateinit var palette: AppPalette
    private var renderedTemperatureUnit: TemperatureUnit? = null
    private var settingsErasureRevision: Long? = null
    private var settingsHadLocationPermission = false
    private var renderedImportRevision = 0L
    private var returnToClock = false
    private val BG get() = palette.background
    private val CARD get() = palette.surface
    private val TEXT get() = palette.text
    private val MUTED get() = palette.muted
    private val ACCENT get() = palette.accent
    private lateinit var root: LinearLayout
    private var contentScroll: ScrollView? = null
    private var restoredScroll = -1
    private var knownPlacedSignature: String? = null
    private var editorAppLocaleTag: String? = null
    private var editorResourceLocaleTags: String? = null
    private var uiLocale: Locale = Locale.ENGLISH
    private var uiLocaleTag: String = "en"
    private val sections = EditorSectionViews(this, ::sectionSummary, ::buildSettingsSection)
    private val handler = Handler(Looper.getMainLooper())
    private var pinRefreshQueued: Boolean = false
    private val pinRequest = WidgetPinRequest()
    private var weatherForeground: WeatherLocation.ForegroundSession? = null
    private var topResumed: Boolean = false
    private var alarmReceiverRegistered = false
    private var deviceAlarmObserver: AutoCloseable? = null
    private val alarmChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED ||
                !preview.active || isDestroyed) return
            controls.refreshAlarmSamples()
            if (!session.world && session.settings.showAlarm) preview.refresh(false)
        }
    }
    private val pinRefresh = Runnable {
        pinRefreshQueued = false
        if (!preview.active || isDestroyed) return@Runnable
        if (finishCompletedPin()) return@Runnable
        applyChangedSettings()
        val previousPreview = preview.stage
        synchronizeHomeEditor()
        if (uiLanguageChanged()) {
            dismissEditorDialogs()
            render()
        }
        if (preview.stage === previousPreview) preview.refresh()
    }
    private val pinChanged = Runnable {
        if (!preview.active || pinRefreshQueued) return@Runnable
        pinRefreshQueued = true
        handler.post(pinRefresh)
    }
    private val weatherChanged = Runnable {
        if (
            preview.active &&
                !isDestroyed &&
                ::session.isInitialized &&
                !session.world &&
                session.settings.weatherEnabled
        ) {
            weather.updateStatus()
            preview.refresh(false)
        }
    }

    override fun onCreate(state: Bundle?) {
        AppTheme.applyToWindow(this)
        super.onCreate(state)
        palette = AppTheme.palette(this)
        renderedTemperatureUnit = AppPreferences(this).temperatureUnit()
        settingsErasureRevision = if (state?.containsKey("settings_erasure_revision") == true)
            state.getLong("settings_erasure_revision") else null
        settingsHadLocationPermission = state?.getBoolean("settings_location_permission", false) ?: false
        returnToClock = state?.getBoolean("return_to_clock", false) ?: false
        AppLocales.refreshLanguage(this)
        WidgetSettingsStore.delete(this, EditorPreview.PREVIEW_CLOCK, false)
        WidgetSettingsStore.delete(this, EditorPreview.PREVIEW_WORLD, true)
        session = EditorSession(this, intent, state)
        renderedImportRevision = state?.getLong("rendered_import_revision", 0)
            ?: session.preferences.importRevision()
        if (session.widgetId > 0) setResult(RESULT_CANCELED)
        preview.restoreSavedSizes()
        if (state != null) {
            restoredScroll = state.getInt("editor_scroll", 0)
            sections.restore(state)
            if (renderedImportRevision == session.preferences.importRevision()) preview.restoreState(state)
        }
        render()
        placePicker.prepare()
        if (intent.getBooleanExtra(TapActions.ALARM_UNAVAILABLE, false)) {
            Toast.makeText(this, ui.tr(R.string.alarm_unavailable), Toast.LENGTH_LONG).show()
            intent.removeExtra(TapActions.ALARM_UNAVAILABLE)
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        session.saveState(out, ::handleApplyResult)
        super.onSaveInstanceState(out)
        preview.saveState(out)
        out.putInt("editor_scroll", contentScroll?.scrollY ?: 0)
        sections.save(out)
        settingsErasureRevision?.let { out.putLong("settings_erasure_revision", it) }
        out.putBoolean("settings_location_permission", settingsHadLocationPermission)
        out.putLong("rendered_import_revision", renderedImportRevision)
        out.putBoolean("return_to_clock", returnToClock)
    }

    override fun onResume() {
        super.onResume()
        session.withBindings { resumeEditor() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && session.widgetId == 0 && !session.creatingNew) {
            setIntent(intent)
            switchTab(false)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isFinishing && session.widgetId == 0 && !session.creatingNew && !pinRequest.isPending)
            returnToClock = true
    }

    private fun resumeEditor() {
        if (finishCompletedPin()) return
        val importRevision = session.preferences.importRevision()
        val imported = renderedImportRevision != importRevision
        if (imported) {
            session.reloadImportedSettings()
            preview.restoreSavedSizes()
            renderedImportRevision = importRevision
        }
        val tabChanged = returnToClock && session.switchTab(false, ::handleApplyResult)
        returnToClock = false
        if (tabChanged) {
            dismissEditorDialogs()
            contentScroll = null
        }
        val nextPalette = AppTheme.palette(this)
        val themeChanged = palette != nextPalette
        palette = nextPalette
        AppTheme.applyToWindow(this)
        val nextUnit = AppPreferences(this).temperatureUnit()
        val unitChanged = renderedTemperatureUnit != nextUnit
        renderedTemperatureUnit = nextUnit
        AppLocales.refreshLanguage(this)
        val skipWeatherRefresh = settingsErasureRevision?.let {
            val erased = it != WeatherRefreshScheduler.erasureRevision(this)
            val permissionGranted = !settingsHadLocationPermission && WeatherLocation.hasForegroundPermission(this)
            erased || (!permissionGranted && !imported)
        } ?: false
        settingsErasureRevision = null
        preview.activate()
        if (!alarmReceiverRegistered) {
            val filter = IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(alarmChanged, filter, RECEIVER_NOT_EXPORTED)
            else registerReceiver(alarmChanged, filter)
            alarmReceiverRegistered = true
            deviceAlarmObserver = SystemAlarmClock.observeDevice(this) {
                if (preview.active && !isDestroyed) {
                    controls.refreshAlarmSamples()
                    if (!session.world && session.settings.showAlarm) preview.refresh(false)
                }
            }
        }
        controls.refreshAlarmSamples()
        AlarmRefreshScheduler.reconcile(this)
        if (weatherForeground == null) weatherForeground = WeatherLocation.openForeground(this)
        WidgetPinReceiver.observe(pinChanged)
        WeatherRepository.observe(weatherChanged)
        val previousPreview = preview.stage
        val phoneLocale = AppLocales.phoneLocale(this)
        val appLocaleTag = AppLocales.appLocale(this, phoneLocale).toLanguageTag()
        val appLocaleChanged = !appLocaleTag.equals(editorAppLocaleTag)
        val phoneChanged = !phoneLocale.toLanguageTag().equals(preview.phoneLocaleTag)
        val resourcesChanged = reconcileEditorLocale(appLocaleTag, phoneChanged)
        synchronizeHomeEditor()
        if (uiLanguageChanged(phoneLocale) || themeChanged || tabChanged || imported) {
            dismissEditorDialogs()
            render()
        }
        if ((resourcesChanged || appLocaleChanged) && preview.stage === previousPreview)
            refreshCustomActionLabels()
        if (unitChanged && preview.stage === previousPreview) replaceSettingsSections(EditorSection.WEATHER)
        val now = preview.clock.millis()
        val period = (if (preview.seconds) 1000 else 60000).toLong()
        if (
            (unitChanged || (preview.stage === previousPreview && preview.wasPaused) ||
                !phoneLocale.toLanguageTag().equals(preview.phoneLocaleTag) ||
                alarmPreviewLocaleChanged(appLocaleTag) ||
                Math.floorDiv(preview.renderedAt, period) != Math.floorDiv(now, period))
        )
            preview.refresh(true, phoneLocale)
        else preview.scheduleTick(now)
        preview.markResumed()
        if (!skipWeatherRefresh) {
            weatherForeground?.let { foreground ->
                WeatherRefreshScheduler.refreshPlacedWidgets(applicationContext, foreground)
                    .exceptionally { null }
            }
            weather.refreshIfActive()
        } else {
            weather.updateStatus()
            preview.refresh(false)
        }
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        val changed = editorConfiguration?.diff(configuration) ?: 0
        val presentation = android.content.pm.ActivityInfo.CONFIG_LOCALE or
            android.content.pm.ActivityInfo.CONFIG_LAYOUT_DIRECTION or
            android.content.pm.ActivityInfo.CONFIG_UI_MODE
        // Resource-only callbacks can also change labels supplied by other packages.
        if (changed == 0 || changed and presentation != 0) dismissEditorDialogs()
        editorConfiguration = Configuration(configuration)
        super.onConfigurationChanged(configuration)
        AppLocales.refreshLanguage(this)
        if (!preview.active || !::root.isInitialized) return
        val phoneLocale = AppLocales.phoneLocale(this)
        val appLocaleTag = AppLocales.appLocale(this, phoneLocale).toLanguageTag()
        val phoneChanged = !phoneLocale.toLanguageTag().equals(preview.phoneLocaleTag)
        reconcileEditorLocale(appLocaleTag, phoneChanged)
        if (uiLanguageChanged(phoneLocale)) {
            dismissEditorDialogs()
            render()
        } else {
            // Foreign packages can select different labels even when this app's locale list is
            // promoted unchanged.
            refreshCustomActionLabels()
            if (phoneChanged || alarmPreviewLocaleChanged(appLocaleTag))
                preview.refresh(true, phoneLocale)
        }
    }

    @android.annotation.TargetApi(29)
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        topResumed = isTopResumedActivity
        if (topResumed && preview.active) finishCompletedPin()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        pinRequest.onWindowFocusChanged(hasFocus)
        if (hasFocus && preview.active && ::session.isInitialized) {
            if (finishCompletedPin(windowFocused = true)) return
        }
    }

    private fun synchronizeHomeEditor() {
        if (session.widgetId == 0) {
            val transition = session.reconcile(::handleApplyResult)
            if (transition != EditorSession.Transition.UNCHANGED) dismissEditorDialogs()
            if (transition == EditorSession.Transition.SELECTED_TARGET) contentScroll = null
            if (
                transition != EditorSession.Transition.UNCHANGED ||
                    session.placedSignature() != knownPlacedSignature
            )
                render()
        } else if (renderedMissingTarget != session.missingTarget()) render()
    }

    override fun onPause() {
        stopObservingAlarm()
        weatherForeground?.close()
        weatherForeground = null
        applyChangedSettings()
        super.onPause()
        preview.pause()
        topResumed = false
        WidgetPinReceiver.unobserve(pinChanged)
        WeatherRepository.removeObserver(weatherChanged)
        handler.removeCallbacks(pinRefresh)
        pinRefreshQueued = false
        preview.cancelQueued()
    }

    override fun onDestroy() {
        stopObservingAlarm()
        weatherForeground?.close()
        weatherForeground = null
        preview.pause()
        WidgetPinReceiver.unobserve(pinChanged)
        WeatherRepository.removeObserver(weatherChanged)
        handler.removeCallbacks(pinRefresh)
        pinRefreshQueued = false
        preview.cancelQueued()
        dismissEditorDialogs()
        super.onDestroy()
    }

    private fun stopObservingAlarm() {
        deviceAlarmObserver?.close()
        deviceAlarmObserver = null
        if (alarmReceiverRegistered) {
            unregisterReceiver(alarmChanged)
            alarmReceiverRegistered = false
        }
    }

    private fun uiLanguageChanged(): Boolean {
        return uiLocaleTag != AppLocales.uiLanguage(AppLocales.resourceLocale(this))
    }

    private fun uiLanguageChanged(phoneLocale: Locale): Boolean {
        return uiLocaleTag != AppLocales.uiLanguage(AppLocales.resourceLocale(this, phoneLocale))
    }

    private fun reconcileEditorLocale(appLocaleTag: String, phoneChanged: Boolean): Boolean {
        val resources = resources.configuration.locales.toLanguageTags()
        val resourcesChanged = !resources.equals(editorResourceLocaleTags)
        if (phoneChanged || resourcesChanged || !appLocaleTag.equals(editorAppLocaleTag))
            dismissEditorDialogs()
        editorAppLocaleTag = appLocaleTag
        uiLocale = AppLocales.resourceLocale(this)
        editorResourceLocaleTags = resources
        return resourcesChanged
    }

    private fun refreshCustomActionLabels() {
        if (
            (TapActions.isCustomApp(session.settings.tapAction) ||
                (!session.world &&
                    ((TapActions.isCustomApp(session.settings.timeTapAction) ||
                        TapActions.isCustomApp(session.settings.dateTapAction) ||
                        TapActions.isCustomApp(session.settings.alarmTapAction)))))
        )
            replaceSettingsSections(EditorSection.ACTIONS)
    }

    private fun alarmPreviewLocaleChanged(appLocaleTag: String): Boolean {
        return !session.world &&
            session.settings.showAlarm &&
            !appLocaleTag.equals(preview.appLocaleTag)
    }

    private fun render() {
        session.withBindings { renderContent() }
    }

    private fun renderContent() {
        editorConfiguration = Configuration(resources.configuration)
        val appLocale = AppLocales.appLocale(this)
        uiLocale = AppLocales.resourceLocale(this)
        uiLocaleTag = AppLocales.uiLanguage(uiLocale)
        editorAppLocaleTag = appLocale.toLanguageTag()
        editorResourceLocaleTags = resources.configuration.locales.toLanguageTags()
        applyChangedSettings()
        preview.prepareForRender()
        val previousScroll =
            if (restoredScroll >= 0) restoredScroll else contentScroll?.scrollY ?: 0
        restoredScroll = -1
        sections.clear()
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.setOnApplyWindowInsetsListener(
            { view, insets ->
                AppInsets.apply(view, insets)
                insets
            },
        )
        setContentView(root)
        root.requestApplyInsets()

        val header = ui.horizontal()
        header.setPadding(ui.dp(20f), ui.dp(4f), ui.dp(16f), ui.dp(4f))
        val brand =
            ui.text(
                if (session.creatingNew) ui.tr(if (session.world) R.string.new_world_clock else R.string.new_clock)
                else if (session.widgetId > 0) ui.tr(R.string.edit_widget)
                else getString(R.string.app_name),
                20f,
                TEXT,
            )
        brand.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        brand.setPadding(0, ui.dp(8f), 0, ui.dp(8f))
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        if (session.creatingNew || intent.getBooleanExtra(EditorIntents.FROM_WIDGETS, false)) {
            val back = ui.navigationIcon(R.drawable.ic_arrow_back, ui.tr(R.string.app_back))
            back.tag = "editor-back"
            back.setOnClickListener { finish() }
            header.addView(back, 0, LinearLayout.LayoutParams(ui.dp(48f), ui.dp(48f)))
        }
        if (session.widgetId == 0 && !session.creatingNew) {
            val widgets = ui.button(ui.tr(R.string.my_widgets), Color.TRANSPARENT, TEXT)
            widgets.tag = "my-widgets-trigger"
            widgets.textSize = 13f
            widgets.setPadding(ui.dp(8f), ui.dp(8f), ui.dp(8f), ui.dp(8f))
            widgets.setOnClickListener { startActivity(Intent(this, MyWidgetsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION)) }
            widgets.maxWidth = ui.dp(136f)
            header.addView(widgets, LinearLayout.LayoutParams(-2, -2))
        }
        val settings = ui.navigationIcon(R.drawable.ic_settings, ui.tr(R.string.app_settings))
        settings.tag = "settings-trigger"
        settings.setOnClickListener {
            settingsErasureRevision = WeatherRefreshScheduler.erasureRevision(this)
            settingsHadLocationPermission = WeatherLocation.hasForegroundPermission(this)
            startActivity(Intent(this, AppSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION))
        }
        header.addView(settings, LinearLayout.LayoutParams(ui.dp(48f), ui.dp(48f)))
        root.addView(header)

        val scroll = ScrollView(this)
        contentScroll = scroll
        scroll.isFillViewport = true
        scroll.clipToPadding = false
        scroll.isVerticalScrollBarEnabled = false
        val content = ui.vertical()
        content.setPadding(ui.dp(16f), 0, ui.dp(16f), ui.dp(4f))

        if (session.widgetId == 0 && !session.creatingNew) {
            val tabs = ui.horizontal()
            tabs.tag = "widget-type-tabs"
            tabs.minimumHeight = ui.dp(48f)
            tabs.setPadding(ui.dp(4f), ui.dp(4f), ui.dp(4f), ui.dp(4f))
            tabs.background = ui.shape(palette.controlSurface, 14f)
            ui.addSegment(tabs, ui.tr(R.string.clock_widget_name), !session.world, { switchTab(false) })
            ui.addSegment(tabs, ui.tr(R.string.world_widget_name), session.world, { switchTab(true) })
            content.addView(tabs, LinearLayout.LayoutParams(-1, -2))
            ui.space(content, 4)
        }
        content.addView(preview.createCard(), LinearLayout.LayoutParams(-1, -2))

        val pinPreview =
            (resources.configuration.screenHeightDp >= 720 &&
                resources.configuration.screenWidthDp < resources.configuration.screenHeightDp &&
                resources.configuration.fontScale <= 1.15f)
        if (pinPreview) root.addView(content, LinearLayout.LayoutParams(-1, -2))
        val controls = ui.vertical()
        controls.setPadding(ui.dp(16f), 0, ui.dp(16f), ui.dp(16f))
        if (pinPreview) scroll.addView(controls)
        else {
            val scrollBody = ui.vertical()
            scrollBody.addView(content, LinearLayout.LayoutParams(-1, -2))
            scrollBody.addView(controls, LinearLayout.LayoutParams(-1, -2))
            scroll.addView(scrollBody)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val missingTarget = session.missingTarget()
        renderedMissingTarget = missingTarget
        if (missingTarget) missingTargetNotice(controls)
        else {
            if (session.world) worldControls(controls) else clockControls(controls)
        }
        if (!missingTarget) {
            val adding =
                session.initialConfiguration || session.creatingNew ||
                    session.widgetId == 0 && session.placedIds(session.world).isEmpty()
            val footer = ui.vertical()
            footer.setPadding(ui.dp(16f), ui.dp(8f), ui.dp(16f), ui.dp(8f))
            val action =
                ui.button(
                    if (adding) ui.tr(R.string.add_to_home_screen) else ui.tr(R.string.save_changes),
                    ACCENT,
                    palette.onAccent,
                )
            action.tag = if (adding) "action-add-widget" else "action-save-changes"
            action.textSize = 16f
            action.minimumHeight = ui.dp(48f)
            action.setPadding(ui.dp(12f), ui.dp(8f), ui.dp(12f), ui.dp(8f))
            footer.addView(action, LinearLayout.LayoutParams(-1, -2))
            action.setOnClickListener {
                if (adding) addCurrentWidget() else saveCurrentChanges()
            }
            root.addView(footer)
        }
        knownPlacedSignature = session.placedSignature()
        editorControlsStale = false
        preview.refresh(false)
        scroll.post({ scroll.scrollTo(0, previousScroll) })
    }

    private fun switchTab(value: Boolean) {
        session.withBindings {
            if (!session.switchTab(value, ::handleApplyResult)) return@withBindings
            dismissEditorDialogs()
            contentScroll = null
            renderContent()
            weather.refreshIfActive()
        }
    }

    private fun selectHomeTarget(id: Int, kind: Boolean) {
        dismissEditorDialogs()
        session.selectTarget(id, kind, ::handleApplyResult)
        contentScroll = null
        render()
        weather.refreshIfActive()
    }

    private fun missingTargetNotice(parent: LinearLayout) {
        val notice = ui.card(parent)
        notice.tag = "removed-widget-notice"
        notice.setPadding(ui.dp(16f), ui.dp(16f), ui.dp(16f), ui.dp(16f))
        val title = ui.text(ui.tr(R.string.widget_removed), 16f, TEXT)
        notice.addView(title)
        val explanation =
            ui.text(
                ui.tr(R.string.widget_removed_help),
                13f,
                MUTED,
            )
        explanation.setPadding(0, ui.dp(8f), 0, 0)
        notice.addView(explanation)
        val available = session.placedIds(session.world)
        if (available.size == 0) {
            explanation.setText(
                ui.tr(R.string.widget_add_help),
            )
            return
        }
        ui.space(notice, 16)
        val recover =
            ui.button(
                if (available.size == 1) ui.tr(R.string.open_remaining_widget)
                else ui.tr(R.string.choose_another_widget),
                palette.secondary,
                TEXT,
            )
        recover.tag = "recover-widget-target"
        recover.textSize = 14f
        notice.addView(recover, LinearLayout.LayoutParams(-1, ui.dp(48f)))
        recover.setOnClickListener(
            { view ->
                val current = session.placedIds(session.world)
                if (current.size == 0) {
                    render()
                    return@setOnClickListener
                }
                if (current.size == 1) {
                    recoverWidgetTarget(current[0])
                    return@setOnClickListener
                }
                val labels =
                    Array(current.size) { index ->
                        ui.tr(
                            if (session.world) R.string.world_clock_number
                            else R.string.clock_number,
                            index + 1,
                        )
                    }
                AppDialogBuilder(this)
                    .setTitle(ui.tr(R.string.choose_another_widget))
                    .setItems(labels, { dialog, index -> recoverWidgetTarget(current[index]) })
                    .show()
            },
        )
    }

    private fun recoverWidgetTarget(id: Int) {
        if (!session.targetExists(id, session.world)) {
            render()
            return
        }
        if (session.widgetId == 0) selectHomeTarget(id, session.world)
        else {
            startActivity(EditorIntents.edit(this, id, session.world).addFlags(Intent.FLAG_ACTIVITY_NO_USER_ACTION))
            finish()
        }
    }

    private fun clockControls(content: LinearLayout) {
        val defaults = WidgetSettings.defaults(false)
        for (key in CLOCK_SECTIONS) buildSettingsSection(content, key, defaults)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        weather.onPermissionResult(requestCode)
    }

    private fun worldControls(content: LinearLayout) {
        val defaults = WidgetSettings.defaults(true)
        for (key in WORLD_SECTIONS) buildSettingsSection(content, key, defaults)
    }

    private fun addCurrentWidget() {
        if (isFinishing || isDestroyed) return
        if (session.initialConfiguration && session.widgetId > 0) {
            val manager = AppWidgetManager.getInstance(this)
            val allocated = manager.getAppWidgetInfo(session.widgetId)
            val size = preview.requestedSize()
            val differentCells = Build.VERSION.SDK_INT >= 31 && allocated != null &&
                allocated.targetCellWidth > 0 && allocated.targetCellHeight > 0 &&
                (allocated.targetCellWidth != size.columns || allocated.targetCellHeight != size.rows)
            val differentSize = allocated != null && (differentCells || allocated.provider !=
                BaseWidgetProvider.component(this, session.world, size))
            if (differentSize && manager.isRequestPinAppWidgetSupported) {
                val handoff = EditorIntents.handoff(this, session.world, session.settings, size,
                    session.importedConfiguration?.token)
                WidgetSettingsStore.saveUnplaced(this, session.world, session.settings)
                setResult(RESULT_CANCELED)
                finish()
                overridePendingTransition(0, 0)
                startActivity(handoff)
                return
            }
            val target = session.configureInitial()
            if (target == null) {
                Toast.makeText(this, ui.tr(R.string.widget_removed_add_again), Toast.LENGTH_LONG)
                    .show()
                return
            }
            BaseWidgetProvider.updateWidget(this, target.id, target.world)
            if (!target.world) WeatherRefreshScheduler.reconcile(this)
            if (!target.world) AlarmRefreshScheduler.reconcile(this)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, target.id))
            if (differentSize)
                Toast.makeText(this, ui.tr(R.string.widget_resize_hint), Toast.LENGTH_LONG).show()
            finishAfterAdding()
            return
        }
        val transition = session.prepareAdd(::handleApplyResult)
        if (transition != EditorSession.Transition.UNCHANGED) {
            dismissEditorDialogs()
            if (transition == EditorSession.Transition.SELECTED_TARGET) contentScroll = null
            render()
            return
        }
        if (session.widgetId == 0 && (session.creatingNew || session.placedIds(session.world).isEmpty())) {
            requestSizedWidget()
        }
    }

    private fun requestSizedWidget() {
        if (!pinRequest.request {
            WidgetPinReceiver.request(this, session.world, session.settings,
                preview.pinSnapshot(), session.pinOwner, preview.requestedSize(), session.importedConfiguration?.token)
        })
            Toast.makeText(this,
                ui.tr(R.string.launcher_add_hint, getString(R.string.app_name)),
                Toast.LENGTH_LONG).show()
    }

    private fun saveCurrentChanges() {
        preview.cancelQueued()
        val id = session.target.id
        if (session.initialConfiguration || !session.targetExists(id, session.world)) {
            render()
            return
        }
        applyChangedSettings(forceHostUpdate = true)
        val reloaded = editorControlsStale
        preview.refresh(false)
        if (reloaded) return
        if (session.widgetId > 0)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        Toast.makeText(this, ui.tr(R.string.changes_saved), Toast.LENGTH_SHORT).show()
        finishToHome()
    }

    private fun finishCompletedPin(windowFocused: Boolean = hasWindowFocus()): Boolean {
        if (Build.VERSION.SDK_INT >= 29 && !topResumed && !windowFocused) return false
        if (!session.preferences.consumePinCompletion(session.pinOwner)) return false
        finishAfterAdding()
        return true
    }

    private fun finishAfterAdding() {
        if (isFinishing || isDestroyed) return
        if (callingActivity == null) {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val component = home.resolveActivity(packageManager)
            if (component != null && referrer?.scheme == "android-app" &&
                referrer?.authority == component.packageName) moveTaskToBack(true)
            else startActivity(
                if (component == null || component.packageName == "android")
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                else
                    Intent().setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
        finish()
    }

    private fun finishToHome() {
        if (isFinishing || isDestroyed) return
        if (callingActivity == null)
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        finish()
    }

    private fun applyChangedSettings(forceHostUpdate: Boolean = false) {
        if (::session.isInitialized) handleApplyResult(session.apply(forceHostUpdate))
    }

    private fun handleApplyResult(result: EditorSession.ApplyResult) {
        when (result) {
            is EditorSession.ApplyResult.HostUpdate -> {
                val target = result.target
                if (result.wrote && session.widgetId > 0)
                    setResult(
                        RESULT_OK,
                        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, target.id),
                    )
                BaseWidgetProvider.updateWidget(this, target.id, target.world)
                if (!target.world) AlarmRefreshScheduler.reconcile(this)
            }
            EditorSession.ApplyResult.ConflictReloaded -> {
                dismissEditorDialogs()
                editorControlsStale = true
            }
            else -> Unit
        }
    }

    override fun registerEditorDialog(dialog: Dialog) {
        editorDialogs.removeIf({ previous -> !previous.isShowing })
        editorDialogs.add(dialog)
    }

    private fun dismissEditorDialogs() {
        val previous = editorDialogs.toList()
        editorDialogs.clear()
        for (dialog in previous) if (dialog.isShowing) dialog.dismiss()
    }

    override fun resetAppearance() {
        session.resetAppearance()
        refreshSettingsSections(*sections.keys())
        WeatherRefreshScheduler.reconcile(this)
        Toast.makeText(
                this,
                ui.tr(R.string.appearance_reset),
                Toast.LENGTH_SHORT,
            )
            .show()
    }

    override fun section(parent: LinearLayout, key: EditorSection) = sections.create(parent, key)

    private fun sectionSummary(key: EditorSection): String =
        when (key) {
            EditorSection.TIME ->
                ui.tr(
                    R.string.time_summary,
                    session.settings.textSize,
                    session.settings.timeFontWeight,
                    session.settings.timeFormat,
                )
            EditorSection.DATE ->
                if (session.settings.showDate)
                    ui.tr(
                        R.string.date_summary,
                        session.settings.dateFormat,
                        session.settings.dateSize,
                    )
                else ui.tr(R.string.date_hidden_summary)
            EditorSection.WEATHER ->
                weather.summary()
            EditorSection.APPEARANCE ->
                ui.tr(
                    R.string.appearance_summary,
                    controls.fontSummary(),
                    if (session.world) ui.tr(R.string.day_night_and_backdrop)
                    else if (session.settings.orientation == WidgetOrientation.HORIZONTAL)
                        ui.tr(R.string.horizontal_summary)
                    else ui.tr(R.string.vertical_summary),
                )
            EditorSection.PADDING ->
                ui.tr(
                    R.string.padding_summary,
                    session.settings.paddingTop,
                    session.settings.paddingBottom,
                    session.settings.paddingLeft,
                    session.settings.paddingRight,
                )
            EditorSection.ALARM ->
                if (session.settings.showAlarm)
                    ui.tr(R.string.alarm_enabled_summary, session.settings.alarmFormat)
                else ui.tr(R.string.alarm_hidden_summary)
            EditorSection.ACTIONS ->
                if (session.settings.advancedTap && !session.world)
                    ui.tr(R.string.separate_time_date_and_alarm_actions)
                else TapActions.label(this, session.settings.tapAction, ui.locale())
            EditorSection.CITIES ->
                session.settings.cities.joinToString(" · ") { CityCatalog.name(this, it, ui.locale()) }
            EditorSection.FOREGROUND -> ui.tr(R.string.separate_day_and_night_colors)
            EditorSection.WORLD_BACKGROUND -> ui.tr(R.string.separate_day_and_night_backgrounds)
        }

    private fun buildSettingsSection(
        parent: LinearLayout,
        key: EditorSection,
        defaults: WidgetSettings,
    ) {
        if (!sections.isExpanded(key)) {
            section(parent, key)
            return
        }
        when (key) {
            EditorSection.TIME -> clockSections.clockTimeControls(parent, defaults)
            EditorSection.DATE -> clockSections.clockDateControls(parent, defaults)
            EditorSection.WEATHER -> weather.buildControls(parent)
            EditorSection.APPEARANCE ->
                if (session.world) worldSections.worldAppearanceControls(parent, defaults)
                else clockSections.clockAppearanceControls(parent, defaults)
            EditorSection.PADDING -> clockSections.clockPaddingControls(parent, defaults)
            EditorSection.ALARM -> clockSections.clockAlarmControls(parent, defaults)
            EditorSection.ACTIONS ->
                if (session.world) worldSections.worldActionsControls(parent) else clockSections.clockActionsControls(parent)
            EditorSection.CITIES -> worldSections.worldCitiesControls(parent)
            EditorSection.FOREGROUND -> worldSections.worldForegroundControls(parent)
            EditorSection.WORLD_BACKGROUND -> worldSections.worldBackgroundControls(parent)
        }
        sections.markBuilt(key)
    }

    override fun refreshSettingsSections(vararg keys: EditorSection?) {
        applyChangedSettings()
        if (
            (editorControlsStale ||
                renderedMissingTarget != session.missingTarget() ||
                !session.placedSignature().equals(knownPlacedSignature) ||
                uiLanguageChanged())
        ) {
            dismissEditorDialogs()
            render()
            return
        }
        replaceSettingsSections(*keys)
        preview.refresh(false)
    }

    private fun replaceSettingsSections(vararg keys: EditorSection?) = sections.replace(*keys)

    override fun settingsSectionKey(view: View) = sections.keyFor(view)

    override fun refreshSectionSummary(key: EditorSection?) = sections.refreshSummary(key)

    companion object {
        private val CLOCK_SECTIONS =
            arrayOf(
                EditorSection.TIME,
                EditorSection.DATE,
                EditorSection.WEATHER,
                EditorSection.APPEARANCE,
                EditorSection.PADDING,
                EditorSection.ALARM,
                EditorSection.ACTIONS,
            )
        private val WORLD_SECTIONS =
            arrayOf(
                EditorSection.CITIES,
                EditorSection.APPEARANCE,
                EditorSection.FOREGROUND,
                EditorSection.WORLD_BACKGROUND,
                EditorSection.ACTIONS,
            )
    }
}

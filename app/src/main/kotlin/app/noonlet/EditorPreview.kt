package app.noonlet

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.SizeF
import android.view.Choreographer
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

internal class EditorPreview(
    private val activity: Activity,
    private val ui: EditorUi,
    private val sessionProvider: () -> EditorSession?,
    private val applySettings: () -> Unit,
    private val controlsStale: () -> Boolean,
    private val rebuildEditor: () -> Unit,
) {
    private val session: EditorSession get() = checkNotNull(sessionProvider())
    private val handler = Handler(Looper.getMainLooper())
    var stage: WidgetPreviewStage? = null
        private set
    private var card: LinearLayout? = null
    private var layoutId: Int = 0
    private val presets = intArrayOf(0, -1)
    private val columns = intArrayOf(1, 1)
    private val rows = intArrayOf(0, 0)
    private var refreshAfterDrag: Boolean = false
    var phoneLocaleTag: String? = null
        private set
    var appLocaleTag: String? = null
        private set
    var renderedAt: Long = 0
        private set
    var wasPaused: Boolean = false
        private set
    var active: Boolean = false
        private set
    var seconds: Boolean = false
        private set
    var clock = java.time.Clock.systemUTC()
    private var needsFullUpdate = true
    private var settingsCommitQueued: Boolean = false
    private var previewFrameQueued: Boolean = false
    private var adjusting = false
    private var adjustmentChanged = false
    private var pendingPhoneLocale: Locale? = null
    private var measuredTarget = -1
    private var measuredWorld = false
    private var hostOptions = Bundle.EMPTY

    private val tick = Runnable { refresh(commitSettings = !adjusting) }
    private val layoutRefresh = Runnable {
        if (!activity.isDestroyed && (active || !wasPaused)) refresh(false)
    }
    private val queuedSettingsCommit = Runnable {
        settingsCommitQueued = false
        if (active && !activity.isDestroyed) {
            applySettings()
            if (controlsStale()) rebuildEditor()
        }
    }
    private val queuedPreviewFrame = Choreographer.FrameCallback {
        previewFrameQueued = false
        if (active && !activity.isDestroyed) {
            if (
                (!needsFullUpdate &&
                    !controlsStale() &&
                    stage != null &&
                    sessionProvider() != null &&
                    stage?.isDragging() == false &&
                    WidgetRenderer.updatePreviewAppearance(
                        stage?.getWidgetContent(),
                        session.settings,
                        session.world,
                        phoneLocaleTag?.let(Locale::forLanguageTag),
                    ))
            )
                return@FrameCallback
            refresh(false)
        }
    }
    fun createCard(): LinearLayout {
        val previewCard = ui.vertical().apply {
            tag = "preview-card"
            background = ui.shape(ui.palette.controlSurface, 16f)
            clipToOutline = true
        }
        val sizes = ui.horizontal().apply {
            tag = "preview-sizes"
            contentDescription = ui.tr(R.string.preview)
            setPadding(ui.dp(4f), ui.dp(2f), ui.dp(4f), ui.dp(2f))
        }
        val kind = if (session.world) 1 else 0
        val selectedPreset = presetIndex()
        for (index in PREVIEW_SIZES.indices) {
            val label = PREVIEW_SIZES[index].label
            val choice = index
            val chip =
                ui.button(
                    label,
                    if (index == selectedPreset) ui.palette.selectedSurface else Color.TRANSPARENT,
                    if (index == selectedPreset) ui.palette.text else ui.palette.muted,
                )
            chip.textSize = 12f
            chip.minimumHeight = ui.dp(24f)
            chip.background = sizeBackground(index == selectedPreset)
            chip.isSelected = index == selectedPreset
            chip.tag = "preview-size:" + index
            chip.contentDescription = ui.tr(R.string.preview_size, label)
            sizes.addView(chip, LinearLayout.LayoutParams(0, -1, 1f))
            chip.setOnClickListener({ v -> selectSize(choice) })
        }
        val stage = WidgetPreviewStage(activity)
        this.stage = stage
        stage.setPoseListener(
            { x, y, dragging ->
                columns[kind] = x.toInt()
                rows[kind] = y.toInt()
                if (!dragging && refreshAfterDrag && active && this.stage === stage)
                    refresh()
            },
        )
        val wallpaper =
            GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                if (session.world) intArrayOf(-0xcfcfc9, -0xe5e5e0)
                else intArrayOf(-0x707e5b, -0x9aaa89),
            )
        stage.background = wallpaper
        stage.clipToOutline = false
        stage.tag = "preview-stage"
        stage.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                needsFullUpdate = true
                handler.removeCallbacks(layoutRefresh)
                if (!activity.isDestroyed && (active || !wasPaused)) {
                    // Populate the measured viewport before the next traversal barrier clears.
                    handler.sendMessage(Message.obtain(handler, layoutRefresh).apply { isAsynchronous = true })
                }
            }
        }
        previewCard.addView(stage, LinearLayout.LayoutParams(-1, -2))
        previewCard.addView(sizes, LinearLayout.LayoutParams(-1, -2))
        card = previewCard
        return previewCard
    }

    fun queueFull() {
        needsFullUpdate = true
        queueAppearance()
    }

    fun queueAppearance() {
        queueFrame()
        if (adjusting) adjustmentChanged = true
        else if (!settingsCommitQueued) {
            settingsCommitQueued = true
            handler.postDelayed(queuedSettingsCommit, 75)
        }
    }

    private fun queueFrame() {
        if (!previewFrameQueued) {
            previewFrameQueued = true
            Choreographer.getInstance().postFrameCallback(queuedPreviewFrame)
        }
    }

    fun beginAdjustment() {
        adjusting = true
        adjustmentChanged = settingsCommitQueued
        handler.removeCallbacks(queuedSettingsCommit)
        settingsCommitQueued = false
    }

    fun finishAdjustment() {
        if (!adjusting) return
        adjusting = false
        val changed = adjustmentChanged
        adjustmentChanged = false
        if (changed && active && !activity.isDestroyed) {
            applySettings()
            if (controlsStale()) rebuildEditor()
        }
    }

    fun cancelQueued() {
        handler.removeCallbacks(layoutRefresh)
        handler.removeCallbacks(queuedSettingsCommit)
        Choreographer.getInstance().removeFrameCallback(queuedPreviewFrame)
        previewFrameQueued = false
        settingsCommitQueued = false
    }

    fun refresh(
        commitSettings: Boolean = true,
        resolvedPhoneLocale: Locale? = null,
    ) {
        val stage = stage ?: return
        if (sessionProvider() == null) return
        if (commitSettings && !adjusting) {
            cancelQueued()
            applySettings()
        }
        if (controlsStale()) {
            rebuildEditor()
            return
        }
        handler.removeCallbacks(tick)
        handler.removeCallbacks(layoutRefresh)
        if (stage.width <= 0 || stage.height <= 0) {
            needsFullUpdate = true
            pendingPhoneLocale = resolvedPhoneLocale
            return
        }
        if (stage.isDragging()) {
            refreshAfterDrag = true
            return
        }
        refreshAfterDrag = false
        val previewId = if (session.world) PREVIEW_WORLD else PREVIEW_CLOCK
        val kind = if (session.world) 1 else 0
        val size = PREVIEW_SIZES[presetIndex()]
        val phoneLocale = resolvedPhoneLocale ?: pendingPhoneLocale ?: AppLocales.phoneLocale(activity)
        pendingPhoneLocale = null
        stage.setNativeSize(nativeSize(size))
        val viewport = stage.viewport(size.width, size.height)
        val views =
            WidgetRenderer.preview(
                activity,
                previewId,
                session.world,
                session.settings,
                viewport.width,
                viewport.height,
                WidgetFont.Scope.LOCAL,
                phoneLocale,
            )
        var view = stage.getWidgetContent()
        if (view != null && layoutId == views.layoutId) views.reapply(activity, view)
        else view = views.apply(activity, stage)
        layoutId = views.layoutId
        val timestamp = clock.millis()
        phoneLocaleTag = phoneLocale.toLanguageTag()
        appLocaleTag = AppLocales.appLocale(activity, phoneLocale).toLanguageTag()
        seconds = PreviewClockFormatter.localize(view, timestamp, phoneLocale)
        stage.showWidget(
            view,
            size.width,
            size.height,
            size.columns,
            size.rows,
            columns[kind],
            rows[kind],
        )
        needsFullUpdate = false
        renderedAt = timestamp
        scheduleTick(timestamp)
    }

    fun scheduleTick(timestamp: Long) {
        handler.removeCallbacks(tick)
        if (active && stage?.visibility == View.VISIBLE) {
            val period = (if (seconds) 1000 else 60000).toLong()
            handler.postDelayed(tick, period - Math.floorMod(timestamp, period))
        }
    }

    private fun presetIndex(): Int {
        val kind = if (session.world) 1 else 0
        if (presets[kind] < 0)
            presets[kind] = if (session.world && session.settings.cities.size > 2) 2 else 0
        return presets[kind]
    }

    private fun sizeBackground(selected: Boolean) = AppDialogBuilder.ripple(activity,
        if (selected) ui.palette.selectedSurface else Color.TRANSPARENT, 6, false)

    private fun selectSize(choice: Int) {
        if (choice < 0 || choice >= PREVIEW_SIZES.size || choice == presetIndex()) return
        refreshAfterDrag = false
        stage?.cancelDrag()
        cancelQueued()
        presets[if (session.world) 1 else 0] = choice
        session.preferences.selectPreviewSize(session.world, choice)
        for (index in PREVIEW_SIZES.indices) {
            val chip = card?.findViewWithTag<TextView>("preview-size:" + index)
            val selected = index == choice
            if (chip != null && chip.isSelected != selected) {
                chip.isSelected = selected
                chip.setTextColor(if (selected) ui.palette.text else ui.palette.muted)
                chip.background = sizeBackground(selected)
            }
        }
        refresh()
    }

    fun restoreSavedSizes() {
        for (index in 0..1) presets[index] =
            session.preferences.previewSize(index == 1, PREVIEW_SIZES.size)
    }

    fun restoreState(state: Bundle) {
        val sizes = state.getIntArray("preview_presets")
        val x = state.getIntArray("preview_columns")
        val y = state.getIntArray("preview_rows")
        for (index in 0..1) {
            if (
                sizes != null &&
                    index < sizes.size &&
                    sizes[index] >= -1 &&
                    sizes[index] < PREVIEW_SIZES.size
            )
                presets[index] = if (index == 0) Math.max(0, sizes[index]) else sizes[index]
            val size = PREVIEW_SIZES[Math.max(0, presets[index])]
            columns[index] = (WidgetPreviewStage.COLUMNS - size.columns) / 2
            if (x != null && index < x.size && x[index] >= 0 &&
                x[index] <= WidgetPreviewStage.COLUMNS - size.columns)
                columns[index] = x[index]
            if (y != null && index < y.size && y[index] >= 0 &&
                y[index] <= WidgetPreviewStage.ROWS - size.rows)
                rows[index] = y[index]
        }
    }

    fun prepareForRender() {
        adjusting = false
        adjustmentChanged = false
        cancelQueued()
        refreshAfterDrag = false
        stage?.cancelDrag()
    }

    fun pause() {
        adjusting = false
        adjustmentChanged = false
        active = false
        wasPaused = true
        stage?.cancelDrag()
        handler.removeCallbacks(tick)
        cancelQueued()
    }

    fun activate() {
        active = true
        measuredTarget = -1
    }

    fun markResumed() {
        wasPaused = false
    }

    private fun nativeSize(size: WidgetSize): SizeF? {
        val id = session.target.id
        if (id <= 0 || session.initialConfiguration) return null
        if (id != measuredTarget || session.world != measuredWorld) {
            hostOptions = AppWidgetManager.getInstance(activity).getAppWidgetOptions(id)
            measuredTarget = id
            measuredWorld = session.world
        }
        val width = hostOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val height = hostOptions.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
        if (width <= 0 || height <= 0) return null
        val columns = if (width >= 245) 4 else 2
        val rows = if (height >= 136) 2 else 1
        if (size.columns != columns || size.rows != rows) return null
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            hostOptions.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                ?.filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }
                ?.minByOrNull { kotlin.math.abs(it.width - width) + kotlin.math.abs(it.height - height) }
                ?.let { return it }
        }
        return SizeF(width.toFloat(), height.toFloat())
    }

    fun saveState(out: Bundle) {
        out.putIntArray("preview_presets", presets.clone())
        out.putIntArray("preview_columns", columns.clone())
        out.putIntArray("preview_rows", rows.clone())
    }

    fun pinSnapshot(): android.widget.RemoteViews {
        return WidgetPinReceiver.preview(activity, session.world, session.settings, requestedSize())
    }

    fun requestedSize(): WidgetSize = PREVIEW_SIZES[presetIndex()]

    companion object {
        const val PREVIEW_CLOCK = -1001
        const val PREVIEW_WORLD = -1002
        private val PREVIEW_SIZES = WidgetSize.entries.toTypedArray()

    }
}

package app.noonlet

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.UUID

class EditorSession(context: Context, intent: Intent, state: Bundle?) {
    private val context = context.applicationContext
    val preferences = EditorPreferences(context)
    val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0).coerceAtLeast(0)
    val creatingNew = widgetId == 0 &&
        (if (state?.containsKey(CREATING_NEW) == true) state.getBoolean(CREATING_NEW)
         else intent.action == EditorIntents.CREATE_WIDGET)
    val pinOwner =
        state?.getString(PIN_OWNER)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
    var world: Boolean
        private set

    val initialConfiguration: Boolean
    var homeTargetId = 0
        private set

    var settings: WidgetSettings
        private set

    private var selectedClockId = 0
    private var selectedWorldId = 0
    private var homeDrafts = Bundle()
    private var homeSavedStates = Bundle()
    private var editorSavedState: Bundle? = null
    private var bindingSnapshot: Array<IntArray>? = null
    private var importRevision = preferences.importRevision()
    private val importedToken = intent.getStringExtra(EditorIntents.RESTORED_CONFIGURATION)
    internal val importedConfiguration: ImportedWidgetConfigurations.Item?
        get() = when {
            creatingNew -> ImportedWidgetConfigurations.get(context, importedToken)
            widgetId == 0 && homeTargetId == 0 -> ImportedWidgetConfigurations.all(context).firstOrNull { it.world == world }
            else -> null
        }

    internal fun <T> withBindings(action: () -> T): T {
        if (bindingSnapshot != null) return action()
        bindingSnapshot = arrayOf(readBoundIds(false), readBoundIds(true))
        return try { action() } finally { bindingSnapshot = null }
    }

    data class Target(val id: Int, val world: Boolean)

    sealed interface ApplyResult {
        data class HostUpdate(val target: Target, val wrote: Boolean) : ApplyResult

        data object Unchanged : ApplyResult

        data object DraftSaved : ApplyResult

        data object Missing : ApplyResult

        data object Deferred : ApplyResult

        data object ConflictReloaded : ApplyResult
    }

    enum class Transition {
        UNCHANGED,
        SELECTED_TARGET,
        ADOPTED_TARGET,
        RELOADED_SETTINGS,
    }

    val target: Target
        get() = Target(if (widgetId > 0) widgetId else homeTargetId, world)

    init {
        val restoreDrafts = state == null || state.getLong(IMPORT_REVISION, 0) == importRevision
        bindingSnapshot = arrayOf(readBoundIds(false), readBoundIds(true))
        WidgetSettingsStore.pruneConfigurations(context, boundIds(false), boundIds(true))
        world =
            if (widgetId > 0) {
                val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(widgetId)
                intent.getBooleanExtra(
                    "world",
                    BaseWidgetProvider.owns(context, info?.provider, true),
                )
            } else if (creatingNew) intent.getBooleanExtra("world", false)
            else if (intent.action == Intent.ACTION_MAIN) false
            else preferences.worldTab()
        if (state != null) world = state.getBoolean("world", world)
        val directEditor = EditorIntents.isDirectEdit(intent)
        initialConfiguration =
            widgetId > 0 &&
                (WidgetSettingsStore.pendingConfiguration(context, widgetId, world) ||
                    (!directEditor &&
                        (if (state != null && state.containsKey(INITIAL_CONFIGURATION))
                            state.getBoolean(INITIAL_CONFIGURATION)
                        else !WidgetSettingsStore.hasOwnSettings(context, widgetId, world))))
        if (initialConfiguration && boundTargetExists(widgetId, world))
            WidgetSettingsStore.beginConfiguration(context, widgetId, world)
        if (widgetId > 0) rememberSelection(widgetId, world)
        if (widgetId == 0) {
            selectedClockId =
                if (state?.containsKey("selected_clock_id") == true)
                    state.getInt("selected_clock_id")
                else initialHomeTarget(false)
            selectedWorldId =
                if (state?.containsKey("selected_world_id") == true)
                    state.getInt("selected_world_id")
                else initialHomeTarget(true)
            homeTargetId =
                if (creatingNew) 0
                else if (state?.containsKey("home_target_id") == true) state.getInt("home_target_id")
                else selectedTarget(world)
            if (restoreDrafts) {
                state?.getBundle("home_drafts")?.let { homeDrafts = Bundle(it) }
                state?.getBundle("home_saved_states")?.let { homeSavedStates = Bundle(it) }
            }
        }
        val previousSaved =
            if (widgetId == 0) homeSavedStates.getBundle(draftKey(homeTargetId, world)) else null
        settings =
            if (widgetId > 0) {
                if (
                    initialConfiguration &&
                        !WidgetSettingsStore.hasOwnSettings(context, widgetId, world)
                )
                    WidgetSettingsStore.loadUnplaced(context, world)
                else WidgetSettingsStore.load(context, widgetId, world)
            } else loadHomeDraft(homeTargetId, world)
        val imported = importedConfiguration
        if (creatingNew && imported?.world == world && state == null) {
            settings = imported.configuration.settings
            preferences.selectPreviewSize(world, imported.configuration.size.ordinal)
        }
        if (widgetId > 0) editorSavedState = SettingsCodec.snapshot(settings)
        if (
            state != null && restoreDrafts &&
                (if (widgetId > 0)
                    initialConfiguration ||
                        state.getBundle("editor_saved_state") == null ||
                        SettingsCodec.sameEditorSettings(
                            state.getBundle("editor_saved_state"),
                            editorSavedState,
                        )
                else
                    previousSaved == null ||
                        SettingsCodec.sameEditorSettings(
                            previousSaved,
                            homeSavedStates.getBundle(draftKey(homeTargetId, world)),
                        ))
        )
            WidgetSettingsStore.restoreEditorState(context, target.id, world, settings, state)
        bindingSnapshot = null
    }

    fun saveState(out: Bundle, onApplied: (ApplyResult) -> Unit) {
        onApplied(apply())
        out.putBoolean("world", world)
        out.putBoolean(INITIAL_CONFIGURATION, initialConfiguration)
        out.putBoolean(CREATING_NEW, creatingNew)
        out.putString(PIN_OWNER, pinOwner)
        out.putLong(IMPORT_REVISION, importRevision)
        editorSavedState?.let { out.putBundle("editor_saved_state", Bundle(it)) }
        if (widgetId == 0) {
            stashHomeDraft()
            out.putInt("home_target_id", homeTargetId)
            out.putInt("selected_clock_id", selectedClockId)
            out.putInt("selected_world_id", selectedWorldId)
            out.putBundle("home_drafts", Bundle(homeDrafts))
            out.putBundle("home_saved_states", Bundle(homeSavedStates))
        }
        SettingsCodec.writeEditorState(settings, out)
    }

    fun boundIds(kind: Boolean): IntArray =
        bindingSnapshot?.get(if (kind) 1 else 0)?.clone() ?: readBoundIds(kind)

    private fun readBoundIds(kind: Boolean): IntArray =
        BaseWidgetProvider.boundIds(context, kind)

    fun placedIds(kind: Boolean): IntArray =
        WidgetSettingsStore.configuredIds(context, boundIds(kind), kind)

    fun placedSignature(): String =
        placedIds(false).contentToString() + ":" + placedIds(true).contentToString()

    fun boundTargetExists(id: Int, kind: Boolean): Boolean = id > 0 && id in boundIds(kind)

    fun targetExists(id: Int = target.id, kind: Boolean = world): Boolean {
        if (id <= 0) return false
        if (widgetId == id && world == kind && !initialConfiguration)
            return boundTargetExists(id, kind) &&
                !WidgetSettingsStore.pendingConfiguration(context, id, kind)
        return id in placedIds(kind)
    }

    fun missingTarget(): Boolean =
        if (widgetId > 0) !boundTargetExists(widgetId, world)
        else homeTargetId > 0 && homeTargetId !in placedIds(world) && placedIds(world).isNotEmpty()

    fun switchTab(kind: Boolean, onApplied: (ApplyResult) -> Unit): Boolean {
        if (creatingNew || world == kind) return false
        onApplied(apply())
        stashHomeDraft()
        syncExternalSelection(kind)
        world = kind
        homeTargetId = availableHomeTarget(selectedTarget(kind), kind)
        rememberSelection(homeTargetId, kind)
        settings = loadHomeDraft(homeTargetId, kind)
        return true
    }

    fun selectTarget(id: Int, kind: Boolean, onApplied: (ApplyResult) -> Unit) {
        if (creatingNew) return
        onApplied(apply())
        stashHomeDraft()
        world = kind
        homeTargetId = id
        rememberSelection(id, kind)
        settings = loadHomeDraft(id, kind)
    }

    fun reconcile(onApplied: (ApplyResult) -> Unit): Transition {
        if (reloadImportedSettings()) return Transition.RELOADED_SETTINGS
        if (widgetId > 0 || creatingNew) return Transition.UNCHANGED
        syncExternalSelection(false)
        syncExternalSelection(true)
        val selected = selectedTarget(world)
        if (selected > 0 && selected != homeTargetId) {
            selectTarget(selected, world, onApplied)
            return Transition.SELECTED_TARGET
        }
        if (adoptAvailableTarget(onApplied)) return Transition.ADOPTED_TARGET
        return if (reloadExternalSettings()) Transition.RELOADED_SETTINGS else Transition.UNCHANGED
    }

    fun prepareAdd(onApplied: (ApplyResult) -> Unit): Transition {
        if (creatingNew) {
            onApplied(apply())
            return Transition.UNCHANGED
        }
        if (widgetId == 0) {
            syncExternalSelection(world)
            val selected = selectedTarget(world)
            if (selected > 0 && selected != homeTargetId && targetExists(selected, world)) {
                selectTarget(selected, world, onApplied)
                return Transition.SELECTED_TARGET
            }
            if (adoptAvailableTarget(onApplied)) return Transition.ADOPTED_TARGET
        }
        onApplied(apply())
        return Transition.UNCHANGED
    }

    fun configureInitial(): Target? {
        if (!initialConfiguration || !boundTargetExists(widgetId, world)) return null
        WidgetSettingsStore.save(context, widgetId, world, settings)
        return target
    }

    fun resetAppearance() {
        val current = settings
        settings =
            WidgetSettings.defaults(world).also {
                it.zoneId = current.zoneId
                it.cities = current.cities.clone()
                it.weatherEnabled = current.weatherEnabled
                it.weatherUseDevice = current.weatherUseDevice
                it.weatherPlace = current.weatherPlace
                it.showDate = current.showDate
                it.showAlarm = current.showAlarm
                it.showNoAlarm = current.showNoAlarm
                it.showAlarmIcon = current.showAlarmIcon
                it.advancedTap = current.advancedTap
                it.tapAction = current.tapAction
                it.timeTapAction = current.timeTapAction
                it.dateTapAction = current.dateTapAction
                it.alarmTapAction = current.alarmTapAction
            }
    }

    fun apply(forceHostUpdate: Boolean = false): ApplyResult {
        return withBindings { applyWithBindings(forceHostUpdate) }
    }

    private fun applyWithBindings(forceHostUpdate: Boolean): ApplyResult {
        if (reloadImportedSettings()) return ApplyResult.ConflictReloaded
        if (initialConfiguration) return ApplyResult.Deferred
        val current = target
        val imported = importedConfiguration?.takeIf { it.world == world && current.id == 0 }
        if (imported != null) {
            val key = draftKey(0, world)
            val saved = SettingsCodec.snapshot(imported.configuration.settings)
            val previous = homeSavedStates.getBundle(key)
            if (previous != null && !SettingsCodec.sameEditorSettings(previous, saved)) {
                settings = imported.configuration.settings
                homeSavedStates.putBundle(key, saved)
                homeDrafts.remove(key)
                return ApplyResult.ConflictReloaded
            }
            val snapshot = SettingsCodec.snapshot(settings)
            if (!SettingsCodec.sameEditorSettings(saved, snapshot))
                ImportedWidgetConfigurations.updateSettings(context, imported.token, settings)
            homeSavedStates.putBundle(key, snapshot)
            stashHomeDraft()
            return ApplyResult.DraftSaved
        }
        if (widgetId == 0 && !targetExists() && (creatingNew || placedIds(world).isEmpty())) {
            val savedDraft = WidgetSettingsStore.loadUnplaced(context, world)
            if (
                !SettingsCodec.sameEditorSettings(
                    SettingsCodec.snapshot(savedDraft),
                    SettingsCodec.snapshot(settings),
                )
            )
                WidgetSettingsStore.saveUnplaced(context, world, settings)
            if (homeTargetId == 0)
                homeSavedStates.putBundle(draftKey(0, world), SettingsCodec.snapshot(settings))
            stashHomeDraft()
            return ApplyResult.DraftSaved
        }
        if (!targetExists()) return ApplyResult.Missing
        val key = draftKey(current.id, world)
        val previous = if (widgetId > 0) editorSavedState else homeSavedStates.getBundle(key)
        val persisted = WidgetSettingsStore.load(context, current.id, world)
        val saved = SettingsCodec.snapshot(persisted)
        if (previous != null && !SettingsCodec.sameEditorSettings(previous, saved)) {
            settings = persisted
            if (widgetId > 0) editorSavedState = saved
            else {
                homeSavedStates.putBundle(key, saved)
                homeDrafts.remove(key)
            }
            return ApplyResult.ConflictReloaded
        }
        val unchanged = SettingsCodec.sameEditorSettings(saved, SettingsCodec.snapshot(settings))
        if (unchanged && !forceHostUpdate) return ApplyResult.Unchanged
        val wrote = !unchanged || !WidgetSettingsStore.hasOwnSettings(context, current.id, world)
        if (wrote) {
            WidgetSettingsStore.save(context, current.id, world, settings)
            val normalized = SettingsCodec.snapshot(settings)
            if (widgetId > 0) editorSavedState = normalized
            else {
                homeSavedStates.putBundle(key, normalized)
                stashHomeDraft()
            }
        }
        return ApplyResult.HostUpdate(current, wrote)
    }

    private fun selectedTarget(kind: Boolean) = if (kind) selectedWorldId else selectedClockId

    private fun rememberSelection(id: Int, kind: Boolean) {
        if (kind) selectedWorldId = id else selectedClockId = id
        preferences.selectTarget(id, kind)
    }

    private fun initialHomeTarget(kind: Boolean): Int {
        val ids = placedIds(kind)
        val remembered = preferences.selectedTarget(kind)
        return if (remembered in ids) remembered else ids.firstOrNull() ?: 0
    }

    private fun availableHomeTarget(selected: Int, kind: Boolean) =
        if (selected != 0) selected else placedIds(kind).firstOrNull() ?: 0

    private fun syncExternalSelection(kind: Boolean) {
        val remembered = preferences.selectedTarget(kind)
        if (remembered <= 0 || remembered == selectedTarget(kind) || remembered !in placedIds(kind))
            return
        if (kind) selectedWorldId = remembered else selectedClockId = remembered
        homeDrafts.remove(draftKey(remembered, kind))
    }

    private fun adoptAvailableTarget(onApplied: (ApplyResult) -> Unit): Boolean {
        if (widgetId > 0 || homeTargetId != 0) return false
        val available = availableHomeTarget(0, world)
        if (available == 0) return false
        selectTarget(available, world, onApplied)
        return true
    }

    private fun draftKey(id: Int, kind: Boolean) = "${if (kind) "world:" else "clock:"}$id"

    private fun stashHomeDraft() {
        if (widgetId == 0)
            homeDrafts.putBundle(draftKey(homeTargetId, world), SettingsCodec.snapshot(settings))
    }

    private fun loadHomeDraft(id: Int, kind: Boolean): WidgetSettings {
        val draft = persistedHomeDraft(id, kind)
        val key = draftKey(id, kind)
        val saved = SettingsCodec.snapshot(draft)
        val previous = homeSavedStates.getBundle(key)
        if (previous != null && !SettingsCodec.sameEditorSettings(previous, saved))
            homeDrafts.remove(key)
        homeSavedStates.putBundle(key, saved)
        WidgetSettingsStore.restoreEditorState(context, id, kind, draft, homeDrafts.getBundle(key))
        return draft
    }

    private fun reloadExternalSettings(): Boolean {
        if (homeTargetId > 0 && homeTargetId !in placedIds(world)) return false
        val key = draftKey(homeTargetId, world)
        val saved = persistedHomeDraft(homeTargetId, world)
        val snapshot = SettingsCodec.snapshot(saved)
        val previous = homeSavedStates.getBundle(key)
        if (previous == null) {
            homeSavedStates.putBundle(key, snapshot)
            return false
        }
        if (SettingsCodec.sameEditorSettings(previous, snapshot)) return false
        homeSavedStates.putBundle(key, snapshot)
        homeDrafts.remove(key)
        settings = saved
        return true
    }

    private fun persistedHomeDraft(id: Int, kind: Boolean): WidgetSettings =
        if (id > 0) WidgetSettingsStore.load(context, id, kind)
        else importedConfiguration?.takeIf { it.world == kind }?.configuration?.settings
            ?: WidgetSettingsStore.loadUnplaced(context, kind)

    internal fun reloadImportedSettings(): Boolean {
        val revision = preferences.importRevision()
        if (revision == importRevision) return false
        importRevision = revision
        homeDrafts = Bundle()
        homeSavedStates = Bundle()
        if (widgetId > 0) {
            settings = WidgetSettingsStore.load(context, widgetId, world)
            editorSavedState = SettingsCodec.snapshot(settings)
        } else settings = loadHomeDraft(homeTargetId, world)
        return true
    }

    companion object {
        private const val INITIAL_CONFIGURATION = "initial_widget_configuration"
        private const val CREATING_NEW = "creating_new_widget"
        private const val PIN_OWNER = "pin_owner"
        private const val IMPORT_REVISION = "editor_import_revision"
    }
}

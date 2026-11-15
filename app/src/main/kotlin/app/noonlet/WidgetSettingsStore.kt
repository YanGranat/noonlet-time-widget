package app.noonlet

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle

object WidgetSettingsStore {
    private fun preferences(context: Context) =
        context.getSharedPreferences("noonlet_widgets", Context.MODE_PRIVATE)

    private fun configurations(context: Context) =
        context.getSharedPreferences("widget_configuration", Context.MODE_PRIVATE)

    private fun drafts(context: Context) =
        context.getSharedPreferences("widget_drafts", Context.MODE_PRIVATE)

    private fun prefix(id: Int, world: Boolean) = "${if (world) "world." else "clock."}$id."

    private fun configurationKey(id: Int, world: Boolean) =
        "${if (world) "world." else "clock."}$id"

    private fun draftPrefix(world: Boolean) = if (world) "world." else "clock."

    private fun sourcePrefix(stored: SharedPreferences, id: Int, world: Boolean): String {
        val own = prefix(id, world)
        return if (id != 0 && !stored.contains(own + "saved")) prefix(0, world) else own
    }

    fun hasOwnSettings(context: Context, id: Int, world: Boolean) =
        id > 0 && preferences(context).contains(prefix(id, world) + "saved") &&
            !RestoredWidgetBindings.contains(context, id, world)

    fun load(context: Context, id: Int, world: Boolean): WidgetSettings = loadStored(context, id, world, false)

    internal fun loadForRestore(context: Context, id: Int, world: Boolean): WidgetSettings =
        loadStored(context, id, world, true)

    private fun loadStored(context: Context, id: Int, world: Boolean, restoring: Boolean): WidgetSettings {
        val stored = preferences(context)
        val source = if (!restoring && RestoredWidgetBindings.contains(context, id, world)) prefix(0, world)
            else sourcePrefix(stored, id, world)
        return WidgetSettings.defaults(world).apply {
            SettingsCodec.loadInto(this, stored, source, legacy = true)
            tapAction =
                migratedAction(
                    tapAction,
                    world,
                    stored.getInt(source + SettingsCodec.TAP_DEFAULT_VERSION_KEY, 0),
                )
        }
    }

    fun save(context: Context, id: Int, world: Boolean, settings: WidgetSettings) {
        SettingsCodec.save(preferences(context), prefix(id, world), settings, world)
        claimRestoredBinding(context, id, world)
        clearConfiguration(context, id, world)
    }

    fun delete(context: Context, id: Int, world: Boolean) {
        val stored = preferences(context)
        stored
            .edit()
            .apply {
                stored.all.keys.filter { it.startsWith(prefix(id, world)) }.forEach(::remove)
            }
            .apply()
        claimRestoredBinding(context, id, world)
        clearConfiguration(context, id, world)
    }

    private fun claimRestoredBinding(context: Context, id: Int, world: Boolean) {
        if (RestoredWidgetBindings.contains(context, id, world) && preferences(context).edit().commit())
            RestoredWidgetBindings.claim(context, id, world)
    }

    fun loadUnplaced(context: Context, world: Boolean): WidgetSettings =
        load(context, 0, world).also { settings ->
            val stored = drafts(context)
            if (stored.contains(draftPrefix(world) + "state"))
                SettingsCodec.loadInto(settings, stored, draftPrefix(world))
        }

    fun saveUnplaced(context: Context, world: Boolean, settings: WidgetSettings) =
        SettingsCodec.save(drafts(context), draftPrefix(world), settings, world)

    fun restoreEditorState(
        context: Context,
        id: Int,
        world: Boolean,
        settings: WidgetSettings,
        input: Bundle?,
    ) {
        if (input == null) return
        val previousAction = settings.tapAction
        SettingsCodec.readFrom(settings, input)
        if (!world || !input.containsKey("cfg_tapAction")) return
        val version = input.getInt(SettingsCodec.EDITOR_TAP_DEFAULT_VERSION, 0)
        val stored = preferences(context)
        settings.tapAction =
            if (
                version < SettingsCodec.TAP_DEFAULT_VERSION &&
                    stored.getInt(
                        sourcePrefix(stored, id, world) + SettingsCodec.TAP_DEFAULT_VERSION_KEY,
                        0,
                    ) >= SettingsCodec.TAP_DEFAULT_VERSION
            )
                previousAction
            else migratedAction(settings.tapAction, world, version)
    }

    private fun migratedAction(action: String, world: Boolean, version: Int) =
        if (
            world &&
                version < SettingsCodec.TAP_DEFAULT_VERSION &&
                (action == "clock" || action == "calendar")
        )
            "settings"
        else action

    fun beginConfiguration(context: Context, id: Int, world: Boolean) {
        if (id <= 0 || hasOwnSettings(context, id, world)) return
        val stored = configurations(context)
        if (!stored.getBoolean(configurationKey(id, world), false))
            stored.edit().putBoolean(configurationKey(id, world), true).apply()
    }

    fun pendingConfiguration(context: Context, id: Int, world: Boolean): Boolean =
        id > 0 &&
            configurations(context).getBoolean(configurationKey(id, world), false) &&
            !hasOwnSettings(context, id, world)

    private fun clearConfiguration(context: Context, id: Int, world: Boolean) {
        if (id <= 0) return
        val stored = configurations(context)
        if (stored.contains(configurationKey(id, world)))
            stored.edit().remove(configurationKey(id, world)).apply()
    }

    fun configuredIds(context: Context, bound: IntArray, world: Boolean): IntArray =
        bound.filter { hasOwnSettings(context, it, world) }.toIntArray()

    fun pruneConfigurations(context: Context, clockIds: IntArray, worldIds: IntArray) {
        val stored = configurations(context)
        val changes = stored.edit()
        var changed = false
        for (name in stored.all.keys) {
            val world = name.startsWith("world.")
            if (!world && !name.startsWith("clock.")) continue
            val id = name.substringAfter('.').toIntOrNull() ?: continue
            if (id !in (if (world) worldIds else clockIds) || hasOwnSettings(context, id, world)) {
                changes.remove(name)
                changed = true
            }
        }
        if (changed) changes.apply()
    }
}

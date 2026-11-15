package app.noonlet

import android.appwidget.AppWidgetManager
import android.content.Context

class EditorPreferences(context: Context) {
    private val context = context.applicationContext
    private val stored = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)

    fun worldTab(): Boolean = stored.getBoolean("world_tab", false)

    fun selectedTarget(world: Boolean): Int = stored.getInt(targetKey(world), 0)

    fun selectTarget(id: Int, world: Boolean, selectTab: Boolean = true) {
        stored
            .edit()
            .apply {
                putInt(targetKey(world), id)
                if (selectTab) putBoolean("world_tab", world)
            }
            .apply()
    }

    fun previewSize(world: Boolean, count: Int): Int {
        val value = stored.all[previewSizeKey(world)]
        return if (value is Int && value in 0 until count) value else if (world) -1 else 0
    }

    fun selectPreviewSize(world: Boolean, choice: Int) =
        stored.edit().putInt(previewSizeKey(world), choice).apply()

    internal fun importRevision(): Long = stored.getLong("settings_import_revision", 0)

    internal fun recordImport() =
        stored.edit().putLong("settings_import_revision", importRevision() + 1).apply()

    fun recordPinCompletion(id: Int, world: Boolean, owner: String) =
        stored.edit().putString(completionKey(id, world), owner).apply()

    fun consumePinCompletion(owner: String): Boolean {
        val changes = stored.edit()
        val manager = AppWidgetManager.getInstance(context)
        var completed = false
        var changed = false
        for ((key, value) in stored.all) {
            if (!key.startsWith(COMPLETION_PREFIX)) continue
            val target = key.removePrefix(COMPLETION_PREFIX)
            val world = target.startsWith("world.")
            val knownType = world || target.startsWith("clock.")
            val id = target.substringAfter('.').toIntOrNull()
            val valid =
                knownType &&
                    id != null &&
                    id > 0 &&
                    BaseWidgetProvider.owns(context, manager.getAppWidgetInfo(id)?.provider, world)
            if (!valid || value == owner) {
                changes.remove(key)
                changed = true
                if (valid && value == owner) completed = true
            }
        }
        if (changed) changes.apply()
        return completed
    }

    private fun targetKey(world: Boolean) = if (world) "world_target" else "clock_target"

    private fun previewSizeKey(world: Boolean) =
        if (world) "preview_size_world" else "preview_size_clock"

    private fun completionKey(id: Int, world: Boolean) =
        "$COMPLETION_PREFIX${if (world) "world" else "clock"}.$id"

    companion object {
        private const val COMPLETION_PREFIX = "pin_complete."
    }
}

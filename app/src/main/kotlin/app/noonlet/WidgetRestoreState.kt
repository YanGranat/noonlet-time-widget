package app.noonlet

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.io.FileNotFoundException
import org.json.JSONArray
import org.json.JSONObject

/** Keeps source IDs immutable while separate provider broadcasts remap one shared store. */
internal object WidgetRestoreState {
    private val lock = Any()

    private fun file(context: Context) = File(context.applicationContext.noBackupFilesDir, "widget_restore.json")
    private fun key(id: Int, world: Boolean) = "${if (world) "world" else "clock"}.$id"

    fun capture(context: Context) = synchronized(lock) {
        val stored = context.getSharedPreferences("noonlet_widgets", Context.MODE_PRIVATE)
        val originals = JSONObject()
        val identities = JSONObject()
        for (world in listOf(false, true)) {
            val prefix = if (world) "world." else "clock."
            val ids = stored.all.keys.asSequence().filter { it.startsWith(prefix) && it.endsWith(".saved") }
                .mapNotNull { it.removePrefix(prefix).removeSuffix(".saved").toIntOrNull() }
                .filter { it >= 0 }.toSet() + 0
            for (id in ids) {
                originals.put(key(id, world), SettingsCodec.encode(WidgetSettingsStore.loadForRestore(context, id, world)))
                if (id > 0) WidgetSettingsStore.existingIdentity(context, id, world)?.let {
                    identities.put(key(id, world), it)
                }
            }
        }
        write(context, JSONObject().put("originals", originals).put("identities", identities).put("targets", JSONArray()))
    }

    fun remap(context: Context, oldIds: IntArray, newIds: IntArray, world: Boolean) = synchronized(lock) {
        val target = file(context)
        val input = try {
            AtomicFile(target).openRead()
        } catch (_: FileNotFoundException) {
            capture(context)
            AtomicFile(target).openRead()
        }
        val state = input.use {
            if (target.length() > 2 * 1024 * 1024) throw IOException("Restore journal is too large")
            JSONObject(it.bufferedReader(Charsets.UTF_8).readText())
        }
        val originals = state.getJSONObject("originals")
        val targets = state.getJSONArray("targets").let { values ->
            (0 until values.length()).map { values.getString(it) }.toMutableSet()
        }
        val pairs = oldIds.zip(newIds).filter { (old, new) -> old > 0 && new > 0 }
        val snapshots = pairs.map { (old, _) ->
            WidgetSettings.defaults(world).apply {
                SettingsCodec.decode(this, originals.optJSONObject(key(old, world))
                    ?: originals.getJSONObject(key(0, world)))
            }
        }
        val identities = pairs.map { (old, _) ->
            state.optJSONObject("identities")?.optString(key(old, world))?.takeIf { it.isNotEmpty() }
        }
        pairs.forEach { (_, new) -> targets += key(new, world) }
        state.put("targets", JSONArray(targets.sorted()))
        write(context, state)
        pairs.forEachIndexed { index, (_, new) ->
            WidgetSettingsStore.save(context, new, world, snapshots[index])
            WidgetSettingsStore.restoreIdentity(context, new, world, identities[index])
        }
        // A previous provider may already have assigned an old ID to another restored widget.
        for (old in oldIds) if (old > 0 && key(old, world) !in targets)
            WidgetSettingsStore.delete(context, old, world)
    }

    private fun write(context: Context, state: JSONObject) {
        val file = AtomicFile(file(context))
        val output = file.startWrite()
        try {
            output.write(state.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: IOException) {
            file.failWrite(output)
            throw error
        }
    }
}

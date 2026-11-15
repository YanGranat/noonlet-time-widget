package app.noonlet

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.WeakHashMap
import org.json.JSONArray

internal class WidgetBackupAgent : BackupAgent() {
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput, newState: ParcelFileDescriptor) = Unit

    override fun onRestore(data: BackupDataInput, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    override fun onRestoreFinished() {
        super.onRestoreFinished()
        RestoredWidgetBindings.quarantine(this)
    }
}

/** Restored settings become active only after a launcher remaps or configures their IDs. */
internal object RestoredWidgetBindings {
    private val pending = ConcurrentHashMap<File, Set<String>>()
    private val files = WeakHashMap<Context, File>()
    private val diskLock = Any()

    private fun file(context: Context): File {
        val app = context.applicationContext
        synchronized(files) { files[app]?.let { return it } }
        val target = File(app.noBackupFilesDir, "restored_widgets.json")
        return synchronized(files) { files.getOrPut(app) { target } }
    }

    private fun key(id: Int, world: Boolean) = "${if (world) "world" else "clock"}.$id"

    private fun saved(context: Context): Set<String> =
        context.getSharedPreferences("noonlet_widgets", Context.MODE_PRIVATE).all.keys
            .filter { it.matches(Regex("(?:clock|world)\\.[1-9][0-9]*\\.saved")) }
            .map { it.removeSuffix(".saved") }.toSet()

    private fun entries(context: Context, target: File): Set<String> {
        pending[target]?.let { return it }
        return synchronized(diskLock) {
            pending.getOrPut(target) {
                if (listOf(target, File(target.path + ".bak"), File(target.path + ".new")).none { it.exists() }) emptySet()
                else runCatching {
                    require(target.length() <= 64 * 1024)
                    val data = JSONArray(AtomicFile(target).openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
                    (0 until data.length()).map {
                        val value = data.get(it)
                        require(value is String && value.matches(Regex("(?:clock|world)\\.[1-9][0-9]*")))
                        value
                    }.toSet()
                }.getOrElse { saved(context) }
            }
        }
    }

    fun contains(context: Context, id: Int, world: Boolean): Boolean =
        id > 0 && key(id, world) in entries(context, file(context))

    fun quarantine(context: Context) {
        WidgetRestoreState.capture(context)
        val target = file(context)
        synchronized(diskLock) {
            val restored = saved(context)
            write(target, restored)
            pending[target] = restored
        }
    }

    fun claim(context: Context, id: Int, world: Boolean) {
        val target = file(context)
        if (key(id, world) !in entries(context, target)) return
        synchronized(diskLock) {
            val remaining = entries(context, target) - key(id, world)
            runCatching { write(target, remaining) }.onSuccess { pending[target] = remaining }
        }
    }

    private fun write(target: File, values: Set<String>) {
        val file = AtomicFile(target)
        val output = file.startWrite()
        val payload = JSONArray(values.sorted()).toString().toByteArray(Charsets.UTF_8)
        try {
            output.write(payload)
            file.finishWrite(output)
            if (!target.isFile || !target.readBytes().contentEquals(payload))
                throw java.io.IOException("Widget restore state was not committed")
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
}

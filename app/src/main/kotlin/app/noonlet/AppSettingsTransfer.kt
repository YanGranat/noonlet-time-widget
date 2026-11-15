package app.noonlet

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class AppSettingsTransfer(private val activity: Activity, private val imported: () -> Unit) {
    private var exportText: String? = null
    private var importText: String? = null
    private var pendingUri: String? = null
    private var operation = 0
    private var choosing = false
    private var resumed = false
    private var applying = false
    private var task: Future<*>? = null
    private var confirmation: AlertDialog? = null
    private val handler = Handler(Looper.getMainLooper())

    fun export() {
        if (choosing || task != null || exportText != null) return
        val contents = attempt { SettingsArchive.encode(SettingsArchive.capture(activity)) }
        exportText = contents.getOrNull()
        if (exportText == null) { toast(R.string.settings_file_error); return }
        launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "Noonlet-settings.json")
        }, EXPORT)
    }

    fun import() {
        if (choosing || task != null || importText != null) return
        launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }, IMPORT)
    }

    private fun launch(intent: Intent, request: Int) {
        choosing = true
        operation = request
        try { activity.startActivityForResult(intent, request) }
        catch (_: android.content.ActivityNotFoundException) {
            choosing = false
            exportText = null
            toast(R.string.settings_file_error)
        }
    }

    fun result(request: Int, result: Int, data: Intent?): Boolean {
        if (request != IMPORT && request != EXPORT) return false
        choosing = false
        val uri = data?.data
        if (result != Activity.RESULT_OK || uri == null || uri.scheme != "content") {
            exportText = null
            operation = 0
            return true
        }
        operation = request
        pendingUri = uri.toString()
        processPending()
        return true
    }

    fun resume() {
        resumed = true
        processPending()
    }

    private fun processPending() {
        val uri = pendingUri
        if (uri != null && task == null && importText == null) {
            val resolver = activity.applicationContext.contentResolver
            val output = if (operation == EXPORT) exportText else null
            task = IO.submit {
                val loaded = attempt {
                    if (output != null) {
                        resolver.openOutputStream(Uri.parse(uri), "wt")!!.use { it.write(output.toByteArray(Charsets.UTF_8)) }
                        null
                    } else resolver.openInputStream(Uri.parse(uri))!!.use { SettingsArchive.encode(SettingsArchive.read(it)) }
                }
                handler.post {
                    task = null
                    if (activity.isDestroyed) return@post
                    pendingUri = null
                    exportText = null
                    if (loaded.isFailure) toast(R.string.settings_file_error)
                    else if (output != null) toast(R.string.settings_exported)
                    else { importText = loaded.getOrNull(); showConfirmation() }
                }
            }
        }
        showConfirmation()
    }

    fun pause() { resumed = false }

    private fun showConfirmation() {
        if (!resumed || confirmation?.isShowing == true || importText == null) return
        confirmation = AppDialogBuilder(activity)
            .setTitle(AppLocales.text(activity, R.string.import_settings))
            .setMessage(AppLocales.text(activity, R.string.import_settings_message))
            .setNegativeButton(AppLocales.text(activity, R.string.cancel)) { _, _ -> importText = null }
            .setPositiveButton(AppLocales.text(activity, R.string.import_settings_confirm)) { _, _ ->
                val text = importText ?: return@setPositiveButton
                importText = null
                val context = activity.applicationContext
                applying = true
                task = IO.submit {
                    val result = attempt {
                        SettingsArchive.restore(context, SettingsArchive.decode(text))
                        BaseWidgetProvider.updateAll(context, false)
                        BaseWidgetProvider.updateAll(context, true)
                        WeatherRefreshScheduler.reconcile(context)
                        AlarmRefreshScheduler.reconcile(context)
                    }
                    handler.post {
                        task = null
                        applying = false
                        if (activity.isDestroyed) return@post
                        if (result.isSuccess) { imported(); toast(R.string.settings_imported) }
                        else toast(R.string.settings_file_error)
                    }
                }
            }.show().also { it.setOnCancelListener { importText = null } }
    }

    fun save(out: Bundle) {
        out.putString("transfer_export", exportText)
        out.putString("transfer_import", importText)
        out.putString("transfer_uri", pendingUri)
        out.putInt("transfer_operation", operation)
        out.putBoolean("transfer_choosing", choosing)
    }

    fun restore(state: Bundle?) {
        exportText = state?.getString("transfer_export")
        importText = state?.getString("transfer_import")
        pendingUri = state?.getString("transfer_uri")
        operation = state?.getInt("transfer_operation") ?: 0
        choosing = state?.getBoolean("transfer_choosing") ?: false
    }

    fun close() {
        if (!applying && operation != EXPORT) task?.cancel(true)
        task = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun toast(resource: Int) = Toast.makeText(activity, AppLocales.text(activity, resource), Toast.LENGTH_LONG).show()
    private fun <T> attempt(action: () -> T): Result<T> =
        try { Result.success(action()) } catch (error: Exception) { Result.failure(error) }

    companion object {
        internal const val IMPORT = 5350
        internal const val EXPORT = 5351
        private val IO = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "settings-transfer-io").apply { isDaemon = true } }
    }
}

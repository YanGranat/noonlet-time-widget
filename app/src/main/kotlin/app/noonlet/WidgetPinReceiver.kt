package app.noonlet

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.widget.RemoteViews
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

class WidgetPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLocales.refreshLanguage(context)
        val id =
            intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID,
            )
        val snapshot = intent.getBundleExtra(SNAPSHOT) ?: return
        if (id <= 0) return
        val world = intent.getBooleanExtra(WORLD, false)
        val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(id) ?: return
        if (!BaseWidgetProvider.owns(context, info.provider, world)) return
        if (!WidgetSettingsStore.hasOwnSettings(context, id, world)) {
            val settings = WidgetSettings.defaults(world)
            WidgetSettingsStore.restoreEditorState(context, id, world, settings, snapshot)
            WidgetSettingsStore.save(context, id, world, settings)
        }
        val editor = EditorPreferences(context)
        editor.selectTarget(id, world, selectTab = false)
        BaseWidgetProvider.updateWidget(context, id, world)
        ImportedWidgetConfigurations.get(context, intent.getStringExtra(EditorIntents.RESTORED_CONFIGURATION))
            ?.takeIf { it.world == world }?.let { ImportedWidgetConfigurations.remove(context, it.token) }
        if (!world) WeatherRefreshScheduler.reconcile(context)
        if (!world) AlarmRefreshScheduler.reconcile(context)
        intent
            .getStringExtra(OWNER)
            ?.takeIf { it.isNotBlank() }
            ?.let { owner ->
                editor.recordPinCompletion(id, world, owner)
            }
        notifyEditors()
    }

    companion object {
        private const val SNAPSHOT = "widget_snapshot"
        private const val WORLD = "world"
        private const val OWNER = "editor_owner"
        private val listeners: MutableSet<Runnable> = Collections.newSetFromMap(WeakHashMap())

        internal fun preview(context: Context, world: Boolean, settings: WidgetSettings,
            size: WidgetSize): RemoteViews =
            WidgetRenderer.preview(context, -1, world, settings, size.width.toFloat(), size.height.toFloat())
                .apply { setInt(R.id.widget_root, "setBackgroundColor", if (world) -0xcfcfc9 else -0x707e5b) }

        fun observe(listener: Runnable) {
            synchronized(listeners) { listeners += listener }
        }

        fun unobserve(listener: Runnable) {
            synchronized(listeners) { listeners -= listener }
        }

        private fun notifyEditors() {
            val current = synchronized(listeners) { listeners.toTypedArray() }
            current.forEach(Runnable::run)
        }

        internal fun request(
            context: Context,
            world: Boolean,
            settings: WidgetSettings,
            preview: RemoteViews,
            editorOwner: String? = null,
            size: WidgetSize? = null,
            restoredToken: String? = null,
        ): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            if (!manager.isRequestPinAppWidgetSupported) return false
            val snapshot = Bundle().apply { SettingsCodec.writeEditorState(settings, this) }
            val result =
                Intent(context, WidgetPinReceiver::class.java)
                    .setData(Uri.parse("noonlet://pin/${UUID.randomUUID()}"))
                    .putExtra(WORLD, world)
                    .putExtra(SNAPSHOT, snapshot)
                    .putExtra(OWNER, editorOwner)
                    .putExtra(EditorIntents.RESTORED_CONFIGURATION, restoredToken)
            val callback =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    result,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE,
                )
            return try {
                val extras =
                    Bundle().apply {
                        putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, preview)
                    }
                manager
                    .requestPinAppWidget(
                        BaseWidgetProvider.component(context, world, size ?: WidgetSize.TWO_BY_ONE),
                        extras,
                        callback,
                    )
                    .also {
                        if (!it) callback.cancel()
                    }
            } catch (_: IllegalStateException) {
                callback.cancel()
                false
            } catch (_: SecurityException) {
                callback.cancel()
                false
            }
        }
    }
}

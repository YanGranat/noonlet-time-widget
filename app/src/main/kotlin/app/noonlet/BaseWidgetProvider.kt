package app.noonlet

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.Bundle

/** Provider component names are persisted by launchers. */
abstract class BaseWidgetProvider(private val world: Boolean) : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        AppLocales.refreshLanguage(context)
        updateBatch(context, ids, world)
        if (!world) WeatherRefreshScheduler.reconcile(context)
        if (!world) AlarmRefreshScheduler.reconcile(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        options: Bundle,
    ) {
        AppLocales.refreshLanguage(context)
        updateBatch(context, intArrayOf(id), world)
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        for (id in ids) {
            WidgetInstances.forget(context, id)
            WidgetSettingsStore.delete(context, id, world)
        }
        if (!world) WeatherRefreshScheduler.reconcile(context)
        if (!world) AlarmRefreshScheduler.reconcile(context)
    }

    override fun onRestored(context: Context, oldIds: IntArray, newIds: IntArray) {
        AppLocales.refreshLanguage(context)
        WidgetRestoreState.remap(context, oldIds, newIds, world)
        val manager = AppWidgetManager.getInstance(context)
        for (id in newIds) {
            val options =
                Bundle().apply {
                    putBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED, true)
                }
            manager.updateAppWidgetOptions(id, options)
        }
        updateAll(context, world)
        if (!world) WeatherRefreshScheduler.reconcile(context)
        if (!world) AlarmRefreshScheduler.reconcile(context)
    }

    companion object {
        internal fun component(context: Context, world: Boolean, size: WidgetSize = WidgetSize.TWO_BY_ONE) =
            ComponentName(context, if (world) size.worldProvider else size.clockProvider)

        fun owns(context: Context, provider: ComponentName?, world: Boolean): Boolean =
            provider != null && WidgetSize.entries.any { component(context, world, it) == provider }

        fun boundIds(context: Context, world: Boolean): IntArray {
            val manager = AppWidgetManager.getInstance(context)
            return WidgetSize.entries.flatMap {
                manager.getAppWidgetIds(component(context, world, it)).asIterable()
            }.distinct().sorted().toIntArray()
        }

        fun updateWidget(context: Context, id: Int, world: Boolean) {
            val views =
                if (world) WidgetRenderer.world(context, id) else WidgetRenderer.clock(context, id)
            AppWidgetManager.getInstance(context).updateAppWidget(id, views)
            WidgetInstances.remember(context, id, world)
        }

        internal fun updateBatch(context: Context, ids: IntArray, world: Boolean): Boolean {
            var complete = true
            for (id in ids) try {
                updateWidget(context, id, world)
            } catch (_: Exception) {
                complete = false
            }
            return complete
        }

        fun updateAll(context: Context, world: Boolean): Boolean =
            updateBatch(context, boundIds(context, world), world)
    }
}

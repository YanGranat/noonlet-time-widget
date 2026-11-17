package app.noonlet

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import java.util.WeakHashMap

/** A completion marker in launcher storage survives clearing application data. */
internal object WidgetInstances {
    private const val CONFIGURED = "app.noonlet.CONFIGURED"
    private val remembered = WeakHashMap<Context, MutableSet<Int>>()

    fun manageableIds(context: Context, bound: IntArray, world: Boolean): IntArray {
        val manager = AppWidgetManager.getInstance(context)
        return bound.filter { id ->
            if (id <= 0 || WidgetSettingsStore.pendingConfiguration(context, id, world) ||
                RestoredWidgetBindings.contains(context, id, world)) false
            else if (WidgetSettingsStore.hasOwnSettings(context, id, world)) {
                remember(context, id, world)
                true
            } else manager.getAppWidgetOptions(id).getBoolean(CONFIGURED, false)
        }.toIntArray()
    }

    fun remember(context: Context, id: Int, world: Boolean) {
        if (id <= 0 || !WidgetSettingsStore.hasOwnSettings(context, id, world)) return
        val app = context.applicationContext
        if (synchronized(remembered) { remembered[app]?.contains(id) == true }) return
        val manager = AppWidgetManager.getInstance(context)
        if (!BaseWidgetProvider.owns(context, manager.getAppWidgetInfo(id)?.provider, world)) return
        WidgetSettingsStore.identity(context, id, world)
        if (!manager.getAppWidgetOptions(id).getBoolean(CONFIGURED, false))
            manager.updateAppWidgetOptions(id, Bundle().apply { putBoolean(CONFIGURED, true) })
        synchronized(remembered) { remembered.getOrPut(app) { mutableSetOf() }.add(id) }
    }

    fun forget(context: Context, id: Int) {
        synchronized(remembered) { remembered[context.applicationContext]?.remove(id) }
    }
}

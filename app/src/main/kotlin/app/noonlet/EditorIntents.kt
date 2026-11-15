package app.noonlet

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri

internal object EditorIntents {
    const val CREATE_WIDGET = "app.noonlet.action.CREATE_WIDGET"
    const val FROM_WIDGETS = "from_widget_list"
    const val PIN_SNAPSHOT = "pin_snapshot"
    const val PIN_SIZE = "pin_size"
    const val RESTORED_CONFIGURATION = "restored_configuration"

    fun edit(context: Context, id: Int, world: Boolean): Intent =
        Intent(context, MainActivity::class.java)
            .setData(Uri.parse("noonlet://edit/$world/$id"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .putExtra("world", world)

    fun create(context: Context, world: Boolean): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(CREATE_WIDGET)
            .putExtra("world", world)
            .putExtra(FROM_WIDGETS, true)

    fun isDirectEdit(intent: Intent): Boolean =
        intent.data?.let { it.scheme == "noonlet" && it.host == "edit" } == true

    fun handoff(context: Context, world: Boolean, settings: WidgetSettings, size: WidgetSize,
        restoredToken: String? = null): Intent =
        create(context, world)
            .setComponent(ComponentName(context, WidgetPinActivity::class.java))
            .putExtra(PIN_SNAPSHOT, SettingsCodec.snapshot(settings))
            .putExtra(PIN_SIZE, size.ordinal)
            .putExtra(RESTORED_CONFIGURATION, restoredToken)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION)
}

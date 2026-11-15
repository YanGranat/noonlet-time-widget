package app.noonlet

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import java.util.Locale

object TapActions {
    const val ALARM_UNAVAILABLE = "app.noonlet.ALARM_UNAVAILABLE"

    data class Choice(val value: String, val label: Int, val summaryLabel: Int = label)

    val builtinChoices =
        listOf(
            Choice("clock", R.string.system_alarm, R.string.system_alarm_clock),
            Choice("calendar", R.string.calendar_app, R.string.calendar),
            Choice("settings", R.string.widget_settings),
            Choice("none", R.string.no_action, R.string.do_nothing),
        )

    private const val APP_PREFIX = "app:"

    fun appAction(component: ComponentName): String = APP_PREFIX + component.flattenToString()

    fun isCustomApp(action: String?): Boolean = action?.startsWith(APP_PREFIX) == true

    private fun appComponent(action: String?): ComponentName? =
        if (isCustomApp(action))
            ComponentName.unflattenFromString(checkNotNull(action).removePrefix(APP_PREFIX))
        else null

    fun pending(context: Context, id: Int, world: Boolean, action: String?): PendingIntent? {
        if (action == "none") return null
        var intent =
            when {
                action == "calendar" ->
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(
                            "content://com.android.calendar/time/${System.currentTimeMillis()}",
                        ),
                    )
                action == "clock" || isCustomApp(action) ->
                    Intent(context, WidgetTapActivity::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                        .putExtra("world", world)
                        .putExtra(WidgetTapActivity.ACTION, action)
                else -> editor(context, id, world)
            }
        if (action != "clock" && !isCustomApp(action) &&
            intent.resolveActivity(context.packageManager) == null)
            intent = editor(context, id, world)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val code = 31 * id + (if (world) 1 else 0) + (action?.hashCode() ?: 0)
        return PendingIntent.getActivity(
            context,
            code,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    internal fun customTarget(context: Context, id: Int, world: Boolean, action: String?): Intent {
        val target = appComponent(action)
        if (target != null) {
            try {
                val info = context.packageManager.getActivityInfo(target, 0)
                if (accessibleActivity(context, info))
                    return Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setComponent(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return editor(context, id, world)
    }

    internal fun accessibleActivity(context: Context, activity: ActivityInfo?): Boolean {
        if (
            activity == null ||
                activity.packageName == null ||
                activity.name == null ||
                !activity.enabled ||
                !activity.exported
        )
            return false
        val application =
            activity.applicationInfo
                ?: try {
                    context.packageManager.getApplicationInfo(activity.packageName, 0)
                } catch (_: PackageManager.NameNotFoundException) {
                    return false
                }
        if (!application.enabled) return false
        return activity.permission.isNullOrEmpty() ||
            context.packageManager.checkPermission(activity.permission, context.packageName) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun editor(context: Context, id: Int, world: Boolean) =
        EditorIntents.edit(context, id, world)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)

    fun label(context: Context, action: String?) =
        label(context, action, AppLocales.resourceLocale(context))

    fun label(context: Context, action: String?, locale: Locale): String {
        builtinChoices
            .firstOrNull { it.value == action }
            ?.let {
                return AppLocales.text(context, locale, it.summaryLabel)
            }
        if (isCustomApp(action)) {
            val component = appComponent(action)
            if (component != null)
                try {
                    return context.packageManager
                        .getActivityInfo(component, 0)
                        .loadLabel(context.packageManager)
                        .toString()
                } catch (_: PackageManager.NameNotFoundException) {}
        }
        return AppLocales.text(context, locale, R.string.choose_app)
    }
}

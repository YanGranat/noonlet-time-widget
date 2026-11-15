package app.noonlet

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.AlarmClock

internal object AlarmClockApps {
    private val knownPackages = listOf("com.android.deskclock", "com.google.android.deskclock")

    fun ownsAlarm(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        if (packageName in knownPackages) return true
        return handlers(context, Intent(AlarmClock.ACTION_SHOW_ALARMS).setPackage(packageName))
            .any { accessible(context, it) }
    }

    fun targets(context: Context): List<Intent> {
        val manager = context.packageManager
        val show = Intent(AlarmClock.ACTION_SHOW_ALARMS)
        val handlers = handlers(context, show).filter { accessible(context, it) }
        val preferred = manager.resolveActivity(show, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        val ordered = (handlers.filter { it.packageName == preferred?.packageName && it.name == preferred?.name } +
            knownPackages.flatMap { name -> handlers.filter { it.packageName == name } } + handlers)
            .distinctBy { ComponentName(it.packageName, it.name) }
        val targets = ordered.map {
            Intent(show).setComponent(ComponentName(it.packageName, it.name))
        }.toMutableList()
        for (packageName in knownPackages) {
            val launch = manager.getLaunchIntentForPackage(packageName) ?: continue
            val target = launch.component ?: continue
            try {
                if (accessible(context, manager.getActivityInfo(target, 0))) targets += launch
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return targets
    }

    private fun handlers(context: Context, intent: Intent): List<ActivityInfo> = try {
        context.packageManager.queryIntentActivities(intent, 0).mapNotNull { it.activityInfo }
    } catch (_: SecurityException) {
        emptyList()
    }

    private fun accessible(context: Context, activity: ActivityInfo): Boolean =
        (activity.packageName in knownPackages || activity.applicationInfo?.flags?.let {
            it and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } == true) && TapActions.accessibleActivity(context, activity)
}

package app.noonlet

import android.app.AlarmManager
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

internal object SystemAlarmClock {
    fun triggerTime(context: Context): Long? {
        val alarm = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
        val platform = alarm?.takeIf { it.triggerTime > System.currentTimeMillis() &&
            AlarmClockApps.ownsAlarm(context, it.showIntent?.creatorPackage) }?.triggerTime
        return DeviceAlarmClock.triggerTime(context, platform)
    }

    fun observeDevice(context: Context, changed: () -> Unit): AutoCloseable {
        val resolver = context.applicationContext.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = changed()
        }
        try {
            resolver.registerContentObserver(DeviceAlarmClock.uri, false, observer)
        } catch (_: SecurityException) {
            return AutoCloseable { }
        }
        return AutoCloseable { resolver.unregisterContentObserver(observer) }
    }

    fun format(context: Context, pattern: String, triggerTime: Long?): String {
        if (triggerTime == null) return AppLocales.text(context, R.string.no_alarm)
        return SimpleDateFormat(pattern, AppLocales.appLocale(context)).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(triggerTime))
    }
}

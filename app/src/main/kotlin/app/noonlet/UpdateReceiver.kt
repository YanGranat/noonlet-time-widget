package app.noonlet

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action in SYSTEM_ACTIONS) {
            updateWidgets(context, action == Intent.ACTION_LOCALE_CHANGED)
            WeatherRefreshScheduler.reconcile(context)
            AlarmRefreshScheduler.reconcile(context)
        }
    }

    companion object {
        private val SYSTEM_ACTIONS =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_DATE_CHANGED,
                Intent.ACTION_LOCALE_CHANGED,
                AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED,
            )

        fun updateForLanguage(context: Context) = updateWidgets(context, true)

        private fun updateWidgets(context: Context, languageOnly: Boolean) {
            AppLocales.refreshLanguage(context)
            val signature = AppLocales.widgetLanguageSignature(context)
            if (languageOnly && AppLocales.widgetLanguageDelivered(context, signature)) return
            val clocks = BaseWidgetProvider.updateAll(context, false)
            val world = BaseWidgetProvider.updateAll(context, true)
            if (clocks && world) AppLocales.markWidgetLanguageDelivered(context, signature)
        }
    }
}

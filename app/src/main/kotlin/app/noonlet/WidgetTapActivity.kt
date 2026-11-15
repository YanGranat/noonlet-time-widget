package app.noonlet

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ActivityNotFoundException
import android.os.Bundle
import android.content.Intent

/** Resolves a saved launcher target at the time the widget is tapped. */
internal class WidgetTapActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0).coerceAtLeast(0)
        val world = intent.getBooleanExtra("world", false)
        val action = intent.getStringExtra(ACTION)
        val targets = if (action == "clock") AlarmClockApps.targets(this)
            else listOf(TapActions.customTarget(this, id, world, action))
        for (target in targets) {
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        startActivity(EditorIntents.edit(this, id, world).apply {
            if (action == "clock") putExtra(TapActions.ALARM_UNAVAILABLE, true)
        })
        finish()
    }

    companion object {
        const val ACTION = "app.noonlet.CUSTOM_TAP_ACTION"
    }
}

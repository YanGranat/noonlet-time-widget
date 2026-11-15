package app.noonlet

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast

internal object SettingsNavigation {
    fun open(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        unavailable(context)
    } catch (_: SecurityException) {
        unavailable(context)
    }

    private fun unavailable(context: Context): Boolean {
        Toast.makeText(context, AppLocales.text(context, R.string.weather_settings_unavailable),
            Toast.LENGTH_LONG).show()
        return false
    }
}

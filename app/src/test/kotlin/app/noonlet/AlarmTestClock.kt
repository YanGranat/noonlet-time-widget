package app.noonlet

import android.content.Context
import android.app.PendingIntent
import android.content.Intent
import org.robolectric.Shadows

internal fun clockAlarm(context: Context, requestCode: Int, intent: Intent, flags: Int): PendingIntent =
    PendingIntent.getBroadcast(context, requestCode, intent, flags).apply {
        Shadows.shadowOf(this).setCreatorPackage("com.android.deskclock")
    }

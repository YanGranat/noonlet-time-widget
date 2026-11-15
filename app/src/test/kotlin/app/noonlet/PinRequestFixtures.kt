package app.noonlet

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.os.Bundle
import java.util.ArrayList
import org.junit.Assert.*
import org.robolectric.Shadows
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAppWidgetManager

object PinRequestFixtures {
    private val home = ComponentName("example.launcher", "example.launcher.Home")

    fun installHome(context: Context) {
        Shadows.shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    name = home.className
                    packageName = home.packageName
                    exported = true
                    applicationInfo = ApplicationInfo().apply { packageName = home.packageName }
                }
            },
        )
    }

    fun assertReturnedToLauncher(activity: android.app.Activity) {
        val shadow = Shadows.shadowOf(activity)
        assertTrue(activity.isFinishing)
        assertFalse(shadow.isTaskMovedToBack)
        val resumed = checkNotNull(shadow.nextStartedActivity)
        assertEquals(home, resumed.component)
        assertNull(resumed.action)
        assertNull(resumed.categories)
        assertTrue(resumed.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(resumed.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertNull(shadow.nextStartedActivity)
    }

    class CapturedPin(val provider: ComponentName, val extras: Bundle, val callback: PendingIntent)

    @Implements(AppWidgetManager::class)
    class CapturedPins : ShadowAppWidgetManager() {
        @Implementation
        protected fun updateAppWidgetProviderInfo(provider: ComponentName, metadataKey: String?) {
            metadataRequests.add(provider to metadataKey)
        }

        @Implementation protected override fun isRequestPinAppWidgetSupported(): Boolean = supported

        @Implementation
        protected override fun requestPinAppWidget(
            provider: ComponentName?,
            extras: Bundle?,
            callback: PendingIntent?,
        ): Boolean {
            requests.add(
                CapturedPin(
                    requireNotNull(provider),
                    requireNotNull(extras),
                    requireNotNull(callback),
                ),
            )
            failure?.let { throw it }
            return supported && accepted
        }

        companion object {
            val requests: MutableList<CapturedPin> = ArrayList()
            val metadataRequests = mutableListOf<Pair<ComponentName, String?>>()
            var supported: Boolean = false
            var accepted: Boolean = true
            var failure: RuntimeException? = null
        }
    }
}

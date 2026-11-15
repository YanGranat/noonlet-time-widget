package app.noonlet

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36])
class SettingsNavigationTest {
    @Test
    fun unavailableAndRestrictedSettingsReturnAnExplanationWithoutCrashing() {
        val app = RuntimeEnvironment.getApplication() as Context
        AppLocales.setLanguage(app, "en")
        for (error in listOf(ActivityNotFoundException(), SecurityException())) {
            val context = object : ContextWrapper(app) {
                override fun startActivity(intent: Intent) { throw error }
            }
            assertFalse(SettingsNavigation.open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)))
            assertEquals(AppLocales.text(app, R.string.weather_settings_unavailable), ShadowToast.getTextOfLatestToast())
        }
    }
}

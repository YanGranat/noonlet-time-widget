package app.noonlet

import android.os.LocaleList
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLocaleManager

@Implements(className = "android.app.LocaleManager", minSdk = 33)
class CountedLocaleManager : ShadowLocaleManager() {
    @Implementation
    override fun getSystemLocales(): LocaleList {
        systemReads++
        return super.getSystemLocales()
    }

    @Implementation
    override fun getApplicationLocales(packageName: String): LocaleList {
        reads++
        return super.getApplicationLocales(packageName)
    }

    companion object {
        var reads: Int = 0
        var systemReads: Int = 0
    }
}

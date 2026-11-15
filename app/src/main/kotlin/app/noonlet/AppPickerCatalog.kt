package app.noonlet

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import java.text.Collator
import java.util.Collections
import java.util.Locale

object AppPickerCatalog {
    class Entry(val action: String, val label: String)

    fun load(manager: PackageManager, locale: Locale): List<Entry> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val collator = Collator.getInstance(locale)
        val resolved =
            manager
                .queryIntentActivities(launcher, 0)
                .mapNotNull { app ->
                    val info = app.activityInfo ?: return@mapNotNull null
                    if (!info.enabled || !info.exported) return@mapNotNull null
                    val component = ComponentName(info.packageName, info.name)
                    Entry(
                        TapActions.appAction(component),
                        app.loadLabel(manager)?.toString() ?: info.name,
                    )
                }
                .sortedWith { first, second -> collator.compare(first.label, second.label) }
                .distinctBy { it.action }
        return Collections.unmodifiableList(resolved)
    }
}

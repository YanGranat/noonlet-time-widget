package app.noonlet

import android.graphics.Typeface
import android.widget.LinearLayout

internal class AboutActivity : AppScreenActivity() {
    override val titleResource = R.string.about_app

    override fun buildContent(parent: LinearLayout) {
        val details = card(parent).apply { setPadding(dp(20), dp(24), dp(20), dp(24)) }
        details.addView(label(tr(R.string.app_name), 26f).apply {
            tag = "about-name"
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(-1, -2))
        details.addView(label(tr(R.string.app_description), 16f, palette.muted).apply {
            tag = "about-description"
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        @Suppress("DEPRECATION")
        val version = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        details.addView(label(tr(R.string.app_version_format, version), 14f, palette.muted).apply {
            tag = "about-version"
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
    }
}

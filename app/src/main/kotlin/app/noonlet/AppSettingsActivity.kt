package app.noonlet

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import java.util.Locale

internal class AppSettingsActivity : AppScreenActivity() {
    override val titleResource = R.string.app_settings
    private val transfer = AppSettingsTransfer(this, ::settingsImported)

    override fun onCreate(state: Bundle?) {
        transfer.restore(state)
        super.onCreate(state)
    }

    override fun onResume() { super.onResume(); transfer.resume() }
    override fun onPause() { transfer.pause(); super.onPause() }
    override fun onDestroy() { transfer.close(); super.onDestroy() }
    override fun onSaveInstanceState(out: Bundle) { transfer.save(out); super.onSaveInstanceState(out) }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!transfer.result(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
    }

    private fun settingsImported() {
        AppTheme.applyToWindow(this)
        if (!renderIfPresentationChanged()) onContentVisible()
    }

    override fun buildContent(parent: LinearLayout) {
        val preferences = AppPreferences(this)
        val appearance = card(parent)
        row(appearance, tr(R.string.application_language), languageName(), "language-trigger") {
            chooseLanguage()
        }
        divider(appearance)
        row(appearance, tr(R.string.app_theme), themeName(preferences.themeMode()), "app-theme") {
            chooseTheme()
        }
        val files = card(parent)
        row(files, tr(R.string.export_settings), tagValue = "export-settings") { transfer.export() }
        divider(files)
        row(files, tr(R.string.import_settings), tagValue = "import-settings") { transfer.import() }
        val information = card(parent)
        row(information, tr(R.string.data_privacy), tagValue = "data-privacy") {
            startActivity(Intent(this, DataPrivacyActivity::class.java))
        }
        divider(information)
        row(information, tr(R.string.about_app), tagValue = "about-app") {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    override fun onContentVisible() {
        body.findViewWithTag<View>("language-trigger")?.let {
            updateRow(it, tr(R.string.application_language), languageName())
        }
        body.findViewWithTag<View>("app-theme")?.let {
            updateRow(it, tr(R.string.app_theme), themeName(AppPreferences(this).themeMode()))
        }
    }

    private fun chooseTheme() {
        val modes = ThemeMode.entries
        AppDialogBuilder(this)
            .setTitle(tr(R.string.app_theme))
            .setSingleChoiceItems(modes.map(::themeName).toTypedArray(),
                modes.indexOf(AppPreferences(this).themeMode())) { dialog, index ->
                dialog.dismiss()
                applyTheme(modes[index])
            }.show()
    }

    private fun applyTheme(mode: ThemeMode) {
        if (AppPreferences(this).setThemeMode(mode)) {
            AppTheme.applyToWindow(this)
            if (!renderIfPresentationChanged()) onContentVisible()
        }
    }

    private fun chooseLanguage() {
        LanguagePickerDialog.show(this, AppLocales.language(this)) { selected ->
            if (selected == AppLocales.language(this)) return@show
            AppLocales.setLanguage(this, selected)
            if (Build.VERSION.SDK_INT < 33) UpdateReceiver.updateForLanguage(this)
            if (!renderIfPresentationChanged()) onContentVisible()
        }
    }

    private fun languageName(): String {
        val selected = AppLocales.language(this)
        if (!AppLocales.isSupported(selected)) return tr(R.string.system_language)
        val locale = Locale.forLanguageTag(selected)
        return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
    }

    private fun themeName(mode: ThemeMode) = tr(when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    })

}

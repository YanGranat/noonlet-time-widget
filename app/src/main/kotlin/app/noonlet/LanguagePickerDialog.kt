package app.noonlet

import android.app.Activity
import android.app.AlertDialog
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import java.text.Collator
import java.util.Locale

internal object LanguagePickerDialog {
    fun show(activity: Activity, currentLanguage: String, onSelected: (String) -> Unit): AlertDialog {
        val locale = AppLocales.resourceLocale(activity)
        val current = if (AppLocales.isSupported(currentLanguage))
            Locale.forLanguageTag(currentLanguage.replace('_', '-')).language else "system"
        val collator = Collator.getInstance(Locale.ROOT)
        val languages = AppLocales.supportedTags.sortedWith { first, second ->
            collator.compare(languageName(first), languageName(second))
        }
        val values = listOf("system") + languages
        val system = AppLocales.text(activity, locale, R.string.system_language)
        val phone = titleCase(AppLocales.phoneLocale(activity).getDisplayLanguage(locale))
        val automatic = SpannableString("$system\n$phone").apply {
            setSpan(RelativeSizeSpan(13f / 16f), system.length + 1, length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(AppTheme.palette(activity).muted), system.length + 1,
                length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val labels = listOf<CharSequence>(automatic) + languages.map(::languageName)
        return AppDialogBuilder(activity)
            .setTitle(AppLocales.text(activity, locale, R.string.application_language))
            .setSingleChoiceItems(labels.toTypedArray(), values.indexOf(current)) { source, index ->
                source.dismiss()
                onSelected(values[index])
            }.show()
    }

    private fun languageName(tag: String): String {
        val locale = Locale.forLanguageTag(tag)
        return titleCase(locale.getDisplayLanguage(locale))
    }

    private fun titleCase(name: String): String {
        if (name.isEmpty()) return ""
        val first = name.codePointAt(0)
        return StringBuilder().appendCodePoint(Character.toTitleCase(first))
            .append(name.substring(Character.charCount(first))).toString()
    }
}

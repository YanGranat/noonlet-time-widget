package app.noonlet

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.CheckedTextView
import android.widget.TextView
import java.util.Locale
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LanguagePickerDialogTest {
    private val values = listOf("system", "de", "en", "es", "fr", "it", "pl", "pt", "ru", "uk")
    private val autonyms = listOf("Deutsch", "English", "Español", "Français", "Italiano",
        "Polski", "Português", "Русский", "Українська")

    @Before fun prepare() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        context.getSharedPreferences("noonlet_app", 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
    }

    @Test fun naturalLanguageOrderAndTheSystemChoiceHaveOneCheckedValue() {
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            for (current in values + listOf("invalid", "ru_RU")) {
                val dialog = LanguagePickerDialog.show(activity, current) { fail("Opening is not a selection") }
                try {
                    layout(dialog)
                    val list = dialog.listView
                    val expected = when (current) { "invalid" -> 0; "ru_RU" -> 8; else -> values.indexOf(current) }
                    assertEquals(values.size, list.count)
                    assertEquals(expected, list.checkedItemPosition)
                    assertEquals(autonyms, (1 until list.count).map { list.adapter.getItem(it).toString() })
                    assertEquals("System language\nEnglish", list.adapter.getItem(0).toString())
                    for (index in values.indices) {
                        val row = list.adapter.getView(index, null, list) as CheckedTextView
                        val info = row.createAccessibilityNodeInfo()
                        assertTrue(info.isCheckable)
                        assertEquals(index == expected, info.isChecked)
                        IconAssertions.assertChoice(row.compoundDrawablesRelative[2], index == expected)
                    }
                    assertNotNull(dialog.window!!.decorView.findViewWithTag<View>("dialog-header"))
                    assertTrue(dialog.window!!.attributes.dimAmount > 0f)
                    assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_POSITIVE).visibility)
                    assertEquals(View.GONE, dialog.getButton(AlertDialog.BUTTON_NEGATIVE).visibility)
                } finally { dialog.dismiss() }
            }
        }
    }

    @Test fun selectionDismissesBeforeTheCallbackAndRejectsStaleClicks() {
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val chosen = mutableListOf<String>()
            lateinit var dialog: AlertDialog
            dialog = LanguagePickerDialog.show(controller.get(), "system") {
                assertFalse(dialog.isShowing)
                chosen += it
            }
            layout(dialog)
            DialogTestFixtures.choose(dialog, 1)
            assertEquals(listOf("de"), chosen)
            DialogTestFixtures.choose(dialog, 8)
            assertEquals(listOf("de"), chosen)
            assertEquals("en", AppLocales.language(controller.get()))
        }
    }

    @Test fun closingWithTheHeaderBackOrOutsideDoesNotSelect() {
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            for (method in 0..2) {
                val dialog = LanguagePickerDialog.show(controller.get(), "en") { fail("Cancellation must not select") }
                val root = layout(dialog)
                when (method) {
                    0 -> root.findViewWithTag<View>("dialog-close").performClick()
                    1 -> {
                        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                        dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
                    }
                    else -> {
                        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            val event = MotionEvent.obtain(0, 0, action, -100f, -100f, 0)
                            try { dialog.onTouchEvent(event) } finally { event.recycle() }
                        }
                    }
                }
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertFalse("Cancellation method $method must close the dialog", dialog.isShowing)
            }
        }
    }

    @Test fun localizedAutonymsRemainReadableAndTheLastChoiceCanBeReachedAtLargeFont() {
        val previous = RuntimeEnvironment.getFontScale()
        RuntimeEnvironment.setFontScale(1.3f)
        try {
            for (language in values.drop(1)) {
                AppLocales.setLanguage(RuntimeEnvironment.getApplication(), language)
                Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
                    val dialog = LanguagePickerDialog.show(controller.get(), "uk") {}
                    try {
                        val root = layout(dialog)
                        val list = dialog.listView
                        assertTrue(root.height <= 600)
                        for (index in values.indices) {
                            val row = list.adapter.getView(index, null, list) as CheckedTextView
                            row.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
                            assertTrue(row.height >= 48)
                            assertTrue(row.layout.height <= row.height - row.paddingTop - row.paddingBottom)
                            for (line in 0 until row.layout.lineCount) assertEquals(0, row.layout.getEllipsisCount(line))
                        }
                        list.setSelection(values.lastIndex)
                        layout(dialog)
                        assertEquals(values.lastIndex, list.lastVisiblePosition)
                        val last = list.getChildAt(list.childCount - 1)
                        assertTrue(last.bottom <= list.height)
                        val system = list.adapter.getItem(0).toString().substringAfter('\n')
                        val locale = Locale.forLanguageTag(language)
                        assertEquals(AppLocales.phoneLocale(controller.get()).getDisplayLanguage(locale)
                            .lowercase(locale), system.lowercase(locale))
                        assertNotNull(DialogTestFixtures.first(root.findViewWithTag("dialog-header"), TextView::class.java))
                    } finally { dialog.dismiss() }
                }
            }
        } finally { RuntimeEnvironment.setFontScale(previous) }
    }

    @Test fun pausingSettingsDismissesItsLanguageDialog() {
        Robolectric.buildActivity(AppSettingsActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val root = DialogTestFixtures.layout(activity.window.decorView, 360, 640)
            root.findViewWithTag<View>("language-trigger").performClick()
            val dialog = DialogTestFixtures.latest()
            assertTrue(dialog.isShowing)
            controller.pause()
            assertFalse(dialog.isShowing)
            DialogTestFixtures.choose(dialog, 1)
            assertEquals("en", AppLocales.language(activity))
        }
    }

    private fun layout(dialog: AlertDialog): View =
        DialogTestFixtures.layout(requireNotNull(dialog.window).decorView, 320, 600)
}

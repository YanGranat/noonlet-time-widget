package app.noonlet

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextClock
import android.widget.TextView
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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

/** Android's external app-language setting must agree with the manually localized editor. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34, 36],
    qualifiers = "en-rUS-w400dp-h850dp-mdpi",
    shadows = [CountedLocaleManager::class],
)
class LanguageSourceTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        for (file: String in
            arrayOf<String>("noonlet_widgets", "MainActivity", "widget_drafts")) context
            .getSharedPreferences(file, 0)
            .edit()
            .clear()
            .commit()
        AppLocales.setLanguage(context, "en")
        CountedLocaleManager.reads = 0
        CountedLocaleManager.systemReads = 0
    }

    @Test
    fun legacyLanguageMigratesOnceAndLaterSystemResetsRemainAuthoritative() {
        val manager = context.getSystemService(LocaleManager::class.java)
        manager.applicationLocales = LocaleList.getEmptyLocaleList()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear()
            .putString("language", "fr").putString("language_tag", "fr-CA").commit()
        AppLocales.refreshLanguage(context)
        assertEquals("fr-CA", manager.applicationLocales.toLanguageTags())
        assertEquals("fr-CA", AppLocales.appLocale(context).toLanguageTag())
        manager.applicationLocales = LocaleList.getEmptyLocaleList()
        assertTrue(AppLocales.refreshLanguage(context))
        assertEquals("system", AppLocales.language(context))
        AppLocales.refreshLanguage(context)
        assertTrue(manager.applicationLocales.isEmpty)
    }

    @Test
    fun externalAppLanguageOverridesTheOldPrivateChoiceInTheActualEditor() {
        val manager: LocaleManager = context.getSystemService(LocaleManager::class.java)
        assertNotNull(manager)
        assertEquals("en", manager.getApplicationLocales().get(0).getLanguage())
        manager.setApplicationLocales(LocaleList.forLanguageTags("ru"))
        assertEquals(
            "The fixture changes the real app-language API",
            "ru",
            manager.getApplicationLocales().get(0).getLanguage(),
        )
        assertEquals(
            "The external setting did not rewrite the app's private preference",
            "en",
            context.getSharedPreferences("noonlet_widgets", 0).getString("language", "system"),
        )
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val root: View = layout(controller.get())
                    assertNotNull(
                        "Android selected Russian, so the editor must display its Russian Clock tab",
                        text(root, "Часы"),
                    )
                    assertEquals("ru", AppLocales.language(controller.get()))
                    assertEquals("ru", AppLocales.appLocale(controller.get()).getLanguage())
                },
            )
    }

    @Test
    fun externalLocaleListsKeepFallbackMatchingAndSupportedFormattingExtensionsSeparate() {
        val system = Resources.getSystem()
        val original = Configuration(system.configuration)
        try {
            val phone = Configuration(original).apply { setLocale(Locale.GERMANY) }
            system.updateConfiguration(phone, system.displayMetrics)
            val manager = context.getSystemService(LocaleManager::class.java)
            val cases =
                listOf(
                    "ja-JP" to "en",
                    "ja-JP,pl-PL" to "pl-PL",
                    "de-AT" to "de-AT",
                    "en-US-u-ca-japanese" to "en-US-u-ca-japanese",
                )
            for ((requested, expectedTag) in cases) {
                manager.applicationLocales = LocaleList.forLanguageTags(requested)
                AppLocales.refreshLanguage(context)
                val expectedLocale = Locale.forLanguageTag(expectedTag)
                assertEquals(expectedLocale.language, AppLocales.language(context))
                assertEquals(expectedTag, AppLocales.appLocale(context).toLanguageTag())
                assertEquals(requested, manager.applicationLocales.toLanguageTags())
                assertEquals("de-DE", AppLocales.phoneLocale(context).toLanguageTag())
                val configuration = Configuration(context.resources.configuration)
                configuration.setLocale(expectedLocale)
                val expectedLabel =
                    context
                        .createConfigurationContext(configuration)
                        .getString(R.string.clock_widget_name)
                Robolectric.buildActivity(MainActivity::class.java).setup().visible().use {
                    controller ->
                    assertNotNull(text(layout(controller.get()), expectedLabel))
                    assertEquals(
                        expectedTag,
                        AppLocales.appLocale(controller.get()).toLanguageTag(),
                    )
                }
            }
            val formatted = java.text.SimpleDateFormat("G y", AppLocales.appLocale(context))
            assertEquals("japanese", formatted.calendar.calendarType)
        } finally {
            system.updateConfiguration(original, system.displayMetrics)
        }
    }

    @Test
    fun returningToTheEditorReflectsAnExternalLanguageChange() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    assertNotNull(text(layout(controller.get()), "Clock"))
                    for (language in listOf("de", "es", "fr", "it", "pl", "pt", "ru", "uk")) {
                        controller.pause()
                        val manager = context.getSystemService(LocaleManager::class.java)
                        manager.setApplicationLocales(LocaleList.forLanguageTags(language))
                        assertEquals(language, manager.applicationLocales.get(0).language)
                        controller.resume()
                        val configuration = Configuration(context.resources.configuration)
                        configuration.setLocale(Locale.forLanguageTag(language))
                        val expected =
                            context
                                .createConfigurationContext(configuration)
                                .getString(R.string.clock_widget_name)
                        assertNotNull(
                            "Returning from Android language settings must refresh $language labels",
                            text(layout(controller.get()), expected),
                        )
                        assertEquals(language, AppLocales.appLocale(controller.get()).language)
                    }
                },
            )
    }

    @Test
    fun externallyChoosingSystemLanguageSelectsTheSystemRowRatherThanTheOldExplicitLanguage() {
        val manager: LocaleManager = context.getSystemService(LocaleManager::class.java)
        manager.setApplicationLocales(LocaleList.getEmptyLocaleList())
        assertTrue(manager.getApplicationLocales().isEmpty())
        Robolectric.buildActivity(AppSettingsActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val button: View? = layout(controller.get()).findViewWithTag("language-trigger")
                    assertNotNull(button)
                    assertTrue(button!!.performClick())
                    idle()
                    val menu = DialogTestFixtures.latest()
                    assertNotNull(menu)
                    try {
                        assertEquals(
                            "An empty Android application-locale list means Follow system",
                            0, menu.listView.checkedItemPosition,
                        )
                        assertEquals("system", AppLocales.language(controller.get()))
                    } finally {
                        menu.dismiss()
                    }
                },
            )
    }

    @Test
    fun explicitLocaleReadsDoNotQueryTheApplicationLocaleServiceForEveryTranslatedRow() {
        AppLocales.appLocale(context)
        CountedLocaleManager.reads = 0
        for (index: Int in 0..99) assertEquals("en", AppLocales.appLocale(context).getLanguage())
        assertTrue(
            "Locale rendering must use its established source without one Binder query per row; calls=" +
                CountedLocaleManager.reads,
            CountedLocaleManager.reads <= 1,
        )
    }

    @Test
    fun systemLanguageLabelsReuseTheEditorsEstablishedLocaleSnapshot() {
        AppLocales.setLanguage(context, "system")
        val manager: LocaleManager = context.getSystemService(LocaleManager::class.java)
        assertNotNull(manager)
        CountedLocaleManager.systemReads = 0
        assertEquals("en", manager.getSystemLocales().get(0).getLanguage())
        assertEquals(
            "The counting fixture observes the real system-locale API",
            1,
            CountedLocaleManager.systemReads,
        )
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    assertNotNull(text(layout(controller.get()), "Clock"))
                    CountedLocaleManager.systemReads = 0
                    for (index: Int in 0..99) {
                        val label = controller.get().ui.tr(R.string.clock_widget_name)
                        assertEquals("Clock", label)
                    }
                    assertEquals(
                        "An established editor must not query the locale service again for each label",
                        0,
                        CountedLocaleManager.systemReads,
                    )
                },
            )
    }

    @Test
    fun systemLanguageSnapshotRefreshesOnReturnAndRecreationWithoutChangingTheSelectedMode() {
        AppLocales.setLanguage(context, "system")
        val system: Resources = Resources.getSystem()
        val original: Configuration = Configuration(system.getConfiguration())
        try {
            Robolectric.buildActivity(MainActivity::class.java)
                .setup()
                .visible()
                .use(
                    { controller ->
                        assertNotNull(text(layout(controller.get()), "Clock"))
                        controller.pause()
                        val russian: Configuration = Configuration(original)
                        russian.setLocales(LocaleList.forLanguageTags("ru-RU"))
                        system.updateConfiguration(russian, system.getDisplayMetrics())
                        assertEquals(
                            "The actual system-locale API now reports Russian",
                            "ru",
                            context
                                .getSystemService(LocaleManager::class.java)
                                .getSystemLocales()
                                .get(0)
                                .getLanguage(),
                        )
                        assertEquals(
                            "Changing the phone language keeps Follow system selected",
                            "system",
                            AppLocales.language(context),
                        )
                        controller.resume()
                        assertNotNull(
                            "Returning to the same System editor must update its labels",
                            text(layout(controller.get()), "Часы"),
                        )
                        controller.recreate()
                        assertNotNull(
                            "A fresh editor must establish the current system language",
                            text(layout(controller.get()), "Часы"),
                        )
                        assertEquals("system", AppLocales.language(context))
                    },
                )
        } finally {
            system.updateConfiguration(original, system.getDisplayMetrics())
        }
    }

    @Test
    fun aSupportedSecondarySystemLanguageDoesNotReplaceThePrimaryPhoneFormattingLocale() {
        AppLocales.setLanguage(context, "system")
        val system = Resources.getSystem()
        val original = Configuration(system.configuration)
        try {
            val changed = Configuration(original)
            changed.setLocales(LocaleList.forLanguageTags("ja-JP,pl-PL"))
            system.updateConfiguration(changed, system.displayMetrics)
            assertEquals("ja", AppLocales.phoneLocale(context).language)
            assertEquals("ja", AppLocales.appLocale(context).language)
            assertEquals("pl", AppLocales.resourceLocale(context).language)
            Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller
                ->
                assertNotNull(text(layout(controller.get()), "Zegar"))
                assertEquals("system", AppLocales.language(controller.get()))
                controller.recreate()
                assertNotNull(text(layout(controller.get()), "Zegar"))
                assertEquals("ja", AppLocales.phoneLocale(controller.get()).language)
            }
        } finally {
            system.updateConfiguration(original, system.displayMetrics)
        }
    }

    @Test
    fun clockPreviewDoesNotQueryThePhoneLocaleForEveryClock() {
        assertPreviewLocaleReads(false, 2)
    }

    @Test
    fun twoPlacePreviewDoesNotQueryThePhoneLocaleForEveryClock() {
        assertPreviewLocaleReads(true, 4)
    }

    @Test
    @Config(sdk = [26])
    fun preAndroidThirteenKeepsThePrivatePreferenceFallback() {
        AppLocales.setLanguage(context, "ru")
        assertEquals("ru", AppLocales.language(context))
        assertEquals("ru", AppLocales.appLocale(context).getLanguage())
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use({ controller -> assertNotNull(text(layout(controller.get()), "Часы")) })
    }

    private fun assertPreviewLocaleReads(world: Boolean, clockCount: Int) {
        val clock: WidgetSettings = WidgetSettings()
        clock.zoneId = "UTC"
        clock.showDate = true
        clock.showAlarm = false
        WidgetSettingsStore.save(context, 0, false, clock)
        val places: WidgetSettings = WidgetSettings.defaults(true)
        places.cities = arrayOf<String>("America/Los_Angeles", "Asia/Riyadh")
        WidgetSettingsStore.save(context, 0, true, places)
        val manager: LocaleManager = context.getSystemService(LocaleManager::class.java)
        assertNotNull(manager)
        CountedLocaleManager.systemReads = 0
        assertEquals("en", manager.getSystemLocales().get(0).getLanguage())
        assertEquals(
            "The probe must observe the actual system-locale API",
            1,
            CountedLocaleManager.systemReads,
        )
        for (language: String in arrayOf<String>("ru", "system")) {
            AppLocales.setLanguage(context, language)
            context
                .getSharedPreferences("MainActivity", 0)
                .edit()
                .putBoolean("world_tab", world)
                .commit()
            Robolectric.buildActivity(MainActivity::class.java)
                .setup()
                .visible()
                .use(
                    { controller ->
                        val activity: MainActivity = controller.get()
                        val stage: View = layout(activity).findViewWithTag<View>("preview-stage")
                        assertNotNull(stage)
                        assertEquals(
                            "The real preview must contain all time and date TextClocks",
                            clockCount,
                            countClocks(stage),
                        )
                        for (frame: Int in 0..1) {
                            activity.preview.clock = Clock.fixed(
                                Instant.parse("2026-10-30T12:34:00Z").plusSeconds(frame * 60L),
                                ZoneOffset.UTC,
                            )
                            CountedLocaleManager.systemReads = 0
                            activity.preview.refresh(true, null)
                            val reads: Int = CountedLocaleManager.systemReads
                            assertTrue("The actual frame must resolve its phone locale", reads > 0)
                            assertTrue(
                                ("One renderer snapshot and one localization snapshot must serve the whole " +
                                    (if (world) "two-place" else "local") +
                                    " frame (" +
                                    language +
                                    "); calls=" +
                                    reads),
                                reads <= 2,
                            )
                            assertEquals(clockCount, countClocks(stage))
                        }
                    },
                )
        }
    }

    private fun countClocks(view: View): Int {
        var count: Int = if (view is TextClock) 1 else 0
        if (view is ViewGroup)
            for (index: Int in 0 until (view as ViewGroup).getChildCount()) count +=
                countClocks((view as ViewGroup).getChildAt(index))
        return count
    }

    private fun layout(activity: android.app.Activity): View {
        idle()
        val root: View =
            (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 400, 850)
        return root
    }

    private fun text(root: View, value: String): TextView? {
        if (root is TextView && value.contentEquals((root as TextView).getText()))
            return root as TextView
        if (root is ViewGroup)
            for (index: Int in 0 until (root as ViewGroup).getChildCount()) {
                val found: TextView? = text((root as ViewGroup).getChildAt(index), value)
                if (found != null) return found
            }
        return null
    }

    private fun description(root: View, value: String): View? {
        if (
            root.getContentDescription() != null &&
                value.contentEquals(root.getContentDescription())
        )
            return root
        if (root is ViewGroup)
            for (index: Int in 0 until (root as ViewGroup).getChildCount()) {
                val found: View? = description((root as ViewGroup).getChildAt(index), value)
                if (found != null) return found
            }
        return null
    }

    private fun idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
}

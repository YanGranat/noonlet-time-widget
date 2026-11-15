package app.noonlet

import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.TextClock
import android.widget.TextView
import java.time.Instant
import java.util.ArrayList
import java.util.Locale
import java.util.TimeZone
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
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CitySelectionTest {
    private lateinit var context: Context
    private var manager: AppWidgetManager? = null

    @Before
    @Throws(Exception::class)
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        manager = AppWidgetManager.getInstance(context)
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit()
        context.getSharedPreferences("widget_drafts", 0).edit().clear().commit()
        CityCatalog.prepare(context, Locale.ENGLISH).get(30, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test
    fun timeZonePickerMarksStoredIdentityAcrossRecycledSearchResults() {
        AppLocales.setLanguage(context, "en")
        WidgetSettingsStore.saveUnplaced(context, false, WidgetSettings().apply {
            zoneId = CityCatalog.SAN_FRANCISCO
        })
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val root = controller.get().findViewById<View>(android.R.id.content)
            val section = root.findViewWithTag<View>("section-content:time")
            if (section.visibility != View.VISIBLE) root.findViewWithTag<View>("section-toggle:time").performClick()
            fun open(): AlertDialog {
                clickableParent(text(root.findViewWithTag("section-content:time"), "Time zone"))!!.performClick()
                return requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            }
            var dialog = open()
            var list = first(dialog.window!!.decorView, ListView::class.java)!!
            val input = first(dialog.window!!.decorView, EditText::class.java)!!
            DialogTestFixtures.layout(dialog.window!!.decorView, 400, 850)
            val searchDivider = dialog.window!!.decorView.findViewWithTag<View>("place-search-divider")
            assertSame(input.parent, searchDivider.parent)
            assertEquals(0, searchDivider.left)
            assertEquals((input.parent as View).width, searchDivider.right)
            assertEquals(input.bottom + 8, searchDivider.top)
            assertEquals(32, input.height)
            input.setText("san francisco")
            val selected = list.adapter.getView(0, null, list)
            val check = selected.findViewWithTag<android.widget.ImageView>("place-current-indicator")
            assertEquals(View.VISIBLE, check.visibility)
            IconAssertions.assertCheck(check.drawable)
            assertTrue(selected.createAccessibilityNodeInfo().isSelected)
            input.setText("los angeles")
            val other = list.adapter.getView(0, selected, list)
            assertSame(selected, other)
            assertEquals(View.INVISIBLE, other.findViewWithTag<View>("place-current-indicator").visibility)
            assertFalse(other.createAccessibilityNodeInfo().isSelected)
            input.setText("")
            val device = list.adapter.getView(0, other, list)
            assertEquals(View.INVISIBLE, device.findViewWithTag<View>("place-current-indicator").visibility)
            device.performClick()
            dialog = open()
            list = first(dialog.window!!.decorView, ListView::class.java)!!
            val current = list.adapter.getView(0, null, list)
            assertEquals(View.VISIBLE, current.findViewWithTag<View>("place-current-indicator").visibility)
            assertTrue(current.createAccessibilityNodeInfo().isSelected)
            dialog.dismiss()
        }
    }

    @Test
    fun replacingAWorldPlaceAndAddingPlacesUseTheirOwnCurrentSelections() {
        AppLocales.setLanguage(context, "en")
        WidgetSettingsStore.saveUnplaced(context, true, WidgetSettings.defaults(true).apply {
            cities = arrayOf("Europe/London", "Asia/Tokyo")
        })
        context.getSharedPreferences("MainActivity", 0).edit().putBoolean("world_tab", true).commit()
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val root = controller.get().findViewById<View>(android.R.id.content)
            val section = root.findViewWithTag<View>("section-content:cities")
            if (section.visibility != View.VISIBLE) root.findViewWithTag<View>("section-toggle:cities").performClick()
            clickableParent(text(section, "London"))!!.performClick()
            fun assertMark(query: String, id: String, marked: Boolean) {
                val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
                first(dialog.window!!.decorView, EditText::class.java)!!.setText(query)
                val list = first(dialog.window!!.decorView, ListView::class.java)!!
                val index = (0 until list.count).first { (list.adapter.getItem(it) as Place).id == id }
                val row = list.adapter.getView(index, null, list)
                assertEquals(if (marked) View.VISIBLE else View.INVISIBLE,
                    row.findViewWithTag<View>("place-current-indicator").visibility)
            }
            assertMark("London", "Europe/London", true)
            assertMark("Tokyo", "Asia/Tokyo", false)
            ShadowAlertDialog.getLatestAlertDialog().dismiss()
            clickableParent(text(section, "Add place"))!!.performClick()
            assertMark("London", "Europe/London", true)
            assertMark("Tokyo", "Asia/Tokyo", true)
            assertMark("san francisco", CityCatalog.SAN_FRANCISCO, false)
            ShadowAlertDialog.getLatestAlertDialog().dismiss()
        }
    }

    @Test
    fun sanFranciscoHasItsOwnIdentityAndUsesPacificDst() {
        val sf: Place =
            CityCatalog.all()
                .stream()
                .filter({ c -> c.id.equals(CityCatalog.SAN_FRANCISCO) })
                .findFirst()
                .get()
        assertEquals("America/Los_Angeles", sf.zoneId)
        assertNotEquals(sf.id, sf.zoneId)
        assertEquals("San Francisco", CityCatalog.name(context, sf.id, Locale.ENGLISH))
        assertEquals("Сан-Франциско", CityCatalog.name(context, sf.id, Locale("ru")))
        for (query: String in
            arrayOf<String>(
                "san francisco",
                "SanFrancisco",
                "САН-ФРАНЦИСКО",
                "сан франциско",
                "санфранциско",
            )) assertTrue(
            query,
            CityCatalog.search(context, query, Locale.ENGLISH).any { it.id == sf.id },
        )
        assertEquals(
            "Los Angeles",
            CityCatalog.name(context, "America/Los_Angeles", Locale.ENGLISH),
        )
        assertFalse(CityCatalog.isValidSelection("America/San_Francisco"))
        assertFalse(CityCatalog.isValidSelection("city:arbitrary-custom-label"))
        val zone: TimeZone = TimeZone.getTimeZone(CityCatalog.timeZoneId(sf.id))
        assertEquals(
            -8 * 3600000,
            zone.getOffset(Instant.parse("2026-01-15T12:00:00Z").toEpochMilli()),
        )
        assertEquals(
            -7 * 3600000,
            zone.getOffset(Instant.parse("2026-07-15T12:00:00Z").toEpochMilli()),
        )
    }

    @Test
    fun selectedPlaceSurvivesJsonEditorStateAndOverlappingRestore() {
        val settings: WidgetSettings = WidgetSettings.defaults(true)
        settings.cities =
            arrayOf<String>(CityCatalog.SAN_FRANCISCO, "America/Los_Angeles", "Asia/Riyadh")
        settings.zoneId = CityCatalog.SAN_FRANCISCO
        WidgetSettingsStore.save(context, 980, true, settings)
        val loaded: WidgetSettings = WidgetSettingsStore.load(context, 980, true)
        assertArrayEquals(settings.cities, loaded.cities)
        assertEquals(settings.zoneId, loaded.zoneId)
        assertEquals("America/Los_Angeles", loaded.effectiveZone())
        val state: Bundle = Bundle()
        SettingsCodec.writeEditorState(loaded, state)
        val restored: WidgetSettings = WidgetSettings.defaults(true)
        WidgetSettingsStore.restoreEditorState(context, 980, true, restored, state)
        assertArrayEquals(settings.cities, restored.cities)
        assertEquals(settings.zoneId, restored.zoneId)
        val other: WidgetSettings = WidgetSettings.defaults(true)
        WidgetSettingsStore.save(context, 981, true, other)
        bind(981)
        bind(982)
        WorldWidgetProvider().onRestored(context, intArrayOf(980, 981), intArrayOf(981, 982))
        assertArrayEquals(settings.cities, WidgetSettingsStore.load(context, 981, true).cities)
        assertArrayEquals(other.cities, WidgetSettingsStore.load(context, 982, true).cities)
        assertEquals(
            "London",
            CityCatalog.name(
                context,
                WidgetSettingsStore.load(context, 982, true).cities[0],
                Locale.ENGLISH,
            ),
        )
    }

    @Test
    fun localClockKeepsTheSelectedPlaceNameAndReceivesARealTimeZone() {
        val settings: WidgetSettings = WidgetSettings.defaults(false)
        settings.zoneId = CityCatalog.SAN_FRANCISCO
        WidgetSettingsStore.save(context, 983, false, settings)
        val loaded: WidgetSettings = WidgetSettingsStore.load(context, 983, false)
        assertEquals(CityCatalog.SAN_FRANCISCO, loaded.zoneId)
        val host: View =
            WidgetRenderer.preview(
                    context,
                    983,
                    false,
                    WidgetSettingsStore.load(context, 983, false),
                    328f,
                    90f,
                )
                .apply(context, android.widget.FrameLayout(context))
        assertEquals(
            "America/Los_Angeles",
            (host.findViewById<View>(R.id.clock_time) as TextClock).getTimeZone(),
        )
        assertEquals("San Francisco", CityCatalog.name(context, loaded.zoneId, Locale.ENGLISH))
    }

    @Test
    @Throws(Exception::class)
    fun localizedSearchSaveAndRecreationKeepTheSamePlaceIdentityInEveryLanguage() {
        var id: Int = 984
        for (language in listOf("de", "en", "es", "fr", "it", "pl", "pt", "ru", "uk")) {
            val locale = Locale.forLanguageTag(language)
            AppLocales.setLanguage(context, language)
            CityCatalog.prepare(context, locale).get(30, java.util.concurrent.TimeUnit.SECONDS)
            WidgetSettingsStore.save(context, id, true, WidgetSettings.defaults(true))
            bind(id)
            val intent: Intent =
                Intent(context, MainActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra("world", true)
            Robolectric.buildActivity(MainActivity::class.java, intent)
                .setup()
                .visible()
                .use(
                    { controller ->
                        val activity: MainActivity = controller.get()
                        val root: View = activity.findViewById<View>(android.R.id.content)
                        assertTrue(
                            root.findViewWithTag<View>("section-toggle:cities").performClick(),
                        )
                        val oldName: TextView? =
                            text(
                                root.findViewWithTag<View>("section:cities"),
                                CityCatalog.name(context, "Europe/London", locale),
                            )
                        assertNotNull(oldName)
                        assertTrue((oldName!!.getParent() as View).performClick())
                        val dialog: AlertDialog = ShadowAlertDialog.getLatestAlertDialog()
                        first(dialog.getWindow()!!.getDecorView(), EditText::class.java)!!.setText(
                            CityCatalog.name(context, CityCatalog.SAN_FRANCISCO, locale)
                                .uppercase(locale),
                        )
                        val results: ListView? =
                            first(dialog.getWindow()!!.getDecorView(), ListView::class.java)
                        assertEquals(1, results!!.getAdapter().getCount())
                        assertEquals(
                            CityCatalog.SAN_FRANCISCO,
                            (results!!.getAdapter().getItem(0) as Place).id,
                        )
                        assertTrue(results!!.performItemClick(null, 0, 0))
                        assertArrayEquals(
                            arrayOf<String>(CityCatalog.SAN_FRANCISCO),
                            configuration(activity).cities,
                        )
                        assertSaveChanges(activity, language)
                        controller.recreate()
                        assertArrayEquals(
                            arrayOf(CityCatalog.SAN_FRANCISCO),
                            configuration(controller.get()).cities,
                        )
                    },
                )
            assertArrayEquals(
                arrayOf<String>(CityCatalog.SAN_FRANCISCO),
                WidgetSettingsStore.load(context, id, true).cities,
            )
            val host: View = Shadows.shadowOf(manager).getViewFor(id)
            assertNotNull(
                text(host, CityCatalog.name(context, CityCatalog.SAN_FRANCISCO, locale)),
            )
            val clocks: MutableList<TextClock> = all(host, TextClock::class.java)
            assertFalse(clocks.isEmpty())
            for (clock: TextClock in clocks) assertEquals(
                "America/Los_Angeles",
                clock.getTimeZone(),
            )
            id++
        }
    }

    @Test
    @Throws(Exception::class)
    fun addPlaceSearchShowsTheSelectedCityInsteadOfItsBackingZoneCity() {
        var id: Int = 988
        for (language: String in arrayOf<String>("ru", "en")) {
            AppLocales.setLanguage(context, language)
            WidgetSettingsStore.save(context, id, true, WidgetSettings.defaults(true))
            bind(id)
            val intent: Intent =
                Intent(context, MainActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra("world", true)
            Robolectric.buildActivity(MainActivity::class.java, intent)
                .setup()
                .visible()
                .use(
                    { controller ->
                        val activity: MainActivity = controller.get()
                        val root: View = activity.findViewById<View>(android.R.id.content)
                        assertTrue(
                            root.findViewWithTag<View>("section-toggle:cities").performClick(),
                        )
                        val add: TextView? =
                            text(
                                root.findViewWithTag<View>("section:cities"),
                                if ("ru".equals(language)) "Добавить место" else "Add place",
                            )
                        assertNotNull(add)
                        assertTrue(clickableParent(add)!!.performClick())
                        val dialog: AlertDialog = ShadowAlertDialog.getLatestAlertDialog()
                        val search: EditText? =
                            first(dialog.getWindow()!!.getDecorView(), EditText::class.java)
                        val results: ListView? =
                            first(dialog.getWindow()!!.getDecorView(), ListView::class.java)
                        for (query: String in
                            arrayOf<String>(
                                "сан франциско",
                                "сан-франциско",
                                "САН ФРАНЦИСКО",
                                "San Francisco",
                                "SanFrancisco",
                            )) {
                            search!!.setText(query)
                            assertEquals(query, 1, results!!.getAdapter().getCount())
                            val result: View = results!!.getAdapter().getView(0, null, results)
                            val labels: MutableList<TextView> = all(result, TextView::class.java)
                            assertEquals(
                                query,
                                if ("ru".equals(language)) "Сан-Франциско" else "San Francisco",
                                labels.get(0).getText().toString(),
                            )
                            assertEquals(
                                query,
                                "UTC" +
                                    java.time.ZonedDateTime.now(
                                            java.time.ZoneId.of("America/Los_Angeles"),
                                        )
                                        .getOffset()
                                        .getId(),
                                labels.get(1).getText().toString(),
                            )
                        }
                        assertTrue(results!!.performItemClick(null, 0, 0))
                        assertArrayEquals(
                            arrayOf<String>("Europe/London", CityCatalog.SAN_FRANCISCO),
                            configuration(activity).cities,
                        )
                        assertNotNull(
                            text(
                                activity.findViewById<View>(android.R.id.content),
                                if ("ru".equals(language)) "Сан-Франциско" else "San Francisco",
                            ),
                        )
                        assertSaveChanges(activity, language)
                    },
                )
            assertArrayEquals(
                arrayOf<String>("Europe/London", CityCatalog.SAN_FRANCISCO),
                WidgetSettingsStore.load(context, id, true).cities,
            )
            val host: View = Shadows.shadowOf(manager).getViewFor(id)
            assertNotNull(
                text(host, if ("ru".equals(language)) "Сан-Франциско" else "San Francisco"),
            )
            assertNotNull(text(host, if ("ru".equals(language)) "Лондон" else "London"))
            assertEquals(setOf("Europe/London", "America/Los_Angeles"),
                all(host, TextClock::class.java).map { it.timeZone }.toSet())
            id++
        }
    }

    @Test
    @Throws(Exception::class)
    fun theDeviceTimeChoiceShowsItsOffsetWithoutNamingAnotherCity() {
        val original: TimeZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            AppLocales.setLanguage(context, "ru")
            Robolectric.buildActivity(MainActivity::class.java)
                .setup()
                .visible()
                .use(
                    { controller ->
                        val activity: MainActivity = controller.get()
                        val root: View = activity.findViewById<View>(android.R.id.content)
                        val time: View = root.findViewWithTag<View>("section-content:time")
                        if (time.getVisibility() != View.VISIBLE)
                            assertTrue(
                                root.findViewWithTag<View>("section-toggle:time").performClick(),
                            )
                        assertTrue(clickableParent(text(time, "Часовой пояс"))!!.performClick())
                        val dialog: AlertDialog = ShadowAlertDialog.getLatestAlertDialog()
                        val results: ListView? =
                            first(dialog.getWindow()!!.getDecorView(), ListView::class.java)
                        assertNull(results!!.getAdapter().getItem(0))
                        val labels: MutableList<TextView> =
                            all(
                                results!!.getAdapter().getView(0, null, results),
                                TextView::class.java,
                            )
                        assertEquals("Время телефона", labels.get(0).getText().toString())
                        assertEquals(
                            "UTC" +
                                java.time.ZonedDateTime.now(java.time.ZoneId.systemDefault())
                                    .getOffset()
                                    .getId(),
                            labels.get(1).getText().toString(),
                        )
                    },
                )
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    @Throws(Exception::class)
    fun aCountryCanBeChosenSavedAndRenderedInBothLanguages() {
        val japan: Place =
            CityCatalog.all()
                .stream()
                .filter({ c -> c.country && c.id.startsWith("country:JP:") })
                .findFirst()
                .get()
        var id: Int = 986
        for (language: String in arrayOf<String>("en", "ru")) {
            AppLocales.setLanguage(context, language)
            WidgetSettingsStore.save(context, id, true, WidgetSettings.defaults(true))
            bind(id)
            val intent: Intent =
                Intent(context, MainActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra("world", true)
            Robolectric.buildActivity(MainActivity::class.java, intent)
                .setup()
                .visible()
                .use(
                    { controller ->
                        val activity: MainActivity = controller.get()
                        val root: View = activity.findViewById<View>(android.R.id.content)
                        assertTrue(
                            root.findViewWithTag<View>("section-toggle:cities").performClick(),
                        )
                        val oldName: TextView? =
                            text(
                                root.findViewWithTag<View>("section:cities"),
                                if ("ru".equals(language)) "Лондон" else "London",
                            )
                        assertNotNull(oldName)
                        assertTrue((oldName!!.getParent() as View).performClick())
                        val dialog: AlertDialog = ShadowAlertDialog.getLatestAlertDialog()
                        first(dialog.getWindow()!!.getDecorView(), EditText::class.java)!!.setText(
                            if ("ru".equals(language)) "ЯПОНИЯ" else "Japan",
                        )
                        val results: ListView? =
                            first(dialog.getWindow()!!.getDecorView(), ListView::class.java)
                        val countryIndex = (0 until results!!.adapter.count).single {
                            (results.adapter.getItem(it) as Place).id == japan.id
                        }
                        val labels: MutableList<TextView> =
                            all(
                                results!!.getAdapter().getView(countryIndex, null, results),
                                TextView::class.java,
                            )
                        assertEquals(
                            (if ("ru".equals(language)) "Страна/регион" else "Country/region") +
                                " · UTC+09:00",
                            labels.get(1).getText().toString(),
                        )
                        assertTrue(results!!.performItemClick(null, countryIndex, countryIndex.toLong()))
                        controller.recreate()
                        assertArrayEquals(
                            arrayOf<String>(japan.id),
                            configuration(controller.get()).cities,
                        )
                        assertSaveChanges(controller.get(), language)
                    },
                )
            assertArrayEquals(
                arrayOf<String>(japan.id),
                WidgetSettingsStore.load(context, id, true).cities,
            )
            val host: View = Shadows.shadowOf(manager).getViewFor(id)
            assertNotNull(text(host, if ("ru".equals(language)) "Япония" else "Japan"))
            for (clock: TextClock in all(host, TextClock::class.java)) assertEquals(
                japan.zoneId,
                clock.getTimeZone(),
            )
            id++
        }
    }

    private fun assertSaveChanges(activity: MainActivity, language: String) {
        val save =
            activity
                .findViewById<View>(android.R.id.content)
                .findViewWithTag<TextView>("action-save-changes")
        assertNotNull(save)
        assertEquals(
            AppLocales.text(activity, Locale.forLanguageTag(language), R.string.save_changes),
            save.text.toString(),
        )
        assertTrue(save.performClick())
    }

    private fun bind(id: Int) {
        val info: AppWidgetProviderInfo = AppWidgetProviderInfo()
        info.provider = ComponentName(context, WorldWidgetProvider::class.java)
        info.initialLayout = R.layout.world_widget
        Shadows.shadowOf(manager).addInstalledProvider(info)
        Shadows.shadowOf(manager).addBoundWidget(id, info)
        val options: Bundle = Bundle()
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 328)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 328)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 174)
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 174)
        manager!!.updateAppWidgetOptions(id, options)
    }

    @Throws(Exception::class)
    private fun configuration(activity: MainActivity): WidgetSettings {
        return activity.session.settings
    }

    private fun clickableParent(view: View?): View? {
        var view = view
        while (!view!!.isClickable() && view!!.getParent() is View) view =
            view!!.getParent() as View
        return view
    }

    private fun text(root: View, value: String): TextView? {
        for (view: TextView in all(root, TextView::class.java)) if (
            value.contentEquals(view.getText())
        )
            return view
        return null
    }

    private fun <T : View> first(root: View, type: Class<T>): T? {
        val list: MutableList<T> = all<T>(root, type)
        return if (list.isEmpty()) null else list.get(0)
    }

    private fun <T : View> all(root: View, type: Class<T>): MutableList<T> {
        val result: MutableList<T> = ArrayList()
        if (type.isInstance(root)) result.add(checkNotNull(type.cast(root)))
        if (root is ViewGroup)
            for (i: Int in 0 until (root as ViewGroup).getChildCount()) result.addAll(
                all((root as ViewGroup).getChildAt(i), type),
            )
        return result
    }
}

package app.noonlet

import android.app.AlertDialog
import app.noonlet.EditorTestInteractions.tap
import app.noonlet.DialogTestFixtures.first
import app.noonlet.DialogTestFixtures.all
import app.noonlet.DialogTestFixtures.choose
import app.noonlet.DialogTestFixtures.layout
import app.noonlet.DialogTestFixtures.latest
import app.noonlet.DialogTestFixtures.assertAppearance
import app.noonlet.DialogTestFixtures.awaitAppsLoaded
import android.content.Context
import android.os.Looper
import android.view.*
import android.view.View
import android.widget.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DialogDesignTest {
    lateinit var context: Context

    @Before
    @Throws(Exception::class)
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit()
        CityCatalog.prepare(context, java.util.Locale.ENGLISH)
            .get(30, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test
    @Config(qualifiers = "de-w360dp-h720dp-mdpi")
    fun shortChoicesRemainFullyVisibleWhenTheirLabelsWrap() {
        val originalScale = context.resources.configuration.fontScale
        try {
            RuntimeEnvironment.setFontScale(1.3f)
            AppLocales.setLanguage(context, "de")
            Robolectric.buildActivity(AppSettingsActivity::class.java).setup().visible().use { controller ->
                val activity = controller.get()
                val labels = intArrayOf(R.string.weather_condition_temperature, R.string.weather_temperature_condition,
                    R.string.weather_temperature_only).map { AppLocales.text(activity, it) }.toTypedArray()
                val dialog = AppDialogBuilder(activity)
                    .setTitle(R.string.weather_text_format)
                    .setSingleChoiceItems(labels, 0) { selected, _ -> selected.dismiss() }.show()
                val root = dialog.window!!.decorView
                layout(root, 320, 780)
                val list = dialog.listView
                assertEquals(labels.lastIndex, list.lastVisiblePosition)
                val last = list.getChildAt(list.childCount - 1)
                assertTrue("Last row ${last.bottom} exceeds list height ${list.height}",
                    last.bottom <= list.height)
                dialog.dismiss()
            }
        } finally { RuntimeEnvironment.setFontScale(originalScale) }
    }

    @Test
    fun privacyConfirmationsKeepWrappedTitlesInsideTheirHeader() {
        for (language in listOf("en", "de", "es", "fr", "it", "pl", "pt", "ru", "uk")) {
            AppLocales.setLanguage(context, language)
            Robolectric.buildActivity(DataPrivacyActivity::class.java).setup().visible().use { controller ->
                val activity = controller.get()
                layout(activity.window.decorView, 360, 780)
                for (tag in listOf("clear-weather-cache", "forget-device-location")) {
                    activity.window.decorView.findViewWithTag<View>(tag).performClick()
                    val dialog = latest()
                    val root = dialog.window!!.decorView
                    layout(root, 330, 680)
                    val title = root.findViewWithTag<TextView>("dialog-title")
                    val toolbar = title.parent as View
                    assertTrue(toolbar.paddingTop >= 12)
                    assertTrue(toolbar.paddingBottom >= 8)
                    assertEquals(title.layout.height, title.height)
                    assertEquals(20f, title.textSize, .01f)
                    assertEquals(View.GONE, root.findViewWithTag<View>("dialog-close").visibility)
                    assertEquals(View.GONE, root.findViewWithTag<View>("dialog-header-divider").visibility)
                    assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick())
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    assertFalse(dialog.isShowing)
                }
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun choiceDialogMatrixRendersInBothLanguages() {
        val entries =
            listOf(
                DialogCase("Font", "Шрифт"),
                DialogCase("Element layout", "Расположение элементов"),
                DialogCase("Time alignment", "Выравнивание времени"),
                DialogCase("Time format", "Формат времени"),
                DialogCase("Date format", "Формат даты"),
                DialogCase("Alarm time format", "Формат времени будильника"),
                DialogCase("Widget tap", "Нажатие на виджет"),
                DialogCase("Clock color", "Цвет часов", colorChannels = true),
                DialogCase("Time zone", "Часовой пояс"),
            )
        for (language: Int in 0..1) {
            AppLocales.setLanguage(context, if (language == 0) "en" else "ru")
            CityCatalog.prepare(context, AppLocales.appLocale(context))
                .get(30, java.util.concurrent.TimeUnit.SECONDS)
            context.getSharedPreferences("MainActivity", 0).edit().clear().commit()
            Robolectric.buildActivity(MainActivity::class.java)
                .setup()
                .visible()
                .use(
                    { controller ->
                        val activity: MainActivity = controller.get()
                        layout(requireNotNull(activity.window).getDecorView(), 400, 850)
                        Robolectric.buildActivity(AppSettingsActivity::class.java).setup().visible().use { settingsController ->
                            val settings = settingsController.get()
                            layout(settings.window.decorView, 400, 850)
                            settings.findViewById<View>(android.R.id.content)
                                .findViewWithTag<View>("language-trigger").performClick()
                            val menu = latest()
                            assertTrue(menu.isShowing)
                            assertNotNull(menu.window!!.decorView.findViewWithTag<View>("dialog-header"))
                            assertAppearance(menu)
                            menu.dismiss()
                        }
                        var dialog: AlertDialog
                        for (entry in entries) {
                            tap(activity, if (language == 0) entry.english else entry.russian)
                            dialog = latest()
                            assertAppearance(dialog, checkColorChannels = entry.colorChannels)
                            dialog.dismiss()
                            Shadows.shadowOf(Looper.getMainLooper()).idle()
                        }
                        tap(activity, if (language == 0) "Date format" else "Формат даты")
                        choose(latest(), 7)
                        dialog = latest()
                        assertAppearance(dialog)
                        dialog.dismiss()
                        WidgetTestViews.selectWorldTab(activity)
                        layout(requireNotNull(activity.window).getDecorView(), 400, 850)
                        tap(activity, if (language == 0) "Day / night mode" else "Режим дня и ночи")
                        dialog = latest()
                        assertAppearance(dialog)
                        dialog.dismiss()
                    },
                )
        }
    }

    @Test
    @Throws(Exception::class)
    fun appSelectorRendersLongListsWithoutAnotherWidgetSelector() {
        AppLocales.setLanguage(context, "en")
        val launcher: android.content.Intent =
            android.content
                .Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        for (i: Int in 0..17) {
            val app: android.content.pm.ResolveInfo = android.content.pm.ResolveInfo()
            app.nonLocalizedLabel = "Example application with a descriptive long label " + i
            app.activityInfo = android.content.pm.ActivityInfo()
            app.activityInfo.name = "example.app" + i + ".MainActivity"
            app.activityInfo.packageName = "example.app" + i
            app.activityInfo.exported = true
            app.activityInfo.enabled = app.activityInfo.exported
            Shadows.shadowOf(context.getPackageManager()).addResolveInfoForIntent(launcher, app)
        }
        val manager: android.appwidget.AppWidgetManager =
            android.appwidget.AppWidgetManager.getInstance(context)
        for (i: Int in 0..9) {
            val info: android.appwidget.AppWidgetProviderInfo =
                android.appwidget.AppWidgetProviderInfo()
            info.provider = android.content.ComponentName(context, WorldWidgetProvider::class.java)
            Shadows.shadowOf(manager).addBoundWidget(3100 + i, info)
            val settings: WidgetSettings = WidgetSettings.defaults(true)
            settings.cities =
                arrayOf<String>(
                    "America/Los_Angeles",
                    "Europe/London",
                    "Asia/Kathmandu",
                    "Asia/Riyadh",
                )
            WidgetSettingsStore.save(context, 3100 + i, true, settings)
        }
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    layout(requireNotNull(activity.window).getDecorView(), 400, 850)
                    tap(activity, "Widget tap")
                    choose(latest(), 4)
                    val apps: AlertDialog = latest()
                    awaitAppsLoaded(apps)
                    assertTrue(apps.getListView().getCount() >= 18)
                    assertAppearance(apps)
                    apps.dismiss()
                    WidgetTestViews.selectWorldTab(activity)
                    layout(requireNotNull(activity.window).getDecorView(), 400, 850)
                    val root = requireNotNull(activity.window).decorView
                    val cities = root.findViewWithTag<View>("section:cities")
                    assertNotNull(cities)
                    assertEquals(View.VISIBLE, cities.visibility)
                    assertNotNull(root.findViewWithTag<View>("action-save-changes"))
                    assertNull(root.findViewWithTag<View>("action-add-widget"))
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun inputDialogsKeepTheirFieldAndActionVisibleWhenHeightShrinks() {
        AppLocales.setLanguage(context, "en")
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    layout(requireNotNull(activity.window).getDecorView(), 400, 850)
                    tap(activity, "Date format")
                    choose(latest(), 7)
                    checkInput(latest(), "custom-format")
                    tap(activity, "Clock color")
                    checkInput(latest(), "color")
                    tap(activity, "Time zone")
                    val cities: AlertDialog = latest()
                    val search: EditText? =
                        first(requireNotNull(cities.window).getDecorView(), EditText::class.java)
                    search!!.setText("no-such-city-result-xyz")
                    checkInput(cities, "city-search-empty")
                },
            )
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h640dp-mdpi")
    @Throws(Exception::class)
    fun longRussianTitlesAndInputActionsFitLargeText() {
        RuntimeEnvironment.setFontScale(1.3f)
        AppLocales.setLanguage(context, "ru")
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    layout(requireNotNull(activity.window).getDecorView(), 320, 640)
                    tap(activity, "Формат времени будильника")
                    val formats = latest()
                    assertEquals(3, formats.listView.adapter.count)
                    choose(formats, formats.listView.adapter.count - 1)
                    checkInput(latest(), "large-text-alarm-format")
                },
            )
    }

    private data class DialogCase(
        val english: String,
        val russian: String,
        val colorChannels: Boolean = false,
    )

    companion object {
        @Throws(Exception::class)
        fun checkInput(dialog: AlertDialog, name: String) {
            assertEquals(
                "Input dialogs must allow keyboard-driven resizing",
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                requireNotNull(dialog.window).getAttributes().softInputMode and
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST,
            )
            val display: android.view.Display =
                org.robolectric.shadows.ShadowDisplay.getDefaultDisplay()
            val fullHeight: Int = display.getHeight()
            Shadows.shadowOf(display).setHeight(380)
            requireNotNull(dialog.window)
                .getDecorView()
                .getViewTreeObserver()
                .dispatchOnGlobalLayout()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val actualWidth: Int = requireNotNull(dialog.window).getAttributes().width
            val root: View =
                layout(
                    requireNotNull(dialog.window).getDecorView(),
                    if (actualWidth > 0) Math.min(368, actualWidth) else 368,
                    380,
                )
            val input: EditText? = first(root, EditText::class.java)
            assertNotNull(input)
            assertTrue(input!!.requestFocus())
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            input!!.requestRectangleOnScreen(
                android.graphics.Rect(0, 0, input!!.getWidth(), input!!.getHeight()),
                true,
            )
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            WidgetTestViews.prepare(root)
            val visible: android.graphics.Rect = android.graphics.Rect()
            assertTrue("Input field clipped in " + name, input!!.getGlobalVisibleRect(visible))
            assertEquals(
                "Input field must remain fully usable in " + name,
                input!!.getHeight(),
                visible.height(),
            )
            for (button: Int in
                intArrayOf(
                    AlertDialog.BUTTON_POSITIVE,
                    AlertDialog.BUTTON_NEUTRAL,
                    AlertDialog.BUTTON_NEGATIVE,
                )) {
                val action: View? = dialog.getButton(button)
                if (action == null || action!!.getVisibility() != View.VISIBLE) continue
                assertTrue(
                    "Dialog action clipped in " + name,
                    action!!.getGlobalVisibleRect(visible),
                )
                assertEquals(
                    "Action height clipped in " + name,
                    action!!.getHeight(),
                    visible.height(),
                )
                assertEquals(
                    "Action width clipped in " + name,
                    action!!.getWidth(),
                    visible.width(),
                )
            }
            if (name.equals("color")) {
                val channels: MutableList<SeekBar> = all(root, SeekBar::class.java)
                assertEquals(4, channels.size)
                val last: View = channels.get(3)
                last.requestRectangleOnScreen(
                    android.graphics.Rect(0, 0, last.getWidth(), last.getHeight()),
                    true,
                )
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                assertTrue("Last color channel is unreachable", last.getGlobalVisibleRect(visible))
                assertEquals(last.getHeight(), visible.height())
            }
            WidgetTestViews.prepare(root)
            dialog.dismiss()
            Shadows.shadowOf(display).setHeight(fullHeight)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }


        private fun findText(root: View, value: String): TextView? {
            if (root is TextView && value.contentEquals((root as TextView).getText()))
                return root as TextView
            if (root is ViewGroup)
                for (i: Int in 0 until (root as ViewGroup).getChildCount()) {
                    val found: TextView? = findText((root as ViewGroup).getChildAt(i), value)
                    if (found != null) return found
                }
            return null
        }

    }
}

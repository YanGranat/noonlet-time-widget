package app.noonlet

import android.app.AlertDialog
import app.noonlet.EditorTestInteractions.tap
import app.noonlet.EditorTestInteractions.tapView
import app.noonlet.EditorTestInteractions.bringIntoView
import app.noonlet.EditorTestInteractions.revealSection
import app.noonlet.EditorTestInteractions.text
import app.noonlet.EditorTestInteractions.texts
import app.noonlet.EditorTestInteractions.first
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import java.lang.reflect.Method
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
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReferenceUiTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
        Locale.setDefault(Locale.ENGLISH)
    }

    @Test
    @Throws(Exception::class)
    fun everyAppearanceAndPositionControlIsAvailableInBothLanguages() {
        val labels: Array<Array<String>> =
            arrayOf<Array<String>>(
                arrayOf<String>(
                    "Widget tap",
                    "Advanced tap actions",
                    "Font",
                    "Widget orientation",
                    "Reset appearance",
                    "Top padding",
                    "Bottom padding",
                    "Left padding",
                    "Right padding",
                    "Clock color",
                    "Time format",
                    "Time size",
                    "Time alignment",
                    "Show date",
                    "Date color",
                    "Date format",
                    "Date size",
                    "Font weight",
                    "Date alignment",
                    "Show alarm",
                    "Show alarm icon",
                    "Alarm time format",
                    "Alarm color",
                    "Alarm size",
                    "Alarm alignment",
                    "Time horizontal offset",
                    "Time vertical offset",
                    "Date horizontal offset",
                    "Date vertical offset",
                    "Alarm horizontal offset",
                    "Alarm vertical offset",
                    "Time–date spacing",
                    "Digit weight",
                ),
                arrayOf<String>(
                    "Нажатие на виджет",
                    "Раздельные действия по нажатию",
                    "Шрифт",
                    "Расположение элементов",
                    "Сбросить оформление",
                    "Отступ сверху",
                    "Отступ снизу",
                    "Отступ слева",
                    "Отступ справа",
                    "Цвет часов",
                    "Формат времени",
                    "Размер времени",
                    "Выравнивание времени",
                    "Показывать дату",
                    "Цвет даты",
                    "Формат даты",
                    "Размер даты",
                    "Толщина шрифта",
                    "Выравнивание даты",
                    "Показывать будильник",
                    "Показывать значок будильника",
                    "Формат времени будильника",
                    "Цвет будильника",
                    "Размер будильника",
                    "Выравнивание будильника",
                    "Сдвиг времени по горизонтали",
                    "Сдвиг времени по вертикали",
                    "Сдвиг даты по горизонтали",
                    "Сдвиг даты по вертикали",
                    "Сдвиг будильника по горизонтали",
                    "Сдвиг будильника по вертикали",
                    "Расстояние между временем и датой",
                    "Толщина цифр",
                ),
            )
        for (language: Int in 0..1) {
            context
                .getSharedPreferences("widget_drafts", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
            AppLocales.setLanguage(context, if (language == 0) "en" else "ru")
            Robolectric.buildActivity(MainActivity::class.java)
                .setup()
                .use(
                    { controller ->
                        val activity: MainActivity = controller.get()
                        var root: View = layout(activity, 400, 850)
                        assertLaidOut(root)
                        WidgetTestViews.revealAll(root)
                        for (label: String in labels[language]) {
                            val control: TextView? = text(root, label)
                            assertNotNull("Missing control: " + label, control)
                            bringIntoView(root, control)
                        }
                        tap(
                            root,
                            if (language == 0) "Advanced tap actions"
                            else "Раздельные действия по нажатию",
                        )
                        root = layout(activity, 400, 850)
                        for (label: String in
                            if (language == 0) arrayOf<String>("Tap time", "Tap date", "Tap alarm")
                            else
                                arrayOf<String>(
                                    "Нажатие на время",
                                    "Нажатие на дату",
                                    "Нажатие на будильник",
                                )) assertNotNull(
                            "Missing advanced action: " + label,
                            text(root, label),
                        )
                        assertTrue(config(activity).advancedTap)
                        assertFalse(
                            "Normal portrait preview must remain fixed",
                            descendant(preview(activity), scroll(root)),
                        )
                        assertLaidOut(root)
                        assertLaidOut(scroll(root)!!.getChildAt(0))
                    },
                )
        }
    }

    @Test
    fun dateFontWeightControlChangesOnlyDateAndSurvivesRecreation() {
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            var activity = controller.get()
            val initialTime = config(activity).timeFontWeight
            val initialDetail = config(activity).fontWeight
            for (expected in intArrayOf(100, 900, 700)) {
                val root = layout(activity, 400, 850)
                WidgetTestViews.reveal(root, "date")
                val label = requireNotNull(text(root, "Font weight"))
                bringIntoView(root, label)
                val slider =
                    requireNotNull(
                        first(label.parent.parent as View, android.widget.SeekBar::class.java),
                    )
                val arguments =
                    android.os.Bundle().apply {
                        putFloat(
                            android.view.accessibility.AccessibilityNodeInfo
                                .ACTION_ARGUMENT_PROGRESS_VALUE,
                            expected.toFloat(),
                        )
                    }
                assertTrue(
                    slider.performAccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                            .ACTION_SET_PROGRESS
                            .id,
                        arguments,
                    ),
                )
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
                assertEquals(expected, config(activity).dateFontWeight)
                assertEquals(initialTime, config(activity).timeFontWeight)
                assertEquals(initialDetail, config(activity).fontWeight)
                assertEquals(
                    expected,
                    WidgetTestViews.weight(
                        preview(activity).findViewById<TextClock>(R.id.clock_date),
                    ),
                )
                assertEquals(
                    initialTime,
                    WidgetTestViews.weight(
                        preview(activity).findViewById<TextClock>(R.id.clock_time),
                    ),
                )
            }
            controller.recreate()
            activity = controller.get()
            assertEquals(700, config(activity).dateFontWeight)
            assertEquals(initialTime, config(activity).timeFontWeight)
            assertEquals(initialDetail, config(activity).fontWeight)
        }
    }

    @Test
    @Throws(Exception::class)
    fun formatsColorAndAdvancedActionsChangeRealPreviewAndResetKeepsCities() {
        AppLocales.setLanguage(context, "ru")
        val phone: Configuration = Configuration(Resources.getSystem().getConfiguration())
        phone.setLocale(Locale.ENGLISH)
        Resources.getSystem().updateConfiguration(phone, Resources.getSystem().getDisplayMetrics())
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    var root: View = layout(activity, 400, 850)
                    tap(root, "Шрифт")
                    choose(latestDialog(), 2)
                    assertEquals(350, config(activity).timeFontWeight)
                    assertEquals(
                        350,
                        WidgetTestViews.weight(
                            preview(activity).findViewById<TextClock>(R.id.clock_time) as TextView,
                        ),
                    )
                    root = layout(activity, 400, 850)
                    tap(root, "Шрифт")
                    choose(latestDialog(), 3)
                    assertEquals(450, config(activity).timeFontWeight)
                    assertEquals(450, config(activity).fontWeight)
                    assertTrue(
                        "The Bold preset must remain below weight 500",
                        config(activity).timeFontWeight < 500,
                    )
                    assertEquals(
                        "Choosing Bold must change actual clock glyph weight",
                        450,
                        WidgetTestViews.weight(
                            preview(activity).findViewById<TextClock>(R.id.clock_time) as TextView,
                        ),
                    )
                    root = layout(activity, 400, 850)
                    tap(root, "Формат даты")
                    val picker: AlertDialog = latestDialog()
                    assertTrue(picker.isShowing())
                    assertFalse(
                        "Fixture requires a Russian app with an English phone",
                        AppLocales.appLocale(activity)
                            .getLanguage()
                            .equals(
                                Resources.getSystem()
                                    .getConfiguration()
                                    .getLocales()
                                    .get(0)
                                    .getLanguage(),
                            ),
                    )
                    picker.getListView().setSelection(picker.getListView().getCount() - 1)
                    layoutDialog(picker)
                    assertNotNull(
                        "Date language limitation must be explained",
                        text(
                            requireNotNull(picker.window).getDecorView(),
                            "Названия дней недели и месяцев используют язык телефона.",
                        ),
                    )
                    choose(picker, 3)
                    assertEquals("dd.MM.yyyy", config(activity).dateFormat)

                    root = layout(activity, 400, 850)
                    tap(root, "Формат даты")
                    choose(latestDialog(), 7)
                    val custom: AlertDialog = latestDialog()
                    val format: EditText? =
                        first(requireNotNull(custom.window).getDecorView(), EditText::class.java)
                    format!!.setText("invalid'")
                    custom.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertTrue(custom.isShowing())
                    assertNotNull(format!!.getError())
                    format!!.setText("yyyy/MM/dd")
                    custom.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertEquals("yyyy/MM/dd", config(activity).dateFormat)

                    root = layout(activity, 400, 850)
                    tap(root, "Цвет часов")
                    val colors: AlertDialog = latestDialog()
                    val hex: EditText? =
                        first(requireNotNull(colors.window).getDecorView(), EditText::class.java)
                    hex!!.setText("bad")
                    colors.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertTrue(colors.isShowing())
                    assertNotNull(hex!!.getError())
                    hex!!.setText("#123456")
                    assertEquals(
                        "Typing six digits must not force eight digits",
                        "#123456",
                        hex!!.getText().toString(),
                    )
                    hex!!.setText("#7F123456")
                    colors.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertEquals(0x7f123456, config(activity).timeColor)
                    assertEquals(
                        0x7f123456,
                        (preview(activity).findViewById<TextClock>(R.id.clock_time) as TextView)
                            .getCurrentTextColor(),
                    )

                    root = layout(activity, 400, 850)
                    tap(root, "Раздельные действия по нажатию")
                    root = layout(activity, 400, 850)
                    tap(root, "Нажатие на дату")
                    choose(latestDialog(), 3)
                    assertEquals("none", config(activity).dateTapAction)

                    val edited: WidgetSettings = config(activity)
                    edited.zoneId = "Asia/Kathmandu"
                    edited.cities = arrayOf<String>("Asia/Tokyo", "Europe/London")
                    edited.timeOffsetX = 14
                    edited.dateOffsetY = -7
                    edited.timeFontWeight = 900
                    invokeRender(activity)
                    tap(layout(activity, 400, 850), "Сбросить оформление")
                    latestDialog().getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    val expected: WidgetSettings = WidgetSettings.defaults(false)
                    expected.advancedTap = true
                    expected.dateTapAction = "none"
                    expected.zoneId = "Asia/Kathmandu"
                    expected.cities = arrayOf<String>("Asia/Tokyo", "Europe/London")
                    SettingsTestFixtures.assertSettings(expected, config(activity))
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun worldHasFourIndependentColorEditorsAndFontControls() {
        for (language: String in arrayOf<String>("en", "ru")) {
            AppLocales.setLanguage(context, language)
            Robolectric.buildActivity(MainActivity::class.java)
                .setup()
                .use(
                    { controller ->
                        val activity: MainActivity = controller.get()
                        setWorld(activity)
                        var root: View = layout(activity, 400, 850)
                        WidgetTestViews.revealAll(root)
                        assertNotNull(text(root, if ("ru".equals(language)) "Шрифт" else "Font"))
                        assertNotNull(
                            text(
                                root,
                                if ("ru".equals(language)) "Толщина шрифта" else "Font weight",
                            ),
                        )
                        val days: MutableList<TextView> =
                            texts(root, if ("ru".equals(language)) "День" else "Day")
                        val nights: MutableList<TextView> =
                            texts(root, if ("ru".equals(language)) "Ночь" else "Night")
                        assertEquals(2, days.size)
                        assertEquals(2, nights.size)
                        val hexValues: Array<String> =
                            arrayOf<String>("#FFCC1122", "#FF33CC44", "#CC123456", "#80123456")
                        for (index: Int in hexValues.indices) {
                            root = layout(activity, 400, 850)
                            val label: String =
                                if (index % 2 == 0) (if ("ru".equals(language)) "День" else "Day")
                                else (if ("ru".equals(language)) "Ночь" else "Night")
                            tapView(root, texts(root, label).get(index / 2))
                            val colors: AlertDialog = latestDialog()
                            first(
                                    requireNotNull(colors.window).getDecorView(),
                                    EditText::class.java,
                                )!!
                                .setText(hexValues[index])
                            colors.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                            if (index == 0) {
                                val config: WidgetSettings = config(activity)
                                assertEquals(-0x33eede, config.worldDayTextColor)
                                assertEquals(-0x39393a, config.worldNightTextColor)
                                assertEquals(-0x1, config.worldDayBackgroundColor)
                                assertEquals(-0xeeeeef, config.worldNightBackgroundColor)
                            }
                        }
                        val config: WidgetSettings = config(activity)
                        assertEquals(-0x33eede, config.worldDayTextColor)
                        assertEquals(-0xcc33bc, config.worldNightTextColor)
                        assertEquals(-0x33edcbaa, config.worldDayBackgroundColor)
                        assertEquals(-0x7fedcbaa, config.worldNightBackgroundColor)
                        root = layout(activity, 400, 850)
                        assertLaidOut(root)
                        assertLaidOut(scroll(root)!!.getChildAt(0))
                    },
                )
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w844dp-h390dp-land-mdpi")
    @Throws(Exception::class)
    fun landscapeKeepsEveryControlReachableAndScrollsPreview() {
        checkShortScreen(844, 390, false)
        checkShortScreen(844, 390, true)
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h640dp-port-mdpi")
    @Throws(Exception::class)
    fun smallPortraitKeepsLastClockAndWorldControlsReachable() {
        checkShortScreen(360, 640, false)
        checkShortScreen(360, 640, true)
    }

    @Test
    @Throws(Exception::class)
    fun overviewDisclosuresAndPreviewKeepDraftAndStateAcrossRecreation() {
        AppLocales.setLanguage(context, "ru")
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .use(
                { controller ->
                    var activity: MainActivity = controller.get()
                    var root: View = layout(activity, 400, 850)
                    for (key: String in
                        arrayOf<String>(
                            "time",
                            "date",
                            "appearance",
                            "padding",
                            "alarm",
                            "actions",
                        )) {
                        val heading: View = root.findViewWithTag<View>("section-toggle:" + key)
                        assertNotNull("Every group must have an obvious disclosure", heading)
                        assertTrue(heading.getHeight() >= 48)
                        assertEquals(
                            "The overview should expose the available groups",
                            View.GONE,
                            root.findViewWithTag<View>("section-content:" + key).getVisibility(),
                        )
                    }
                    assertTrue(
                        "Settings must retain a useful first-screen viewport",
                        scroll(root)!!.getHeight() >= root.getHeight() * .45f,
                    )
                    val scene: View = preview(activity)
                    val usableWidth: Int =
                        scene.getWidth() - scene.getPaddingLeft() - scene.getPaddingRight()
                    val naturalHeight: Int =
                        scene.getPaddingTop() +
                            scene.getPaddingBottom() +
                            Math.round(usableWidth * 174f / 334f)
                    assertEquals(
                        "The complete four-column, two-row board must determine the scene's aspect",
                        naturalHeight,
                        scene.getHeight(),
                    )
                    assertLaidOut(root)
                    WidgetTestViews.reveal(root, "time")
                    WidgetTestViews.reveal(root, "date")
                    bringIntoView(root, text(root, "Размер времени"))
                    bringIntoView(root, text(root, "Размер даты"))
                    assertEquals(
                        View.VISIBLE,
                        root.findViewWithTag<View>("section-content:time").getVisibility(),
                    )
                    assertEquals(
                        "Independent lines need independently open controls",
                        View.VISIBLE,
                        root.findViewWithTag<View>("section-content:date").getVisibility(),
                    )
                    assertNull(
                        "The preview must not have a collapse control",
                        root.findViewWithTag<View>("preview-toggle"),
                    )
                    assertNull(root.findViewWithTag<View>("preview-label"))
                    val previewSizes: View = root.findViewWithTag<View>("preview-sizes")
                    assertFalse(
                        "The preview size group must not collapse the scene",
                        previewSizes.performClick(),
                    )
                    assertEquals(View.VISIBLE, preview(activity).getVisibility())
                    config(activity).timeOffsetX = 14
                    config(activity).dateSize = 17
                    controller.recreate()
                    activity = controller.get()
                    root = layout(activity, 400, 850)
                    assertEquals(
                        View.VISIBLE,
                        root.findViewWithTag<View>("section-content:time").getVisibility(),
                    )
                    assertEquals(
                        View.VISIBLE,
                        root.findViewWithTag<View>("section-content:date").getVisibility(),
                    )
                    assertEquals(
                        "The preview must remain open after recreation",
                        View.VISIBLE,
                        preview(activity).getVisibility(),
                    )
                    assertEquals(14, config(activity).timeOffsetX)
                    assertEquals(17, config(activity).dateSize)
                    bringIntoView(root, text(root, "Сдвиг времени по горизонтали"))
                    assertLaidOut(root)
                },
            )
    }

    @Test
    @Config(qualifiers = "en-rUS-w360dp-h640dp-port-mdpi")
    @Throws(Exception::class)
    fun largeTextOverviewAndLongSliderLabelsFitNarrowScreen() {
        RuntimeEnvironment.setFontScale(1.3f)
        AppLocales.setLanguage(context, "ru")
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    var root: View = layout(activity, 360, 640)
                    assertTrue(
                        "Large text must scroll the preview with the settings",
                        descendant(preview(activity), scroll(root)),
                    )
                    assertLaidOut(root)
                    for (key: String in
                        arrayOf<String>(
                            "time",
                            "date",
                            "appearance",
                            "padding",
                            "alarm",
                            "actions",
                        )) {
                        val heading: View = root.findViewWithTag<View>("section-toggle:" + key)
                        bringIntoView(root, heading)
                        assertAllTextFits(heading)
                    }
                    WidgetTestViews.reveal(root, "time")
                    val longLabel: TextView? = text(root, "Сдвиг времени по горизонтали")
                    bringIntoView(root, longLabel)
                    assertAllTextFits(longLabel!!.getParent() as View)
                    assertLaidOut(root)
                    setWorld(activity)
                    config(activity).cities =
                        arrayOf<String>("America/Los_Angeles", "Asia/Riyadh")
                    invokeRender(activity)
                    root = layout(activity, 360, 640)
                    assertLaidOut(root)
                    for (key: String in
                        arrayOf<String>(
                            "cities",
                            "appearance",
                            "foreground",
                            "worldBackground",
                            "actions",
                        )) {
                        val heading: View = root.findViewWithTag<View>("section-toggle:" + key)
                        bringIntoView(root, heading)
                        assertAllTextFits(heading)
                    }
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun sectionSummariesFollowRealSliderDragsAndVisibilitySwitches() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    val root: View = layout(activity, 400, 850)
                    WidgetTestViews.reveal(root, "time")
                    val sizeLabel: TextView? = text(root, "Time size")
                    bringIntoView(root, sizeLabel)
                    val size: android.widget.SeekBar? =
                        first(
                            sizeLabel!!.getParent().getParent() as View,
                            android.widget.SeekBar::class.java,
                        )
                    assertNotNull(size)
                    bringIntoView(root, size)
                    val originalSize: Int = config(activity).textSize
                    val now: Long = android.os.SystemClock.uptimeMillis()
                    var eventTime: Long = now
                    for (action: Int in
                        intArrayOf(
                            android.view.MotionEvent.ACTION_DOWN,
                            android.view.MotionEvent.ACTION_MOVE,
                            android.view.MotionEvent.ACTION_UP,
                        )) {
                        val x: Float =
                            if (action == android.view.MotionEvent.ACTION_DOWN)
                                size!!.getWidth() * .25f
                            else (size!!.width - size!!.paddingRight).toFloat()
                        val event: android.view.MotionEvent =
                            android.view.MotionEvent.obtain(
                                now,
                                eventTime,
                                action,
                                x,
                                size!!.getHeight() / 2f,
                                0,
                            )
                        eventTime += 25
                        assertTrue(size!!.dispatchTouchEvent(event))
                        event.recycle()
                    }
                    Shadows.shadowOf(Looper.getMainLooper())
                        .idleFor(java.time.Duration.ofMillis(100))
                    assertTrue(
                        "The real drag must increase the clock size",
                        config(activity).textSize > originalSize,
                    )
                    val timeHeading: View = root.findViewWithTag<View>("section-toggle:time")
                    bringIntoView(root, timeHeading)
                    timeHeading.performClick()
                    assertNotNull(
                        "Summary must show the new size",
                        text(
                            timeHeading,
                            "Size " + config(activity).textSize + " · weight 450 · HH:mm",
                        ),
                    )
                    tap(root, "Show date")
                    assertFalse(config(activity).showDate)
                    val dateHeading: View = root.findViewWithTag<View>("section-toggle:date")
                    bringIntoView(root, dateHeading)
                    dateHeading.performClick()
                    assertNotNull(
                        "Date summary must follow its switch",
                        text(
                            root.findViewWithTag<View>("section-toggle:date"),
                            "Hidden · format, size and position",
                        ),
                    )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun formatSamplesMatchNativePhoneClockAndAlarmAppLocale() {
        AppLocales.setLanguage(context, "ru")
        Locale.setDefault(Locale.ENGLISH)
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        // Android's shadow clock starts in 1970 while java.util.Date reads the host clock.
        // Align them before creating any Activity timers for the native TextClock oracle.
        assertTrue(android.os.SystemClock.setCurrentTimeMillis(java.util.Date().getTime()))
        val trigger = System.currentTimeMillis() + 97 * 60000
        val alarms = context.getSystemService(android.app.AlarmManager::class.java)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val operation = clockAlarm(context, 31001,
            android.content.Intent(context, UpdateReceiver::class.java).setAction("FORMAT_TEST_ALARM"),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        alarms.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(trigger, operation), operation)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    config(activity).zoneId = "America/Los_Angeles"
                    val root: View = layout(activity, 400, 850)
                    tap(root, "Формат даты")
                    val date: AlertDialog = latestDialog()
                    val nativeClock: android.widget.TextClock = android.widget.TextClock(context)
                    nativeClock.setTimeZone("America/Los_Angeles")
                    nativeClock.setFormat12Hour("EEE, d MMM")
                    nativeClock.setFormat24Hour("EEE, d MMM")
                    val dateChoice: String = date.getListView().getAdapter().getItem(0).toString()
                    assertTrue(
                        dateChoice + " differs from native " + nativeClock.getText(),
                        dateChoice.endsWith(nativeClock.getText().toString()),
                    )
                    assertEquals(1, date.getListView().getFooterViewsCount())
                    date.dismiss()
                    tap(root, "Формат времени будильника")
                    val alarm: AlertDialog = latestDialog()
                    val expected: String =
                        java.text.SimpleDateFormat("HH:mm", Locale("ru")).format(java.util.Date(trigger))
                    assertTrue(
                        alarm.getListView().getAdapter().getItem(0).toString().endsWith(expected),
                    )
                    assertEquals(
                        "Alarm formatting uses the app language",
                        0,
                        alarm.getListView().getFooterViewsCount(),
                    )
                    alarm.dismiss()
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun customClockFormatsRejectLiteralYearTokensAndAcceptNativeStandaloneNames() {
        AppLocales.setLanguage(context, "en")
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    tap(layout(activity, 400, 850), "Date format")
                    choose(latestDialog(), 7)
                    var date: AlertDialog = latestDialog()
                    var input: EditText? =
                        first(requireNotNull(date.window).getDecorView(), EditText::class.java)
                    input!!.setText("YYYY-MM-dd")
                    date.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertTrue("TextClock would print unsupported YYYY literally", date.isShowing())
                    assertNotNull(input!!.getError())
                    input!!.setText("'YYYY' yyyy-MM-dd")
                    date.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertFalse("Quoted words must stay valid native literals", date.isShowing())
                    assertEquals("'YYYY' yyyy-MM-dd", config(activity).dateFormat)

                    tap(layout(activity, 400, 850), "Date format")
                    choose(latestDialog(), 7)
                    date = latestDialog()
                    input = first(requireNotNull(date.window).getDecorView(), EditText::class.java)
                    input!!.setText("ccccc LLLLL 'o''clock'")
                    date.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertFalse(
                        "Native narrow/standalone fields must be accepted",
                        date.isShowing(),
                    )
                    assertEquals("ccccc LLLLL 'o''clock'", config(activity).dateFormat)
                    val rendered: android.widget.TextClock =
                        preview(activity).findViewById<android.widget.TextClock>(R.id.clock_date)
                    val previousLocale = Locale.getDefault()
                    val phoneLocale = AppLocales.phoneLocale(activity)
                    val expected =
                        try {
                            Locale.setDefault(phoneLocale)
                            val calendar =
                                java.util.Calendar.getInstance(
                                    java.util.TimeZone.getTimeZone(config(activity).effectiveZone()),
                                    phoneLocale,
                                )
                            android.text.format.DateFormat.format(
                                config(activity).dateFormat,
                                calendar,
                            ).toString()
                        } finally {
                            Locale.setDefault(previousLocale)
                        }
                    assertEquals(expected, rendered.getText().toString())
                    WidgetSettingsStore.save(context, 3502, false, config(activity))
                    val placed: View =
                        WidgetRenderer.clock(context, 3502).apply(context, FrameLayout(context))
                    assertEquals(
                        "ccccc LLLLL 'o''clock'",
                        (placed.findViewById<android.widget.TextClock>(R.id.clock_date)
                                as android.widget.TextClock)
                            .getFormat24Hour()
                            .toString(),
                    )

                    tap(layout(activity, 400, 850), "Alarm time format")
                    val alarmOptions = latestDialog()
                    assertEquals(3, alarmOptions.listView.adapter.count)
                    choose(alarmOptions, alarmOptions.listView.adapter.count - 1)
                    val alarm: AlertDialog = latestDialog()
                    first(requireNotNull(alarm.window).getDecorView(), EditText::class.java)!!
                        .setText(
                            "YYYY-MM-dd",
                        )
                    alarm.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertFalse(
                        "Alarm uses the complete SimpleDateFormat vocabulary",
                        alarm.isShowing(),
                    )
                    assertEquals("YYYY-MM-dd", config(activity).alarmFormat)
                },
            )
    }

    @Throws(Exception::class)
    private fun checkShortScreen(width: Int, height: Int, world: Boolean) {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    if (world) setWorld(activity)
                    val root: View = layout(activity, width, height)
                    val scroll: ScrollView? = scroll(root)
                    assertTrue(
                        "Settings viewport collapsed at " + width + "x" + height,
                        scroll!!.getHeight() > 0,
                    )
                    assertEquals(
                        "Small screens must scroll the compact preview with the controls",
                        height < 720,
                        descendant(preview(activity), scroll),
                    )
                    WidgetTestViews.reveal(root, if (world) "worldBackground" else "alarm")
                    val last: TextView?
                    if (world) {
                        val nights: MutableList<TextView> = texts(root, "Night")
                        last = nights.get(nights.size - 1)
                    } else last = text(root, "Alarm vertical offset")
                    assertNotNull(last)
                    revealSection(root, last)
                    // Reachability is independent of the framework's smooth-scroll animation clock.
                    scroll!!.setSmoothScrollingEnabled(false)
                    assertTrue(
                        "End-of-form scrolling did not move",
                        scroll!!.fullScroll(View.FOCUS_DOWN),
                    )
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    WidgetTestViews.prepare(root)
                    val rect: Rect = Rect()
                    assertTrue(
                        ("Last setting cannot be brought into view; scrollY=" +
                            scroll!!.getScrollY() +
                            ", viewport=" +
                            scroll!!.getHeight() +
                            ", body=" +
                            scroll!!.getChildAt(0).getHeight()),
                        last!!.getGlobalVisibleRect(rect),
                    )
                    assertTrue(
                        "Last setting is clipped vertically",
                        rect.height() >= last!!.getHeight() - 1,
                    )
                    assertLaidOut(root)
                },
            )
    }

    @Throws(Exception::class)
    private fun setWorld(activity: MainActivity) {
        tap(
            layout(activity, 400, 850),
            AppLocales.text(
                activity,
                AppLocales.resourceLocale(activity),
                R.string.world_widget_name,
            ),
        )
        SettingsCodec.readFrom(
            config(activity),
            SettingsCodec.snapshot(WidgetSettings.defaults(true)),
        )
        config(activity).cities =
            arrayOf("America/New_York", "Asia/Hong_Kong", "Europe/London", "Asia/Riyadh")
        invokeRender(activity)
    }

    companion object {

        private fun assertAllTextFits(root: View) {
            WidgetTestViews.prepare(root)
            if (root is TextView) {
                val value: TextView = root as TextView
                val lines: android.text.Layout = value.getLayout()
                assertNotNull(lines)
                for (line: Int in 0 until lines.getLineCount()) assertEquals(
                    "Essential label is ellipsized: " + value.getText(),
                    0,
                    lines.getEllipsisCount(line),
                )
                assertTrue(
                    "Label is vertically clipped: " + value.getText(),
                    (lines.getHeight() <=
                        value.getHeight() -
                            value.getCompoundPaddingTop() -
                            value.getCompoundPaddingBottom()),
                )
            }
            if (root is ViewGroup)
                for (index: Int in 0 until (root as ViewGroup).getChildCount()) assertAllTextFits(
                    (root as ViewGroup).getChildAt(index),
                )
        }

        @Throws(Exception::class)
        private fun invokeRender(activity: MainActivity) {
            val render: Method = MainActivity::class.java.getDeclaredMethod("render")
            render.setAccessible(true)
            render.invoke(activity)
        }

        @Throws(Exception::class)
        private fun config(activity: MainActivity): WidgetSettings {
            return activity.session.settings
        }

        private fun preview(activity: MainActivity): FrameLayout =
            requireNotNull(
                activity
                    .findViewById<View>(android.R.id.content)
                    .findViewWithTag<FrameLayout>("preview-stage"),
            )

        private fun layout(activity: MainActivity, width: Int, height: Int): View {
            val root: View = requireNotNull(activity.window).getDecorView()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, width, height)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            WidgetTestViews.prepare(root)
            return root
        }

        private fun choose(dialog: AlertDialog, position: Int) {
            val list: ListView = dialog.getListView()
            list.performItemClick(
                list.getAdapter().getView(position, null, list),
                position,
                list.getAdapter().getItemId(position),
            )
        }

        private fun latestDialog(): AlertDialog {
            // OnShow installs the real button listener through the Android main event loop.
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val dialog: AlertDialog = ShadowAlertDialog.getLatestAlertDialog()
            assertNotNull(dialog)
            layoutDialog(dialog)
            return dialog
        }

        private fun layoutDialog(dialog: AlertDialog) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val root: View = requireNotNull(dialog.window).getDecorView()
            root.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(620, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 400, 620)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            WidgetTestViews.prepare(root)
        }

        private fun descendant(child: View, parent: View?): Boolean {
            var candidate: ViewParent? = child.getParent()
            while (candidate != null) {
                if (candidate === parent) return true
                candidate = candidate!!.getParent()
            }
            return false
        }

        private fun scroll(root: View): ScrollView? {
            val result: ScrollView? = first(root, ScrollView::class.java)
            assertNotNull(result)
            return result
        }

        private fun assertLaidOut(root: View) {
            assertTrue(root.width > 0)
            assertTrue(root.height > 0)
            WidgetTestViews.prepare(root)
        }
    }
}

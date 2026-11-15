package app.noonlet

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.LocaleManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Bundle
import android.os.LocaleList
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextClock
import android.widget.TextView
import java.text.SimpleDateFormat
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import org.junit.After
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
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowDialog
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34, 36],
    qualifiers = "en-rUS-w400dp-h850dp-mdpi",
    shadows = [CountedLocaleManager::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class LanguageTransitionTest {
    private lateinit var context: Context
    private var manager: AppWidgetManager? = null
    private var originalSystem: Configuration? = null

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        manager = AppWidgetManager.getInstance(context)
        originalSystem = Configuration(Resources.getSystem().getConfiguration())
        for (file: String in
            arrayOf<String>(
                "noonlet_widgets",
                "MainActivity",
                "widget_drafts",
                "widget_configuration",
            )) context.getSharedPreferences(file, 0).edit().clear().commit()
        systemLocale("en-US")
        AppLocales.setLanguage(context, "en")
        val clock: WidgetSettings = WidgetSettings()
        clock.zoneId = "UTC"
        WidgetSettingsStore.save(context, CLOCK, false, clock)
        val world: WidgetSettings = WidgetSettings.defaults(true)
        world.cities = arrayOf<String>("Asia/Riyadh")
        WidgetSettingsStore.save(context, WORLD, true, world)
        bind(CLOCK, false)
        bind(WORLD, true)
    }

    @After
    fun restoreSystem() {
        val system: Resources = Resources.getSystem()
        system.updateConfiguration(originalSystem, system.getDisplayMetrics())
    }

    @Test
    @Throws(Exception::class)
    fun phoneEditorUsesPortraitAndPreservesDialogsDuringGeometryChanges() {
        val info: ActivityInfo =
            context
                .getPackageManager()
                .getActivityInfo(ComponentName(context, MainActivity::class.java), 0)
        assertEquals(
            ActivityInfo.CONFIG_LOCALE or
                ActivityInfo.CONFIG_LAYOUT_DIRECTION or
                ActivityInfo.CONFIG_SCREEN_LAYOUT,
            info.configChanges and
                (ActivityInfo.CONFIG_LOCALE or
                    ActivityInfo.CONFIG_LAYOUT_DIRECTION or
                    ActivityInfo.CONFIG_SCREEN_LAYOUT),
        )
        assertEquals(
            "Geometry changes keep the existing editor instance",
            ActivityInfo.CONFIG_ORIENTATION,
            info.configChanges and ActivityInfo.CONFIG_ORIENTATION,
        )
        assertEquals(
            ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE,
            info.configChanges and
                (ActivityInfo.CONFIG_SCREEN_SIZE or ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE),
        )
    }

    @Test
    fun systemAndExplicitRussianKeepTheEditorAndDoNotRedeliverEquivalentWidgets() {
        systemLocale("ru")
        AppLocales.setLanguage(context, "system")
        update(Intent.ACTION_MY_PACKAGE_REPLACED)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    val root: View = layout(activity)
                    val stage: WidgetPreviewStage = stage(root)
                    val shown: TextClock = root.findViewById<TextClock>(R.id.clock_time)
                    val format: CharSequence = shown.getFormat24Hour()
                    val pending: WidgetSettings = WidgetSettingsStore.load(context, CLOCK, false)
                    pending.timeColor = Color.RED
                    WidgetSettingsStore.save(context, CLOCK, false, pending)
                    val before: Int = hostClock().getCurrentTextColor()
                    choose(activity, "ru")
                    assertEquals("ru", AppLocales.language(context))
                    assertEquals(
                        "ru",
                        context
                            .getSystemService(LocaleManager::class.java)
                            .getApplicationLocales()
                            .get(0)
                            .getLanguage(),
                    )
                    assertSame(root, layout(activity))
                    assertSame(stage, stage(root))
                    assertSame(format, shown.getFormat24Hour())
                    activity.onConfigurationChanged(
                        Configuration(activity.getResources().getConfiguration()),
                    )
                    update(Intent.ACTION_LOCALE_CHANGED)
                    assertEquals(
                        "An equivalent language event must not redo an already delivered host",
                        before,
                        hostClock().getCurrentTextColor(),
                    )
                    assertSame(root, layout(activity))
                    assertSame(format, shown.getFormat24Hour())
                    choose(activity, "system")
                    update(Intent.ACTION_LOCALE_CHANGED)
                    assertEquals("system", AppLocales.language(context))
                    assertTrue(
                        context
                            .getSystemService(LocaleManager::class.java)
                            .getApplicationLocales()
                            .isEmpty(),
                    )
                    assertSame(root, layout(activity))
                    assertSame(format, shown.getFormat24Hour())
                    assertEquals(before, hostClock().getCurrentTextColor())
                },
            )
    }

    @Test
    fun actualLanguageSelectionRendersOnceAndUsesThePlatformReceiverForHosts() {
        update(Intent.ACTION_MY_PACKAGE_REPLACED)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    val english: View = layout(activity)
                    assertEquals("Riyadh", hostCity())
                    choose(activity, "ru")
                    val russian: View = layout(activity)
                    assertNotSame(english, russian)
                    assertNotNull(text(russian, "Часы"))
                    assertEquals(
                        "The Activity does not duplicate Android's package-targeted locale delivery",
                        "Riyadh",
                        hostCity(),
                    )
                    activity.onConfigurationChanged(
                        Configuration(activity.getResources().getConfiguration()),
                    )
                    assertSame(
                        "A later framework callback must not render the same language a second time",
                        russian,
                        layout(activity),
                    )
                    update(Intent.ACTION_LOCALE_CHANGED)
                    assertEquals("Эр-Рияд", hostCity())
                    choose(activity, "en")
                    choose(activity, "ru")
                    update(Intent.ACTION_LOCALE_CHANGED)
                    assertEquals("Эр-Рияд", hostCity())
                    val pending: WidgetSettings = WidgetSettingsStore.load(context, CLOCK, false)
                    pending.timeColor = Color.GREEN
                    WidgetSettingsStore.save(context, CLOCK, false, pending)
                    update(Intent.ACTION_LOCALE_CHANGED)
                    update(Intent.ACTION_LOCALE_CHANGED)
                    assertNotEquals(
                        "Repeated queued broadcasts reuse the last successful visual language",
                        Color.GREEN,
                        hostClock().getCurrentTextColor(),
                    )
                },
            )
    }

    @Test
    fun anUnknownExternalLocaleMustDeliverBeforeItCanBeDeduplicated() {
        val pending: WidgetSettings = WidgetSettingsStore.load(context, CLOCK, false)
        pending.timeColor = Color.RED
        WidgetSettingsStore.save(context, CLOCK, false, pending)
        context
            .getSystemService(LocaleManager::class.java)
            .setApplicationLocales(LocaleList.forLanguageTags("ru"))
        assertEquals(
            "The external writer has not changed the private mirror yet",
            "en",
            AppLocales.language(context),
        )
        update(Intent.ACTION_LOCALE_CHANGED)
        assertEquals("ru", AppLocales.language(context))
        assertEquals("Эр-Рияд", hostCity())
        assertEquals(
            "A missing delivery marker is unknown, never a reason to skip",
            Color.RED,
            hostClock().getCurrentTextColor(),
        )
        pending.timeColor = Color.GREEN
        WidgetSettingsStore.save(context, CLOCK, false, pending)
        update(Intent.ACTION_LOCALE_CHANGED)
        assertEquals(Color.RED, hostClock().getCurrentTextColor())
        update(Intent.ACTION_TIMEZONE_CHANGED)
        assertEquals(
            "Non-language events always deliver their actual widget state",
            Color.GREEN,
            hostClock().getCurrentTextColor(),
        )
    }

    @Test
    fun phoneLocaleChangesRefreshDatesEvenWhenTheVisibleAppLanguageDoesNotChange() {
        update(Intent.ACTION_MY_PACKAGE_REPLACED)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    activity.preview.clock = Clock.fixed(Instant.parse("2026-10-30T12:34:00Z"), ZoneOffset.UTC)
                    activity.preview.refresh(true, null)
                    val root: View = layout(activity)
                    val date: TextClock = root.findViewById<TextClock>(R.id.clock_date)
                    val english: CharSequence = date.getFormat24Hour()
                    assertTrue(english.toString().contains("Fri"))
                    systemLocale("ru-RU")
                    activity.onConfigurationChanged(
                        Configuration(activity.getResources().getConfiguration()),
                    )
                    assertSame("The app is still explicitly English", root, layout(activity))
                    assertEquals("en", AppLocales.language(context))
                    assertNotSame(english, date.getFormat24Hour())
                    assertTrue(
                        "The localized literal follows the phone, not the English UI",
                        date.getFormat24Hour().toString().contains("пт"),
                    )
                    val pending: WidgetSettings = WidgetSettingsStore.load(context, CLOCK, false)
                    pending.timeColor = Color.RED
                    WidgetSettingsStore.save(context, CLOCK, false, pending)
                    update(Intent.ACTION_LOCALE_CHANGED)
                    assertEquals(Color.RED, hostClock().getCurrentTextColor())
                },
            )
    }

    @Test
    fun regionalPhoneChangesRefreshDatesWithoutRebuildingTheSupportedLanguageEditor() {
        systemLocale("de-DE")
        AppLocales.setLanguage(context, "system")
        val instant = Instant.parse("2026-01-15T12:34:00Z")
        val german = SimpleDateFormat("MMMM", Locale.GERMANY).format(Date.from(instant))
        val austrian =
            SimpleDateFormat("MMMM", Locale.forLanguageTag("de-AT")).format(Date.from(instant))
        assertNotEquals("The fixture must distinguish regional month names", german, austrian)
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            activity.preview.clock = Clock.fixed(instant, ZoneOffset.UTC)
            val settings: WidgetSettings = editorSession(activity).settings
            settings.dateFormat = "MMMM"
            activity.preview.refresh(true, null)
            val root = layout(activity)
            val preview = stage(root)
            val date = root.findViewById<TextClock>(R.id.clock_date)
            val previous = date.format24Hour
            assertTrue(previous.toString().contains(german))
            root.findViewWithTag<View>("section-toggle:date").performClick()
            val controls = root.findViewWithTag<View>("section-content:date")
            assertEquals(View.VISIBLE, controls.visibility)

            systemLocale("de-AT")
            activity.onConfigurationChanged(Configuration(activity.resources.configuration))

            assertSame(root, layout(activity))
            assertSame(preview, stage(root))
            assertSame(settings, editorSession(activity).settings)
            assertEquals(View.VISIBLE, controls.visibility)
            assertEquals("de-AT", AppLocales.phoneLocale(activity).toLanguageTag())
            assertEquals("de-AT", AppLocales.appLocale(activity).toLanguageTag())
            assertNotSame(previous, date.format24Hour)
            assertTrue(date.format24Hour.toString().contains(austrian))
            assertEquals("system", AppLocales.language(activity))
        }
    }

    @Test
    fun fullAppLocaleChangesRefreshAlarmTextAndInvalidateDialogsWithTheSameEnglishUi() {
        systemLocale("ja-JP")
        AppLocales.setLanguage(context, "system")
        val alarm: WidgetSettings = WidgetSettingsStore.load(context, CLOCK, false)
        alarm.showAlarm = true
        alarm.showNoAlarm = true
        alarm.alarmFormat = "EEEE, MMMM"
        WidgetSettingsStore.save(context, CLOCK, false, alarm)
        val alarms: AlarmManager = context.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val operation: PendingIntent =
            clockAlarm(
                context,
                28103,
                Intent(context, UpdateReceiver::class.java).setAction("LANGUAGE_TEST_ALARM"),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val trigger: Long = System.currentTimeMillis() + 3600000
        alarms.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, operation), operation)
        val japanese: String =
            SimpleDateFormat(alarm.alarmFormat, Locale.JAPAN).format(Date(trigger))
        val english: String =
            SimpleDateFormat(alarm.alarmFormat, Locale.ENGLISH).format(Date(trigger))
        assertNotEquals(
            "The fixture must distinguish the actual localized alarm",
            japanese,
            english,
        )
        update(Intent.ACTION_MY_PACKAGE_REPLACED)
        try {
            Robolectric.buildActivity(MainActivity::class.java)
                .setup()
                .visible()
                .use(
                    { controller ->
                        val activity: MainActivity = controller.get()
                        val root: View = layout(activity)
                        assertNotNull(text(root, "Clock"))
                        assertEquals(
                            japanese,
                            (root.findViewById<View>(R.id.clock_alarm) as TextView)
                                .getText()
                                .toString(),
                        )
                        assertEquals(
                            japanese,
                            (Shadows.shadowOf(manager)
                                    .getViewFor(CLOCK)
                                    .findViewById<View>(R.id.clock_alarm) as TextView)
                                .getText()
                                .toString(),
                        )
                        choose(activity, "en")
                        assertSame(
                            "Both Japanese fallback and explicit English have the same UI",
                            root,
                            layout(activity),
                        )
                        assertEquals(
                            english,
                            (root.findViewById<View>(R.id.clock_alarm) as TextView)
                                .getText()
                                .toString(),
                        )
                        update(Intent.ACTION_LOCALE_CHANGED)
                        assertEquals(
                            "The full app locale belongs in the host signature",
                            english,
                            (Shadows.shadowOf(manager)
                                    .getViewFor(CLOCK)
                                    .findViewById<View>(R.id.clock_alarm) as TextView)
                                .getText()
                                .toString(),
                        )
                    },
                )
            AppLocales.setLanguage(context, "system")
            update(Intent.ACTION_MY_PACKAGE_REPLACED)
            Robolectric.buildActivity(MainActivity::class.java)
                .setup()
                .visible()
                .use(
                    { controller ->
                        val activity: MainActivity = controller.get()
                        val root: View = layout(activity)
                        root.findViewWithTag<View>("section-toggle:appearance").performClick()
                        var row: View? = text(layout(activity), "Font")
                        assertNotNull(row)
                        while (!row!!.hasOnClickListeners() && row!!.getParent() is View) row =
                            row!!.getParent() as View
                        assertTrue(row!!.performClick())
                        val dialog: AlertDialog = ShadowDialog.getLatestDialog() as AlertDialog
                        assertNotNull(dialog)
                        assertTrue(dialog.isShowing())
                        context
                            .getSystemService(LocaleManager::class.java)
                            .setApplicationLocales(LocaleList.forLanguageTags("en"))
                        activity.onConfigurationChanged(
                            Configuration(activity.getResources().getConfiguration()),
                        )
                        assertFalse(
                            "A dialog bound to the old resource locale must close even with identical English labels",
                            dialog.isShowing(),
                        )
                        assertSame(root, layout(activity))
                        assertEquals(
                            english,
                            (root.findViewById<View>(R.id.clock_alarm) as TextView)
                                .getText()
                                .toString(),
                        )
                    },
                )
        } finally {
            alarms.cancel(operation)
        }
    }

    @Test
    fun firstResumeReusesTheBuiltPreviewWhileRealResumeAndClockJumpsRefreshOnce() {
        val clock: MutableClock = MutableClock("2026-10-30T12:34:00Z")
        Robolectric.buildActivity(MainActivity::class.java)
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    activity.preview.clock = clock
                    controller.create().start().postCreate(null)
                    val root: View = layout(activity)
                    val shown: TextClock = root.findViewById<TextClock>(R.id.clock_time)
                    val format: CharSequence = shown.getFormat24Hour()
                    CountedLocaleManager.systemReads = 0
                    controller.resume().visible()
                    layout(activity)
                    assertSame(shown, root.findViewById<View>(R.id.clock_time))
                    assertSame(format, shown.getFormat24Hour())
                    assertEquals(
                        "Initial resume resolves one phone snapshot without building another native preview",
                        1,
                        CountedLocaleManager.systemReads,
                    )
                    controller.pause()
                    CountedLocaleManager.systemReads = 0
                    controller.resume()
                    layout(activity)
                    assertTrue(
                        "A real return still resolves the current phone date format",
                        CountedLocaleManager.systemReads > 0,
                    )
                    assertTrue(
                        "One return must perform at most one complete preview",
                        CountedLocaleManager.systemReads <= 2,
                    )
                    assertNotSame(format, shown.getFormat24Hour())
                },
            )
        val stable: MutableClock = MutableClock("2026-10-30T12:34:00Z")
        Robolectric.buildActivity(MainActivity::class.java)
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    activity.preview.clock = stable
                    controller.create().start().postCreate(null)
                    val root: View = layout(activity)
                    val date: TextClock = root.findViewById<TextClock>(R.id.clock_date)
                    assertTrue(date.getFormat24Hour().toString().contains("Fri"))
                    systemLocale("ru-RU")
                    activity.onConfigurationChanged(
                        Configuration(activity.getResources().getConfiguration()),
                    )
                    assertSame(
                        "A not-yet-visible locale callback leaves the first scene attached",
                        root,
                        layout(activity),
                    )
                    CountedLocaleManager.systemReads = 0
                    controller.resume().visible()
                    layout(activity)
                    assertSame(
                        "Explicit English does not need a new editor tree",
                        root,
                        layout(activity),
                    )
                    assertTrue(
                        "The first resume still notices a changed phone locale within the same minute",
                        date.getFormat24Hour().toString().contains("пт"),
                    )
                    assertEquals(
                        "One locale snapshot serves the refreshed preview",
                        1,
                        CountedLocaleManager.systemReads,
                    )
                },
            )
        for (jump: String in arrayOf<String>("2026-10-30T12:35:00Z", "2026-10-30T12:33:00Z")) {
            val moving: MutableClock = MutableClock("2026-10-30T12:34:59Z")
            Robolectric.buildActivity(MainActivity::class.java)
                .use(
                    { controller ->
                        val activity: MainActivity = controller.get()
                        activity.preview.clock = moving
                        controller.create().start().postCreate(null)
                        val shown: TextClock =
                            layout(activity).findViewById<TextClock>(R.id.clock_time)
                        assertEquals("12:34", shown.getText().toString())
                        moving.now = Instant.parse(jump)
                        CountedLocaleManager.systemReads = 0
                        controller.resume().visible()
                        layout(activity)
                        assertEquals(
                            if (jump.contains("12:35")) "12:35" else "12:33",
                            shown.getText().toString(),
                        )
                        assertTrue(
                            CountedLocaleManager.systemReads > 0 &&
                                CountedLocaleManager.systemReads <= 2,
                        )
                    },
                )
        }
    }

    @Test
    @Config(sdk = [26])
    fun oldAndroidKeepsPrivateLanguageSelectionAndDirectWidgetDelivery() {
        update(Intent.ACTION_MY_PACKAGE_REPLACED)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    val english: View = layout(activity)
                    choose(activity, "ru")
                    assertEquals("ru", AppLocales.language(context))
                    assertNotSame(english, layout(activity))
                    assertNotNull(text(layout(activity), "Часы"))
                    assertEquals(
                        "Without LocaleManager, the in-app choice must deliver directly",
                        "Эр-Рияд",
                        hostCity(),
                    )
                },
            )
    }

    @Test
    fun pinReconciliationRefreshesTheNewTargetOnlyOnce() {
        Robolectric.buildActivity(MainActivity::class.java)
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    val instant = Instant.parse("2026-10-30T12:34:00Z")
                    activity.preview.clock = Clock.fixed(instant, ZoneOffset.UTC)
                    controller.setup().visible()
                    val previous: View = layout(activity)
                    val next: Int = CLOCK + 2
                    val target: WidgetSettings = WidgetSettings()
                    target.timeColor = Color.GREEN
                    WidgetSettingsStore.save(context, next, false, target)
                    bind(next, false)
                    activity
                        .getPreferences(Context.MODE_PRIVATE)
                        .edit()
                        .putInt("clock_target", next)
                        .commit()
                    CountedLocaleManager.systemReads = 0
                    val reconciliation: Runnable =
                        ReflectionHelpers.getField(activity, "pinRefresh")
                    reconciliation.run()
                    val current: View = layout(activity)
                    assertNotSame(previous, current)
                    assertEquals(next, editorSession(activity).homeTargetId)
                    assertEquals(
                        instant.toEpochMilli(),
                        activity.preview.renderedAt,
                    )
                    assertEquals(
                        Color.GREEN,
                        (current.findViewById<View>(R.id.clock_time) as TextClock)
                            .getCurrentTextColor(),
                    )
                    assertEquals(
                        "Rendering and localization share one locale snapshot",
                        1,
                        CountedLocaleManager.systemReads,
                    )
                },
            )
    }

    @Test
    fun reconciliationCrossingTheMinuteMustNotArmTheFollowingMinuteWithOldText() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    controller.pause()
                    val next: Int = CLOCK + 2
                    val target: WidgetSettings = WidgetSettings()
                    target.zoneId = "UTC"
                    WidgetSettingsStore.save(context, next, false, target)
                    bind(next, false)
                    activity
                        .getPreferences(Context.MODE_PRIVATE)
                        .edit()
                        .putInt("clock_target", next)
                        .commit()
                    activity.preview.clock = CrossingClock()
                    controller.resume()
                    val root: View = layout(activity)
                    assertEquals(next, editorSession(activity).homeTargetId)
                    assertEquals(
                        "Even an already rebuilt scene must show the current minute",
                        "12:35",
                        (root.findViewById<View>(R.id.clock_time) as TextClock)
                            .getText()
                            .toString(),
                    )
                },
            )
    }

    @Test
    fun customActionLabelsWaitForActualResourceLocalesWithoutRebuildingThePreview() {
        for (scenario: Int in 0..2) {
            val advanced: Boolean = scenario == 1
            val promoted: Boolean = scenario == 2
            systemLocale(if (promoted) "ja-JP,en" else "ja-JP")
            AppLocales.setLanguage(context, "system")
            val component: ComponentName =
                ComponentName("example.locale", "example.locale.Launcher")
            val info: android.content.pm.ActivityInfo =
                Shadows.shadowOf(context.getPackageManager()).addActivityIfNotPresent(component)
            info.enabled = true
            info.exported = true
            info.nonLocalizedLabel = "Initial action"
            Shadows.shadowOf(context.getPackageManager()).addOrUpdateActivity(info)
            val action: String = "app:" + component.flattenToString()
            val target: WidgetSettings = WidgetSettings()
            target.tapAction = if (advanced) "clock" else action
            target.advancedTap = advanced
            if (advanced) {
                target.alarmTapAction = action
                target.dateTapAction = target.alarmTapAction
                target.timeTapAction = target.dateTapAction
            }
            WidgetSettingsStore.save(context, CLOCK, false, target)
            BaseWidgetProvider.updateWidget(context, CLOCK, false)
            Robolectric.buildActivity(MainActivity::class.java)
                .setup()
                .visible()
                .use(
                    { controller ->
                        val activity: MainActivity = controller.get()
                        val japanese: Configuration =
                            Configuration(activity.getResources().getConfiguration())
                        activity.preview.clock = Clock.fixed(Instant.parse("2026-06-15T09:41:20Z"), ZoneOffset.UTC)
                        activity.preview.refresh(false)
                        japanese.setLocales(
                            LocaleList.forLanguageTags(if (promoted) "en,ja-JP" else "ja-JP"),
                        )
                        activity
                            .getResources()
                            .updateConfiguration(
                                japanese,
                                activity.getResources().getDisplayMetrics(),
                            )
                        val foreignJapanese: Configuration = Configuration(japanese)
                        foreignJapanese.setLocale(Locale.JAPAN)
                        val japaneseLabels: Resources =
                            if (promoted)
                                activity.createConfigurationContext(foreignJapanese).getResources()
                            else activity.getResources()
                        publishResourceLabel(info, japaneseLabels)
                        assertEquals(
                            "The controlled PackageManager now resolves the actual Japanese package resource",
                            "Japanese action",
                            TapActions.label(activity, action, Locale.ENGLISH),
                        )
                        activity.onConfigurationChanged(japanese)
                        val root: View = layout(activity)
                        WidgetTestViews.reveal(root, "actions")
                        assertNotNull(text(root, "Japanese action"))
                        val shown: TextClock = root.findViewById<TextClock>(R.id.clock_time)
                        val format: CharSequence = shown.getFormat24Hour()
                        val editor: Any = editorSession(activity).settings
                        val scene: View = stage(root)
                        val content: View = checkNotNull(stage(root).getWidgetContent())
                        val pending: WidgetSettings =
                            WidgetSettingsStore.load(context, CLOCK, false)
                        pending.timeColor = Color.RED
                        WidgetSettingsStore.save(context, CLOCK, false, pending)
                        val hostColor: Int = hostClock().getCurrentTextColor()
                        choose(activity, "en")
                        assertEquals(
                            "Logical app language can commit before its Resources callback",
                            "en",
                            AppLocales.language(context),
                        )
                        assertEquals(
                            if (promoted) "en" else "ja",
                            activity
                                .getResources()
                                .getConfiguration()
                                .getLocales()
                                .get(0)
                                .getLanguage(),
                        )
                        assertNotNull(
                            "Do not mark old PM labels as if they had already been rebound in English",
                            text(root, "Japanese action"),
                        )
                        val oldResourceDialog: AlertDialog =
                            AppDialogBuilder(activity)
                                .setTitle("Resource-bound dialog")
                                .setMessage("Pending")
                                .show()
                        val previousOwnTags: String =
                            activity.getResources().getConfiguration().getLocales().toLanguageTags()
                        val english: Configuration = Configuration(japanese)
                        if (!promoted) english.setLocale(Locale.ENGLISH)
                        activity
                            .getResources()
                            .updateConfiguration(
                                english,
                                activity.getResources().getDisplayMetrics(),
                            )
                        val foreignEnglish: Configuration = Configuration(english)
                        foreignEnglish.setLocale(Locale.ENGLISH)
                        val englishLabels: Resources =
                            if (promoted)
                                activity.createConfigurationContext(foreignEnglish).getResources()
                            else activity.getResources()
                        publishResourceLabel(info, englishLabels)
                        assertEquals(
                            "The PackageManager output changes before existing action rows are rebound",
                            "English action",
                            TapActions.label(activity, action, Locale.ENGLISH),
                        )
                        if (promoted)
                            assertEquals(
                                "The app's own promoted locale order can stay identical",
                                previousOwnTags,
                                activity
                                    .getResources()
                                    .getConfiguration()
                                    .getLocales()
                                    .toLanguageTags(),
                            )
                        activity.onConfigurationChanged(english)
                        assertFalse(
                            "A resource-only transition invalidates dialogs even after the logical language was handled",
                            oldResourceDialog.isShowing(),
                        )
                        assertSame(root, layout(activity))
                        assertSame(scene, stage(root))
                        assertSame(content, stage(root).getWidgetContent())
                        assertSame(format, shown.getFormat24Hour())
                        assertSame(editor, editorSession(activity).settings)
                        assertNull(text(root, "Japanese action"))
                        assertNotNull(text(root, "English action"))
                        if (!advanced)
                            assertTrue(
                                root
                                    .findViewWithTag<View>("section-toggle:actions")
                                    .getContentDescription()
                                    .toString()
                                    .contains("English action"),
                            )
                        assertEquals(
                            "Resource labels never flush unrelated configuration changes to Home",
                            hostColor,
                            hostClock().getCurrentTextColor(),
                        )
                        assertEquals(
                            Color.RED,
                            WidgetSettingsStore.load(context, CLOCK, false).timeColor,
                        )
                    },
                )
        }
    }

    private fun publishResourceLabel(info: android.content.pm.ActivityInfo, resources: Resources) {
        // ActivityInfo copies freeze nonLocalizedLabel to a String; publish the fixture's new
        // resource result per configuration phase.
        info.nonLocalizedLabel =
            if ("ja".equals(resources.getConfiguration().getLocales().get(0).getLanguage()))
                "Japanese action"
            else "English action"
        Shadows.shadowOf(context.getPackageManager()).addOrUpdateActivity(info)
    }

    private fun update(action: String) {
        UpdateReceiver().onReceive(context, Intent(action))
    }

    private fun hostClock(): TextClock {
        return Shadows.shadowOf(manager).getViewFor(CLOCK).findViewById<TextClock>(R.id.clock_time)
    }

    private fun hostCity(): String {
        return (Shadows.shadowOf(manager).getViewFor(WORLD).findViewById<View>(R.id.city_name)
                as TextView)
            .getText()
            .toString()
    }

    private fun bind(id: Int, world: Boolean) {
        val info: AppWidgetProviderInfo = AppWidgetProviderInfo()
        info.provider =
            ComponentName(
                context,
                if (world) WorldWidgetProvider::class.java else ClockWidgetProvider::class.java,
            )
        info.initialLayout = if (world) R.layout.world_widget else R.layout.clock_widget
        Shadows.shadowOf(manager).addInstalledProvider(info)
        Shadows.shadowOf(manager).addBoundWidget(id, info)
        val size: Bundle = Bundle()
        size.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 170)
        size.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 170)
        size.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90)
        size.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90)
        manager!!.updateAppWidgetOptions(id, size)
        BaseWidgetProvider.updateWidget(context, id, world)
    }

    private class MutableClock(timestamp: String) : Clock() {
        var now: Instant

        override fun getZone(): ZoneId = ZoneOffset.UTC

        init {
            now = Instant.parse(timestamp)
        }

        override fun withZone(zone: ZoneId): Clock {
            return this
        }

        override fun instant(): Instant {
            return now
        }
    }

    private class CrossingClock : Clock() {
        var reads: Int = 0

        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock {
            return this
        }

        override fun instant(): Instant {
            return Instant.parse(
                if (reads++ == 0) "2026-10-30T12:34:59.999Z" else "2026-10-30T12:35:00.002Z",
            )
        }
    }

    companion object {
        private val CLOCK: Int = 28101
        private val WORLD: Int = 28102

        private fun editorSession(activity: MainActivity): EditorSession =
            activity.session

        private fun systemLocale(tags: String) {
            val system: Resources = Resources.getSystem()
            val next: Configuration = Configuration(system.getConfiguration())
            next.setLocales(LocaleList.forLanguageTags(tags))
            system.updateConfiguration(next, system.getDisplayMetrics())
        }

        private fun choose(activity: MainActivity, value: String) {
            AppLocales.setLanguage(activity, value)
            if (android.os.Build.VERSION.SDK_INT < 33) UpdateReceiver.updateForLanguage(activity)
            activity.onConfigurationChanged(Configuration(activity.resources.configuration))
            idle()
        }

        private fun stage(root: View): WidgetPreviewStage {
            return root.findViewWithTag<View>("preview-stage") as WidgetPreviewStage
        }

        private fun text(view: View, value: String): TextView? {
            if (view is TextView && value.contentEquals((view as TextView).getText()))
                return view as TextView
            if (view is ViewGroup)
                for (index: Int in 0 until (view as ViewGroup).getChildCount()) {
                    val found: TextView? = text((view as ViewGroup).getChildAt(index), value)
                    if (found != null) return found
                }
            return null
        }

        private fun layout(activity: MainActivity): View {
            idle()
            val root: View =
                (activity.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0)
            root.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(850, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 400, 850)
            idle()
            return root
        }

        private fun idle() {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }
}

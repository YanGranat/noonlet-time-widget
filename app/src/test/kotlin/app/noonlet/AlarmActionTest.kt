package app.noonlet

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.provider.AlarmClock
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPackageManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36])
class AlarmActionTest {
    private lateinit var context: Context
    private var manager: PackageManager? = null
    private var packages: ShadowPackageManager? = null

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getPackageManager()
        packages = Shadows.shadowOf(manager)
        Shadows.shadowOf(context as Application?).grantPermissions(SET_ALARM_PERMISSION)
    }

    @Test
    @Throws(Exception::class)
    fun publicSystemAlarmPageIsAnActivityPendingIntentAndOnlyShowsExistingAlarms() {
        val requested: Array<out String>? =
            manager!!
                .getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS)
                .requestedPermissions
        assertNotNull(requested)
        assertTrue(
            "AOSP protects its SHOW_ALARMS activity with this normal permission",
            requested!!.asList().contains(SET_ALARM_PERMISSION),
        )
        val alarms: ComponentName =
            install(
                "phone.clock",
                "phone.clock.AlarmPage",
                ApplicationInfo.FLAG_SYSTEM,
                true,
                false,
            )
        val activity: ActivityInfo = manager!!.getActivityInfo(alarms, 0)
        activity.permission = SET_ALARM_PERMISSION
        packages!!.addOrUpdateActivity(activity)
        val pending = checkNotNull(TapActions.pending(context, 601, false, "clock"))
        assertTrue(pending.isActivity())
        val saved: Intent = saved(601, false, "clock")
        assertEquals(AlarmClock.ACTION_SHOW_ALARMS, saved.getAction())
        assertEquals(alarms, saved.getComponent())
        assertTrue((saved.getFlags() and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        assertFalse(saved.hasExtra(AlarmClock.EXTRA_HOUR))
        assertFalse(saved.hasExtra(AlarmClock.EXTRA_MINUTES))
        pending.send()
        val request = Shadows.shadowOf(context as Application?).nextStartedActivity
        Robolectric.buildActivity(WidgetTapActivity::class.java, request).create().use { controller ->
            val launched = Shadows.shadowOf(controller.get()).nextStartedActivity
            assertEquals(AlarmClock.ACTION_SHOW_ALARMS, launched.action)
            assertEquals(alarms, launched.component)
        }
    }

    @Test
    @Throws(Exception::class)
    fun builtInAlarmWinsOverThirdPartyDefaultButExplicitChosenAppIsPreserved() {
        val third: ComponentName = install("example.clock", "example.clock.Main", 0, true, true)
        val stock: ComponentName =
            install("phone.clock", "phone.clock.Alarms", ApplicationInfo.FLAG_SYSTEM, true, false)
        val filter: IntentFilter = alarmFilter()
        manager!!.addPreferredActivity(
            filter,
            IntentFilter.MATCH_CATEGORY_EMPTY,
            arrayOf<ComponentName>(third, stock),
            third,
        )
        val resolved: ActivityInfo =
            manager!!
                .resolveActivity(
                    Intent(AlarmClock.ACTION_SHOW_ALARMS),
                    PackageManager.MATCH_DEFAULT_ONLY,
                )!!
                .activityInfo
        assertEquals(
            "The fixture must model an actual third-party default",
            third,
            ComponentName(resolved.packageName, resolved.name),
        )
        val nativeAlarm: Intent = saved(602, false, "clock")
        assertEquals(stock, nativeAlarm.getComponent())
        assertEquals(AlarmClock.ACTION_SHOW_ALARMS, nativeAlarm.getAction())
        val request = saved(602, false, TapActions.appAction(third))
        val dispatch = Robolectric.buildActivity(WidgetTapActivity::class.java, request).create()
        val chosen = Shadows.shadowOf(dispatch.get()).nextStartedActivity
        assertEquals(third, chosen.getComponent())
        assertEquals(Intent.ACTION_MAIN, chosen.getAction())
        assertTrue(dispatch.get().isFinishing)
        dispatch.destroy()
    }

    @Test
    fun customTapChecksAvailabilityWhenThePendingIntentIsUsed() {
        val target = install("example.reader", "example.reader.Main", 0, false, true)
        val action = TapActions.appAction(target)
        val request = saved(619, true, action)
        val activity = manager!!.getActivityInfo(target, 0)
        activity.enabled = false
        packages!!.addOrUpdateActivity(activity)
        Robolectric.buildActivity(WidgetTapActivity::class.java, request).create().use { controller ->
            val destination = Shadows.shadowOf(controller.get()).nextStartedActivity
            assertEquals(MainActivity::class.java.name, destination.component!!.className)
            assertEquals(619, destination.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
            assertTrue(destination.getBooleanExtra("world", false))
            assertTrue(controller.get().isFinishing)
        }
        val missing = TapActions.appAction(ComponentName("example.missing", "example.missing.Main"))
        val fallback = TapActions.customTarget(context, 620, false, missing)
        assertEquals(MainActivity::class.java.name, fallback.component!!.className)
        activity.enabled = true
        activity.exported = false
        packages!!.addOrUpdateActivity(activity)
        assertEquals(MainActivity::class.java.name,
            TapActions.customTarget(context, 620, false, action).component!!.className)
    }

    @Test
    fun androidPreferredSystemAlarmWinsOverAnotherBuiltInClock() {
        val aosp: ComponentName =
            install(
                "com.android.deskclock",
                "com.android.deskclock.Alarms",
                ApplicationInfo.FLAG_SYSTEM,
                true,
                false,
            )
        val preferred: ComponentName =
            install("phone.clock", "phone.clock.Alarms", ApplicationInfo.FLAG_SYSTEM, true, false)
        manager!!.addPreferredActivity(
            alarmFilter(),
            IntentFilter.MATCH_CATEGORY_EMPTY,
            arrayOf<ComponentName>(aosp, preferred),
            preferred,
        )
        assertEquals(preferred, saved(603, false, "clock").getComponent())
    }

    @Test
    @Throws(Exception::class)
    fun xiaomiClockWithoutPublicAlarmIntentUsesItsDiscoveredLauncher() {
        val miui: ComponentName =
            install(
                "com.android.deskclock",
                "com.android.deskclock.DeskClockTabActivity",
                ApplicationInfo.FLAG_UPDATED_SYSTEM_APP,
                false,
                true,
            )
        val saved: Intent = saved(604, false, "clock")
        assertEquals(Intent.ACTION_MAIN, saved.getAction())
        assertTrue(saved.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertEquals(miui, saved.getComponent())
        assertFalse(saved.getBooleanExtra(TapActions.ALARM_UNAVAILABLE, false))
        TapActions.pending(context, 604, false, "clock")!!.send()
        val request = Shadows.shadowOf(context as Application?).nextStartedActivity
        Robolectric.buildActivity(WidgetTapActivity::class.java, request).create().use { controller ->
            assertEquals(miui, Shadows.shadowOf(controller.get()).nextStartedActivity.component)
        }
    }

    @Test
    fun googleSystemClockLauncherIsDiscoveredWithoutAssumingAnActivityName() {
        val google: ComponentName =
            install(
                "com.google.android.deskclock",
                "com.google.android.deskclock.NewLauncherEntry",
                ApplicationInfo.FLAG_SYSTEM,
                false,
                true,
            )
        assertEquals(google, saved(605, false, "clock").getComponent())
    }

    @Test
    fun knownClockInstalledAsAUserApplicationCanOpenItsAlarmPageOrLauncher() {
        val clock = install("com.android.deskclock", "com.android.deskclock.Main", 0, false, true)
        assertEquals(clock, saved(621, false, "clock").component)
        packages!!.addIntentFilterForActivity(clock, alarmFilter())
        assertEquals(AlarmClock.ACTION_SHOW_ALARMS, saved(622, false, "clock").action)
    }

    @Test
    fun clockAvailabilityIsResolvedAtTapTime() {
        val request = Shadows.shadowOf(TapActions.pending(context, 623, true, "clock")).savedIntent
        val clock = install("com.android.deskclock", "com.android.deskclock.Main", 0, true, true)
        Robolectric.buildActivity(WidgetTapActivity::class.java, request).create().use { controller ->
            assertEquals(clock, Shadows.shadowOf(controller.get()).nextStartedActivity.component)
        }
        val activity = manager!!.getActivityInfo(clock, 0)
        activity.enabled = false
        packages!!.addOrUpdateActivity(activity)
        Robolectric.buildActivity(WidgetTapActivity::class.java, request).create().use { controller ->
            assertUnavailable(Shadows.shadowOf(controller.get()).nextStartedActivity, 623, true)
        }
    }

    @Test
    fun anExplicitAlarmHandlerDoesNotRequireADefaultCategory() {
        val clock = install("com.android.deskclock", "com.android.deskclock.Alarms", 0, false, false)
        packages!!.addIntentFilterForActivity(clock, IntentFilter(AlarmClock.ACTION_SHOW_ALARMS))
        assertEquals(clock, saved(624, false, "clock").component)
    }

    @Test
    @Throws(Exception::class)
    fun inaccessibleAlarmApiFallsBackToAccessibleSystemClockLauncher() {
        val clock: ComponentName =
            install(
                "com.android.deskclock",
                "com.android.deskclock.Main",
                ApplicationInfo.FLAG_SYSTEM,
                true,
                false,
            )
        val activity: ActivityInfo = manager!!.getActivityInfo(clock, 0)
        activity.permission = "phone.clock.permission.PRIVATE_ALARMS"
        packages!!.addOrUpdateActivity(activity)
        val launcher: ActivityInfo = ActivityInfo()
        launcher.packageName = "com.android.deskclock"
        launcher.name = "com.android.deskclock.PublicLauncher"
        launcher.applicationInfo = activity.applicationInfo
        launcher.exported = true
        launcher.enabled = launcher.exported
        packages!!.addOrUpdateActivity(launcher)
        packages!!.addIntentFilterForActivity(
            ComponentName(launcher.packageName, launcher.name),
            launcherFilter(),
        )
        val saved: Intent = saved(606, false, "clock")
        assertEquals(ComponentName(launcher.packageName, launcher.name), saved.getComponent())
        assertEquals(Intent.ACTION_MAIN, saved.getAction())
    }

    @Test
    fun noSystemClockGivesAnExplicitUnavailableResultWithoutChoosingThirdParty() {
        install("example.clock", "example.clock.Main", 0, true, true)
        assertUnavailable(saved(607, true, "clock"), 607, true)
    }

    @Test
    fun noAlarmHandlerAlsoGivesUnavailableResultAndOtherActionsRemainSeparate() {
        assertUnavailable(saved(608, false, "clock"), 608, false)
        assertNull(TapActions.pending(context, 608, false, "none"))
        val settings: Intent = saved(608, false, "settings")
        assertEquals(MainActivity::class.java.getName(), settings.getComponent()!!.getClassName())
        assertFalse(settings.hasExtra(TapActions.ALARM_UNAVAILABLE))
        val calendar: Intent = saved(608, false, "calendar")
        assertFalse(calendar.hasExtra(TapActions.ALARM_UNAVAILABLE))
    }

    @Test
    @Throws(Exception::class)
    fun unexportedOrDisabledSystemActivitiesAreNotReturnedAsLaunchTargets() {
        val clock: ComponentName =
            install("phone.clock", "phone.clock.Alarms", ApplicationInfo.FLAG_SYSTEM, true, false)
        val activity: ActivityInfo = manager!!.getActivityInfo(clock, 0)
        activity.exported = false
        packages!!.addOrUpdateActivity(activity)
        assertUnavailable(saved(609, false, "clock"), 609, false)
        activity.exported = true
        activity.enabled = false
        packages!!.addOrUpdateActivity(activity)
        assertUnavailable(saved(610, false, "clock"), 610, false)
    }

    private fun saved(id: Int, world: Boolean, action: String): Intent {
        val request = Shadows.shadowOf(TapActions.pending(context, id, world, action)).savedIntent
        if (action != "clock") return request
        return Robolectric.buildActivity(WidgetTapActivity::class.java, request).create().use { controller ->
            Shadows.shadowOf(controller.get()).nextStartedActivity
        }
    }

    private fun assertUnavailable(intent: Intent, id: Int, world: Boolean) {
        assertEquals(MainActivity::class.java.getName(), intent.getComponent()!!.getClassName())
        assertTrue(intent.getBooleanExtra(TapActions.ALARM_UNAVAILABLE, false))
        assertEquals(id, intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0))
        assertEquals(world, intent.getBooleanExtra("world", !world))
    }

    private fun install(
        packageName: String,
        activityName: String,
        flags: Int,
        alarms: Boolean,
        launcher: Boolean,
    ): ComponentName {
        val pkg: PackageInfo = PackageInfo()
        pkg.packageName = packageName
        pkg.applicationInfo = ApplicationInfo()
        pkg.applicationInfo!!.packageName = packageName
        pkg.applicationInfo!!.flags = flags
        pkg.applicationInfo!!.enabled = true
        val activity: ActivityInfo = ActivityInfo()
        activity.packageName = packageName
        activity.name = activityName
        activity.applicationInfo = pkg.applicationInfo
        activity.exported = true
        activity.enabled = activity.exported
        packages!!.installPackage(pkg)
        packages!!.addOrUpdateActivity(activity)
        val component: ComponentName = ComponentName(packageName, activityName)
        if (alarms) packages!!.addIntentFilterForActivity(component, alarmFilter())
        if (launcher) packages!!.addIntentFilterForActivity(component, launcherFilter())
        return component
    }

    companion object {
        private val SET_ALARM_PERMISSION: String = "com.android.alarm.permission.SET_ALARM"

        private fun alarmFilter(): IntentFilter {
            val filter: IntentFilter = IntentFilter(AlarmClock.ACTION_SHOW_ALARMS)
            filter.addCategory(Intent.CATEGORY_DEFAULT)
            return filter
        }

        private fun launcherFilter(): IntentFilter {
            val filter: IntentFilter = IntentFilter(Intent.ACTION_MAIN)
            filter.addCategory(Intent.CATEGORY_LAUNCHER)
            return filter
        }
    }
}

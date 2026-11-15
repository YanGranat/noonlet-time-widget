package app.noonlet

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Looper
import android.view.View
import java.util.ArrayList
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowApplicationPackageManager

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34, 36],
    qualifiers = "en-rUS-w400dp-h850dp-mdpi",
    shadows = [AppPickerTest.ControlledPackages::class, CountedLocaleManager::class],
)
@LooperMode(LooperMode.Mode.PAUSED)
class AppPickerTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        for (name: String in
            arrayOf<String>(
                "noonlet_widgets",
                "MainActivity",
                "widget_drafts",
                "widget_configuration",
            )) context.getSharedPreferences(name, 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
        ControlledPackages.resetFixture()
    }

    @After
    fun releaseWorker() {
        ControlledPackages.gate.countDown()
    }

    @Test
    fun labelsResolveOnceBeforeSortingAndChoicesRemainImmutableAndDistinct() {
        val first: Labeled = app("org.example.z", "Zulu")
        val duplicate: Labeled = app("org.example.z", "Beta")
        val alpha: Labeled = app("org.example.a", "Alpha")
        val disabled: Labeled = app("org.example.disabled", "Disabled")
        disabled.activityInfo.enabled = false
        val privateApp: Labeled = app("org.example.private", "Private")
        privateApp.activityInfo.exported = false
        ControlledPackages.apps =
            Arrays.asList(first, disabled, duplicate, alpha, privateApp, ResolveInfo())
        val entries: List<AppPickerCatalog.Entry> =
            AppPickerCatalog.load(context.getPackageManager(), Locale.ENGLISH)
        assertEquals(2, entries.size)
        assertEquals("Alpha", entries.get(0).label)
        assertEquals("Beta", entries.get(1).label)
        assertEquals("app:org.example.a/org.example.a.Main", entries.get(0).action)
        assertEquals("app:org.example.z/org.example.z.Main", entries.get(1).action)
        for (value: Labeled in arrayOf<Labeled>(first, duplicate, alpha)) assertEquals(
            1,
            value.reads.get(),
        )
        assertEquals(0, disabled.reads.get())
        assertEquals(0, privateApp.reads.get())
        assertSame(first, ControlledPackages.apps.get(0))
        try {
            (entries as MutableList<AppPickerCatalog.Entry>).add(entries.get(0))
            fail("A completed snapshot must be immutable")
        } catch (expected: UnsupportedOperationException) {}

        CountedLocaleManager.systemReads = 0
        assertEquals("Системный будильник", TapActions.label(context, "clock", Locale("ru")))
        assertEquals(0, CountedLocaleManager.systemReads)
    }

    @Test
    @Throws(Exception::class)
    fun realPickerOpensBeforeDiscoveryAndSelectsTheDisplayedComponent() {
        val beta: Labeled = app("org.example.b", "Beta")
        val alpha: Labeled = app("org.example.a", "Alpha")
        ControlledPackages.apps = Arrays.asList(beta, alpha)
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    ControlledPackages.blockNext()
                    val chooser: AlertDialog = openApps(activity)
                    assertTrue(
                        "The actual dialog exists while package discovery is blocked",
                        chooser.isShowing(),
                    )
                    assertTrue(ControlledPackages.entered.await(5, TimeUnit.SECONDS))
                    assertFalse(
                        "Package discovery belongs to the worker",
                        ControlledPackages.onMain.get(),
                    )
                    assertEquals(1, chooser.getListView().getCount())
                    assertFalse(chooser.getListView().getAdapter().isEnabled(0))
                    assertTrue(chooser.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled())
                    ControlledPackages.gate.countDown()
                    DialogTestFixtures.awaitAppsLoaded(chooser)
                    assertEquals(2, chooser.getListView().getCount())
                    assertEquals("Alpha", chooser.getListView().getAdapter().getItem(0).toString())
                    assertEquals("Beta", chooser.getListView().getAdapter().getItem(1).toString())
                    assertEquals(1, beta.reads.get())
                    assertEquals(1, alpha.reads.get())
                    DialogTestFixtures.choose(chooser, 1)
                    assertEquals(
                        "app:org.example.b/org.example.b.Main",
                        WidgetSettingsStore.loadUnplaced(context, false).tapAction,
                    )
                    assertFalse(chooser.isShowing())
                    val formatReads = localeReadsOnClick(activity, "Date format")
                    val formats: AlertDialog = DialogTestFixtures.latest()
                    assertEquals(
                        "A whole format list uses one phone-locale snapshot",
                        1,
                        formatReads,
                    )
                    assertEquals(8, formats.getListView().getCount())
                    formats.dismiss()
                },
            )
    }

    private fun localeReadsOnClick(activity: MainActivity, label: String): Int {
        var target: View = checkNotNull(EditorTestInteractions.text(activity.window.decorView, label))
        while (!target.isClickable) target = target.parent as View
        val listener = checkNotNull(Shadows.shadowOf(target).onClickListener)
        var reads = -1
        target.setOnClickListener { view ->
            CountedLocaleManager.systemReads = 0
            listener.onClick(view)
            reads = CountedLocaleManager.systemReads
        }
        try {
            EditorTestInteractions.tap(activity, label)
        } finally {
            target.setOnClickListener(listener)
        }
        return reads
    }

    @Test
    @Throws(Exception::class)
    fun closedEditorsIgnorePendingResultsAndUnavailableListsStayDisabled() {
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    ControlledPackages.apps = Arrays.asList(app("org.example.old", "Old"))
                    ControlledPackages.blockNext()
                    val old: AlertDialog = openApps(activity)
                    assertTrue(ControlledPackages.entered.await(5, TimeUnit.SECONDS))
                    old.dismiss()
                    EditorTestInteractions.tap(activity, "World clock")
                    ControlledPackages.gate.countDown()
                    awaitDiscovery()
                    assertFalse(old.isShowing())
                    assertFalse(old.getListView().getAdapter().isEnabled(0))
                    assertEquals(
                        "settings",
                        WidgetSettingsStore.loadUnplaced(context, true).tapAction,
                    )
                    assertEquals(
                        "clock",
                        WidgetSettingsStore.loadUnplaced(context, false).tapAction,
                    )
                    for (failure: Boolean in booleanArrayOf(false, true)) {
                        ControlledPackages.resetFixture()
                        ControlledPackages.failure = failure
                        val empty: AlertDialog = openApps(activity)
                        DialogTestFixtures.awaitAppsLoaded(empty)
                        assertTrue(empty.isShowing())
                        assertFalse(empty.getListView().getAdapter().isEnabled(0))
                        assertEquals(
                            if (failure) "Apps could not be loaded" else "No launchable apps found",
                            empty.getListView().getAdapter().getItem(0).toString(),
                        )
                        assertEquals(
                            "settings",
                            WidgetSettingsStore.loadUnplaced(context, true).tapAction,
                        )
                        assertTrue(empty.getButton(AlertDialog.BUTTON_NEGATIVE).performClick())
                    }
                },
            )
    }

    private class Labeled(val label: String) : ResolveInfo() {
        val reads: AtomicInteger = AtomicInteger()

        override fun loadLabel(manager: PackageManager): CharSequence {
            reads.incrementAndGet()
            ControlledPackages.labelReads.incrementAndGet()
            return label
        }
    }

    @Implements(className = "android.app.ApplicationPackageManager")
    class ControlledPackages : ShadowApplicationPackageManager() {
        @Implementation
        override fun queryIntentActivities(intent: Intent, flags: Int): MutableList<ResolveInfo> {
            if (
                !Intent.ACTION_MAIN.equals(intent.getAction()) ||
                    !intent.hasCategory(Intent.CATEGORY_LAUNCHER)
            )
                return super.queryIntentActivities(intent, flags)
            onMain.set(Looper.myLooper() === Looper.getMainLooper())
            entered.countDown()
            try {
                if (!gate.await(5, TimeUnit.SECONDS))
                    throw IllegalStateException("Package query remained blocked")
                if (failure) throw IllegalStateException("Controlled package-manager failure")
                return apps
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException(interrupted)
            } finally {
                finished.countDown()
            }
        }

        companion object {
            @Volatile lateinit var apps: MutableList<ResolveInfo>
            @Volatile var failure: Boolean = false
            lateinit var gate: CountDownLatch
            lateinit var entered: CountDownLatch
            lateinit var finished: CountDownLatch
            val onMain: AtomicBoolean = AtomicBoolean()
            val labelReads: AtomicInteger = AtomicInteger()

            fun resetFixture() {
                apps = ArrayList()
                failure = false
                gate = CountDownLatch(0)
                entered = CountDownLatch(1)
                finished = CountDownLatch(1)
                onMain.set(false)
                labelReads.set(0)
            }

            fun blockNext() {
                gate = CountDownLatch(1)
                entered = CountDownLatch(1)
                finished = CountDownLatch(1)
            }
        }
    }

    companion object {

        private fun openApps(activity: MainActivity): AlertDialog {
            EditorTestInteractions.tap(activity, "Widget tap")
            DialogTestFixtures.choose(DialogTestFixtures.latest(), 4)
            return DialogTestFixtures.latest()
        }

        @Throws(InterruptedException::class)
        private fun awaitDiscovery() {
            assertTrue(ControlledPackages.finished.await(5, TimeUnit.SECONDS))
            assertTrue(
                "Discovery and its completion have left the worker",
                ForkJoinPool.commonPool().awaitQuiescence(5, TimeUnit.SECONDS),
            )
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }

        private fun app(pkg: String, label: String): Labeled {
            val info: Labeled = Labeled(label)
            info.activityInfo = ActivityInfo()
            info.activityInfo.packageName = pkg
            info.activityInfo.name = pkg + ".Main"
            info.activityInfo.enabled = true
            info.activityInfo.exported = true
            return info
        }
    }
}

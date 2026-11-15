package app.noonlet

import android.app.Activity
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextClock
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.ArrayList
import java.util.Arrays
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.Properties
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowProcess
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Cold launcher inflation must survive IPC and the framework's asynchronous action path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorldLauncherHostTest {
    private lateinit var context: Context
    private var manager: AppWidgetManager? = null

    @Before
    @Throws(Exception::class)
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        manager = AppWidgetManager.getInstance(context)
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
        installResourceApkPaths()
        ShadowLog.clear()
    }

    @Test
    @Throws(Exception::class)
    fun coldFrameworkHostAcceptsParcelledDefaultsAndResponsiveWorldUpdates() {
        // ShadowAppWidgetHostView replaces updateAppWidget with a synchronous same-context inflate.
        // Invoke framework methods directly and assert their private CONTENT mode, not shadow
        // state.
        for (asynchronous: Boolean in booleanArrayOf(false, true)) for (count: Int in
            intArrayOf(0, 1, 2, 4)) {
            val id: Int = 2300 + (if (asynchronous) 100 else 0) + count
            val info: AppWidgetProviderInfo = bind(id)
            if (count > 0) savePlaces(id, count)
            Robolectric.buildActivity(Activity::class.java)
                .setup()
                .visible()
                .use(
                    { controller ->
                        val activity: Activity = controller.get()
                        val hostContext: Context = foreignHost()
                        val root: FrameLayout = FrameLayout(hostContext)
                        val host: AppWidgetHostView = AppWidgetHostView(hostContext)
                        host.setAppWidget(id, info)
                        framework(
                            host,
                            "setAppWidget",
                            ClassParameter.from(Int::class.javaPrimitiveType, id),
                            ClassParameter.from(AppWidgetProviderInfo::class.java, info),
                        )
                        if (asynchronous) host.setExecutor(INLINE)
                        root.addView(host, FrameLayout.LayoutParams(-1, -1))
                        activity.setContentView(root)
                        framework(
                            host,
                            "updateAppWidget",
                            ClassParameter.from(RemoteViews::class.java, null),
                        )
                        idle()
                        assertNotNull(
                            "Initial layout must load in the cold framework host",
                            host.findViewById<View>(R.id.world_rows),
                        )
                        val outerSizes: ArrayList<SizeF> = ArrayList()
                        for (size: IntArray in SIZES) outerSizes.add(
                            SizeF(
                                (size[0] + host.getPaddingLeft() + host.getPaddingRight())
                                    .toFloat(),
                                (size[1] + host.getPaddingTop() + host.getPaddingBottom())
                                    .toFloat(),
                            ),
                        )
                        // The framework subtracts host padding and publishes all four possible
                        // content sizes.
                        host.updateAppWidgetSize(Bundle(), outerSizes)
                        val complete: RemoteViews = WidgetRenderer.world(context, id)
                        val variants: MutableList<*> =
                            ReflectionHelpers.getField(complete, "mSizedRemoteViews")
                        assertTrue("Native size publication must retain every advertised layout",
                            variants.size in 4..16)
                        for (size: IntArray in SIZES) {
                            host.setLayoutParams(
                                FrameLayout.LayoutParams(
                                    (size[0] + host.getPaddingLeft() + host.getPaddingRight()),
                                    (size[1] + host.getPaddingTop() + host.getPaddingBottom()),
                                ),
                            )
                            layoutWindow(root)
                            framework(
                                host,
                                "updateAppWidget",
                                ClassParameter.from(
                                    RemoteViews::class.java,
                                    crossProcess(complete),
                                ),
                            )
                            idle()
                            layoutWindow(root)
                            val actual: SizeF = ReflectionHelpers.getField(host, "mCurrentSize")
                            assertEquals(size[0].toFloat(), actual.getWidth(), .01f)
                            assertEquals(size[1].toFloat(), actual.getHeight(), .01f)
                            assertEquals(
                                "Framework host entered its error/default view: " + hostLogs(),
                                1,
                                ReflectionHelpers.getField(host, "mViewMode") as Int,
                            )
                            val remote: Context = ReflectionHelpers.getField(host, "mRemoteContext")
                            assertNotNull(remote)
                            assertTrue("Widget resources must be restricted", remote.isRestricted())
                            assertTrue(host.isAttachedToWindow())
                            assertVisibleWorld(
                                host,
                                Math.max(1, count),
                                ("framework async=" +
                                    asynchronous +
                                    " count=" +
                                    count +
                                    " size=" +
                                    Arrays.toString(size)),
                            )
                        }
                    },
                )
        }
    }

    @Test
    @Throws(Exception::class)
    fun asyncNestedApplyAndReapplyPopulateEveryCityWithNonzeroLayout() {
        BackgroundApply()
            .use(
                { worker ->
                    for (size: IntArray in SIZES) {
                        val id: Int = 2500 + size[0] + size[1]
                        bind(id)
                        options(id, size, false)
                        Robolectric.buildActivity(Activity::class.java)
                            .setup()
                            .visible()
                            .use(
                                { controller ->
                                    val hostContext: Context = foreignHost()
                                    val root: FrameLayout = FrameLayout(hostContext)
                                    controller.get().setContentView(root)
                                    savePlaces(id, 2)
                                    val current: View =
                                        applyAsync(
                                            crossProcess(WidgetRenderer.world(context, id)),
                                            hostContext,
                                            root,
                                            null,
                                            worker,
                                        )
                                    root.addView(current, FrameLayout.LayoutParams(-1, -1))
                                    layout(root, size[0], size[1])
                                    assertVisibleWorld(
                                        current,
                                        2,
                                        "async apply " + Arrays.toString(size),
                                    )
                                    for (count: Int in intArrayOf(4, 1, 2)) {
                                        savePlaces(id, count)
                                        val reapplied: View =
                                            applyAsync(
                                                crossProcess(WidgetRenderer.world(context, id)),
                                                hostContext,
                                                root,
                                                current,
                                                worker,
                                            )
                                        assertSame(
                                            "Reapply must update the existing inflated root",
                                            current,
                                            reapplied,
                                        )
                                        layout(root, size[0], size[1])
                                        assertVisibleWorld(
                                            current,
                                            count,
                                            "async reapply count=" +
                                                count +
                                                " " +
                                                Arrays.toString(size),
                                        )
                                    }
                                },
                            )
                    }
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun sixteenResponsiveLayoutsKeepTheirParcelWellBelowTheSharedBinderBuffer() {
        val id: Int = 2691
        bind(id)
        savePlaces(id, 4)
        val alternatives: ArrayList<SizeF> = ArrayList()
        for (row: Int in 0..3) for (column: Int in 0..3) alternatives.add(
            SizeF((167 + 54 * column).toFloat(), (82 + 31 * row).toFloat()),
        )
        val options: Bundle = Bundle()
        options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, alternatives)
        manager!!.updateAppWidgetOptions(id, options)
        val views: RemoteViews = WidgetRenderer.world(context, id)
        assertEquals(16, idealSizes(views).size)
        assertParcelBudget(views, "existing-minimum")
    }

    @Test
    @Throws(Exception::class)
    fun sixteenOriginalsWithoutTheMinimumCornerReserveOneFallbackSlot() {
        val id: Int = 2693
        bind(id)
        savePlaces(id, 4)
        for (lateMinimum: Boolean in booleanArrayOf(false, true)) {
            val advertised = arrayListOf<SizeF?>()
            advertised.add(null)
            advertised.add(SizeF(-1f, 100f))
            val originals: ArrayList<SizeF> = ArrayList()
            for (index: Int in 0..15) originals.add(
                SizeF((160 + 10 * index).toFloat(), (240 - 10 * index).toFloat()),
            )
            advertised.add(originals.get(0))
            advertised.addAll(originals)
            // A minimum appearing only after sixteen unique hints must still reserve a slot.
            if (lateMinimum) advertised.add(SizeF(150f, 80f))
            publish(id, advertised)
            val views: RemoteViews = WidgetRenderer.world(context, id)
            val chosen: MutableList<SizeF> = idealSizes(views)
            assertEquals(
                "The native constructor must never receive a seventeenth layout",
                16,
                chosen.size,
            )
            val expected: LinkedHashSet<SizeF> = LinkedHashSet(originals.subList(0, 15))
            expected.add(if (lateMinimum) SizeF(150f, 80f) else SizeF(160f, 90f))
            assertEquals(
                "The first fifteen unique original layouts survive alongside the fallback",
                expected,
                LinkedHashSet(chosen),
            )
            assertFalse(
                "One original slot is deliberately reserved for the minimum",
                chosen.contains(originals.get(15)),
            )
            assertParcelBudget(
                views,
                if (lateMinimum) "minimum-after-limit" else "reserved-minimum",
            )
        }
    }

    @Test
    @Throws(Exception::class)
    fun exactWorldAndLocalClockPresetsRetainTheirOriginalLayoutAndTextFit() {
        for (world: Boolean in booleanArrayOf(false, true)) {
            val id: Int = if (world) 2694 else 2695
            bind(id, world)
            val settings: WidgetSettings = WidgetSettings.defaults(world)
            settings.cities = Arrays.copyOf(PLACES, 2)
            if (!world) {
                settings.timeFormat = "HH:mm:ss"
                settings.textSize = WidgetSettings.MAX_TIME_SIZE
                settings.dateSize = 32
            }
            WidgetSettingsStore.save(context, id, world, settings)
            val advertised: ArrayList<SizeF> = ArrayList()
            for (size: IntArray in SIZES) advertised.add(
                SizeF(size[0].toFloat(), size[1].toFloat()),
            )
            advertised.add(advertised.get(0))
            publish(id, advertised)
            val complete: RemoteViews =
                crossProcess(
                    if (world) WidgetRenderer.world(context, id)
                    else WidgetRenderer.clock(context, id),
                )
            val published = idealSizes(complete)
            assertEquals("Responsive sizes must be unique", published.size, published.toSet().size)
            assertTrue(published.size in 4..16)
            assertTrue(published.containsAll(LinkedHashSet(advertised)))
            for (size: IntArray in SIZES) {
                val exact: SizeF = SizeF(size[0].toFloat(), size[1].toFloat())
                val chosen: RemoteViews = select(complete, context, exact)
                assertEquals(
                    "An exact advertised layout still has zero native selection distance",
                    exact,
                    ReflectionHelpers.getField(chosen, "mIdealSize") as SizeF,
                )
                val host: Context = foreignHost()
                val actual: View = chosen.apply(host, FrameLayout(host))
                val expected: View =
                    WidgetRenderer.preview(
                            context,
                            id,
                            world,
                            WidgetSettingsStore.load(context, id, world),
                            size[0].toFloat(),
                            size[1].toFloat(),
                        )
                        .apply(host, FrameLayout(host))
                layout(actual, size[0], size[1])
                layout(expected, size[0], size[1])
                val actualTimes: MutableList<View> =
                    WidgetTestViews.find(
                        actual,
                        if (world) R.id.city_time else R.id.clock_time,
                    )
                val expectedTimes: MutableList<View> =
                    WidgetTestViews.find(
                        expected,
                        if (world) R.id.city_time else R.id.clock_time,
                    )
                assertEquals(expectedTimes.size, actualTimes.size)
                for (index: Int in 0 until actualTimes.size) assertEquals(
                    (expectedTimes.get(index) as TextClock).getTextSize(),
                    (actualTimes.get(index) as TextClock).getTextSize(),
                    .01f,
                )
                if (!world)
                    assertEquals(
                        (expected.findViewById<View>(R.id.clock_date) as TextClock).getTextSize(),
                        (actual.findViewById<View>(R.id.clock_date) as TextClock).getTextSize(),
                        .01f,
                    )
                else {
                    val actualCards: MutableList<View> =
                        WidgetTestViews.find(actual, R.id.world_card)
                    val expectedCards: MutableList<View> =
                        WidgetTestViews.find(expected, R.id.world_card)
                    for (index: Int in 0 until actualCards.size) assertArrayEquals(
                        position(expectedCards.get(index), expected),
                        position(actualCards.get(index), actual),
                        .01f,
                    )
                }
            }
        }
    }

    @Test
    @Throws(Exception::class)
    fun nativeRoundingToleranceCanPreferTheCloserMinimumCorner() {
        val id: Int = 2696
        bind(id)
        savePlaces(id, 2)
        val advertised: ArrayList<SizeF> =
            ArrayList(Arrays.asList(SizeF(245f, 90f), SizeF(244f, 180f)))
        publish(id, advertised)
        val advertisedOnlyLayouts: MutableMap<SizeF, RemoteViews> = LinkedHashMap()
        for (size: SizeF in advertised) advertisedOnlyLayouts.put(
            size,
            WidgetRenderer.preview(
                context,
                id,
                true,
                WidgetSettingsStore.load(context, id, true),
                size.getWidth(),
                size.getHeight(),
            ),
        )
        val advertisedOnly: RemoteViews = RemoteViews(advertisedOnlyLayouts)
        val withMinimum: RemoteViews = crossProcess(WidgetRenderer.world(context, id))
        val actual: SizeF = SizeF(244.1f, 90f)
        assertEquals(
            "The native ceil+1 tolerance admits the slightly wider original",
            SizeF(245f, 90f),
            ReflectionHelpers.getField(select(advertisedOnly, context, actual), "mIdealSize")
                as SizeF,
        )
        val chosen: RemoteViews = select(withMinimum, context, actual)
        assertEquals(
            "The fallback can be closer even though a tolerance-overflowing original fits",
            SizeF(244f, 90f),
            ReflectionHelpers.getField(chosen, "mIdealSize") as SizeF,
        )
        val host: Context = foreignHost()
        val rendered: View = chosen.apply(host, FrameLayout(host))
        layout(rendered, 244, 90)
        val cards: MutableList<View> = WidgetTestViews.find(rendered, R.id.world_card)
        assertTrue(
            "The closer narrow layout legitimately changes the 245dp topology edge",
            position(cards.get(1), rendered)[1] > position(cards.get(0), rendered)[1],
        )
    }

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h850dp-420dpi")
    @Throws(Exception::class)
    fun roundedLauncherHintsCannotChooseWideCardsForTheActualNarrowHost() {
        val id: Int = 2692
        val info: AppWidgetProviderInfo = bind(id)
        savePlaces(id, 2)
        val advertised: ArrayList<SizeF> =
            ArrayList(
                Arrays.asList(
                    SizeF(145.5238f, 136.38095f),
                    SizeF(145.5238f, 135.61905f),
                    SizeF(285.7143f, 62.095238f),
                ),
            )
        val options: Bundle = Bundle()
        options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, advertised)
        manager!!.updateAppWidgetOptions(id, options)
        val complete: RemoteViews = crossProcess(WidgetRenderer.world(context, id))
        Robolectric.buildActivity(Activity::class.java)
            .setup()
            .visible()
            .use(
                { controller ->
                    val hostContext: Context = foreignHost()
                    val root: FrameLayout = FrameLayout(hostContext)
                    val host: AppWidgetHostView = AppWidgetHostView(hostContext)
                    host.setAppWidget(id, info)
                    framework(
                        host,
                        "setAppWidget",
                        ClassParameter.from(Int::class.javaPrimitiveType, id),
                        ClassParameter.from(AppWidgetProviderInfo::class.java, info),
                    )
                    root.addView(
                        host,
                        FrameLayout.LayoutParams(
                            382 + host.getPaddingLeft() + host.getPaddingRight(),
                            347 + host.getPaddingTop() + host.getPaddingBottom(),
                        ),
                    )
                    controller.get().setContentView(root)
                    // Launcher fixture content bounds: 382 x 347px at density 2.625.
                    assertEquals(2.625f, host.getResources().getDisplayMetrics().density, .0001f)
                    layoutWindow(root)
                    framework(
                        host,
                        "updateAppWidget",
                        ClassParameter.from(RemoteViews::class.java, complete),
                    )
                    idle()
                    layoutWindow(root)
                    val actual: SizeF = ReflectionHelpers.getField(host, "mCurrentSize")
                    assertEquals(382 / 2.625f, actual.getWidth(), .01f)
                    assertEquals(347 / 2.625f, actual.getHeight(), .01f)
                    val chosen: RemoteViews =
                        RemoteViews::class
                            .java!!
                            .getMethod(
                                "getRemoteViewsToApply",
                                Context::class.java,
                                SizeF::class.java,
                            )
                            .invoke(complete, hostContext, actual) as RemoteViews
                    val ideal: SizeF = ReflectionHelpers.getField(chosen, "mIdealSize")
                    val cards: MutableList<View> = WidgetTestViews.find(host, R.id.world_card)
                    assertEquals(2, cards.size)
                    val first: FloatArray = position(cards.get(0), host)
                    val second: FloatArray = position(cards.get(1), host)
                    assertTrue(
                        ("A narrow host must stack its cities; actual=" +
                            actual +
                            " chosen=" +
                            ideal +
                            " first=" +
                            Arrays.toString(first) +
                            " second=" +
                            Arrays.toString(second)),
                        second[1] > first[1] + 1,
                    )
                    assertVisibleWorld(host, 2, "rounded launcher hints")
                    for (name in WidgetTestViews.find(host, R.id.city_name))
                        assertEquals(View.VISIBLE, name.visibility)
                    for (date in WidgetTestViews.find(host, R.id.city_date))
                        assertEquals(View.VISIBLE, date.visibility)
                },
            )
    }

    @Test
    fun contentSizedAlternativesRetainTypographyBelowAdvertisedHeights() {
        val actual = SizeF(138.18182f, 122.90909f)
        for (world in listOf(false, true)) {
            val id = if (world) 2688 else 2689
            bind(id, world)
            val settings = WidgetSettings.defaults(world).apply {
                cities = arrayOf("Europe/London", "Asia/Tokyo")
            }
            WidgetSettingsStore.save(context, id, world, settings)
            publish(id, arrayListOf(
                SizeF(138.18182f, 128f), SizeF(138.18182f, 126.90909f),
                SizeF(269.0909f, 58.18182f),
            ))
            val complete = crossProcess(if (world) WidgetRenderer.world(context, id) else WidgetRenderer.clock(context, id))
            val host = foreignHost()
            val rendered = select(complete, host, actual).apply(host, FrameLayout(host))
            val expected = WidgetRenderer.preview(context, id, world, settings, actual.width, actual.height)
                .apply(host, FrameLayout(host))
            layout(rendered, 138, 123)
            layout(expected, 138, 123)
            if (world) {
                for (name in WidgetTestViews.find(rendered, R.id.city_name))
                    assertEquals(View.VISIBLE, name.visibility)
                for (date in WidgetTestViews.find(rendered, R.id.city_date))
                    assertEquals(View.VISIBLE, date.visibility)
            } else {
                assertEquals((expected.findViewById<View>(R.id.clock_time) as TextClock).textSize,
                    (rendered.findViewById<View>(R.id.clock_time) as TextClock).textSize, .01f)
                assertEquals((expected.findViewById<View>(R.id.clock_date) as TextClock).textSize,
                    (rendered.findViewById<View>(R.id.clock_date) as TextClock).textSize, .01f)
            }
        }
    }

    @Throws(Exception::class)
    private fun bind(id: Int, world: Boolean = true): AppWidgetProviderInfo {
        val info: AppWidgetProviderInfo = AppWidgetProviderInfo()
        info.provider =
            ComponentName(
                context,
                if (world) WorldWidgetProvider::class.java else ClockWidgetProvider::class.java,
            )
        info.configure = ComponentName(context, MainActivity::class.java)
        info.initialLayout = if (world) R.layout.world_widget else R.layout.clock_widget
        info.label = if (world) "World clock" else "Clock"
        ReflectionHelpers.setField(
            info,
            "providerInfo",
            context
                .getPackageManager()
                .getReceiverInfo(info.provider, PackageManager.GET_META_DATA),
        )
        Shadows.shadowOf(manager).addInstalledProvider(info)
        Shadows.shadowOf(manager).addBoundWidget(id, info)
        return info
    }

    private fun foreignHost(): Context {
        return object : ContextWrapper(context) {
            // API36 follows actual different-package resource loading; API34 retains the explicit
            // guard.
            override fun getPackageName(): String =
                if (Build.VERSION.SDK_INT >= 35) "app.noonlet.testhost"
                else super.getPackageName()

            override fun isRestricted(): Boolean = true
        }
    }

    @Throws(Exception::class)
    private fun installResourceApkPaths() {
        // Robolectric has no installed base.apk. API35's LoadedApk path validation assumes one.
        // Use AGP's real resource APK in every own-package cache; keep all native resource checks.
        val properties: Properties = Properties()
        javaClass
            .getClassLoader()
            .getResourceAsStream("com/android/tools/test_config.properties")
            .use(
                { input ->
                    assertNotNull("AGP must publish its unit-test resource APK", input)
                    properties.load(input)
                },
            )
        val apk: File = File(properties.getProperty("android_resource_apk")).getCanonicalFile()
        assertTrue("The fixture must point to an actual resource APK", apk.isFile())
        ZipFile(apk)
            .use(
                { archive ->
                    assertNotNull(archive.getEntry("AndroidManifest.xml"))
                    assertNotNull(archive.getEntry("resources.arsc"))
                },
            )
        val path: String = apk.getPath()
        resourcePaths(context.getApplicationInfo(), path)
        val installed: PackageInfo =
            Shadows.shadowOf(context.getPackageManager())
                .getInternalMutablePackageInfo(context.getPackageName())
        resourcePaths(requireNotNull(installed.applicationInfo), path)
        if (installed.activities != null)
            for (activity: ActivityInfo in installed.activities) resourcePaths(
                activity.applicationInfo,
                path,
            )
        if (installed.receivers != null)
            for (receiver: ActivityInfo in installed.receivers) resourcePaths(
                receiver.applicationInfo,
                path,
            )
        val activityThread: Class<*> = Class.forName("android.app.ActivityThread")
        val thread: Any =
            ReflectionHelpers.callStaticMethod(activityThread, "currentActivityThread")
        for (includeCode: Boolean in booleanArrayOf(false, true)) {
            val loaded: Any? =
                ReflectionHelpers.callInstanceMethod(
                    thread,
                    "peekPackageInfo",
                    ClassParameter.from(String::class.java, context.getPackageName()),
                    ClassParameter.from(Boolean::class.javaPrimitiveType, includeCode),
                )
            if (loaded == null) continue
            val info: ApplicationInfo = ReflectionHelpers.getField(loaded, "mApplicationInfo")
            assertEquals(context.getPackageName(), info.packageName)
            resourcePaths(info, path)
            ReflectionHelpers.setField(loaded, "mAppDir", path)
            ReflectionHelpers.setField(loaded, "mResDir", path)
        }
        assertEquals(path, context.getPackageCodePath())
        assertEquals(path, context.getPackageResourcePath())
        val restricted: Context =
            context.createPackageContext(context.getPackageName(), Context.CONTEXT_RESTRICTED)
        assertTrue(restricted.isRestricted())
        assertEquals(path, restricted.getApplicationInfo().sourceDir)
        assertEquals(
            context.getPackageName(),
            restricted.getResources().getResourcePackageName(R.layout.world_widget),
        )
        // This resource-only fixture does not emulate installation or loading application dex code.
    }

    private fun savePlaces(id: Int, count: Int) {
        val settings: WidgetSettings = WidgetSettings.defaults(true)
        settings.cities = Arrays.copyOf(PLACES, count)
        WidgetSettingsStore.save(context, id, true, settings)
    }

    private fun options(id: Int, size: IntArray, responsive: Boolean) {
        val options: Bundle = Bundle()
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, size[0])
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, size[0])
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, size[1])
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, size[1])
        if (responsive) {
            val alternatives: ArrayList<SizeF> = ArrayList()
            for (other: IntArray in SIZES) alternatives.add(
                SizeF(other[0].toFloat(), other[1].toFloat()),
            )
            options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, alternatives)
        }
        manager!!.updateAppWidgetOptions(id, options)
    }

    private fun publish(id: Int, sizes: ArrayList<out SizeF?>) {
        val options: Bundle = Bundle()
        options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes)
        manager!!.updateAppWidgetOptions(id, options)
    }

    private class BackgroundApply : Executor, AutoCloseable {
        private val executor: ExecutorService =
            Executors.newSingleThreadExecutor(
                { task ->
                    val thread: Thread = Thread(task, "noonlet-world-host")
                    thread.setDaemon(true)
                    thread
                },
            )
        @Volatile private var worker: Thread? = null

        @Override
        override fun execute(command: Runnable) {
            executor.execute(
                {
                    worker = Thread.currentThread()
                    command.run()
                },
            )
        }

        @Throws(Exception::class)
        fun awaitWork() {
            // A barrier in this FIFO executor waits for the real AsyncApplyTask background phase.
            executor.submit({}).get(10, TimeUnit.SECONDS)
            assertNotNull("RemoteViews must dispatch work to the supplied executor", worker)
            assertNotSame(
                "Inflation must really run off the main thread",
                Looper.getMainLooper().getThread(),
                worker,
            )
        }

        @Override
        @Throws(Exception::class)
        override fun close() {
            executor.shutdown()
            assertTrue(
                "The host test must not leave its executor running",
                executor.awaitTermination(10, TimeUnit.SECONDS),
            )
        }
    }

    companion object {
        private val INLINE: Executor = Executor { it.run() }
        private val PLACES: Array<String> =
            arrayOf<String>(
                CityCatalog.SAN_FRANCISCO,
                "Asia/Riyadh",
                "Asia/Tokyo",
                "Europe/London",
            )
        private val SIZES: Array<IntArray> =
            arrayOf<IntArray>(
                intArrayOf(167, 82),
                intArrayOf(328, 82),
                intArrayOf(167, 174),
                intArrayOf(328, 174),
            )

        private fun resourcePaths(info: ApplicationInfo, path: String) {
            assertNotNull(info)
            info.sourceDir = path
            info.publicSourceDir = path
            // getCodePath() reads scanSourceDir, whereas getBaseCodePath() reads sourceDir.
            ReflectionHelpers.setField(info, "scanSourceDir", path)
            ReflectionHelpers.setField(info, "scanPublicSourceDir", path)
            assertEquals(path, ReflectionHelpers.callInstanceMethod(info, "getCodePath"))
        }

        private fun idealSizes(views: RemoteViews): MutableList<SizeF> {
            val layouts: MutableList<RemoteViews> =
                ReflectionHelpers.getField(views, "mSizedRemoteViews")
            assertNotNull("This scenario must exercise a responsive RemoteViews map", layouts)
            val result: ArrayList<SizeF> = ArrayList()
            for (layout: RemoteViews in layouts) result.add(
                ReflectionHelpers.getField(layout, "mIdealSize"),
            )
            return result
        }

        @Throws(Exception::class)
        private fun select(views: RemoteViews, host: Context?, size: SizeF): RemoteViews {
            return RemoteViews::class
                .java!!
                .getMethod("getRemoteViewsToApply", Context::class.java, SizeF::class.java)
                .invoke(views, host, size) as RemoteViews
        }

        @Throws(Exception::class)
        private fun assertParcelBudget(views: RemoteViews, scenario: String) {
            assertEquals(
                "$scenario: The worst supported update must really contain sixteen alternatives",
                16,
                idealSizes(views).size,
            )
            val estimate: Method = RemoteViews::class.java.getDeclaredMethod("estimateMemoryUsage")
            val bitmapBytes: Long = (estimate.invoke(views) as Number).toLong()
            assertEquals(
                "$scenario: Native XML backgrounds must not allocate parcelled bitmap surfaces",
                0,
                bitmapBytes,
            )
            val parcel: Parcel = Parcel.obtain()
            try {
                views.writeToParcel(parcel, 0)
                val bytes: Int = parcel.dataSize()
                // Binder's 1MiB buffer is shared with other in-flight transactions; reserve 75% of
                // it.
                assertTrue(
                    "$scenario: A full XML-only widget update must leave substantial IPC headroom: " +
                        bytes,
                    bytes <= 256 * 1024,
                )
                assertNotNull(crossProcess(views))
            } finally {
                parcel.recycle()
            }
        }

        @Throws(Exception::class)
        private fun applyAsync(
            views: RemoteViews,
            host: Context,
            parent: ViewGroup,
            current: View?,
            worker: BackgroundApply,
        ): View {
            val listenerType: Class<*> =
                Class.forName("android.widget.RemoteViews\$OnViewAppliedListener")
            val outcome = arrayOfNulls<Any>(2)
            val listener: Any =
                Proxy.newProxyInstance(
                    listenerType.getClassLoader(),
                    arrayOf<Class<*>>(listenerType),
                    { proxy, method, args ->
                        if ("onViewApplied".equals(method.getName())) outcome[0] = args[0]
                        else if ("onError".equals(method.getName())) outcome[1] = args[0]
                        null
                    },
                )
            // These launcher APIs are public in framework source but hidden from android.jar.
            val method: Method =
                RemoteViews::class
                    .java!!
                    .getMethod(
                        if (current == null) "applyAsync" else "reapplyAsync",
                        Context::class.java,
                        if (current == null) ViewGroup::class.java else View::class.java,
                        Executor::class.java,
                        listenerType,
                    )
            method.invoke(views, host, if (current == null) parent else current, worker, listener)
            worker.awaitWork()
            idle()
            assertNull(
                "Asynchronous RemoteViews reported an action error: " + outcome[1],
                outcome[1],
            )
            assertNotNull("The actual asynchronous apply callback did not complete", outcome[0])
            return outcome[0] as View
        }

        private fun framework(
            host: AppWidgetHostView,
            method: String,
            vararg args: ClassParameter<*>,
        ) {
            Shadow.directlyOn<Any, AppWidgetHostView>(
                host,
                AppWidgetHostView::class.java,
                method,
                *args,
            )
        }

        private fun assertVisibleWorld(view: View, count: Int, scenario: String) {
            val times: MutableList<View> = WidgetTestViews.find(view, R.id.city_time)
            assertEquals(scenario, count, times.size)
            for (time: View in times) {
                assertTrue(scenario + " collapsed time width", time.getWidth() > 0)
                assertTrue(scenario + " collapsed time height", time.getHeight() > 0)
                val visible: Rect = Rect()
                assertTrue(
                    scenario + " has an invisible/clipped city clock",
                    time.getGlobalVisibleRect(visible),
                )
                assertFalse(
                    scenario + " empty clock text",
                    (time as TextClock).getText().toString().isEmpty(),
                )
            }
        }

        private fun layout(view: View, width: Int, height: Int) {
            idle()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
            WidgetTestViews.prepare(view)
        }

        private fun layoutWindow(root: View) {
            layout(
                root,
                root.getResources().getDisplayMetrics().widthPixels,
                root.getResources().getDisplayMetrics().heightPixels,
            )
        }

        private fun position(view: View, ancestor: View): FloatArray {
            val result: FloatArray = floatArrayOf(0f, 0f)
            var current: View = view
            while (current !== ancestor) {
                result[0] += current.getLeft()
                result[1] += current.getTop()
                current = current.getParent() as View
            }
            return result
        }

        private fun idle() {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }

        private fun hostLogs(): String {
            return ShadowLog.getLogsForTag("AppWidgetHostView").toString()
        }

        private fun crossProcess(sender: RemoteViews): RemoteViews {
            val parcel: Parcel = Parcel.obtain()
            val originalPid: Int = android.os.Process.myPid()
            try {
                val senderInfo: ApplicationInfo = ReflectionHelpers.getField(sender, "mApplication")
                val codePath: String =
                    ReflectionHelpers.callInstanceMethod(senderInfo, "getCodePath")
                assertNotNull("The sender's actual scan code path must exist", codePath)
                assertTrue(File(codePath).isFile())
                sender.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                ShadowProcess.setPid(originalPid + 10000)
                val received: RemoteViews = RemoteViews.CREATOR.createFromParcel(parcel)
                val receivedInfo: ApplicationInfo =
                    ReflectionHelpers.getField(received, "mApplication")
                assertEquals(
                    "IPC must preserve the actual ApplicationInfo code path",
                    codePath,
                    ReflectionHelpers.callInstanceMethod(receivedInfo, "getCodePath"),
                )
                return received
            } finally {
                ShadowProcess.setPid(originalPid)
                parcel.recycle()
            }
        }
    }
}

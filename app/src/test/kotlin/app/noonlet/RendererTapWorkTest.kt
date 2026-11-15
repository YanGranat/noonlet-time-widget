package app.noonlet

import android.app.Application
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.Looper
import android.provider.AlarmClock
import android.util.SizeF
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import java.util.ArrayList
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** Preview interaction is inert, while every real responsive layout retains its native action. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RendererTapWorkTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        val handler: ResolveInfo = ResolveInfo()
        handler.activityInfo = ActivityInfo()
        handler.activityInfo.name = "AlarmActivity"
        handler.activityInfo.packageName = "example.system.clock"
        handler.activityInfo.exported = true
        handler.activityInfo.enabled = handler.activityInfo.exported
        handler.activityInfo.applicationInfo = ApplicationInfo()
        handler.activityInfo.applicationInfo.enabled = true
        handler.activityInfo.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM
        Shadows.shadowOf(context.getPackageManager())
            .addResolveInfoForIntent(
                Intent(AlarmClock.ACTION_SHOW_ALARMS),
                handler,
            )
    }

    @Test
    fun inertClockAndWorldPreviewsNeverAskForInstalledAppHandlers() {
        val noPackageLookups: Context =
            object : ContextWrapper(context) {
                override fun getPackageManager(): PackageManager {
                    throw AssertionError("An inert preview requested package handlers")
                }
            }
        for (world: Boolean in booleanArrayOf(false, true)) {
            val settings: WidgetSettings = WidgetSettings.defaults(world)
            settings.tapAction = "clock"
            settings.advancedTap = true
            settings.alarmTapAction = "clock"
            settings.dateTapAction = settings.alarmTapAction
            settings.timeTapAction = settings.dateTapAction
            settings.cities = arrayOf<String>("Europe/London", "Asia/Tokyo")
            val view: View =
                WidgetRenderer.preview(noPackageLookups, -710, world, settings, 328f, 174f)
                    .apply(context, FrameLayout(context))
            assertFalse(view.findViewById<View>(R.id.widget_root).performClick())
            for (target: View in
                WidgetTestViews.find(
                    view,
                    if (world) R.id.world_card else R.id.clock_time,
                )) assertFalse(target.performClick())
        }
    }

    @Test
    fun responsiveVariantsResolveTheClockOnTapAndKeepClicksWorking() {
        for (world: Boolean in booleanArrayOf(false, true)) {
            val id: Int = if (world) 712 else 711
            val settings: WidgetSettings = WidgetSettings.defaults(world)
            settings.tapAction = "clock"
            settings.cities = arrayOf<String>("Europe/London", "Asia/Tokyo")
            WidgetSettingsStore.save(context, id, world, settings)
            val manager: AppWidgetManager = AppWidgetManager.getInstance(context)
            val provider: AppWidgetProviderInfo = AppWidgetProviderInfo()
            provider.provider =
                ComponentName(
                    context,
                    if (world) WorldWidgetProvider::class.java else ClockWidgetProvider::class.java,
                )
            Shadows.shadowOf(manager).addBoundWidget(id, provider)
            val sizes: ArrayList<SizeF> = ArrayList()
            for (index: Int in 0..15) sizes.add(
                SizeF((167 + index * 10).toFloat(), (82 + index * 10).toFloat()),
            )
            val options: Bundle = Bundle()
            options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, sizes)
            manager.updateAppWidgetOptions(id, options)
            val counted: CountingContext = CountingContext(context)
            val complete: RemoteViews =
                if (world) WidgetRenderer.world(counted, id) else WidgetRenderer.clock(counted, id)
            assertEquals(
                "Clock targets are resolved only when tapped",
                0,
                counted.lookups,
            )
            val variants: MutableList<RemoteViews> =
                ReflectionHelpers.getField(complete, "mSizedRemoteViews")
            assertEquals(16, variants.size)
            for (variant: RemoteViews in variants) {
                val view: View = variant.apply(context, FrameLayout(context))
                assertTrue(
                    view
                        .findViewById<View>(if (world) R.id.world_card else R.id.clock_time)
                        .performClick(),
                )
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                val launched: Intent =
                    Shadows.shadowOf(context as Application?).getNextStartedActivity()
                assertNotNull(launched)
                org.robolectric.Robolectric.buildActivity(WidgetTapActivity::class.java, launched)
                    .create().use { controller ->
                        val target = Shadows.shadowOf(controller.get()).nextStartedActivity
                        assertEquals(AlarmClock.ACTION_SHOW_ALARMS, target.action)
                        assertEquals(ComponentName("example.system.clock", "AlarmActivity"), target.component)
                    }
            }
        }
    }

    private class CountingContext(base: Context) : ContextWrapper(base) {
        var lookups: Int = 0

        override fun getPackageManager(): PackageManager {
            lookups++
            return super.getPackageManager()
        }
    }
}

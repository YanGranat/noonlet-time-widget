package app.noonlet

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.widget.RemoteViews
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAppWidgetManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36], shadows = [WidgetBatchFailureTest.Delivery::class])
class WidgetBatchFailureTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun prepare() {
        Delivery.failedId = 8101
        Delivery.delivered.clear()
        for ((id, world) in listOf(8101 to false, 8102 to false, 8103 to true)) {
            val manager = AppWidgetManager.getInstance(context)
            val info = AppWidgetProviderInfo().apply {
                provider = BaseWidgetProvider.component(context, world)
                initialLayout = if (world) R.layout.world_widget else R.layout.clock_widget
            }
            Shadows.shadowOf(manager).addInstalledProvider(info)
            Shadows.shadowOf(manager).addBoundWidget(id, info)
            WidgetSettingsStore.save(context, id, world, WidgetSettings.defaults(world))
        }
    }

    @Test fun aFailedDeliveryDoesNotPreventAnotherWidgetOrSchedulerReconciliation() {
        assertFalse(BaseWidgetProvider.updateAll(context, false))
        assertEquals(listOf(8101, 8102), Delivery.delivered)
        assertNotNull(Shadows.shadowOf(AppWidgetManager.getInstance(context)).getViewFor(8102))
        Delivery.delivered.clear()
        object : BaseWidgetProvider(false) {}.onUpdate(context, AppWidgetManager.getInstance(context), intArrayOf(8101, 8102))
        assertEquals(listOf(8101, 8102), Delivery.delivered)
    }

    @Test fun languagePublicationAlsoUpdatesWorldClocksAndRetriesAnIncompleteDelivery() {
        UpdateReceiver.updateForLanguage(context)
        assertEquals(listOf(8101, 8102, 8103), Delivery.delivered)
        assertFalse(AppLocales.widgetLanguageDelivered(context, AppLocales.widgetLanguageSignature(context)))
        Delivery.failedId = -1
        Delivery.delivered.clear()
        UpdateReceiver.updateForLanguage(context)
        assertEquals(listOf(8101, 8102, 8103), Delivery.delivered)
        assertTrue(AppLocales.widgetLanguageDelivered(context, AppLocales.widgetLanguageSignature(context)))
    }

    @Test fun weatherPublicationReportsFailureAfterUpdatingHealthyWidgetsAndCanRecover() {
        for (id in listOf(8101, 8102)) WidgetSettingsStore.save(context, id, false, WidgetSettings().apply {
            weatherEnabled = true
            weatherUseDevice = false
            weatherPlace = WeatherPlace("East Bay", 35_000_000, 10_000_000).encode()
        })
        assertThrows(ExecutionException::class.java) { WeatherRefreshScheduler.updateWidgets(context).get(5, TimeUnit.SECONDS) }
        assertTrue(Delivery.delivered.contains(8102))
        Delivery.failedId = -1
        Delivery.delivered.clear()
        WeatherRefreshScheduler.updateWidgets(context).get(5, TimeUnit.SECONDS)
        assertEquals(listOf(8101, 8102), Delivery.delivered)
    }

    @Implements(AppWidgetManager::class)
    class Delivery : ShadowAppWidgetManager() {
        @Implementation public override fun updateAppWidget(appWidgetId: Int, views: RemoteViews) {
            delivered += appWidgetId
            if (appWidgetId == failedId) throw IllegalStateException("Widget delivery unavailable")
            super.updateAppWidget(appWidgetId, views)
        }
        companion object {
            var failedId = -1
            val delivered = mutableListOf<Int>()
        }
    }
}

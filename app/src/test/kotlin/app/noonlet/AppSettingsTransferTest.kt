package app.noonlet

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.ListView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 36])
class AppSettingsTransferTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun prepare() {
        for (name in listOf("noonlet_widgets", "widget_drafts", "widget_configuration", "MainActivity",
            "noonlet_app", "noonlet_imported_widgets"))
            context.getSharedPreferences(name, 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
        ShadowToast.reset()
    }

    @Test fun applicationSettingsOwnTransferAndConfirmationChangesBothWidgetTypes() {
        val data = SettingsArchive.capture(context).copy(
            clock = SettingsArchive.Group(SettingsArchive.Configuration(WidgetSize.FOUR_BY_ONE,
                WidgetSettings().apply { textSize = 61 }), emptyList()),
            world = SettingsArchive.Group(SettingsArchive.Configuration(WidgetSize.FOUR_BY_TWO,
                WidgetSettings.defaults(true).apply { textSize = 54 }), emptyList()))
        val uri = Uri.parse("content://settings.example/Noonlet-settings.json")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, SettingsArchive.encode(data).byteInputStream())
        Robolectric.buildActivity(AppSettingsActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val root = activity.findViewById<View>(android.R.id.content)
            assertTrue(root.findViewWithTag<View>("import-settings").performClick())
            val request = Shadows.shadowOf(activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.action)
            root.findViewWithTag<View>("import-settings").performClick()
            assertNull(Shadows.shadowOf(activity).nextStartedActivityForResult)
            Shadows.shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
            waitUntil { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
            assertEquals(56, WidgetSettingsStore.loadUnplaced(context, false).textSize)
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitUntil { ShadowToast.getTextOfLatestToast() == "Settings imported" }
            assertEquals(61, WidgetSettingsStore.loadUnplaced(context, false).textSize)
            assertEquals(54, WidgetSettingsStore.loadUnplaced(context, true).textSize)
            assertEquals(WidgetSize.FOUR_BY_ONE.ordinal, EditorPreferences(context).previewSize(false, 4))
            assertEquals(WidgetSize.FOUR_BY_TWO.ordinal, EditorPreferences(context).previewSize(true, 4))
        }
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use {
            assertNull(it.get().findViewById<View>(android.R.id.content).findViewWithTag<View>("section-toggle:transfer"))
        }
    }

    @Test fun rejectedInputDoesNotBlockExportOrAnotherImport() {
        val invalid = Uri.parse("content://settings.example/invalid.json")
        val nested = "[".repeat(700) + "0" + "]".repeat(700)
        Shadows.shadowOf(context.contentResolver).registerInputStream(invalid,
            ("#\"\n{\"data\":" + nested + "}#\"").byteInputStream())
        Robolectric.buildActivity(AppSettingsActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            fun choose(tag: String, uri: Uri?) {
                activity.findViewById<View>(android.R.id.content).findViewWithTag<View>(tag).performClick()
                val request = Shadows.shadowOf(activity).nextStartedActivityForResult
                assertNotNull(request)
                Shadows.shadowOf(activity).receiveResult(request.intent,
                    if (uri == null) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                    uri?.let { Intent().setData(it) })
            }
            choose("import-settings", invalid)
            waitUntil { ShadowToast.getTextOfLatestToast() == AppLocales.text(context, R.string.settings_file_error) }
            assertEquals(56, WidgetSettingsStore.loadUnplaced(context, false).textSize)
            choose("export-settings", null)
            val valid = Uri.parse("content://settings.example/valid.json")
            val contents = SettingsArchive.capture(context)
            contents.clock.draft.settings.textSize = 61
            Shadows.shadowOf(context.contentResolver).registerInputStream(valid, SettingsArchive.encode(contents).byteInputStream())
            choose("import-settings", valid)
            waitUntil { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            waitUntil { ShadowToast.getTextOfLatestToast() == "Settings imported" }
            assertEquals(61, WidgetSettingsStore.loadUnplaced(context, false).textSize)
        }
    }

    @Test fun pendingImportSurvivesRecreationAndCancellationKeepsBothDrafts() {
        val contents = SettingsArchive.capture(context)
        contents.clock.draft.settings.textSize = 61
        contents.world.draft.settings.textSize = 54
        val uri = Uri.parse("content://settings.example/recreated.json")
        Shadows.shadowOf(context.contentResolver).registerInputStream(uri, SettingsArchive.encode(contents).byteInputStream())
        Robolectric.buildActivity(AppSettingsActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            activity.findViewById<View>(android.R.id.content).findViewWithTag<View>("import-settings").performClick()
            val request = Shadows.shadowOf(activity).nextStartedActivityForResult
            Shadows.shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
            waitUntil { ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
            val first = ShadowAlertDialog.getLatestAlertDialog()
            controller.recreate()
            waitUntil { ShadowAlertDialog.getLatestAlertDialog() !== first && ShadowAlertDialog.getLatestAlertDialog()?.isShowing == true }
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(56, WidgetSettingsStore.loadUnplaced(context, false).textSize)
            assertEquals(56, WidgetSettingsStore.loadUnplaced(context, true).textSize)
        }
    }

    @Test fun cancelledPickersCanBeReopenedAndExportContainsBothConfigurations() {
        val output = java.io.ByteArrayOutputStream()
        val uri = Uri.parse("content://settings.example/export.json")
        Shadows.shadowOf(context.contentResolver).registerOutputStream(uri, output)
        Robolectric.buildActivity(AppSettingsActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val root = activity.findViewById<View>(android.R.id.content)
            for (tag in listOf("import-settings", "export-settings")) {
                root.findViewWithTag<View>(tag).performClick()
                val request = Shadows.shadowOf(activity).nextStartedActivityForResult
                Shadows.shadowOf(activity).receiveResult(request.intent, Activity.RESULT_CANCELED, null)
            }
            root.findViewWithTag<View>("export-settings").performClick()
            val request = Shadows.shadowOf(activity).nextStartedActivityForResult
            assertEquals("Noonlet-settings.json", request.intent.getStringExtra(Intent.EXTRA_TITLE))
            Shadows.shadowOf(activity).receiveResult(request.intent, Activity.RESULT_OK, Intent().setData(uri))
            waitUntil { output.size() > 0 }
            val restored = SettingsArchive.read(output.toByteArray().inputStream())
            assertEquals(56, restored.clock.draft.settings.textSize)
            assertEquals(56, restored.world.draft.settings.textSize)
        }
    }

    @Test fun unplacedImportedConfigurationOpensWithItsOwnSettingsAndSize() {
        val configuration = SettingsArchive.Configuration(WidgetSize.FOUR_BY_TWO,
            WidgetSettings.defaults(true).apply { cities = arrayOf("Asia/Tokyo"); textSize = 59 })
        ImportedWidgetConfigurations.replace(context, listOf(true to configuration))
        val pending = ImportedWidgetConfigurations.all(context).single()
        Robolectric.buildActivity(MyWidgetsActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            val list = activity.findViewById<View>(android.R.id.content).findViewWithTag<ListView>("placed-widgets")
            assertEquals(1, list.adapter.count)
            list.performItemClick(list.adapter.getView(0, null, list), 0, list.adapter.getItemId(0))
            val intent = Shadows.shadowOf(activity).nextStartedActivity
            assertEquals(EditorIntents.CREATE_WIDGET, intent.action)
            assertEquals(pending.token, intent.getStringExtra(EditorIntents.RESTORED_CONFIGURATION))
            Robolectric.buildActivity(MainActivity::class.java, intent).setup().visible().use {
                assertEquals(59, it.get().session.settings.textSize)
                assertArrayEquals(arrayOf("Asia/Tokyo"), it.get().session.settings.cities)
                assertEquals(WidgetSize.FOUR_BY_TWO.ordinal, EditorPreferences(context).previewSize(true, 4))
            }
            assertEquals(1, ImportedWidgetConfigurations.all(context).size)
        }
    }

    @Test
    @Config(shadows = [PinRequestFixtures.CapturedPins::class])
    fun anImportedConfigurationIsConsumedOnlyAfterAValidPinReceipt() {
        val configuration = SettingsArchive.Configuration(WidgetSize.TWO_BY_ONE,
            WidgetSettings().apply { textSize = 59 })
        ImportedWidgetConfigurations.replace(context, listOf(false to configuration))
        val token = ImportedWidgetConfigurations.all(context).single().token
        PinRequestFixtures.CapturedPins.requests.clear()
        PinRequestFixtures.CapturedPins.supported = true
        PinRequestFixtures.CapturedPins.accepted = true
        PinRequestFixtures.CapturedPins.failure = null
        try {
            fun request() {
                assertTrue(WidgetPinReceiver.request(context, false, configuration.settings,
                    WidgetPinReceiver.preview(context, false, configuration.settings, configuration.size),
                    restoredToken = token))
            }
            request()
            assertEquals(1, ImportedWidgetConfigurations.all(context).size)
            PinRequestFixtures.CapturedPins.requests.last().callback.send(context, 0, Intent())
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, ImportedWidgetConfigurations.all(context).size)
            request()
            val info = AppWidgetProviderInfo().apply {
                provider = ComponentName(context, ClockWidgetProvider::class.java)
                initialLayout = R.layout.clock_widget
            }
            val manager = Shadows.shadowOf(AppWidgetManager.getInstance(context))
            manager.addInstalledProvider(info)
            manager.addBoundWidget(6550, info)
            PinRequestFixtures.CapturedPins.requests.last().callback.send(context, 0,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 6550))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(59, WidgetSettingsStore.load(context, 6550, false).textSize)
            assertTrue(ImportedWidgetConfigurations.all(context).isEmpty())
        } finally {
            PinRequestFixtures.CapturedPins.supported = false
            PinRequestFixtures.CapturedPins.requests.clear()
        }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        do {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        assertTrue(predicate())
    }
}

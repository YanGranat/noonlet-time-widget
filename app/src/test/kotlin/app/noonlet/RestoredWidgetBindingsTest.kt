package app.noonlet

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36], shadows = [ReplacingAtomicFileShadow::class])
class RestoredWidgetBindingsTest {
    private lateinit var context: Context
    private lateinit var directory: File

    @Before
    fun prepare() {
        val app = RuntimeEnvironment.getApplication() as Context
        directory = File(app.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        for (name in listOf("noonlet_widgets", "widget_configuration", "widget_drafts"))
            context.getSharedPreferences(name, 0).edit().clear().commit()
    }

    @After
    fun clean() { directory.deleteRecursively() }

    @Test
    fun restoredIdsRemainAvailableForRemappingButCannotConfigureNewBindings() {
        val saved = WidgetSettings().apply { textSize = 63; dateOffsetX = 12 }
        WidgetSettingsStore.save(context, 723, false, saved)
        WidgetSettingsStore.save(context, 0, false, WidgetSettings().apply { textSize = 54 })
        RestoredWidgetBindings.quarantine(context)
        assertFalse(WidgetSettingsStore.hasOwnSettings(context, 723, false))
        assertEquals(54, WidgetSettingsStore.load(context, 723, false).textSize)
        assertEquals(63, WidgetSettingsStore.loadForRestore(context, 723, false).textSize)
        WidgetSettingsStore.beginConfiguration(context, 723, false)
        assertTrue(WidgetSettingsStore.pendingConfiguration(context, 723, false))
        val chosen = WidgetSettings().apply { textSize = 58 }
        WidgetSettingsStore.save(context, 723, false, chosen)
        assertTrue(WidgetSettingsStore.hasOwnSettings(context, 723, false))
        assertFalse(WidgetSettingsStore.pendingConfiguration(context, 723, false))
        assertEquals(58, WidgetSettingsStore.load(context, 723, false).textSize)
        assertEquals("[]", File(directory, "restored_widgets.json").readText())
    }

    @Test
    fun providerRestorationRemapsOverlappingIdsBeforeActivatingTheirSettings() {
        WidgetSettingsStore.save(context, 724, false, WidgetSettings().apply { textSize = 51 })
        WidgetSettingsStore.save(context, 725, false, WidgetSettings().apply { textSize = 61 })
        RestoredWidgetBindings.quarantine(context)
        ClockWidgetProvider().onRestored(context, intArrayOf(724, 725), intArrayOf(725, 726))
        assertEquals(51, WidgetSettingsStore.load(context, 725, false).textSize)
        assertEquals(61, WidgetSettingsStore.load(context, 726, false).textSize)
        assertTrue(WidgetSettingsStore.hasOwnSettings(context, 725, false))
        assertTrue(WidgetSettingsStore.hasOwnSettings(context, 726, false))
        assertFalse(WidgetSettingsStore.hasOwnSettings(context, 724, false))
    }

    @Test
    fun anInterruptedRestoreMarkerKeepsBackedUpSettingsInactive() {
        WidgetSettingsStore.save(context, 728, false, WidgetSettings().apply { textSize = 64 })
        val restarted = File(directory, "restart").apply { mkdirs() }
        File(restarted, "restored_widgets.json.new").writeText("unfinished")
        val fresh = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = restarted
        }
        assertFalse(WidgetSettingsStore.hasOwnSettings(fresh, 728, false))
        assertEquals(64, WidgetSettingsStore.loadForRestore(fresh, 728, false).textSize)
        WidgetSettingsStore.save(fresh, 728, false, WidgetSettings().apply { textSize = 57 })
        assertTrue(WidgetSettingsStore.hasOwnSettings(fresh, 728, false))
        assertEquals(57, WidgetSettingsStore.load(fresh, 728, false).textSize)
    }

    @Test
    fun interruptedJournalWriteRecoversSourcesBeforeAnotherProviderRestores() {
        WidgetSettingsStore.save(context, 741, false, WidgetSettings().apply { textSize = 51 })
        WidgetSettingsStore.save(context, 742, false, WidgetSettings().apply { textSize = 61 })
        RestoredWidgetBindings.quarantine(context)
        ClockWidgetProvider().onRestored(context, intArrayOf(741), intArrayOf(742))
        val journal = File(directory, "widget_restore.json")
        assertTrue(journal.renameTo(File(directory, "widget_restore.json.bak")))
        WideClockWidgetProvider().onRestored(context, intArrayOf(742), intArrayOf(741))
        assertEquals(51, WidgetSettingsStore.load(context, 742, false).textSize)
        assertEquals(61, WidgetSettingsStore.load(context, 741, false).textSize)
    }

    @Test
    fun repeatedProviderDeliveryAndReversedOrderKeepTheOriginalConfiguration() {
        WidgetSettingsStore.save(context, 751, true, WidgetSettings.defaults(true).apply { opacity = 34 })
        WidgetSettingsStore.save(context, 752, true, WidgetSettings.defaults(true).apply { opacity = 78 })
        RestoredWidgetBindings.quarantine(context)
        repeat(2) {
            WideWorldWidgetProvider().onRestored(context, intArrayOf(752), intArrayOf(751))
            WorldWidgetProvider().onRestored(context, intArrayOf(751), intArrayOf(752))
        }
        assertEquals(34, WidgetSettingsStore.load(context, 752, true).opacity)
        assertEquals(78, WidgetSettingsStore.load(context, 751, true).opacity)
    }

    @Test
    fun sizeProvidersRestoreSwappedIdsWithoutOverwritingEachOther() {
        for (world in listOf(false, true)) {
            WidgetSettingsStore.save(context, 731, world, WidgetSettings.defaults(world).apply {
                timeColor = android.graphics.Color.RED
            })
            WidgetSettingsStore.save(context, 732, world, WidgetSettings.defaults(world).apply {
                timeColor = android.graphics.Color.BLUE
            })
        }
        RestoredWidgetBindings.quarantine(context)
        ClockWidgetProvider().onRestored(context, intArrayOf(731), intArrayOf(732))
        WideClockWidgetProvider().onRestored(context, intArrayOf(732), intArrayOf(731))
        WorldWidgetProvider().onRestored(context, intArrayOf(731), intArrayOf(732))
        WideWorldWidgetProvider().onRestored(context, intArrayOf(732), intArrayOf(731))
        for (world in listOf(false, true)) {
            assertEquals(android.graphics.Color.RED, WidgetSettingsStore.load(context, 732, world).timeColor)
            assertEquals(android.graphics.Color.BLUE, WidgetSettingsStore.load(context, 731, world).timeColor)
            assertTrue(WidgetSettingsStore.hasOwnSettings(context, 731, world))
            assertTrue(WidgetSettingsStore.hasOwnSettings(context, 732, world))
        }
    }

    @Test
    fun ordinaryApplicationUpdatesDoNotQuarantineConfiguredWidgets() {
        WidgetSettingsStore.save(context, 727, true, WidgetSettings.defaults(true).apply { fontWeight = 550 })
        assertTrue(WidgetSettingsStore.hasOwnSettings(context, 727, true))
        assertEquals(550, WidgetSettingsStore.load(context, 727, true).fontWeight)
        assertFalse(File(directory, "restored_widgets.json").exists())
        val info = context.packageManager.getApplicationInfo(context.packageName, 0)
        assertNull(info.backupAgentName)
        assertEquals(0, info.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}

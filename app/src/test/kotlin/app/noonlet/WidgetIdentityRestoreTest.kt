package app.noonlet

import android.content.Context
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36], shadows = [ReplacingAtomicFileShadow::class])
class WidgetIdentityRestoreTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun separateRestoreCallbacksKeepIdentityAndSettingsTogetherDuringSwaps() {
        for (reverse in listOf(false, true)) {
            for (name in listOf("noonlet_widgets", "widget_configuration"))
                context.getSharedPreferences(name, 0).edit().clear().commit()
            WidgetSettingsStore.save(context, 7241, false, WidgetSettings().apply { textSize = 61 })
            WidgetSettingsStore.save(context, 7242, false, WidgetSettings().apply { textSize = 49 })
            val first = WidgetSettingsStore.identity(context, 7241, false)
            val second = WidgetSettingsStore.identity(context, 7242, false)
            RestoredWidgetBindings.quarantine(context)
            val pairs = listOf(7241 to 7242, 7242 to 7241).let { if (reverse) it.reversed() else it }
            for ((old, new) in pairs) WidgetRestoreState.remap(context, intArrayOf(old), intArrayOf(new), false)
            assertEquals("reverse=$reverse stored=${WidgetSettingsStore.loadForRestore(context, 7241, false).textSize} quarantined=${RestoredWidgetBindings.contains(context, 7241, false)}",
                49, WidgetSettingsStore.load(context, 7241, false).textSize)
            assertEquals(61, WidgetSettingsStore.load(context, 7242, false).textSize)
            assertEquals(second, WidgetSettingsStore.existingIdentity(context, 7241, false))
            assertEquals(first, WidgetSettingsStore.existingIdentity(context, 7242, false))
            WidgetRestoreState.remap(context, intArrayOf(7241), intArrayOf(7242), false)
            assertEquals(first, WidgetSettingsStore.existingIdentity(context, 7242, false))
        }
    }

    @Test fun aJournalWithoutIdentitiesCannotAdoptTheTargetsPreviousIdentity() {
        WidgetSettingsStore.save(context, 7251, false, WidgetSettings().apply { textSize = 59 })
        WidgetSettingsStore.save(context, 7252, false, WidgetSettings())
        val previous = WidgetSettingsStore.identity(context, 7252, false)
        WidgetRestoreState.capture(context)
        val journal = File(context.noBackupFilesDir, "widget_restore.json")
        val state = JSONObject(journal.readText(Charsets.UTF_8)).apply { remove("identities") }
        journal.writeText(state.toString(), Charsets.UTF_8)
        WidgetRestoreState.remap(context, intArrayOf(7251), intArrayOf(7252), false)
        assertEquals(59, WidgetSettingsStore.load(context, 7252, false).textSize)
        assertNull(WidgetSettingsStore.existingIdentity(context, 7252, false))
        assertNotEquals(previous, WidgetSettingsStore.identity(context, 7252, false))
    }
}

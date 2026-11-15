package app.noonlet

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import android.view.View
import android.widget.Toast

/** Provides the foreground window required by a launcher pin request. */
class WidgetPinActivity : Activity() {
    private var requested = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val size = intent.getIntExtra(EditorIntents.PIN_SIZE, -1)
        requested = state?.getBoolean("pin_requested", false) ?: false
        if (requested || intent.action != EditorIntents.CREATE_WIDGET ||
            intent.component != ComponentName(this, WidgetPinActivity::class.java) ||
            !intent.hasExtra(EditorIntents.PIN_SNAPSHOT) || size !in WidgetSize.entries.indices) {
            finishAndRemoveTask()
            return
        }
        setContentView(View(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        })
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putBoolean("pin_requested", requested)
        super.onSaveInstanceState(out)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || requested || isFinishing) return
        requested = true
        val world = intent.getBooleanExtra("world", false)
        val size = WidgetSize.entries[intent.getIntExtra(EditorIntents.PIN_SIZE, -1)]
        val settings = WidgetSettings.defaults(world)
        WidgetSettingsStore.restoreEditorState(this, 0, world, settings,
            intent.getBundleExtra(EditorIntents.PIN_SNAPSHOT))
        if (!WidgetPinReceiver.request(this, world, settings,
            WidgetPinReceiver.preview(this, world, settings, size), size = size,
            restoredToken = intent.getStringExtra(EditorIntents.RESTORED_CONFIGURATION)))
            Toast.makeText(this, AppLocales.text(this, R.string.launcher_add_hint,
                getString(R.string.app_name)), Toast.LENGTH_LONG).show()
        finishAndRemoveTask()
        overridePendingTransition(0, 0)
    }
}

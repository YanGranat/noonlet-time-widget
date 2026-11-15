package app.noonlet

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

internal class WidgetTypeActivity : AppScreenActivity() {
    override val titleResource = R.string.add_widget

    override fun buildContent(parent: LinearLayout) {
        for (world in listOf(false, true)) {
            val kind = if (world) "world" else "clock"
            val title = tr(if (world) R.string.world_widget_name else R.string.clock_widget_name)
            val option = card(parent).apply {
                tag = "widget-type:$kind"
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = AppDialogBuilder.ripple(this@WidgetTypeActivity, palette.surface, 16, false)
                isClickable = true
                isFocusable = true
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                contentDescription = title
                setOnClickListener {
                    startActivity(EditorIntents.create(this@WidgetTypeActivity, world))
                    finish()
                }
            }
            option.addView(label(title).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, -2))
            val thumbnail = WidgetThumbnailView(this).apply {
                tag = "widget-type-preview:$kind"
                bindCategory(world)
            }
            option.addView(thumbnail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
    }
}

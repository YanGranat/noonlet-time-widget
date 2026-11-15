package app.noonlet

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

internal class MyWidgetsActivity : AppScreenActivity() {
    override val titleResource = R.string.my_widgets
    override val scrollContent = false
    private data class Item(val id: Int, val world: Boolean, val number: Int,
        val imported: ImportedWidgetConfigurations.Item? = null)
    private var items = emptyList<Item>()
    private var list: ListView? = null
    private var restoredPosition = 0
    private var restoredTop = 0

    override fun onCreate(state: Bundle?) {
        restoredPosition = state?.getInt("widgets_position", 0) ?: 0
        restoredTop = state?.getInt("widgets_top", 0) ?: 0
        super.onCreate(state)
    }

    override fun buildContent(parent: LinearLayout) {
        list?.let {
            restoredPosition = it.firstVisiblePosition
            restoredTop = it.getChildAt(0)?.top ?: 0
        }
        items = placedItems()
        parent.setPadding(parent.paddingLeft, parent.paddingTop, parent.paddingRight, dp(8))
        val viewport = FrameLayout(this)
        parent.addView(viewport, LinearLayout.LayoutParams(-1, 0, 1f))
        val add = label(tr(R.string.add_widget), 16f, palette.onAccent).apply {
            tag = "add-widget"
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            setPadding(dp(48), dp(12), dp(48), dp(12))
            background = AppDialogBuilder.ripple(this@MyWidgetsActivity, palette.accent, 12, true)
            isClickable = true
            isFocusable = true
            setOnClickListener { chooseType() }
        }
        val empty = label(tr(R.string.no_widgets), 16f, palette.muted).apply {
            tag = "widgets-empty"
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }
        viewport.addView(empty, FrameLayout.LayoutParams(-1, -1))
        list = ListView(this).apply {
            tag = "placed-widgets"
            divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            dividerHeight = dp(12)
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            adapter = WidgetAdapter()
            emptyView = empty
            setSelectionFromTop(restoredPosition, restoredTop)
            setOnItemClickListener { _, _, position, _ ->
                val item = items[position]
                val intent = if (item.imported != null)
                    EditorIntents.create(this@MyWidgetsActivity, item.world)
                        .putExtra(EditorIntents.RESTORED_CONFIGURATION, item.imported.token)
                else EditorIntents.edit(this@MyWidgetsActivity, item.id, item.world)
                    .putExtra(EditorIntents.FROM_WIDGETS, true)
                startActivity(intent)
            }
        }
        viewport.addView(list, FrameLayout.LayoutParams(-1, -1))
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            addView(FrameLayout(this@MyWidgetsActivity).apply {
                addView(add, FrameLayout.LayoutParams(-1, -2))
                addView(ImageView(this@MyWidgetsActivity).apply {
                    tag = "add-widget-icon"
                    setImageDrawable(getDrawable(R.drawable.ic_add)?.mutate()?.apply {
                        setTint(palette.onAccent)
                    })
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(24), dp(24),
                    Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = dp(16) })
            }, LinearLayout.LayoutParams(-1, -2))
        }
        parent.addView(footer, LinearLayout.LayoutParams(-1, -2))
    }

    override fun onContentVisible() {
        val updated = placedItems()
        items = updated
        (list?.adapter as? WidgetAdapter)?.notifyDataSetChanged()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("widgets_position", list?.firstVisiblePosition ?: 0)
        out.putInt("widgets_top", list?.getChildAt(0)?.top ?: 0)
        super.onSaveInstanceState(out)
    }

    private fun placedItems(): List<Item> {
        val imported = ImportedWidgetConfigurations.all(this)
        return listOf(false, true).flatMap { world ->
            val bound = BaseWidgetProvider.boundIds(this, world)
            val placed = WidgetInstances.manageableIds(this, bound, world).mapIndexed { index, id ->
                Item(id, world, index + 1)
            }
            placed + imported.filter { it.world == world }.mapIndexed { index, value ->
                Item(0, world, placed.size + index + 1, value)
            }
        }
    }

    private fun chooseType() {
        startActivity(Intent(this, WidgetTypeActivity::class.java))
    }

    private inner class WidgetAdapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int): Long = items[position].let {
            it.imported?.let { value -> java.util.UUID.fromString(value.token).mostSignificantBits or Long.MIN_VALUE }
                ?: it.id.toLong()
        }
        override fun hasStableIds() = true

        override fun getView(position: Int, recycled: View?, parent: ViewGroup): View {
            val row = recycled as? LinearLayout ?: LinearLayout(this@MyWidgetsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(label("", 16f), LinearLayout.LayoutParams(-1, -2))
                addView(WidgetThumbnailView(this@MyWidgetsActivity),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                addView(label(tr(R.string.widget_not_placed), 13f, palette.muted),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            val item = items[position]
            val title = tr(if (item.world) R.string.widget_world_number else R.string.widget_clock_number,
                item.number)
            row.tag = "widget-row:${if (item.world) "world" else "clock"}:${item.imported?.token ?: item.id}"
            row.contentDescription = title
            row.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            row.background = AppDialogBuilder.ripple(this@MyWidgetsActivity, palette.surface, 16, false)
            (row.getChildAt(0) as TextView).text = title
            val thumbnail = row.getChildAt(1) as WidgetThumbnailView
            if (item.imported != null) thumbnail.bindConfiguration(item.imported.configuration, item.world)
            else thumbnail.bind(item.id, item.world)
            row.getChildAt(2).visibility = if (item.imported != null) View.VISIBLE else View.GONE
            return row
        }
    }
}

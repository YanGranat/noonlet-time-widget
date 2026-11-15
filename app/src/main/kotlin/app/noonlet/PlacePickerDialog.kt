package app.noonlet

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.CompletableFuture

internal class PlacePickerDialog(
    private val activity: Activity,
    private val ui: EditorUi,
) {
    private val handler = Handler(Looper.getMainLooper())
    internal var catalog: CompletableFuture<List<Place>>? = null
    private var catalogLocaleTag: String? = null

    fun prepare(): CompletableFuture<List<Place>> {
        val tag = ui.locale().toLanguageTag()
        if (catalog == null || catalogLocaleTag != tag) {
            catalogLocaleTag = tag
            catalog = CityCatalog.prepare(activity.applicationContext, ui.locale())
        }
        return requireNotNull(catalog)
    }

    fun show(includeLocal: Boolean, selectedIds: Set<String>, onChoice: (String) -> Unit) {
        val catalog = prepare()
        val container = ui.vertical()
        container.setPadding(0, ui.dp(8f), 0, 0)
        val search = EditText(activity)
        search.tag = "place-search"
        search.setTextColor(ui.palette.text)
        search.setHintTextColor(ui.palette.muted)
        search.textSize = 16f
        search.setSingleLine(true)
        search.hint = ui.tr(R.string.city_country_or_time_zone)
        container.addView(search, LinearLayout.LayoutParams(-1, -2).apply {
            marginStart = ui.dp(16f)
            marginEnd = ui.dp(16f)
        })
        container.addView(View(activity).apply {
            tag = "place-search-divider"
            setBackgroundColor(ui.palette.divider)
        }, LinearLayout.LayoutParams(-1, ui.dp(1f)).apply { topMargin = ui.dp(8f) })
        val results = ListView(activity)
        results.setBackgroundColor(Color.TRANSPARENT)
        results.cacheColorHint = Color.TRANSPARENT
        val filtered = mutableListOf<Place?>()
        lateinit var dialog: AlertDialog
        fun selectPlace(city: Place?) {
            if (!dialog.isShowing) return
            (activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(search.windowToken, 0)
            dialog.dismiss()
            onChoice(city?.id.orEmpty())
        }
        val adapter =
            object : BaseAdapter() {
                override fun getCount(): Int {
                    return filtered.size
                }

                override fun getItem(position: Int): Any? {
                    return filtered.get(position)
                }

                override fun getItemId(position: Int): Long {
                    return position.toLong()
                }

                override fun getView(
                    position: Int,
                    old: View?,
                    parent: android.view.ViewGroup,
                ): View {
                    val line: LinearLayout
                    if (old is LinearLayout) line = old
                    else {
                        line = object : LinearLayout(activity) {
                            override fun getAccessibilityClassName() = android.widget.Button::class.java.name
                        }.apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            isFocusable = false
                        }
                        val labels = ui.vertical()
                        labels.addView(ui.text("", 16f, ui.palette.text))
                        labels.addView(ui.text("", 12f, ui.palette.muted))
                        line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                        line.addView(ImageView(activity).apply {
                            tag = "place-current-indicator"
                            setImageDrawable(AppDialogBuilder.selectionCheck(activity))
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        }, LinearLayout.LayoutParams(ui.dp(24f), ui.dp(24f)).apply { marginStart = ui.dp(12f) })
                        line.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        for (index in 0 until line.childCount)
                            line.getChildAt(index).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    }
                    val city = filtered.get(position)
                    line.tag = "place-choice:${city?.id.orEmpty()}"
                    AppDialogBuilder.styleChoiceRow(line, position == count - 1)
                    val name = if (city == null) ui.tr(R.string.device_time)
                        else CityCatalog.name(activity, city.id, ui.locale())
                    val zone = if (city == null) ZoneId.systemDefault() else ZoneId.of(city.zoneId)
                    val offset = "UTC" + ZonedDateTime.now(zone).offset.id.replace("Z", "+00:00")
                    val detail = if (city != null && city.country) ui.tr(R.string.country_region) + " · " + offset
                        else offset
                    val labels = line.getChildAt(0) as LinearLayout
                    (labels.getChildAt(0) as TextView).text = name
                    (labels.getChildAt(1) as TextView).text = detail
                    val selected = selectedIds.any {
                        CityCatalog.selectionKey(it) == CityCatalog.selectionKey(city?.id.orEmpty())
                    }
                    line.findViewWithTag<View>("place-current-indicator").visibility =
                        if (selected) View.VISIBLE else View.INVISIBLE
                    line.accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View,
                            info: android.view.accessibility.AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.isSelected = selected
                        }
                    }
                    line.contentDescription = "$name, $detail"
                    line.setOnClickListener { selectPlace(city) }
                    return line
                }
            }
        results.setAdapter(adapter)
        val empty = ui.text(ui.tr(R.string.no_places_found), 15f, ui.palette.muted)
        empty.gravity = Gravity.CENTER
        empty.setPadding(ui.dp(24f), ui.dp(16f), ui.dp(24f), ui.dp(18f))
        empty.minimumHeight = ui.dp(72f)
        val listFrame = object : FrameLayout(activity) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED)
                    ui.dp(320f) else minOf(ui.dp(320f), MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
            }
        }
        listFrame.addView(results, FrameLayout.LayoutParams(-1, -2))
        listFrame.addView(empty, FrameLayout.LayoutParams(-1, -2))
        results.emptyView = empty
        container.addView(listFrame, LinearLayout.LayoutParams(-1, -2))
        dialog =
            AppDialogBuilder(activity)
                .setTitle(ui.tr(R.string.choose_a_place))
                .setView(container)
                .setNegativeButton(ui.tr(R.string.cancel), null)
                .create()
        results.setOnItemClickListener { _, _, position, _ ->
            if (position in filtered.indices) selectPlace(filtered[position])
        }
        val populate = Runnable {
            filtered.clear()
            if (!catalog.isDone) {
                empty.text = ui.tr(R.string.loading_places)
                adapter.notifyDataSetChanged()
                return@Runnable
            }
            if (catalog.isCompletedExceptionally) {
                empty.setText(
                    ui.tr(R.string.the_place_list_could_not_be_loaded),
                )
                adapter.notifyDataSetChanged()
                return@Runnable
            }
            empty.text = ui.tr(R.string.no_places_found)
            val query = search.text.toString().trim { it <= ' ' }
            if (includeLocal && query.isEmpty()) filtered.add(null)
            filtered.addAll(CityCatalog.search(activity.applicationContext, query, ui.locale()))
            adapter.notifyDataSetChanged()
        }
        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
                    populate.run()
                }

                override fun afterTextChanged(value: Editable) {}
            },
        )
        val waiting = !catalog.isDone
        dialog.show()
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
        populate.run()
        if (waiting)
            catalog.whenComplete(
                { places, failure ->
                    handler.post(
                        {
                            if (!activity.isDestroyed && !activity.isFinishing && dialog.isShowing) populate.run()
                        },
                    )
                },
            )
    }

}

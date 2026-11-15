package app.noonlet

import android.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.CompletableFuture
import kotlin.math.roundToInt

internal object WeatherLocationDialog {
    fun show(
        context: Context,
        locale: Locale,
        useDevice: Boolean,
        current: WeatherPlace?,
        searchPlaces: (String, String) -> CompletableFuture<List<WeatherPlace>> =
            WeatherRepository::search,
        onCancel: (() -> Unit)? = null,
        onChoice: (WeatherPlace?) -> Unit,
    ): AlertDialog {
        val palette = AppTheme.palette(context)
        fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
        fun tr(id: Int) = AppLocales.text(context, locale, id)
        fun TextView.setLabel(value: String, size: Float, color: Int) =
            apply {
                text = value
                textSize = size
                setTextColor(color)
                includeFontPadding = false
            }
        fun label(value: String, size: Float, color: Int) = TextView(context).setLabel(value, size, color)
        fun icon(resource: Int, color: Int) =
            ImageView(context).apply {
                setImageResource(resource)
                setColorFilter(color)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        fun indicator(checked: Boolean) =
            ImageView(context).apply {
                setImageDrawable(AppDialogBuilder.selectionCheck(context))
                visibility = if (checked) View.VISIBLE else View.INVISIBLE
                isSelected = checked
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        val handler = Handler(Looper.getMainLooper())
        val body =
            BoundedBody(context).apply {
                orientation = LinearLayout.VERTICAL
                isFocusableInTouchMode = true
            }
        val choices =
            LinearLayout(context).apply {
                tag = "weather-location-choices"
                orientation = LinearLayout.VERTICAL
            }
        val automatic =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                AppDialogBuilder.styleChoiceRow(this, useDevice || current == null)
                tag = "weather-location-device"
                isFocusable = true
                isSelected = useDevice
                background = AppDialogBuilder.choiceSurface(context,
                    divider = !useDevice && current != null)
                addView(
                    icon(R.drawable.ic_device_location, palette.muted),
                    LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(12) },
                )
                val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                labels.addView(
                    label(tr(R.string.weather_device_location), 16f, palette.text),
                )
                labels.addView(
                    label(tr(R.string.weather_automatic), 13f, palette.muted),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
                )
                addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                addView(
                    indicator(useDevice).apply {
                        tag = "weather-location-device-indicator"
                    },
                    LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) },
                )
                contentDescription =
                    tr(R.string.weather_device_location) + ", " + tr(R.string.weather_automatic)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                accessibilityDelegate =
                    object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(
                            host: View,
                            info: AccessibilityNodeInfo,
                        ) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = "android.widget.RadioButton"
                            info.isCheckable = true
                            info.isChecked = useDevice
                        }
                    }
            }
        choices.addView(automatic, LinearLayout.LayoutParams(-1, -2))
        var selectedPlace: View? = null
        if (!useDevice && current != null) {
            val selected =
                LinearLayout(context).apply {
                    tag = "weather-location-selected"
                    gravity = Gravity.CENTER_VERTICAL
                    isSelected = true
                    isFocusable = true
                    AppDialogBuilder.styleChoiceRow(this, true)
                    val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                    labels.addView(
                        label(tr(R.string.weather_selected_place), 13f, palette.muted),
                    )
                    labels.addView(
                        label(current.name, 16f, palette.text),
                        LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
                    )
                    addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(
                        indicator(true).apply { tag = "weather-location-current-indicator" },
                        LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) },
                    )
                    contentDescription = tr(R.string.weather_selected_place) + ", " + current.name
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    accessibilityDelegate = object : View.AccessibilityDelegate() {
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = "android.widget.RadioButton"
                            info.isCheckable = true
                            info.isChecked = true
                        }
                    }
                }
            selectedPlace = selected
            choices.addView(selected, LinearLayout.LayoutParams(-1, -2))
        }
        val search =
            EditText(context).apply {
                tag = "weather-location-search"
                setSingleLine(true)
                hint = tr(R.string.weather_search_location)
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                setCompoundDrawablesRelativeWithIntrinsicBounds(
                    context.getDrawable(R.drawable.ic_search)?.apply {
                        setTint(palette.muted)
                    },
                    null,
                    null,
                    null,
                )
                compoundDrawablePadding = dp(10)
            }
        val searchArea = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                automatic.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
                super.onMeasure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(automatic.measuredHeight, MeasureSpec.EXACTLY))
            }
        }.apply {
            tag = "weather-location-search-area"
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(search, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        }
        body.addView(searchArea, LinearLayout.LayoutParams(-1, -2))
        body.addView(View(context).apply {
            tag = "place-search-divider"
            setBackgroundColor(palette.divider)
        }, LinearLayout.LayoutParams(-1, dp(1)))
        val results =
            LinearLayout(context).apply {
                tag = "weather-location-results"
                orientation = LinearLayout.VERTICAL
            }

        val status =
            label("", 14f, palette.muted).apply {
                tag = "weather-location-status"
                setPadding(dp(24), dp(16), dp(24), dp(18))
                visibility = View.GONE
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        val scrollContent =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(choices)
                addView(results)
                addView(status)
            }
        val viewport =
            ContentViewport(context).apply {
                tag = "weather-location-viewport"
                isVerticalScrollBarEnabled = false
                addView(scrollContent, FrameLayout.LayoutParams(-1, -2))
            }
        body.addView(viewport, LinearLayout.LayoutParams(-1, -2))
        val dialog = AppDialogBuilder(context).setTitle(tr(R.string.weather_location))
            .setHeaderSeparated(true).setView(body)
            .setNegativeButton(tr(R.string.cancel), null).create()
        var generation = 0
        var pending: Runnable? = null
        var request: CompletableFuture<List<WeatherPlace>>? = null
        var chosen = false

        fun choose(place: WeatherPlace?) {
            if (!dialog.isShowing) return
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(search.windowToken, 0)
            chosen = true
            dialog.dismiss()
            onChoice(place)
        }
        automatic.setOnClickListener { choose(null) }
        selectedPlace?.setOnClickListener { choose(current) }
        fun showStatus(message: String) {
            status.text = message
            status.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        }
        fun submit() {
            val token = ++generation
            pending?.let(handler::removeCallbacks)
            request?.cancel(false)
            request = null
            val query = search.text.toString().trim()
            results.removeAllViews()
            choices.visibility = if (query.isEmpty()) View.VISIBLE else View.GONE
            viewport.scrollTo(0, 0)
            if (query.length < 2) {
                showStatus(if (query.isEmpty()) "" else tr(R.string.weather_search_prompt))
                return
            }
            showStatus(tr(R.string.weather_loading_short))
            val task = Runnable {
                if (!dialog.isShowing || token != generation) return@Runnable
                val future =
                    try {
                        searchPlaces(query, locale.language)
                    } catch (error: RuntimeException) {
                        CompletableFuture<List<WeatherPlace>>().apply {
                            completeExceptionally(error)
                        }
                    }
                request = future
                future.whenComplete { found, failure ->
                    handler.post {
                        if (!dialog.isShowing || token != generation) return@post
                        results.removeAllViews()
                        val places = if (failure == null) found.orEmpty() else emptyList()
                        for ((index, place) in places.withIndex()) {
                            val row =
                                object : TextView(context) {
                                    override fun getAccessibilityClassName() = android.widget.Button::class.java.name
                                }.setLabel(place.name, 16f, palette.text).apply {
                                    tag = "weather-location-result:$index"
                                    gravity = Gravity.CENTER_VERTICAL
                                    AppDialogBuilder.styleChoiceRow(this, index == places.lastIndex)
                                    isFocusable = true
                                    background = AppDialogBuilder.choiceSurface(context,
                                        divider = index < places.lastIndex)
                                    isSelected = !useDevice && current?.key == place.key
                                    compoundDrawablePadding = dp(12)
                                    setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                                        if (isSelected) AppDialogBuilder.selectionCheck(context) else null, null)
                                    setOnClickListener { choose(place) }
                                }
                            results.addView(row, LinearLayout.LayoutParams(-1, -2))
                        }
                        showStatus(
                            when {
                                failure != null -> tr(R.string.weather_search_failed)
                                places.isEmpty() -> tr(R.string.no_places_found)
                                else -> ""
                            },
                        )
                    }
                }
            }
            pending = task
            handler.postDelayed(task, 250)
        }
        search.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) =
                    submit()

                override fun afterTextChanged(s: Editable?) {}
            },
        )
        search.setOnEditorActionListener { _, action, _ ->
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) false
            else {
                submit()
                pending?.let { task ->
                    handler.removeCallbacks(task)
                    task.run()
                }
                true
            }
        }
        dialog.setOnDismissListener {
            generation++
            pending?.let(handler::removeCallbacks)
            request?.cancel(false)
            if (!chosen) onCancel?.invoke()
        }
        dialog.show()
        body.requestFocus()
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
        )
        return dialog
    }

    private class BoundedBody(context: Context) : LinearLayout(context) {
        private var availableHeight = 0

        init {
            tag = "weather-location-body"
            viewTreeObserver.addOnGlobalLayoutListener {
                val next = visibleHeight()
                if (next != availableHeight) {
                    availableHeight = next
                    requestLayout()
                }
            }
        }

        private fun visibleHeight(): Int {
            val frame = Rect()
            getWindowVisibleDisplayFrame(frame)
            val height = frame.height().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
            return maxOf(1, height - (32 * resources.displayMetrics.density).roundToInt())
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = visibleHeight()
            val limit =
                if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
                    available
                else minOf(available, View.MeasureSpec.getSize(heightMeasureSpec))
            val viewport = findViewWithTag<ContentViewport>("weather-location-viewport")
            if (viewport != null) {
                var fixed = paddingTop + paddingBottom
                for (index in 0 until childCount) {
                    val child = getChildAt(index)
                    val params = child.layoutParams as LinearLayout.LayoutParams
                    fixed += params.topMargin + params.bottomMargin
                    if (child !== viewport && child.visibility != View.GONE) {
                        measureChildWithMargins(
                            child,
                            widthMeasureSpec,
                            0,
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                            0,
                        )
                        fixed += child.measuredHeight
                    }
                }
                viewport.maximumHeight = maxOf(0, limit - fixed)
            }
            super.onMeasure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST),
            )
        }
    }

    private class ContentViewport(context: Context) : ScrollView(context) {
        var maximumHeight = Int.MAX_VALUE

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val limit =
                if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED)
                    maximumHeight
                else minOf(maximumHeight, View.MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(maxOf(0, limit), View.MeasureSpec.AT_MOST),
            )
        }
    }
}

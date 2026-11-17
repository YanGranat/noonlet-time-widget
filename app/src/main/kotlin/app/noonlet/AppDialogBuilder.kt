package app.noonlet

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.shapes.RoundRectShape
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AbsListView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import java.util.IdentityHashMap
import kotlin.math.roundToInt

interface EditorDialogOwner {
    fun registerEditorDialog(dialog: Dialog)
}

internal class AppDialogBuilder(context: Context) :
    AlertDialog.Builder(AppTheme.themedContext(context), AppTheme.dialogStyle(context)) {
    private val palette = AppTheme.palette(context)
    private var title: CharSequence? = null
    private var customBody: View? = null
    private var wrappedBody = false
    private var headerSeparated: Boolean? = null
    private val flexibleHeights = IdentityHashMap<View, Int>()
    private val owner = context as? EditorDialogOwner

    override fun setTitle(value: CharSequence?): AppDialogBuilder {
        title = value
        super.setTitle(value)
        return this
    }

    override fun setTitle(resource: Int) = setTitle(context.getText(resource))

    fun setHeaderSeparated(value: Boolean): AppDialogBuilder {
        headerSeparated = value
        return this
    }

    override fun setSingleChoiceItems(
        items: Array<out CharSequence>,
        checked: Int,
        listener: DialogInterface.OnClickListener?,
    ) = setChoiceItems(items, checked, emptySet(), listener)

    fun setChoiceItems(
        items: Array<out CharSequence>,
        checked: Int,
        navigationRows: Set<Int>,
        listener: DialogInterface.OnClickListener?,
    ): AppDialogBuilder {
        super.setSingleChoiceItems(
            ChoiceAdapter(context, items, true, checked, navigationRows = navigationRows),
            checked,
            visibleListener(listener),
        )
        return this
    }

    override fun setSingleChoiceItems(
        resource: Int,
        checked: Int,
        listener: DialogInterface.OnClickListener?,
    ) = setSingleChoiceItems(context.resources.getTextArray(resource), checked, listener)

    override fun setItems(
        items: Array<out CharSequence>,
        listener: DialogInterface.OnClickListener?,
    ): AppDialogBuilder {
        setAdapter(ChoiceAdapter(context, items, false, -1, navigation = true), listener)
        return this
    }

    fun setActionItems(
        items: Array<out CharSequence>,
        icons: List<Int>,
        listener: DialogInterface.OnClickListener?,
    ): AppDialogBuilder {
        setAdapter(ChoiceAdapter(context, items, false, -1, icons = icons), listener)
        return this
    }

    override fun setAdapter(adapter: ListAdapter?, listener: DialogInterface.OnClickListener?): AppDialogBuilder {
        super.setAdapter(adapter, visibleListener(listener))
        return this
    }

    override fun setItems(resource: Int, listener: DialogInterface.OnClickListener?) =
        setItems(context.resources.getTextArray(resource), listener)

    override fun setView(view: View): AppDialogBuilder {
        styleControls(view)
        customBody =
            if (
                find(view, ScrollView::class.java) == null &&
                    find(view, ListView::class.java) == null
            ) {
                wrappedBody = true
                ScrollView(context).apply {
                    isVerticalScrollBarEnabled = false
                    addView(view, FrameLayout.LayoutParams(-1, -2))
                }
            } else view
        super.setView(customBody)
        return this
    }

    override fun create(): AlertDialog {
        var close: ImageButton? = null
        title
            ?.takeIf { it.isNotEmpty() }
            ?.let { value ->
                val heading =
                    TextView(context).apply {
                        tag = "dialog-title"
                        text = value
                        setTextColor(palette.text)
                        textSize = 16f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        includeFontPadding = false
                        if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
                    }
                val header = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    tag = "dialog-header"
                    setBackgroundColor(palette.sectionHeader)
                }
                val toolbar = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(context, 52)
                    setPadding(dp(context, 24), dp(context, 2), dp(context, 12), dp(context, 2))
                }
                toolbar.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
                close = ImageButton(context).apply {
                    tag = "dialog-close"
                    contentDescription = AppLocales.text(context, R.string.close)
                    setImageDrawable(context.getDrawable(R.drawable.ic_close)?.mutate()?.apply {
                        setTint(palette.muted)
                    })
                    setPadding(dp(context, 9), dp(context, 9), dp(context, 9), dp(context, 9))
                    background = ripple(context, Color.TRANSPARENT, 24, false)
                }
                toolbar.addView(close, LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)).apply {
                    marginStart = dp(context, 8)
                })
                header.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
                header.addView(View(context).apply {
                    tag = "dialog-header-divider"
                    setBackgroundColor(palette.divider)
                },
                    LinearLayout.LayoutParams(-1, dp(context, 1)))
                super.setCustomTitle(header)
            }
        val dialog = super.create()
        close?.setOnClickListener { dialog.dismiss() }
        val window = requireNotNull(dialog.window)
        val decor = window.decorView
        window.setBackgroundDrawable(shape(context, palette.surface, 28))
        window.setDimAmount(.54f)
        AppTheme.protectColors(decor)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        decor.clipToOutline = true
        var styled = false
        val previous = intArrayOf(-1, -1, -1)
        val update = Runnable {
            if (!styled && dialog.getButton(DialogInterface.BUTTON_POSITIVE) != null) {
                styled = true
                finishAppearance(dialog)
                previous.fill(-1)
            }
            constrain(dialog, previous)
        }
        decor.addOnAttachStateChangeListener(
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = update.run()

                override fun onViewDetachedFromWindow(view: View) = Unit
            },
        )
        decor.viewTreeObserver.addOnGlobalLayoutListener { update.run() }
        owner?.registerEditorDialog(dialog)
        return dialog
    }

    override fun show(): AlertDialog = create().also { it.show() }

    private fun finishAppearance(dialog: AlertDialog) {
        val window = requireNotNull(dialog.window)
        val separated = headerSeparated ?: (dialog.listView != null ||
            customBody?.let { find(it, ListView::class.java) } != null)
        window.decorView.findViewWithTag<View>("dialog-header")?.apply {
            setBackgroundColor(if (separated) palette.sectionHeader else palette.surface)
            findViewWithTag<View>("dialog-header-divider").visibility =
                if (separated) View.VISIBLE else View.GONE
            findViewWithTag<TextView>("dialog-title").textSize = if (separated) 16f else 20f
            findViewWithTag<View>("dialog-close").apply {
                val footerCancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)?.visibility == View.VISIBLE
                visibility = if (!separated && footerCancel) View.GONE else View.VISIBLE
                val toolbar = parent as View
                toolbar.setPadding(toolbar.paddingLeft, dp(context, if (separated) 2 else 12),
                    dp(context, if (visibility == View.GONE) 24 else 12),
                    dp(context, if (separated) 2 else 8))
            }
        }
        val spacerId = context.resources.getIdentifier("titleDividerNoCustom", "id", "android")
        if (spacerId != 0) window.findViewById<View>(spacerId)?.visibility = View.GONE
        for (name in
            arrayOf(
                "parentPanel",
                "topPanel",
                "contentPanel",
                "customPanel",
                "buttonPanel",
                "custom",
            )) {
            val id = context.resources.getIdentifier(name, "id", "android")
            val panel = if (id == 0) null else window.findViewById<View>(id)
            panel?.apply {
                setBackgroundColor(Color.TRANSPARENT)
                setPadding(0, 0, 0, 0)
            }
        }
        window.findViewById<TextView>(android.R.id.message)?.apply {
            setTextColor(palette.text)
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setLineSpacing(dp(context, 3).toFloat(), 1f)
            setPadding(dp(context, 24), dp(context, 20), dp(context, 24), dp(context, 8))
        }
        dialog.listView?.apply {
            setBackgroundColor(Color.TRANSPARENT)
            divider = ColorDrawable(palette.divider)
            dividerHeight = dp(context, 1)
            selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        rebuildButtons(dialog)
        customBody?.let(::styleControls)
        if (listOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE,
                DialogInterface.BUTTON_NEUTRAL).any { dialog.getButton(it)?.visibility == View.VISIBLE }) {
            dialog.listView?.let(::decorateListEnd)
            customBody?.let { find(it, ListView::class.java) }?.let(::decorateListEnd)
        }
    }

    private fun decorateListEnd(list: ListView) {
        val dividerHeight = list.dividerHeight
        list.divider = object : ColorDrawable(palette.divider) {
            override fun draw(canvas: Canvas) {
                val last = list.getChildAt(list.childCount - 1)
                if (last == null || list.lastVisiblePosition != list.count - 1 || bounds.top < last.bottom)
                    super.draw(canvas)
            }
        }
        list.dividerHeight = dividerHeight
        list.foregroundGravity = Gravity.FILL
        list.foreground = object : ColorDrawable(palette.divider) {
            override fun draw(canvas: Canvas) {
                val last = list.getChildAt(list.childCount - 1) ?: return
                if (list.lastVisiblePosition != list.count - 1 || !list.adapter.isEnabled(list.count - 1)) return
                val checkpoint = canvas.save()
                canvas.clipRect(0, last.bottom - dp(context, 1), list.width, last.bottom)
                super.draw(canvas)
                canvas.restoreToCount(checkpoint)
            }
        }
    }

    private fun rebuildButtons(dialog: AlertDialog) {
        val positive = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
        val negative = dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
        val neutral = dialog.getButton(DialogInterface.BUTTON_NEUTRAL)
        val visible =
            listOfNotNull(neutral, negative, positive).filter { it.visibility == View.VISIBLE }
        if (visible.isEmpty()) return
        val panel = visible.first().parent as ViewGroup
        for (button in visible) (button.parent as? ViewGroup)?.removeView(button)
        panel.removeAllViews()
        panel.setPadding(dp(context, 24), dp(context, 12), dp(context, 24), dp(context, 20))
        val footer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(footer, ViewGroup.LayoutParams(-1, -2))
        if (visible.size == 3) {
            val primary = requireNotNull(positive)
            styleButton(primary, true)
            footer.addView(primary, LinearLayout.LayoutParams(-1, dp(context, 48)))
            val row = buttonRow()
            addButton(row, requireNotNull(neutral), false, false)
            addButton(row, requireNotNull(negative), false, true)
            footer.addView(
                row,
                LinearLayout.LayoutParams(-1, dp(context, 48)).apply { topMargin = dp(context, 8) },
            )
        } else {
            val row = buttonRow()
            visible.forEachIndexed { index, button ->
                addButton(row, button, button === positive, index > 0)
            }
            footer.addView(row, LinearLayout.LayoutParams(-1, dp(context, 48)))
        }
    }

    private fun buttonRow() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    private fun addButton(row: LinearLayout, button: Button, primary: Boolean, margin: Boolean) {
        styleButton(button, primary)
        val params = LinearLayout.LayoutParams(0, -1, 1f)
        if (margin) params.leftMargin = dp(context, 12)
        row.addView(button, params)
    }

    private fun styleButton(button: Button, primary: Boolean) =
        with(button) {
            isAllCaps = false
            textSize = 15f
            setTextColor(if (primary) palette.onAccent else palette.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minHeight = 0
            minimumHeight = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            stateListAnimator = null
            background = ripple(context, if (primary) palette.accent else palette.secondary, 14, primary)
        }

    private fun constrain(dialog: AlertDialog, previous: IntArray) {
        val window = requireNotNull(dialog.window)
        val visible = Rect()
        window.decorView.getWindowVisibleDisplayFrame(visible)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight =
            visible.height().takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        val width = minOf(dp(context, 400), maxOf(dp(context, 240), screenWidth - dp(context, 40)))
        val listWidth = dialog.listView?.width ?: 0
        if (previous[0] == width && previous[1] == screenHeight && previous[2] == listWidth) return
        previous[0] = width
        previous[1] = screenHeight
        previous[2] = listWidth
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        if (customBody == null) dialog.listView?.let { list ->
            val header = window.decorView.findViewWithTag<View>("dialog-header")
            header?.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val footer = intArrayOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE,
                DialogInterface.BUTTON_NEUTRAL).any { dialog.getButton(it)?.visibility == View.VISIBLE }
            val available = maxOf(dp(context, 56), screenHeight - (header?.measuredHeight ?: 0) -
                dp(context, if (footer) 112 else 32))
            var content = list.paddingTop + list.paddingBottom
            for (position in 0 until list.adapter.count) {
                val row = list.adapter.getView(position, null, list)
                row.layoutDirection = context.resources.configuration.layoutDirection
                row.measure(View.MeasureSpec.makeMeasureSpec(width - list.paddingLeft - list.paddingRight,
                    View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                content += row.measuredHeight + if (position == 0) 0 else list.dividerHeight
                if (content >= available) break
            }
            setHeight(list, minOf(content, available))
        }
        val body = customBody ?: return
        val buttons =
            intArrayOf(
                    DialogInterface.BUTTON_POSITIVE,
                    DialogInterface.BUTTON_NEGATIVE,
                    DialogInterface.BUTTON_NEUTRAL,
                )
                .count { dialog.getButton(it)?.visibility == View.VISIBLE }
        val buttonSpace =
            when {
                buttons == 3 -> 132
                buttons > 0 -> 80
                else -> 12
            }
        val maxBody = maxOf(dp(context, 56), screenHeight - dp(context, 32 + 76 + buttonSpace))
        if (wrappedBody && body is ScrollView) {
            val child = body.getChildAt(0)
            child.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            setHeight(body, minOf(maxBody, child.measuredHeight))
        } else {
            val flexible = flexibleTarget(body) ?: return
            val original = flexibleHeights[flexible] ?: flexible.layoutParams.height
            if (original > 0) {
                flexibleHeights[flexible] = original
                setHeight(flexible, original)
                body.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                val otherHeight = maxOf(0, body.measuredHeight - original)
                setHeight(flexible, maxOf(dp(context, 48), minOf(original, maxBody - otherHeight)))
            }
        }
    }

    internal class ChoiceAdapter(
        context: Context,
        items: Array<out CharSequence>,
        private val choices: Boolean,
        private var selected: Int,
        private val navigationRows: Set<Int> = emptySet(),
        private val navigation: Boolean = false,
        private val icons: List<Int> = emptyList(),
    ) : ArrayAdapter<CharSequence>(context, 0, items.toMutableList()) {
        private var selectable = true
        private val palette = AppTheme.palette(context)
        private val availableText = ColorStateList.valueOf(palette.text)
        private val unavailableText = ColorStateList.valueOf(palette.muted)

        fun replaceLabels(labels: List<CharSequence>, enabled: Boolean, checked: Int = -1) {
            selectable = enabled
            selected = checked
            setNotifyOnChange(false)
            clear()
            addAll(labels)
            notifyDataSetChanged()
        }

        override fun areAllItemsEnabled() = selectable

        override fun isEnabled(position: Int) = selectable

        override fun getView(position: Int, old: View?, parent: ViewGroup): View {
            val row =
                old as? CheckedTextView
                    ?: (LayoutInflater.from(context)
                            .inflate(R.layout.dialog_choice_row, parent, false) as CheckedTextView)
                        .apply {
                            textSize = 16f
                            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                            setTextColor(availableText)
                            includeFontPadding = false
                            gravity = Gravity.CENTER_VERTICAL
                            layoutParams = AbsListView.LayoutParams(-1, -2)
                            checkMarkDrawable = null
                            compoundDrawablePadding = dp(context, 12)
                        }
            row.text = getItem(position)
            styleChoiceRow(row, position == count - 1)
            row.isChecked = choices && position == selected
            row.isEnabled = selectable
            row.setTextColor(if (selectable) availableText else unavailableText)
            val leading = when {
                position < icons.size -> context.getDrawable(icons[position])?.mutate()?.apply {
                    setTint(palette.text)
                }
                else -> null
            }
            val opensNext = selectable && (navigation || position in navigationRows)
            val arrow = if (opensNext) context.getDrawable(R.drawable.ic_chevron_right)?.mutate()?.apply {
                setTint(palette.muted)
            } else null
            val trailing = when {
                choices && selectable && row.isChecked && arrow != null -> LayerDrawable(arrayOf(
                    choiceIndicator(context, true), arrow)).apply {
                    setLayerInsetRelative(0, 0, 0, dp(context, 32), 0)
                    setLayerInsetRelative(1, dp(context, 32), 0, 0, 0)
                }
                arrow != null -> arrow
                choices && selectable -> choiceIndicator(context, row.isChecked)
                else -> null
            }
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(leading, null, trailing, null)
            row.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View,
                    info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = if (choices && selectable) "android.widget.CheckedTextView" else "android.widget.Button"
                    info.isCheckable = choices && selectable
                    info.isChecked = row.isChecked
                }
            }
            return row
        }
    }

    companion object {
        fun selectionCheck(context: Context): Drawable =
            requireNotNull(context.getDrawable(R.drawable.ic_check)).mutate().apply {
                setTint(AppTheme.palette(context).accent)
            }

        fun styleChoiceRow(row: View, last: Boolean) {
            val extra = if (last) 2 else 0
            row.minimumHeight = dp(row.context, 52 + extra)
            row.setPadding(dp(row.context, 24), dp(row.context, 8), dp(row.context, 24),
                dp(row.context, 8 + extra))
            row.background = choiceSurface(row.context)
        }

        fun choiceIndicator(context: Context, checked: Boolean): Drawable {
            val palette = AppTheme.palette(context)
            val circle = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (checked) palette.accent else Color.TRANSPARENT)
                if (!checked) setStroke(dp(context, 1), palette.muted)
                setSize(dp(context, 22), dp(context, 22))
            }
            if (!checked) return InsetDrawable(circle, dp(context, 1))
            val check = requireNotNull(context.getDrawable(R.drawable.ic_check)).mutate().apply {
                setTint(palette.onAccent)
            }
            val selected = LayerDrawable(arrayOf(circle, check)).apply {
                val inset = dp(context, 3)
                setLayerInset(1, inset, inset, inset, inset)
                setLayerSize(1, dp(context, 16), dp(context, 16))
                setLayerGravity(1, Gravity.CENTER)
            }
            return InsetDrawable(selected, dp(context, 1))
        }

        fun choiceSurface(context: Context, divider: Boolean = false): Drawable {
            val palette = AppTheme.palette(context)
            val focused = shape(context, palette.secondary, 0)
            val surface = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(), shape(context, Color.TRANSPARENT, 0))
            }
            val feedback = ColorStateList(arrayOf(
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_hovered), intArrayOf()),
                intArrayOf(palette.ripple, Color.TRANSPARENT, palette.hoverRipple, Color.TRANSPARENT))
            val feedbackSurface = RippleDrawable(feedback, surface, shape(context, Color.WHITE, 0))
            if (!divider) return feedbackSurface
            return LayerDrawable(arrayOf(feedbackSurface, ColorDrawable(palette.divider))).apply {
                setLayerGravity(1, Gravity.BOTTOM)
                setLayerHeight(1, dp(context, 1))
                setLayerInsetRelative(1, 0, 0, 0, 0)
            }
        }

        private fun visibleListener(
            listener: DialogInterface.OnClickListener?,
        ): DialogInterface.OnClickListener? = listener?.let { target ->
            DialogInterface.OnClickListener { dialog, which ->
                if (dialog !is Dialog || dialog.isShowing) target.onClick(dialog, which)
            }
        }

        private fun setHeight(view: View, height: Int) {
            val params = view.layoutParams ?: return
            if (params.height != height) {
                params.height = maxOf(1, height)
                view.layoutParams = params
            }
        }

        private fun flexibleTarget(root: View): View? {
            val scroll = find(root, ScrollView::class.java)
            if (scroll != null && (scroll.layoutParams?.height ?: 0) > 0) return scroll
            var candidate: View? = find(root, ListView::class.java)
            while (candidate != null && candidate !== root) {
                if ((candidate.layoutParams?.height ?: 0) > 0) return candidate
                candidate = candidate.parent as? View
            }
            return null
        }

        @JvmStatic
        fun styleControls(root: View) {
            when (root) {
                is EditText -> styleInput(root)
                is SeekBar -> styleSlider(root)
                is Switch -> styleSwitch(root)
                is ListView ->
                    root.apply {
                        divider = ColorDrawable(AppTheme.palette(context).divider)
                        dividerHeight = dp(context, 1)
                        selector = ColorDrawable(Color.TRANSPARENT)
                        isVerticalScrollBarEnabled = false
                    }
            }
            if (root is ViewGroup)
                for (index in 0 until root.childCount) styleControls(root.getChildAt(index))
        }

        @JvmStatic
        fun styleInput(input: EditText) =
            with(input) {
                val palette = AppTheme.palette(context)
                textSize = 15f
                setTextColor(palette.text)
                setHintTextColor(palette.muted)
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                highlightColor = (palette.accent and 0x00ffffff) or 0x55000000
                minimumHeight = dp(context, 48)
                setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
                background =
                    StateListDrawable().apply {
                        addState(
                            intArrayOf(android.R.attr.state_focused),
                            shape(context, palette.input, 12).apply { setStroke(dp(context, 1), palette.accent) },
                        )
                        addState(
                            intArrayOf(),
                            shape(context, palette.input, 12).apply { setStroke(dp(context, 1), palette.border) },
                        )
                    }
                if (tag == "place-search" || tag == "weather-location-search") {
                    minimumHeight = dp(context, 32)
                    setPadding(dp(context, 14), dp(context, 4), dp(context, 14), dp(context, 4))
                    background = shape(context, palette.input, 8)
                }
                if (Build.VERSION.SDK_INT >= 29)
                    textCursorDrawable =
                        shape(context, palette.accent, 1).apply {
                            setSize(dp(context, 2), dp(context, 22))
                        }
            }

        @JvmStatic
        fun styleSlider(slider: SeekBar) =
            with(slider) {
                val palette = AppTheme.palette(context)
                val base = shape(context, palette.border, 2)
                val fill = shape(context, palette.accent, 2)
                progressDrawable =
                    LayerDrawable(
                            arrayOf(
                                base,
                                ClipDrawable(fill, Gravity.LEFT, ClipDrawable.HORIZONTAL),
                            ),
                        )
                        .apply {
                            setId(0, android.R.id.background)
                            setId(1, android.R.id.progress)
                            for (index in 0..1) {
                                setLayerGravity(index, Gravity.CENTER_VERTICAL)
                                setLayerHeight(index, dp(context, 3))
                            }
                        }
                progressTintList = null
                progressBackgroundTintList = null
                thumb =
                    shape(context, palette.accent, 9).apply {
                        setSize(dp(context, 18), dp(context, 18))
                        setStroke(dp(context, 2), palette.surface)
                    }
                thumbTintList = null
                splitTrack = false
                thumbOffset = dp(context, 9)
            }

        @JvmStatic
        fun styleSwitch(toggle: Switch) =
            with(toggle) {
                val palette = AppTheme.palette(context)
                val track =
                    StateListDrawable().apply {
                        addState(
                            intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
                            switchTrack(context, palette.switchDisabledChecked),
                        )
                        addState(
                            intArrayOf(-android.R.attr.state_enabled),
                            switchTrack(context, palette.switchDisabledUnchecked),
                        )
                        addState(
                            intArrayOf(android.R.attr.state_checked),
                            switchTrack(context, palette.switchChecked),
                        )
                        addState(intArrayOf(), switchTrack(context, palette.switchUnchecked))
                    }
                val thumb =
                    StateListDrawable().apply {
                        addState(
                            intArrayOf(-android.R.attr.state_enabled),
                            switchThumb(context, palette.switchDisabledThumb),
                        )
                        addState(intArrayOf(android.R.attr.state_checked), switchThumb(context, Color.WHITE))
                        addState(intArrayOf(), switchThumb(context, palette.switchUncheckedThumb))
                    }
                buttonTintList = null
                trackTintList = null
                thumbTintList = null
                showText = false
                textOn = null
                textOff = null
                thumbTextPadding = 0
                setPadding(0, 0, 0, 0)
                switchPadding = 0
                gravity = Gravity.CENTER_VERTICAL
                trackDrawable = track
                thumbDrawable = thumb
                switchMinWidth = dp(context, 52)
                minimumWidth = dp(context, 52)
                minimumHeight = dp(context, 48)
                splitTrack = false
                background = ripple(context, Color.TRANSPARENT, 24, false)
            }

        private fun switchTrack(context: Context, color: Int): Drawable {
            val radii = FloatArray(8) { dp(context, 14).toFloat() }
            return ShapeDrawable(RoundRectShape(radii, null, null)).apply {
                paint.isAntiAlias = true
                paint.color = color
                intrinsicWidth = dp(context, 52)
                intrinsicHeight = dp(context, 28)
                val inset = dp(context, 2)
                setPadding(inset, inset, inset, inset)
            }
        }

        private fun switchThumb(context: Context, color: Int): Drawable {
            val circle =
                shape(context, color, 12).apply { setSize(dp(context, 24), dp(context, 24)) }
            // Switch uses the track height for thumb bounds; insets keep the visible knob round.
            return InsetDrawable(circle, 0, dp(context, 2), 0, dp(context, 2))
        }

        @JvmStatic
        fun ripple(context: Context, color: Int, radius: Int, primary: Boolean): RippleDrawable =
            RippleDrawable(
                ColorStateList.valueOf(if (primary) 0x20000000 else AppTheme.palette(context).ripple),
                shape(context, color, radius),
                shape(context, Color.WHITE, radius),
            )

        @JvmStatic
        fun shape(context: Context, color: Int, radius: Int) =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(context, radius).toFloat()
            }

        @JvmStatic
        fun dp(context: Context, value: Float) =
            (value * context.resources.displayMetrics.density).roundToInt()

        @JvmStatic fun dp(context: Context, value: Int) = dp(context, value.toFloat())

        private fun <T : View> find(root: View, type: Class<T>): T? {
            if (type.isInstance(root)) return type.cast(root)
            if (root is ViewGroup)
                for (index in 0 until root.childCount) {
                    find(root.getChildAt(index), type)?.let {
                        return it
                    }
                }
            return null
        }
    }
}

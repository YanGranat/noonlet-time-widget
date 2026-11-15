package app.noonlet

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

internal class EditorUi(
    val context: android.content.Context,
    private val paletteProvider: () -> AppPalette,
    private val localeProvider: () -> Locale,
) {
    val palette: AppPalette get() = paletteProvider()

    fun vertical(): LinearLayout {
        val view = LinearLayout(context)
        view.orientation = LinearLayout.VERTICAL
        return view
    }

    fun horizontal(): LinearLayout {
        val view = LinearLayout(context)
        view.orientation = LinearLayout.HORIZONTAL
        view.gravity = Gravity.CENTER_VERTICAL
        return view
    }

    fun text(value: String?, size: Float, color: Int): TextView {
        val view = TextView(context)
        view.text = value
        view.textSize = size
        view.setTextColor(color)
        view.gravity = Gravity.CENTER_VERTICAL
        view.fontFeatureSettings = "tnum"
        view.includeFontPadding = false
        return view
    }

    fun navigationIcon(resource: Int, description: String) = ImageButton(context).apply {
        setImageDrawable(context.getDrawable(resource)?.mutate()?.apply { setTint(palette.text) })
        setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        contentDescription = description
        background = AppDialogBuilder.ripple(context, Color.TRANSPARENT, 12, false)
        isFocusable = true
    }

    fun shape(color: Int, radius: Float): GradientDrawable {
        val drawable = GradientDrawable()
        drawable.setColor(color)
        drawable.cornerRadius = dp(radius).toFloat()
        return drawable
    }

    fun button(value: String, background: Int, foreground: Int): TextView {
        val view = text(value, 15f, foreground)
        view.gravity = Gravity.CENTER
        view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        view.background = AppDialogBuilder.ripple(context, background, 16, background == palette.accent)
        view.setClickable(true)
        view.isFocusable = true
        return view
    }

    fun icon(kind: AppIcons.Kind, description: String): ImageButton {
        val view = ImageButton(context)
        view.setImageDrawable(AppIcons.create(context, kind, palette.muted))
        view.imageTintList = null
        view.setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        view.contentDescription = description
        view.background = AppDialogBuilder.ripple(context, Color.TRANSPARENT, 12, false)
        view.setClickable(true)
        view.isFocusable = true
        return view
    }

    fun imageIcon(kind: AppIcons.Kind, color: Int): ImageView {
        val image = ImageView(context)
        image.setImageDrawable(AppIcons.create(context, kind, color))
        image.imageTintList = null
        image.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        return image
    }

    fun sectionHeaderBackground(expanded: Boolean): RippleDrawable {
        val radius = dp(16f).toFloat()
        val corners =
            floatArrayOf(
                radius,
                radius,
                radius,
                radius,
                if (expanded) 0f else radius,
                if (expanded) 0f else radius,
                if (expanded) 0f else radius,
                if (expanded) 0f else radius,
            )
        val surface = GradientDrawable()
        surface.setColor(palette.sectionHeader)
        surface.setCornerRadii(corners)
        val mask = GradientDrawable()
        mask.setColor(Color.WHITE)
        mask.setCornerRadii(corners)
        return RippleDrawable(android.content.res.ColorStateList.valueOf(palette.ripple), surface, mask)
    }

    fun addSegment(
        parent: LinearLayout,
        label: String,
        selected: Boolean,
        action: () -> Unit,
    ) {
        val view =
            button(
                label,
                if (selected) palette.selectedSurface else Color.TRANSPARENT,
                if (selected) palette.text else palette.muted,
            )
        view.textSize = 14f
        view.minimumHeight = dp(40f)
        view.setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
        view.isSelected = selected
        parent.addView(view, LinearLayout.LayoutParams(0, -1, 1f))
        view.setOnClickListener({ v -> action() })
    }

    fun space(parent: LinearLayout, height: Int) {
        parent.addView(View(context), LinearLayout.LayoutParams(1, dp(height.toFloat())))
    }

    fun card(parent: LinearLayout): LinearLayout =
        vertical().apply {
            background = shape(palette.surface, 16f)
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

    fun divider(parent: LinearLayout) {
        val line = View(context)
        line.setBackgroundColor(palette.divider)
        val params = LinearLayout.LayoutParams(-1, dp(0.7f))
        params.setMargins(dp(16f), 0, dp(16f), 0)
        parent.addView(line, params)
    }

    fun dp(value: Float): Int {
        return Math.round(value * context.resources.displayMetrics.density)
    }

    fun tr(resource: Int, vararg arguments: Any): String =
        AppLocales.text(context, locale(), resource, *arguments)

    fun locale(): Locale = localeProvider()

}

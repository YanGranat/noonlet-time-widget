package app.noonlet

import android.app.Activity
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

internal abstract class AppScreenActivity : Activity(), EditorDialogOwner {
    protected lateinit var palette: AppPalette
    protected lateinit var body: LinearLayout
    protected var screenLocale: Locale = Locale.ENGLISH
        private set
    protected open val scrollContent = true
    private var scroll: ScrollView? = null
    private var restoredScroll = 0
    private var renderedLanguage = ""
    private val dialogs = mutableListOf<Dialog>()

    protected abstract val titleResource: Int
    protected abstract fun buildContent(parent: LinearLayout)

    override fun onCreate(state: Bundle?) {
        AppTheme.applyToWindow(this)
        super.onCreate(state)
        restoredScroll = state?.getInt("screen_scroll", 0) ?: 0
        AppLocales.refreshLanguage(this)
        renderScreen()
    }

    override fun onResume() {
        super.onResume()
        AppTheme.applyToWindow(this)
        AppLocales.refreshLanguage(this)
        if (!renderIfPresentationChanged()) onContentVisible()
    }

    override fun onConfigurationChanged(configuration: Configuration) {
        super.onConfigurationChanged(configuration)
        AppTheme.applyToWindow(this)
        AppLocales.refreshLanguage(this)
        if (!renderIfPresentationChanged()) onContentVisible()
    }

    override fun onSaveInstanceState(out: Bundle) {
        out.putInt("screen_scroll", scroll?.scrollY ?: 0)
        super.onSaveInstanceState(out)
    }

    override fun onPause() {
        dismissScreenDialogs()
        super.onPause()
    }

    override fun onDestroy() {
        dismissScreenDialogs()
        super.onDestroy()
    }

    override fun registerEditorDialog(dialog: Dialog) {
        dialogs += dialog
        dialog.setOnDismissListener { dialogs.remove(dialog) }
    }

    protected open fun onContentVisible() = Unit

    protected fun renderIfPresentationChanged(): Boolean {
        val nextPalette = AppTheme.palette(this)
        val nextLocale = AppLocales.resourceLocale(this)
        if (nextPalette == palette && AppLocales.uiLanguage(nextLocale) == renderedLanguage)
            return false
        renderScreen()
        return true
    }

    protected fun renderScreen() {
        val position = scroll?.scrollY ?: restoredScroll
        dismissScreenDialogs()
        palette = AppTheme.palette(this)
        screenLocale = AppLocales.resourceLocale(this)
        renderedLanguage = AppLocales.uiLanguage(screenLocale)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(palette.background)
            setOnApplyWindowInsetsListener { view, insets ->
                AppInsets.apply(view, insets)
                insets
            }
        }
        val toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(16), dp(4))
            minimumHeight = dp(56)
        }
        val back = icon(R.drawable.ic_arrow_back, tr(R.string.app_back)).apply {
            tag = "app-screen-back"
            setOnClickListener { finish() }
        }
        toolbar.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)))
        toolbar.addView(label(tr(titleResource), 20f).apply {
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(8), dp(8), 0, dp(8))
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        if (scrollContent) {
            scroll = ScrollView(this).apply {
                isFillViewport = true
                isVerticalScrollBarEnabled = false
                clipToPadding = false
                addView(body, ViewGroup.LayoutParams(-1, -2))
            }
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            scroll = null
            root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
        root.requestApplyInsets()
        buildContent(body)
        scroll?.post { scroll?.scrollTo(0, position) }
        restoredScroll = 0
    }

    protected fun tr(resource: Int, vararg arguments: Any): String =
        AppLocales.text(this, screenLocale, resource, *arguments)

    protected fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    protected fun label(value: String, size: Float = 16f, color: Int = palette.text) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }

    protected fun card(parent: LinearLayout): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = AppDialogBuilder.shape(this@AppScreenActivity, palette.surface, 16)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    protected fun row(
        parent: LinearLayout,
        title: String,
        detail: String = "",
        tagValue: String,
        action: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        setPadding(dp(16), dp(12), dp(12), dp(12))
        tag = tagValue
        isClickable = true
        isFocusable = true
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        background = AppDialogBuilder.ripple(this@AppScreenActivity, Color.TRANSPARENT, 16, false)
        val texts = LinearLayout(this@AppScreenActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(title), LinearLayout.LayoutParams(-1, -2))
            if (detail.isNotBlank()) addView(label(detail, 13f, palette.muted).apply {
                tag = "$tagValue:value"
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        }
        addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ImageView(this@AppScreenActivity).apply {
            setImageDrawable(getDrawable(R.drawable.ic_chevron_right)?.mutate()?.apply {
                setTint(palette.muted)
            })
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { leftMargin = dp(12) })
        contentDescription = if (detail.isBlank()) title else "$title, $detail"
        setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }

    protected fun updateRow(row: View, title: String, detail: String) {
        row.findViewWithTag<TextView>("${row.tag}:value")?.text = detail
        row.contentDescription = "$title, $detail"
    }

    protected fun divider(parent: LinearLayout) {
        parent.addView(View(this).apply { setBackgroundColor(palette.divider) },
            LinearLayout.LayoutParams(-1, dp(1)).apply {
                leftMargin = dp(16)
                rightMargin = dp(16)
            })
    }

    protected fun icon(resource: Int, description: String) = ImageButton(this).apply {
        setImageDrawable(getDrawable(resource)?.mutate()?.apply { setTint(palette.text) })
        setPadding(dp(12), dp(12), dp(12), dp(12))
        contentDescription = description
        background = AppDialogBuilder.ripple(this@AppScreenActivity, Color.TRANSPARENT, 12, false)
        isFocusable = true
    }

    private fun dismissScreenDialogs() {
        dialogs.toList().forEach { if (it.isShowing) it.dismiss() }
        dialogs.clear()
    }
}

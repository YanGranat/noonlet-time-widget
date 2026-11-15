package app.noonlet

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.roundToInt

/** Snapshots attached native clocks, then releases their time receivers. */
internal class WidgetThumbnailView(context: Context) : FrameLayout(context) {
    private var widgetId = 0
    private var world = false
    private var categoryPreview = false
    private var importedConfiguration: SettingsArchive.Configuration? = null
    private var nativeContent: View? = null
    private var logicalSize = SizeF(150f, 90f)
    private val density get() = resources.displayMetrics.density
    private val capture = Runnable { captureContent() }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        clipChildren = true
        clipToOutline = true
    }

    fun bind(id: Int, kind: Boolean) {
        removeCallbacks(capture)
        removeAllViews()
        nativeContent = null
        widgetId = id
        world = kind
        categoryPreview = false
        importedConfiguration = null
        val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(id)
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 150).coerceAtLeast(60)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 90).coerceAtLeast(35)
        logicalSize = SizeF(width.toFloat(), height.toFloat())
        if (Build.VERSION.SDK_INT >= 31) {
            options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                ?.filter { it.width.isFinite() && it.height.isFinite() && it.width > 0 && it.height > 0 }
                ?.minByOrNull { kotlin.math.abs(it.width - width) + kotlin.math.abs(it.height - height) }
                ?.let { logicalSize = it }
        }
        val dimensionScale = minOf(1f, 1024f / logicalSize.width, 1024f / logicalSize.height,
            512f / kotlin.math.sqrt(logicalSize.width * logicalSize.height))
        logicalSize = SizeF(maxOf(1f, logicalSize.width * dimensionScale),
            maxOf(1f, logicalSize.height * dimensionScale))
        updateBackground()
        if (isAttachedToWindow) inflateContent()
        requestLayout()
    }

    fun bindCategory(kind: Boolean) {
        removeCallbacks(capture)
        removeAllViews()
        nativeContent = null
        widgetId = 0
        world = kind
        categoryPreview = true
        importedConfiguration = null
        logicalSize = SizeF(328f, 82f)
        updateBackground()
        if (isAttachedToWindow) inflateContent()
        requestLayout()
    }

    private fun updateBackground() {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (world) intArrayOf(-0xcfcfc9, -0xe5e5e0) else intArrayOf(-0x707e5b, -0x9aaa89)).apply {
            cornerRadius = 12 * density
        }
    }

    fun bindConfiguration(configuration: SettingsArchive.Configuration, kind: Boolean) {
        removeCallbacks(capture)
        removeAllViews()
        nativeContent = null
        widgetId = 0
        world = kind
        categoryPreview = true
        importedConfiguration = configuration
        logicalSize = SizeF(configuration.size.width.toFloat(), configuration.size.height.toFloat())
        updateBackground()
        if (isAttachedToWindow) inflateContent()
        requestLayout()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (childCount == 0 && (widgetId > 0 || categoryPreview)) inflateContent()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(capture)
        removeAllViews()
        nativeContent = null
        super.onDetachedFromWindow()
    }

    private fun inflateContent() {
        val settings =
            if (importedConfiguration != null) importedConfiguration!!.settings
            else if (categoryPreview) WidgetSettingsStore.loadUnplaced(context, world)
            else WidgetSettingsStore.load(context, widgetId, world)
        val content = WidgetRenderer.preview(context, widgetId, world, settings,
            logicalSize.width, logicalSize.height, WidgetFont.Scope.LOCAL).apply(context, this)
        nativeContent = content
        addView(content, LayoutParams((logicalSize.width * density).roundToInt(),
            (logicalSize.height * density).roundToInt()))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val nativeWidth = (logicalSize.width * density).roundToInt().coerceAtLeast(1)
        val nativeHeight = (logicalSize.height * density).roundToInt().coerceAtLeast(1)
        val scale = minOf(1f, available.toFloat() / nativeWidth, 172 * density / nativeHeight)
        val height = (nativeHeight * scale).roundToInt().coerceAtLeast(1)
        setMeasuredDimension(available, height)
        nativeContent?.measure(MeasureSpec.makeMeasureSpec(nativeWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(nativeHeight, MeasureSpec.EXACTLY))
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child !== nativeContent) child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val content = nativeContent
        if (content == null) {
            super.onLayout(changed, left, top, right, bottom)
            return
        }
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val scale = minOf(1f, width.toFloat() / content.width, height.toFloat() / content.height)
        content.pivotX = 0f
        content.pivotY = 0f
        content.scaleX = scale
        content.scaleY = scale
        content.translationX = (width - content.width * scale) / 2f
        removeCallbacks(capture)
        post(capture)
    }

    private fun captureContent() {
        val content = nativeContent ?: return
        if (!isAttachedToWindow || content.width <= 0 || content.height <= 0) return
        PreviewClockFormatter.localize(content, System.currentTimeMillis(), AppLocales.phoneLocale(context))
        content.measure(MeasureSpec.makeMeasureSpec(content.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(content.height, MeasureSpec.EXACTLY))
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val scale =
            if (categoryPreview) minOf(1f, 1024f / content.width, 1024f / content.height)
            else minOf(1f, width.toFloat() / content.width, height.toFloat() / content.height,
                1024f / content.width, 1024f / content.height)
        val bitmap = Bitmap.createBitmap((content.width * scale).roundToInt().coerceAtLeast(1),
            (content.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(bitmap.width.toFloat() / content.width, bitmap.height.toFloat() / content.height)
        content.draw(canvas)
        removeView(content)
        nativeContent = null
        addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(bitmap)
        }, ViewGroup.LayoutParams(-1, -1))
    }
}

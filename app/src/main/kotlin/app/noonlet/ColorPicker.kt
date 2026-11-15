package app.noonlet

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.util.Locale
import java.util.function.IntConsumer
import kotlin.math.roundToInt

internal object ColorPicker {
    private val colorPattern = Regex("[0-9a-fA-F]{6}|[0-9a-fA-F]{8}")

    @JvmStatic
    fun show(
        activity: Activity,
        title: String,
        initial: Int,
        selected: IntConsumer,
    ) {
        val colors = AppTheme.palette(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val body =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(12))
            }
        var color = initial
        var synchronizing = false
        var editingHex = false
        val sample =
            TextView(activity).apply {
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = Typeface.MONOSPACE
            }
        body.addView(sample, LinearLayout.LayoutParams(-1, dp(56)))

        val paletteScroll =
            HorizontalScrollView(activity).apply { isHorizontalScrollBarEnabled = false }
        val palette =
            LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, dp(8))
            }
        paletteScroll.addView(palette)
        body.addView(paletteScroll)

        val hex =
            EditText(activity).apply {
                setSingleLine(true)
                textSize = 16f
                setTextColor(colors.text)
                setHintTextColor(colors.muted)
                hint = "#AARRGGBB / #RRGGBB"
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                contentDescription = AppLocales.text(activity, R.string.color_argb_description)
            }
        body.addView(hex, LinearLayout.LayoutParams(-1, dp(52)))

        val sliders = Array(4) { SeekBar(activity) }
        val values = Array(4) { TextView(activity) }
        val names =
            intArrayOf(
                    R.string.color_alpha,
                    R.string.color_red,
                    R.string.color_green,
                    R.string.color_blue,
                )
                .map { AppLocales.text(activity, it) }
        for (channel in 0..3) {
            val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
            val label =
                TextView(activity).apply {
                    text = names[channel]
                    setTextColor(colors.secondaryText)
                    textSize = 13f
                }
            row.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            values[channel].apply {
                setTextColor(colors.muted)
                textSize = 13f
            }
            row.addView(values[channel])
            row.minimumHeight = dp(24)
            body.addView(row, LinearLayout.LayoutParams(-1, -2))
            sliders[channel].apply {
                max = 255
                contentDescription = names[channel]
                progressTintList = ColorStateList.valueOf(colors.accent)
                thumbTintList = ColorStateList.valueOf(colors.accent)
            }
            body.addView(sliders[channel], LinearLayout.LayoutParams(-1, dp(44)))
        }

        fun sync() {
            synchronizing = true
            val value = String.format(Locale.ROOT, "#%08X", color)
            if (!editingHex) hex.setText(value)
            sample.text = value
            sample.background =
                GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(color)
                    setStroke(dp(1), colors.swatchBorder)
                }
            val alpha = Color.alpha(color)
            fun composite(foreground: Int, background: Int) =
                (foreground * alpha + background * (255 - alpha) + 127) / 255
            val background = Color.rgb(
                composite(Color.red(color), Color.red(colors.surface)),
                composite(Color.green(color), Color.green(colors.surface)),
                composite(Color.blue(color), Color.blue(colors.surface)),
            )
            val luminance = Color.luminance(background)
            val blackContrast = (luminance + .05) / .05
            val whiteContrast = 1.05 / (luminance + .05)
            sample.setTextColor(if (blackContrast >= whiteContrast) Color.BLACK else Color.WHITE)
            for (index in 0..3) {
                val valueForChannel = (color ushr ((3 - index) * 8)) and 255
                sliders[index].progress = valueForChannel
                values[index].text = valueForChannel.toString()
            }
            hex.error = null
            synchronizing = false
        }

        val paletteColors =
            intArrayOf(
                Color.WHITE,
                Color.BLACK,
                0xffbdbdbd.toInt(),
                0xffef4038.toInt(),
                0xffed1765.toInt(),
                0xffad2bcc.toInt(),
                0xff7046c4.toInt(),
                0xff4658bf.toInt(),
                0xff249ceb.toInt(),
                0xff16b9ba.toInt(),
                0xff9dd767.toInt(),
                0xffffc400.toInt(),
            )
        for (preset in paletteColors) {
            val swatch =
                View(activity).apply {
                    background =
                        GradientDrawable().apply {
                            cornerRadius = dp(12).toFloat()
                            setColor(preset)
                            setStroke(dp(1), colors.swatchBorder)
                        }
                    contentDescription = String.format(Locale.ROOT, "#%08X", preset)
                }
            val params =
                LinearLayout.LayoutParams(dp(48), dp(48)).apply { setMargins(0, 0, dp(10), 0) }
            palette.addView(swatch, params)
            swatch.setOnClickListener {
                color = preset
                sync()
            }
        }
        for (channel in 0..3) {
            sliders[channel].setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                        if (!fromUser || synchronizing) return
                        val shift = (3 - channel) * 8
                        color = (color and (255 shl shift).inv()) or (value shl shift)
                        sync()
                    }

                    override fun onStartTrackingTouch(bar: SeekBar) = Unit

                    override fun onStopTrackingTouch(bar: SeekBar) = Unit
                },
            )
        }
        hex.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    value: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    value: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    if (synchronizing) return
                    val parsed = parse(value?.toString().orEmpty()) ?: return
                    color = parsed
                    val selection = hex.selectionStart
                    editingHex = true
                    sync()
                    editingHex = false
                    hex.setSelection(selection.coerceIn(0, hex.length()))
                }

                override fun afterTextChanged(value: Editable?) = Unit
            },
        )

        val dialog =
            AppDialogBuilder(activity)
                .setTitle(title)
                .setView(body)
                .setNegativeButton(AppLocales.text(activity, R.string.cancel), null)
                .setPositiveButton(AppLocales.text(activity, R.string.apply), null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!dialog.isShowing) return@setOnClickListener
                val parsed = parse(hex.text.toString())
                if (parsed == null) {
                    hex.error = AppLocales.text(activity, R.string.invalid_color_format)
                    return@setOnClickListener
                }
                selected.accept(parsed)
                dialog.dismiss()
            }
        }
        sync()
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun parse(value: String): Int? {
        val text = value.trim { it <= ' ' }.removePrefix("#")
        if (!colorPattern.matches(text)) return null
        return (if (text.length == 6) "FF$text" else text).toLongOrNull(16)?.toInt()
    }
}

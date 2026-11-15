package app.noonlet

import android.os.Build
import android.view.View
import android.view.WindowInsets

internal object AppInsets {
    @Suppress("DEPRECATION")
    fun apply(view: View, insets: WindowInsets) {
        if (Build.VERSION.SDK_INT >= 30) {
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
        } else {
            var left = insets.systemWindowInsetLeft
            var top = insets.systemWindowInsetTop
            var right = insets.systemWindowInsetRight
            var bottom = insets.systemWindowInsetBottom
            if (Build.VERSION.SDK_INT >= 28) {
                insets.displayCutout?.let {
                    left = maxOf(left, it.safeInsetLeft)
                    top = maxOf(top, it.safeInsetTop)
                    right = maxOf(right, it.safeInsetRight)
                    bottom = maxOf(bottom, it.safeInsetBottom)
                }
            }
            view.setPadding(left, top, right, bottom)
        }
    }
}

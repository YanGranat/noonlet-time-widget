package app.noonlet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

internal object IconAssertions {
    fun assertChoice(drawable: Drawable, checked: Boolean) = inspect(drawable) { pixels ->
        assertInk(pixels, Rect(10, 1, 14, 4))
        assertInk(pixels, Rect(1, 10, 4, 14))
        assertInk(pixels, Rect(20, 10, 23, 14))
        assertInk(pixels, Rect(10, 20, 14, 23))
        if (checked) {
            assertInk(pixels, Rect(6, 6, 18, 18))
            assertTrue(pixels.getPixel(7, 7) != pixels.getPixel(10, 14))
        } else assertClear(pixels, Rect(5, 5, 19, 19))
    }

    fun assertPlus(drawable: Drawable) =
        inspect(drawable) { pixels ->
            assertInk(pixels, Rect(10, 4, 14, 8))
            assertInk(pixels, Rect(4, 10, 8, 14))
            assertInk(pixels, Rect(10, 10, 14, 14))
            assertInk(pixels, Rect(16, 10, 20, 14))
            assertInk(pixels, Rect(10, 16, 14, 20))
            for (corner in
                arrayOf(
                    Rect(3, 3, 8, 8),
                    Rect(16, 3, 21, 8),
                    Rect(3, 16, 8, 21),
                    Rect(16, 16, 21, 21),
                )) assertClear(pixels, corner)
        }

    fun assertCheck(drawable: Drawable) =
        inspect(drawable) { pixels ->
            assertInk(pixels, Rect(3, 9, 8, 14))
            assertInk(pixels, Rect(7, 14, 13, 19))
            assertInk(pixels, Rect(16, 5, 21, 10))
            assertClear(pixels, Rect(3, 3, 8, 8))
            assertClear(pixels, Rect(16, 16, 21, 21))
        }

    private fun inspect(drawable: Drawable, verify: (Bitmap) -> Unit) {
        assertFalse(
            "Icons must use drawable geometry rather than bitmap substitutes",
            drawable is BitmapDrawable,
        )
        val previous = Rect(drawable.bounds)
        val pixels = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, 24, 24)
            drawable.draw(Canvas(pixels))
            verify(pixels)
        } finally {
            drawable.bounds = previous
            pixels.recycle()
        }
    }

    private fun assertInk(pixels: Bitmap, region: Rect) {
        assertTrue("Missing icon stroke in $region", hasInk(pixels, region))
    }

    private fun assertClear(pixels: Bitmap, region: Rect) {
        assertFalse("Unexpected icon stroke in $region", hasInk(pixels, region))
    }

    private fun hasInk(pixels: Bitmap, region: Rect): Boolean {
        for (y in region.top until region.bottom) for (x in region.left until region.right) if (
            Color.alpha(pixels.getPixel(x, y)) >= 128
        )
            return true
        return false
    }
}

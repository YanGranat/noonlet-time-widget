package app.noonlet

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CompletableFuture
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeatherLocationDialogTest {
    private lateinit var context: Context
    private lateinit var activity: org.robolectric.android.controller.ActivityController<Activity>

    @Before
    fun prepare() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        context = activity.get()
        AppLocales.setLanguage(context, "en")
    }

    @After fun close() = activity.close()

    @Test
    fun searchResultsAreDirectActionsAndCommitTheExactPlaceOnce() {
        val current = WeatherPlace("Harbor, Sample region", 13_000_001, 24_000_002)
        val renamed = WeatherPlace("Port, Sample region", current.latitudeE6, current.longitudeE6)
        val other = WeatherPlace(current.name, 13_000_003, 24_000_004)
        for (useDevice in listOf(false, true)) {
            val selections = mutableListOf<WeatherPlace?>()
            val dialog = WeatherLocationDialog.show(
                context, Locale.ENGLISH, useDevice, current,
                searchPlaces = { _, _ -> CompletableFuture.completedFuture(listOf(renamed, other)) },
                onChoice = { selections.add(it) },
            )
            val root = dialog.window!!.decorView
            assertEquals(View.VISIBLE, root.findViewWithTag<View>("dialog-header-divider").visibility)
            val device = root.findViewWithTag<ImageView>("weather-location-device-indicator")
            assertEquals(if (useDevice) View.VISIBLE else View.INVISIBLE, device.visibility)
            assertEquals(useDevice, device.isSelected)
            IconAssertions.assertCheck(device.drawable)
            if (!useDevice) {
                val selected = root.findViewWithTag<ImageView>("weather-location-current-indicator")
                assertEquals(View.VISIBLE, selected.visibility)
                IconAssertions.assertCheck(selected.drawable)
            }
            root.findViewWithTag<EditText>("weather-location-search").setText("Ha")
            idle(260)
            val body = root.findViewWithTag<LinearLayout>("weather-location-body")
            body.measure(
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST),
            )
            body.layout(0, 0, body.measuredWidth, body.measuredHeight)
            WidgetTestViews.prepare(body)
            val search = root.findViewWithTag<EditText>("weather-location-search")
            val searchDivider = root.findViewWithTag<View>("place-search-divider")
            assertEquals(0, searchDivider.left)
            assertEquals(body.width, searchDivider.right)
            val searchArea = root.findViewWithTag<View>("weather-location-search-area")
            assertEquals(searchArea.bottom, searchDivider.top)
            assertEquals(root.findViewWithTag<View>("weather-location-device").measuredHeight,
                searchArea.measuredHeight)
            assertEquals(32, search.height)
            val searchPixels = Bitmap.createBitmap(search.width, search.height, Bitmap.Config.ARGB_8888)
            search.background.setBounds(0, 0, search.width, search.height)
            search.background.draw(Canvas(searchPixels))
            assertEquals(AppTheme.palette(context).input, searchPixels.getPixel(0, search.height / 2))
            assertEquals(AppTheme.palette(context).input, searchPixels.getPixel(search.width / 2, search.height - 1))
            searchPixels.recycle()
            val results = root.findViewWithTag<LinearLayout>("weather-location-results")
            assertEquals(2, results.childCount)
            val exact = results.getChildAt(0) as TextView
            val alternative = results.getChildAt(1) as TextView
            assertEquals(renamed.name, exact.text.toString())
            for (row in listOf(exact, alternative)) {
                assertTrue(row.isClickable && row.isFocusable)
                assertTrue(row.height >= 52)
                val currentResult = !useDevice && row === exact
                if (currentResult) IconAssertions.assertCheck(requireNotNull(row.compoundDrawablesRelative[2]))
                else assertTrue(row.compoundDrawablesRelative.all { it == null })
                val node = row.createAccessibilityNodeInfo()
                assertEquals("android.widget.Button", node.className.toString())
                assertTrue(node.isClickable)
                assertFalse(node.isCheckable)
                assertEquals(currentResult, node.isSelected)
                val pixels = Bitmap.createBitmap(row.width, row.height, Bitmap.Config.ARGB_8888)
                try {
                    row.draw(Canvas(pixels))
                    assertEquals(
                        android.graphics.Color.TRANSPARENT,
                        pixels.getPixel(6, row.height / 2),
                    )
                } finally {
                    pixels.recycle()
                }
            }
            assertEquals(exact.bottom, alternative.top)
            val separator = Bitmap.createBitmap(exact.width, exact.height, Bitmap.Config.ARGB_8888)
            try {
                exact.draw(Canvas(separator))
                assertEquals(AppTheme.palette(context).divider,
                    separator.getPixel(exact.width / 2, exact.height - 1))
            } finally {
                separator.recycle()
            }
            assertTrue(alternative.performClick())
            assertEquals(1, selections.size)
            assertEquals(other.encode(), selections.single()!!.encode())
            assertFalse(dialog.isShowing)
            assertNull(Shadows.shadowOf(activity.get()).nextStartedActivity)
            alternative.performClick()
            assertEquals(1, selections.size)
        }
    }

    @Test
    fun theCurrentPlaceCanBeConfirmedWithoutChangingItsIdentity() {
        val current = WeatherPlace("Saved sample", 11_000_001, 22_000_002)
        val selections = mutableListOf<WeatherPlace?>()
        var searches = 0
        val dialog = WeatherLocationDialog.show(
            context, Locale.ENGLISH, false, current,
            searchPlaces = { _, _ ->
                searches++
                CompletableFuture.completedFuture(emptyList())
            },
            onChoice = { selections.add(it) },
        )
        val row = dialog.window!!.decorView.findViewWithTag<View>("weather-location-selected")
        assertTrue(row.isClickable && row.isFocusable && row.isSelected)
        val node = row.createAccessibilityNodeInfo()
        assertTrue(node.isCheckable && node.isChecked)
        assertTrue(row.performClick())
        assertFalse(dialog.isShowing)
        assertSame(current, selections.single())
        assertEquals(current.encode(), selections.single()!!.encode())
        assertEquals(0, searches)
        row.performClick()
        assertEquals(1, selections.size)
    }

    @Test
    fun resultsUseTheQueryLanguageAndOnlyTheSelectedCoordinatesAreCommitted() {
        val requests = mutableListOf<Pair<String, String>>()
        val futures = mutableListOf<CompletableFuture<List<WeatherPlace>>>()
        val selections = mutableListOf<WeatherPlace?>()
        val initial = WeatherPlace("Original place", 10_000_001, 20_000_002)
        val dialog =
            WeatherLocationDialog.show(
                context,
                Locale.GERMAN,
                false,
                initial,
                searchPlaces = { query, language ->
                    requests.add(query to language)
                    object : CompletableFuture<List<WeatherPlace>>() {
                            override fun cancel(mayInterruptIfRunning: Boolean) = false
                        }
                        .also { futures.add(it) }
                },
                onChoice = { selections.add(it) },
            )
        val search = dialog.window!!.decorView.findViewWithTag<EditText>("weather-location-search")
        search.setText("Al")
        idle(260)
        search.setText("Be")
        idle(260)
        assertEquals(listOf("Al" to "de", "Be" to "de"), requests)
        assertTrue(selections.isEmpty())
        val selected = WeatherPlace("Beta, Sample region", -12_345_678, 98_765_432)
        futures[1].complete(listOf(selected))
        idle()
        futures[0].complete(listOf(WeatherPlace("Alpha", 1_000_000, 2_000_000)))
        idle()
        val list = search.rootView.findViewWithTag<LinearLayout>("weather-location-results")
        assertEquals(1, list.childCount)
        val row = list.getChildAt(0) as TextView
        assertEquals("Beta, Sample region", row.text.toString())
        row.performClick()
        assertEquals(1, selections.size)
        assertEquals(selected.encode(), selections.single()!!.encode())
        assertFalse(dialog.isShowing)
    }

    @Test
    fun cancellationRemovesPendingSearchAndLeavesTheSavedChoiceUntouched() {
        var requests = 0
        var selections = 0
        val dialog =
            WeatherLocationDialog.show(
                context,
                Locale.ENGLISH,
                true,
                null,
                searchPlaces = { _, _ ->
                    requests++
                    CompletableFuture.completedFuture(emptyList())
                },
                onChoice = { selections++ },
            )
        val search = dialog.window!!.decorView.findViewWithTag<EditText>("weather-location-search")
        search.setText("Sample")
        dialog.window!!.decorView.findViewWithTag<View>("dialog-close").performClick()
        idle(500)
        assertEquals(0, requests)
        assertEquals(0, selections)
    }

    @Test
    fun deviceLocationIsAnExplicitChoiceWithoutStartingAPlaceSearch() {
        var requests = 0
        val selections = mutableListOf<WeatherPlace?>()
        val dialog =
            WeatherLocationDialog.show(
                context,
                Locale.ENGLISH,
                false,
                WeatherPlace("Manual place", 1, 2),
                searchPlaces = { _, _ ->
                    requests++
                    CompletableFuture.completedFuture(emptyList())
                },
                onChoice = { selections.add(it) },
            )
        assertTrue(
            dialog.window!!
                .decorView
                .findViewWithTag<View>("weather-location-device")
                .performClick(),
        )
        idle()
        assertEquals(0, requests)
        assertEquals(1, selections.size)
        assertNull(selections.single())
        assertFalse(dialog.isShowing)
    }

    @Test
    fun selectedPlaceIsSeparateFromSearchAndTheIdleDialogUsesOnlyItsContentHeight() {
        var requests = 0
        val dialog =
            WeatherLocationDialog.show(
                context,
                Locale.ENGLISH,
                false,
                WeatherPlace("Sample place, Sample region", 10_000_001, 20_000_002),
                searchPlaces = { _, _ ->
                    requests++
                    CompletableFuture.completedFuture(emptyList())
                },
                onChoice = {},
            )
        val root = dialog.window!!.decorView
        val selected = root.findViewWithTag<View>("weather-location-selected")
        assertTrue(selected.isSelected)
        assertTrue(selected.contentDescription.toString().startsWith("Selected place, "))
        assertFalse(root.findViewWithTag<View>("weather-location-device").isSelected)
        assertEquals(0, root.findViewWithTag<LinearLayout>("weather-location-results").childCount)
        assertEquals(View.GONE, root.findViewWithTag<View>("weather-location-status").visibility)
        val viewport = root.findViewWithTag<ScrollView>("weather-location-viewport")
        val body = root.findViewWithTag<LinearLayout>("weather-location-body")
        body.measure(
            View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST),
        )
        assertTrue(body.measuredHeight < 400)
        assertEquals(viewport.getChildAt(0).measuredHeight, viewport.measuredHeight)
        val searchArea = root.findViewWithTag<ViewGroup>("weather-location-search-area")
        assertSame(body, searchArea.parent)
        assertSame(searchArea, root.findViewWithTag<EditText>("weather-location-search").parent)
        assertTrue(
            body.indexOfChild(searchArea) <
                body.indexOfChild(viewport),
        )
        assertEquals(0, requests)
        dialog.dismiss()
    }

    @Test
    fun searchStatesKeepTheSelectionAndScrollWithinAConstrainedViewport() {
        val originalScale = org.robolectric.RuntimeEnvironment.getFontScale()
        org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
        try {
            val response = CompletableFuture<List<WeatherPlace>>()
            val dialog =
                WeatherLocationDialog.show(
                    context,
                    Locale.ENGLISH,
                    false,
                    WeatherPlace("Selected sample", 1, 2),
                    searchPlaces = { _, _ -> response },
                    onChoice = {},
                )
            val root = dialog.window!!.decorView
            val selected = root.findViewWithTag<View>("weather-location-selected")
            val choices = root.findViewWithTag<View>("weather-location-choices")
            val search = root.findViewWithTag<EditText>("weather-location-search")
            val status = root.findViewWithTag<TextView>("weather-location-status")
            search.setText("Sa")
            idle(260)
            assertEquals(View.GONE, choices.visibility)
            assertEquals(context.getString(R.string.weather_loading_short), status.text.toString())
            response.complete((1..10).map { WeatherPlace("Result $it, Sample region", it, it) })
            idle()
            assertSame(selected, root.findViewWithTag<View>("weather-location-selected"))
            val results = root.findViewWithTag<LinearLayout>("weather-location-results")
            assertEquals(10, results.childCount)
            assertEquals(View.GONE, status.visibility)
            val viewport = root.findViewWithTag<ScrollView>("weather-location-viewport")
            val body = root.findViewWithTag<LinearLayout>("weather-location-body")
            body.measure(
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.AT_MOST),
            )
            body.layout(0, 0, body.measuredWidth, body.measuredHeight)
            assertTrue(body.measuredHeight <= 240)
            assertTrue(viewport.getChildAt(0).height > viewport.height)
            DialogTestFixtures.layout(root, 320, 360)
            val close = root.findViewWithTag<View>("dialog-close")
            val closeBounds = android.graphics.Rect(0, 0, close.width, close.height)
            (root as android.view.ViewGroup).offsetDescendantRectToMyCoords(close, closeBounds)
            assertTrue(closeBounds.top >= 0)
            assertEquals(48, closeBounds.height())
            val searchBounds = android.graphics.Rect(0, 0, search.width, search.height)
            root.offsetDescendantRectToMyCoords(search, searchBounds)
            assertTrue(closeBounds.bottom <= searchBounds.top)
            val firstBounds =
                android.graphics.Rect(
                    0,
                    0,
                    results.getChildAt(0).width,
                    results.getChildAt(0).height,
                )
            body.offsetDescendantRectToMyCoords(results.getChildAt(0), firstBounds)
            assertTrue(firstBounds.top >= search.bottom)
            assertTrue(firstBounds.bottom <= body.height - body.paddingBottom)
            val searchTop = search.top
            val closeTop = closeBounds.top
            viewport.scrollTo(0, 500)
            assertEquals(searchTop, search.top)
            val afterScroll = android.graphics.Rect(0, 0, close.width, close.height)
            root.offsetDescendantRectToMyCoords(close, afterScroll)
            assertEquals(closeTop, afterScroll.top)
            search.setText("")
            idle()
            assertEquals(View.VISIBLE, choices.visibility)
            assertSame(selected, root.findViewWithTag<View>("weather-location-selected"))
            assertTrue(selected.isSelected)
            assertEquals(0, results.childCount)
            dialog.dismiss()
        } finally {
            org.robolectric.RuntimeEnvironment.setFontScale(originalScale)
        }
    }

    @Test
    fun aDismissedDialogRejectsLateResultsAndSearchFailuresDoNotCommitAChoice() {
        val response =
            object : CompletableFuture<List<WeatherPlace>>() {
                override fun cancel(mayInterruptIfRunning: Boolean) = false
            }
        var choices = 0
        val dialog =
            WeatherLocationDialog.show(
                context,
                Locale.ENGLISH,
                true,
                null,
                searchPlaces = { _, _ -> response },
                onChoice = { choices++ },
            )
        val root = dialog.window!!.decorView
        root.findViewWithTag<EditText>("weather-location-search").setText("Sa")
        idle(260)
        dialog.dismiss()
        response.complete(listOf(WeatherPlace("Late sample", 1, 2)))
        idle()
        assertEquals(0, root.findViewWithTag<LinearLayout>("weather-location-results").childCount)
        assertEquals(0, choices)
        val failed =
            WeatherLocationDialog.show(
                context,
                Locale.ENGLISH,
                true,
                null,
                searchPlaces = { _, _ -> throw IllegalStateException("Search unavailable") },
                onChoice = { choices++ },
            )
        failed.window!!.decorView.findViewWithTag<EditText>("weather-location-search").setText("Sa")
        idle(260)
        assertEquals(
            context.getString(R.string.weather_search_failed),
            failed.window!!
                .decorView
                .findViewWithTag<TextView>("weather-location-status")
                .text
                .toString(),
        )
        assertEquals(0, choices)
        failed.dismiss()
    }

    private fun idle(milliseconds: Long = 0) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(milliseconds))
    }
}

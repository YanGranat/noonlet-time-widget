package app.noonlet

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Looper
import android.view.View
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.ListView
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34, 36], qualifiers = "en-rUS-w400dp-h850dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChoiceRowsTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        for (name in listOf("noonlet_widgets", "MainActivity", "widget_drafts", "widget_configuration", "noonlet_app"))
            context.getSharedPreferences(name, 0).edit().clear().commit()
        AppLocales.setLanguage(context, "en")
    }

    @Test
    fun terminalDividerTracksTheLastItemWithoutSeparatingTheButtonPanel() {
        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            AppPreferences(context).setThemeMode(theme)
            Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
                for (count in listOf(2, 40)) {
                    val dialog = AppDialogBuilder(controller.get()).setTitle("Places")
                        .setItems(Array(count) { "Place ${it + 1}" }, null)
                        .setNegativeButton("Cancel", null).show()
                    val root = DialogTestFixtures.layout(requireNotNull(dialog.window).decorView, 360, 640)
                    val list = dialog.listView
                    assertNull(root.findViewWithTag<View>("dialog-footer-divider"))
                    assertEquals(1, list.dividerHeight)
                    val firstPicture = WidgetTestViews.draw(list)
                    assertEquals(AppTheme.palette(controller.get()).divider,
                        firstPicture.getPixel(1, list.getChildAt(0).bottom))
                    firstPicture.recycle()
                    if (count > 2) {
                        assertTrue(list.lastVisiblePosition < count - 1)
                        val picture = Bitmap.createBitmap(list.width, list.height, Bitmap.Config.ARGB_8888)
                        requireNotNull(list.foreground).setBounds(0, 0, list.width, list.height)
                        requireNotNull(list.foreground).draw(Canvas(picture))
                        assertTrue((0 until picture.height).all { picture.getPixel(1, it) == android.graphics.Color.TRANSPARENT })
                        picture.recycle()
                        repeat(count) {
                            if (list.lastVisiblePosition < count - 1) {
                                list.scrollListBy(list.height)
                                list.layout(list.left, list.top, list.right, list.bottom)
                            }
                        }
                    }
                    assertEquals(count - 1, list.lastVisiblePosition)
                    val last = list.getChildAt(list.childCount - 1)
                    val picture = WidgetTestViews.draw(list)
                    assertEquals(AppTheme.palette(controller.get()).divider,
                        picture.getPixel(1, last.bottom - 1))
                    picture.recycle()
                    val spill = Bitmap.createBitmap(list.width, last.bottom + 2, Bitmap.Config.ARGB_8888)
                    requireNotNull(list.divider).setBounds(0, last.bottom, list.width, last.bottom + 1)
                    requireNotNull(list.divider).draw(Canvas(spill))
                    assertEquals(android.graphics.Color.TRANSPARENT, spill.getPixel(1, last.bottom))
                    spill.recycle()
                    dialog.dismiss()
                }
            }
        }
    }

    @Test
    fun formsAndMessagesUseAContinuousSurfaceWhileListsKeepASeparatedHeader() {
        for (theme in listOf(ThemeMode.DARK, ThemeMode.LIGHT)) {
            AppPreferences(context).setThemeMode(theme)
            Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
                val activity = controller.get()
                var applied = false
                val dialogs = listOf(
                    AppDialogBuilder(activity).setTitle("Format").setView(EditText(activity))
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Save") { _, _ -> applied = true }.show(),
                    AppDialogBuilder(activity).setTitle("Confirm").setMessage("Reset settings?")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Reset") { _, _ -> applied = true }.show(),
                    AppDialogBuilder(activity).setTitle("Details").setMessage("Application details").show(),
                    AppDialogBuilder(activity).setTitle("Appearance")
                        .setSingleChoiceItems(arrayOf("Automatic", "Light", "Dark"), 0, null)
                        .setNegativeButton("Cancel", null).show(),
                )
                dialogs.forEachIndexed { index, dialog ->
                    val root = DialogTestFixtures.layout(requireNotNull(dialog.window).decorView, 360, 640)
                    val header = root.findViewWithTag<View>("dialog-header")
                    val separated = index == 3
                    assertEquals(if (separated) AppTheme.palette(activity).sectionHeader
                        else AppTheme.palette(activity).surface, pixel(requireNotNull(header.background)))
                    assertEquals(if (separated) View.VISIBLE else View.GONE,
                        root.findViewWithTag<View>("dialog-header-divider").visibility)
                    val heading = root.findViewWithTag<android.widget.TextView>("dialog-title")
                    assertEquals(if (separated) 16f else 20f, heading.textSize, 0f)
                    val close = root.findViewWithTag<View>("dialog-close")
                    assertEquals(if (index < 2) View.GONE else View.VISIBLE, close.visibility)
                    if (index < 2) dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
                    else close.performClick()
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    assertFalse(dialog.isShowing)
                    assertFalse(applied)
                }
            }
        }
    }

    @Test
    fun singleSurfaceListsExposeTheCurrentValueAndApplyTheChosenRowImmediately() {
        for (theme in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) {
            AppPreferences(context).setThemeMode(theme)
            Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
                val activity = controller.get()
                var selected = -1
                val dialog = AppDialogBuilder(activity).setTitle("Appearance")
                    .setSingleChoiceItems(arrayOf("Automatic", "Light", "Dark"), 1) { source, index ->
                        selected = index
                        source.dismiss()
                    }.show()
                DialogTestFixtures.layout(requireNotNull(dialog.window).decorView, 400, 850)
                val list = dialog.listView
                val palette = AppTheme.palette(activity)
                for (index in 0..2) {
                    val row = list.adapter.getView(index, null, list) as CheckedTextView
                    assertEquals(index == 1, row.isChecked)
                    assertNull(row.compoundDrawablesRelative[0])
                    val indicator = requireNotNull(row.compoundDrawablesRelative[2])
                    assertTrue(opaquePixels(indicator) > 0)
                    assertEquals(android.graphics.Color.TRANSPARENT, pixel(row.background))
                    val info = row.createAccessibilityNodeInfo()
                    assertTrue(info.isCheckable)
                    assertEquals(index == 1, info.isChecked)
                }
                assertEquals(1, list.checkedItemPosition)
                assertEquals(1, list.dividerHeight)
                assertNotNull(list.divider)
                DialogTestFixtures.choose(dialog, 2)
                assertEquals(2, selected)
                assertFalse(dialog.isShowing)
            }
        }
    }

    @Test
    fun dividedRowsFillTheSurfaceAndKeepTerminalCompensationBelowTheLabel() {
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val dialog = AppDialogBuilder(controller.get()).setTitle("Appearance")
                .setSingleChoiceItems(arrayOf("Automatic", "Light", "Dark"), 1, null).show()
            val root = DialogTestFixtures.layout(requireNotNull(dialog.window).decorView, 360, 800)
            val list = dialog.listView
            val header = requireNotNull(root.findViewWithTag<View>("dialog-header"))
            val rows = (0..2).map { list.getChildAt(it) as CheckedTextView }
            val headerPosition = IntArray(2).also(header::getLocationInWindow)
            val listPosition = IntArray(2).also(list::getLocationInWindow)
            assertEquals(headerPosition[1] + header.height, listPosition[1] + rows[0].top)
            assertEquals(0, list.paddingLeft)
            assertEquals(0, list.paddingRight)
            assertEquals(0, list.paddingTop)
            assertEquals(0, list.paddingBottom)
            assertTrue(rows.all { it.width == list.width && it.height >= 48 })
            assertEquals(rows[0].height, rows[1].height)
            val extra = rows[2].height - rows[1].height
            assertTrue(extra in 2..3)
            assertEquals(rows[1].paddingTop, rows[2].paddingTop)
            assertEquals(extra, rows[2].paddingBottom - rows[1].paddingBottom)
            val heading = requireNotNull(DialogTestFixtures.first(header, android.widget.TextView::class.java))
            assertTrue(heading.textSize <= rows[0].textSize)
            val close = requireNotNull(header.findViewWithTag<View>("dialog-close"))
            assertTrue(close.width >= 48 && close.height >= 48)
            val line = requireNotNull(list.divider)
            val pixels = Bitmap.createBitmap(64, 1, Bitmap.Config.ARGB_8888)
            line.setBounds(0, 0, 64, 1)
            line.draw(Canvas(pixels))
            assertTrue(pixels.getPixel(0, 0) ushr 24 != 0)
            assertEquals(pixels.getPixel(0, 0), pixels.getPixel(63, 0))
            var applied = false
            list.setOnItemClickListener { _, _, _, _ -> applied = true }
            close.performClick()
            assertFalse(dialog.isShowing)
            assertFalse(applied)
        }
    }

    @Test
    fun recycledRowsResetTheirTerminalInsetsAfterTheCollectionChanges() {
        val list = ListView(context)
        val adapter = AppDialogBuilder.ChoiceAdapter(context, arrayOf("One", "Two", "Three"), true, 2)
        val last = adapter.getView(2, null, list) as CheckedTextView
        val terminalBottom = last.paddingBottom
        val first = adapter.getView(0, last, list) as CheckedTextView
        assertTrue(first.paddingBottom < terminalBottom)
        assertEquals(first.paddingTop, first.paddingBottom)
        adapter.replaceLabels(listOf("Loading"), false)
        val loading = adapter.getView(0, first, list) as CheckedTextView
        assertFalse(loading.isEnabled)
        assertFalse(loading.createAccessibilityNodeInfo().isCheckable)
        assertNull(loading.compoundDrawablesRelative[2])
        adapter.replaceLabels(listOf("One", "Two", "Three"), true, 2)
        val selected = adapter.getView(2, loading, list) as CheckedTextView
        assertTrue(selected.isEnabled)
        assertTrue(selected.isChecked)
        assertEquals(terminalBottom, selected.paddingBottom)
    }

    @Test
    fun actionAndNavigationRowsDoNotPresentASettingsSelection() {
        Robolectric.buildActivity(Activity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            var action = -1
            val dialog = AppDialogBuilder(activity).setTitle("Places")
                .setActionItems(arrayOf("Move up", "Remove"),
                    listOf(R.drawable.ic_arrow_upward, R.drawable.ic_delete)) { source, index ->
                    action = index
                    source.dismiss()
                }.show()
            val row = dialog.listView.adapter.getView(1, null, dialog.listView) as CheckedTextView
            assertNotNull(row.compoundDrawablesRelative[0])
            assertNull(row.compoundDrawablesRelative[2])
            assertFalse(row.createAccessibilityNodeInfo().isCheckable)
            DialogTestFixtures.choose(dialog, 1)
            assertEquals(1, action)
            val navigation = AppDialogBuilder(activity).setItems(arrayOf("Clock 1", "World clock 1"), null).show()
            val destination = navigation.listView.adapter.getView(0, null, navigation.listView) as CheckedTextView
            assertNull(destination.compoundDrawablesRelative[0])
            assertNotNull(destination.compoundDrawablesRelative[2])
            assertFalse(destination.createAccessibilityNodeInfo().isCheckable)
            navigation.dismiss()
        }
    }

    @Test
    fun aCustomFormatRemainsSelectedWhenOpeningAndCancellingItsEditor() {
        val pattern = "HH 'h' mm"
        WidgetSettingsStore.save(context, 0, false, WidgetSettings.defaults(false).apply { timeFormat = pattern })
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            DialogTestFixtures.layout(activity.window.decorView, 400, 850)
            EditorTestInteractions.tap(activity, "Time format")
            val picker = DialogTestFixtures.latest()
            assertEquals(picker.listView.count - 1, picker.listView.checkedItemPosition)
            val index = picker.listView.count - 1
            val custom = picker.listView.adapter.getView(index, null, picker.listView) as CheckedTextView
            assertTrue(custom.isChecked)
            assertTrue(custom.compoundDrawablesRelative[2].intrinsicWidth > 24)
            DialogTestFixtures.choose(picker, index)
            val editor = DialogTestFixtures.latest()
            assertNotSame(picker, editor)
            val input = requireNotNull(DialogTestFixtures.first(editor.window!!.decorView, EditText::class.java))
            assertEquals(pattern, input.text.toString())
            input.setText("HH:mm")
            editor.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            val session = activity.session
            assertEquals(pattern, session.settings.timeFormat)
        }
    }

    @Test
    fun aCustomAppActionIsSelectedWithoutChangingItWhenOpeningTheAppList() {
        val action = TapActions.appAction(ComponentName("example.player", "example.player.MainActivity"))
        WidgetSettingsStore.save(context, 0, false, WidgetSettings.defaults(false).apply { tapAction = action })
        Robolectric.buildActivity(MainActivity::class.java).setup().visible().use { controller ->
            val activity = controller.get()
            DialogTestFixtures.layout(activity.window.decorView, 400, 850)
            EditorTestInteractions.tap(activity, "Widget tap")
            val picker = DialogTestFixtures.latest()
            val index = picker.listView.count - 1
            assertEquals(index, picker.listView.checkedItemPosition)
            val custom = picker.listView.adapter.getView(index, null, picker.listView) as CheckedTextView
            assertTrue(custom.isChecked)
            DialogTestFixtures.choose(picker, index)
            val apps = DialogTestFixtures.latest()
            assertNotSame(picker, apps)
            val session = activity.session
            assertEquals(action, session.settings.tapAction)
            apps.dismiss()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun longListLabelsWrapWithinTheirIndicatorSpaceInBothLayoutDirections() {
        val configuration = Configuration(context.resources.configuration).apply { fontScale = 1.3f }
        val scaled = context.createConfigurationContext(configuration)
        for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
            val list = ListView(scaled)
            val adapter = AppDialogBuilder.ChoiceAdapter(scaled,
                arrayOf("Automatically use the appearance selected in the device settings"), true, 0)
            val row = adapter.getView(0, null, list) as CheckedTextView
            row.layoutDirection = direction
            row.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            row.layout(0, 0, row.measuredWidth, row.measuredHeight)
            assertTrue(row.layout.lineCount > 1)
            for (line in 0 until row.layout.lineCount) {
                assertEquals(0, row.layout.getEllipsisCount(line))
                assertTrue(row.layout.getLineRight(line) <= row.layout.width)
            }
            assertEquals(row.width, row.layout.width + row.compoundPaddingLeft + row.compoundPaddingRight)
            assertTrue(opaquePixels(requireNotNull(row.compoundDrawablesRelative[2])) > 0)
        }
    }

    private fun pixel(drawable: Drawable): Int {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 64, 64)
        drawable.draw(Canvas(bitmap))
        return bitmap.getPixel(32, 32)
    }

    @Test
    fun navigationChevronMirrorsItsRenderedDirectionInRtl() {
        fun render(direction: Int): Bitmap {
            val drawable = requireNotNull(context.getDrawable(R.drawable.ic_chevron_right)).mutate()
            assertTrue(drawable.isAutoMirrored)
            drawable.setLayoutDirection(direction)
            drawable.setBounds(0, 0, 24, 24)
            return Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).also { drawable.draw(Canvas(it)) }
        }
        val ltr = render(View.LAYOUT_DIRECTION_LTR)
        val rtl = render(View.LAYOUT_DIRECTION_RTL)
        var opaque = 0
        var changed = 0
        for (y in 0 until 24) for (x in 0 until 24) {
            val original = ltr.getPixel(x, y)
            if (original ushr 24 != 0) opaque++
            if (original != rtl.getPixel(x, y)) changed++
            assertEquals(original, rtl.getPixel(23 - x, y))
        }
        assertTrue(opaque > 0)
        assertTrue(changed > 0)
    }

    private fun opaquePixels(drawable: Drawable): Int {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 24, 24)
        drawable.draw(Canvas(bitmap))
        return (0 until 24).sumOf { y -> (0 until 24).count { x -> bitmap.getPixel(x, y) ushr 24 != 0 } }
    }
}

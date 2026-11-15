package app.noonlet

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import java.time.Duration
import java.util.ArrayList
import java.util.Arrays
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
@Config(sdk = [34, 36], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorPresentationTest {
    private lateinit var context: Context

    @Before
    fun prepare() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("noonlet_widgets", 0).edit().clear().commit()
        context.getSharedPreferences("MainActivity", 0).edit().clear().commit()
        AppLocales.setLanguage(context, "ru")
    }

    @Test
    @Throws(Exception::class)
    fun worldOverviewUsesCompactHeadingsAndDrawnIcons() {
        RuntimeEnvironment.setFontScale(1.3f)
        worldDraft("America/Los_Angeles", "Asia/Riyadh")
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    var root: View = layout(activity)
                    val settings = root.findViewWithTag<android.widget.ImageButton>("settings-trigger")
                    assertNotNull(settings)
                    assertEquals("Настройки", settings.contentDescription.toString())
                    assertNotNull(settings.drawable)
                    assertNull(root.findViewWithTag<View>("language-trigger"))
                    assertNull(root.findViewWithTag<View>("preview-toggle"))
                    assertNull(root.findViewWithTag<View>("preview-label"))
                    assertNotNull(root.findViewWithTag<View>("preview-sizes"))
                    assertEquals(
                        View.VISIBLE,
                        root.findViewWithTag<View>("preview-stage").getVisibility(),
                    )
                    assertSectionOrder(
                        root,
                        "cities",
                        "appearance",
                        "foreground",
                        "worldBackground",
                        "actions",
                    )
                    for (key: String in
                        arrayOf<String>(
                            "cities",
                            "appearance",
                            "foreground",
                            "worldBackground",
                            "actions",
                        )) {
                        val heading: ViewGroup =
                            root.findViewWithTag<ViewGroup>("section-toggle:" + key)
                        assertNotNull(heading)
                        assertTrue(
                            "World settings need a usable touch target",
                            heading.getHeight() >= 48,
                        )
                        assertTrue(
                            "Collapsed world groups must remain compact with larger text",
                            heading.getHeight() <= 56,
                        )
                        assertTrue(heading.getChildAt(heading.getChildCount() - 1) is ImageView)
                        val icon: ImageView =
                            heading.getChildAt(heading.getChildCount() - 1) as ImageView
                        assertNotNull(icon.getDrawable())
                        assertFalse(
                            "Navigation icons must not be raster substitutes",
                            icon.getDrawable() is BitmapDrawable,
                        )
                    }
                    assertEquals(0, root.findViewWithTag<ViewGroup>("section-content:cities").childCount)
                    WidgetTestViews.prepare(root)
                    revealAndClick(root, root.findViewWithTag<View>("section-toggle:cities"))
                    root = layout(activity)
                    assertEquals(
                        View.VISIBLE,
                        root.findViewWithTag<View>("section-content:cities").visibility,
                    )
                    assertCityControls(root, settings(activity).cities)
                    val addPlace: TextView? = text(root, "Добавить место")
                    assertNotNull(addPlace)
                    val addRow: ViewGroup = addPlace!!.getParent().getParent() as ViewGroup
                    val addIcon: ImageView =
                        addRow.getChildAt(addRow.getChildCount() - 1) as ImageView
                    IconAssertions.assertPlus(addIcon.drawable)
                    val actions: ImageButton = root.findViewWithTag<ImageButton>("city-drag:" + settings(activity).cities[0])
                    assertNotNull(actions)
                    assertNotNull(actions.getDrawable())
                    assertTrue(actions.createAccessibilityNodeInfo().actionList.any {
                        it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    })
                    assertNotNull(root.findViewWithTag<View>("city-remove:" + settings(activity).cities[0]))
                    assertTrue(requireNotNull(text(root, "Часы")).performClick())
                    assertSectionOrder(
                        layout(activity),
                        "time",
                        "date",
                        "weather",
                        "appearance",
                        "padding",
                        "alarm",
                        "actions",
                    )
                },
            )
    }

    @Test
    @Throws(Exception::class)
    fun placeControlsMoveAndRemoveTheSelectedCityAtEveryBoundary() {
        worldDraft("America/Los_Angeles", "Europe/London", "Asia/Riyadh", "Asia/Kathmandu")
        Robolectric.buildActivity(MainActivity::class.java)
            .setup()
            .use(
                { controller ->
                    val activity: MainActivity = controller.get()
                    var root: View = layout(activity)
                    revealAndClick(root, root.findViewWithTag<View>("section-toggle:cities"))
                    chooseCityAction(activity, 0, "Ниже")
                    assertArrayEquals(
                        arrayOf<String>(
                            "Europe/London",
                            "America/Los_Angeles",
                            "Asia/Riyadh",
                            "Asia/Kathmandu",
                        ),
                        settings(activity).cities,
                    )
                    root = layout(activity)
                    chooseCityAction(activity, 3, "Выше")
                    assertArrayEquals(
                        arrayOf<String>(
                            "Europe/London",
                            "America/Los_Angeles",
                            "Asia/Kathmandu",
                            "Asia/Riyadh",
                        ),
                        settings(activity).cities,
                    )
                    root = layout(activity)
                    chooseCityAction(activity, 1, "Удалить")
                    assertArrayEquals(
                        arrayOf<String>("Europe/London", "Asia/Kathmandu", "Asia/Riyadh"),
                        settings(activity).cities,
                    )
                    chooseCityAction(activity, 0, "Удалить")
                    chooseCityAction(activity, 0, "Удалить")
                    assertArrayEquals(arrayOf<String>("Asia/Riyadh"), settings(activity).cities)
                    root = layout(activity)
                    assertFalse(root.findViewWithTag<View>("city-remove:" + settings(activity).cities[0]).isEnabled)
                    assertCityControls(root, settings(activity).cities)
                },
            )
    }

    private fun worldDraft(vararg cities: String) {
        val settings: WidgetSettings = WidgetSettings.defaults(true)
        settings.cities = arrayOf(*cities)
        WidgetSettingsStore.save(context, 0, true, settings)
        context
            .getSharedPreferences("MainActivity", 0)
            .edit()
            .putBoolean("world_tab", true)
            .commit()
    }

    private fun assertCityControls(root: View, cities: Array<String>) {
        val section = root.findViewWithTag<ViewGroup>("section:cities")
        assertEquals(2, section.childCount)
        val fields = labels(root.findViewWithTag<View>("section-content:cities"))
        assertEquals(cities.size * 2 + 1, fields.size)
        for (index in cities.indices) {
            assertEquals(
                CityCatalog.name(context, cities[index], AppLocales.appLocale(context)),
                fields[index * 2],
            )
            assertTrue(fields[index * 2 + 1].matches(Regex("UTC[+-][0-9]{2}:[0-9]{2}")))
        }
        assertEquals("Добавить место", fields.last())
    }

    private fun labels(root: View): List<String> {
        val result = ArrayList<String>()
        if (root is TextView && root.text.isNotEmpty()) result.add(root.text.toString())
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) result.addAll(labels(root.getChildAt(index)))
        }
        return result
    }

    private fun chooseCityAction(activity: MainActivity, position: Int, action: String) {
        val root: View = layout(activity)
        val zone = settings(activity).cities[position]
        if (action == "Удалить") revealAndClick(root, root.findViewWithTag<View>("city-remove:$zone"))
        else {
            val handle = root.findViewWithTag<View>("city-drag:$zone")
            assertTrue(handle.performAccessibilityAction(
                if (action == "Выше") android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null))
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertSectionOrder(root: View, vararg expected: String) {
        val group = requireNotNull(root.findViewWithTag<View>("section:${expected.first()}"))
        val parent = group.parent as ViewGroup
        val actual =
            (0 until parent.childCount).mapNotNull { index ->
                (parent.getChildAt(index).tag as? String)?.takeIf { it.startsWith("section:") }
            }
        assertEquals(expected.map { "section:$it" }, actual)
    }

    @Throws(Exception::class)
    private fun settings(activity: MainActivity): WidgetSettings {
        return activity.session.settings
    }

    private fun revealAndClick(root: View, view: View) {
        assertNotNull(view)
        view.requestRectangleOnScreen(Rect(0, 0, view.getWidth(), view.getHeight()), true)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        val visible: Rect = Rect()
        assertTrue("The requested action must be reachable", view.getGlobalVisibleRect(visible))
        assertEquals(view.getHeight(), visible.height())
        assertTrue(view.performClick())
    }

    private fun layout(activity: MainActivity): View {
        val root: View = activity.getWindow().getDecorView()
        root.measure(
            View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 360, 640)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        WidgetTestViews.prepare(root)
        return root
    }

    private fun text(root: View, value: String): TextView? {
        if (root is TextView && value.contentEquals((root as TextView).getText()))
            return root as TextView
        if (root is ViewGroup)
            for (index: Int in 0 until (root as ViewGroup).getChildCount()) {
                val found: TextView? = text((root as ViewGroup).getChildAt(index), value)
                if (found != null) return found
            }
        return null
    }
}

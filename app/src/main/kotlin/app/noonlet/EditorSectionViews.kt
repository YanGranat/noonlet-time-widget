package app.noonlet

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.ArrayList

internal class EditorSectionViews(
    private val host: EditorHost,
    private val summary: (EditorSection) -> String,
    private val build: (LinearLayout, EditorSection, WidgetSettings) -> Unit,
) {
    private val ui get() = host.ui
    private val session get() = host.session
    private val CARD get() = ui.palette.surface
    private val TEXT get() = ui.palette.text
    private val MUTED get() = ui.palette.muted
    private val expandedClock = mutableSetOf<EditorSection>()
    private val expandedWorld = mutableSetOf<EditorSection>()
    private val sectionViews = linkedMapOf<EditorSection, SectionHandle>()
    private var populatingSection: EditorSection? = null

    fun restore(state: Bundle) {
        expandedClock.addAll(state.getStringArrayList("expanded_clock").orEmpty().mapNotNull(EditorSection::fromKey))
        expandedWorld.addAll(state.getStringArrayList("expanded_world").orEmpty().mapNotNull(EditorSection::fromKey))
    }

    fun save(out: Bundle) {
        out.putStringArrayList("expanded_clock", ArrayList(expandedClock.map { it.key }))
        out.putStringArrayList("expanded_world", ArrayList(expandedWorld.map { it.key }))
    }

    fun clear() = sectionViews.clear()
    fun keys() = sectionViews.keys.toTypedArray()
    fun isExpanded(key: EditorSection) = key in if (session.world) expandedWorld else expandedClock
    fun markBuilt(key: EditorSection) { sectionViews[key]?.built = true }

    fun create(parent: LinearLayout, key: EditorSection): LinearLayout {
        if (populatingSection == key) return checkNotNull(sectionViews[key]).content
        val card = ui.vertical()
        val title = ui.tr(key.titleResource)
        val group = ui.vertical()
        group.tag = "section:" + key.key
        group.background = ui.shape(CARD, 16f)
        group.clipToOutline = true
        val heading = ui.horizontal()
        heading.tag = "section-toggle:" + key.key
        val hasSummary = !session.world
        val verticalPadding = if (hasSummary) 12 else 10
        heading.setPadding(
            ui.dp(16f),
            ui.dp(verticalPadding.toFloat()),
            ui.dp(16f),
            ui.dp(verticalPadding.toFloat()),
        )
        heading.minimumHeight = ui.dp((if (hasSummary) 64 else 52).toFloat())
        val labels = ui.vertical()
        val name = ui.text(title, (if (session.world) 15 else 16).toFloat(), TEXT)
        name.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        labels.addView(name)
        val summary = if (hasSummary) ui.text(summary(key), 12f, MUTED) else null
        if (summary != null) {
            summary.maxLines = 2
            summary.ellipsize = android.text.TextUtils.TruncateAt.END
            val summaryParams = LinearLayout.LayoutParams(-1, -2)
            summaryParams.topMargin = ui.dp(4f)
            labels.addView(summary, summaryParams)
        }
        heading.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val expanded = if (session.world) expandedWorld else expandedClock
        val expandedInitially = expanded.contains(key)
        val arrow =
            ui.imageIcon(
                if (expandedInitially) AppIcons.Kind.EXPAND_LESS else AppIcons.Kind.EXPAND_MORE,
                MUTED,
            )
        heading.addView(arrow, LinearLayout.LayoutParams(ui.dp(24f), ui.dp(24f)))
        heading.background = ui.sectionHeaderBackground(expandedInitially)
        heading.isFocusable = true
        heading.contentDescription = description(title, summary(key), expandedInitially)
        if (android.os.Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true)
        if (android.os.Build.VERSION.SDK_INT >= 30)
            heading.setStateDescription(
                if (expanded.contains(key)) ui.tr(R.string.expanded) else ui.tr(R.string.collapsed),
            )
        card.tag = "section-content:" + key.key
        card.visibility = if (expanded.contains(key)) View.VISIBLE else View.GONE
        heading.setOnClickListener(
            { v ->
                val open = card.visibility != View.VISIBLE
                if (open) expanded.add(key) else expanded.remove(key)
                if (open && sectionViews[key]?.built == false) {
                    populatingSection = key
                    try { build(parent, key, WidgetSettings.defaults(session.world)) }
                    finally { populatingSection = null }
                }
                card.visibility = if (open) View.VISIBLE else View.GONE
                arrow.setImageDrawable(
                    AppIcons.create(
                        host.activity,
                        if (open) AppIcons.Kind.EXPAND_LESS else AppIcons.Kind.EXPAND_MORE,
                        MUTED,
                    ),
                )
                heading.background = ui.sectionHeaderBackground(open)
                if (summary != null) summary.setText(summary(key))
                heading.contentDescription = description(title, summary(key), open)
                if (android.os.Build.VERSION.SDK_INT >= 30)
                    heading.setStateDescription(
                        if (open) ui.tr(R.string.expanded) else ui.tr(R.string.collapsed),
                    )
            },
        )
        group.addView(heading)
        group.addView(card)
        val params = LinearLayout.LayoutParams(-1, -2)
        val firstSection = if (session.world) EditorSection.CITIES else EditorSection.TIME
        val topGap = if (session.world) 6 else 8
        params.topMargin = ui.dp((if (key == firstSection) topGap / 2 else topGap).toFloat())
        parent.addView(group, params)
        sectionViews[key] = SectionHandle(group, heading, card, summary, title)
        return card
    }

    fun replace(vararg keys: EditorSection?) {
        val updated = mutableSetOf<EditorSection>()
        val defaults = WidgetSettings.defaults(session.world)
        for (key in keys) {
            if (key == null || !updated.add(key)) continue
            val previous = sectionViews[key]?.group ?: continue
            val parent = previous.parent as? ViewGroup ?: continue
            val index = parent.indexOfChild(previous)
            val layout = previous.layoutParams
            val staging = ui.vertical()
            build(staging, key, defaults)
            val replacement = sectionViews[key]?.group
            if (replacement == null || replacement.parent !== staging) continue
            staging.removeView(replacement)
            parent.removeView(previous)
            parent.addView(replacement, index, layout)
        }
    }

    fun keyFor(view: View): EditorSection? {
        var candidate: View? = view
        while (candidate != null) {
            sectionViews.entries.firstOrNull { it.value.content === candidate }?.let {
                return it.key
            }
            candidate = candidate.parent as? View
        }
        return null
    }

    fun refreshSummary(key: EditorSection?) {
        if (key == null) return
        val section = sectionViews[key] ?: return
        val current = summary(key)
        section.summary?.let { value ->
            if (!current.contentEquals(value.text)) value.setText(current)
        }
        val open = section.content.visibility == View.VISIBLE
        section.heading.contentDescription = description(section.title, current, open)
    }

    private fun description(title: String, current: String, open: Boolean): String {
        val label = if (current.isEmpty()) title else "$title, $current"
        return label + if (android.os.Build.VERSION.SDK_INT < 30)
            ", " + ui.tr(if (open) R.string.expanded else R.string.collapsed) else ""
    }

    private class SectionHandle(
        val group: LinearLayout,
        val heading: LinearLayout,
        val content: LinearLayout,
        val summary: TextView?,
        val title: String,
    ) {
        var built = false
    }

}

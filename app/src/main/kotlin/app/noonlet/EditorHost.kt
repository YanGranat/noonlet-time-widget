package app.noonlet

import android.app.Activity
import android.view.View
import android.widget.LinearLayout

internal interface EditorHost {
    val activity: Activity
    val session: EditorSession
    val ui: EditorUi
    val placePicker: PlacePickerDialog
    val preview: EditorPreview
    fun section(parent: LinearLayout, key: EditorSection): LinearLayout
    fun settingsSectionKey(view: View): EditorSection?
    fun refreshSettingsSections(vararg keys: EditorSection?)
    fun refreshSectionSummary(key: EditorSection?)
    fun resetAppearance()
}

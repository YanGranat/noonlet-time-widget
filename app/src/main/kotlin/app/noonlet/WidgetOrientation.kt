package app.noonlet

enum class WidgetOrientation(val wireValue: String) {
    VERTICAL("vertical"), HORIZONTAL("horizontal");

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wireValue == value } ?: VERTICAL
    }
}

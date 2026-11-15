package app.noonlet

enum class WidgetAlignment(val wireValue: String) {
    LEFT("left"), CENTER("center"), RIGHT("right");

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wireValue == value } ?: LEFT
    }
}

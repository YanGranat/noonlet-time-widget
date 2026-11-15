package app.noonlet

internal enum class WidgetSize(
    val width: Int,
    val height: Int,
    val columns: Int,
    val rows: Int,
    val clockProvider: Class<out BaseWidgetProvider>,
    val worldProvider: Class<out BaseWidgetProvider>,
) {
    TWO_BY_ONE(167, 82, 2, 1, ClockWidgetProvider::class.java, WorldWidgetProvider::class.java),
    FOUR_BY_ONE(328, 82, 4, 1, WideClockWidgetProvider::class.java, WideWorldWidgetProvider::class.java),
    TWO_BY_TWO(167, 174, 2, 2, SquareClockWidgetProvider::class.java, SquareWorldWidgetProvider::class.java),
    FOUR_BY_TWO(328, 174, 4, 2, LargeClockWidgetProvider::class.java, LargeWorldWidgetProvider::class.java);

    val label: String get() = "$columns×$rows"
}

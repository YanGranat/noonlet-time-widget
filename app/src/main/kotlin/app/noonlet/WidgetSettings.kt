package app.noonlet

import android.graphics.Color
import java.time.ZoneId

class WidgetSettings {
    @JvmField var zoneId = ""
    @JvmField var style = WorldClockStyle.AUTO
    @JvmField var alignment = WidgetAlignment.LEFT
    @JvmField var textSize = 56
    @JvmField var opacity = 100
    @JvmField var padding = 0
    @JvmField var showDate = true
    @JvmField var hour24 = true
    @JvmField var outline = true
    /** Stable place selections; existing IANA zone IDs remain valid selections. */
    @JvmField var cities = arrayOf("Europe/London")
    @JvmField var fontFamily = "sans-serif-medium"
    @JvmField var orientation = WidgetOrientation.VERTICAL
    @JvmField var timeFormat = "HH:mm"
    @JvmField var dateFormat = "EEE, d MMM"
    @JvmField var alarmFormat = "HH:mm"
    @JvmField var dateAlignment = WidgetAlignment.LEFT
    @JvmField var alarmAlignment = WidgetAlignment.CENTER
    @JvmField var tapAction = "clock"
    @JvmField var timeTapAction = "clock"
    @JvmField var dateTapAction = "calendar"
    @JvmField var alarmTapAction = "clock"
    @JvmField var showAlarm = false
    @JvmField var showNoAlarm = false
    @JvmField var showAlarmIcon = true
    @JvmField var advancedTap = false
    @JvmField var timeColor = Color.WHITE
    @JvmField var dateColor = Color.WHITE
    @JvmField var alarmColor = Color.WHITE
    @JvmField var dateSize = 16
    @JvmField var alarmSize = 14
    @JvmField var paddingTop = 0
    @JvmField var paddingBottom = 0
    @JvmField var paddingLeft = 0
    @JvmField var paddingRight = 0
    @JvmField var timeOffsetX = 0
    @JvmField var timeOffsetY = 0
    @JvmField var dateOffsetX = 4
    @JvmField var dateOffsetY = -4
    @JvmField var alarmOffsetX = -4
    @JvmField var alarmOffsetY = -24
    @JvmField var timeDateGap = 0
    @JvmField var fontWeight = WidgetFont.MEDIUM_WEIGHT
    @JvmField var timeFontWeight = WidgetFont.BOLD_WEIGHT
    @JvmField var dateFontWeight = WidgetFont.MEDIUM_WEIGHT
    @JvmField var worldDayTextColor = 0xff292929.toInt()
    @JvmField var worldNightTextColor = 0xffc6c6c6.toInt()
    @JvmField var worldDayBackgroundColor = Color.WHITE
    @JvmField var worldNightBackgroundColor = 0xff111111.toInt()
    @JvmField var worldBackdropColor = 0x66000000
    @JvmField var worldCardCornerScale = 100
    @JvmField var worldBackdropCornerScale = 100
    @JvmField var weatherEnabled = false
    @JvmField var weatherUseDevice = true
    @JvmField var weatherPlace = ""
    @JvmField var weatherFontFamily = "sans-serif-medium"
    @JvmField var weatherFontWeight = WidgetFont.MEDIUM_WEIGHT
    @JvmField var weatherColor = Color.WHITE
    @JvmField var weatherTextSize = 13
    @JvmField var weatherIconSize = 52
    @JvmField var weatherIconGap = 0
    @JvmField var weatherTextFormat = WeatherTextFormat.CONDITION_TEMPERATURE
    @JvmField var weatherOffsetX = 0
    @JvmField var weatherOffsetY = 0

    fun effectiveZone(): String {
        val systemZone = ZoneId.systemDefault().id
        return CityCatalog.timeZoneId(
            if (CityCatalog.isValidSelection(zoneId)) zoneId else systemZone,
        )
    }

    companion object {
        const val MAX_TIME_SIZE = 65
        const val MAX_DATE_SIZE = 35
        const val MAX_WORLD_CORNER_SCALE = 200

        fun defaults(world: Boolean) =
            WidgetSettings().apply {
                if (world) {
                    fontFamily = "sans-serif-bold"
                    fontWeight = WidgetFont.BOLD_WEIGHT
                    timeFontWeight = WidgetFont.BOLD_WEIGHT
                    tapAction = "settings"
                }
            }
    }
}

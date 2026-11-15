package app.noonlet

import java.lang.reflect.Field
import java.lang.reflect.Modifier
import org.junit.Assert.*

object SettingsTestFixtures {
    fun sample(): WidgetSettings {
        val s: WidgetSettings = WidgetSettings()
        s.zoneId = "Asia/Kathmandu"
        s.style = WorldClockStyle.DARK
        s.alignment = WidgetAlignment.RIGHT
        s.textSize = 47
        s.opacity = 62
        s.padding = 7
        s.showDate = false
        s.hour24 = false
        s.outline = false
        s.cities = arrayOf<String>("Europe/London", "Asia/Kathmandu")
        s.fontFamily = "serif"
        s.orientation = WidgetOrientation.HORIZONTAL
        s.timeFormat = "HH:mm:ss"
        s.dateFormat = "dd.MM.yyyy"
        s.alarmFormat = "HH:mm"
        s.dateAlignment = WidgetAlignment.CENTER
        s.alarmAlignment = WidgetAlignment.LEFT
        s.tapAction = "settings"
        s.timeTapAction = "calendar"
        s.dateTapAction = "none"
        s.alarmTapAction = "clock"
        s.showAlarm = true
        s.showNoAlarm = true
        s.showAlarmIcon = false
        s.advancedTap = true
        s.timeColor = -0xed54cc
        s.dateColor = -0xbadc33
        s.alarmColor = -0x6745df
        s.dateSize = 19
        s.alarmSize = 21
        s.paddingTop = 3
        s.paddingBottom = 8
        s.paddingLeft = 12
        s.paddingRight = 17
        s.timeOffsetX = 11
        s.timeOffsetY = -7
        s.dateOffsetX = -8
        s.dateOffsetY = 13
        s.alarmOffsetX = -9
        s.alarmOffsetY = 6
        s.timeDateGap = 9
        s.fontWeight = 700
        s.timeFontWeight = 900
        s.dateFontWeight = 650
        s.worldDayTextColor = -0xeeddcd
        s.worldNightTextColor = -0x554434
        s.worldDayBackgroundColor = -0xbbaa9a
        s.worldNightBackgroundColor = -0x221101
        s.worldBackdropColor = 0x7f234567
        s.worldCardCornerScale = 50
        s.worldBackdropCornerScale = 150
        s.weatherFontFamily = "serif"
        s.weatherFontWeight = 600
        s.weatherColor = 0xffabcdef.toInt()
        s.weatherTextSize = 19
        s.weatherIconSize = 62
        s.weatherIconGap = 7
        s.weatherTextFormat = WeatherTextFormat.TEMPERATURE_CONDITION
        s.weatherOffsetX = 12
        s.weatherOffsetY = -8
        return s
    }

    @Throws(Exception::class)
    fun assertSettings(expected: WidgetSettings, actual: WidgetSettings) {
        for (field: Field in WidgetSettings::class.java.getFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue
            if (field.getType() === Array<String>::class.java)
                assertArrayEquals(
                    field.getName(),
                    field.get(expected) as Array<String>,
                    field.get(actual) as Array<String>,
                )
            else assertEquals(field.getName(), field.get(expected), field.get(actual))
        }
    }
}

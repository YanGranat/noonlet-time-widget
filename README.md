# Noonlet

Home-screen clock widgets for Android 8.0 and later.

[Download APK](https://github.com/YanGranat/noonlet-time-widget/releases/download/v1.4.39/Noonlet-1.4.39.apk)

- **Clock:** time, date, the next system alarm and optional weather, with configurable typography, colors, spacing and tap actions.
- **World clock:** up to four cities or single-timezone countries, with day/night colors and an adjustable rounded backdrop.

![Noonlet clock and weather widgets, customization, and world time](docs/preview.png)

[More screenshots](docs/screenshots/)

Each widget has its own settings. Clocks tick inside the launcher using Android `TextClock`, without exact alarms. Optional weather uses Open-Meteo's non-commercial API and either the device location or a manually selected place. Clock and world-clock features work offline.

## Use

Install the APK, open your home screen's **Widgets** menu, and choose **Noonlet**. Settings apply automatically after placement. **Save changes** also saves and refreshes the selected widget, then returns to the home screen. Adding a widget from the app returns home after the launcher confirms placement. By default, clock taps open the system alarm screen and world-clock taps open widget settings.

The editor offers 2 × 1, 4 × 1, 2 × 2 and 4 × 2 layouts. The selected layout sets the initial size requested when adding from the app. The home screen controls final placement and resizing.

Export or import app and widget settings from **Settings**. Imported widgets that are not placed yet appear in **My widgets**.

Weekday and month names in widgets follow the phone's language. Weather, day/night colors and time-difference labels refresh periodically and may be delayed by battery-saving modes. Device location is checked when you open Noonlet or refresh weather; while closed, weather uses the last detected place. A manual location works without location permission.

## Build

Requirements: JDK 21, Android SDK 36, and `ANDROID_HOME` or a local `local.properties` containing the SDK path.

```sh
./gradlew :app:assembleRelease
./gradlew :app:testDebugUnitTest :app:lintRelease
```

On Windows, use `.\gradlew.bat`. Release APKs are written to `app/build/outputs/apk/release/`.

For signing, create `~/.android/noonlet-signing.properties` outside the repository:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

Without this file, the release APK is unsigned. Keep signing keys private and retain the same key for future updates.

## Development

The app uses Kotlin and Android platform APIs. JUnit and Robolectric test widget layouts, settings, lifecycle, locales and launcher interactions.

To regenerate widget-picker artwork, install `fonttools==4.60.1` in an external Python environment and run:

```sh
python tools/generate_widget_picker_previews.py
```

Add `--check` to verify the checked-in artwork.

## License and privacy

[MIT](LICENSE).

See the [privacy policy](PRIVACY.md) for local storage, location and weather requests.

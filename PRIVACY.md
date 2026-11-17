# Privacy policy

Last updated: November 17, 2026

Noonlet stores app preferences and widget settings in Android app storage. It has no user accounts,
advertising, analytics or crash-reporting SDKs, and no application backend.
Clock and world-clock features work without an internet connection.

## Optional weather

Weather is disabled by default. When enabled, forecasts are requested directly
from Open-Meteo over HTTPS using the coordinates of the selected place.

- **Manual location:** the selected place's name and coordinates are saved in
  widget settings. Device location permission is not required.
- **Device location:** with your permission, Noonlet can obtain an approximate
  location while the app is open. While the app is closed, weather refreshes use
  the last detected place; they do not obtain a new device location. Revoking
  location permission stops these refreshes until access is granted again or
  a manual location is selected.
- **Place search:** searching for a weather location sends the search text to
  Open-Meteo's geocoding service before you select a result. This service uses
  GeoNames location data.

Open-Meteo receives your IP address and the request details, including weather
coordinates or place-search text. Its free API privacy policy states that server
logs may contain geographical coordinates and are deleted after 90 days.
See [Open-Meteo's privacy policy](https://open-meteo.com/en/terms).
Noonlet does not operate the provider's servers or control their logs.

## Local storage and settings files

Widget settings include appearance, time zones, tap-action choices and any
manually selected weather place. Automatic Android backup and device transfer
are disabled. Uninstalling Noonlet removes its local settings and cache.

Export settings saves app and widget configurations to a file you choose.
Import settings applies that file after confirmation. Exported files remain
where you save them until you delete them.

The last detected device location and cached weather are stored separately in
Android's no-backup storage. They are excluded from standard Android app backup.
The weather cache includes coordinates for up to 16 places. Forecasts older than
six hours are not used; expired entries are removed when the cache is updated.
Turning weather off, changing location permission or resetting appearance does
not automatically erase previously saved data.

The app list used to choose tap actions and system-alarm information are used
locally and are not sent with weather or location-search requests.

## Your controls

- Choose a manual weather location to use weather without device location access.
- Change or revoke location permission in Android settings.
- Turn weather off for a widget to stop its automatic forecast requests.
- Clear cached forecasts in Settings → Data and privacy. This keeps the last
  detected device location and manually selected places.
- Forget the last detected device location in the same section. This also clears
  cached forecasts; manually selected places and widget appearance are preserved.
  Weather can obtain a new device location the next time the app requests one
  while open and permission is granted.
- Clear Noonlet's storage in Android settings or uninstall it to remove local app
  data. Exported files and the weather provider's logs have separate retention
  and are not erased by clearing app storage.

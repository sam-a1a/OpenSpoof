# OpenSpoof

A small Android app for setting a mock GPS location. Pick a point on an
OpenStreetMap map, tap **Set location**, and the device reports that position to
every app that asks for one.

Release APK is **~2.2 MB**.

## Using it

Android will not let any app fake a position until you nominate it by hand:

1. Open **Settings → Developer options → Select mock location app**
2. Choose **OpenSpoof**

The app detects whether this has been done and offers to open the right screen
if it hasn't. If Developer options aren't visible, open **Settings → About
phone** and tap the build number seven times.

Once running, the spoof continues in the background via a foreground service
until you stop it from the app or its notification. Location is published to the
`gps`, `network` and `fused` providers, because most apps read the fused one.

## Requirements

- Android 13 (API 33) or newer
- Location and notification permission, which Android requires before a
  location-typed foreground service may start

## Building

```
./gradlew assembleRelease
```

## How it is kept small

- Two dependencies: `compose-material3` and `activity-compose`. Networking is
  `HttpURLConnection` and JSON is `org.json`, both already in the framework.
- The map is drawn directly onto a Compose `Canvas` rather than pulling in
  osmdroid, which is archived, is View-based so it cannot take part in Compose
  animation, and would add roughly 1.5 MB.
- Icons are hand-written vector drawables (~4 KB total) instead of
  `material-icons-core`.
- No test or `debugImplementation` configurations, no Compose BOM, no
  `ui-tooling`.
- R8 code and resource shrinking, one packaged locale, unused build features off.

Material 3 Expressive components live in the `material3` 1.5.0 alpha line;
1.4.0 is the newest stable release but predates `MaterialExpressiveTheme` and
most of the expressive components, so the app tracks the alpha.

## Attribution and fair use

Map data and tiles © [OpenStreetMap](https://www.openstreetmap.org/copyright)
contributors, used under the ODbL.

The app is written to respect the OpenStreetMap
[tile](https://operations.osmfoundation.org/policies/tiles/) and
[Nominatim](https://operations.osmfoundation.org/policies/nominatim/) usage
policies:

- A User-Agent identifying the app, with a contact URL
- Tiles cached per their HTTP `max-age`, never below the required 7 days
- Only tiles actually on screen are fetched; queued downloads are abandoned
  once the camera moves off them, and there is no prefetching or area seeding
- Place search runs on submit only, never as you type, since Nominatim forbids
  autocomplete, and is rate limited to below one request per second with
  results cached

If you fork this and ship it, change the User-Agent strings in `TileStore.kt`
and `Nominatim.kt` to identify your build.

## Note

Mock locations are a developer feature. Plenty of apps detect them, either via
`Location.isMock()` or by noticing that the position disagrees with the network,
so this will not fool everything.

# SolarMonitor (Android)

Native app for the ESP32-C3 solar monitor (firmware + web dashboard live in
`C:\Users\Yasir\Desktop\Solar App ESP 32 C3 mini Project`, see its README for the HTTP API).

## Stack
- Kotlin + Jetpack Compose + Material 3 (dynamic colour, edge-to-edge, splash), no extra libraries:
  HTTP/SSE via HttpURLConnection, JSON via org.json, charts and power-flow drawn on Compose Canvas.
- Same build setup as MyApp: AGP 9.4, Gradle 9.8, compileSdk 37 / targetSdk 36 / minSdk 26, aapt2 override in gradle.properties.

## Structure
- `data/Repository` - one connection: `/events` (fast, ~0.6 s) while UI is visible, `/events/status` (quiet) when only
  `MonitorService` runs. Requests are bound to the Wi-Fi network. `Api.stream` must send `Accept: text/event-stream`.
- `notify/Alerts` - grid off/on, battery low/full, inverter faults/not answering. `MonitorService` = connectedDevice FGS
  with ongoing status notification; `BootReceiver` restarts it.
- `ui/components/PowerFlow` mirrors the web dashboard geometry (400x292 design space).

## Build
`assembleRelease` -> `app\build\outputs\apk\release\app-release.apk` (debug-signed). The emulator can reach the real device on the LAN through the host.

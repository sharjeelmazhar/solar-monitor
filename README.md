# Solar Monitor

Real-time, local-first monitoring for off-grid/hybrid solar inverters. An ESP32-C3 reads the inverter over its
RS232 port and pushes every change to a web dashboard and a native Android app within about a second. No cloud is
involved, so it keeps working when the internet is down. (The official cloud app for the first supported inverter
showed a grid cut about 5 minutes late in a side-by-side test.)

> Work in progress. The full README (architecture diagram, wiring, setup, screenshots) arrives with milestone 1.

## Repository layout

| Path | What it is |
|---|---|
| `firmware/solar_monitor_v3/` | ESP32-C3 firmware (Arduino). Reads the inverter, serves REST + live streams, keeps a small history buffer, hosts the web app. |
| `web/` | Web dashboard: React + Vite + TypeScript, Tailwind, Base UI, Motion, three.js (lazy 3D). Stored on the ESP32's flash and served at `http://solar.local/`. |
| `firmware/embedded-web/` | Small dashboard built into the firmware; fallback at `/classic`. Packed by `firmware/build_web.ps1`. |
| `firmware/test/` | Host-side unit tests for the inverter protocol (CRC, parsing, malformed replies). |
| `firmware/legacy/`, `firmware/tools/` | Earlier firmware and wiring/debug sketches. |
| `android/` | Native Android app (Kotlin, Jetpack Compose, Material 3). |
| `docs/` | Documentation, including the audit of the official inverter app. |

## Supported inverters

- **Voltronic-based (PI30 protocol)** over RS232, e.g. Inverex Veyron. Others (Growatt, Deye, Must, Knox, ...) are planned
  behind a common driver interface.

## Hardware (short version)

ESP32-C3 Super Mini + MAX3232 RS232↔TTL module. The inverter's RJ45 jack is a serial port (RS232), not Ethernet:
RJ45 pin 1 = inverter TX, pin 2 = inverter RX, pin 8 = GND. Never wire RS232 straight to the ESP32 (±12 V will damage it).

| MAX3232 | ESP32-C3 |
|---|---|
| VCC | 3V3 |
| GND | GND |
| TXD | GPIO10 (RX) |
| RXD | GPIO20 (TX) |

## Building the firmware

1. Copy `firmware/solar_monitor_v3/secrets.example.h` to `secrets.h` and set your own passwords (that file is git-ignored).
2. Arduino IDE or arduino-cli: board **ESP32C3 Dev Module**, USB CDC On Boot **Enabled**, partition **Default 4MB with spiffs**,
   libraries **ESP Async WebServer** and **Async TCP** (ESP32Async).
3. After editing the built-in dashboard, run `firmware/build_web.ps1`.
4. Later updates go over Wi-Fi: `curl -u admin:<your-admin-password> -F "fw=@solar_monitor_v3.ino.bin" http://solar.local/update`.

## Web dashboard

```bash
cd web
npm install
npm run dev      # http://localhost:5173, proxied to the monitor (set DEVICE_URL in web/.env.local)
npm test
npm run deploy   # builds, gzips and uploads to the monitor's flash (needs ADMIN_PASS in web/.env.local)
```

`web/.env.local` (git-ignored):

```
DEVICE_URL=http://solar.local
ADMIN_PASS=<your admin password from secrets.h>
```

Pages: Live (energy flow with direction-aware particles, optional 3D core), History (any day, CSV export),
Energy (month / 7 / 30 days / year / custom range, solar units and savings), Outages (load-shedding log,
hour-of-day heat map, timeline), System (inverter settings explained, device status, your settings, theme).

## Device API

| Endpoint | Returns |
|---|---|
| `GET /events` | Server-Sent Events, event `live`, pushed on every change (heartbeat ≤ 5 s). Send `Accept: text/event-stream`. |
| `GET /events/status` | Same JSON, only on mode / grid / battery % / charge direction / warning changes + 60 s heartbeat (phones in background). |
| `GET /api/live` | Latest reading and today's totals (JSON). |
| `GET /api/info` | Device, Wi-Fi, storage, settings, raw inverter ratings (`QPIRI`, `QID`, `QVFW`, `QFLAG`). |
| `GET /api/recent` | Binary, 16 B per sample: u32 t, u16 ms, u16 pvW, loadW, gridW, i16 battW, u8 battPct, u8 flags. |
| `GET /api/day?d=YYYYMMDD` | Binary, 24 B per minute: u32 t, u16 pvW, loadW, gridW, i16 battW, u16 battV×100, pvV×10, gridV×10, outV×10, u8 battPct, i8 tempC, u8 mode, u8 flags. |
| `GET /api/www`, `POST /api/www?path=…`, `POST /api/www/delete?path=…` | List / upload (admin) / delete (admin) web app files in flash. |
| `GET /api/days` | Binary, 40 B per day: u32 date, f32 pvWh, loadWh, gridWh, chgWh, disWh, u16 pvPeak, loadPeak, gridOnMin, onlineMin, u8 battMin, battMax, i8 tempMax, u8 outages, u32 reserved. |
| `POST /api/settings` | `battAh`, `tariff`, `name`, `tz`. |
| `POST /api/time` | `t` = epoch seconds (only used when NTP isn't reachable). |

Binary data is little-endian. Flags: bit0 grid present, bit1 solar charging, bit2 grid charging, bit3 load on.

## License

To be decided before the first release.

# Official app audit: WatchPower (Voltronic / Eybond)

Read-only audit of the app currently used with the inverter, to make sure our app covers everything it shows.
Captured on 2026-10-07 from a Huawei P40 lite (EMUI 12, Android 10), with WatchPower (`wifiapp.volfw.watchpower`)
running inside GBox. Nothing was changed, saved or toggled; only screens were opened, scrolled and read.
Raw screenshots and UI dumps are in `research/official-app/` (git-ignored, contains personal identifiers).

## How it gets data

The inverter has a plug-in Wi-Fi data logger (WatchPower "Wi-Fi Module", firmware 3.1.0.1) that uploads to the
vendor cloud. The app reads the cloud, not the inverter. Its History tab holds about 140 snapshots per day (one every
~5-10 minutes), which explains the lag users see.

**Measured lag (2026-10-07):** grid was switched off at the breaker by 12:38:03. The ESP32 monitor reported it within
1 s. WatchPower still showed "Line Mode, 220.5 V" until 12:42:51, about **4 min 48 s** later.

## Screens and fields

### Live diagram (top of device page)
Grid V and Hz, mode text ("Battery Mode", "Line Mode"), load V / W / %, PV V / W, battery V / %.
Animated dots along the lines, but no direction or magnitude encoding.

### Basic Information

| Field | Value seen | Unit | Our source |
|---|---|---|---|
| Grid Voltage | 0.0 | V | QPIGS f0 ✔ |
| Grid Frequency | 0.0 | Hz | QPIGS f1 ✔ |
| PV1 Input Voltage | 395.2 | V | QPIGS f13 ✔ |
| PV2 Input voltage | 0.0 | V | **QPIGS2: not read yet** |
| PV1 Charging Power | 729 | W | QPIGS f19 ✔ |
| PV2 Charging power | 0 | W | **QPIGS2: not read yet** |
| Battery Voltage | 27.5 | V | QPIGS f8 ✔ |
| Battery Capacity | 100 | % | QPIGS f10 ✔ |
| Battery Charging Current | 0 | A | QPIGS f9 ✔ |
| Battery Discharge Current | 3 | A | QPIGS f15 ✔ |
| AC Output Voltage | 230.2 | V | QPIGS f2 ✔ |
| AC Output Frequency | 50.0 | Hz | QPIGS f3 ✔ |
| AC Output Apparent Power | 782 | VA | QPIGS f4 ✔ |
| AC Output Active Power | 766 | W | QPIGS f5 ✔ |
| Output Load Percent | 24 | % | QPIGS f6 ✔ |

### Product Information

| Field | Value | Our source |
|---|---|---|
| Machine Type | Off Grid | QPIRI f19 = 01 ✔ |
| Main CPU Firmware Version | 00052.03 | QVFW ✔ |
| Secondary CPU Firmware Version | 00026.04 | **QVFW2: not read yet** |

### Rated Information

| Field | Value | Our source |
|---|---|---|
| Grid Rating Voltage | 230.0 V | QPIRI f0 ✔ |
| Grid Rating Current | 13.9 A | QPIRI f1 ✔ |
| Battery Rating Voltage | 24.0 V | QPIRI f7 ✔ |
| AC Output Rating Voltage | 230.0 V | QPIRI f2 ✔ |
| AC Output Rating Current | 13.9 A | QPIRI f4 ✔ |
| AC Output Rating Frequency | 50.0 Hz | QPIRI f3 ✔ |
| AC Output Rating Apparent Power | 3200 VA | QPIRI f5 ✔ |
| AC Output Rating Active Power | 3200 W | QPIRI f6 ✔ |

### History
Snapshot list per day (timestamp + all Basic fields), about 140 entries per day. Our monitor stores one record per
minute (and the logger will store every reading), so this is covered and improved.

### Parameter Setting (groups only, not opened)
Output Setting · Battery Parameters Setting · Enable/Disable Items · LED Setting · Restore to the defaults ·
Time zone setting · Wi-Fi Module configuration.

The sub-screens were **not opened** (owner's choice; the audit focus moved to building our own app). The values they
would show are readable directly from the inverter, without risk, using these commands:

| Group | Parameters | Read with | Current values |
|---|---|---|---|
| Output | Output source priority, AC input range, output V/Hz | QPIRI f16, f15, f2, f3 | Solar first (SUB), Appliance, 230 V, 50 Hz |
| Battery | Battery type, bulk (CV), float, cut-off, back-to-grid, back-to-battery, max charge current, max AC charge current, charger priority | QPIRI f12, f10, f11, f9, f8, f22, f14, f13, f17 | User, 27.8 V, 27.5 V, 22.1 V, 25.5 V, 26.5 V, 50 A, 10 A, Solar first |
| Enable/Disable | Buzzer, overload bypass, power saving, LCD return, overload restart, over-temp restart, backlight, beep on grid loss, fault record | QFLAG | Enabled: buzzer, LCD return, backlight, beep on grid loss, fault record. Disabled: bypass, power saving, overload restart, over-temp restart |
| LED | RGB LED ring settings | model-specific (not yet identified) | – |
| Time zone / Wi-Fi module | Cloud logger settings | not applicable to our device | – |

Allowed ranges for writing settings will come from the PI30 protocol documentation for this inverter series and
will be validated before any write (see the settings rules in the project plan). No setting has been written.

### Overview / Me tabs
Device count by status (Normal / Offline / Alarm / Fault), "Current Power" and "Today Power" (kWh) with a 24 h PV
chart, account page, Wi-Fi config, message push. The device showed status **Alarm** (1 alarm), most likely the
"grid not available" warning bit that the inverter raises during load-shedding.

## Gaps to close in our app
1. Read PV2 input voltage and power (`QPIGS2`) for dual-MPPT models.
2. Read the secondary CPU firmware (`QVFW2`).
3. Identify the LED-setting command for this model before exposing it.

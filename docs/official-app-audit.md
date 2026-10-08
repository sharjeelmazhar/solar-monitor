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

### Parameter Setting (opened read-only on 8 Oct 2026)
Every sub-screen was opened, scrolled and closed. Nothing was set or queried (the **Setting** / **Query** buttons
were never tapped). Captures: `research/official-app/wp2/` (PNG + UI dump; ✔ = option WatchPower shows as current).
Each screen is a list of options (or one number field) with **Setting** and **Query** buttons at the bottom.

**Output Setting**

| Item | Options in WatchPower | Current | PI30 write | Read back |
|---|---|---|---|---|
| Output Source Priority | Utility Solar Bat / Solar Utility Bat / Solar Bat Utility | Solar Utility Bat | POP00/01/02 | QPIRI f16 = 1 ✔ |
| AC Input Range | Appliance / UPS | UPS | PGR00/01 | QPIRI f15 = 1 ✔ |
| Output Voltage | 220.0 / 230.0 / 240.0 | 230.0 | V220/V230/V240 | QPIRI f2 ✔ |
| Output Frequency | 50.0 / 60.0 | 50.0 | F50/F60 | QPIRI f3 ✔ |

**Battery Parameters Setting** (24 V system)

| Item | Options in WatchPower | Current | PI30 write | Read back |
|---|---|---|---|---|
| Battery Type | AGM / Flooded / User / Pylon / Lib / Lic / Isc | User | PBT00…06 (order as listed) | QPIRI f12 = 2 ✔ |
| Battery Cut-off Voltage | number field | 22.1 | PSDV | QPIRI f9 ✔ |
| Bulk Charging Voltage | number field | 27.2 | PCVV | QPIRI f10 ✔ |
| Float Charging Voltage | number field | 26.8 | PBFT | QPIRI f11 ✔ |
| Max. Charging Current | 10…120 step 10 | 50 | MNCHGC / MCHGC | QPIRI f14 ✔ |
| Max. AC Charging Current | 2, 10…100 step 10 | 10 | MUCHGC | QPIRI f13 ✔ |
| Charging Source Priority | Solar First / Solar + Utility / Only Solar Charging Permitted | Solar First | PCP01/02/03 | QPIRI f17 = 1 ✔ |
| Battery Equalization | Disable / Enable | Disable | PBEQE0/1 | QBEQI f0 |
| Real-time Activate Battery Equalization | Cancel / Activation | Cancel | PBEQA0/1 | QBEQI |
| Battery Equalization Time Out | 5…900 min step 5 | (none marked) | PBEQOT nnn | QBEQI |
| Battery Equalization Time | 5…900 min step 5 | (none marked) | PBEQT nnn | QBEQI |
| Equalization Period | number field, hint 0~90 (days) | – | PBEQP nnn | QBEQI |
| Equalization Voltage | number field | 29.2 | PBEQV xx.xx | QBEQI |
| Back to Grid Voltage | 22.0…25.5 step 0.5 | 24.0 | PBCV | QPIRI f8 ✔ |
| Back to Discharge Voltage | FULL, 24.0…29.0 step 0.5 | 26.5 | PBDV (FULL = 00.0) | QPIRI f22 ✔ |

**Enable/Disable Items** (all Disable / Enable)

| Item | Current | QFLAG letter |
|---|---|---|
| LCD Auto-return to Main Screen | Enable | k ✔ |
| Fault Code Record | Enable | z ✔ |
| Backlight | Enable | x ✔ |
| Bypass Function | Disable | b ✔ |
| Solar Feed To Grid | Disable | d ✔ |
| Beeps While Primary Source Interrupt | Enable | y ✔ |
| Over Temperature Auto Restart | Disable | v ✔ |
| Overload Auto Restart | Disable | u ✔ |
| Buzzer | Enable | a ✔ |

Every value matches our `QPIRI` / `QFLAG` reading (`EakxyzDbdjuv`). WatchPower does not show power saving (j).

**LED Setting** (RGB ring)

| Item | Options | Current (as shown) | PI30 write (MAX-series) |
|---|---|---|---|
| LED Status | Disable / Enable | Disable | PLEDE0/1 |
| LED Speed | Low / Normal / High | Low | PLEDS0/1/2 |
| LED Effect | Cycling / Wheel / Chasing / Solid on | Wheel | PLEDM0…3 |
| LED Brightness | Low / Normal / High | Low | PLEDB… |
| LED Data | PV Input Power / Battery Capacity Percentage / Load Percentage | Battery % | PLEDT0/1/2 |
| LED Color 1 | Blue / Sky Blue / Green / Purple / Pink / Orange / Yellow | Green | PLEDC1 rrrgggbbb |
| LED Color 2 | same list | Sky Blue | PLEDC2 rrrgggbbb |

The LED commands are not confirmed for this model: confirm with a read-only probe before exposing them.

**Restore to the defaults**: one item, "Restore To Default" (PF). Not tapped.
**Time zone setting**: list GMT+3 … GMT+13, current "(GMT +05:00) Islamabad, Karachi". Cloud logger only.
**Wi-Fi Module configuration**: asks "Do you reconfigure or change the connected router?" (cancelled). Cloud logger only.

### Wi-Fi Module Information
PN W08…(redacted), status Online, firmware 3.1.0.1 (the vendor's cloud logger).

### Device Alarm (bell icon)
List per day with filters **All types / Alarm / Fault** and **All status / Unprocessed / Processed**. Each entry shows
code, time, logger PN, status and description. Today: `0x00000001 LINE_FAIL` at 07:49 (grid lost).

### Overview / Me tabs
Device count by status (Normal / Offline / Alarm / Fault), "Current Power" and "Today Power" (kWh) with a 24 h PV
chart, account page, Wi-Fi config, message push. The device showed status **Alarm** (1 alarm), most likely the
"grid not available" warning bit that the inverter raises during load-shedding.

## Gaps to close in our app
1. Read PV2 input voltage and power (`QPIGS2`) for dual-MPPT models.
2. Read the secondary CPU firmware (`QVFW2`).
3. Confirm the LED-setting commands (PLED*) for this model with a read-only probe before exposing them.
4. Add every WatchPower setting above to both apps (AC input range, battery type, output V/Hz, equalization, LED, restore defaults, the full Enable/Disable list).

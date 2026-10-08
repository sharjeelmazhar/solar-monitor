#pragma once
// ============================================================================
//  Solar Monitor v3 - settings you may want to change
// ============================================================================

#define FW_VERSION "3.5.0"

// Network name: the dashboard is also reachable at http://solar.local/
#define HOSTNAME "solar"

// Private values (Wi-Fi, passwords) go in secrets.h, which is git-ignored.
// Copy secrets.example.h to secrets.h and edit it. Anything not set there uses the defaults below.
#if __has_include("secrets.h")
#include "secrets.h"
#endif

// Wi-Fi. Leave empty to reuse the network already saved inside the ESP32
// (it remembers the last network it joined). If nothing is saved and these are
// empty, the board opens its own hotspot "SolarMonitor-Setup" (password below)
// where you can pick your Wi-Fi from the dashboard's Settings page.
#ifndef WIFI_SSID
#define WIFI_SSID ""
#endif
#ifndef WIFI_PASS
#define WIFI_PASS ""
#endif
// The hotspot is called SolarMonitor-XXXX (last 4 characters of the board's address). Phones that join it open the
// setup page by themselves; it can also be reached at http://192.168.4.1/. No password = an open hotspot that only
// runs while the monitor has no working Wi-Fi.
#define SETUP_AP_SSID "SolarMonitor"
#ifndef SETUP_AP_PASS
#define SETUP_AP_PASS "solarsetup"
#endif

// Password for firmware updates over Wi-Fi and for changing Wi-Fi from the
// dashboard (user name is "admin").
#define ADMIN_USER "admin"
#ifndef ADMIN_PASS
#define ADMIN_PASS "change-me-now"
#endif

// Password for changing inverter settings from the apps (edit mode). Set it in secrets.h; without one it is the
// admin password above.
#ifndef SET_PASS
#define SET_PASS ADMIN_PASS
#endif

// Time zone (POSIX format). Pakistan = UTC+5, no daylight saving.
#define TZ_DEFAULT "PKT-5"

// Wiring: MAX3232 TXD -> INV_RX_PIN, MAX3232 RXD -> INV_TX_PIN. The board is picked by the Arduino board setting.
// BOOT button: hold 5 s to forget Wi-Fi and open the setup hotspot.
#if CONFIG_IDF_TARGET_ESP32C3          // ESP32-C3 Super Mini (4 MB flash)
#define FW_BOARD     "esp32c3"
#define INV_RX_PIN   10
#define INV_TX_PIN   20
#define LED_PIN      8
#define LED_ON       LOW
#define BOOT_BTN_PIN 9
#elif CONFIG_IDF_TARGET_ESP32S3        // ESP32-S3 DevKitC-1 (N16R8: 16 MB flash, 8 MB PSRAM)
#define FW_BOARD     "esp32s3"
#define INV_RX_PIN   18
#define INV_TX_PIN   17
#define LED_PIN      -1                 // the RGB LED needs a driver; status shows in the apps instead
#define LED_ON       HIGH
#define BOOT_BTN_PIN 0
#else                                   // classic ESP32 DevKit (ESP32-WROOM-32, micro-USB)
#define FW_BOARD     "esp32"
#define INV_RX_PIN   16
#define INV_TX_PIN   17
#define LED_PIN      2
#define LED_ON       HIGH
#define BOOT_BTN_PIN 0
#endif

// Inverter polling. At 2400 baud a full QPIGS answer takes ~0.5 s on the wire,
// so the real update rate is ~1-1.4 per second; MIN_CYCLE_MS only stops the loop
// from going faster than this if the inverter ever answers quicker.
#define MIN_CYCLE_MS       400
#define REPLY_TIMEOUT_MS   1500
#define STALE_MS           15000   // dashboard says "no data" after this long without a good reading
#define HEARTBEAT_MS       5000    // push to browsers at least this often even if nothing changed

// History storage on the ESP32's flash
#define KEEP_MINUTE_DAYS   20      // per-minute files kept on the ESP (the logger keeps long history); also trimmed if flash fills
#define KEEP_DAY_RECORDS   800     // daily totals kept (~2 years)
#define RECENT_SAMPLES     1200    // high-resolution ring for the live chart (~15-20 min)

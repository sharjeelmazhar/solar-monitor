#pragma once
// ============================================================================
//  Solar Monitor v3 - settings you may want to change
// ============================================================================

#define FW_VERSION "3.0.0"

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
#define SETUP_AP_SSID "SolarMonitor-Setup"
#ifndef SETUP_AP_PASS
#define SETUP_AP_PASS "change-me-now"
#endif

// Password for firmware updates over Wi-Fi and for changing Wi-Fi from the
// dashboard (user name is "admin").
#define ADMIN_USER "admin"
#ifndef ADMIN_PASS
#define ADMIN_PASS "change-me-now"
#endif

// Time zone (POSIX format). Pakistan = UTC+5, no daylight saving.
#define TZ_DEFAULT "PKT-5"

// Wiring (ESP32-C3 Super Mini + MAX3232)
#define INV_RX_PIN 10   // MAX3232 TXD
#define INV_TX_PIN 20   // MAX3232 RXD
#define LED_PIN    8    // onboard LED, ON when LOW

// Inverter polling. At 2400 baud a full QPIGS answer takes ~0.5 s on the wire,
// so the real update rate is ~1-1.4 per second; MIN_CYCLE_MS only stops the loop
// from going faster than this if the inverter ever answers quicker.
#define MIN_CYCLE_MS       400
#define REPLY_TIMEOUT_MS   1500
#define STALE_MS           15000   // dashboard says "no data" after this long without a good reading
#define HEARTBEAT_MS       5000    // push to browsers at least this often even if nothing changed

// History storage on the ESP32's flash
#define KEEP_MINUTE_DAYS   45      // per-minute files kept (also trimmed if flash gets full)
#define KEEP_DAY_RECORDS   800     // daily totals kept (~2 years)
#define RECENT_SAMPLES     1200    // high-resolution ring for the live chart (~15-20 min)

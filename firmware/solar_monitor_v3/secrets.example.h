#pragma once
// Copy this file to secrets.h (git-ignored) and fill in your own values.
// Leave WIFI_SSID empty to reuse the network the ESP32 already remembers.

#define WIFI_SSID ""
#define WIFI_PASS ""
#define SETUP_AP_PASS "pick-a-hotspot-password"   // at least 8 characters
#define ADMIN_PASS "pick-an-admin-password"       // firmware update + Wi-Fi changes (user "admin")
#define SET_PASS "pick-a-settings-password"     // changing inverter settings from the apps (edit mode)

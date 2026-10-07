# Testing status

Development build (web + Android 1.2, firmware 3.2.1). Automated tests pass; the items below still need
a full pass on real devices before calling it production-ready.

## Automated (run on every commit and in CI)
- Firmware protocol: 85 checks (CRC, QPIGS parsing, malformed replies).
- Bill estimate: shared vectors in `shared/bill-vectors.json`, used by both web (Vitest) and Android (JUnit),
  including two real IESCO bills (Feb 2026, Sep 2026) matched within Rs 2.
- Outage detection, hourly mix, unit-alert steps (150 / 175 / 190 / heading over 200), bill insights.

## Checked by hand this round
- Web (phone width + desktop): Live, Outages clock and bullets, Energy bill card, Your bills, settings,
  offline overlay (network cut), no horizontal scroll at 375 px.
- Android emulator: floating blurred bar, 3D core, "Solar + Grid" label, Energy bill card + history,
  Outages clock details, settings render.

## Still to test before production
- [ ] Sunrise/sunset on the Solar tile match a sun app for Islamabad (±2 min); no "cloudy" alert in the last 90 min before sunset; after sunset with grid off the info line says the sun is down.
- [ ] Battery amps on the Battery tile match the inverter display / a clamp meter (charging and discharging).
- [ ] Single notification on a real phone (S24 Ultra, Huawei): grid off → sound once, live timer;
      grid back → "back after X"; reverts to quiet status; nothing doubled on the lock screen.
- [ ] Android 16 promoted "Grid off" chip / Now Bar (needs a real Android 16 phone).
- [ ] Unit alerts firing once per billing month (needs a week+ of monitor data; check after the 8th).
- [ ] Weak-solar alert on a cloudy day (grid off, battery discharging, 5 minutes).
- [ ] Battery idle threshold: 100 W default vs. real discharge at night.
- [ ] Bill estimate vs. the October bill when it arrives (enter it in Your bills, compare).
- [ ] Notification history list and badge after several alerts; Clear all.
- [ ] Monitor offline on a real phone (ESP unplugged) and recovery when it comes back.
- [ ] Huawei (no Google services) background survival overnight.
- [ ] Dark mode on all new screens; large font sizes; tablet / landscape layout.
- [ ] Playwright viewport matrix and Lighthouse (from the original plan).
- [ ] Firmware: 24 h soak at 3.2.1, heap and LittleFS usage.

## v1.3 / firmware 3.3.0 (pending real-world checks)
- [ ] Billing counter: on the 8th at 8 PM the bill card switches to "Grid units used since 8 Oct, 8 PM" and counts from 0 (web + phone).
- [ ] Counter survives a restart (saved every 15 min and before a planned restart).
- [ ] First-time Wi-Fi: hold BOOT 5 s -> hotspot "SolarMonitor-XXXX" (password solarsetup) -> phone opens the setup page -> pick Wi-Fi -> page shows the new address.
- [ ] Watchdog: no unexpected restarts over a few days (System -> Monitor device "Up for"; /api/info "reset" 3 = normal software restart).
- [ ] Phone stays upright with auto-rotate on; a tablet (or the emulator in tablet size) rotates and shows two columns.

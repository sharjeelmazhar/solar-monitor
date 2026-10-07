#pragma once
// ============================================================================
//  History on the ESP32 flash (LittleFS)
//    /h/YYYYMMDD.bin  one MinRec per minute (24 bytes, ~34 KB per day)
//    /days.bin        one DayRec per finished day (40 bytes)
//  All values little-endian. Times are UTC epoch seconds; file names use local date.
// ============================================================================
#include <Arduino.h>
#include <LittleFS.h>
#include <time.h>

#define HIST_VERSION 1

struct __attribute__((packed)) MinRec {   // 24 bytes, averages over one minute
  uint32_t t;        // UTC epoch of the minute start
  uint16_t pvW, loadW, gridW;
  int16_t  battW;    // + charging, - discharging
  uint16_t battV;    // 0.01 V
  uint16_t pvV;      // 0.1 V
  uint16_t gridV;    // 0.1 V
  uint16_t outV;     // 0.1 V
  uint8_t  battPct;
  int8_t   tempC;
  uint8_t  mode;     // QMOD letter
  uint8_t  flags;    // b0 grid present, b1 solar charging, b2 grid charging, b3 load on
};
static_assert(sizeof(MinRec) == 24, "MinRec size");

struct __attribute__((packed)) DayRec {   // 40 bytes, totals for one local day
  uint32_t date;     // YYYYMMDD
  float pvWh, loadWh, gridWh, chgWh, disWh;
  uint16_t pvPeakW, loadPeakW;
  uint16_t gridOnMin, onlineMin;
  uint8_t battMin, battMax;
  int8_t tempMax;
  uint8_t outages;
  uint32_t reserved;
};
static_assert(sizeof(DayRec) == 40, "DayRec size");

struct __attribute__((packed)) Sample {   // 16 bytes, one per inverter reading (RAM only)
  uint32_t t;        // UTC epoch seconds (0 = clock not set yet)
  uint16_t ms;
  uint16_t pvW, loadW, gridW;
  int16_t battW;
  uint8_t battPct;
  uint8_t flags;
};
static_assert(sizeof(Sample) == 16, "Sample size");

static uint32_t localDate(time_t t) {
  struct tm tm;
  localtime_r(&t, &tm);
  return (tm.tm_year + 1900) * 10000UL + (tm.tm_mon + 1) * 100UL + tm.tm_mday;
}

static String dayPath(uint32_t date) { return "/h/" + String(date) + ".bin"; }

static void dayInit(DayRec& d, uint32_t date) {
  memset(&d, 0, sizeof(d));
  d.date = date;
  d.battMin = 255;
  d.tempMax = -128;
}

// Fold one minute record into a day summary (used when rebuilding after a reboot).
static void dayAddMinute(DayRec& d, const MinRec& m, bool& prevGridKnown, bool& prevGrid) {
  const float h = 1.0f / 60.0f;
  d.pvWh += m.pvW * h;
  d.loadWh += m.loadW * h;
  d.gridWh += m.gridW * h;
  if (m.battW > 0) d.chgWh += m.battW * h; else d.disWh += -m.battW * h;
  if (m.pvW > d.pvPeakW) d.pvPeakW = m.pvW;
  if (m.loadW > d.loadPeakW) d.loadPeakW = m.loadW;
  bool g = m.flags & 1;
  if (g) d.gridOnMin++;
  d.onlineMin++;
  if (m.battPct < d.battMin) d.battMin = m.battPct;
  if (m.battPct > d.battMax) d.battMax = m.battPct;
  if (m.tempC > d.tempMax) d.tempMax = m.tempC;
  if (prevGridKnown && prevGrid && !g && d.outages < 255) d.outages++;
  prevGrid = g;
  prevGridKnown = true;
}

static bool summarizeFile(uint32_t date, DayRec& out) {
  File f = LittleFS.open(dayPath(date), "r");
  if (!f) return false;
  dayInit(out, date);
  bool pk = false, pg = false;
  MinRec m;
  while (f.read((uint8_t*)&m, sizeof(m)) == sizeof(m)) dayAddMinute(out, m, pk, pg);
  f.close();
  if (out.onlineMin == 0) out.battMin = 0;
  return true;
}

static uint32_t lastDayInFile() {
  File f = LittleFS.open("/days.bin", "r");
  if (!f) return 0;
  size_t sz = f.size();
  uint32_t date = 0;
  if (sz >= sizeof(DayRec)) {
    f.seek((sz / sizeof(DayRec) - 1) * sizeof(DayRec));
    f.read((uint8_t*)&date, 4);
  }
  f.close();
  return date;
}

static void appendDay(const DayRec& d) {
  uint32_t last = lastDayInFile();
  if (d.date <= last) return;   // already stored
  File f = LittleFS.open("/days.bin", "a");
  if (!f) return;
  f.write((const uint8_t*)&d, sizeof(d));
  size_t count = f.size() / sizeof(DayRec);
  f.close();
  if (count > KEEP_DAY_RECORDS + 50) {   // trim oldest, rarely needed
    File in = LittleFS.open("/days.bin", "r");
    File out = LittleFS.open("/days.tmp", "w");
    if (in && out) {
      in.seek((count - KEEP_DAY_RECORDS) * sizeof(DayRec));
      DayRec r;
      while (in.read((uint8_t*)&r, sizeof(r)) == sizeof(r)) out.write((const uint8_t*)&r, sizeof(r));
    }
    if (in) in.close();
    if (out) out.close();
    LittleFS.remove("/days.bin");
    LittleFS.rename("/days.tmp", "/days.bin");
  }
}

// Sorted list of dates that have a minute file
static int listDayFiles(uint32_t* dates, int cap) {
  int n = 0;
  File dir = LittleFS.open("/h");
  if (!dir) return 0;
  for (File f = dir.openNextFile(); f; f = dir.openNextFile()) {
    uint32_t d = strtoul(f.name(), nullptr, 10);
    f.close();
    if (d > 20000000 && n < cap) dates[n++] = d;
  }
  dir.close();
  for (int i = 1; i < n; i++) {   // insertion sort, n is small
    uint32_t v = dates[i];
    int j = i - 1;
    while (j >= 0 && dates[j] > v) { dates[j + 1] = dates[j]; j--; }
    dates[j + 1] = v;
  }
  return n;
}

// Add day totals for finished days that were never summarised (e.g. ESP was off at midnight),
// then delete the oldest minute files if there are too many or flash is getting full.
static void reconcileAndPrune(uint32_t today) {
  static uint32_t dates[120];
  int n = listDayFiles(dates, 120);
  uint32_t last = lastDayInFile();
  for (int i = 0; i < n; i++) {
    if (dates[i] > last && dates[i] < today) {
      DayRec d;
      if (summarizeFile(dates[i], d)) appendDay(d);
    }
  }
  int i = 0;
  while (i < n - 1 && (n - i > KEEP_MINUTE_DAYS || LittleFS.usedBytes() > LittleFS.totalBytes() * 85 / 100)) {
    if (dates[i] != today) LittleFS.remove(dayPath(dates[i]));
    i++;
  }
}

static uint32_t oldestDayFile() {
  static uint32_t dates[120];
  int n = listDayFiles(dates, 120);
  return n ? dates[0] : 0;
}

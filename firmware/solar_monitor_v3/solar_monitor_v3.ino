/*
  Solar Monitor v3 - ESP32-C3 Super Mini + MAX3232 + Inverex Veyron (Voltronic PI30 protocol)

  What it does
    - Reads the inverter as fast as the 2400-baud link allows (~1 reading per 0.7 s)
    - Pushes every change instantly to all open dashboards (Server-Sent Events, /events)
    - Keeps per-minute history on flash (~45 days) and daily energy totals (~2 years)
    - Syncs the clock from the internet (NTP); a dashboard can also set it if there is no internet
    - Serves the dashboard itself: http://<ESP32-IP>/ or http://solar.local/
    - Firmware can be updated over Wi-Fi: http://solar.local/update  (user admin, password in config.h)

  Wiring:
    MAX3232 VCC -> 3V3, GND -> GND, TXD -> GPIO10, RXD -> GPIO20
    MAX3232 RS232 side -> inverter RJ45: pin1 = TX, pin2 = RX, pin8 = GND

  Arduino IDE: Board "ESP32C3 Dev Module", USB CDC On Boot = Enabled,
               Partition Scheme = Default 4MB with spiffs.
  Libraries:   "ESP Async WebServer" and "Async TCP" (both by ESP32Async), from Library Manager.
  The web page lives in web/index.html; run build_web.ps1 to regenerate web_index.h after editing it.

  HTTP API (all JSON unless noted, CORS open so apps can use it)
    GET  /events                 SSE stream, event "live" = same JSON as /api/live, on every change
    GET  /events/status          same, but only on mode/grid/battery%/warning changes + 60 s heartbeat (for phones in background)
    GET  /api/live               latest reading + today's totals
    GET  /api/info               device, Wi-Fi, storage, inverter ratings (raw QPIRI etc.), settings
    GET  /api/recent             binary Sample[] (16 B each), last ~15 min, oldest first
    GET  /api/day?d=YYYYMMDD     binary MinRec[] (24 B each) for that local day
    GET  /api/days               binary DayRec[] (40 B each), oldest first, today (in progress) last
    POST /api/time    t=<epoch seconds>                      set clock if NTP is not available
    POST /api/settings battAh, tariff, name, tz              user settings
    POST /api/wifi    ssid, pass          (admin auth)       join another network
    GET  /api/scan                                            nearby Wi-Fi networks
    POST /api/refresh                                         re-read inverter ratings now
    GET/POST /update                      (admin auth)       firmware update
*/

#include <WiFi.h>
#include <ESPmDNS.h>
#include <LittleFS.h>
#include <Preferences.h>
#include <Update.h>
#include <esp_wifi.h>
#include <sys/time.h>
#include <AsyncTCP.h>
#include <ESPAsyncWebServer.h>
#include "config.h"
#include "inverter.h"
#include "history.h"
#include "web_index.h"

AsyncWebServer server(80);
AsyncEventSource events("/events");
AsyncEventSource statusEvents("/events/status");   // quiet stream for phone background alerts
Preferences prefs;
SemaphoreHandle_t dataMux;

// ---------- shared state (guarded by dataMux) ----------
Live live;
bool everOk = false;
uint32_t lastOkMs = 0, seq = 0, cycleMs = 0, okCount = 0, failCount = 0;
char liveJson[1400] = "{\"ok\":false,\"ever\":false}";

Sample ring[RECENT_SAMPLES];
int ringHead = 0, ringCount = 0;   // head = next write position

MinRec todayRecs[1440];
int todayCount = 0;

DayRec today;            // running totals for the current local day
bool todayLoaded = false;
uint32_t histFrom = 0;   // oldest day with minute data

// inverter info, refreshed rarely
char qpiri[160] = "", qid[40] = "", qvfw[40] = "", qflag[40] = "";

// settings
float setBattAh = 0, setTariff = 0;
String setName = "Solar", setTz = TZ_DEFAULT;

// misc
bool fsOk = false, mdnsOk = false, apOn = false, rebootPending = false, refreshRated = true;
bool timeSetByClient = false;
uint32_t wifiLostAt = 0, rebootAt = 0;

// ---------- minute accumulator (loop task only) ----------
struct {
  uint32_t minute = 0;   // epoch / 60
  uint32_t n = 0;
  double pv = 0, load = 0, grid = 0, batt = 0, battV = 0, pvV = 0, gridV = 0, outV = 0;
  uint8_t battPct = 0, mode = '?', flags = 0;
  int8_t tempMax = -128;
} acc;

static bool timeValid() { return time(nullptr) > 1735689600; }   // after 2025-01-01

static inline void lock()   { xSemaphoreTake(dataMux, portMAX_DELAY); }
static inline void unlock() { xSemaphoreGive(dataMux); }

// only safe characters reach the JSON
static void jsonSafe(char* dst, const char* src, size_t cap) {
  size_t j = 0;
  for (size_t i = 0; src[i] && j < cap - 1; i++) {
    char c = src[i];
    if (c >= 32 && c < 127 && c != '"' && c != '\\') dst[j++] = c;
  }
  dst[j] = 0;
}

static uint8_t liveFlags(const Live& L) {
  uint8_t f = 0;
  if (L.gridOn) f |= 1;
  if (strlen(L.st) == 8) {
    if (L.st[6] == '1') f |= 2;   // b1 SCC (solar) charging
    if (L.st[7] == '1') f |= 4;   // b0 AC (grid) charging
    if (L.st[3] == '1') f |= 8;   // b4 load on
  }
  return f;
}

// ---------- JSON ----------
static void buildLiveJsonLocked() {
  const Live& L = live;
  struct timeval tv;
  gettimeofday(&tv, nullptr);
  double tms = timeValid() ? (double)tv.tv_sec * 1000.0 + tv.tv_usec / 1000 : 0;
  bool fresh = everOk && millis() - lastOkMs < STALE_MS;
  char err[64];
  jsonSafe(err, invErr, sizeof(err));
  char mode[2] = {L.mode ? L.mode : '?', 0};
  snprintf(liveJson, sizeof(liveJson),
    "{\"seq\":%lu,\"t\":%.0f,\"ok\":%s,\"ever\":%s,\"age\":%lu,\"mode\":\"%s\","
    "\"pvW\":%d,\"pvV\":%.1f,\"pvA\":%.1f,\"pvChgW\":%d,"
    "\"battV\":%.2f,\"battVscc\":%.2f,\"battPct\":%d,\"chgA\":%.1f,\"dischgA\":%.1f,\"battW\":%d,"
    "\"loadW\":%d,\"loadVA\":%d,\"loadPct\":%d,\"outV\":%.1f,\"outHz\":%.1f,"
    "\"gridOn\":%s,\"gridV\":%.1f,\"gridHz\":%.1f,\"gridW\":%d,"
    "\"tempC\":%d,\"busV\":%d,\"st\":\"%s\",\"st2\":\"%s\",\"warn\":\"%s\","
    "\"today\":{\"date\":%lu,\"pv\":%.1f,\"load\":%.1f,\"grid\":%.1f,\"chg\":%.1f,\"dis\":%.1f,"
    "\"gridOnMin\":%u,\"onlineMin\":%u,\"outages\":%u,\"pvPeak\":%u,\"loadPeak\":%u},"
    "\"poll\":{\"ms\":%lu,\"ok\":%lu,\"fail\":%lu,\"crc\":%lu,\"err\":\"%s\"},\"timeOk\":%s}",
    (unsigned long)seq, tms, fresh ? "true" : "false", everOk ? "true" : "false",
    everOk ? (unsigned long)(millis() - lastOkMs) : 0UL, mode,
    L.pvW, L.pvV, L.pvA, L.pvChgW,
    L.battV, L.battVscc, L.battPct, L.chgA, L.dischgA, L.battW,
    L.outW, L.outVA, L.loadPct, L.outV, L.outHz,
    L.gridOn ? "true" : "false", L.gridV, L.gridHz, L.gridW,
    L.tempC, L.busV, L.st, L.st2, L.warn,
    (unsigned long)today.date, today.pvWh, today.loadWh, today.gridWh, today.chgWh, today.disWh,
    today.gridOnMin, today.onlineMin, today.outages, today.pvPeakW, today.loadPeakW,
    (unsigned long)cycleMs, (unsigned long)okCount, (unsigned long)failCount, (unsigned long)invCrcErrors, err,
    timeValid() ? "true" : "false");
}

static String buildInfoJson() {
  char a[160], b[40], c[40], d[40], name[40], tz[40], ssid[40];
  lock();
  jsonSafe(a, qpiri, sizeof(a)); jsonSafe(b, qid, sizeof(b));
  jsonSafe(c, qvfw, sizeof(c));  jsonSafe(d, qflag, sizeof(d));
  jsonSafe(name, setName.c_str(), sizeof(name)); jsonSafe(tz, setTz.c_str(), sizeof(tz));
  uint32_t from = histFrom;
  unlock();
  jsonSafe(ssid, WiFi.SSID().c_str(), sizeof(ssid));
  char buf[1100];
  snprintf(buf, sizeof(buf),
    "{\"fw\":\"%s\",\"histVer\":%d,\"name\":\"%s\",\"host\":\"%s\",\"ip\":\"%s\",\"mac\":\"%s\",\"ssid\":\"%s\",\"rssi\":%d,"
    "\"ap\":%s,\"uptime\":%lu,\"heap\":%lu,\"minHeap\":%lu,\"fsUsed\":%lu,\"fsTotal\":%lu,\"fsOk\":%s,"
    "\"timeOk\":%s,\"time\":%lu,\"tz\":\"%s\",\"clients\":%u,\"histFrom\":%lu,"
    "\"battAh\":%.1f,\"tariff\":%.2f,"
    "\"inv\":{\"qpiri\":\"%s\",\"qid\":\"%s\",\"qvfw\":\"%s\",\"qflag\":\"%s\"}}",
    FW_VERSION, HIST_VERSION, name, HOSTNAME, WiFi.localIP().toString().c_str(), WiFi.macAddress().c_str(), ssid,
    WiFi.RSSI(), apOn ? "true" : "false", (unsigned long)(millis() / 1000), (unsigned long)ESP.getFreeHeap(),
    (unsigned long)ESP.getMinFreeHeap(), fsOk ? (unsigned long)LittleFS.usedBytes() : 0UL,
    fsOk ? (unsigned long)LittleFS.totalBytes() : 0UL, fsOk ? "true" : "false",
    timeValid() ? "true" : "false", (unsigned long)time(nullptr), tz, (unsigned)events.count(), (unsigned long)from,
    setBattAh, setTariff, a, b, c, d);
  return String(buf);
}

// ---------- history ----------
static void finalizeMinute() {
  if (acc.n == 0 || !fsOk) { acc.n = 0; return; }
  MinRec m;
  m.t = acc.minute * 60;
  m.pvW = (uint16_t)lround(acc.pv / acc.n);
  m.loadW = (uint16_t)lround(acc.load / acc.n);
  m.gridW = (uint16_t)lround(acc.grid / acc.n);
  m.battW = (int16_t)lround(acc.batt / acc.n);
  m.battV = (uint16_t)lround(acc.battV / acc.n * 100);
  m.pvV = (uint16_t)lround(acc.pvV / acc.n * 10);
  m.gridV = (uint16_t)lround(acc.gridV / acc.n * 10);
  m.outV = (uint16_t)lround(acc.outV / acc.n * 10);
  m.battPct = acc.battPct;
  m.tempC = acc.tempMax;
  m.mode = acc.mode;
  m.flags = acc.flags;
  acc.n = 0;
  acc.pv = acc.load = acc.grid = acc.batt = acc.battV = acc.pvV = acc.gridV = acc.outV = 0;
  acc.tempMax = -128;

  uint32_t date = localDate(m.t);
  if (date != today.date) return;   // belongs to a day we already closed
  lock();
  bool dup = todayCount > 0 && todayRecs[todayCount - 1].t >= m.t;
  if (!dup && todayCount < 1440) {
    todayRecs[todayCount++] = m;
    today.onlineMin++;
    if (m.flags & 1) today.gridOnMin++;
  }
  unlock();
  if (dup) return;
  if (!LittleFS.exists("/h")) LittleFS.mkdir("/h");
  File f = LittleFS.open(dayPath(date), "a");
  if (f) { f.write((const uint8_t*)&m, sizeof(m)); f.close(); }
}

// Called once the clock is valid: restore today's minutes and totals from flash.
static void loadToday() {
  uint32_t date = localDate(time(nullptr));
  DayRec d;
  dayInit(d, date);
  int cnt = 0;
  if (fsOk) reconcileAndPrune(date);
  lock();
  if (fsOk) {
    File f = LittleFS.open(dayPath(date), "r");
    if (f) {
      bool pk = false, pg = false;
      MinRec m;
      while (cnt < 1440 && f.read((uint8_t*)&m, sizeof(m)) == sizeof(m)) {
        if (cnt && m.t <= todayRecs[cnt - 1].t) continue;
        todayRecs[cnt++] = m;
        dayAddMinute(d, m, pk, pg);
      }
      f.close();
    }
  }
  if (cnt == 0) d.battMin = 255;
  todayCount = cnt;
  // keep energy counted before the clock was set
  d.pvWh += today.pvWh; d.loadWh += today.loadWh; d.gridWh += today.gridWh;
  d.chgWh += today.chgWh; d.disWh += today.disWh;
  today = d;
  histFrom = fsOk ? oldestDayFile() : 0;
  todayLoaded = true;
  unlock();
  Serial.printf("History: today %lu, %d minutes restored, history from %lu\n", (unsigned long)date, cnt, (unsigned long)histFrom);
}

static void rollDay(uint32_t newDate) {
  finalizeMinute();
  DayRec done;
  lock();
  done = today;
  if (done.onlineMin == 0 && done.battMin == 255) done.battMin = 0;
  dayInit(today, newDate);
  todayCount = 0;
  unlock();
  if (fsOk) {
    appendDay(done);
    reconcileAndPrune(newDate);
    uint32_t from = oldestDayFile();
    lock(); histFrom = from; unlock();
  }
  Serial.printf("New day %lu\n", (unsigned long)newDate);
}

// ---------- one good reading ----------
static uint32_t lastSampleMs = 0;
static bool prevGridKnown = false, prevGrid = false;

static void commitSample(const Live& L) {
  uint32_t nowMs = millis();
  float dtH = 0;
  if (lastSampleMs) dtH = min<uint32_t>(nowMs - lastSampleMs, 5000) / 3600000.0f;
  lastSampleMs = nowMs;

  time_t now = time(nullptr);
  bool tOk = timeValid();
  if (tOk && !todayLoaded) loadToday();
  if (tOk) {
    uint32_t date = localDate(now);
    if (date != today.date && todayLoaded) rollDay(date);
  }

  struct timeval tv;
  gettimeofday(&tv, nullptr);
  Sample s;
  s.t = tOk ? (uint32_t)tv.tv_sec : 0;
  s.ms = tOk ? tv.tv_usec / 1000 : 0;
  s.pvW = L.pvW; s.loadW = L.outW; s.gridW = L.gridW; s.battW = L.battW;
  s.battPct = L.battPct; s.flags = liveFlags(L);

  lock();
  live = L;
  everOk = true;
  lastOkMs = nowMs;
  okCount++;
  seq++;
  ring[ringHead] = s;
  ringHead = (ringHead + 1) % RECENT_SAMPLES;
  if (ringCount < RECENT_SAMPLES) ringCount++;
  // energy
  today.pvWh += L.pvW * dtH;
  today.loadWh += L.outW * dtH;
  today.gridWh += L.gridW * dtH;
  if (L.battW > 0) today.chgWh += L.battW * dtH; else today.disWh += -L.battW * dtH;
  if (L.pvW > today.pvPeakW) today.pvPeakW = L.pvW;
  if (L.outW > today.loadPeakW) today.loadPeakW = L.outW;
  if (L.battPct < today.battMin) today.battMin = L.battPct;
  if (L.battPct > today.battMax) today.battMax = L.battPct;
  if (L.tempC > today.tempMax) today.tempMax = L.tempC;
  if (prevGridKnown && prevGrid && !L.gridOn && today.outages < 255) today.outages++;
  prevGrid = L.gridOn; prevGridKnown = true;
  buildLiveJsonLocked();
  unlock();

  // per-minute averages
  if (tOk && todayLoaded) {
    uint32_t minute = now / 60;
    if (acc.minute != minute) {
      if (acc.minute && minute > acc.minute) finalizeMinute();
      acc.n = 0;
      acc.pv = acc.load = acc.grid = acc.batt = acc.battV = acc.pvV = acc.gridV = acc.outV = 0;
      acc.tempMax = -128;
      acc.minute = minute;
    }
    acc.n++;
    acc.pv += L.pvW; acc.load += L.outW; acc.grid += L.gridW; acc.batt += L.battW;
    acc.battV += L.battV; acc.pvV += L.pvV; acc.gridV += L.gridV; acc.outV += L.outV;
    acc.battPct = L.battPct; acc.mode = L.mode; acc.flags = liveFlags(L);
    if (L.tempC > acc.tempMax) acc.tempMax = L.tempC;
  }
}

// ---------- inverter polling ----------
static uint32_t cycleNo = 0;
static uint32_t lastPushMs = 0;
static uint32_t lastPushHash = 0;

static uint32_t fnv(const char* s, uint32_t h = 2166136261u) {
  while (*s) { h ^= (uint8_t)*s++; h *= 16777619u; }
  return h;
}

static void pollInverter() {
  static char buf[200];
  uint32_t t0 = millis();
  Live L;
  lock(); L = live; unlock();

  bool ok = invQuery("QPIGS", buf, sizeof(buf)) > 0 && parseQPIGS(buf, L);
  uint32_t h = 0;
  if (ok) {
    h = fnv(buf);
    char m[8];
    if (invQuery("QMOD", m, sizeof(m), 800) > 0) L.mode = m[0];
    if (cycleNo % 5 == 0) {
      char w[48];
      if (invQuery("QPIWS", w, sizeof(w), 800) > 0) strlcpy(L.warn, w, sizeof(L.warn));
    }
    estimateGrid(L);
    h = fnv(L.warn, (h ^ (uint8_t)L.mode) * 16777619u);
    if (refreshRated) {
      char r[160], a[40], b[40], c[40];
      bool got = invQuery("QPIRI", r, sizeof(r)) > 0;
      bool gotId = invQuery("QID", a, sizeof(a), 800) > 0;
      bool gotFw = invQuery("QVFW", b, sizeof(b), 800) > 0;
      bool gotFl = invQuery("QFLAG", c, sizeof(c), 800) > 0;
      lock();
      if (got) strlcpy(qpiri, r, sizeof(qpiri));
      if (gotId) strlcpy(qid, a, sizeof(qid));
      if (gotFw) strlcpy(qvfw, b, sizeof(qvfw));
      if (gotFl) strlcpy(qflag, c, sizeof(qflag));
      unlock();
      refreshRated = !got;   // retry next cycle if QPIRI failed
    }
    commitSample(L);
  } else {
    lock();
    failCount++;
    buildLiveJsonLocked();
    unlock();
    Serial.printf("Inverter: %s\n", invErr);
  }
  cycleNo++;
  if (cycleNo % 900 == 0) refreshRated = true;   // ratings/settings every ~10 min

  // push to dashboards: on every change, plus a heartbeat
  uint32_t now = millis();
  if (events.count() > 0 && ((ok && h != lastPushHash) || now - lastPushMs >= HEARTBEAT_MS || !ok)) {
    static char out[sizeof(liveJson)];
    lock(); strlcpy(out, liveJson, sizeof(out)); uint32_t id = seq; unlock();
    events.send(out, "live", id);
    lastPushMs = now;
    lastPushHash = h;
  }

  // quiet stream: only when something an alert could care about changes, plus a 60 s heartbeat
  static uint32_t lastStatusMs = 0, lastStatusSig = 0;
  if (statusEvents.count() > 0) {
    Live c;
    lock(); c = live; bool fresh = everOk && millis() - lastOkMs < STALE_MS; unlock();
    char sig[64];
    snprintf(sig, sizeof(sig), "%d%c%d%d%d%s", fresh, c.mode, c.gridOn, c.battPct, c.battW > 60 ? 1 : c.battW < -60 ? 2 : 0, c.warn);
    uint32_t s = fnv(sig);
    if (s != lastStatusSig || now - lastStatusMs >= 60000) {
      static char out[sizeof(liveJson)];
      lock(); strlcpy(out, liveJson, sizeof(out)); uint32_t id = seq; unlock();
      statusEvents.send(out, "live", id);
      lastStatusSig = s;
      lastStatusMs = now;
    }
  }

  lock(); cycleMs = millis() - t0; unlock();
  digitalWrite(LED_PIN, (everOk && millis() - lastOkMs < STALE_MS) ? LOW : HIGH);
}

// ---------- Wi-Fi ----------
static void startAP() {
  if (apOn) return;
  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(SETUP_AP_SSID, SETUP_AP_PASS);
  apOn = true;
  Serial.printf("Setup hotspot '%s' on: http://%s/\n", SETUP_AP_SSID, WiFi.softAPIP().toString().c_str());
}

static void stopAP() {
  if (!apOn) return;
  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_STA);
  apOn = false;
}

static void onWiFiEvent(WiFiEvent_t e, WiFiEventInfo_t info) {
  if (e == ARDUINO_EVENT_WIFI_STA_GOT_IP) {
    Serial.printf("Wi-Fi connected: http://%s/  (RSSI %d)\n", WiFi.localIP().toString().c_str(), WiFi.RSSI());
    wifiLostAt = 0;
  } else if (e == ARDUINO_EVENT_WIFI_STA_DISCONNECTED) {
    if (!wifiLostAt) wifiLostAt = millis() | 1;
  }
}

static void startWiFi() {
  WiFi.persistent(true);
  WiFi.setHostname(HOSTNAME);
  WiFi.mode(WIFI_STA);
  WiFi.setTxPower(WIFI_POWER_8_5dBm);   // Super Mini boards connect more reliably at lower power
  WiFi.onEvent(onWiFiEvent);
  WiFi.setSleep(false);                 // lowest latency
  WiFi.setAutoReconnect(true);
  wifi_config_t conf;
  bool saved = esp_wifi_get_config(WIFI_IF_STA, &conf) == ESP_OK && conf.sta.ssid[0] != 0;
  if (strlen(WIFI_SSID)) {
    WiFi.begin(WIFI_SSID, WIFI_PASS);
  } else if (saved) {
    Serial.printf("Joining saved Wi-Fi '%s'\n", (const char*)conf.sta.ssid);
    WiFi.begin();
  } else {
    Serial.println("No Wi-Fi saved");
    startAP();
  }
  wifiLostAt = millis() | 1;
}

static void serviceWiFi() {
  bool up = WiFi.status() == WL_CONNECTED;
  if (up && !mdnsOk) {
    mdnsOk = MDNS.begin(HOSTNAME);
    if (mdnsOk) {
      MDNS.addService("http", "tcp", 80);
      MDNS.addService("solarmon", "tcp", 80);
      MDNS.addServiceTxt("solarmon", "tcp", "fw", FW_VERSION);
      Serial.println("Also at: http://" HOSTNAME ".local/");
    }
  }
  if (up && apOn && millis() > 60000) stopAP();
  if (!up && wifiLostAt && millis() - wifiLostAt > 90000 && !apOn) startAP();   // let the user fix Wi-Fi
}

// ---------- web ----------
static void sendJson(AsyncWebServerRequest* r, const String& s) {
  AsyncWebServerResponse* res = r->beginResponse(200, "application/json", s);
  res->addHeader("Cache-Control", "no-store");
  r->send(res);
}

static bool needAdmin(AsyncWebServerRequest* r) {
  if (r->authenticate(ADMIN_USER, ADMIN_PASS)) return false;
  r->requestAuthentication(AsyncAuthType::AUTH_BASIC);
  return true;
}

static String param(AsyncWebServerRequest* r, const char* name) {
  if (r->hasParam(name, true)) return r->getParam(name, true)->value();
  if (r->hasParam(name)) return r->getParam(name)->value();
  return String();
}

static bool wwwUploadFailed = false;   // uploads are sent one at a time by deploy.mjs

// Only simple relative paths inside /www: "index.html.gz", "favicon.svg", "assets/<name>"
static bool wwwPathOk(const String& p) {
  if (p.isEmpty() || p.length() > 48 || p.indexOf("..") >= 0 || p.startsWith("/")) return false;
  for (size_t i = 0; i < p.length(); i++) {
    char c = p[i];
    if (!(isalnum((unsigned char)c) || c == '.' || c == '-' || c == '_' || c == '/')) return false;
  }
  int slash = p.indexOf('/');
  return slash < 0 ? true : (p.startsWith("assets/") && p.indexOf('/', 7) < 0);
}

static const char UPDATE_PAGE[] PROGMEM = R"HTML(<!doctype html><meta name=viewport content="width=device-width,initial-scale=1">
<title>Firmware update</title><body style="font-family:system-ui;max-width:480px;margin:40px auto;padding:0 16px">
<h2>Firmware update</h2><p>Select the compiled <b>.bin</b> file (Arduino: Sketch &rarr; Export compiled binary).</p>
<form method=POST enctype=multipart/form-data><input type=file name=fw accept=.bin required> <button>Upload</button></form>
<p><a href="/">Back to dashboard</a></p></body>)HTML";

static void setupWeb() {
  DefaultHeaders::Instance().addHeader("Access-Control-Allow-Origin", "*");
  DefaultHeaders::Instance().addHeader("Access-Control-Allow-Headers", "*");

  // Dashboard: the full web app lives in flash (/www, uploaded with web/scripts/deploy.mjs);
  // the small built-in dashboard is the fallback and stays reachable at /classic.
  auto classic = [](AsyncWebServerRequest* r) {
    AsyncWebServerResponse* res = r->beginResponse(200, "text/html", INDEX_HTML_GZ, INDEX_HTML_GZ_LEN);
    res->addHeader("Content-Encoding", "gzip");
    res->addHeader("Cache-Control", "no-cache");
    r->send(res);
  };
  server.on("/classic", HTTP_GET, classic);
  server.on("/", HTTP_GET, [classic](AsyncWebServerRequest* r) {
    if (fsOk && LittleFS.exists("/www/index.html.gz")) {
      AsyncWebServerResponse* res = r->beginResponse(LittleFS, "/www/index.html.gz", "text/html");
      res->addHeader("Content-Encoding", "gzip");
      res->addHeader("Cache-Control", "no-cache");
      r->send(res);
    } else {
      classic(r);
    }
  });
  // hashed file names never change content, so browsers may cache them for a year
  server.serveStatic("/assets/", LittleFS, "/www/assets/").setCacheControl("public, max-age=31536000, immutable");
  server.serveStatic("/favicon.svg", LittleFS, "/www/favicon.svg").setCacheControl("public, max-age=86400");

  // Web app upload (admin): POST /api/www?path=assets/x.js.gz (multipart file), GET /api/www (list), POST /api/www/delete?path=...
  server.on("/api/www", HTTP_GET, [](AsyncWebServerRequest* r) {
    String s = "[";
    File dir = LittleFS.open("/www/assets");
    bool first = true;
    if (dir) for (File f = dir.openNextFile(); f; f = dir.openNextFile()) {
      if (!first) s += ",";
      s += "\"assets/" + String(f.name()) + "\"";
      first = false;
      f.close();
    }
    for (const char* top : {"index.html.gz", "favicon.svg"}) {
      if (LittleFS.exists(String("/www/") + top)) { if (!first) s += ","; s += String("\"") + top + "\""; first = false; }
    }
    s += "]";
    sendJson(r, s);
  });
  server.on("/api/www/delete", HTTP_POST, [](AsyncWebServerRequest* r) {
    if (needAdmin(r)) return;
    String p = param(r, "path");
    if (!wwwPathOk(p)) { r->send(400, "text/plain", "bad path"); return; }
    bool ok = LittleFS.remove("/www/" + p);
    sendJson(r, ok ? "{\"ok\":true}" : "{\"ok\":false}");
  });
  server.on("/api/www", HTTP_POST,
    [](AsyncWebServerRequest* r) {
      if (!r->authenticate(ADMIN_USER, ADMIN_PASS)) { r->requestAuthentication(AsyncAuthType::AUTH_BASIC); return; }
      bool ok = !wwwUploadFailed;
      sendJson(r, ok ? "{\"ok\":true}" : "{\"ok\":false}");
    },
    [](AsyncWebServerRequest* r, const String& filename, size_t index, uint8_t* data, size_t len, bool final) {
      static File out;
      if (!r->authenticate(ADMIN_USER, ADMIN_PASS)) return;
      String p = param(r, "path");
      if (index == 0) {
        wwwUploadFailed = false;
        if (out) out.close();
        if (!wwwPathOk(p)) { wwwUploadFailed = true; return; }
        if (!LittleFS.exists("/www")) LittleFS.mkdir("/www");
        if (!LittleFS.exists("/www/assets")) LittleFS.mkdir("/www/assets");
        out = LittleFS.open("/www/" + p, "w");
        if (!out) { wwwUploadFailed = true; return; }
      }
      if (out && len && out.write(data, len) != len) { wwwUploadFailed = true; out.close(); }
      if (final && out) out.close();
    });

  events.onConnect([](AsyncEventSourceClient* c) {
    static char out[sizeof(liveJson)];
    lock(); strlcpy(out, liveJson, sizeof(out)); uint32_t id = seq; unlock();
    c->send(out, "live", id, 2000);   // also tells the browser to retry after 2 s if dropped
  });
  server.addHandler(&events);
  statusEvents.onConnect([](AsyncEventSourceClient* c) {
    static char out[sizeof(liveJson)];
    lock(); strlcpy(out, liveJson, sizeof(out)); uint32_t id = seq; unlock();
    c->send(out, "live", id, 5000);
  });
  server.addHandler(&statusEvents);

  auto liveHandler = [](AsyncWebServerRequest* r) {
    lock(); String s(liveJson); unlock();
    sendJson(r, s);
  };
  server.on("/api/live", HTTP_GET, liveHandler);
  server.on("/data", HTTP_GET, liveHandler);   // old endpoint name
  server.on("/api/info", HTTP_GET, [](AsyncWebServerRequest* r) { sendJson(r, buildInfoJson()); });

  server.on("/api/recent", HTTP_GET, [](AsyncWebServerRequest* r) {
    lock();
    int count = ringCount, start = (ringHead - ringCount + RECENT_SAMPLES) % RECENT_SAMPLES;
    unlock();
    size_t len = count * sizeof(Sample);
    AsyncWebServerResponse* res = r->beginResponse("application/octet-stream", len,
      [start, len](uint8_t* buf, size_t maxLen, size_t index) -> size_t {
        size_t n = 0;
        lock();
        while (n < maxLen && index + n < len) {
          size_t pos = index + n, i = pos / sizeof(Sample), off = pos % sizeof(Sample);
          size_t take = min(maxLen - n, sizeof(Sample) - off);
          memcpy(buf + n, (const uint8_t*)&ring[(start + i) % RECENT_SAMPLES] + off, take);
          n += take;
        }
        unlock();
        return n;
      });
    res->addHeader("Cache-Control", "no-store");
    r->send(res);
  });

  server.on("/api/day", HTTP_GET, [](AsyncWebServerRequest* r) {
    uint32_t d = strtoul(param(r, "d").c_str(), nullptr, 10);
    lock(); uint32_t td = today.date; int cnt = todayCount; unlock();
    if (d == 0) d = td;
    if (d == td && td) {
      size_t len = cnt * sizeof(MinRec);
      AsyncWebServerResponse* res = r->beginResponse("application/octet-stream", len,
        [len](uint8_t* buf, size_t maxLen, size_t index) -> size_t {
          size_t n = min(maxLen, len - index);
          lock(); memcpy(buf, (uint8_t*)todayRecs + index, n); unlock();
          return n;
        });
      res->addHeader("Cache-Control", "no-store");
      r->send(res);
      return;
    }
    String p = dayPath(d);
    if (fsOk && LittleFS.exists(p)) {
      AsyncWebServerResponse* res = r->beginResponse(LittleFS, p, "application/octet-stream");
      res->addHeader("Cache-Control", "max-age=60");
      r->send(res);
    } else {
      r->send(404, "text/plain", "no data for that day");
    }
  });

  server.on("/api/days", HTTP_GET, [](AsyncWebServerRequest* r) {
    size_t fileLen = 0;
    if (fsOk) { File f = LittleFS.open("/days.bin", "r"); if (f) { fileLen = f.size() / sizeof(DayRec) * sizeof(DayRec); f.close(); } }
    auto cur = std::make_shared<DayRec>();
    lock(); *cur = today; unlock();
    if (cur->battMin == 255) cur->battMin = 0;
    size_t total = fileLen + (cur->date ? sizeof(DayRec) : 0);
    AsyncWebServerResponse* res = r->beginResponse("application/octet-stream", total,
      [fileLen, cur, total](uint8_t* buf, size_t maxLen, size_t index) -> size_t {
        size_t n = 0;
        if (index < fileLen) {
          File f = LittleFS.open("/days.bin", "r");
          if (f) { f.seek(index); n = f.read(buf, min(maxLen, fileLen - index)); f.close(); }
        } else if (index < total) {
          size_t off = index - fileLen;
          n = min(maxLen, sizeof(DayRec) - off);
          memcpy(buf, (uint8_t*)cur.get() + off, n);
        }
        return n;
      });
    res->addHeader("Cache-Control", "no-store");
    r->send(res);
  });

  server.on("/api/time", HTTP_POST, [](AsyncWebServerRequest* r) {
    double t = param(r, "t").toDouble();
    if (t > 1735689600 && (!timeValid() || timeSetByClient)) {
      struct timeval tv = {(time_t)t, 0};
      settimeofday(&tv, nullptr);
      timeSetByClient = true;
      Serial.println("Clock set by dashboard");
    }
    sendJson(r, String("{\"timeOk\":") + (timeValid() ? "true" : "false") + "}");
  });

  server.on("/api/settings", HTTP_POST, [](AsyncWebServerRequest* r) {
    String v;
    lock();
    if ((v = param(r, "battAh")).length()) { setBattAh = constrain(v.toFloat(), 0, 10000); prefs.putFloat("battAh", setBattAh); }
    if ((v = param(r, "tariff")).length()) { setTariff = constrain(v.toFloat(), 0, 10000); prefs.putFloat("tariff", setTariff); }
    if ((v = param(r, "name")).length())   { setName = v.substring(0, 30); prefs.putString("name", setName); }
    if ((v = param(r, "tz")).length() && v.length() < 40) {
      setTz = v; prefs.putString("tz", setTz);
      setenv("TZ", setTz.c_str(), 1); tzset();
    }
    unlock();
    sendJson(r, buildInfoJson());
  });

  server.on("/api/refresh", HTTP_POST, [](AsyncWebServerRequest* r) {
    refreshRated = true;
    sendJson(r, "{\"ok\":true}");
  });

  server.on("/api/scan", HTTP_GET, [](AsyncWebServerRequest* r) {
    int n = WiFi.scanComplete();
    if (n == WIFI_SCAN_FAILED) { WiFi.scanNetworks(true); sendJson(r, "{\"scanning\":true}"); return; }
    if (n == WIFI_SCAN_RUNNING) { sendJson(r, "{\"scanning\":true}"); return; }
    String s = "{\"scanning\":false,\"nets\":[";
    for (int i = 0; i < n; i++) {
      char ssid[40];
      jsonSafe(ssid, WiFi.SSID(i).c_str(), sizeof(ssid));
      if (i) s += ",";
      s += "{\"ssid\":\"" + String(ssid) + "\",\"rssi\":" + WiFi.RSSI(i) + ",\"open\":" +
           (WiFi.encryptionType(i) == WIFI_AUTH_OPEN ? "true" : "false") + "}";
    }
    s += "]}";
    WiFi.scanDelete();
    sendJson(r, s);
  });

  server.on("/api/wifi", HTTP_POST, [](AsyncWebServerRequest* r) {
    if (needAdmin(r)) return;
    String ssid = param(r, "ssid"), pass = param(r, "pass");
    if (!ssid.length()) { r->send(400, "text/plain", "ssid missing"); return; }
    sendJson(r, "{\"ok\":true,\"msg\":\"Joining network. Reconnect to the dashboard on the new network.\"}");
    WiFi.begin(ssid.c_str(), pass.c_str());
    wifiLostAt = millis() | 1;
  });

  server.on("/raw", HTTP_GET, [](AsyncWebServerRequest* r) {
    char b[400];
    lock();
    snprintf(b, sizeof(b), "QPIRI: %s\nQID: %s\nQVFW: %s\nQFLAG: %s\nlast error: %s\n", qpiri, qid, qvfw, qflag, invErr);
    unlock();
    r->send(200, "text/plain", b);
  });

  // firmware update
  server.on("/update", HTTP_GET, [](AsyncWebServerRequest* r) {
    if (needAdmin(r)) return;
    r->send(200, "text/html", UPDATE_PAGE);
  });
  server.on("/update", HTTP_POST,
    [](AsyncWebServerRequest* r) {
      if (!r->authenticate(ADMIN_USER, ADMIN_PASS)) { r->requestAuthentication(AsyncAuthType::AUTH_BASIC); return; }
      bool ok = !Update.hasError();
      r->send(ok ? 200 : 500, "text/plain", ok ? "Update OK, restarting...\n" : (String("Update failed: ") + Update.errorString() + "\n"));
      if (ok) { rebootAt = millis() + 1500; rebootPending = true; }
    },
    [](AsyncWebServerRequest* r, const String& filename, size_t index, uint8_t* data, size_t len, bool final) {
      if (!r->authenticate(ADMIN_USER, ADMIN_PASS)) return;
      if (index == 0) {
        Serial.printf("OTA start: %s\n", filename.c_str());
        Update.begin(UPDATE_SIZE_UNKNOWN, U_FLASH);
      }
      if (len && !Update.hasError()) Update.write(data, len);
      if (final) {
        if (Update.end(true)) Serial.printf("OTA done: %u bytes\n", (unsigned)(index + len));
        else Serial.printf("OTA failed: %s\n", Update.errorString());
      }
    });

  server.onNotFound([](AsyncWebServerRequest* r) {
    if (r->method() == HTTP_OPTIONS) { r->send(204); return; }
    if (apOn) { r->redirect("/"); return; }   // captive-portal style while in setup hotspot
    r->send(404, "text/plain", "Not found");
  });
  server.begin();
}

// ---------- setup / loop ----------
void setup() {
  Serial.begin(115200);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, HIGH);
  dataMux = xSemaphoreCreateMutex();
  Serial1.setRxBufferSize(512);
  Serial1.begin(2400, SERIAL_8N1, INV_RX_PIN, INV_TX_PIN);
  delay(200);
  Serial.println("\nSolar Monitor v" FW_VERSION);

  prefs.begin("solar", false);
  setBattAh = prefs.getFloat("battAh", 0);
  setTariff = prefs.getFloat("tariff", 0);
  setName = prefs.getString("name", "Solar");
  setTz = prefs.getString("tz", TZ_DEFAULT);

  fsOk = LittleFS.begin(true);   // formats the data partition on first run
  Serial.printf("Storage: %s, %u / %u bytes used\n", fsOk ? "ok" : "FAILED",
                fsOk ? (unsigned)LittleFS.usedBytes() : 0, fsOk ? (unsigned)LittleFS.totalBytes() : 0);
  if (fsOk && !LittleFS.exists("/h")) LittleFS.mkdir("/h");

  dayInit(today, 0);
  startWiFi();
  configTzTime(setTz.c_str(), "pool.ntp.org", "time.google.com", "time.cloudflare.com");
  setupWeb();
}

void loop() {
  static uint32_t lastCycle = 0;
  if (millis() - lastCycle >= MIN_CYCLE_MS) {
    lastCycle = millis();
    pollInverter();
  }
  serviceWiFi();
  if (rebootPending && (int32_t)(millis() - rebootAt) > 0) {
    finalizeMinute();
    ESP.restart();
  }
  delay(2);
}

/*
  Solar Monitor - ESP32-C3 Super Mini + MAX3232 + Inverex Veyron (Voltronic PI30 protocol)
  100% local: open http://<ESP32-IP>/ or http://solar.local/ on any phone/PC on your Wi-Fi.

  Wiring:
    MAX3232 VCC  -> ESP32 3V3
    MAX3232 GND  -> ESP32 GND
    MAX3232 TXD  -> ESP32 GPIO10 (RX)     (TTL side)
    MAX3232 RXD  -> ESP32 GPIO20 (TX)     (TTL side)
    MAX3232 RS232 side -> inverter RJ45: pin1=TX, pin2=RX, pin8=GND
  If you get "No response", swap the two RS232 wires at the inverter first.

  Arduino IDE: Board = "ESP32C3 Dev Module", USB CDC On Boot = Enabled.
  No extra libraries needed.
*/

#include <WiFi.h>
#include <WebServer.h>
#include <ESPmDNS.h>

// ====== CHANGE THESE ======
const char* WIFI_SSID = "YOUR_WIFI_NAME";
const char* WIFI_PASS = "YOUR_WIFI_PASSWORD";
// ==========================

#define INV_RX_PIN 10
#define INV_TX_PIN 20
#define LED_PIN    8        // onboard LED, ON when LOW
#define POLL_MS    3000     // ask the inverter every 3 s
#define REPLY_TIMEOUT_MS 1500
#define MAX_REPLY_LEN 200   // a real QPIGS reply is ~110 chars
#define VERIFY_CRC 1        // 1 = reject replies whose CRC is wrong (recommended)
#define STALE_MS   30000    // dashboard shows "no data" if nothing good for 30 s

WebServer server(80);

struct Data {
  float gridV = 0, gridHz = 0, outV = 0, outHz = 0, battV = 0, chgA = 0, dischgA = 0, pvV = 0, pvA = 0, busV = 0;
  int outVA = 0, outW = 0, loadPct = 0, battPct = 0, tempC = 0, pvW = 0;
  String mode = "Unknown";
  bool everOk = false;
  unsigned long lastOk = 0;
  unsigned long okCount = 0, failCount = 0, crcErrors = 0;
  String lastError = "starting";
  String raw;
} d;

bool mdnsStarted = false;

// ---- Voltronic CRC16 (XMODEM, with reserved-byte fix) ----
uint16_t crc16(const uint8_t* p, size_t len) {
  uint16_t crc = 0;
  while (len--) {
    crc ^= (uint16_t)(*p++) << 8;
    for (int i = 0; i < 8; i++) crc = (crc & 0x8000) ? (crc << 1) ^ 0x1021 : (crc << 1);
  }
  uint8_t hi = crc >> 8, lo = crc & 0xFF;
  if (hi == 0x28 || hi == 0x0D || hi == 0x0A) hi++;
  if (lo == 0x28 || lo == 0x0D || lo == 0x0A) lo++;
  return (hi << 8) | lo;
}

// Wait without freezing: keeps the web page and Wi-Fi responsive
void serviceWait() {
  server.handleClient();
  delay(1);
}

// Send command, return payload without "(" and CRC, or "" on failure (reason in d.lastError)
String query(const char* cmd) {
  while (Serial1.available()) Serial1.read();   // flush old bytes
  d.lastError = "";
  uint16_t c = crc16((const uint8_t*)cmd, strlen(cmd));
  Serial1.print(cmd);
  Serial1.write((uint8_t)(c >> 8));
  Serial1.write((uint8_t)(c & 0xFF));
  Serial1.write('\r');

  String r;
  r.reserve(MAX_REPLY_LEN + 4);
  bool started = false, done = false;
  unsigned long t = millis();
  while (millis() - t < REPLY_TIMEOUT_MS) {
    while (Serial1.available()) {
      int b = Serial1.read();
      if (b < 0) break;
      char ch = (char)b;
      if (!started) {                  // skip noise until the '(' that starts a reply
        if (ch == '(') started = true;
        else if (ch == cmd[0]) { d.lastError = "echo: RS232 TX/RX shorted or looped back"; }
        continue;
      }
      if (ch == '\r') { done = true; break; }
      if (r.length() >= MAX_REPLY_LEN) { d.lastError = "reply too long"; return ""; }
      r += ch;
    }
    if (done) break;
    serviceWait();
  }
  if (!started) { if (d.lastError.length() == 0) d.lastError = "no response"; return ""; }
  if (!done)    { d.lastError = "reply cut off (no end)"; return ""; }
  if (r.length() < 3) { d.lastError = "reply too short"; return ""; }

  String payload = r.substring(0, r.length() - 2);
#if VERIFY_CRC
  String framed = "(" + payload;
  uint16_t want = crc16((const uint8_t*)framed.c_str(), framed.length());
  uint16_t got = ((uint8_t)r[r.length() - 2] << 8) | (uint8_t)r[r.length() - 1];
  if (want != got) { d.crcErrors++; d.lastError = "CRC mismatch (noise on cable?)"; return ""; }
#endif
  if (payload.startsWith("NAK")) { d.lastError = String("inverter rejected ") + cmd; return ""; }
  return payload;
}

// Split by spaces, ignoring repeated spaces. Returns number of fields.
int splitFields(const String& s, String* f, int maxFields) {
  int n = 0, i = 0, len = s.length();
  while (i < len && n < maxFields) {
    while (i < len && s[i] == ' ') i++;
    if (i >= len) break;
    int start = i;
    while (i < len && s[i] != ' ') i++;
    f[n++] = s.substring(start, i);
  }
  return n;
}

bool isNumber(const String& s) {
  if (s.length() == 0) return false;
  bool digit = false;
  for (unsigned i = 0; i < s.length(); i++) {
    char c = s[i];
    if (c >= '0' && c <= '9') digit = true;
    else if (!(c == '.' || ((c == '-' || c == '+') && i == 0))) return false;
  }
  return digit;
}

// Parse a QPIGS payload into d. Returns false (and leaves old values) if it doesn't look right.
bool parseQPIGS(const String& s) {
  String f[24];
  int n = splitFields(s, f, 24);
  if (n < 16) { d.lastError = "short reply (" + String(n) + " fields)"; return false; }
  for (int i = 0; i < 16; i++) {
    if (!isNumber(f[i])) { d.lastError = "bad field #" + String(i) + ": " + f[i]; return false; }
  }
  d.gridV = f[0].toFloat();   d.gridHz = f[1].toFloat();
  d.outV  = f[2].toFloat();   d.outHz  = f[3].toFloat();
  d.outVA = f[4].toInt();     d.outW   = f[5].toInt();
  d.loadPct = f[6].toInt();   d.busV   = f[7].toFloat();
  d.battV = f[8].toFloat();   d.chgA   = f[9].toFloat();
  d.battPct = f[10].toInt();  d.tempC  = f[11].toInt();
  d.pvA   = f[12].toFloat();  d.pvV    = f[13].toFloat();
  d.dischgA = f[15].toFloat();
  int pv19 = (n > 19 && isNumber(f[19])) ? f[19].toInt() : 0;
  d.pvW = pv19 > 0 ? pv19 : (int)(d.pvA * d.pvV + 0.5f);
  if (d.battPct < 0) d.battPct = 0;
  if (d.battPct > 100) d.battPct = 100;
  return true;
}

String modeName(const String& m) {
  if (m.length() == 0) return "Unknown";
  switch (m[0]) {
    case 'L': return "Grid (Line)";
    case 'B': return "Battery / Solar";
    case 'S': return "Standby";
    case 'F': return "FAULT";
    case 'H': return "Power saving";
    case 'P': return "Power on";
    case 'D': return "Shutdown";
  }
  return "Unknown";
}

void pollInverter() {
  String s = query("QPIGS");
  if (s.length() == 0 || !parseQPIGS(s)) {
    d.failCount++;
    Serial.println("QPIGS: " + d.lastError);
    return;
  }
  d.raw = s;
  String m = query("QMOD");
  if (m.length()) d.mode = modeName(m);
  d.everOk = true; d.lastOk = millis(); d.okCount++; d.lastError = "";
  Serial.printf("PV %dW %.1fV | Batt %.2fV %d%% +%.0fA -%.0fA | Load %dW %d%% | Grid %.0fV | %s\n",
                d.pvW, d.pvV, d.battV, d.battPct, d.chgA, d.dischgA, d.outW, d.loadPct, d.gridV, d.mode.c_str());
}

bool dataFresh() { return d.everOk && (millis() - d.lastOk) < STALE_MS; }

// keep numbers sane so the JSON can never break
float clampF(float v) { if (!(v == v)) return 0; if (v > 99999) return 99999; if (v < -99999) return -99999; return v; }
long  clampI(long v)  { if (v > 999999) return 999999; if (v < -999999) return -999999; return v; }

// only letters/digits/space and a few symbols reach the JSON (no quotes or backslashes)
String jsonSafe(const String& s) {
  String o;
  for (unsigned i = 0; i < s.length() && i < 60; i++) {
    char c = s[i];
    if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == ' ' || c == '/' ||
        c == '(' || c == ')' || c == '.' || c == ',' || c == ':' || c == '-' || c == '#' || c == '_' || c == '?') o += c;
  }
  return o;
}

String buildJson() {
  char buf[900];
  unsigned long age = d.everOk ? (millis() - d.lastOk) / 1000 : 0;
  int n = snprintf(buf, sizeof(buf),
    "{\"ok\":%s,\"ever\":%s,\"age\":%lu,\"mode\":\"%s\",\"err\":\"%s\",\"pvW\":%ld,\"pvV\":%.1f,\"pvA\":%.1f,"
    "\"battV\":%.2f,\"battPct\":%ld,\"chgA\":%.1f,\"dischgA\":%.1f,"
    "\"outW\":%ld,\"outVA\":%ld,\"loadPct\":%ld,\"outV\":%.1f,\"outHz\":%.1f,"
    "\"gridV\":%.1f,\"gridHz\":%.1f,\"tempC\":%ld,\"busV\":%.0f,"
    "\"okCount\":%lu,\"failCount\":%lu,\"crcErrors\":%lu,\"uptime\":%lu}",
    dataFresh() ? "true" : "false", d.everOk ? "true" : "false", age,
    jsonSafe(d.mode).c_str(), jsonSafe(d.lastError).c_str(),
    clampI(d.pvW), clampF(d.pvV), clampF(d.pvA), clampF(d.battV), clampI(d.battPct), clampF(d.chgA), clampF(d.dischgA),
    clampI(d.outW), clampI(d.outVA), clampI(d.loadPct), clampF(d.outV), clampF(d.outHz),
    clampF(d.gridV), clampF(d.gridHz), clampI(d.tempC), clampF(d.busV),
    d.okCount, d.failCount, d.crcErrors, millis() / 1000);
  if (n < 0 || n >= (int)sizeof(buf)) return "{\"ok\":false,\"err\":\"json overflow\"}";
  return String(buf);
}

void handleData() { server.send(200, "application/json", buildJson()); }

const char PAGE[] PROGMEM = R"HTML(<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Solar Monitor</title>
<style>
body{margin:0;font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0}
h1{font-size:20px;margin:16px}#st{font-size:13px;margin:0 16px;color:#94a3b8}
.g{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;padding:16px}
.c{background:#1e293b;border-radius:12px;padding:14px}.l{font-size:12px;color:#94a3b8;text-transform:uppercase}
.v{font-size:28px;font-weight:700;margin-top:4px}.s{font-size:13px;color:#cbd5e1;margin-top:4px}
.pv .v{color:#facc15}.bt .v{color:#4ade80}.ld .v{color:#60a5fa}.gr .v{color:#f472b6}
.bar{height:6px;background:#334155;border-radius:3px;margin-top:8px}.bar i{display:block;height:100%;width:0;background:#4ade80;border-radius:3px}
.stale .v{opacity:.4}
</style></head><body><h1>&#9728; Solar Monitor</h1><div id="st">Connecting...</div>
<div class="g" id="g">
<div class="c pv"><div class="l">Solar</div><div class="v" id="pvW">-</div><div class="s" id="pvS"></div></div>
<div class="c bt"><div class="l">Battery</div><div class="v" id="bP">-</div><div class="s" id="bS"></div><div class="bar"><i id="bB"></i></div></div>
<div class="c ld"><div class="l">Load</div><div class="v" id="oW">-</div><div class="s" id="oS"></div></div>
<div class="c gr"><div class="l">Grid (WAPDA)</div><div class="v" id="gV">-</div><div class="s" id="gS"></div></div>
<div class="c"><div class="l">Mode</div><div class="v" style="font-size:20px" id="md">-</div><div class="s" id="tS"></div></div>
</div><script>
const $=i=>document.getElementById(i);
const n=(x,dp)=>{if(x===null||x===undefined||x==='')return '-';x=Number(x);return isFinite(x)?x.toFixed(dp):'-'};
let busy=false;
async function u(){if(busy)return;busy=true;
try{const ctl=new AbortController();const tm=setTimeout(()=>ctl.abort(),5000);
const r=await fetch('/data',{cache:'no-store',signal:ctl.signal});clearTimeout(tm);
if(!r.ok)throw new Error('http '+r.status);const d=await r.json();
$('g').classList.toggle('stale',!d.ok);
$('st').textContent=d.ok?'Live · updated '+d.age+'s ago':(d.ever?'No fresh data from inverter ('+d.age+'s old) · '+(d.err||''):'Waiting for inverter · '+(d.err||''));
if(d.ever){
$('pvW').textContent=n(d.pvW,0)+' W';$('pvS').textContent=n(d.pvV,1)+' V · '+n(d.pvA,1)+' A';
const p=Math.max(0,Math.min(100,Number(d.battPct)||0));
$('bP').textContent=p+' %';$('bB').style.width=p+'%';
$('bS').textContent=n(d.battV,2)+' V · '+(d.chgA>0?'charging '+n(d.chgA,0)+' A':d.dischgA>0?'discharging '+n(d.dischgA,0)+' A':'idle');
$('oW').textContent=n(d.outW,0)+' W';$('oS').textContent=n(d.loadPct,0)+'% · '+n(d.outV,0)+' V '+n(d.outHz,1)+' Hz';
$('gV').textContent=n(d.gridV,0)+' V';$('gS').textContent=d.gridV>100?n(d.gridHz,1)+' Hz · available':'not available';
$('md').textContent=d.mode||'-';$('tS').textContent='Inverter temp '+n(d.tempC,0)+' °C';}
}catch(e){$('st').textContent='ESP32 not reachable - check Wi-Fi';$('g').classList.add('stale')}
finally{busy=false}}
u();setInterval(u,3000);
</script></body></html>)HTML";

void startMdnsOnce() {
  if (!mdnsStarted && WiFi.status() == WL_CONNECTED) {
    mdnsStarted = MDNS.begin("solar");
    if (mdnsStarted) Serial.println("Or: http://solar.local/");
  }
}

void setup() {
  Serial.begin(115200);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, HIGH);
  Serial1.begin(2400, SERIAL_8N1, INV_RX_PIN, INV_TX_PIN);   // Voltronic = 2400 baud

  WiFi.mode(WIFI_STA);
  WiFi.setTxPower(WIFI_POWER_8_5dBm);   // helps Super Mini boards with weak antennas connect
  WiFi.setAutoReconnect(true);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  Serial.print("Connecting to Wi-Fi");
  for (int i = 0; i < 40 && WiFi.status() != WL_CONNECTED; i++) { delay(500); Serial.print("."); }
  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("\nConnected! Open: http://" + WiFi.localIP().toString() + "/");
  } else {
    Serial.println("\nWi-Fi failed - check name/password (2.4 GHz only). Will keep retrying.");
  }
  startMdnsOnce();

  server.on("/", [] { server.send_P(200, "text/html", PAGE); });
  server.on("/data", handleData);
  server.on("/raw", [] { server.send(200, "text/plain", d.raw.length() ? d.raw : String("no data yet: ") + d.lastError); });
  server.onNotFound([] { server.send(404, "text/plain", "Not found. Open / for the dashboard."); });
  server.begin();
}

void loop() {
  server.handleClient();
  static unsigned long last = 0;
  if (millis() - last >= POLL_MS) {
    last = millis();
    pollInverter();
    digitalWrite(LED_PIN, dataFresh() ? LOW : HIGH);   // LED on = inverter talking
  }
  static unsigned long lastTry = 0;
  if (WiFi.status() != WL_CONNECTED) {
    if (millis() - lastTry > 15000) { lastTry = millis(); WiFi.reconnect(); Serial.println("Wi-Fi lost, reconnecting..."); }
  } else if (!mdnsStarted) {
    Serial.println("Wi-Fi connected: http://" + WiFi.localIP().toString() + "/");
    startMdnsOnce();
  }
  delay(2);
}

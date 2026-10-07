/*
  Solar Monitor - ESP32-C3 Super Mini + MAX3232 + Inverex Veyron (Voltronic PI30 protocol)
  100% local: open http://<ESP32-IP>/ or http://solar.local/ on any phone/PC on your Wi-Fi.

  Wiring:
    MAX3232 VCC  -> ESP32 3V3
    MAX3232 GND  -> ESP32 GND
    MAX3232 TXD  -> ESP32 GPIO4 (RX)      (TTL side)
    MAX3232 RXD  -> ESP32 GPIO5 (TX)      (TTL side)
    MAX3232 DB9  -> inverter RS232 cable (RJ45 on inverter: pin1=TX, pin2=RX, pin8=GND)
  If you get "No response", swap the GPIO4/GPIO5 wires first.

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

#define INV_RX_PIN 4
#define INV_TX_PIN 5
#define LED_PIN    8      // onboard LED, ON when LOW
#define POLL_MS    3000

WebServer server(80);

struct Data {
  float gridV, gridHz, outV, outHz, battV, chgA, dischgA, pvV, pvA, busV;
  int outVA, outW, loadPct, battPct, tempC, pvW;
  String mode = "?";
  bool ok = false;
  unsigned long lastOk = 0;
  String raw;
} d;

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

// Send command, return payload without "(" and CRC, or "" on failure
String query(const char* cmd) {
  while (Serial1.available()) Serial1.read();   // flush
  uint16_t c = crc16((const uint8_t*)cmd, strlen(cmd));
  Serial1.print(cmd);
  Serial1.write(c >> 8);
  Serial1.write(c & 0xFF);
  Serial1.write('\r');

  String r;
  unsigned long t = millis();
  while (millis() - t < 2000) {
    if (Serial1.available()) {
      char ch = Serial1.read();
      if (ch == '\r') break;
      r += ch;
    }
  }
  if (r.length() < 4 || r[0] != '(') return "";
  return r.substring(1, r.length() - 2);        // drop '(' and 2 CRC bytes
}

void pollInverter() {
  String s = query("QPIGS");
  if (s.length() == 0) {
    d.ok = false;
    Serial.println("QPIGS: No response (check wiring / swap TX-RX)");
    return;
  }
  d.raw = s;
  String f[24]; int n = 0, start = 0;
  for (int i = 0; i <= (int)s.length() && n < 24; i++) {
    if (i == (int)s.length() || s[i] == ' ') { f[n++] = s.substring(start, i); start = i + 1; }
  }
  if (n < 16) { d.ok = false; Serial.println("QPIGS: short reply: " + s); return; }

  d.gridV = f[0].toFloat();   d.gridHz = f[1].toFloat();
  d.outV  = f[2].toFloat();   d.outHz  = f[3].toFloat();
  d.outVA = f[4].toInt();     d.outW   = f[5].toInt();
  d.loadPct = f[6].toInt();   d.busV   = f[7].toFloat();
  d.battV = f[8].toFloat();   d.chgA   = f[9].toFloat();
  d.battPct = f[10].toInt();  d.tempC  = f[11].toInt();
  d.pvA   = f[12].toFloat();  d.pvV    = f[13].toFloat();
  d.dischgA = f[15].toFloat();
  d.pvW = (n > 19 && f[19].toInt() > 0) ? f[19].toInt() : (int)(d.pvA * d.pvV);

  String m = query("QMOD");
  if (m.length()) {
    switch (m[0]) {
      case 'L': d.mode = "Grid (Line)"; break;
      case 'B': d.mode = "Battery / Solar"; break;
      case 'S': d.mode = "Standby"; break;
      case 'F': d.mode = "FAULT"; break;
      case 'H': d.mode = "Power saving"; break;
      case 'P': d.mode = "Power on"; break;
      default:  d.mode = m;
    }
  }
  d.ok = true; d.lastOk = millis();
  Serial.printf("PV %dW %.1fV | Batt %.2fV %d%% +%.0fA -%.0fA | Load %dW %d%% | Grid %.0fV | %s\n",
                d.pvW, d.pvV, d.battV, d.battPct, d.chgA, d.dischgA, d.outW, d.loadPct, d.gridV, d.mode.c_str());
}

void handleData() {
  char buf[600];
  snprintf(buf, sizeof(buf),
    "{\"ok\":%s,\"age\":%lu,\"mode\":\"%s\",\"pvW\":%d,\"pvV\":%.1f,\"pvA\":%.1f,"
    "\"battV\":%.2f,\"battPct\":%d,\"chgA\":%.1f,\"dischgA\":%.1f,"
    "\"outW\":%d,\"outVA\":%d,\"loadPct\":%d,\"outV\":%.1f,\"outHz\":%.1f,"
    "\"gridV\":%.1f,\"gridHz\":%.1f,\"tempC\":%d,\"busV\":%.0f}",
    d.ok ? "true" : "false", (millis() - d.lastOk) / 1000, d.mode.c_str(),
    d.pvW, d.pvV, d.pvA, d.battV, d.battPct, d.chgA, d.dischgA,
    d.outW, d.outVA, d.loadPct, d.outV, d.outHz, d.gridV, d.gridHz, d.tempC, d.busV);
  server.send(200, "application/json", buf);
}

const char PAGE[] PROGMEM = R"HTML(<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Solar Monitor</title>
<style>
body{margin:0;font-family:system-ui,sans-serif;background:#0f172a;color:#e2e8f0}
h1{font-size:20px;margin:16px}#st{font-size:13px;margin:0 16px;color:#94a3b8}
.g{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;padding:16px}
.c{background:#1e293b;border-radius:12px;padding:14px}.l{font-size:12px;color:#94a3b8;text-transform:uppercase}
.v{font-size:28px;font-weight:700;margin-top:4px}.s{font-size:13px;color:#cbd5e1;margin-top:4px}
.pv .v{color:#facc15}.bt .v{color:#4ade80}.ld .v{color:#60a5fa}.gr .v{color:#f472b6}
.bar{height:6px;background:#334155;border-radius:3px;margin-top:8px}.bar i{display:block;height:100%;background:#4ade80;border-radius:3px}
</style></head><body><h1>&#9728; Solar Monitor</h1><div id="st">Connecting...</div>
<div class="g">
<div class="c pv"><div class="l">Solar</div><div class="v" id="pvW">-</div><div class="s" id="pvS"></div></div>
<div class="c bt"><div class="l">Battery</div><div class="v" id="bP">-</div><div class="s" id="bS"></div><div class="bar"><i id="bB"></i></div></div>
<div class="c ld"><div class="l">Load</div><div class="v" id="oW">-</div><div class="s" id="oS"></div></div>
<div class="c gr"><div class="l">Grid (WAPDA)</div><div class="v" id="gV">-</div><div class="s" id="gS"></div></div>
<div class="c"><div class="l">Mode</div><div class="v" style="font-size:20px" id="md">-</div><div class="s" id="tS"></div></div>
</div><script>
const $=i=>document.getElementById(i);
async function u(){try{const d=await(await fetch('/data')).json();
$('st').textContent=d.ok?'Live · updated '+d.age+'s ago':'No data from inverter ('+d.age+'s)';
$('pvW').textContent=d.pvW+' W';$('pvS').textContent=d.pvV+' V · '+d.pvA+' A';
$('bP').textContent=d.battPct+' %';$('bB').style.width=d.battPct+'%';
$('bS').textContent=d.battV+' V · '+(d.chgA>0?'charging '+d.chgA+' A':d.dischgA>0?'discharging '+d.dischgA+' A':'idle');
$('oW').textContent=d.outW+' W';$('oS').textContent=d.loadPct+'% · '+d.outV+' V '+d.outHz+' Hz';
$('gV').textContent=d.gridV+' V';$('gS').textContent=d.gridV>100?d.gridHz+' Hz · available':'not available';
$('md').textContent=d.mode;$('tS').textContent='Inverter temp '+d.tempC+' °C';
}catch(e){$('st').textContent='ESP32 not reachable'}}
u();setInterval(u,3000);
</script></body></html>)HTML";

void setup() {
  Serial.begin(115200);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, HIGH);
  Serial1.begin(2400, SERIAL_8N1, INV_RX_PIN, INV_TX_PIN);   // Voltronic = 2400 baud

  WiFi.mode(WIFI_STA);
  WiFi.setTxPower(WIFI_POWER_8_5dBm);   // helps Super Mini boards with weak antennas connect
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  Serial.print("Connecting to Wi-Fi");
  for (int i = 0; i < 40 && WiFi.status() != WL_CONNECTED; i++) { delay(500); Serial.print("."); }
  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("\nConnected! Open: http://" + WiFi.localIP().toString() + "/");
    if (MDNS.begin("solar")) Serial.println("Or: http://solar.local/");
  } else {
    Serial.println("\nWi-Fi failed - check name/password (2.4 GHz only). Will keep retrying.");
  }

  server.on("/", [] { server.send_P(200, "text/html", PAGE); });
  server.on("/data", handleData);
  server.on("/raw", [] { server.send(200, "text/plain", d.raw); });
  server.begin();
}

void loop() {
  server.handleClient();
  static unsigned long last = 0;
  if (millis() - last > POLL_MS) {
    last = millis();
    pollInverter();
    digitalWrite(LED_PIN, d.ok ? LOW : HIGH);   // LED on = inverter talking
  }
  if (WiFi.status() != WL_CONNECTED) {
    static unsigned long lastTry = 0;
    if (millis() - lastTry > 15000) { lastTry = millis(); WiFi.reconnect(); }
  }
}

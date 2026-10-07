/*
  =====================================================================
   ESP32-C3 SUPER MINI - TEST TOOL  (for Yasir's solar monitor project)
  =====================================================================

  HOW TO USE
  ----------
  1. Open this file in Arduino IDE.
  2. Tools -> Board -> esp32 -> ESP32C3 Dev Module
     Tools -> USB CDC On Boot -> Enabled
     Tools -> Port -> COM3 (or whatever COM port appears)
  3. Click Upload (the -> arrow).
  4. Open Tools -> Serial Monitor. Set the speed box (bottom right) to 115200.
  5. Type a command in the box at the top of Serial Monitor and press Enter.

  COMMANDS (type them in the Serial Monitor)
  ------------------------------------------
  help              -> show this list
  led on            -> blue LED on   (LED is on GPIO8, ON when LOW)
  led off           -> blue LED off
  blink             -> blink the LED 5 times
  high 10           -> set GPIO10 to 3.3V   (measure with multimeter)
  low 10            -> set GPIO10 to 0V
  read 10           -> read GPIO10, shows 0 or 1
  toggle 20         -> GPIO20 switches 0V/3.3V every 2 sec, 5 times (multimeter test)
  loop 10 20        -> serial loopback test: RX=GPIO10, TX=GPIO20
                       (short the two RS232 wires together, or jumper the pins directly)
  inverter 10 20    -> ask the inverter for data (QPIGS) with RX=10, TX=20, show raw reply

  SAFE PINS ON THE SUPER MINI
  ---------------------------
  Good to use:  0, 1, 3, 4, 5, 6, 7, 10, 20, 21
  Avoid:        2, 8, 9  (used at startup; 8 is the LED, 9 is BOOT button)
                18, 19   (used by USB - using them breaks uploading)

  CHANGING PINS IN YOUR SOLAR PROGRAM (solar_monitor.ino)
  -------------------------------------------------------
  Find these two lines near the top and change the numbers:
      #define INV_RX_PIN 10    <- pin connected to MAX3232 TXD (signal coming IN to ESP32)
      #define INV_TX_PIN 20    <- pin connected to MAX3232 RXD (signal going OUT of ESP32)
  Then press Ctrl+S and Upload.

  IF UPLOAD GETS STUCK ON "Connecting..."
  ---------------------------------------
  Hold BOOT, tap RST, release BOOT, click Upload again. Press RST after upload.
  =====================================================================
*/

#define LED_PIN 8

String line;

void help() {
  Serial.println();
  Serial.println("===== ESP32 TEST TOOL - commands =====");
  Serial.println(" led on | led off | blink");
  Serial.println(" high <pin> | low <pin> | read <pin> | toggle <pin>");
  Serial.println(" loop <rx> <tx>      e.g. loop 10 20");
  Serial.println(" inverter <rx> <tx>  e.g. inverter 10 20");
  Serial.println(" Safe pins: 0 1 3 4 5 6 7 10 20 21   (avoid 2 8 9 18 19)");
  Serial.println("======================================");
}

bool safePin(int p) {
  if (p == 18 || p == 19) { Serial.println("NO: GPIO18/19 are USB pins - using them breaks uploading."); return false; }
  if (p < 0 || p > 21 || (p > 10 && p < 20)) { Serial.println("NO: Super Mini pins are 0-10 and 20-21."); return false; }
  return true;
}

uint16_t crc16(const uint8_t* p, size_t len) {          // Voltronic CRC
  uint16_t crc = 0;
  while (len--) { crc ^= (uint16_t)(*p++) << 8; for (int i = 0; i < 8; i++) crc = (crc & 0x8000) ? (crc << 1) ^ 0x1021 : (crc << 1); }
  uint8_t hi = crc >> 8, lo = crc & 0xFF;
  if (hi == 0x28 || hi == 0x0D || hi == 0x0A) hi++;
  if (lo == 0x28 || lo == 0x0D || lo == 0x0A) lo++;
  return (hi << 8) | lo;
}

void loopTest(int rx, int tx) {
  Serial1.end();
  Serial1.begin(2400, SERIAL_8N1, rx, tx);
  delay(50);
  for (int n = 1; n <= 5; n++) {
    while (Serial1.available()) Serial1.read();
    Serial1.print("HELLO123");
    String got; unsigned long t = millis();
    while (millis() - t < 300) { while (Serial1.available()) { if (got.length() < 40) got += (char)Serial1.read(); else Serial1.read(); } delay(1); }
    Serial.printf("  try %d: %s  [%s]\n", n, got == "HELLO123" ? "PASS" : (got.length() ? "GARBAGE (loose wire/GND?)" : "nothing (check wires / swap pins)"), got.c_str());
    delay(500);
  }
  Serial1.end();
}

void inverterTest(int rx, int tx) {
  Serial1.end();
  Serial1.begin(2400, SERIAL_8N1, rx, tx);
  delay(50);
  while (Serial1.available()) Serial1.read();
  const char* cmd = "QPIGS";
  uint16_t c = crc16((const uint8_t*)cmd, 5);
  Serial1.print(cmd); Serial1.write((uint8_t)(c >> 8)); Serial1.write((uint8_t)(c & 0xFF)); Serial1.write('\r');
  String r; bool done = false; unsigned long t = millis();
  while (millis() - t < 2000 && !done) {
    while (Serial1.available()) { char ch = Serial1.read(); if (ch == '\r') { done = true; break; } if (r.length() < 300) r += ch; }
    delay(1);
  }
  if (r.length() == 0)      Serial.println("  No reply. Check RJ45 pins 1/2/8, try swapping RS232 TX/RX.");
  else if (r[0] == 'Q')     Serial.println("  Got my own message back = RS232 wires are shorted, not connected to inverter.");
  else if (r[0] == '(') {
    Serial.println("  INVERTER ANSWERED! Raw data: " + r.substring(0, r.length() >= 2 ? r.length() - 2 : 0));
    if (r.length() >= 4) {
      uint16_t want = crc16((const uint8_t*)r.c_str(), r.length() - 2);
      uint16_t got = ((uint8_t)r[r.length() - 2] << 8) | (uint8_t)r[r.length() - 1];
      Serial.println(want == got ? "  CRC OK - data is clean." : "  CRC BAD - noise on the cable? Check GND (RJ45 pin 8).");
    }
    if (!done) Serial.println("  (reply had no end character - cable may be loose)");
  }
  else                      Serial.println("  Got something unexpected: " + r);
  Serial1.end();
}

// true if s is a whole number like "10"
bool isInt(const String& s) {
  if (s.length() == 0 || s.length() > 3) return false;
  for (unsigned i = 0; i < s.length(); i++) if (s[i] < '0' || s[i] > '9') return false;
  return true;
}

void runCommand(String cmd) {
  cmd.trim(); cmd.toLowerCase();
  while (cmd.indexOf("  ") >= 0) cmd.replace("  ", " ");
  if (cmd.length() == 0) return;
  Serial.println("> " + cmd);

  String w[4]; int n = 0, start = 0;
  for (int i = 0; i <= (int)cmd.length() && n < 4; i++)
    if (i == (int)cmd.length() || cmd[i] == ' ') { w[n++] = cmd.substring(start, i); start = i + 1; }
  String word = w[0];
  bool needOne = (word == "high" || word == "low" || word == "read" || word == "toggle");
  bool needTwo = (word == "loop" || word == "inverter");

  if (cmd == "help") { help(); return; }
  if (cmd == "led on")  { digitalWrite(LED_PIN, LOW);  Serial.println("  LED is ON"); return; }
  if (cmd == "led off") { digitalWrite(LED_PIN, HIGH); Serial.println("  LED is OFF"); return; }
  if (cmd == "blink") {
    for (int i = 0; i < 5; i++) { digitalWrite(LED_PIN, LOW); delay(300); digitalWrite(LED_PIN, HIGH); delay(300); }
    Serial.println("  Blinked 5 times"); return;
  }
  if (needOne) {
    if (n != 2 || !isInt(w[1])) { Serial.println("  Usage: " + word + " <pin>   e.g. " + word + " 10"); return; }
    int a = w[1].toInt();
    if (!safePin(a)) return;
    if (a == LED_PIN) Serial.println("  Note: GPIO8 is the blue LED.");
    if (word == "high") { pinMode(a, OUTPUT); digitalWrite(a, HIGH); Serial.printf("  GPIO%d = HIGH (3.3V). Measure it now.\n", a); }
    else if (word == "low") { pinMode(a, OUTPUT); digitalWrite(a, LOW); Serial.printf("  GPIO%d = LOW (0V). Measure it now.\n", a); }
    else if (word == "read") { pinMode(a, INPUT); int v = digitalRead(a); Serial.printf("  GPIO%d reads %d  (%s)\n", a, v, v ? "about 3.3V" : "about 0V"); }
    else {
      pinMode(a, OUTPUT);
      for (int i = 0; i < 5; i++) {
        digitalWrite(a, HIGH); Serial.printf("  GPIO%d = 3.3V\n", a); delay(2000);
        digitalWrite(a, LOW);  Serial.printf("  GPIO%d = 0V\n", a);   delay(2000);
      }
    }
    return;
  }
  if (needTwo) {
    if (n != 3 || !isInt(w[1]) || !isInt(w[2])) { Serial.println("  Usage: " + word + " <rx> <tx>   e.g. " + word + " 10 20"); return; }
    int a = w[1].toInt(), b = w[2].toInt();
    if (!safePin(a) || !safePin(b)) return;
    if (a == b) { Serial.println("  RX and TX must be two different pins."); return; }
    if (word == "loop") { Serial.printf("  Loopback RX=GPIO%d TX=GPIO%d\n", a, b); loopTest(a, b); }
    else { Serial.printf("  Asking inverter, RX=GPIO%d TX=GPIO%d\n", a, b); inverterTest(a, b); }
    return;
  }
  Serial.println("  Unknown command. Type: help");
}

void setup() {
  Serial.begin(115200);
  pinMode(LED_PIN, OUTPUT);
  digitalWrite(LED_PIN, HIGH);
  delay(1500);
  Serial.println("\nESP32 Test Tool ready. Type 'help' and press Enter.");
  help();
}

void loop() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') { runCommand(line); line = ""; }
    else if (line.length() < 64) line += c;
  }
}

/*
  MAX3232 loopback test: TX=GPIO20, RX=GPIO10, 2400 baud.
*/
const char* MSG = "HELLO123";

void setup() {
  Serial.begin(115200);
  Serial1.begin(2400, SERIAL_8N1, 10, 20);
  delay(1500);
}

void loop() {
  while (Serial1.available()) Serial1.read();
  Serial1.print(MSG);
  String got; unsigned long t = millis();
  while (millis() - t < 300) if (Serial1.available()) got += (char)Serial1.read();
  Serial.printf("UART RX=GPIO10 TX=GPIO20 : %s  [%s]\n", got == MSG ? "PASS" : (got.length() ? "GARBAGE" : "nothing"), got.c_str());
  delay(1500);
}

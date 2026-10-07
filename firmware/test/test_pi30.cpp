// Host-side tests for the PI30 protocol code (firmware/solar_monitor_v3/pi30.h).
// Build & run:  firmware/test/run.ps1  (or: g++ -std=c++17 -I../solar_monitor_v3 test_pi30.cpp && ./a.out)
#include "pi30.h"

#include <cstdio>
#include <string>

static int failures = 0, checks = 0;
#define CHECK(cond)                                                              \
  do {                                                                           \
    checks++;                                                                    \
    if (!(cond)) { failures++; printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); } \
  } while (0)
#define CHECK_NEAR(a, b) CHECK(std::fabs((double)(a) - (double)(b)) < 1e-3)

// A real QPIGS reply captured from an Inverex Veyron IV 3.2 kW (payload only).
static const char* REAL_QPIGS =
    "000.0 00.0 228.8 50.0 0800 0790 025 436 27.50 000 100 0043 01.8 399.7 00.00 00002 00010000 00 00 00749 011 0 01 0000 00.0";

// Wraps a payload the way the inverter sends it: payload + CRC (with Voltronic tweak), no '(' / CR.
static std::string framed(const char* payload, bool tweak = true) {
  uint16_t c = crcRaw(crcRaw(0, (const uint8_t*)"(", 1), (const uint8_t*)payload, strlen(payload));
  if (tweak) c = crcFix(c);
  std::string s(payload);
  s.push_back((char)(c >> 8));
  s.push_back((char)(c & 0xFF));
  return s;
}

static int check(std::string s, char* out) {
  memcpy(out, s.data(), s.size());
  return checkReply(out, s.size());
}

static void testRequestCrc() {
  // Published request frames for these commands.
  struct { const char* cmd; uint8_t hi, lo; } v[] = {
      {"QPIGS", 0xB7, 0xA9}, {"QMOD", 0x49, 0xC1}, {"QPIRI", 0xF8, 0x54},
      {"QPIWS", 0xB4, 0xDA}, {"QID", 0xD6, 0xEA}, {"QVFW", 0x62, 0x99},
  };
  for (auto& x : v) {
    uint8_t f[24];
    size_t n = buildRequest(x.cmd, f, sizeof f);
    CHECK(n == strlen(x.cmd) + 3);
    CHECK(f[n - 3] == x.hi);
    CHECK(f[n - 2] == x.lo);
    CHECK(f[n - 1] == '\r');
  }
  uint8_t small[4];
  CHECK(buildRequest("QPIGS", small, sizeof small) == 0);   // doesn't fit
}

static void testCrcNeverReservedBytes() {
  // The tweak must never produce '(' , CR or LF, otherwise framing breaks.
  for (uint32_t c = 0; c <= 0xFFFF; c++) {
    uint16_t f = crcFix((uint16_t)c);
    uint8_t hi = f >> 8, lo = f & 0xFF;
    if (hi == 0x28 || hi == 0x0D || hi == 0x0A || lo == 0x28 || lo == 0x0D || lo == 0x0A) { CHECK(false); return; }
  }
  CHECK(true);
}

static void testCheckReply() {
  char buf[256];
  CHECK(check(framed(REAL_QPIGS), buf) == (int)strlen(REAL_QPIGS));
  CHECK(strcmp(buf, REAL_QPIGS) == 0);
  // some firmwares send the CRC without the tweak: accepted too
  CHECK(check(framed("B", false), buf) == 1);
  // corrupted payload byte -> CRC error
  std::string bad = framed(REAL_QPIGS);
  bad[5] = '9';
  CHECK(check(bad, buf) == REPLY_CRC);
  // flipped CRC byte
  std::string bad2 = framed("L");
  bad2[1] ^= 0x01;
  CHECK(check(bad2, buf) == REPLY_CRC);
  // too short
  CHECK(check(std::string("A"), buf) == REPLY_TOO_SHORT);
  CHECK(check(std::string(""), buf) == REPLY_TOO_SHORT);
  // NAK (valid CRC)
  CHECK(check(framed("NAK"), buf) == REPLY_NAK);
}

static void testParseRealQpigs() {
  Live L;
  L.mode = 'B';
  char err[64];
  CHECK(parseQPIGS(REAL_QPIGS, L, err, sizeof err));
  CHECK(err[0] == 0);
  CHECK_NEAR(L.gridV, 0.0);
  CHECK(!L.gridOn);
  CHECK_NEAR(L.outV, 228.8);
  CHECK_NEAR(L.outHz, 50.0);
  CHECK(L.outVA == 800);
  CHECK(L.outW == 790);
  CHECK(L.loadPct == 25);
  CHECK(L.busV == 436);
  CHECK_NEAR(L.battV, 27.5);
  CHECK(L.battPct == 100);
  CHECK(L.tempC == 43);
  CHECK_NEAR(L.pvA, 1.8);
  CHECK_NEAR(L.pvV, 399.7);
  CHECK_NEAR(L.dischgA, 2.0);
  CHECK(strcmp(L.st, "00010000") == 0);
  CHECK(strcmp(L.st2, "011") == 0);
  CHECK(L.pvChgW == 749);
  CHECK(L.pvW == 749);           // field 19 preferred over V x A (719)
  CHECK(L.battW == -55);         // 27.5 V x (0 - 2 A)
  CHECK(L.mode == 'B');          // mode untouched by QPIGS
}

static void testParseFallbacksAndClamps() {
  Live L;
  char err[64];
  // no field 19 -> PV power = V x A; battery % clamped to 0..100
  CHECK(parseQPIGS("230.0 50.0 230.0 50.0 0500 0480 015 400 26.00 010 150 0035 02.0 300.0 00.00 00000 00000110", L, err, sizeof err));
  CHECK(L.pvW == 600);
  CHECK(L.battPct == 100);
  CHECK(L.gridOn);
  CHECK(L.battW == 260);
  CHECK(parseQPIGS("230.0 50.0 230.0 50.0 0500 0480 015 400 26.00 010 -5 0035 02.0 300.0 00.00 00000 00000110", L, err, sizeof err));
  CHECK(L.battPct == 0);
  // repeated spaces are fine
  CHECK(parseQPIGS("230.0  50.0 230.0 50.0 0500 0480 015 400 26.00 010 50 0035 02.0 300.0 00.00 00000 00000110", L, err, sizeof err));
  CHECK(L.battPct == 50);
}

static void testParseRejectsGarbage() {
  Live L;
  L.battPct = 77;
  char err[64];
  CHECK(!parseQPIGS("", L, err, sizeof err));
  CHECK(strstr(err, "short") != nullptr);
  CHECK(!parseQPIGS("000.0 00.0 228.8", L, err, sizeof err));                     // partial frame
  CHECK(!parseQPIGS("000.0 00.0 228.8 50.0 0800 0790 0x5 436 27.50 000 100 0043 01.8 399.7 00.00 00002 00010000", L, err, sizeof err));
  CHECK(strstr(err, "bad field 6") != nullptr);
  CHECK(!parseQPIGS("a b c d e f g h i j k l m n o p q r s t", L, err, sizeof err));
  CHECK(L.battPct == 77);                                                           // untouched on failure
}

static void testGridEstimate() {
  Live L;
  char err[64];
  // Line mode, charging from grid: grid = load + charge - solar
  parseQPIGS("230.0 50.0 230.0 50.0 0500 0400 015 400 26.00 010 080 0035 00.0 000.0 00.00 00000 00000101 00 00 00000", L, err, sizeof err);
  L.mode = 'L';
  estimateGrid(L);
  CHECK(L.gridW == 400 + 260);
  // battery mode, grid present but unused
  L.mode = 'B';
  strcpy(L.st, "00010000");
  estimateGrid(L);
  CHECK(L.gridW == 0);
  // solar covers everything in line mode -> never negative
  L.mode = 'L';
  L.pvW = 5000;
  estimateGrid(L);
  CHECK(L.gridW == 0);
  // grid absent
  L.gridOn = false;
  estimateGrid(L);
  CHECK(L.gridW == 0);
}

static void testHelpers() {
  CHECK(isNum("12.5"));
  CHECK(isNum("-3"));
  CHECK(isNum("+0"));
  CHECK(!isNum(""));
  CHECK(!isNum("."));
  CHECK(!isNum("1-2"));
  CHECK(!isNum("NAK"));
  char d[4];
  CHECK(pi30Copy(d, "abcdef", sizeof d) == 6);
  CHECK(strcmp(d, "abc") == 0);
}

int main() {
  testRequestCrc();
  testCrcNeverReservedBytes();
  testCheckReply();
  testParseRealQpigs();
  testParseFallbacksAndClamps();
  testParseRejectsGarbage();
  testGridEstimate();
  testHelpers();
  printf("%d checks, %d failures\n", checks, failures);
  return failures ? 1 : 0;
}

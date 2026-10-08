// Host-side tests for the PI30 protocol code (firmware/solar_monitor_v3/pi30.h).
// Build & run:  firmware/test/run.ps1  (or: g++ -std=c++17 -I../solar_monitor_v3 test_pi30.cpp && ./a.out)
#include "pi30.h"
#include "pi18.h"
#include "invset.h"

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


// Real QPIRI from the Inverex Veyron (24 V battery)
static const char* REAL_QPIRI = "230.0 13.9 230.0 50.0 13.9 3200 3200 24.0 24.0 22.1 27.2 26.8 02 010 050 1 1 1 1 01 0 0 26.5 0 1";

static std::string setCmd(const char* key, float v, char letter = 0, const char* chg = "010 020 030 040 050 060", const char* ac = "002 010 020 030") {
  char cmd[24] = "", err[64] = "";
  return buildSetCommand(key, v, letter, REAL_QPIRI, chg, ac, cmd, sizeof cmd, err, sizeof err) ? std::string(cmd) : std::string("ERR ") + err;
}
static bool isErr(const std::string& s) { return s.rfind("ERR", 0) == 0; }

static void testSetCommands() {
  CHECK(setCmd("outPrio", 2) == "POP02");
  CHECK(isErr(setCmd("outPrio", 3)));
  CHECK(setCmd("chgPrio", 3) == "PCP03");
  CHECK(setCmd("range", 0) == "PGR00");
  CHECK(setCmd("bulk", 28.2f) == "PCVV28.2");
  CHECK(isErr(setCmd("bulk", 26.0f)));      // below float 26.8
  CHECK(isErr(setCmd("bulk", 29.3f)));      // above 29.2 for 24 V
  CHECK(setCmd("float", 27.0f) == "PBFT27.0");
  CHECK(isErr(setCmd("float", 27.5f)));     // above bulk 27.2
  CHECK(setCmd("cutoff", 21.0f) == "PSDV21.0");
  CHECK(isErr(setCmd("cutoff", 20.9f)));
  CHECK(isErr(setCmd("cutoff", 24.0f)));    // not below back-to-grid 24.0
  CHECK(setCmd("recharge", 23.5f) == "PBCV23.5");
  CHECK(isErr(setCmd("recharge", 23.3f)));  // 0.5 V steps on 24 V
  CHECK(isErr(setCmd("recharge", 26.5f)));   // not below back-to-battery 26.5
  CHECK(setCmd("redischarge", 0) == "PBDV00.0");
  CHECK(setCmd("redischarge", 27.0f) == "PBDV27.0");
  CHECK(isErr(setCmd("redischarge", 23.5f)));
  CHECK(setCmd("maxChg", 60) == "MCHGC060");
  CHECK(isErr(setCmd("maxChg", 55)));
  CHECK(setCmd("maxChg", 100, 0, "060 080 100") == "MNCHGC0100");
  CHECK(isErr(setCmd("maxChg", 60, 0, "")));  // list not read: refuse
  CHECK(setCmd("maxAc", 20) == "MUCHGC020");
  CHECK(setCmd("flag", 1, 'a') == "PEa");
  CHECK(setCmd("flag", 0, 'x') == "PDx");
  CHECK(isErr(setCmd("flag", 1, 'q')));
  CHECK(isErr(setCmd("battType", 1)));
  char cmd[24], err[64];
  CHECK(!buildSetCommand("bulk", 28.0f, 0, "", "", "", cmd, sizeof cmd, err, sizeof err));   // nothing read yet
  // 48 V inverter: ranges scale
  const char* q48 = "230.0 21.7 230.0 50.0 21.7 5000 5000 48.0 46.0 42.0 56.4 54.0 2 30 60 0 2 1 9 01 0 0 54.0 0 1";
  CHECK(buildSetCommand("bulk", 57.6f, 0, q48, "", "", cmd, sizeof cmd, err, sizeof err) && std::string(cmd) == "PCVV57.6");
  CHECK(buildSetCommand("recharge", 47.0f, 0, q48, "", "", cmd, sizeof cmd, err, sizeof err) && std::string(cmd) == "PBCV47.0");
  CHECK(!buildSetCommand("recharge", 46.5f, 0, q48, "", "", cmd, sizeof cmd, err, sizeof err));
}

static void testSetVerify() {
  CHECK(setVerified("bulk", 27.2f, 0, REAL_QPIRI, ""));
  CHECK(!setVerified("bulk", 28.2f, 0, REAL_QPIRI, ""));
  CHECK(setVerified("maxChg", 50, 0, REAL_QPIRI, ""));
  CHECK(setVerified("outPrio", 1, 0, REAL_QPIRI, ""));
  CHECK(setVerified("redischarge", 26.5f, 0, REAL_QPIRI, ""));
  CHECK(setVerified("flag", 1, 'a', REAL_QPIRI, "EakxyzDbdjuv"));
  CHECK(setVerified("flag", 0, 'u', REAL_QPIRI, "EakxyzDbdjuv"));
  CHECK(!setVerified("flag", 1, 'u', REAL_QPIRI, "EakxyzDbdjuv"));
}

static void testPI18() {
  char cmd[16];
  CHECK(pi18Command("GS", cmd, sizeof cmd) && std::string(cmd) == "^P005GS");
  CHECK(pi18Command("PIRI", cmd, sizeof cmd) && std::string(cmd) == "^P007PIRI");
  const char* gs = "2300,500,2300,500,0920,0880,018,512,000,000,000,012,100,035,040,000,1500,0000,3800,0000,0,2,0,1,1,0,1,0";
  std::string fr = std::string("^D106") + gs;
  uint16_t c = crcFix(crcRaw(0, (const uint8_t*)fr.data(), fr.size()));
  fr.push_back((char)(c >> 8)); fr.push_back((char)(c & 0xFF));
  char buf[256];
  memcpy(buf, fr.data(), fr.size());
  int n = pi18CheckReply(buf, fr.size());
  CHECK(n == (int)strlen(gs));
  Live L; char err[64];
  CHECK(parsePI18GS(buf, L, err, sizeof err));
  CHECK_NEAR(L.gridV, 230.0); CHECK_NEAR(L.battV, 51.2); CHECK(L.outW == 880); CHECK(L.battPct == 100);
  CHECK(L.pvW == 1500); CHECK_NEAR(L.pvV, 380.0); CHECK_NEAR(L.chgA, 12); CHECK(L.battW == 614);
  CHECK(L.gridOn); CHECK(strcmp(L.st, "00010010") == 0);
  CHECK(pi18Mode("03") == 'B'); CHECK(pi18Mode("05") == 'L');
  buf[0] = '^'; buf[1] = '0'; uint16_t c2 = crcFix(crcRaw(0, (const uint8_t*)"^0", 2)); buf[2] = (char)(c2 >> 8); buf[3] = (char)(c2 & 0xFF);
  CHECK(pi18CheckReply(buf, 4) == REPLY_NAK);
  CHECK(!parsePI18GS("1,2,3", L, err, sizeof err));
  uint8_t mb[8];
  CHECK(modbusRead(1, 3, 0x0000, 10, mb) == 8);
  CHECK(mb[6] == 0xC5 && mb[7] == 0xCD);   // known frame 01 03 00 00 00 0A C5 CD
  CHECK(modbusRead(1, 6, 0, 1, mb) == 0);  // writes are never built
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
  testSetCommands();
  testSetVerify();
  testPI18();
  printf("%d checks, %d failures\n", checks, failures);
  return failures ? 1 : 0;
}

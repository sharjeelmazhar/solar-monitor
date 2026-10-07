#pragma once
// ============================================================================
//  Voltronic PI30 protocol: pure functions only (no Arduino), so they can be
//  unit-tested on a PC (see firmware/test/).
//  Request:  <ASCII command><CRC hi><CRC lo><CR>
//  Reply:    '(' <payload> <CRC hi><CRC lo> <CR>
// ============================================================================
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

struct Live {
  float gridV = 0, gridHz = 0, outV = 0, outHz = 0, battV = 0, battVscc = 0, chgA = 0, dischgA = 0, pvV = 0, pvA = 0;
  int busV = 0, outVA = 0, outW = 0, loadPct = 0, battPct = 0, tempC = 0, pvW = 0, pvChgW = 0;
  int battW = 0;        // + charging, - discharging
  int gridW = 0;        // estimated power taken from the grid
  bool gridOn = false;
  char mode = '?';      // QMOD letter
  char st[9] = "";      // QPIGS device status bits b7..b0
  char st2[4] = "";     // QPIGS device status 2 bits b10..b8
  char warn[40] = "";   // QPIWS bit string
};

// Copies at most cap-1 chars and always terminates (strlcpy is not portable).
inline size_t pi30Copy(char* dst, const char* src, size_t cap) {
  size_t n = strlen(src);
  if (cap) {
    size_t m = n < cap - 1 ? n : cap - 1;
    memcpy(dst, src, m);
    dst[m] = 0;
  }
  return n;
}

// CRC-CCITT (XMODEM) as used by Voltronic...
inline uint16_t crcRaw(uint16_t crc, const uint8_t* p, size_t len) {
  while (len--) {
    crc ^= (uint16_t)(*p++) << 8;
    for (int i = 0; i < 8; i++) crc = (crc & 0x8000) ? (crc << 1) ^ 0x1021 : (crc << 1);
  }
  return crc;
}
// ...with their tweak: a CRC byte never equals '(' , CR or LF.
inline uint16_t crcFix(uint16_t crc) {
  uint8_t hi = crc >> 8, lo = crc & 0xFF;
  if (hi == 0x28 || hi == 0x0D || hi == 0x0A) hi++;
  if (lo == 0x28 || lo == 0x0D || lo == 0x0A) lo++;
  return (hi << 8) | lo;
}

// Builds a request frame. Returns its length, or 0 if it doesn't fit.
inline size_t buildRequest(const char* cmd, uint8_t* out, size_t cap) {
  size_t cl = strlen(cmd);
  if (cl + 3 > cap) return 0;
  memcpy(out, cmd, cl);
  uint16_t c = crcFix(crcRaw(0, (const uint8_t*)cmd, cl));
  out[cl] = c >> 8;
  out[cl + 1] = c & 0xFF;
  out[cl + 2] = '\r';
  return cl + 3;
}

enum ReplyStatus { REPLY_OK = 0, REPLY_TOO_SHORT = -1, REPLY_CRC = -2, REPLY_NAK = -3 };

// buf holds the bytes between '(' and CR (payload + 2 CRC bytes), n its length.
// On success the CRC bytes are cut off, buf is NUL-terminated and the payload length is returned.
inline int checkReply(char* buf, size_t n) {
  if (n < 3) return REPLY_TOO_SHORT;
  uint16_t got = ((uint8_t)buf[n - 2] << 8) | (uint8_t)buf[n - 1];
  n -= 2;
  uint16_t want = crcRaw(crcRaw(0, (const uint8_t*)"(", 1), (const uint8_t*)buf, n);
  if (got != crcFix(want) && got != want) return REPLY_CRC;   // accept both CRC variants seen in the wild
  buf[n] = 0;
  if (strncmp(buf, "NAK", 3) == 0) return REPLY_NAK;
  return (int)n;
}

inline bool isNum(const char* s) {
  if (!*s) return false;
  bool digit = false;
  for (int i = 0; s[i]; i++) {
    char c = s[i];
    if (c >= '0' && c <= '9') digit = true;
    else if (!(c == '.' || ((c == '-' || c == '+') && i == 0))) return false;
  }
  return digit;
}

// Splits s (modified in place) on spaces. Returns field count.
inline int splitFields(char* s, char** f, int maxF) {
  int n = 0;
  char* p = s;
  while (*p && n < maxF) {
    while (*p == ' ') p++;
    if (!*p) break;
    f[n++] = p;
    while (*p && *p != ' ') p++;
    if (*p) *p++ = 0;
  }
  return n;
}

// QPIGS: general status. Returns false (leaving L untouched, reason in err) if the reply looks wrong.
inline bool parseQPIGS(const char* payload, Live& L, char* err, size_t errCap) {
  char tmp[160];
  pi30Copy(tmp, payload, sizeof(tmp));
  char* f[28];
  int n = splitFields(tmp, f, 28);
  if (n < 17) { snprintf(err, errCap, "QPIGS: short reply (%d fields)", n); return false; }
  for (int i = 0; i < 16; i++)
    if (!isNum(f[i])) { snprintf(err, errCap, "QPIGS: bad field %d", i); return false; }
  Live o = L;   // keep mode / warnings
  o.gridV = atof(f[0]);  o.gridHz = atof(f[1]);
  o.outV  = atof(f[2]);  o.outHz  = atof(f[3]);
  o.outVA = atoi(f[4]);  o.outW   = atoi(f[5]);
  o.loadPct = atoi(f[6]); o.busV  = atoi(f[7]);
  o.battV = atof(f[8]);  o.chgA   = atof(f[9]);
  int pct = atoi(f[10]);
  o.battPct = pct < 0 ? 0 : pct > 100 ? 100 : pct;
  o.tempC = atoi(f[11]);
  o.pvA   = atof(f[12]); o.pvV    = atof(f[13]);
  o.battVscc = atof(f[14]);
  o.dischgA = atof(f[15]);
  pi30Copy(o.st, f[16], sizeof(o.st));
  o.pvChgW = (n > 19 && isNum(f[19])) ? atoi(f[19]) : 0;
  pi30Copy(o.st2, (n > 20) ? f[20] : "", sizeof(o.st2));
  // PV power: field 19 when the model reports it, else V x A
  o.pvW = o.pvChgW > 0 ? o.pvChgW : (int)(o.pvA * o.pvV + 0.5f);
  o.battW = (int)lroundf(o.battV * (o.chgA - o.dischgA));
  o.gridOn = o.gridV > 90.0f;
  L = o;
  if (errCap) err[0] = 0;
  return true;
}

inline void estimateGrid(Live& L) {
  // QPIGS has no grid-input power, so balance it: grid + solar = load + battery charging
  bool acCharging = strlen(L.st) == 8 && L.st[7] == '1';
  if (L.gridOn && (L.mode == 'L' || acCharging)) {
    int need = L.outW + (L.battW > 0 ? L.battW : 0) - L.pvW;
    L.gridW = need > 0 ? need : 0;
  } else {
    L.gridW = 0;
  }
}

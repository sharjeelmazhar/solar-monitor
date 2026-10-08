#pragma once
// ============================================================================
//  Voltronic PI18 protocol (InfiniSolar-family inverters). Pure functions only, unit-tested on a PC.
//  Request:  ^P<3-digit length><command><CRC hi><CRC lo><CR>   (length = command + CRC + CR)
//  Reply:    ^D<3-digit length><comma-separated data><CRC hi><CRC lo><CR>, CRC over everything before it.
// ============================================================================
#include "pi30.h"

// "GS" -> "^P005GS" (the CRC and CR are added by buildRequest)
inline bool pi18Command(const char* cmd, char* out, size_t cap) {
  int n = snprintf(out, cap, "^P%03d%s", (int)strlen(cmd) + 3, cmd);
  return n > 0 && (size_t)n < cap;
}

// buf = everything from '^' up to (not including) CR, n its length. Leaves the data part (after "^Dnnn") at the
// start of buf, NUL-terminated, and returns its length; or a ReplyStatus.
inline int pi18CheckReply(char* buf, size_t n) {
  if (n < 4) return REPLY_TOO_SHORT;
  uint16_t got = ((uint8_t)buf[n - 2] << 8) | (uint8_t)buf[n - 1];
  n -= 2;
  uint16_t want = crcRaw(0, (const uint8_t*)buf, n);
  if (got != crcFix(want) && got != want) return REPLY_CRC;
  buf[n] = 0;
  if (n >= 2 && buf[1] == '0') return REPLY_NAK;          // "^0" = refused
  if (n < 5 || buf[1] != 'D') return n >= 2 && buf[1] == '1' ? 0 : REPLY_TOO_SHORT;   // "^1" = accepted
  memmove(buf, buf + 5, n - 5 + 1);
  return (int)(n - 5);
}

inline int splitComma(char* s, char** f, int maxF) {
  int n = 0;
  char* p = s;
  while (n < maxF) {
    f[n++] = p;
    char* c = strchr(p, ',');
    if (!c) break;
    *c = 0;
    p = c + 1;
  }
  return n;
}

// MOD reply ("00".."05") -> the PI30 mode letter the apps understand
inline char pi18Mode(const char* d) {
  switch (atoi(d)) {
    case 0: return 'P'; case 1: return 'S'; case 2: return 'L'; case 3: return 'B'; case 4: return 'F'; case 5: return 'L';
  }
  return '?';
}

// GS: general status. Fills the same Live record as PI30 so the rest of the firmware doesn't care which protocol it is.
inline bool parsePI18GS(const char* payload, Live& L, char* err, size_t errCap) {
  char tmp[200];
  pi30Copy(tmp, payload, sizeof(tmp));
  char* f[32];
  int n = splitComma(tmp, f, 32);
  if (n < 25) { snprintf(err, errCap, "GS: short reply (%d fields)", n); return false; }
  for (int i = 0; i < 20; i++)
    if (!isNum(f[i])) { snprintf(err, errCap, "GS: bad field %d", i); return false; }
  Live o = L;
  o.gridV = atoi(f[0]) / 10.0f;  o.gridHz = atoi(f[1]) / 10.0f;
  o.outV = atoi(f[2]) / 10.0f;   o.outHz = atoi(f[3]) / 10.0f;
  o.outVA = atoi(f[4]);          o.outW = atoi(f[5]);
  o.loadPct = atoi(f[6]);
  o.battV = atoi(f[7]) / 10.0f;  o.battVscc = atoi(f[8]) / 10.0f;
  o.dischgA = (float)atoi(f[10]); o.chgA = (float)atoi(f[11]);
  int pct = atoi(f[12]);
  o.battPct = pct < 0 ? 0 : pct > 100 ? 100 : pct;
  o.tempC = atoi(f[13]);
  o.pvW = atoi(f[16]) + atoi(f[17]);
  o.pvChgW = o.pvW;
  o.pvV = atoi(f[18]) / 10.0f;
  o.pvA = o.pvV > 1 ? o.pvW / o.pvV : 0;
  o.busV = 0;
  o.battW = (int)lroundf(o.battV * (o.chgA - o.dischgA));
  o.gridOn = o.gridV > 90.0f;
  // same status bits as QPIGS so history flags work: b4 load on, b1 solar charging, b0 grid charging
  bool loadOn = n > 23 && atoi(f[23]) == 1;
  bool sccChg = n > 21 && atoi(f[21]) == 2;
  bool acChg = n > 26 && atoi(f[24]) == 1 && atoi(f[26]) == 1 && !sccChg;
  snprintf(o.st, sizeof(o.st), "000%c00%c%c", loadOn ? '1' : '0', sccChg ? '1' : '0', acChg ? '1' : '0');
  o.st2[0] = 0;
  L = o;
  if (errCap) err[0] = 0;
  return true;
}

// ---------- Modbus RTU (read-only, used only by the probe to recognise other inverter brands) ----------
inline uint16_t modbusCrc(const uint8_t* p, size_t n) {
  uint16_t crc = 0xFFFF;
  while (n--) {
    crc ^= *p++;
    for (int i = 0; i < 8; i++) crc = (crc & 1) ? (crc >> 1) ^ 0xA001 : crc >> 1;
  }
  return crc;
}

// Function 03 (read holding registers) or 04 (read input registers) only: these cannot change anything.
inline size_t modbusRead(uint8_t addr, uint8_t fn, uint16_t reg, uint16_t count, uint8_t* out) {
  if (fn != 3 && fn != 4) return 0;
  out[0] = addr; out[1] = fn; out[2] = reg >> 8; out[3] = reg & 0xFF; out[4] = count >> 8; out[5] = count & 0xFF;
  uint16_t c = modbusCrc(out, 6);
  out[6] = c & 0xFF; out[7] = c >> 8;
  return 8;
}

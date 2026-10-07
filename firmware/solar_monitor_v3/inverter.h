#pragma once
// ============================================================================
//  Serial transport for the Voltronic PI30 protocol (Inverex Veyron and other
//  Axpert-family inverters). Protocol logic lives in pi30.h.
// ============================================================================
#include <Arduino.h>
#include "pi30.h"

static char invErr[64] = "starting";
static uint32_t invCrcErrors = 0;

// Sends cmd, puts the payload (without '(' and CRC) in out. Returns payload length or -1.
static int invQuery(const char* cmd, char* out, size_t cap, uint32_t timeoutMs = REPLY_TIMEOUT_MS) {
  while (Serial1.available()) Serial1.read();   // drop stale bytes
  uint8_t frame[24];
  size_t fl = buildRequest(cmd, frame, sizeof(frame));
  if (!fl) { snprintf(invErr, sizeof(invErr), "%s: command too long", cmd); return -1; }
  Serial1.write(frame, fl);

  size_t n = 0;
  bool started = false, done = false;
  uint32_t t0 = millis();
  while (!done && millis() - t0 < timeoutMs) {
    int avail = Serial1.available();
    if (avail <= 0) { delay(1); continue; }
    while (avail-- > 0) {
      int b = Serial1.read();
      if (b < 0) break;
      if (!started) { if (b == '(') started = true; continue; }
      if (b == '\r') { done = true; break; }
      if (n >= cap - 1) { snprintf(invErr, sizeof(invErr), "%s: reply too long", cmd); return -1; }
      out[n++] = (char)b;
    }
  }
  if (!started) { snprintf(invErr, sizeof(invErr), "%s: no response from inverter", cmd); return -1; }
  if (!done)    { snprintf(invErr, sizeof(invErr), "%s: reply cut off", cmd); return -1; }

  int r = checkReply(out, n);
  if (r == REPLY_TOO_SHORT) { snprintf(invErr, sizeof(invErr), "%s: reply too short", cmd); return -1; }
  if (r == REPLY_CRC) { invCrcErrors++; snprintf(invErr, sizeof(invErr), "%s: CRC mismatch (noise on cable?)", cmd); return -1; }
  if (r == REPLY_NAK) { snprintf(invErr, sizeof(invErr), "%s: not supported (NAK)", cmd); return -1; }
  return r;
}

static bool parseQPIGS(const char* payload, Live& L) { return parseQPIGS(payload, L, invErr, sizeof(invErr)); }

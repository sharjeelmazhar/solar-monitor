#pragma once
// ============================================================================
//  Inverter drivers. One serial link, several protocols:
//    PI30  Voltronic / Axpert family (Inverex Veyron and many brands sold in Pakistan)
//    PI18  Voltronic InfiniSolar family
//  The protocol is found by itself at start-up (and again if the inverter stops answering), so the same monitor
//  works when it is moved to another inverter. Protocol logic lives in pi30.h / pi18.h (unit-tested on a PC).
// ============================================================================
#include <Arduino.h>
#include "pi30.h"
#include "pi18.h"

enum Proto : uint8_t { PROTO_NONE = 0, PROTO_PI30, PROTO_PI18 };
static Proto invProto = PROTO_NONE;
static const char* protoName(Proto p) { return p == PROTO_PI30 ? "PI30" : p == PROTO_PI18 ? "PI18" : "none"; }

static char invErr[64] = "starting";
static uint32_t invCrcErrors = 0;

// Sends a raw frame and collects bytes until CR (or the timeout). Returns the count, -1 if nothing came back.
static int invExchange(const uint8_t* frame, size_t fl, char* out, size_t cap, uint32_t timeoutMs, char startCh) {
  while (Serial1.available()) Serial1.read();   // drop stale bytes
  Serial1.write(frame, fl);
  size_t n = 0;
  bool started = startCh == 0, done = false;
  uint32_t t0 = millis();
  while (!done && millis() - t0 < timeoutMs) {
    int avail = Serial1.available();
    if (avail <= 0) { delay(1); continue; }
    while (avail-- > 0) {
      int b = Serial1.read();
      if (b < 0) break;
      if (!started) { if (b == startCh) { started = true; if (startCh == '^') out[n++] = (char)b; } continue; }
      if (b == '\r' && startCh) { done = true; break; }
      if (n >= cap - 1) { done = true; break; }
      out[n++] = (char)b;
    }
  }
  if (!started) return -1;
  return done || startCh == 0 ? (int)n : -2;
}

// PI30 or PI18 query (picked by the command: PI18 commands start with '^'). Puts the payload in out (without the
// framing and CRC). Returns its length or -1, with the reason in invErr.
static int invQuery(const char* cmd, char* out, size_t cap, uint32_t timeoutMs = REPLY_TIMEOUT_MS) {
  bool pi18 = cmd[0] == '^';
  uint8_t frame[24];
  size_t fl = buildRequest(cmd, frame, sizeof(frame));
  if (!fl) { snprintf(invErr, sizeof(invErr), "%s: command too long", cmd); return -1; }
  int n = invExchange(frame, fl, out, cap, timeoutMs, pi18 ? '^' : '(');
  if (n == -1) { snprintf(invErr, sizeof(invErr), "%s: no response from inverter", cmd); return -1; }
  if (n < 0)   { snprintf(invErr, sizeof(invErr), "%s: reply cut off", cmd); return -1; }
  int r = pi18 ? pi18CheckReply(out, n) : checkReply(out, n);
  if (r == REPLY_TOO_SHORT) { snprintf(invErr, sizeof(invErr), "%s: reply too short", cmd); return -1; }
  if (r == REPLY_CRC) { invCrcErrors++; snprintf(invErr, sizeof(invErr), "%s: CRC mismatch (noise on cable?)", cmd); return -1; }
  if (r == REPLY_NAK) { snprintf(invErr, sizeof(invErr), "%s: not supported (NAK)", cmd); return -1; }
  return r;
}

static bool parseQPIGS(const char* payload, Live& L) { return parseQPIGS(payload, L, invErr, sizeof(invErr)); }

// One live reading with whichever protocol the inverter speaks. warnEvery: also read warnings this cycle.
// Returns false (reason in invErr) if the inverter did not answer properly; hash gets a fingerprint of the reply.
static bool invReadLive(Live& L, bool withWarnings, uint32_t& hash, uint32_t (*fnvFn)(const char*, uint32_t)) {
  static char buf[220];
  if (invProto == PROTO_PI18) {
    char cmd[16];
    pi18Command("GS", cmd, sizeof cmd);
    if (invQuery(cmd, buf, sizeof buf) <= 0 || !parsePI18GS(buf, L, invErr, sizeof invErr)) return false;
    hash = fnvFn(buf, 2166136261u);
    char m[16];
    pi18Command("MOD", cmd, sizeof cmd);
    if (invQuery(cmd, m, sizeof m, 800) > 0) L.mode = pi18Mode(m);
    if (withWarnings) {
      char w[64];
      pi18Command("FWS", cmd, sizeof cmd);
      // PI18 fault/warning list is comma-separated 0/1 flags; keep it in the same field (the apps show raw bits)
      if (invQuery(cmd, w, sizeof w, 800) > 0) { int j = 0; for (int i = 0; w[i] && j < 39; i++) if (w[i] == '0' || w[i] == '1') L.warn[j++] = w[i]; L.warn[j] = 0; }
    }
    return true;
  }
  if (invQuery("QPIGS", buf, sizeof buf) <= 0 || !parseQPIGS(buf, L)) return false;
  hash = fnvFn(buf, 2166136261u);
  char m[8];
  if (invQuery("QMOD", m, sizeof m, 800) > 0) L.mode = m[0];
  if (withWarnings) {
    char w[48];
    if (invQuery("QPIWS", w, sizeof w, 800) > 0) pi30Copy(L.warn, w, sizeof L.warn);
  }
  return true;
}

// Which protocol does the inverter on the cable speak? Tries each in turn; PROTO_NONE if nothing answered.
static Proto invDetect() {
  char buf[220];
  if (invQuery("QPIGS", buf, sizeof buf) > 0) return PROTO_PI30;
  char cmd[16];
  pi18Command("GS", cmd, sizeof cmd);
  if (invQuery(cmd, buf, sizeof buf) > 0) return PROTO_PI18;
  return PROTO_NONE;
}

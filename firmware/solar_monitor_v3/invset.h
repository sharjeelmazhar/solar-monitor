#pragma once
// ============================================================================
//  Changing inverter settings (PI30 / Voltronic). Pure functions only, unit-tested on a PC (firmware/test/).
//  Only the settings listed here can ever be sent, each checked against the allowed range for the battery
//  voltage (12 / 24 / 48 V) and against the other battery voltages, so a bad value never reaches the inverter.
// ============================================================================
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>

// QPIRI field index of each setting (Voltronic PI30 order)
enum { RF_OUTV = 2, RF_OUTHZ = 3, RF_RECHARGE = 8, RF_CUTOFF = 9, RF_BULK = 10, RF_FLOAT = 11, RF_BATTTYPE = 12, RF_MAXAC = 13, RF_MAXCHG = 14, RF_RANGE = 15,
       RF_OUTPRIO = 16, RF_CHGPRIO = 17, RF_REDISCHARGE = 22 };
// Battery equalization settings live in QBEQI, not QPIRI: field = EQ_BASE + QBEQI index
// QBEQI = enable, time (min), period (days), max current, reserved, voltage, reserved, time-out (min), active, elapsed
enum { EQ_BASE = 100, EQ_EN = 100, EQ_TIME = 101, EQ_PERIOD = 102, EQ_VOLT = 105, EQ_TIMEOUT = 107, EQ_ACTIVE = 108 };
enum { KEY_FLAG = -1, KEY_UNKNOWN = -2, KEY_RESTORE = -3 };
static const int RESTORE_CODE = 7373;   // "restore" must carry this value, so a stray request can never reset the inverter

struct SetKey { const char* key; int field; };
static const SetKey SET_KEYS[] = {
  {"outPrio", RF_OUTPRIO}, {"chgPrio", RF_CHGPRIO}, {"range", RF_RANGE}, {"bulk", RF_BULK}, {"float", RF_FLOAT},
  {"cutoff", RF_CUTOFF}, {"recharge", RF_RECHARGE}, {"redischarge", RF_REDISCHARGE}, {"maxChg", RF_MAXCHG},
  {"maxAc", RF_MAXAC}, {"flag", KEY_FLAG}, {"battType", RF_BATTTYPE}, {"outV", RF_OUTV}, {"outHz", RF_OUTHZ},
  {"eqEn", EQ_EN}, {"eqNow", EQ_ACTIVE}, {"eqTime", EQ_TIME}, {"eqTimeout", EQ_TIMEOUT}, {"eqPeriod", EQ_PERIOD},
  {"eqVolt", EQ_VOLT}, {"restore", KEY_RESTORE},
};
static const char SET_FLAGS[] = "abdjkuvxyz";   // QFLAG letters that may be switched (buzzer, backlight, solar feed, ...)

inline int setKeyField(const char* key) {
  for (const SetKey& k : SET_KEYS) if (!strcmp(k.key, key)) return k.field;
  return KEY_UNKNOWN;
}

// Field i (0-based, space separated) of a QPIRI payload as a number; NAN if missing.
inline float qpiriField(const char* q, int i) {
  const char* p = q;
  for (int n = 0; ; n++) {
    while (*p == ' ') p++;
    if (!*p) return NAN;
    if (n == i) return (float)atof(p);
    while (*p && *p != ' ') p++;
  }
}

// "010 020 030" -> true if v is one of them (allowed charge currents from QMCHGCR / QMUCHGCR)
inline bool inList(const char* list, int v) {
  const char* p = list;
  while (*p) {
    while (*p == ' ') p++;
    if (!*p) break;
    if (atoi(p) == v) return true;
    while (*p && *p != ' ') p++;
  }
  return false;
}

inline bool onStep(float v, float lo, float step) {
  float k = (v - lo) / step;
  return fabsf(k - roundf(k)) < 0.01f;
}

// Builds the command for key = value. qpiri = current settings, chgList / acList = allowed currents.
// flag: value 1 = enable, 0 = disable, letter = which flag. Returns true and the command in cmd, or false and the
// reason in err.
inline bool buildSetCommand(const char* key, float value, char letter, const char* qpiri, const char* chgList,
                            const char* acList, char* cmd, size_t cap, char* err, size_t errCap) {
  int field = setKeyField(key);
  if (field == KEY_UNKNOWN) { snprintf(err, errCap, "unknown setting"); return false; }
  if (field == KEY_RESTORE) {
    if (lroundf(value) != RESTORE_CODE) { snprintf(err, errCap, "restore not confirmed"); return false; }
    snprintf(cmd, cap, "PF"); return true;
  }
  if (field == KEY_FLAG) {
    if (!letter || !strchr(SET_FLAGS, letter) || (value != 0 && value != 1)) { snprintf(err, errCap, "unknown option"); return false; }
    snprintf(cmd, cap, "P%c%c", value == 1 ? 'E' : 'D', letter);
    return true;
  }
  float battV = qpiriField(qpiri, 7);
  if (!(battV == 12 || battV == 24 || battV == 48)) { snprintf(err, errCap, "battery voltage not read yet"); return false; }
  float k = battV / 12;   // ranges below are for a 12 V battery; 24 V doubles them, 48 V x4
  int iv = (int)lroundf(value);
  bool isInt = fabsf(value - iv) < 0.001f;
  float cutoff = qpiriField(qpiri, RF_CUTOFF), recharge = qpiriField(qpiri, RF_RECHARGE);
  float bulk = qpiriField(qpiri, RF_BULK), flt = qpiriField(qpiri, RF_FLOAT), redis = qpiriField(qpiri, RF_REDISCHARGE);
  auto range = [&](float lo, float hi, float step) -> bool {
    if (value < lo - 0.001f || value > hi + 0.001f || !onStep(value, lo, step)) {
      snprintf(err, errCap, "allowed %.1f to %.1f V in %.1f V steps", lo, hi, step);
      return false;
    }
    return true;
  };
  auto steps = [&](int lo, int hi, int step) -> bool {
    if (!isInt || iv < lo || iv > hi || (iv - lo) % step) { snprintf(err, errCap, "allowed %d to %d in steps of %d", lo, hi, step); return false; }
    return true;
  };
  switch (field) {
    case RF_BATTTYPE:   // AGM, flooded, user, Pylontech (the lithium types need a model-specific code: not offered)
      if (!isInt || iv < 0 || iv > 3) { snprintf(err, errCap, "allowed 0 to 3"); return false; }
      snprintf(cmd, cap, "PBT%02d", iv); return true;
    case RF_OUTV:
      if (!isInt || (iv != 220 && iv != 230 && iv != 240)) { snprintf(err, errCap, "allowed 220, 230 or 240 V"); return false; }
      snprintf(cmd, cap, "V%d", iv); return true;
    case RF_OUTHZ:
      if (!isInt || (iv != 50 && iv != 60)) { snprintf(err, errCap, "allowed 50 or 60 Hz"); return false; }
      snprintf(cmd, cap, "F%d", iv); return true;
    case EQ_EN:
      if (!isInt || iv < 0 || iv > 1) { snprintf(err, errCap, "allowed 0 or 1"); return false; }
      snprintf(cmd, cap, "PBEQE%d", iv); return true;
    case EQ_ACTIVE:
      if (!isInt || iv < 0 || iv > 1) { snprintf(err, errCap, "allowed 0 or 1"); return false; }
      snprintf(cmd, cap, "PBEQA%d", iv); return true;
    case EQ_TIME:
      if (!steps(5, 900, 5)) return false;
      snprintf(cmd, cap, "PBEQT%03d", iv); return true;
    case EQ_TIMEOUT:
      if (!steps(5, 900, 5)) return false;
      snprintf(cmd, cap, "PBEQOT%03d", iv); return true;
    case EQ_PERIOD:
      if (!steps(0, 90, 1)) return false;
      snprintf(cmd, cap, "PBEQP%03d", iv); return true;
    case EQ_VOLT:
      if (!range(12.0f * k, 15.25f * k, 0.05f * k)) return false;
      snprintf(cmd, cap, "PBEQV%05.2f", value); return true;
    case RF_OUTPRIO:
      if (!isInt || iv < 0 || iv > 2) { snprintf(err, errCap, "allowed 0 to 2"); return false; }
      snprintf(cmd, cap, "POP%02d", iv); return true;
    case RF_CHGPRIO:
      if (!isInt || iv < 0 || iv > 3) { snprintf(err, errCap, "allowed 0 to 3"); return false; }
      snprintf(cmd, cap, "PCP%02d", iv); return true;
    case RF_RANGE:
      if (!isInt || iv < 0 || iv > 1) { snprintf(err, errCap, "allowed 0 or 1"); return false; }
      snprintf(cmd, cap, "PGR%02d", iv); return true;
    case RF_BULK:
      if (!range(12.0f * k, 14.6f * k, 0.1f)) return false;
      if (value < flt - 0.001f) { snprintf(err, errCap, "must not be below float (%.1f V)", flt); return false; }
      snprintf(cmd, cap, "PCVV%04.1f", value); return true;
    case RF_FLOAT:
      if (!range(12.0f * k, 14.6f * k, 0.1f)) return false;
      if (value > bulk + 0.001f) { snprintf(err, errCap, "must not be above bulk (%.1f V)", bulk); return false; }
      snprintf(cmd, cap, "PBFT%04.1f", value); return true;
    case RF_CUTOFF:
      if (!range(10.5f * k, 12.0f * k, 0.1f)) return false;
      if (value >= recharge - 0.001f) { snprintf(err, errCap, "must be below back-to-grid (%.1f V)", recharge); return false; }
      snprintf(cmd, cap, "PSDV%04.1f", value); return true;
    case RF_RECHARGE:
      if (!range(11.0f * k, 12.75f * k, 0.25f * k)) return false;
      if (value <= cutoff + 0.001f) { snprintf(err, errCap, "must be above cut-off (%.1f V)", cutoff); return false; }
      if (redis > 0 && value >= redis - 0.001f) { snprintf(err, errCap, "must be below back-to-battery (%.1f V)", redis); return false; }
      snprintf(cmd, cap, "PBCV%04.1f", value); return true;
    case RF_REDISCHARGE:
      if (value != 0) {
        if (!range(12.0f * k, 14.5f * k, 0.25f * k)) return false;
        if (value <= recharge + 0.001f) { snprintf(err, errCap, "must be above back-to-grid (%.1f V)", recharge); return false; }
      }
      snprintf(cmd, cap, "PBDV%04.1f", value); return true;
    case RF_MAXCHG:
      if (!isInt || !chgList[0] || !inList(chgList, iv)) { snprintf(err, errCap, "allowed: %s A", chgList[0] ? chgList : "list not read yet"); return false; }
      if (iv >= 100) snprintf(cmd, cap, "MNCHGC0%03d", iv); else snprintf(cmd, cap, "MCHGC0%02d", iv);
      return true;
    case RF_MAXAC:
      if (!isInt || !acList[0] || !inList(acList, iv)) { snprintf(err, errCap, "allowed: %s A", acList[0] ? acList : "list not read yet"); return false; }
      if (iv >= 100) { snprintf(err, errCap, "too high"); return false; }
      snprintf(cmd, cap, "MUCHGC0%02d", iv); return true;
  }
  snprintf(err, errCap, "unknown setting");
  return false;
}

// After the inverter says ACK: does the fresh QPIRI / QFLAG show the new value?
// Restore to defaults cannot be checked field by field: the ACK is the answer.
inline bool setVerified(const char* key, float value, char letter, const char* qpiri, const char* qflag, const char* qbeqi = "") {
  int field = setKeyField(key);
  if (field == KEY_RESTORE) return true;
  if (field >= EQ_BASE) {
    float now = qpiriField(qbeqi, field - EQ_BASE);
    return !std::isnan(now) && fabsf(now - value) < 0.005f;
  }
  if (field == KEY_FLAG) {
    const char* d = strchr(qflag, 'D');   // QFLAG = "E<enabled letters>D<disabled letters>"
    const char* l = strchr(qflag, letter);
    if (!d || !l) return false;
    return value == 1 ? l < d : l > d;
  }
  if (field < 0) return false;
  float now = qpiriField(qpiri, field);
  return !std::isnan(now) && fabsf(now - value) < 0.05f;
}

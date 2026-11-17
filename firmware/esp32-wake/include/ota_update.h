// Signed OTA firmware updates (protocol v0.5). CONCEPT / UNTESTED ON HARDWARE.
// See docs/PROTOCOL.md "node.ota" and docs/MANUAL.md "Signed OTA updates".
#pragma once
#include <Arduino.h>

#ifndef FW_VERSION
#define FW_VERSION "0.5.0"
#endif
// major*10000 + minor*100 + patch; must match FW_VERSION (tools/ota_sign.py checks the same).
#ifndef FW_VERSION_CODE
#define FW_VERSION_CODE 500
#endif
#define OTA_PROJECT "pyra-claw-esp32-wake"
#define OTA_FORMAT "pyra-ota/1"

struct OtaRequest {
  const char *url;
  const char *project;
  const char *version;
  long version_code;
  long size;
  const char *sha256;   // 64 lowercase hex
  const char *img_sig;  // hex DER ECDSA P-256 over the pyra-ota/1 message
};

// True when an OTA public key was compiled in (PYRA_OTA_PUBKEY at build time).
bool otaHasPubkey();
// Shape + anti-rollback + signature checks, no download. nullptr = OK, else a reason.
const char *otaCheck(const OtaRequest &r);
// Downloads, hashes and writes the image to the next OTA slot, then sets it as boot
// partition. nullptr = ready to reboot, else a reason (nothing switched on failure).
// progress(pct) is called every ~10 %.
const char *otaApply(const OtaRequest &r, void (*progress)(int pct));

// Rollback support. Call otaBootCheck() early in setup().
void otaBootCheck();
bool otaPendingVerify();      // running a fresh OTA image that isn't confirmed yet
void otaMarkValid();          // health check passed: cancel rollback, raise the floor
void otaRollbackAndReboot();  // health check failed: boot the previous image
long otaFloor();              // lowest version_code an OTA may install (exclusive)
const char *otaState();       // "none" | "pending_verify" | "valid" | "rolled_back"

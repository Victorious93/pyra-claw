// Signed OTA firmware updates (protocol v0.5). CONCEPT / UNTESTED ON HARDWARE.
//
// 1. Hub sends {"action":"ota",...} over MQTT, HMAC-signed with hub_token (checked in
//    main.cpp, like power_pulse). 2. otaCheck(): project, version_code anti-rollback
//    (> running FW_VERSION_CODE and > NVS floor) and the ECDSA P-256 signature over
//    "pyra-ota/1|project|version|code|size|sha256" with the compiled-in public key.
// 3. otaApply(): stream the image into the inactive slot via esp_ota_*, SHA-256 it on
//    the fly, refuse on size/hash mismatch, then esp_ota_set_boot_partition + reboot.
// 4. New image boots in PENDING_VERIFY (CONFIG_BOOTLOADER_APP_ROLLBACK_ENABLE=y in the
//    Arduino core). main.cpp calls otaMarkValid() once Wi-Fi + MQTT/HTTP are up, or
//    otaRollbackAndReboot() after the deadline; a crash/reset before that also rolls back.
#include "ota_update.h"

#include <HTTPClient.h>
#include <Preferences.h>
#include <WiFi.h>
#include <esp_ota_ops.h>
#include <mbedtls/pk.h>
#include <mbedtls/sha256.h>

static const char *NVS_OTA = "ota";
static bool pendingVerify = false;
static const char *state = "none";

// Arduino core: returning true stops it from auto-confirming a PENDING_VERIFY image,
// so our own health check decides (see initArduino() in esp32-hal-misc.c).
extern "C" bool verifyRollbackLater() { return true; }

bool otaHasPubkey() {
#ifdef OTA_PUBKEY_DER_HEX
  return sizeof(OTA_PUBKEY_DER_HEX) > 1;
#else
  return false;
#endif
}

static int hexval(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

// Returns bytes written, or -1 on bad hex / too long.
static int unhex(const char *s, uint8_t *out, size_t cap) {
  size_t n = strlen(s);
  if (n % 2 || n / 2 > cap) return -1;
  for (size_t i = 0; i < n / 2; i++) {
    int a = hexval(s[2 * i]), b = hexval(s[2 * i + 1]);
    if (a < 0 || b < 0) return -1;
    out[i] = (uint8_t)(a << 4 | b);
  }
  return (int)(n / 2);
}

static bool isHex64(const char *s) {
  if (!s || strlen(s) != 64) return false;
  for (int i = 0; i < 64; i++)
    if (!((s[i] >= '0' && s[i] <= '9') || (s[i] >= 'a' && s[i] <= 'f'))) return false;
  return true;
}

long otaFloor() {
  Preferences p;
  long f = 0;
  if (p.begin(NVS_OTA, true)) {
    f = p.getLong("floor", 0);
    p.end();
  }
  return f > FW_VERSION_CODE ? f : (long)FW_VERSION_CODE;
}

static bool verifySig(const OtaRequest &r) {
#ifdef OTA_PUBKEY_DER_HEX
  uint8_t der[96], sig[80], hash[32];
  int derLen = unhex(OTA_PUBKEY_DER_HEX, der, sizeof(der));
  int sigLen = unhex(r.img_sig, sig, sizeof(sig));
  if (derLen <= 0 || sigLen <= 0) return false;
  String msg = String(OTA_FORMAT) + "|" + r.project + "|" + r.version + "|" +
               String(r.version_code) + "|" + String(r.size) + "|" + r.sha256;
  mbedtls_sha256((const uint8_t *)msg.c_str(), msg.length(), hash, 0);
  mbedtls_pk_context pk;
  mbedtls_pk_init(&pk);
  bool ok = mbedtls_pk_parse_public_key(&pk, der, derLen) == 0 &&
            mbedtls_pk_can_do(&pk, MBEDTLS_PK_ECKEY) &&
            mbedtls_pk_verify(&pk, MBEDTLS_MD_SHA256, hash, sizeof(hash), sig, sigLen) == 0;
  mbedtls_pk_free(&pk);
  return ok;
#else
  (void)r;
  return false;
#endif
}

const char *otaCheck(const OtaRequest &r) {
  if (!otaHasPubkey()) return "rejected_no_pubkey";
  if (!r.url || strncmp(r.url, "http://", 7) != 0 || strlen(r.url) > 300) return "rejected_bad_url";
  if (!r.project || strcmp(r.project, OTA_PROJECT) != 0) return "rejected_wrong_project";
  if (!r.version || strlen(r.version) > 8 || strchr(r.version, '|')) return "rejected_bad_version";
  if (r.version_code <= otaFloor()) return "rejected_rollback";
  if (!isHex64(r.sha256)) return "rejected_bad_sha256";
  if (!r.img_sig || strlen(r.img_sig) < 16 || strlen(r.img_sig) > 160) return "rejected_bad_sig";
  const esp_partition_t *next = esp_ota_get_next_update_partition(nullptr);
  if (!next) return "rejected_no_ota_slot";
  if (r.size <= 0 || (uint32_t)r.size > next->size) return "rejected_too_big";
  if (!verifySig(r)) return "rejected_signature";
  return nullptr;
}

const char *otaApply(const OtaRequest &r, void (*progress)(int pct)) {
  const esp_partition_t *next = esp_ota_get_next_update_partition(nullptr);
  if (!next) return "failed_no_ota_slot";
  HTTPClient http;
  http.setTimeout(15000);
  http.setReuse(false);
  if (!http.begin(r.url)) return "failed_http_begin";
  int code = http.GET();
  if (code != 200) {
    http.end();
    return code == 403 ? "failed_download_403" : "failed_download";
  }
  int len = http.getSize();
  if (len >= 0 && len != r.size) {
    http.end();
    return "failed_size_header";
  }
  esp_ota_handle_t h = 0;
  if (esp_ota_begin(next, (size_t)r.size, &h) != ESP_OK) {
    http.end();
    return "failed_ota_begin";
  }
  mbedtls_sha256_context sha;
  mbedtls_sha256_init(&sha);
  mbedtls_sha256_starts(&sha, 0);
  WiFiClient *s = http.getStreamPtr();
  static uint8_t buf[1024];
  long got = 0;
  int lastPct = -10;
  uint32_t lastData = millis();
  const char *why = nullptr;
  while (got < r.size) {
    size_t avail = s->available();
    if (!avail) {
      if (!http.connected() || millis() - lastData > 15000) {
        why = "failed_download_stalled";
        break;
      }
      delay(2);
      continue;
    }
    size_t want = min((size_t)(r.size - got), min(avail, sizeof(buf)));
    int n = s->readBytes(buf, want);
    if (n <= 0) continue;
    lastData = millis();
    mbedtls_sha256_update(&sha, buf, n);
    if (esp_ota_write(h, buf, n) != ESP_OK) {
      why = "failed_ota_write";
      break;
    }
    got += n;
    int pct = (int)(got * 100 / r.size);
    if (progress && pct - lastPct >= 10) {
      lastPct = pct;
      progress(pct);
    }
  }
  http.end();
  uint8_t digest[32];
  mbedtls_sha256_finish(&sha, digest);
  mbedtls_sha256_free(&sha);
  if (!why) {
    char hex[65];
    for (int i = 0; i < 32; i++) snprintf(hex + 2 * i, 3, "%02x", digest[i]);
    if (strcmp(hex, r.sha256) != 0) why = "rejected_sha256_mismatch";
  }
  if (why) {
    esp_ota_abort(h);
    return why;
  }
  if (esp_ota_end(h) != ESP_OK) return "rejected_image_invalid";  // IDF validates the image
  if (esp_ota_set_boot_partition(next) != ESP_OK) return "failed_set_boot";
  Preferences p;
  if (p.begin(NVS_OTA, false)) {
    p.putLong("pending", r.version_code);
    p.end();
  }
  return nullptr;
}

void otaBootCheck() {
  esp_ota_img_states_t st;
  const esp_partition_t *running = esp_ota_get_running_partition();
  if (running && esp_ota_get_state_partition(running, &st) == ESP_OK &&
      st == ESP_OTA_IMG_PENDING_VERIFY) {
    pendingVerify = true;
    state = "pending_verify";
    return;
  }
  // A pending version that isn't the one running means the bootloader rolled back.
  Preferences p;
  if (p.begin(NVS_OTA, false)) {
    long pend = p.getLong("pending", 0);
    if (pend && pend != FW_VERSION_CODE) state = "rolled_back";
    else if (pend) state = "valid";
    p.remove("pending");
    p.end();
  }
}

bool otaPendingVerify() { return pendingVerify; }

void otaMarkValid() {
  if (!pendingVerify) return;
  esp_ota_mark_app_valid_cancel_rollback();
  pendingVerify = false;
  state = "valid";
  Preferences p;
  if (p.begin(NVS_OTA, false)) {
    if (p.getLong("floor", 0) < FW_VERSION_CODE) p.putLong("floor", FW_VERSION_CODE);
    p.remove("pending");
    p.end();
  }
}

void otaRollbackAndReboot() {
  state = "rolled_back";
  esp_ota_mark_app_invalid_rollback_and_reboot();  // does not return if a fallback exists
}

const char *otaState() { return state; }

// Pyra Claw ESP32 wake node.
// CONCEPT / UNTESTED: verify wiring before connecting to a real PC.
// USB serial provisioning contract v1: see docs/PROVISIONING.md.
#include <Arduino.h>
#include <ArduinoJson.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include <PubSubClient.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <WebServer.h>
#include <mbedtls/md.h>
#include <time.h>

#if __has_include("config.h")
#include "config.h"
#else
#include "config.example.h"
#endif

#ifndef FW_VERSION
#define FW_VERSION "0.3.0"
#endif
#ifndef HUB_TOKEN
#define HUB_TOKEN ""
#endif
#ifndef PC_BROADCAST
#define PC_BROADCAST "255.255.255.255"
#endif

// Hard safety cap, enforced regardless of what the hub requests.
static const uint32_t PULSE_MAX_MS = 300;
static const uint32_t PULSE_COOLDOWN_MS = 5000;
static const uint32_t PROV_WINDOW_MS = 10000;
static const char *PROTOCOL_VERSION = "0.2";
static const long SIG_MAX_SKEW_S = 30;          // |node clock - ts| must be <= 30 s
static const time_t TIME_VALID_AFTER = 1700000000;  // clock before this = no NTP sync yet
static const uint32_t HTTP_WAKE_MIN_INTERVAL_MS = 10000;
#ifndef HTTP_PORT
#define HTTP_PORT 80
#endif
static const char *NVS_NS = "fleet";

// Runtime config: config.h defaults, overridden by NVS namespace "fleet".
struct Cfg {
  String ssid, pass, broker, node_id, mqtt_user, mqtt_pass, pc_mac, pc_broadcast, hub_token;
  int broker_port;
} cfg;

static const char *STR_KEYS[] = {"ssid",      "pass",   "broker",       "node_id",  "mqtt_user",
                                 "mqtt_pass", "pc_mac", "pc_broadcast", "hub_token"};
static const char *ALL_KEYS[] = {"ssid",      "pass",      "broker", "broker_port",
                                 "node_id",   "mqtt_user", "mqtt_pass", "pc_mac",
                                 "pc_broadcast", "hub_token"};

String *strField(const char *k) {
  if (!strcmp(k, "ssid")) return &cfg.ssid;
  if (!strcmp(k, "pass")) return &cfg.pass;
  if (!strcmp(k, "broker")) return &cfg.broker;
  if (!strcmp(k, "node_id")) return &cfg.node_id;
  if (!strcmp(k, "mqtt_user")) return &cfg.mqtt_user;
  if (!strcmp(k, "mqtt_pass")) return &cfg.mqtt_pass;
  if (!strcmp(k, "pc_mac")) return &cfg.pc_mac;
  if (!strcmp(k, "pc_broadcast")) return &cfg.pc_broadcast;
  if (!strcmp(k, "hub_token")) return &cfg.hub_token;
  return nullptr;
}

bool isSecret(const char *k) {
  return !strcmp(k, "pass") || !strcmp(k, "mqtt_pass") || !strcmp(k, "hub_token");
}

bool parseMac(const char *s, uint8_t out[6]) {
  if (strlen(s) != 17) return false;
  for (int i = 0; i < 6; i++) {
    char h[3] = {s[i * 3], s[i * 3 + 1], 0};
    if (!isxdigit((unsigned char)h[0]) || !isxdigit((unsigned char)h[1])) return false;
    if (i < 5 && s[i * 3 + 2] != ':') return false;
    out[i] = (uint8_t)strtoul(h, nullptr, 16);
  }
  return true;
}

bool parseIPv4(const char *s, IPAddress &ip) {
  int a, b, c, d;
  char tail;
  if (sscanf(s, "%d.%d.%d.%d%c", &a, &b, &c, &d, &tail) != 4) return false;
  if (a < 0 || a > 255 || b < 0 || b > 255 || c < 0 || c > 255 || d < 0 || d > 255) return false;
  ip = IPAddress(a, b, c, d);
  return true;
}

void loadCfg() {
  const uint8_t m[6] = PC_MAC;
  char macStr[18];
  snprintf(macStr, sizeof(macStr), "%02X:%02X:%02X:%02X:%02X:%02X", m[0], m[1], m[2], m[3], m[4],
           m[5]);
  cfg.ssid = WIFI_SSID;
  cfg.pass = WIFI_PASS;
  cfg.broker = MQTT_HOST;
  cfg.broker_port = MQTT_PORT;
  cfg.node_id = NODE_ID;
  cfg.mqtt_user = MQTT_USER;
  cfg.mqtt_pass = MQTT_PASS;
  cfg.pc_mac = macStr;
  cfg.pc_broadcast = PC_BROADCAST;
  cfg.hub_token = HUB_TOKEN;

  Preferences p;
  if (!p.begin(NVS_NS, true)) return;  // namespace absent: keep defaults
  for (const char *k : STR_KEYS)
    if (p.isKey(k)) *strField(k) = p.getString(k);
  if (p.isKey("broker_port")) cfg.broker_port = p.getInt("broker_port", MQTT_PORT);
  p.end();
}

// ---------- serial provisioning ----------
uint32_t bootMs = 0;
String lineBuf;

bool provWindowOpen() { return millis() - bootMs < PROV_WINDOW_MS || cfg.ssid.length() == 0; }

void reply(JsonDocument &d) {
  serializeJson(d, Serial);
  Serial.print('\n');
}

void replyOk() {
  JsonDocument d;
  d["v"] = 1;
  d["ok"] = true;
  reply(d);
}

void replyErr(const char *err) {
  JsonDocument d;
  d["v"] = 1;
  d["ok"] = false;
  d["err"] = err;
  reply(d);
}

// Validates every field first; writes nothing unless all are valid.
void handleProvision(JsonVariantConst in) {
  if (!in.is<JsonObjectConst>()) return replyErr("cfg_not_object");
  JsonObjectConst o = in.as<JsonObjectConst>();
  static char err[64];
  for (JsonPairConst kv : o) {
    const char *k = kv.key().c_str();
    JsonVariantConst v = kv.value();
    if (!strcmp(k, "broker_port")) {
      if (!v.is<int>() || v.as<int>() < 1 || v.as<int>() > 65535) {
        snprintf(err, sizeof(err), "bad_value:%s", k);
        return replyErr(err);
      }
      continue;
    }
    if (!strField(k)) {
      snprintf(err, sizeof(err), "unknown_key:%s", k);
      return replyErr(err);
    }
    if (!v.is<const char *>()) {
      snprintf(err, sizeof(err), "bad_type:%s", k);
      return replyErr(err);
    }
    const char *s = v.as<const char *>();
    uint8_t mac[6];
    IPAddress ip;
    bool bad = strlen(s) > 128 || (!strcmp(k, "ssid") && strlen(s) > 32) ||
               (!strcmp(k, "pass") && strlen(s) > 63) ||
               (!strcmp(k, "pc_mac") && !parseMac(s, mac)) ||
               (!strcmp(k, "pc_broadcast") && !parseIPv4(s, ip)) ||
               (!strcmp(k, "node_id") && strlen(s) == 0);
    if (bad) {
      snprintf(err, sizeof(err), "bad_value:%s", k);
      return replyErr(err);
    }
  }
  Preferences p;
  if (!p.begin(NVS_NS, false)) return replyErr("nvs_open_failed");
  for (JsonPairConst kv : o) {
    const char *k = kv.key().c_str();
    if (!strcmp(k, "broker_port")) {
      cfg.broker_port = kv.value().as<int>();
      p.putInt(k, cfg.broker_port);
    } else {
      String s = kv.value().as<const char *>();
      if (!strcmp(k, "pc_mac")) s.toUpperCase();
      *strField(k) = s;
      p.putString(k, s);
    }
  }
  p.end();
  replyOk();  // Wi-Fi/MQTT changes take effect after reboot
}

void rebootSoon() {
  Serial.flush();
  delay(100);
  ESP.restart();
}

void handleLine(const String &line) {
  JsonDocument d;
  if (deserializeJson(d, line)) return;            // bad JSON / log line: ignore
  if (!d.is<JsonObject>() || d["v"] != 1) return;  // not a v1 command: ignore
  const char *cmd = d["cmd"] | "";
  if (!strcmp(cmd, "hello")) {
    JsonDocument r;
    r["v"] = 1;
    r["ok"] = true;
    r["project"] = "pyra-claw";
    r["fw"] = FW_VERSION;
    r["chip"] = "esp32";
    JsonArray keys = r["keys"].to<JsonArray>();
    for (const char *k : ALL_KEYS) keys.add(k);
    reply(r);
  } else if (!strcmp(cmd, "get")) {
    JsonDocument r;
    r["v"] = 1;
    r["ok"] = true;
    JsonObject c = r["cfg"].to<JsonObject>();
    for (const char *k : ALL_KEYS) {
      if (!strcmp(k, "broker_port")) c[k] = cfg.broker_port;
      else if (isSecret(k)) c[k] = "***";
      else c[k] = *strField(k);
    }
    reply(r);
  } else if (!strcmp(cmd, "provision")) {
    handleProvision(d["cfg"]);
  } else if (!strcmp(cmd, "reboot")) {
    replyOk();
    rebootSoon();
  } else if (!strcmp(cmd, "factory_reset")) {
    Preferences p;
    bool ok = p.begin(NVS_NS, false) && p.clear();
    p.end();
    if (!ok) return replyErr("nvs_clear_failed");
    replyOk();
    rebootSoon();
  } else {
    replyErr("unknown_cmd");
  }
}

void pollSerial() {
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n') {
      if (provWindowOpen()) handleLine(lineBuf);
      lineBuf = "";
    } else if (c != '\r' && lineBuf.length() < 1024) {
      lineBuf += c;
    }
  }
}

// ---------- Wi-Fi / MQTT / WOL ----------
WiFiClient net;
PubSubClient mqtt(net);
WiFiUDP udp;

String tCmd, tStatus, tHeartbeat;
uint32_t lastBeat = 0, lastPulse = 0, lastMqttTry = 0, lastWifiTry = 0;

bool timeSynced();

void publishStatus(const char *state, const char *detail = "") {
  JsonDocument d;
  d["state"] = state;
  d["detail"] = detail;
  d["ip"] = WiFi.localIP().toString();
  d["fw"] = FW_VERSION;
  d["protocol"] = PROTOCOL_VERSION;
  d["time_synced"] = timeSynced();
  char buf[320];
  size_t n = serializeJson(d, buf);
  mqtt.publish(tStatus.c_str(), (const uint8_t *)buf, n, true);
}

void doWake() {
  uint8_t mac[6];
  IPAddress bcast(255, 255, 255, 255);
  if (!parseMac(cfg.pc_mac.c_str(), mac)) {
    publishStatus("online", "wake_pc:bad_mac");
    return;
  }
  parseIPv4(cfg.pc_broadcast.c_str(), bcast);
  uint8_t pkt[102];
  memset(pkt, 0xFF, 6);
  for (int i = 1; i <= 16; i++) memcpy(pkt + i * 6, mac, 6);
  bool ok = true;
  for (int r = 0; r < 3; r++) {
    ok &= udp.beginPacket(bcast, 9) == 1;
    udp.write(pkt, sizeof(pkt));
    ok &= udp.endPacket() == 1;
    delay(100);
  }
  publishStatus("online", ok ? "wake_pc:sent" : "wake_pc:failed");
}

// ---------- signed command verification (protocol v0.2) ----------
// sig = hex(HMAC-SHA256(hub_token, "<id>|<action>|<ms>|<ts>")), ts = Unix seconds.
static const int REPLAY_RING = 16;
String recentIds[REPLAY_RING];
int recentIdx = 0;

bool timeSynced() { return time(nullptr) > TIME_VALID_AFTER; }

bool idSeen(const char *id) {
  for (const String &r : recentIds)
    if (r.length() && r == id) return true;
  return false;
}

void rememberId(const char *id) {
  recentIds[recentIdx] = id;
  recentIdx = (recentIdx + 1) % REPLAY_RING;
}

bool hmacHex(const String &key, const String &msg, char out[65]) {
  uint8_t mac[32];
  const mbedtls_md_info_t *info = mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);
  if (!info || mbedtls_md_hmac(info, (const uint8_t *)key.c_str(), key.length(),
                               (const uint8_t *)msg.c_str(), msg.length(), mac) != 0)
    return false;
  for (int i = 0; i < 32; i++) snprintf(out + i * 2, 3, "%02x", mac[i]);
  return true;
}

// Constant-time compare (case-insensitive for hex).
bool ctEqual(const char *a, const char *b) {
  size_t la = strlen(a), lb = strlen(b);
  uint8_t diff = la != lb;
  for (size_t i = 0; i < la && i < lb; i++) diff |= tolower((unsigned char)a[i]) ^ tolower((unsigned char)b[i]);
  return diff == 0;
}

// Returns nullptr if the command may run, else a reason string (also used in status).
const char *verifySigned(const char *id, const char *action, long long ms, long long ts,
                         const char *sig) {
  if (cfg.hub_token.length() == 0) return "rejected_no_hub_token";
  if (!id || !*id || strchr(id, '|') || strlen(id) > 64) return "rejected_bad_id";
  if (!sig || strlen(sig) != 64) return "rejected_bad_sig";
  if (!timeSynced()) return "rejected_no_time_sync";  // fail closed
  String msg = String(id) + "|" + action + "|" + String((long)ms) + "|" + String((long)ts);
  char expected[65];
  if (!hmacHex(cfg.hub_token, msg, expected)) return "rejected_hmac_error";
  if (!ctEqual(expected, sig)) return "rejected_bad_sig";
  long long skew = (long long)time(nullptr) - ts;
  if (skew > SIG_MAX_SKEW_S || skew < -SIG_MAX_SKEW_S) return "rejected_stale_ts";
  if (idSeen(id)) return "rejected_replay";
  rememberId(id);
  return nullptr;
}

// Returns "done" or a rejection reason. Publishes the result in status.
const char *doPulse(JsonVariantConst d) {
  static char detail[64];
  const char *id = d["id"] | (const char *)nullptr;
  const char *sig = d["sig"] | (const char *)nullptr;
  long long ms = d["ms"] | (long long)PULSE_DEFAULT_MS;
  long long ts = d["ts"] | (long long)0;
  const char *why = verifySigned(id, "power_pulse", ms, ts, sig);
  if (!why && lastPulse != 0 && millis() - lastPulse < PULSE_COOLDOWN_MS) why = "rejected_cooldown";
  if (why) {
    snprintf(detail, sizeof(detail), "power_pulse:%s", why);
    publishStatus("online", detail);
    return why;
  }
  uint32_t pms = (ms <= 0 || ms > PULSE_MAX_MS) ? PULSE_MAX_MS : (uint32_t)ms;  // hard cap
  digitalWrite(PULSE_PIN, HIGH);
  delay(pms);
  digitalWrite(PULSE_PIN, LOW);
  lastPulse = millis();
  publishStatus("online", "power_pulse:done");
  return "done";
}

void onMessage(char *topic, byte *payload, unsigned int len) {
  JsonDocument d;
  if (deserializeJson(d, payload, len)) return;
  const char *action = d["action"] | "";
  if (strcmp(action, "wake_pc") == 0) {
    doWake();
  } else if (strcmp(action, "power_pulse") == 0) {
    doPulse(d.as<JsonVariantConst>());
  }
}

// ---------- standalone HTTP API (works without hub/broker) ----------
WebServer http(HTTP_PORT);
bool httpStarted = false, mdnsStarted = false;
uint32_t lastHttpWake = 0;

void sendJson(int code, JsonDocument &d) {
  String out;
  serializeJson(d, out);
  http.send(code, "application/json", out);
}

void sendErr(int code, const char *err) {
  JsonDocument d;
  d["ok"] = false;
  d["err"] = err;
  sendJson(code, d);
}

bool bearerOk() {
  if (cfg.hub_token.length() == 0) return false;
  String h = http.header("Authorization");
  if (!h.startsWith("Bearer ")) return false;
  return ctEqual(h.substring(7).c_str(), cfg.hub_token.c_str());
}

void httpHealth() {
  JsonDocument d;
  d["ok"] = true;
  d["node_id"] = cfg.node_id;
  d["fw"] = FW_VERSION;
  d["protocol"] = PROTOCOL_VERSION;
  d["time_synced"] = timeSynced();
  d["mqtt"] = mqtt.connected();
  d["ip"] = WiFi.localIP().toString();
  sendJson(200, d);
}

void httpWake() {
  if (cfg.hub_token.length() == 0) return sendErr(503, "no_hub_token");
  if (!bearerOk()) return sendErr(401, "unauthorized");
  if (lastHttpWake != 0 && millis() - lastHttpWake < HTTP_WAKE_MIN_INTERVAL_MS)
    return sendErr(429, "rate_limited");
  lastHttpWake = millis();
  doWake();
  JsonDocument d;
  d["ok"] = true;
  d["sent"] = "wake_pc";
  sendJson(200, d);
}

void httpPulse() {
  JsonDocument d;
  if (deserializeJson(d, http.arg("plain")) || !d.is<JsonObject>()) return sendErr(400, "bad_json");
  const char *action = d["action"] | "power_pulse";
  if (strcmp(action, "power_pulse") != 0) return sendErr(400, "bad_action");
  const char *r = doPulse(d.as<JsonVariantConst>());
  if (!strcmp(r, "done")) {
    JsonDocument ok;
    ok["ok"] = true;
    ok["result"] = "power_pulse:done";
    return sendJson(200, ok);
  }
  int code = 403;
  if (!strcmp(r, "rejected_cooldown")) code = 429;
  else if (!strcmp(r, "rejected_replay")) code = 409;
  else if (!strcmp(r, "rejected_no_time_sync") || !strcmp(r, "rejected_no_hub_token")) code = 503;
  sendErr(code, r);
}

void startNetServices() {
  if (!httpStarted) {
    const char *hdrs[] = {"Authorization"};
    http.collectHeaders(hdrs, 1);
    http.on("/health", HTTP_GET, httpHealth);
    http.on("/wake", HTTP_POST, httpWake);
    http.on("/pulse", HTTP_POST, httpPulse);
    http.onNotFound([] { sendErr(404, "not_found"); });
    http.begin();
    httpStarted = true;
  }
  if (!mdnsStarted) {
    String host = String("pyra-") + cfg.node_id;
    if (MDNS.begin(host.c_str())) {
      MDNS.addService("http", "tcp", HTTP_PORT);
      mdnsStarted = true;
    }
  }
}

// Non-blocking-ish: a short TCP probe (500 ms) gates the MQTT connect, with backoff up to
// 30 s, so serial provisioning and the HTTP API stay responsive when the broker is down.
uint32_t mqttBackoff = 2000;

void ensureMqtt() {
  if (mqtt.connected() || millis() - lastMqttTry < mqttBackoff) return;
  lastMqttTry = millis();
  if (cfg.broker.length() == 0 || !net.connect(cfg.broker.c_str(), cfg.broker_port, 500)) {
    net.stop();
    mqttBackoff = min<uint32_t>(mqttBackoff * 2, 30000);
    return;
  }
  const char *lwt = "{\"state\":\"offline\"}";
  const char *user = cfg.mqtt_user.length() ? cfg.mqtt_user.c_str() : nullptr;
  const char *pass = cfg.mqtt_pass.length() ? cfg.mqtt_pass.c_str() : nullptr;
  if (mqtt.connect(cfg.node_id.c_str(), user, pass, tStatus.c_str(), 1, true, lwt)) {
    mqttBackoff = 2000;
    mqtt.subscribe(tCmd.c_str(), 1);
    publishStatus("online", timeSynced() ? "boot" : "boot:no_time_sync");
  } else {
    net.stop();
    mqttBackoff = min<uint32_t>(mqttBackoff * 2, 30000);
  }
}

void setup() {
  pinMode(PULSE_PIN, OUTPUT);
  digitalWrite(PULSE_PIN, LOW);  // fail safe: relay off
  Serial.begin(115200);
  bootMs = millis();
  loadCfg();
  Serial.printf("pyra-claw fw %s node=%s provisioning=v1\n", FW_VERSION, cfg.node_id.c_str());
  String base = String("pyra/node/") + cfg.node_id;
  tCmd = base + "/cmd";
  tStatus = base + "/status";
  tHeartbeat = base + "/heartbeat";
  WiFi.mode(WIFI_STA);
  if (cfg.ssid.length()) {
    WiFi.begin(cfg.ssid.c_str(), cfg.pass.c_str());
  } else {
    Serial.println("no ssid configured; waiting for serial provisioning");
  }
  configTime(0, 0, "pool.ntp.org", "time.google.com", "time.cloudflare.com");  // UTC
  mqtt.setServer(cfg.broker.c_str(), (uint16_t)cfg.broker_port);
  mqtt.setSocketTimeout(2);
  mqtt.setCallback(onMessage);
}

void loop() {
  pollSerial();
  if (cfg.ssid.length() == 0) {
    delay(10);
    return;
  }
  if (WiFi.status() != WL_CONNECTED) {
    if (millis() - lastWifiTry > 10000) {
      lastWifiTry = millis();
      WiFi.reconnect();
    }
    delay(10);
    return;
  }
  startNetServices();
  http.handleClient();
  ensureMqtt();
  mqtt.loop();
  if (mqtt.connected() && millis() - lastBeat > 10000) {
    lastBeat = millis();
    char buf[64];
    snprintf(buf, sizeof(buf), "{\"uptime_s\":%lu,\"rssi\":%d}", millis() / 1000, WiFi.RSSI());
    mqtt.publish(tHeartbeat.c_str(), buf);
  }
}

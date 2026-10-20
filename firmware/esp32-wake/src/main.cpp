// Pyra Claw ESP32 wake node.
// CONCEPT / UNTESTED: verify wiring before connecting to a real PC.
// USB serial provisioning contract v1: see docs/PROVISIONING.md.
#include <Arduino.h>
#include <ArduinoJson.h>
#include <Preferences.h>
#include <PubSubClient.h>
#include <WiFi.h>
#include <WiFiUdp.h>

#if __has_include("config.h")
#include "config.h"
#else
#include "config.example.h"
#endif

#ifndef FW_VERSION
#define FW_VERSION "0.2.0"
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

void publishStatus(const char *state, const char *detail = "") {
  JsonDocument d;
  d["state"] = state;
  d["detail"] = detail;
  d["ip"] = WiFi.localIP().toString();
  char buf[256];
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

void doPulse(uint32_t ms, const char *approval) {
  // Approval token is validated by the hub; node refuses if it is missing.
  if (approval == nullptr || strlen(approval) < 8) {
    publishStatus("online", "power_pulse:rejected_no_approval");
    return;
  }
  if (millis() - lastPulse < PULSE_COOLDOWN_MS && lastPulse != 0) {
    publishStatus("online", "power_pulse:rejected_cooldown");
    return;
  }
  if (ms == 0 || ms > PULSE_MAX_MS) ms = PULSE_MAX_MS;
  digitalWrite(PULSE_PIN, HIGH);
  delay(ms);
  digitalWrite(PULSE_PIN, LOW);
  lastPulse = millis();
  publishStatus("online", "power_pulse:done");
}

void onMessage(char *topic, byte *payload, unsigned int len) {
  JsonDocument d;
  if (deserializeJson(d, payload, len)) return;
  const char *action = d["action"] | "";
  if (strcmp(action, "wake_pc") == 0) {
    doWake();
  } else if (strcmp(action, "power_pulse") == 0) {
    doPulse(d["ms"] | PULSE_DEFAULT_MS, d["approval"] | (const char *)nullptr);
  }
}

// Non-blocking so the serial provisioning port stays responsive.
void ensureMqtt() {
  if (mqtt.connected() || millis() - lastMqttTry < 2000) return;
  lastMqttTry = millis();
  const char *lwt = "{\"state\":\"offline\"}";
  const char *user = cfg.mqtt_user.length() ? cfg.mqtt_user.c_str() : nullptr;
  const char *pass = cfg.mqtt_pass.length() ? cfg.mqtt_pass.c_str() : nullptr;
  if (mqtt.connect(cfg.node_id.c_str(), user, pass, tStatus.c_str(), 1, true, lwt)) {
    mqtt.subscribe(tCmd.c_str(), 1);
    publishStatus("online", "boot");
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
  mqtt.setServer(cfg.broker.c_str(), (uint16_t)cfg.broker_port);
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
  ensureMqtt();
  mqtt.loop();
  if (mqtt.connected() && millis() - lastBeat > 10000) {
    lastBeat = millis();
    char buf[64];
    snprintf(buf, sizeof(buf), "{\"uptime_s\":%lu,\"rssi\":%d}", millis() / 1000, WiFi.RSSI());
    mqtt.publish(tHeartbeat.c_str(), buf);
  }
}

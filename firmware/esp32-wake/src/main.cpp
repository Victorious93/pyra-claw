// Pyra Claw ESP32 wake node.
// CONCEPT / UNTESTED: verify wiring before connecting to a real PC.
#include <Arduino.h>
#include <ArduinoJson.h>
#include <PubSubClient.h>
#include <WiFi.h>
#include <WiFiUdp.h>
#include <WakeOnLan.h>

#if __has_include("config.h")
#include "config.h"
#else
#include "config.example.h"
#endif

// Hard safety cap, enforced regardless of what the hub requests.
static const uint32_t PULSE_MAX_MS = 300;

WiFiClient net;
PubSubClient mqtt(net);
WiFiUDP udp;
WakeOnLan wol(udp);

String tCmd, tStatus, tHeartbeat;
uint32_t lastBeat = 0;
uint32_t lastPulse = 0;
static const uint32_t PULSE_COOLDOWN_MS = 5000;

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
  uint8_t mac[6] = PC_MAC;
  char macStr[18];
  snprintf(macStr, sizeof(macStr), "%02X:%02X:%02X:%02X:%02X:%02X", mac[0], mac[1],
           mac[2], mac[3], mac[4], mac[5]);
  wol.setRepeat(3, 100);
  wol.calculateBroadcastAddress(WiFi.localIP(), WiFi.subnetMask());
  bool ok = wol.sendMagicPacket(macStr);
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

void ensureMqtt() {
  while (!mqtt.connected()) {
    const char *lwt = "{\"state\":\"offline\"}";
    if (mqtt.connect(NODE_ID, MQTT_USER, MQTT_PASS, tStatus.c_str(), 1, true, lwt)) {
      mqtt.subscribe(tCmd.c_str(), 1);
      publishStatus("online", "boot");
    } else {
      delay(2000);
    }
  }
}

void setup() {
  pinMode(PULSE_PIN, OUTPUT);
  digitalWrite(PULSE_PIN, LOW);  // fail safe: relay off
  Serial.begin(115200);
  String base = String("pyra/node/") + NODE_ID;
  tCmd = base + "/cmd";
  tStatus = base + "/status";
  tHeartbeat = base + "/heartbeat";
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASS);
  while (WiFi.status() != WL_CONNECTED) delay(250);
  mqtt.setServer(MQTT_HOST, MQTT_PORT);
  mqtt.setCallback(onMessage);
}

void loop() {
  if (WiFi.status() != WL_CONNECTED) WiFi.reconnect();
  ensureMqtt();
  mqtt.loop();
  if (millis() - lastBeat > 10000) {
    lastBeat = millis();
    char buf[64];
    snprintf(buf, sizeof(buf), "{\"uptime_s\":%lu,\"rssi\":%d}", millis() / 1000, WiFi.RSSI());
    mqtt.publish(tHeartbeat.c_str(), buf);
  }
}

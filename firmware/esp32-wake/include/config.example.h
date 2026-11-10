// Copy to include/config.h (git-ignored) and edit.
#pragma once
#define WIFI_SSID      "your-ssid"
#define WIFI_PASS      "your-pass"
#define MQTT_HOST      "192.168.4.1"   // Pi 5 hub
#define MQTT_PORT      1883
#define MQTT_USER      ""
#define MQTT_PASS      ""
#define NODE_ID        "esp32-wake-01"
#define PC_MAC         {0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF}
#define PULSE_PIN      26              // -> 330R -> PC817 anode
#define PULSE_DEFAULT_MS 300
#define PC_BROADCAST   "255.255.255.255"
#define HUB_TOKEN      ""
#define NTP_SERVER     ""              // "" = use MQTT_HOST (the Pi runs chrony)
#define USB_PINS       ""              // e.g. "27,32,33": one GPIO per USB 5 V channel
// All of the above can be overridden at runtime over USB serial (docs/PROVISIONING.md).

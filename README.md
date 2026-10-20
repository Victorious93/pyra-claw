<p align="center"><img src="assets/logo.png" width="180" alt="Pyra Claw logo"></p>

# Pyra Claw

**Raspberry Pi 5 + ESP32 command hub for the Victorious fleet.** You control it from an Android phone over USB-C tethering or Wi-Fi. The hub relays commands over MQTT to ESP32 nodes. The first node wakes Don's PC with **Wake-on-LAN** and an approval-gated, opto-isolated **PWR_SW pulse** (≤ 300 ms). The Android app works on its own and also registers as a droidcommand-AI `EXTERNAL_NODE`.

> ⚠️ v0.1 scaffold: **CONCEPT / UNTESTED** on real hardware.

## Quick links
- 📘 [Manual](docs/MANUAL.md) · 📡 [Protocol](docs/PROTOCOL.md) · 🖨️ [Case print specs](docs/CASES.md)
- 🐍 [hub/](hub/): FastAPI daemon (Pi OS Bookworm)
- 🔌 [firmware/esp32-wake/](firmware/esp32-wake/): PlatformIO ESP32 node
- 📱 [android/](android/): Kotlin + Compose app

## Visuals
<p>
<img src="assets/visuals/02_hero_desk_setup_16x9_alt-zimage.png" width="640" alt="Hero">
</p>
<p>
<img src="assets/visuals/03a_dashboard_9x16.png" width="180" alt="Dashboard">
<img src="assets/visuals/03b_power_pulse_confirm_9x16.png" width="180" alt="Power pulse confirm">
<img src="assets/visuals/03c_nodes_detail_9x16.png" width="180" alt="Nodes">
</p>
<p><img src="assets/visuals/05a_architecture.png" width="640" alt="Architecture"></p>

## Quick start
```bash
sudo ./hub/install.sh                       # on the Pi
cd firmware/esp32-wake && pio run -t upload # ESP32 (after creating include/config.h)
cd android && ./gradlew assembleDebug       # APK
```

## License
MIT

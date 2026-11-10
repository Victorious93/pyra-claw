package com.victorious.pyraclaw.ui.help

/** In-app manual (fleet rule 8). Mirrors docs/MANUAL.md, "Android app" section. */
data class HelpSection(val id: String, val title: String, val summary: String, val body: List<String>)

object HelpContent {
    val sections = listOf(
        HelpSection(
            "start", "Getting started", "What Pyra Claw is and the 3-minute setup",
            listOf(
                "Pyra Claw turns a Raspberry Pi 5 into a command hub. This app talks to the hub; the hub drives ESP32 nodes over MQTT. The first node wakes your PC (Wake-on-LAN) and can press its power button through a PC817 optocoupler.",
                "1. Install the hub on the Pi: sudo ./hub/install.sh (see docs/MANUAL.md). Note the auth_token it prints or find it in /etc/pyra-claw/config.toml.",
                "2. Connect this phone to the Pi: same Wi-Fi, or USB-C cable with USB tethering turned on.",
                "3. Open Pyra Claw, scan or type the hub address, paste the token, press Test connection, then Save.",
                "4. On the Dashboard, press WAKE PC. That's it.",
                "Status: the app, hub and firmware are not yet tested on real hardware. Treat every result as something to double-check the first time.",
            ),
        ),
        HelpSection(
            "pairing", "Pairing and the token", "Hub URL, scanning, token storage, errors",
            listOf(
                "Hub URL: the Pi's address plus port 8787, for example http://192.168.1.20:8787. If you leave out the port the app adds 8787. If you leave out http:// the app adds it.",
                "Scan: looks for the hub over mDNS (_pyra-hub._tcp, advertised by hub/install.sh v0.4+, and _http._tcp services named pyra*) and probes the usual addresses: 192.168.42.1 (USB tether), pyra.local, pyra-hub.local, raspberrypi.local. Older hubs that don't advertise are still found if they use one of those addresses.",
                "Token: auth_token from /etc/pyra-claw/config.toml on the Pi (section [hub]). It is stored on this phone encrypted with a key held in the Android Keystore. It is never shown again after saving.",
                "Test connection checks two things: GET /health (is the address right?) then GET /api/status (is the token right?). \"Can't reach the hub\" means the address/port/network is wrong. \"Token rejected (401)\" means the address is right but the token is not.",
                "To re-pair later: Settings > Change hub. To remove everything: Settings > Forget this hub.",
            ),
        ),
        HelpSection(
            "dashboard", "Dashboard", "Live status, Wake PC, quick actions, nodes",
            listOf(
                "The pill at the top right shows the live link to the hub (WebSocket /ws, a frame every 2 s): Live (green), Connecting/Offline with a retry countdown (amber), Token rejected (red). It reconnects by itself with a growing delay up to 30 s. Pull down or tap Refresh to retry right away.",
                "WAKE PC sends a Wake-on-LAN magic packet from the hub and asks the ESP32 wake node to send one too. No approval is needed. The result says how many bytes were sent and whether the ESP32 relay worked. The hub cannot tell whether the PC actually turned on; give it about 30 seconds.",
                "Power button opens the approval sheet (see Approvals).",
                "Run a routine lists your enabled Manual timers so you can start them with one tap.",
                "ESP32 nodes shows every node that has sent a heartbeat: online/offline, last seen, IP, firmware, Wi-Fi signal and whether it has synced its clock (needed for signed power pulses).",
            ),
        ),
        HelpSection(
            "approvals", "Approvals (power button, power off)", "Hold, approve, send within 30 s",
            listOf(
                "Some commands can hurt a running machine: pressing the PC power button (pc.power_pulse) and powering a device off (device.power_off). They always need a fresh approval.",
                "Step 1 · Hold the magenta bar for 1.5 s. The app asks the hub for a one-time approval token (POST /api/approve/<command>).",
                "Step 2 · A 30-second countdown starts. Press Send before it runs out. The command goes to the hub with the token, which works once.",
                "If the countdown hits zero, nothing is sent. Request again if you still want it. Every approval and every rejection is written to the hub's audit log (Activity tab).",
                "Pressing the power button on a PC that is already on may make it sleep or shut down, depending on the OS power settings. The ESP32 limits the pulse to 300 ms, so it can't hold power for a forced shutdown, and enforces a 5 s cooldown.",
            ),
        ),
        HelpSection(
            "devices", "Devices and charging", "Charge on/off, power on/off, hub config",
            listOf(
                "The Devices tab lists the PC and every device configured on the hub under [devices.<id>] in /etc/pyra-claw/config.toml.",
                "Each device shows its backends: charge (uhubctl USB port power, adb root charging toggle, or mock), power_on and power_off. Buttons only appear for actions the hub can actually do.",
                "Charge on / Charge off / Power on run immediately. Power off needs approval.",
                "Real charge control needs a USB hub with per-port power switching (for example UUGear MEGA4) or rooted phones with USB debugging. The Pi 5's own USB ports switch off together.",
                "Power on by restoring USB power is unreliable: many phones boot to the charging screen instead of Android.",
                "A 502 error means the hub tried and the backend failed; the message includes the reason from the hub.",
            ),
        ),
        HelpSection(
            "timers", "Timers", "Schedules, steps, reorder, arming",
            listOf(
                "Timers live on the hub. It runs them even when this phone is off or asleep.",
                "Repeat: Once (date + time), Daily (time), Weekly (time + days, with Weekdays/Weekends shortcuts) or Manual (only when you press Run). Times are the hub's local time.",
                "Steps run top to bottom. Each step has an action (charging on/off, power on/off, wake PC), a device, and a wait that happens before it (0 s to 6 h). Drag the ⠿ handle to reorder, or use the arrows. The first failing step stops the run.",
                "Arming: a timer with a Power off step needs a one-time timer.arm approval when you save it, because no one will be there to approve it at 3 a.m. You hold to confirm, the app gets the approval, and the hub records timer_preapproved in the audit log. The card shows \"armed\". Editing the name or schedule keeps the approval; changing the steps needs a new one.",
                "Each card shows the next run, live progress while running, the last result, and buttons to run now, edit or delete. The switch pauses a timer without deleting it.",
                "Missed runs (hub off at fire time) are skipped, not replayed. A Once timer turns itself off after it fires.",
            ),
        ),
        HelpSection(
            "activity", "Activity and audit log", "Filters, search, phone log",
            listOf(
                "Hub audit shows the last 200 events from GET /api/audit: accepted and executed commands, approvals issued, rejections (with the reason), and timer events.",
                "Filters: All, Commands, Approvals, Problems (rejected/failed), Timers. Search matches command, device, reason, source and timer names.",
                "This phone shows what the app's background link did: connections, droidcommand-AI commands received and their results.",
            ),
        ),
        HelpSection(
            "background", "Background link", "Screen-off commands, battery, notification",
            listOf(
                "Settings > Background link keeps a foreground service connected to the hub with the screen off. Android requires a visible notification for this; you can stop it from the notification or the switch.",
                "It is needed for droidcommand-AI to send commands while the phone sleeps. Timers do NOT need it (the hub runs them).",
                "Allow unrestricted battery use (Settings > Battery optimization > Allow), otherwise Android may stop the link after a while.",
                "On Android 15 the service may fall back to the dataSync type, which Android limits to 6 hours per day.",
            ),
        ),
        HelpSection(
            "droidcommand", "droidcommand-AI", "EXTERNAL_NODE integration",
            listOf(
                "Pyra Claw registers as EXTERNAL_NODE \"pyra-claw\" with droidcommand-AI and accepts remote.command / macro.step envelopes through a broadcast protected by a signature permission.",
                "Commands are forwarded to the hub's POST /api/command. Approval-required commands still need an approval token; the hub enforces it.",
                "The registration contract is still a draft and the intent names may change. Turn on the background link so commands can be received while the phone sleeps.",
            ),
        ),
        HelpSection(
            "esp32", "ESP32 node", "Provisioning, standalone mode, LEDs",
            listOf(
                "Provision the ESP32 over USB with tools/provision.py (Wi-Fi, MQTT broker, node id, PC MAC, hub_token). See docs/PROVISIONING.md.",
                "Without the Pi, the ESP32 answers on http://pyra-<node_id>.local: GET /health, POST /wake (Bearer hub_token, max 1 per 10 s), POST /pulse (HMAC-signed only). This app does not use the standalone API yet.",
                "A node shows \"no NTP\" until it syncs its clock. Signed power pulses are refused until then (fail closed).",
            ),
        ),
        HelpSection(
            "trouble", "Troubleshooting", "What each error means",
            listOf(
                "Can't reach the hub: check the Pi is on, systemctl status pyra-hub, the phone network (same Wi-Fi or USB tether), the port (8787), and the firewall (install.sh only allows the LAN/tether subnets).",
                "Token rejected (401): re-copy auth_token from /etc/pyra-claw/config.toml (Settings > Change hub).",
                "Approval rejected (403): the approval token was missing, expired after 30 s, or already used. Request a new one.",
                "Busy (409): the timer is already running.",
                "Device backend failed (502): the hub's backend for that device failed. The message has the reason (e.g. uhubctl not found).",
                "Hub not ready (503): for the power button, node.hub_token isn't set in the hub config.",
                "Live link keeps retrying: the REST API may still work. Pull to refresh. Check that nothing between the phone and the Pi blocks WebSockets.",
                "Nodes offline: check the ESP32's Wi-Fi and MQTT credentials, and that Mosquitto listens on the network (install.sh v0.2+).",
                "Can't install this APK over an older Pyra Claw: v0.4.0 is signed with a new fixed key. Uninstall the old app once.",
            ),
        ),
        HelpSection(
            "safety", "Safety", "What is gated and why",
            listOf(
                "Destructive actions (PC power button, device power off, arming timers that power off) always need an explicit hold-to-confirm and a single-use approval that expires after 30 s.",
                "The hub signs every power pulse it relays (HMAC-SHA256 with hub_token). The ESP32 rejects unsigned, stale (>30 s), replayed or too-frequent pulses.",
                "Keep the hub on a trusted network or the USB tether. Never expose port 8787 to the internet. The API is plain HTTP.",
            ),
        ),
    )

    fun search(q: String): List<HelpSection> {
        val terms = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return sections
        return sections.filter { s ->
            val hay = (s.title + " " + s.summary + " " + s.body.joinToString(" ")).lowercase()
            terms.all { it in hay }
        }
    }
}

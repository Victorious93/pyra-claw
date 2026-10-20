OUT="/workspace/pyra-claw-visuals/src"
BASE='''<html><head><meta charset="utf-8"><style>*{margin:0;padding:0;box-sizing:border-box}body{width:%dpx;height:%dpx;background:#141417;font-family:Inter,sans-serif;color:#F2EEE9;overflow:hidden}
text{font-family:Inter,sans-serif}.m{font-family:'JetBrains Mono',monospace}</style></head><body>%s</body></html>'''
def box(x,y,w,h,title,lines,accent="#FF6A1A",fill="#1F1F24"):
    s=f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="22" fill="{fill}" stroke="{accent}" stroke-width="3"/>'
    s+=f'<text x="{x+w/2}" y="{y+48}" text-anchor="middle" font-size="30" font-weight="800" fill="#F2EEE9">{title}</text>'
    for i,l in enumerate(lines):
        s+=f'<text x="{x+w/2}" y="{y+90+i*34}" text-anchor="middle" font-size="22" fill="#B9B2AA">{l}</text>'
    return s
def label(x,y,t,c="#FF9A4D",bg="#141417",fs=22):
    w=len(t)*fs*0.58+28
    return f'<rect x="{x-w/2}" y="{y-fs-4}" width="{w}" height="{fs+18}" rx="10" fill="{bg}" stroke="{c}" stroke-width="1.5"/><text x="{x}" y="{y+4}" text-anchor="middle" font-size="{fs}" font-weight="700" fill="{c}" class="m">{t}</text>'
DEFS='<defs><marker id="a" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="#FF8A3D"/></marker><marker id="b" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10z" fill="#7FB8FF"/></marker></defs>'
def ln(x1,y1,x2,y2,c="#FF8A3D",dash="",both=True,m="a"):
    return f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{c}" stroke-width="4" {"stroke-dasharray=%s"%chr(34)+dash+chr(34) if dash else ""} marker-end="url(#{m})" {"marker-start=%s"%chr(34)+"url(#"+m+")"+chr(34) if both else ""}/>'
# ---------- (a) architecture 1920x1080
W,H=1920,1080
s=f'<svg width="{W}" height="{H}">{DEFS}'
s+='<text x="60" y="80" font-size="48" font-weight="800" fill="#F2EEE9">Pyra Claw · <tspan fill="#FF6A1A">System Architecture</tspan></text>'
s+='<text x="60" y="122" font-size="24" fill="#9A938C">Concept diagram — topics, ports and pins are example values; adapt to your build.</text>'
s+=box(60,370,400,300,"Android Phone",["Pyra Claw APK","(Kotlin / Jetpack Compose)","also registers as a","droidcommand-AI node","Wake PC · Power Pulse (approval)"])
s+=box(700,370,460,300,"Raspberry Pi 5 Hub",["pyra-hub service (HTTP API)","MQTT broker (e.g. Mosquitto)","approval gate + pulse cap","status OLED · event log","hex claw enclosure"])
s+=box(1400,180,460,250,"ESP32 · esp32-wake",["mounted inside PC case","sends WOL magic packet","GPIO → PC817 opto → PWR_SW","~300 ms pulse, approval-gated"])
s+=box(720,150,420,150,"ESP32 · esp32-aux (optional)",["sensors / LEDs · MQTT client"],accent="#8A847E")
s+=box(1400,700,460,210,"Don's PC",["NIC: Wake-on-LAN enabled","Front-panel header PWR_SW","(parallel with case button)"],accent="#7FB8FF")
# links
s+=ln(462,480,698,480); s+=label(580,465,"USB-C OTG")
s+=ln(462,570,698,570,dash="12 10"); s+=label(580,555,"Wi-Fi (HTTP)")
s+=ln(1162,450,1398,330,dash="12 10"); s+=label(1280,370,"MQTT / HTTP")
s+=ln(930,368,930,302,dash="8 8",c="#8A847E"); s+=label(1030,345,"MQTT",c="#B9B2AA")
s+=f'<line x1="1500" y1="432" x2="1500" y2="698" stroke="#7FB8FF" stroke-width="4" stroke-dasharray="12 10" marker-end="url(#b)"/>'+label(1500,575,"WOL (UDP 9, LAN)",c="#7FB8FF")
s+=f'<line x1="1760" y1="432" x2="1760" y2="698" stroke="#FF8A3D" stroke-width="5" marker-end="url(#a)"/>'+label(1740,650,"relay/PC817 ~300 ms",fs=20)
# legend
s+='<rect x="60" y="760" width="1080" height="260" rx="22" fill="#1A1A1E" stroke="#2E2E35"/>'
s+='<text x="90" y="805" font-size="26" font-weight="800" fill="#FF9A4D">Command flow</text>'
for i,t in enumerate(["1. Tap Wake PC / Power Pulse in the APK (USB-C OTG or Wi-Fi to the hub)","2. Hub validates; Power Pulse requires explicit approval (hold + confirm)","3. Hub publishes the command to esp32-wake over MQTT (or HTTP)","4. esp32-wake sends the WOL magic packet, or pulses the PC817 ~300 ms","Solid line = wired · dashed line = network"]):
    s+=f'<text x="90" y="{850+i*36}" font-size="22" fill="#CFC8C0">{t}</text>'
s+='</svg>'
open(f"{OUT}/05a_architecture.html","w").write(BASE%(W,H,s))
# ---------- (b) wiring 1920x1200
W,H=1920,1200
s=f'<svg width="{W}" height="{H}">{DEFS}'
s+='<text x="60" y="80" font-size="46" font-weight="800" fill="#F2EEE9">Power Pulse Wiring · <tspan fill="#FF6A1A">ESP32 → PC817 → PWR_SW</tspan></text>'
s+='<rect x="1380" y="40" width="480" height="64" rx="14" fill="#3A1414" stroke="#FF5A4F" stroke-width="2"/><text x="1620" y="82" text-anchor="middle" font-size="26" font-weight="800" fill="#FF7A70">CONCEPT · NOT TESTED</text>'
s+='<text x="60" y="124" font-size="24" fill="#9A938C">Verify PWR_SW pin positions and polarity against YOUR motherboard manual before wiring.</text>'
# ESP32
s+='<rect x="80" y="230" width="380" height="520" rx="20" fill="#1F1F24" stroke="#FF6A1A" stroke-width="3"/><text x="270" y="285" text-anchor="middle" font-size="32" font-weight="800" fill="#F2EEE9">ESP32</text><text x="270" y="318" text-anchor="middle" font-size="20" fill="#9A938C">(esp32-wake node)</text>'
s+='<circle cx="460" cy="420" r="9" fill="#FF8A3D"/><text x="440" y="428" text-anchor="end" font-size="26" fill="#F2EEE9" class="m">GPIO26</text><text x="440" y="458" text-anchor="end" font-size="18" fill="#9A938C">(example; avoid strapping pins)</text>'
s+='<circle cx="460" cy="640" r="9" fill="#B9B2AA"/><text x="440" y="648" text-anchor="end" font-size="26" fill="#F2EEE9" class="m">GND</text>'
s+='<text x="270" y="720" text-anchor="middle" font-size="18" fill="#9A938C">3.3 V logic · drive HIGH ~300 ms</text>'
# resistor
s+='<line x1="460" y1="420" x2="560" y2="420" stroke="#FF8A3D" stroke-width="4"/>'
s+='<polyline points="560,420 575,400 600,440 625,400 650,440 675,400 700,440 715,420" fill="none" stroke="#FFB347" stroke-width="4"/>'
s+='<text x="637" y="375" text-anchor="middle" font-size="26" font-weight="800" fill="#FFB347">R1 220–330 Ω</text><text x="637" y="480" text-anchor="middle" font-size="18" fill="#9A938C">≈6–10 mA LED current at 3.3 V</text>'
s+='<line x1="715" y1="420" x2="860" y2="420" stroke="#FF8A3D" stroke-width="4"/>'
s+='<line x1="460" y1="640" x2="860" y2="640" stroke="#B9B2AA" stroke-width="4"/>'
# PC817
s+='<rect x="860" y="330" width="360" height="400" rx="16" fill="#26262B" stroke="#F2EEE9" stroke-width="3"/><text x="1040" y="310" text-anchor="middle" font-size="30" font-weight="800" fill="#F2EEE9">PC817 optocoupler</text>'
s+='<circle cx="880" cy="350" r="8" fill="#F2EEE9"/>'
for y,n,t,side in [(420,"1","Anode",0),(640,"2","Cathode",0),(420,"4","Collector",1),(640,"3","Emitter",1)]:
    x=860 if side==0 else 1220
    s+=f'<rect x="{x-14}" y="{y-12}" width="28" height="24" fill="#B9B2AA"/>'
    tx=x+30 if side==0 else x-30
    s+=f'<text x="{tx}" y="{y+9}" text-anchor="{"start" if side==0 else "end"}" font-size="22" fill="#F2EEE9" class="m">{n} {t}</text>'
# LED + phototransistor symbols
s+='<polygon points="960,500 1000,500 980,540" fill="none" stroke="#FF8A3D" stroke-width="3"/><line x1="958" y1="540" x2="1002" y2="540" stroke="#FF8A3D" stroke-width="3"/><line x1="980" y1="465" x2="980" y2="500" stroke="#FF8A3D" stroke-width="3"/><line x1="980" y1="540" x2="980" y2="600" stroke="#FF8A3D" stroke-width="3"/>'
s+='<line x1="1015" y1="510" x2="1060" y2="510" stroke="#FFB347" stroke-width="2" marker-end="url(#a)"/><line x1="1015" y1="530" x2="1060" y2="530" stroke="#FFB347" stroke-width="2" marker-end="url(#a)"/>'
s+='<line x1="1090" y1="490" x2="1090" y2="560" stroke="#7FB8FF" stroke-width="4"/><line x1="1090" y1="505" x2="1140" y2="470" stroke="#7FB8FF" stroke-width="3"/><line x1="1090" y1="545" x2="1140" y2="580" stroke="#7FB8FF" stroke-width="3" marker-end="url(#b)"/>'
s+='<line x1="980" y1="465" x2="880" y2="420" stroke="#FF8A3D" stroke-width="2"/><line x1="980" y1="600" x2="880" y2="640" stroke="#B9B2AA" stroke-width="2"/><line x1="1140" y1="470" x2="1200" y2="420" stroke="#7FB8FF" stroke-width="2"/><line x1="1140" y1="580" x2="1200" y2="640" stroke="#7FB8FF" stroke-width="2"/>'
s+='<line x1="1040" y1="350" x2="1040" y2="710" stroke="#5E5953" stroke-width="2" stroke-dasharray="8 8"/><text x="1040" y="760" text-anchor="middle" font-size="18" fill="#9A938C">isolation barrier</text>'
# output to header
s+='<line x1="1220" y1="420" x2="1500" y2="420" stroke="#7FB8FF" stroke-width="4"/><line x1="1220" y1="640" x2="1500" y2="640" stroke="#7FB8FF" stroke-width="4"/>'
s+='<rect x="1500" y="330" width="360" height="400" rx="16" fill="#1F1F24" stroke="#7FB8FF" stroke-width="3"/><text x="1680" y="310" text-anchor="middle" font-size="30" font-weight="800" fill="#F2EEE9">PC front-panel header</text>'
s+='<circle cx="1530" cy="420" r="10" fill="#7FB8FF"/><text x="1555" y="428" font-size="26" fill="#F2EEE9" class="m">PWR_SW +</text><text x="1555" y="458" font-size="18" fill="#9A938C">(signal / PWRBTN#)</text>'
s+='<circle cx="1530" cy="640" r="10" fill="#7FB8FF"/><text x="1555" y="648" font-size="26" fill="#F2EEE9" class="m">PWR_SW −</text><text x="1555" y="678" font-size="18" fill="#9A938C">(GND)</text>'
# case button parallel
s+='<line x1="1400" y1="420" x2="1400" y2="820" stroke="#7FB8FF" stroke-width="3"/><line x1="1400" y1="640" x2="1460" y2="640" stroke="#7FB8FF" stroke-width="0"/>'
s+='<line x1="1340" y1="640" x2="1340" y2="900" stroke="#7FB8FF" stroke-width="3"/>'
s+='<circle cx="1400" cy="420" r="7" fill="#7FB8FF"/><circle cx="1340" cy="640" r="7" fill="#7FB8FF"/>'
s+='<line x1="1400" y1="820" x2="1460" y2="820" stroke="#7FB8FF" stroke-width="3"/><line x1="1340" y1="900" x2="1460" y2="900" stroke="#7FB8FF" stroke-width="3"/>'
s+='<rect x="1460" y="790" width="360" height="140" rx="14" fill="#1F1F24" stroke="#8A847E" stroke-width="2"/><text x="1640" y="845" text-anchor="middle" font-size="24" font-weight="800" fill="#F2EEE9">Case power button</text><text x="1640" y="880" text-anchor="middle" font-size="18" fill="#9A938C">momentary · kept connected</text><text x="1640" y="908" text-anchor="middle" font-size="18" fill="#9A938C">(wired in PARALLEL)</text>'
# notes
s+='<rect x="80" y="840" width="1220" height="320" rx="20" fill="#1A1A1E" stroke="#2E2E35"/><text x="110" y="885" font-size="26" font-weight="800" fill="#FF9A4D">Notes (concept — verify everything)</text>'
N=["• Collector (pin 4) → PWR_SW + ; Emitter (pin 3) → PWR_SW −. The output is polarized — if it does nothing, check orientation.",
"• Splice or Y-split the case-button leads so the PC817 output and the button share the same two header pins.",
"• Pulse ~300 ms only. Holding ≥ ~4 s usually forces a hard power-off on most boards.",
"• Choose a GPIO that stays LOW at boot/reset (e.g. GPIO26/27); avoid strapping pins 0, 2, 5, 12, 15.",
"• To wake from full off, the ESP32 needs standby power (5VSB USB header with ErP off, or an external 5 V supply).",
"• Alternative: a relay module's NO/COM contacts across PWR_SW (non-polarized). Firmware should cap pulse length.",
"• Isolation is only real if the ESP32 is not sharing PC ground through its own power source."]
for i,t in enumerate(N): s+=f'<text x="110" y="{925+i*33}" font-size="20" fill="#CFC8C0">{t}</text>'
s+='</svg>'
open(f"{OUT}/05b_wiring.html","w").write(BASE%(W,H,s))
# ---------- (c) setup steps 1920x1080 grid
W,H=1920,1200
steps=[("1","Flash Raspberry Pi OS",["Raspberry Pi Imager → Raspberry Pi OS (64-bit), Lite is fine","OS customisation: hostname pyra-hub, user, Wi-Fi, enable SSH","Boot Pi 5 (official 27 W USB-C PSU recommended)","ssh into pyra-hub.local; sudo apt update && sudo apt full-upgrade"]),
("2","Install the hub service",["sudo apt install mosquitto mosquitto-clients","Copy/clone the pyra-hub service onto the Pi (your repo)","Configure: broker, node list, PC MAC, pulse cap 300 ms, approval on","Enable as systemd unit: sudo systemctl enable --now pyra-hub"]),
("3","Flash the ESP32 nodes",["Arduino IDE / PlatformIO / esptool via USB","Set Wi-Fi SSID, hub broker address, node id (esp32-wake)","esp32-wake: PC MAC for WOL, pulse GPIO (e.g. 26) → PC817","Mount pod inside the PC; wire per the wiring diagram"]),
("4","Enable Wake-on-LAN",["BIOS/UEFI: enable Wake on LAN / Power On by PCI-E","BIOS/UEFI: disable ErP / EuP / Deep Sleep","Windows: NIC → Power Mgmt → Allow this device to wake; Advanced → Wake on Magic Packet","Linux: sudo ethtool -s <iface> wol g (persist it)"]),
("5","Pair the phone app",["Install the Pyra Claw APK (allow install from this source)","Connect: USB-C OTG cable to hub, or same Wi-Fi","Add hub: pyra-hub.local or IP; confirm pairing on hub OLED","App registers as droidcommand-AI node; nodes appear on Dashboard"]),
("6","First checks",["PC in sleep/off → tap Wake PC → watch Logs","Only if WOL fails: Power Pulse → hold to approve","Confirm a single ~300 ms pulse (LED/multimeter) before trusting it","Record what actually worked — nothing here is pre-tested"])]
s=f'<svg width="{W}" height="{H}">'
s+='<text x="60" y="80" font-size="48" font-weight="800" fill="#F2EEE9">Pyra Claw · <tspan fill="#FF6A1A">Setup, Step by Step</tspan></text><text x="60" y="122" font-size="24" fill="#9A938C">Generic guide · service names, paths and pins are examples — match them to your own code.</text>'
for i,(n,t,ls) in enumerate(steps):
    x=60+(i%3)*610; y=170+(i//3)*500
    s+=f'<rect x="{x}" y="{y}" width="580" height="460" rx="26" fill="#1F1F24" stroke="#2E2E35" stroke-width="2"/>'
    s+=f'<circle cx="{x+70}" cy="{y+72}" r="40" fill="#FF6A1A"/><text x="{x+70}" y="{y+86}" text-anchor="middle" font-size="40" font-weight="900" fill="#141417">{n}</text>'
    s+=f'<text x="{x+130}" y="{y+84}" font-size="32" font-weight="800" fill="#F2EEE9">{t}</text>'
    yy=y+150
    for l in ls:
        # wrap at ~44 chars
        words=l.split(); lines=[]; cur=""
        for w_ in words:
            if len(cur)+len(w_)+1>44: lines.append(cur); cur=w_
            else: cur=(cur+" "+w_).strip()
        lines.append(cur)
        s+=f'<circle cx="{x+44}" cy="{yy-8}" r="6" fill="#FF8A3D"/>'
        for j,lt in enumerate(lines):
            s+=f'<text x="{x+64}" y="{yy+j*30}" font-size="22" fill="#CFC8C0">{lt.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")}</text>'
        yy+=len(lines)*30+26
s+='</svg>'
open(f"{OUT}/05c_setup_steps.html","w").write(BASE%(W,H,s))

#!/usr/bin/env python3
"""Draws docs/img/usb-power-wiring.png (fleet neon theme). Run: python3 tools/diagrams/usb_power_wiring.py
Concept diagram only; NOT tested on real hardware."""

from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyBboxPatch

BG, CY, MG, TX, DIM = "#0B0F1A", "#00E5FF", "#FF2BD6", "#E6F7FF", "#7A8AA6"
OUT = Path(__file__).resolve().parents[2] / "docs" / "img" / "usb-power-wiring.png"

fig, ax = plt.subplots(figsize=(13.6, 8.6), dpi=110)
fig.patch.set_facecolor(BG)
ax.set_facecolor(BG)
ax.set_xlim(0, 136)
ax.set_ylim(-10, 76)
ax.axis("off")


def box(x, y, w, h, title, body="", color=CY):
    ax.add_patch(
        FancyBboxPatch((x, y), w, h, boxstyle="round,pad=0.4,rounding_size=1.6",
                       fc="#111829", ec=color, lw=2.2)
    )
    ax.text(x + w / 2, y + h - 2.6, title, color=color, ha="center", va="top",
            fontsize=11.5, weight="bold")
    if body:
        ax.text(x + w / 2, y + h - 7.2, body, color=TX, ha="center", va="top", fontsize=9.2,
                linespacing=1.45)


def wire(pts, color=CY, label=None, lx=None, ly=None, lw=2.0):
    xs, ys = zip(*pts)
    ax.plot(xs, ys, color=color, lw=lw, solid_capstyle="round")
    if label:
        ax.text(lx, ly, label, color=color, fontsize=8.6, ha="center", va="bottom")


def dot(x, y, color=CY):
    ax.plot([x], [y], "o", color=color, ms=5)


ax.text(2, 73.5, "USB power channel wiring: ESP32 GPIO → driver → high-side P-MOSFET → USB 5 V",
        color=CY, fontsize=15, weight="bold", va="top")
ax.text(2, 68.6, "One copy per channel (usb_pins = \"27,32,33\" → ch 0,1,2). Concept diagram · "
        "NOT tested on real hardware · only VBUS is switched, GND/D+/D− pass straight through",
        color=DIM, fontsize=9.5, va="top")

box(2, 30, 20, 30, "ESP32 DevKitC", "GPIO27  (ch 0)\nGPIO32  (ch 1)\nGPIO33  (ch 2)\n\nGND\n\nHIGH = line ON\nfloat/LOW = OFF")
box(36, 30, 22, 30, "Driver (per ch)", "NPN 2N2222 / BC547\nor N-MOSFET 2N7000\n\nbase 1 kΩ from GPIO\nbase 10 kΩ to GND\n(holds OFF in reset)", MG)
box(72, 30, 24, 30, "High-side switch", "P-MOSFET AO3401\n(logic-level, ≥ 2 A)\n\nS → +5 V in\nD → USB VBUS out\nG: 10 kΩ pull-up to S\nG ← driver collector", CY)
box(110, 30, 24, 30, "USB port / phone", "VBUS ← MOSFET drain\nGND ← common GND\nD+/D− → Pi (optional,\nfor adb)\n\nPhone keeps running\non battery when cut", MG)
box(72, 2, 24, 15, "5 V supply", "≥ 2.5 A per phone\n+ headroom; NOT the\nESP32 3V3 pin", CY)

wire([(22.8, 52), (35.2, 52)], CY, "GPIO27", 29, 52.6)
wire([(58.8, 45), (71.2, 45)], MG, "pulls G low", 65, 45.6)
wire([(96.8, 45), (109.2, 45)], CY, "switched 5 V", 103, 45.6)
wire([(90, 17.8), (90, 29.2)], CY, "+5 V → S", 94.5, 20)
wire([(22.8, 36), (28, 36), (28, 24), (122, 24), (122, 29.2)], DIM)
ax.text(44, 23.2, "common GND (ESP32 · driver · supply · USB)", color=DIM, fontsize=8.6, ha="center", va="top")
wire([(47, 29.2), (47, 24)], DIM)
wire([(71.2, 10), (66, 10), (66, 24)], DIM, "GND", 68.5, 10.6)
for p in [(28, 24), (47, 24), (66, 24)]:
    dot(*p, DIM)

ax.text(68, -9,
        "Relay option: a 5 V relay module per channel (COM = +5 V in, NO = VBUS out) driven by the same NPN stage; "
        "add a flyback diode if the module has none.\n"
        "Never drive a relay coil or the MOSFET gate straight from the GPIO. Fuse or polyfuse each line. "
        "[Likely] values; check your MOSFET's datasheet.",
        color=TX, fontsize=8.6, ha="center", va="bottom", linespacing=1.4)

OUT.parent.mkdir(parents=True, exist_ok=True)
fig.savefig(OUT, facecolor=BG, bbox_inches="tight")
print(OUT)

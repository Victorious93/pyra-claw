# Pyra Claw — Case Print Specs (concept)

Starting-point settings for the three concept enclosures. **No case has been modelled or printed yet**; these are planning numbers, not test results.
Tags: **[Likely]** = based on published board sizes or common FDM practice. **[Guessing]** = design estimate; check it against CAD and a test print.

Reference board sizes:
- Raspberry Pi 5 PCB: **85 × 56 mm**; M2.5 mounting holes at **58 × 49 mm** spacing [Likely]. Ports stick out past the PCB edge by a few mm [Likely].
- With the official Active Cooler, the stack is about 20–25 mm tall above the bottom of the PCB [Guessing].
- 0.96" SSD1306 OLED module: board about 27 × 27 mm, visible area about 22 × 11 mm. Sizes differ by vendor [Guessing].
- ESP32 DevKitC-style board: about 55 × 28 mm. PC817 1–2 channel breakout: about 20 × 15 mm. Both vary by vendor [Guessing].

**Modelled case:** [hardware/case/](../hardware/case/README.md) — parametric OpenSCAD v1 (Pi 5 + ESP32 + PC817 + Coral, not yet test-printed).

---

## 1. Hexagonal Pi 5 hub enclosure (claw vent fins + OLED window)

| Setting | Suggestion | Tag |
|---|---|---|
| Material | **PETG** for the body: the Pi 5 runs warm and PLA softens around 55–60 °C. PLA is OK for the fins only. | [Likely] |
| Colours | Charcoal body, ember-orange fins. Print them as separate parts or use a filament swap. | [Likely] |
| Approx. outer size | Hexagon about **120 mm across flats** (~139 mm across corners), **~50–55 mm tall** | [Guessing] |
| Inner clearance | ≥ 3 mm around the 85 × 56 mm PCB, plus room for the port overhang and cable bends | [Guessing] |
| Wall / top thickness | 3 perimeters (~1.2 mm at 0.4 mm nozzle). Top/bottom 4–5 layers. | [Likely] |
| Layer height | **0.20 mm** (use 0.16 mm for smoother fin curves) | [Likely] |
| Infill | **15–20 % gyroid** | [Likely] |
| Supports | Print the shell top-down with the lid as a separate part. Port cut-outs need little or no support if you add bridged/teardrop tops. Print the fins flat as separate parts and glue or press-fit them in, so they need no supports. | [Guessing] |
| OLED window | Through-cut about 24 × 13 mm, with a recessed pocket for a 1 mm smoked acrylic or clear PETG insert | [Guessing] |
| Mounting | M2.5 heat-set inserts or 2.2 mm self-tap bosses at 58 × 49 mm | [Likely] |
| Venting | The fin gaps act as intake/exhaust. Leave a clear path to the Active Cooler fan. | [Guessing] |
| Fit tolerance | 0.2–0.3 mm per side on lid joints and press-fits | [Likely] |

## 2. ESP32 + PC817/relay pod (mounts inside the PC case)

| Setting | Suggestion | Tag |
|---|---|---|
| Material | **PETG** (warm interior of the PC, mild flex for snap lids). ASA/ABS if the pod sits near GPU exhaust. | [Likely] |
| Approx. outer size | **~78 × 52 × 30 mm** (inner ~72 × 46 × 24 mm) | [Guessing] |
| Layer height | **0.20 mm** | [Likely] |
| Infill | **20 %** grid or gyroid | [Likely] |
| Walls | 3 perimeters | [Likely] |
| Supports | None if printed base-down. Make the lid a separate snap-fit or screw-on part. | [Likely] |
| Features | USB slot for power and flashing. Grommet slot (~6 × 4 mm) for the 2-wire PWR_SW lead. Vent slots. | [Guessing] |
| Mounting | Tabs with M3 holes, double-sided foam tape, or Ø10 × 3 mm magnet pockets for the steel case wall | [Guessing] |
| Safety | Keep the pod clear of fans and GPU. Strain-relieve the header lead. The pod must not short against the motherboard. | [Likely] |

## 3. Phone dock (holds the phone next to the hub, with a cable channel)

| Setting | Suggestion | Tag |
|---|---|---|
| Material | **PLA** is fine (low heat, stiff). Use PETG if the dock sits in sunlight or near heat. | [Likely] |
| Approx. outer size | Base **~95 × 110 mm**, back rest **~110–130 mm** tall, cradle opening **~80–85 mm** wide (measure phone + case) | [Guessing] |
| Recline | ~65–70° from horizontal | [Guessing] |
| Cable channel | ~7 × 5 mm groove under the base, with a ~13 × 8 mm pocket for the USB-C plug body at the phone end. Measure your cable. | [Guessing] |
| Layer height | **0.20 mm** (0.28 mm works for draft prints) | [Likely] |
| Infill | **25–30 %** for weight and stiffness. Optional pocket for a steel or coin weight in the base. | [Guessing] |
| Supports | Print on its side or back so the cradle lip needs none. Otherwise use tree supports under the lip only. | [Guessing] |
| Grip | TPU pads or rubber feet under the base. Orange claw arms as separate PLA parts. | [Guessing] |

---

### General
- 0.4 mm nozzle, 3 perimeters, brim for PETG parts with sharp hex corners [Likely].
- Print one tolerance-test coupon (lid joint + M2.5 boss) before committing to the full hub print [Likely].
- None of these dimensions have been checked against a real Pi 5 / ESP32 / phone fit; confirm with calipers and CAD [Guessing].

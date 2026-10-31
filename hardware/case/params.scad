// Pyra Claw case v1 - ALL dimensions (mm). Edit here; every other file reads these.
// Source tags: [Certain]/[Likely]/[Guessing] - see README.md.

$fn = 48;

// ---- Print / fit ----
wall      = 2.4;   // side wall (>= 2 mm rule)
floor_t   = 2.4;   // base floor
lid_t     = 2.4;   // lid plate
fit       = 0.3;   // clearance per side for lid lip / slots
lip_h     = 3;     // lid locating lip depth
lip_t     = 2.0;   // lid lip thickness

// ---- Fasteners (M2.5) ----
use_inserts   = true;  // true: heat-set insert holes; false: self-tap holes
insert_d      = 3.6;   // hole for M2.5 x 4 mm heat-set insert (check your insert's spec) [Guessing]
insert_depth  = 5;
selftap_d     = 2.2;   // M2.5 self-tap pilot [Likely]
clear_d       = 2.9;   // M2.5 clearance [Likely]
head_d        = 5.2;   // M2.5 pan/socket head counterbore
head_h        = 1.4;
corner_r      = 4.5;   // corner screw lug radius

// ---- Raspberry Pi 5 (RP-008347-DS mechanical drawing) ----
pi_l = 85;  pi_w = 56;  pi_t = 1.6;     // board [Certain]/[Likely t]
pi_holes = [[3.5,3.5],[61.5,3.5],[3.5,52.5],[61.5,52.5]]; // 58 x 49, 3.5 from edge [Certain]
pi_hole_d = 2.7;                         // [Certain]
pi_standoff_h = 5;                       // under-board gap (microSD, solder) [Guessing]
pi_standoff_d = 6;
cooler_h = 13.7;                         // Active Cooler envelope above PCB [Likely]
cooler_clear = 6;                        // air gap cooler->lid [Guessing]
fan_c = [42, 34];                        // fan centre in board coords [Guessing - verify]
grille_r = 20;
// Port centres (board coords) from drawing [Certain]; housing sizes [Guessing]
usbc_x = 11.2; hdmi_x = [25.8, 39.2];
end_ports_y = [10.2, 29.1, 47.0];        // Ethernet, USB, USB (right edge)

// ---- ESP32 DevKit (38-pin) - vendor dependent ----
esp_l = 55;  esp_w = 28;  esp_t = 1.6;   // [Likely for DevKitC-38; MEASURE yours]
esp_lift = 10;                           // PCB bottom above bracket floor (header pins) [Guessing]
esp_usb_w = 12; esp_usb_h = 8;           // port cut-out (fits plug overmould) [Guessing]

// ---- Perfboard (PC817 + resistor) ----
perf_l = 30; perf_w = 20; perf_hole_inset = 2.5; perf_standoff_h = 5;

// ---- Coral USB Accelerator (datasheet: 65 x 30 x 8) ----
coral_l = 65; coral_w = 30; coral_t = 8;  // [Certain]
coral_rail_h = 10;

// ---- Misc ----
lead_slot = [6, 4];      // 2-wire PWR_SW lead exit (w x h)
led_hole  = true;  led_d = 5.2;   // 5 mm LED
label     = "PYRA CLAW"; label_size = 7; label_depth = 0.8;

// ---- Derived layout (interior coords, origin = inner front-left floor corner) ----
gap = 3;
pi_x = 5.5;  pi_y = 2.5;
strip_y  = pi_y + pi_w + gap;                 // ESP32 + perfboard strip
coral_y  = strip_y + esp_w + 2*fit + 4 + gap; // Coral slot
in_x = pi_x + pi_l + 1.5;                     // 92
in_y = coral_y + coral_t + 2*fit + 2*2 + lip_t + fit + 1;
pcb_z = pi_standoff_h;                        // board bottom above floor top
in_z = max(pcb_z + pi_t + cooler_h + cooler_clear, coral_w + 2);
out = [in_x + 2*wall, in_y + 2*wall, in_z + floor_t];

// Pyra Claw case v2 - ALL dimensions (mm). Edit here; every other file reads these.
// Source tags: [Certain]/[Likely]/[Guessing] - see README.md.

$fn = 48;

// ---- Print / fit ----
wall      = 2.4;   // side wall (>= 2 mm rule)
floor_t   = 2.4;   // base floor
lid_t     = 3.0;   // lid plate (cap sits on top of the walls)
fit       = 0.3;   // clearance per side for slots / rails
// ---- Styling (v2) ----
case_r    = 6;     // vertical corner radius
top_fillet = 1.6;  // lid top edge round-over
bot_chamfer = 1.0; // base bottom edge chamfer
port_ch   = 1.0;   // 45 deg chamfer on port openings
panel_d   = 0.8;   // inset lid panel depth (= two-colour swap height, see README)
claw_d    = 0.8;   // claw groove depth below panel floor
font      = "Orbitron:style=ExtraBold";   // falls back to default font if not installed
foot_d    = 10.5;  foot_depth = 1.2;      // recessed pockets for 10 mm stick-on bumpers

// ---- Fasteners (M2.5) ----
use_inserts   = true;  // true: heat-set insert holes; false: self-tap holes
insert_d      = 3.6;   // hole for M2.5 x 4 mm heat-set insert (check your insert's spec) [Guessing]
insert_depth  = 5;
selftap_d     = 2.2;   // M2.5 self-tap pilot [Likely]
clear_d       = 2.9;   // M2.5 clearance [Likely]
head_d        = 5.2;   // M2.5 pan/socket head counterbore
head_h        = 1.4;
boss_r        = 3.4;   // internal corner screw boss (replaces v1 external lugs)
boss_in       = 2.6;   // boss centre inset from the interior corner

// ---- Raspberry Pi 5 (RP-008347-DS mechanical drawing) ----
pi_l = 85;  pi_w = 56;  pi_t = 1.6;     // board [Certain]/[Likely t]
pi_holes = [[3.5,3.5],[61.5,3.5],[3.5,52.5],[61.5,52.5]]; // 58 x 49, 3.5 from edge [Certain]
pi_hole_d = 2.7;                         // [Certain]
pi_standoff_h = 5;                       // under-board gap (microSD, solder) [Guessing]
pi_standoff_d = 6;
cooler_h = 13.7;                         // Active Cooler envelope above PCB [Likely]
cooler_clear = 6;                        // air gap cooler->lid [Guessing]
fan_c = [42, 34];                        // Active Cooler fan centre in board coords [Guessing - verify]
grille_r = 19;
usbc_x = 11.2; hdmi_x = [25.8, 39.2];    // [Certain]
end_ports_y = [10.2, 29.1, 47.0];        // Ethernet, USB, USB (right edge) [Certain]
port_grow = 2;                           // v2: openings 2 mm bigger (walls sit 3 mm further out) [Guessing]

// ---- ESP32 DevKit (38-pin) - vendor dependent ----
esp_l = 55;  esp_w = 28;  esp_t = 1.6;   // [Likely for DevKitC-38; MEASURE yours]
esp_lift = 10;                           // [Guessing]
esp_usb_w = 12; esp_usb_h = 8;           // [Guessing]

// ---- Perfboard (PC817 + resistor) ----
perf_l = 30; perf_w = 20; perf_hole_inset = 2.5; perf_standoff_h = 5;

// ---- Coral USB Accelerator (datasheet: 65 x 30 x 8) ----
coral_l = 65; coral_w = 30; coral_t = 8;  // [Certain]
coral_rail_h = 10;

// ---- Case fan (lid mounted, exhaust) ----
// Standard hole spacing: 40 mm fan = 32 mm, 30 mm fan = 24 mm, 80 mm = 71.5 mm (see README sources) [Certain]
case_fan = 40;                            // 40 (default) or 30
function fan_hole_pitch(s) = s == 30 ? 24 : s == 40 ? 32 : s == 60 ? 50 : s == 80 ? 71.5 : s*0.8;
case_fan_t = 10;                          // 40x40x10 / 30x30x10 5 V
fan_screw_d = 3.4;                        // M3 / fan self-tap clearance [Likely]
case_fan_c = [33, 80];                    // fan centre, interior coords (over the ESP32 strip) [Guessing]

// ---- Misc ----
lead_slot = [6, 4];
led_hole  = true;  led_d = 5.2;
// dovetail rails on the back wall for the phone rack
dt_w0 = 6; dt_w1 = 9; dt_h = 3;          // rail root width, tip width, depth
dt_x  = [22, 80];                        // rail centres along the back wall (outer x)

// ---- Derived layout (interior coords, origin = inner front-left floor corner) ----
s = 3;                                    // v2 growth per side to fit internal screw bosses
gap = 3;
pi_x = 5.5 + s;  pi_y = 2.5 + s;
strip_y  = pi_y + pi_w + gap;
coral_y  = strip_y + esp_w + 2*fit + 4 + gap;
in_x = pi_x + pi_l + 1.5 + s;             // 98
in_y = coral_y + coral_t + 2*fit + 2*2 + 2 + fit + 1 + s;
pcb_z = pi_standoff_h;
in_z = max(pcb_z + pi_t + cooler_h + cooler_clear, coral_w + 2);
out = [in_x + 2*wall, in_y + 2*wall, in_z + floor_t];   // base (lid adds lid_t)

// ================= PHONE RACK (separate dockable module) =================
// Phone sizes, official Google spec pages (see README):  [L, W, T]
nexus5  = [137.84, 69.17, 8.59];   // [Certain]
nexus5x = [147.0, 72.6, 7.9];      // [Certain]
nexus6p = [159.3, 77.8, 7.3];      // [Certain]
phones  = [nexus5, nexus5x, nexus6p];
phone_override = undef;            // e.g. [150, 75, 9] to size for another phone
function maxi(v, i) = max([for (p = v) p[i]]);
ph = phone_override == undef ? [maxi(phones,0), maxi(phones,1), maxi(phones,2)] : phone_override;
phone_len_margin = 1.5;            // per end, bare phone (no case) [Guessing]
phone_t_margin   = 0.8;            // per side [Guessing]
slot_l = ph[0] + 2*phone_len_margin;
slot_t = ph[2] + 2*phone_t_margin;
air_gap = 12;                      // between phones (>= 10) 
rack_wall = 6;                     // side walls (dovetail slots cut into the case-side one, >= 2 mm left)
rack_floor = 6;  groove_d = 4;
rack_h = 70;                       // side-wall height; phones (77.8 tall) stand proud
edge_gap = 6;                      // phone to side wall
rack_in_w = 3*slot_t + 2*air_gap + 2*edge_gap;
rack_w = rack_in_w + 2*rack_wall;
comb_t = 5;                        // end frames
plug_clear = 25;                   // room for the USB plug + bend at the far end [Guessing]
tray_l = 14;                       // cable tray past the far end
rack_l = comb_t + slot_l + plug_clear + comb_t + tray_l;
frame_b = 7;                       // fan-end frame border
usb_notch = [13, 9];
// fan for the rack: "80" (one 80x80x25) or "2x40" (two 40x40 stacked)
rack_fan = "80";
rack_fan_t = rack_fan == "80" ? 25 : 10;
shroud_l = 32;
rack_dock_x = -85;                 // rack x origin relative to case x origin when docked

// Pyra Claw case v2 - geometry modules. Dimensions live in params.scad.
include <params.scad>

// bracket / board geometry (interior coords)
br_l = esp_l + 2*fit + 4;  br_w = esp_w + 2*fit + 4;
br_x = 1 + s;  br_y = strip_y;
br_pads = [[8, br_w/2], [br_l-8, br_w/2]];
perf_x = in_x - perf_l - 2 - s;  perf_y = strip_y + 2;
coral_x = in_x - coral_l - 8 - s;
coral_rail_y = coral_y;
coral_mid_y = coral_y + 2 + fit + coral_t/2;
H  = out[2] + lid_t;                       // assembled height (without feet)

// screw boss centres (outer coords)
bosses = [[wall + boss_in, wall + boss_in], [out[0] - wall - boss_in, wall + boss_in],
          [wall + boss_in, out[1] - wall - boss_in], [out[0] - wall - boss_in, out[1] - wall - boss_in]];

// ---------- helpers ----------
module rr2d(w, h, r) { r2 = min(r, w/2 - 0.01, h/2 - 0.01); offset(r2) square([w - 2*r2, h - 2*r2], center = true); }
module corner_solid(r, h, cb, ft) {
  if (cb > 0) cylinder(r1 = r - cb, r2 = r, h = cb + 0.001);
  translate([0, 0, cb]) cylinder(r = r, h = h - cb - ft);
  if (ft > 0) translate([0, 0, h - ft]) rotate_extrude() intersection() {
    translate([r - ft, 0]) circle(ft); translate([0, 0]) square([r, ft]); }
}
// rounded box: vertical radius r, bottom chamfer cb, top fillet ft
module rbox(sz, r, cb = 0, ft = 0)
  hull() for (x = [r, sz[0] - r], y = [r, sz[1] - r]) translate([x, y, 0]) corner_solid(r, sz[2], cb, ft);
// tapered slash (claw scratch): from p0 width w0 to p1 width w1
module slash2d(len, w0, w1, ang) rotate(ang) hull() { translate([0, -len/2]) circle(d = w0); translate([0, len/2]) circle(d = w1); }
module claws2d(len, w, pitch, ang)
  for (i = [-1:1]) translate([i*pitch, -abs(i)*len*0.08]) slash2d(len*(1 - abs(i)*0.14), w*0.45, w, ang);
// chamfered through-cut. local XY = face plane, z=0 outer face, +z inward
module chcut(t, ch = port_ch) {
  translate([0, 0, -1]) linear_extrude(t + 2) children();
  hull() { translate([0, 0, -1]) linear_extrude(1.001) offset(delta = ch) children();
           translate([0, 0, ch]) linear_extrude(0.01) children(); }
}
// wall placement: front(y=0) back(y=out) left(x=0) right(x=out)
module on_front(x, z) translate([x, 0, z]) rotate([-90, 0, 0]) mirror([0,1,0]) children();
module on_back(x, z)  translate([x, out[1], z]) rotate([90, 0, 0]) children();
module on_left(y, z)  translate([0, y, z]) rotate([0, 90, 0]) rotate([0,0,90]) mirror([1,0,0]) children();
module on_right(y, z) translate([out[0], y, z]) rotate([0, -90, 0]) rotate([0,0,90]) children();
module fix_hole(depth) { cylinder(d = use_inserts ? insert_d : selftap_d, h = depth + 0.01); }
module hexgrid2d(r, cell = 5.2, web = 1.3) {
  dx = cell + web; dy = dx * sqrt(3)/2;
  intersection() { circle(r);
    for (j = [-ceil(r/dy):ceil(r/dy)], i = [-ceil(r/dx)-1:ceil(r/dx)+1])
      translate([i*dx + (j % 2 == 0 ? 0 : dx/2), j*dy]) rotate(30) circle(d = cell/cos(30), $fn = 6); }
}

// ---------------- BASE ----------------
module base() {
  pz = floor_t + pcb_z;              // PCB bottom (outer z)
  difference() {
    union() {
      difference() {
        rbox(out, case_r, bot_chamfer, 0);
        translate([wall, wall, floor_t]) cube([in_x, in_y, in_z + 1]);
      }
      // internal corner screw bosses, merged into the corners
      for (b = bosses) hull() {
        translate([b[0], b[1], 0]) cylinder(r = boss_r, h = out[2]);
        translate([b[0] < out[0]/2 ? wall - 0.5 : out[0] - wall - 0.5, b[1] < out[1]/2 ? wall - 0.5 : out[1] - wall - 0.5, 0]) cube([1, 1, out[2]]);
      }
      // Pi standoffs (with small base fillet cone)
      for (h = pi_holes) translate([wall + pi_x + h[0], wall + pi_y + h[1], 0]) {
        cylinder(d = pi_standoff_d, h = pz);
        translate([0, 0, floor_t]) cylinder(d1 = pi_standoff_d + 3, d2 = pi_standoff_d, h = 1.5);
      }
      for (i = [0,1], j = [0,1])
        translate([wall + perf_x + perf_hole_inset + i*(perf_l - 2*perf_hole_inset),
                   wall + perf_y + perf_hole_inset + j*(perf_w - 2*perf_hole_inset), 0])
          cylinder(d = 5, h = floor_t + perf_standoff_h);
      for (k = [0,1]) translate([wall + coral_x, wall + coral_rail_y + k*(2 + coral_t + 2*fit), 0])
        cube([coral_l + 2, 2, floor_t + coral_rail_h]);
      translate([wall + coral_x - 2, wall + coral_rail_y, 0]) cube([2, coral_t + 2*fit + 4, floor_t + coral_rail_h]);
      // dovetail rails on the back wall (phone-rack dock); chamfered bottom so it prints clean
      for (x = dt_x) translate([x, out[1] - 0.5, 0]) hull() {
        translate([0, 0, 1.5]) linear_extrude(out[2] - 1.5) polygon([[-dt_w0/2, 0], [dt_w0/2, 0], [dt_w1/2, dt_h + 0.5], [-dt_w1/2, dt_h + 0.5]]);
        linear_extrude(0.01) polygon([[-dt_w0/2, 0], [dt_w0/2, 0], [dt_w0/2, 0.5], [-dt_w0/2, 0.5]]);
      }
    }
    // standoff + boss holes
    for (h = pi_holes) translate([wall + pi_x + h[0], wall + pi_y + h[1], pz - insert_depth]) fix_hole(insert_depth);
    for (i = [0,1], j = [0,1])
      translate([wall + perf_x + perf_hole_inset + i*(perf_l - 2*perf_hole_inset),
                 wall + perf_y + perf_hole_inset + j*(perf_w - 2*perf_hole_inset), floor_t + 0.6])
        cylinder(d = selftap_d, h = 10);
    for (b = bosses) translate([b[0], b[1], out[2] - insert_depth]) fix_hole(insert_depth);
    for (p = br_pads) translate([wall + br_x + p[0], wall + br_y + p[1], -1]) cylinder(d = clear_d, h = floor_t + 2);

    // ---- port openings, chamfered (sizes = v1 + port_grow) ----
    g = port_grow;
    // RIGHT wall: Ethernet + 2x USB3 + 2x USB2
    on_right(wall + pi_y + pi_w/2, pz - 0.5 - g/2 + (pi_t + 17 + g)/2) chcut(wall) rr2d(pi_w - 2 + g, pi_t + 17 + g, 1.5);
    // FRONT wall: USB-C + 2x micro-HDMI
    on_front(wall + pi_x + (usbc_x + hdmi_x[1])/2, pz + pi_t - 1 - g/2 + (9 + g)/2) chcut(wall) rr2d(hdmi_x[1] - usbc_x + 12 + g, 9 + g, 1.5);
    // LEFT wall: microSD
    on_left(wall + pi_y + pi_w/2, pz - 1) chcut(wall) rr2d(16, 5, 1);
    // LEFT wall: ESP32 USB
    on_left(wall + br_y + br_w/2, floor_t + 2 + esp_lift + esp_t - 1.5 + esp_usb_h/2) chcut(wall) rr2d(esp_usb_w, esp_usb_h, 1.5);
    // RIGHT wall: PWR_SW lead exit
    on_right(wall + perf_y + perf_w/2, floor_t + perf_standoff_h + 4 + lead_slot[1]/2) chcut(wall, 0.6) rr2d(lead_slot[0], lead_slot[1], 1);
    // RIGHT wall: Coral USB-C pass-through
    on_right(wall + coral_mid_y, floor_t + coral_w/2) chcut(wall) rr2d(14, 10, 2);

    // ---- claw-slash side vents ----
    // intake: right wall between the Pi ports and the lead exit, low
    on_right(wall + pi_y + pi_w + 0.5, floor_t + 15) chcut(wall, 0.6) claws2d(16, 3, 5.5, -25);
    // intake: front wall, right of the HDMI slot
    on_front(wall + pi_x + 72, floor_t + 18) chcut(wall, 0.6) claws2d(18, 3, 5.5, 25);
    // exhaust helpers: left wall towards the back (diagonal flow front-right -> back-left)
    on_left(wall + coral_y + 4, floor_t + 18) chcut(wall, 0.6) claws2d(18, 3, 5.5, 25);
    // floor intake under the Pi: angled slashes
    for (i = [0:6]) translate([wall + pi_x + 16 + i*7, wall + pi_y + 28, -1]) linear_extrude(floor_t + 2) slash2d(30, 3, 3, -18);
    // recessed rubber-foot pockets under the corner bosses
    for (b = bosses) translate([b[0] + (b[0] < out[0]/2 ? 3 : -3), b[1] + (b[1] < out[1]/2 ? 3 : -3), -1]) cylinder(d = foot_d, h = foot_depth + 1);
  }
}

// ---------------- LID ----------------
// Printed outer face UP (as modelled), no supports: everything on the underside is a recess.
pan = [wall + boss_in + head_d/2 + 2.2, wall + 2.2, out[0] - wall - boss_in - head_d/2 - 2.2, wall + pi_y + fan_c[1] - grille_r - 2.5]; // front band x0 y0 x1 y1
pan_c = [(pan[0] + pan[2])/2, (pan[1] + pan[3])/2];
pan_sz = [pan[2] - pan[0], pan[3] - pan[1]];
cf = [wall + case_fan_c[0], wall + case_fan_c[1]];     // case fan centre (outer coords)
led_p = [wall + perf_x + perf_l/2, wall + perf_y + perf_w/2];
module wordmark2d() translate([pan_c[0] + 8.5, pan_c[1]]) resize([pan_sz[0] - 25, 0], auto = true)
  text("PYRA CLAW", size = 7, halign = "center", valign = "center", font = font, spacing = 1.08);
module lidclaws2d() translate([pan[0] + 9, pan_c[1] + 0.5]) claws2d(pan_sz[1] - 3, 3.2, 5, -24);
// big through claw-slash vents over the Coral (passive exhaust)
module coral_claws2d() translate([wall + coral_x + 48, wall + coral_mid_y - 9]) claws2d(26, 4.6, 8.5, -24);
module lid_plate() rbox([out[0], out[1], lid_t], case_r, 0, top_fillet);
module lid() {
  difference() {
    lid_plate();
    // inset panel
    translate([pan_c[0], pan_c[1], lid_t - panel_d]) linear_extrude(panel_d + 1) rr2d(pan_sz[0], pan_sz[1], 3);
    // claw grooves below the panel floor
    translate([0, 0, lid_t - panel_d - claw_d]) linear_extrude(claw_d + 0.01) lidclaws2d();
    // hex grille over the Pi Active Cooler fan (chamfered top edge)
    translate([wall + pi_x + fan_c[0], wall + pi_y + fan_c[1], 0]) {
      translate([0, 0, -1]) linear_extrude(lid_t + 2) hexgrid2d(grille_r);
      translate([0, 0, lid_t - 0.6]) cylinder(r1 = grille_r, r2 = grille_r + 0.6, h = 0.61);
    }
    // case fan: claw-slash grille (2.6 mm slots - finger safe) + screw holes
    translate([cf[0], cf[1], 0]) {
      translate([0, 0, -1]) linear_extrude(lid_t + 2) intersection() {
        circle(d = case_fan - 3);
        for (k = [-8:8]) translate([k*4.6, 0]) rotate(-22) square([2.6, case_fan*1.6], center = true);
      }
      for (i = [-1, 1], j = [-1, 1]) translate([i, j, 0]*fan_hole_pitch(case_fan)/2 + [0, 0, -1]) cylinder(d = fan_screw_d, h = lid_t + 2);
      // fan frame seat (0.6 mm locating recess on the underside)
      translate([0, 0, -0.01]) linear_extrude(0.6) square(case_fan + 0.6, center = true);
    }
    // fan wire channel (underside groove) from the fan to above the Pi GPIO 5V/GND pins
    translate([0, 0, -0.01]) linear_extrude(1.2) hull() {
      translate([cf[0] - case_fan/2 + 3, cf[1] - case_fan/2]) circle(d = 3);
      translate([wall + pi_x + 12, wall + pi_y + 50]) circle(d = 3);
    }
    // corner screws, counterbored from the top
    for (b = bosses) translate([b[0], b[1], -1]) {
      cylinder(d = clear_d, h = lid_t + 2);
      translate([0, 0, 1 + lid_t - head_h]) cylinder(d = head_d, h = head_h + 1);
    }
    translate([0, 0, -1]) linear_extrude(lid_t + 2) coral_claws2d();
    translate([0, 0, lid_t - 0.6]) linear_extrude(0.61) offset(delta = 0.6) coral_claws2d();
    if (led_hole) translate([led_p[0], led_p[1], -1]) cylinder(d = led_d, h = lid_t + 2);
  }
  // raised wordmark inside the panel (flush with the lid top)
  translate([0, 0, lid_t - panel_d - 0.01]) lid_inlay_wordmark();
}
// --- two-colour helpers: the parts that change colour ---
module lid_inlay_wordmark() translate([0, 0, lid_t - panel_d]) linear_extrude(panel_d) wordmark2d();
module lid_inlay_claws() translate([0, 0, lid_t - panel_d - claw_d]) linear_extrude(claw_d) lidclaws2d();

// ---------------- ESP32 BRACKET (unchanged from v1) ----------------
module esp32_bracket() {
  ph2 = esp_lift + esp_t + 1.5;
  difference() {
    union() {
      cube([br_l, br_w, 2]);
      for (i = [0,1], j = [0,1]) translate([i*(br_l - 6), j*(br_w - 6), 0]) cube([6, 6, ph2]);
      for (p = br_pads) translate([p[0], p[1], 0]) cylinder(d = 7, h = 5);
    }
    translate([2, 2, 2 + esp_lift]) cube([esp_l + 2*fit, esp_w + 2*fit, 10]);
    for (p = br_pads) translate([p[0], p[1], 0.6]) cylinder(d = selftap_d, h = 10);
  }
}

// ---------------- OPTIONAL CASE-FAN GUARD (sits on the lid, 3 mm standoffs) ----------------
module fan_guard() {
  sz = case_fan + 4; p = fan_hole_pitch(case_fan);
  difference() {
    union() {
      linear_extrude(1.6) rr2d(sz, sz, 4);
      for (i = [-1, 1], j = [-1, 1]) translate([i*p/2, j*p/2, 0]) cylinder(d = 7, h = 1.6 + 3);
    }
    translate([0, 0, -1]) linear_extrude(4) intersection() {
      circle(d = case_fan - 3);
      for (k = [-8:8]) translate([k*4.6, 0]) rotate(22) square([2.6, case_fan*1.6], center = true);
    }
    for (i = [-1, 1], j = [-1, 1]) translate([i*p/2, j*p/2, -1]) cylinder(d = fan_screw_d, h = 10);
  }
}

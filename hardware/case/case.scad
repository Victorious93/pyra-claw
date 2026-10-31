// Pyra Claw case v1 - geometry modules. Dimensions live in params.scad.
include <params.scad>

// bracket geometry (interior coords)
br_l = esp_l + 2*fit + 4;  br_w = esp_w + 2*fit + 4;
br_x = 1;  br_y = strip_y;
br_pads = [[8, br_w/2], [br_l-8, br_w/2]];
perf_x = in_x - perf_l - 2;  perf_y = strip_y + 2;
coral_x = in_x - coral_l - 8;
coral_rail_y = coral_y;                       // first rail
coral_mid_y = coral_y + 2 + fit + coral_t/2;

function W(p) = [p[0] + wall, p[1] + wall];   // interior -> outer coords
corners = [[0,0],[out[0],0],[0,out[1]],[out[0],out[1]]];

module outline(h) {
  union() {
    cube([out[0], out[1], h]);
    for (c = corners) translate(c) cylinder(r = corner_r, h = h);
  }
}
module fix_hole(depth) { cylinder(d = use_inserts ? insert_d : selftap_d, h = depth + 0.01); }

// ---------------- BASE ----------------
module base() {
  pz = floor_t + pcb_z;              // PCB bottom (outer z)
  difference() {
    union() {
      difference() {
        outline(out[2]);
        translate([wall, wall, floor_t]) cube([in_x, in_y, in_z + 1]);
      }
      // Pi standoffs
      for (h = pi_holes) translate([W([pi_x + h[0], pi_y + h[1]])[0], W([pi_x + h[0], pi_y + h[1]])[1], 0])
        cylinder(d = pi_standoff_d, h = pz);
      // perfboard standoffs
      for (i = [0,1], j = [0,1])
        translate([wall + perf_x + perf_hole_inset + i*(perf_l - 2*perf_hole_inset),
                   wall + perf_y + perf_hole_inset + j*(perf_w - 2*perf_hole_inset), 0])
          cylinder(d = 5, h = floor_t + perf_standoff_h);
      // Coral rails (device stands on its long edge)
      for (k = [0,1]) translate([wall + coral_x, wall + coral_rail_y + k*(2 + coral_t + 2*fit), 0])
        cube([coral_l + 2, 2, floor_t + coral_rail_h]);
      // Coral end stop (left)
      translate([wall + coral_x - 2, wall + coral_rail_y, 0]) cube([2, coral_t + 2*fit + 4, floor_t + coral_rail_h]);
    }
    // standoff holes
    for (h = pi_holes) translate([wall + pi_x + h[0], wall + pi_y + h[1], pz - insert_depth]) fix_hole(insert_depth);
    for (i = [0,1], j = [0,1])
      translate([wall + perf_x + perf_hole_inset + i*(perf_l - 2*perf_hole_inset),
                 wall + perf_y + perf_hole_inset + j*(perf_w - 2*perf_hole_inset), floor_t + 0.6])
        cylinder(d = selftap_d, h = 10);
    // corner lug holes for lid screws
    for (c = corners) translate([c[0], c[1], out[2] - insert_depth]) fix_hole(insert_depth);
    // ESP32 bracket screw holes (screws from below)
    for (p = br_pads) translate([wall + br_x + p[0], wall + br_y + p[1], -1]) cylinder(d = clear_d, h = floor_t + 2);

    // --- RIGHT wall: Ethernet + 2x USB3 + 2x USB2 (one opening) ---
    translate([out[0] - wall - 1, wall + pi_y + 1, pz - 0.5]) cube([wall + 2, pi_w - 2, pi_t + 17]);
    // --- FRONT wall: USB-C power + 2x micro-HDMI ---
    translate([wall + pi_x + usbc_x - 6, -1, pz + pi_t - 1]) cube([hdmi_x[1] - usbc_x + 12, wall + 2, 9]);
    // --- LEFT wall: microSD (under board, centre ~y 28) ---
    translate([-1, wall + pi_y + pi_w/2 - 8, pz - 3.5]) cube([wall + 2, 16, 5]);
    // --- LEFT wall: ESP32 USB ---
    translate([-1, wall + br_y + br_w/2 - esp_usb_w/2, floor_t + 2 + esp_lift + esp_t - 1.5]) cube([wall + 2, esp_usb_w, esp_usb_h]);
    // --- RIGHT wall: PC front-panel lead exit ---
    translate([out[0] - wall - 1, wall + perf_y + perf_w/2 - lead_slot[0]/2, floor_t + perf_standoff_h + 4]) cube([wall + 2, lead_slot[0], lead_slot[1]]);
    // --- RIGHT wall: Coral USB-C cable pass-through ---
    translate([out[0] - wall - 1, wall + coral_mid_y - 7, floor_t + coral_w/2 - 5]) cube([wall + 2, 14, 10]);
    // floor intake vents under the Pi
    for (i = [0:5]) translate([wall + pi_x + 14 + i*6, wall + pi_y + 12, -1]) cube([3, 32, floor_t + 2]);
  }
}

// ---------------- LID (modelled print-orientation: outer face on z=0, lip up) ----------------
module lid() {
  ix = in_x - 2*fit; iy = in_y - 2*fit;
  difference() {
    union() {
      outline(lid_t);
      translate([wall + fit, wall + fit, 0]) difference() {
        cube([ix, iy, lid_t + lip_h]);
        translate([lip_t, lip_t, -1]) cube([ix - 2*lip_t, iy - 2*lip_t, lid_t + lip_h + 2]);
      }
    }
    // corner screws + counterbore on outer face
    for (c = corners) translate([c[0], c[1], -1]) {
      cylinder(d = clear_d, h = lid_t + 2);
      cylinder(d = head_d, h = head_h + 1);
    }
    // lid is mirrored in Y when flipped over: y_lid = out[1] - y_base
    // fan grille: slots inside circle
    fc = [wall + pi_x + fan_c[0], out[1] - (wall + pi_y + fan_c[1])];
    translate([fc[0], fc[1], -1]) intersection() {
      cylinder(r = grille_r, h = lid_t + 2);
      for (k = [-5:5]) translate([k*4 - 1, -grille_r, 0]) cube([2, 2*grille_r, lid_t + 2]);
    }
    // status LED (above perfboard)
    if (led_hole) translate([wall + perf_x + perf_l/2, out[1] - (wall + perf_y + perf_w/2), -1]) cylinder(d = led_d, h = lid_t + 2);
    // debossed name on outer face (mirrored so it reads right side up after flipping)
    translate([wall + br_x + br_l/2 + 3, out[1] - (wall + strip_y + br_w/2), -0.01])
      mirror([0,1,0]) linear_extrude(label_depth + 0.01)
        text(label, size = label_size, halign = "center", valign = "center", font = "Liberation Sans:style=Bold");
  }
}

// ---------------- ESP32 BRACKET ----------------
module esp32_bracket() {
  ph = esp_lift + esp_t + 1.5;
  difference() {
    union() {
      cube([br_l, br_w, 2]);
      for (i = [0,1], j = [0,1]) translate([i*(br_l - 6), j*(br_w - 6), 0]) cube([6, 6, ph]);
      for (p = br_pads) translate([p[0], p[1], 0]) cylinder(d = 7, h = 5);
    }
    translate([2 - fit + fit, 2 - fit + fit, 2 + esp_lift]) cube([esp_l + 2*fit, esp_w + 2*fit, 10]);
    for (p = br_pads) translate([p[0], p[1], 0.6]) cylinder(d = selftap_d, h = 10);
  }
}

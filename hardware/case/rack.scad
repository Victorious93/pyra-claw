// Pyra Claw phone-rack add-on (v2). Holds 3 bare phones on their long edge,
// fan-ducted, docks onto the case's back-wall dovetail rails. Dimensions: params.scad.
include <case.scad>

lane_y = [for (i = [0:2]) rack_wall + edge_gap + slot_t/2 + i*(slot_t + air_gap)];
gap_y  = [for (i = [0:1]) lane_y[i] + slot_t/2 + air_gap/2];
groove_x0 = comb_t;  groove_x1 = comb_t + slot_l;
far_x0 = rack_l - tray_l - comb_t;  far_x1 = rack_l - tray_l;
peg_d = 4; peg_l = 6; peg_z = 3.5;
fan_faces = rack_fan == "80" ? [[0, 0, 80]] : [[0, -20, 40], [0, 20, 40]];   // [y, z, size] offsets
fan_box = rack_fan == "80" ? [80, 80] : [40, 80];
fan_zc = 1 + fan_box[1]/2;               // fan centre height above the table plane
dock_case_x = function(x) x - rack_dock_x;

module rack() {
  difference() {
    intersection() { rbox([rack_l, rack_w, rack_h], 5, 1, 0); union() {
      cube([rack_l, rack_w, rack_floor]);                                       // floor + tray
      for (y = [0, rack_w - rack_wall]) translate([0, y, 0]) cube([far_x1, rack_wall, rack_h]);   // side walls
      // fan-end frame
      difference() {
        cube([comb_t, rack_w, rack_h]);
        translate([-1, frame_b, frame_b]) cube([comb_t + 2, rack_w - 2*frame_b, rack_h - 2*frame_b]);
      }
      // phone end-channels at the fan end (pairs of ribs)
      for (y = lane_y, sgn = [-1, 1]) translate([0, y + sgn*(slot_t/2 + 1.25) - 1.25, 0]) cube([comb_t + 6, 2.5, rack_h - frame_b + 0.01]);
      // far-end slotted back
      translate([far_x0, 0, 0]) cube([comb_t, rack_w, rack_h]);
      // tray lip
      translate([rack_l - 3, 0, 0]) cube([3, rack_w, rack_floor + 4]);
    } }
    // phone grooves
    for (y = lane_y) translate([groove_x0, y - slot_t/2, rack_floor - groove_d]) cube([slot_l, slot_t, groove_d + 1]);
    // USB cable channels: groove end -> through the back -> tray -> cross channel to the case side
    for (y = lane_y) {
      translate([groove_x1 - 1, y - 3.5, rack_floor - groove_d]) cube([rack_l - tray_l/2 - groove_x1 + 4.5, 7, groove_d + 1]);
      translate([far_x0 - 1, y - 3.5, rack_floor - groove_d]) cube([comb_t + 2, 7, rack_h]);          // vertical slit in the back
    }
    translate([rack_l - tray_l/2 - 3.5, -1, rack_floor - groove_d]) cube([7, lane_y[2] + 4.5, groove_d + 1]);
    // slotted back: slash vents in the air lanes (claw styling)
    for (y = concat(gap_y, [rack_wall + edge_gap/2 - 0.5, rack_w - rack_wall - edge_gap/2 + 0.5]), z = [rack_floor + 6 : 9 : rack_h - 8])
      translate([far_x0 - 1, y, z]) rotate([90, 0, 90]) linear_extrude(comb_t + 2)
        intersection() { slash2d(9, 3, 3, -30); square([y == gap_y[0] || y == gap_y[1] ? air_gap - 3 : edge_gap - 2.5, 20], center = true); }
    // floor slash vents in the air lanes
    for (y = gap_y, x = [comb_t + 14 : 10 : groove_x1 - 8]) translate([x, y, -1]) linear_extrude(rack_floor + 2) slash2d(8, 3, 3, -35);
    // fan-end: shroud screws (top) + peg holes (bottom)
    for (y = [rack_wall/2, rack_w - rack_wall/2]) translate([-1, y, rack_h - frame_b/2]) rotate([0, 90, 0]) cylinder(d = clear_d, h = comb_t + 2);
    for (y = gap_y) translate([-1, y, peg_z]) rotate([0, 90, 0]) cylinder(d = peg_d + 2*fit, h = peg_l + 1.5);
    // dovetail slots on the case-facing wall (slide down over the case rails)
    for (x = dt_x) translate([dock_case_x(x), 0, -1]) linear_extrude(rack_h + 2)
      polygon([[-dt_w0/2 - fit, -1], [dt_w0/2 + fit, -1], [dt_w0/2 + fit, 0], [dt_w1/2 + fit, dt_h + fit], [-dt_w1/2 - fit, dt_h + fit], [-dt_w0/2 - fit, 0]]);
    // outer-side styling: debossed claw marks + wordmark
    for (x = [24, far_x1 - 24]) translate([x, rack_w + 0.01, rack_h/2]) rotate([90, 0, 0]) linear_extrude(0.8) claws2d(36, 5, 9, x < 50 ? -22 : 22);
    translate([far_x1/2, rack_w + 0.01, rack_h/2]) rotate([90, 0, 0]) mirror([1, 0, 0]) linear_extrude(0.8)
      text("PYRA CLAW", size = 9, halign = "center", valign = "center", font = font, spacing = 1.08);
    // recessed rubber-foot pockets
    for (x = [12, rack_l - 12], y = [12, rack_w - 12]) translate([x, y, -1]) cylinder(d = foot_d, h = foot_depth + 1);
  }
}

// Shroud, modelled in rack coordinates (x from -shroud_l to 0); shroud_print() lays it flat.
module shroud_shape(t) {   // t = 0 outer, 1 inner
  hull() {
    translate([-shroud_l, rack_w/2 - fan_box[0]/2, fan_zc - fan_box[1]/2]) cube([3, fan_box[0], fan_box[1]]);
    translate([-3, 0, 0]) cube([3, rack_w, rack_h]);
  }
}
module shroud() {
  difference() {
    shroud_shape();
    hull() {
      for (f = fan_faces) translate([-shroud_l - 1, rack_w/2 + f[0], fan_zc + f[1]]) rotate([0, 90, 0]) cylinder(d = f[2] - 3, h = 4.01);
      translate([-0.99, frame_b, frame_b]) cube([1, rack_w - 2*frame_b, rack_h - 2*frame_b]);
    }
    for (f = fan_faces) translate([-shroud_l - 1, rack_w/2 + f[0], fan_zc + f[1]]) rotate([0, 90, 0]) cylinder(d = f[2] - 3, h = 4.01);
    // fan screw pilots (fan self-tap screws or M4 self-tap) [Guessing]
    for (f = fan_faces, i = [-1, 1], j = [-1, 1])
      translate([-shroud_l - 1, rack_w/2 + f[0] + i*fan_hole_pitch(f[2])/2, fan_zc + f[1] + j*fan_hole_pitch(f[2])/2]) rotate([0, 90, 0]) cylinder(d = f[2] >= 60 ? 3.8 : 2.8, h = 13);
    // rack screw pilots (top) + wire exit notch
    for (y = [rack_wall/2, rack_w - rack_wall/2]) translate([-10, y, rack_h - frame_b/2]) rotate([0, 90, 0]) cylinder(d = selftap_d, h = 11);
    translate([-shroud_l + 3, rack_w - 4, -1]) cube([6, 5, 6]);
    // claw slashes on the shroud sides (debossed)
    for (y = [-0.01, rack_w + 0.01]) translate([-shroud_l/2 - 2, y, rack_h/2]) rotate([90, 0, 0]) linear_extrude(0.8, center = true) claws2d(28, 4, 7, 24);
  }
  // alignment pegs into the rack frame
  for (y = gap_y) translate([-0.01, y, peg_z]) rotate([0, 90, 0]) cylinder(d = peg_d, h = peg_l);
}
module shroud_print() translate([0, 0, shroud_l]) rotate([0, -90, 0]) shroud();

// ghost fan(s) for previews
module fan_ghost() for (f = fan_faces) translate([-shroud_l - rack_fan_t, rack_w/2 + f[0] - f[2]/2, fan_zc + f[1] - f[2]/2])
  difference() { cube([rack_fan_t, f[2], f[2]]); translate([-1, f[2]/2, f[2]/2]) rotate([0, 90, 0]) cylinder(d = f[2] - 4, h = rack_fan_t + 2); }
// ghost phones
module phones_ghost() for (y = lane_y) translate([groove_x0 + phone_len_margin, y - ph[2]/2, rack_floor - groove_d]) cube([ph[0], ph[2], ph[1]]);

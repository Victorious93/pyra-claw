// v2 previews. Set view from the CLI: -D 'view="docked"' | "exploded" | "airflow"
include <rack.scad>
view = "docked";
body = "#3b414d"; accent = "#ff5a1f"; ghost = "#9aa0a8";
module arrow(p0, p1, c = "#18a0ff", d = 5) {
  v = p1 - p0; L = norm(v); b = acos(v[2]/L); a = atan2(v[1], v[0]);
  color(c) translate(p0) rotate([0, b, a]) { cylinder(d = d, h = L - 3*d); translate([0, 0, L - 3*d]) cylinder(d1 = 2.4*d, d2 = 0, h = 3*d); }
}
module case_closed(lift = 0) {
  color(body) base();
  color(body) translate([0, 0, out[2] + lift]) difference() { lid(); translate([0, 0, lid_t - panel_d]) linear_extrude(5) wordmark2d(); }
  color(accent) translate([0, 0, out[2] + lift]) { lid_inlay_wordmark(); lid_inlay_claws(); }
}
module boards() {
  color("#2f8f4e", 0.9) translate([wall + pi_x, wall + pi_y, floor_t + pcb_z]) cube([pi_l, pi_w, pi_t]);
  color("#555", 0.9) translate([wall + pi_x + 3, wall + pi_y + 6.5, floor_t + pcb_z + pi_t]) cube([63.5, 42.5, cooler_h]);
  color("#d8d8d8", 0.9) translate([wall + coral_x, wall + coral_y + 2 + fit, floor_t]) cube([coral_l, coral_t, coral_w]);
  color(ghost, 0.9) translate([wall + br_x, wall + br_y, floor_t]) esp32_bracket();
  color("#333", 0.9) translate([cf[0] - case_fan/2, cf[1] - case_fan/2, out[2] - case_fan_t]) cube([case_fan, case_fan, case_fan_t]);
}
module rack_docked() translate([rack_dock_x, out[1], 0]) {
  color(body) rack(); color(accent) shroud(); color("#555") fan_ghost();
  color("#2a2f3a", 0.85) phones_ghost();
}
if (view == "docked") { case_closed(); rack_docked(); }
if (view == "case") case_closed();
if (view == "bottom") color(body) base();
if (view == "exploded") { color(body) base(); translate([0,0,30]) case_closed_lid(); boards(); }
module case_closed_lid() {
  color(body) translate([0, 0, out[2]]) difference() { lid(); translate([0, 0, lid_t - panel_d]) linear_extrude(5) wordmark2d(); }
  color(accent) translate([0, 0, out[2]]) { lid_inlay_wordmark(); lid_inlay_claws(); }
}
if (view == "airflow") {
  color(body) base(); boards();
  
  // case: intake right wall + front (cool, blue) -> over Active Cooler / Coral -> lid fan exhaust (warm, red)
  arrow([out[0] + 30, wall + pi_y + pi_w, floor_t + 15], [out[0] - 6, wall + pi_y + pi_w - 4, floor_t + 15]);
  arrow([wall + pi_x + 72, -30, floor_t + 18], [wall + pi_x + 68, 10, floor_t + 18]);
  arrow([75, 40, 24], [45, 78, 24], "#a040ff");
  arrow([70, 100, 22], [40, 85, 24], "#a040ff");
  arrow([cf[0], cf[1], out[2] + 2], [cf[0], cf[1], out[2] + 40], "#ff3030");
  arrow([wall + pi_x + fan_c[0], wall + pi_y + fan_c[1], out[2] + 40], [wall + pi_x + fan_c[0], wall + pi_y + fan_c[1], out[2] + 6]);
  // rack: fan -> along the three phone lanes -> out the slotted back
  translate([rack_dock_x, out[1], 0]) {
    color(body) rack(); color(accent) shroud(); color("#555") fan_ghost(); color("#2a2f3a", 0.6) phones_ghost();
    for (y = concat(gap_y, [rack_wall + edge_gap/2, rack_w - rack_wall - edge_gap/2])) {
      arrow([-shroud_l - rack_fan_t - 30, y, 40], [40, y, 40]);
      arrow([60, y, 40], [far_x0 - 5, y, 40], "#a040ff");
      arrow([far_x1, y, 40], [far_x1 + 35, y, 40], "#ff3030");
    }
  }
}

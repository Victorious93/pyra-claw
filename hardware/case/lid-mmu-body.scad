include <case.scad>
difference() { lid(); translate([0, 0, lid_t - panel_d]) linear_extrude(5) wordmark2d(); }

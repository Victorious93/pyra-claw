include <case.scad>
explode = 30;
color("dimgray") base();
color("orange") translate([0, out[1], out[2] + lid_t + explode]) mirror([0,1,0]) mirror([0,0,1]) translate([0,0,0]) lid();
color("steelblue") translate([wall + br_x, wall + br_y, floor_t]) esp32_bracket();
// ghost boards
%translate([wall + pi_x, wall + pi_y, floor_t + pcb_z]) cube([pi_l, pi_w, pi_t]);
%translate([wall + pi_x, wall + pi_y + 6.5, floor_t + pcb_z + pi_t]) cube([63.5, 42.5, cooler_h]);
%translate([wall + coral_x, wall + coral_y + 2 + fit, floor_t]) cube([coral_l, coral_t, coral_w]);

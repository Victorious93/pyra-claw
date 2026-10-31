include <rack.scad>
S="stl/";
body="#3b414d"; acc="#ff5a1f";
view="case";
module lidc() translate([0,0,out[2]]) { color(body) import(str(S,"lid-mmu-body.stl")); color(acc){import(str(S,"lid-inlay-wordmark.stl")); import(str(S,"lid-inlay-claws.stl"));} }
if (view=="case") { color(body) import(str(S,"base.stl")); lidc(); }
if (view=="bottom") color(body) import(str(S,"base.stl"));
if (view=="docked") { color(body) import(str(S,"base.stl")); lidc();
  translate([rack_dock_x, out[1], 0]) { color(body) import(str(S,"phone-rack.stl")); color(acc) shroud(); color("#555") fan_ghost(); color("#20242c") phones_ghost(); } }

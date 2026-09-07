// Original synthetic teaching fixture, distributed under the project MIT license.
// Not microscopy data and not suitable for validating anatomical alignment.
newImage("AtlasAlign synthetic slide", "16-bit black", 1200, 700, 1);
setForegroundColor(90, 90, 90);
makeOval(80, 100, 260, 400); fill();
makeOval(450, 120, 280, 420); fill();
makeOval(830, 80, 250, 440); fill();
run("Select None");
// Draw your own enclosing polygons, add each to ROI Manager, then start Batch review.

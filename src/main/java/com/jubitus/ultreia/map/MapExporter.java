package com.jubitus.ultreia.map;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/** Writes the segments shown on the map to a text file in the game folder (debug export, English). */
@SideOnly(Side.CLIENT)
final class MapExporter {

    private MapExporter() {}

    /** Writes the export and returns the file name; throws when the file cannot be written. */
    static String export(File gameDir, int centerX, int centerZ, int radius,
                         List<PacketOpenMapGui.SegmentData> segments, int roadCount, int bridgeCount,
                         int villageCount) throws IOException {
        String name = "ultreia_export_" + System.currentTimeMillis() + ".txt";
        File file = new File(gameDir, name);

        try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
            pw.println("# Ultreia Road Network Export");
            pw.println("# Timestamp: " + System.currentTimeMillis());
            pw.println("# Center: (" + centerX + ", " + centerZ + ")");
            pw.println("# Radius: " + radius + " blocks");
            pw.println("# Segments: " + segments.size() +
                    " total (" + roadCount + " road, " + bridgeCount + " bridge)");
            pw.println("# Villages: " + villageCount);
            pw.println();

            for (PacketOpenMapGui.SegmentData seg : segments) {
                int wc = seg.waypointCount();
                pw.println("== SEGMENT " + seg.id + " [" + (seg.isBridge ? "BRIDGE" : "ROAD") + "] ==");
                pw.println("From-village: " + (seg.fromX != PacketOpenMapGui.ABSENT
                        ? "(" + seg.fromX + ", " + seg.fromZ + ")" : "none"));
                pw.println("To-village:   " + (seg.toX != PacketOpenMapGui.ABSENT
                        ? "(" + seg.toX + ", " + seg.toZ + ")" : "none"));
                pw.println("Waypoints: " + wc);
                if (wc > 0) {
                    pw.println("Start: (" + seg.wx(0) + ", " + seg.wy(0) + ", " + seg.wz(0) + ")");
                    pw.println("End:   (" + seg.wx(wc-1) + ", " + seg.wy(wc-1) + ", " + seg.wz(wc-1) + ")");
                }
                pw.println("# idx |     X |  Ypath |     Z");
                for (int i = 0; i < wc; i++) {
                    pw.printf("  %4d | %6d | %6d | %6d%n", i, seg.wx(i), seg.wy(i), seg.wz(i));
                }
                pw.println();
            }
        }
        return name;
    }
}

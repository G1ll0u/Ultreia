package com.jubitus.ultreia.bridge;

import com.jubitus.ultreia.bridge.template.BridgeTemplate;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.List;

public final class BridgeGeometry {

    private BridgeGeometry() {}

    /** Walkable deck width of every bridge, in blocks (odd); independent of roadWidth. */
    public static final int DECK_WIDTH_BLOCKS = 3;

    public static final class Dir2 {
        public final int dx;
        public final int dz;

        public Dir2(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        public Dir2 perp() {
            return new Dir2(-dz, dx);
        }
    }

    public static Dir2 localDir(List<BlockPos> line, int idx) {
        if (line == null || line.isEmpty()) return new Dir2(1, 0);
        if (line.size() == 1) return new Dir2(1, 0);

        // Sample direction from an interior index, never the very first/last point. A bridge
        // centerline is one straight raster line, but its raw first/last step can land on a
        // single Bresenham-phase artifact (e.g. a diagonal step) that isn't representative of the
        // line's real heading — using it as-is rotates the endpoint's wall/lateral perpendicular
        // away from the approach road's true direction, so the entrance walls end up offset from
        // where the road actually arrives. Borrowing the direction from one step in (a symmetric,
        // interior neighbor window) gives the steady-state staircase direction instead.
        int sampleIdx = clampInterior(idx, line.size());

        BlockPos prev = line.get(Math.max(0, sampleIdx - 1));
        BlockPos next = line.get(Math.min(line.size() - 1, sampleIdx + 1));

        int dx = Integer.signum(next.getX() - prev.getX());
        int dz = Integer.signum(next.getZ() - prev.getZ());

        if (dx == 0 && dz == 0) {
            BlockPos cur = line.get(sampleIdx);

            if (sampleIdx > 0) {
                BlockPos p = line.get(sampleIdx - 1);
                dx = Integer.signum(cur.getX() - p.getX());
                dz = Integer.signum(cur.getZ() - p.getZ());
            } else if (sampleIdx + 1 < line.size()) {
                BlockPos n = line.get(sampleIdx + 1);
                dx = Integer.signum(n.getX() - cur.getX());
                dz = Integer.signum(n.getZ() - cur.getZ());
            }
        }

        if (dx == 0 && dz == 0) {
            dx = 1;
        }

        return new Dir2(dx, dz);
    }

    private static int clampInterior(int idx, int size) {
        if (size <= 2) return idx; // no interior point to borrow from on a 1-2 point line
        if (idx <= 0) return 1;
        if (idx >= size - 1) return size - 2;
        return idx;
    }

    /** Half width a bridge built with {@code template} occupies beside its centerline (deck plus walls). */
    public static int lateralHalfWidth(@Nullable BridgeTemplate template) {
        int half = 1;
        if (template != null) {
            half = Math.max(half, Math.max(Math.abs(template.minLateral), Math.abs(template.maxLateral)));
        }
        return half;
    }

    /**
     * True when (x, z) lies under the span away from its two ends: within {@code lateral} blocks of its axis
     * and more than {@code endMargin} blocks inside both ends along it.
     */
    public static boolean isUnderSpan(BridgeSpan span, double lateral, double endMargin, int x, int z) {
        double ax = span.end.getX() - span.start.getX();
        double az = span.end.getZ() - span.start.getZ();
        double len = Math.sqrt(ax * ax + az * az);
        if (len < 1.0) return false;
        double px = x - span.start.getX();
        double pz = z - span.start.getZ();
        double along = (px * ax + pz * az) / len;
        if (along <= endMargin || along >= len - endMargin) return false;
        return Math.abs(px * az - pz * ax) / len <= lateral;
    }

    public interface FootprintSink {
        /** {@code idx}: centerline index the cell belongs to; {@code diagonalFill}: a corner cell of a diagonal step. */
        void accept(int x, int z, int idx, boolean diagonalFill);
    }

    /** Lateral stripe of every centerline cell, plus the corner cells of diagonal steps so there are no holes. */
    public static void forEachFootprintCell(List<BlockPos> centerline, int half, FootprintSink sink) {
        for (int i = 0; i < centerline.size(); i++) {
            BlockPos c = centerline.get(i);
            Dir2 perp = localDir(centerline, i).perp();
            for (int o = -half; o <= half; o++) {
                sink.accept(c.getX() + perp.dx * o, c.getZ() + perp.dz * o, i, false);
            }
            if (i > 0) {
                BlockPos p = centerline.get(i - 1);
                int stepX = Integer.signum(c.getX() - p.getX());
                int stepZ = Integer.signum(c.getZ() - p.getZ());
                if (stepX != 0 && stepZ != 0) {
                    for (int o = -half; o <= half; o++) {
                        sink.accept(p.getX() + stepX + perp.dx * o, p.getZ() + perp.dz * o, i, true);
                        sink.accept(p.getX() + perp.dx * o, p.getZ() + stepZ + perp.dz * o, i, true);
                    }
                }
            }
        }
    }
}

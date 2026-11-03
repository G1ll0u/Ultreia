package com.jubitus.ultreia.bridge;

import net.minecraft.util.math.BlockPos;

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
}

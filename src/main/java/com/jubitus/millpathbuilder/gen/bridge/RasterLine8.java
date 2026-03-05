package com.jubitus.millpathbuilder.gen.bridge;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RasterLine8 {

    private RasterLine8() {}

    // Standard Bresenham (8-connected): one cell per step, stable for diagonals.
    public static List<BlockPos> raster(int x0, int z0, int x1, int z1, int yPlaceholder) {
        ArrayList<BlockPos> out = new ArrayList<>(Math.max(8, Math.abs(x1 - x0) + Math.abs(z1 - z0) + 2));

        int dx = Math.abs(x1 - x0);
        int dz = Math.abs(z1 - z0);
        int sx = x0 < x1 ? 1 : -1;
        int sz = z0 < z1 ? 1 : -1;

        int x = x0;
        int z = z0;

        out.add(new BlockPos(x, yPlaceholder, z));

        if (dx == 0 && dz == 0) return out;

        int err = dx - dz;

        while (x != x1 || z != z1) {
            int e2 = err << 1;
            if (e2 > -dz) {
                err -= dz;
                x += sx;
            }
            if (e2 < dx) {
                err += dx;
                z += sz;
            }
            out.add(new BlockPos(x, yPlaceholder, z));
        }

        return out;
    }
}
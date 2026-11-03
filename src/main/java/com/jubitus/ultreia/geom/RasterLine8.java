package com.jubitus.ultreia.geom;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RasterLine8 {

    private static final int MAX_RASTER_POINTS = 1_000_000;

    private RasterLine8() {}

    public static List<BlockPos> raster(int x0, int z0, int x1, int z1, int yPlaceholder) {
        long ldx = (long) x1 - (long) x0;
        long ldz = (long) z1 - (long) z0;
        long estimate = Math.abs(ldx) + Math.abs(ldz) + 2L;

        if (estimate <= 0L || estimate > MAX_RASTER_POINTS) {
            throw new IllegalArgumentException("RasterLine8 segment too large: (" + x0 + "," + z0 + ") -> (" + x1 + "," + z1 + "), estimate=" + estimate);
        }

        ArrayList<BlockPos> out = new ArrayList<>((int) estimate);

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
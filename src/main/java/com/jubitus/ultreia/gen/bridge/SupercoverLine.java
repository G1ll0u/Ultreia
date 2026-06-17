package com.jubitus.ultreia.gen.bridge;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class SupercoverLine {

    private SupercoverLine() {}

    public static List<BlockPos> raster(int x0, int z0, int x1, int z1, int yPlaceholder) {
        ArrayList<BlockPos> out = new ArrayList<>(Math.max(8, Math.abs(x1 - x0) + Math.abs(z1 - z0) + 4));
        out.add(new BlockPos(x0, yPlaceholder, z0));

        int dx = x1 - x0;
        int dz = z1 - z0;

        int sx = Integer.signum(dx);
        int sz = Integer.signum(dz);

        dx = Math.abs(dx);
        dz = Math.abs(dz);

        int x = x0;
        int z = z0;

        if (dx == 0 && dz == 0) {
            return out;
        }

        int err = dx - dz;

        while (x != x1 || z != z1) {
            int e2 = err << 1;

            int nx = x;
            int nz = z;

            if (e2 > -dz) {
                err -= dz;
                nx += sx;
            }
            if (e2 < dx) {
                err += dx;
                nz += sz;
            }

            // supercover: when diagonal step occurs, include the two side cells too
            if (nx != x && nz != z) {
                out.add(new BlockPos(nx, yPlaceholder, z));
                out.add(new BlockPos(x, yPlaceholder, nz));
            }

            x = nx;
            z = nz;
            out.add(new BlockPos(x, yPlaceholder, z));
        }

        // dedup consecutive same xz
        ArrayList<BlockPos> dedup = new ArrayList<>(out.size());
        BlockPos last = null;
        for (BlockPos p : out) {
            if (last == null || p.getX() != last.getX() || p.getZ() != last.getZ()) dedup.add(p);
            last = p;
        }
        return dedup;
    }
}
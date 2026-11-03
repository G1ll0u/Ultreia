package com.jubitus.ultreia.geom;

import com.jubitus.ultreia.config.UltreiaConfig;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Grid cells crossed by a straight segment in the XZ plane. */
public final class LineRaster {

    private static final boolean USE_RASTER8_FOR_BRIDGES = true;

    private static final int MAX_RASTER_POINTS = 1_000_000;

    private LineRaster() {}

    /** The bridge-line raster chosen by config: 8-connected, or supercover. */
    public static List<BlockPos> forBridges(int x0, int z0, int x1, int z1, int yPlaceholder) {
        return USE_RASTER8_FOR_BRIDGES
                ? eight(x0, z0, x1, z1, yPlaceholder)
                : supercover(x0, z0, x1, z1, yPlaceholder);
    }

    public static List<BlockPos> eight(int x0, int z0, int x1, int z1, int yPlaceholder) {
        long ldx = (long) x1 - (long) x0;
        long ldz = (long) z1 - (long) z0;
        long estimate = Math.abs(ldx) + Math.abs(ldz) + 2L;

        if (estimate <= 0L || estimate > MAX_RASTER_POINTS) {
            throw new IllegalArgumentException("LineRaster segment too large: (" + x0 + "," + z0 + ") -> (" + x1 + "," + z1 + "), estimate=" + estimate);
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

    /** Supercover: a diagonal step also includes both side cells, so no corner is skipped. */
    public static List<BlockPos> supercover(int x0, int z0, int x1, int z1, int yPlaceholder) {
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

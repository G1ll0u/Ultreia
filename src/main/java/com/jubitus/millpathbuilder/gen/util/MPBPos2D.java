package com.jubitus.millpathbuilder.gen.util;

import net.minecraft.util.math.BlockPos;

public final class MPBPos2D {

    private MPBPos2D() {}

    public static long xzKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    public static int xFromKey(long key) {
        return (int) (key >> 32);
    }

    public static int zFromKey(long key) {
        return (int) key;
    }

    public static double distSq(int x1, int z1, int x2, int z2) {
        long dx = (long) x1 - (long) x2;
        long dz = (long) z1 - (long) z2;
        return (double) dx * (double) dx + (double) dz * (double) dz;
    }

    public static int snapDown(int value, int step) {
        return Math.floorDiv(value, step) * step;
    }

    public static boolean sameXZ(BlockPos a, BlockPos b) {
        return a != null && b != null && a.getX() == b.getX() && a.getZ() == b.getZ();
    }
}
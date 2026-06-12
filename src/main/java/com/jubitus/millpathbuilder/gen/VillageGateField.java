package com.jubitus.millpathbuilder.gen;

import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/**
 * Holds all valid gate entry positions for a destination village.
 * Injected into an A* session so the pathfinder can terminate at any gate
 * instead of a single pre-selected endpoint.
 */
public final class VillageGateField {

    private final int[] xs;
    private final int[] zs;
    private final int[] ys;
    private final int count;

    public VillageGateField(int[] xs, int[] zs, int[] ys, int count) {
        this.xs = xs;
        this.zs = zs;
        this.ys = ys;
        this.count = count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public int size() {
        return count;
    }

    /** True if (x, z) is within `step` blocks of any gate. */
    public boolean isAtGate(int x, int z, int step) {
        double thresh = (double) step * (double) step;
        for (int i = 0; i < count; i++) {
            double dx = x - xs[i];
            double dz = z - zs[i];
            if (dx * dx + dz * dz <= thresh) return true;
        }
        return false;
    }

    /** Euclidean distance to the nearest gate (for A* heuristic). */
    public double distToNearest(int x, int z) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            double dx = x - xs[i];
            double dz = z - zs[i];
            double d = dx * dx + dz * dz;
            if (d < best) best = d;
        }
        return Math.sqrt(best);
    }

    /** Returns the nearest gate position, or null if empty. */
    @Nullable
    public BlockPos nearestGate(int x, int z) {
        if (count == 0) return null;
        double best = Double.MAX_VALUE;
        int bestIdx = 0;
        for (int i = 0; i < count; i++) {
            double dx = x - xs[i];
            double dz = z - zs[i];
            double d = dx * dx + dz * dz;
            if (d < best) {
                best = d;
                bestIdx = i;
            }
        }
        return new BlockPos(xs[bestIdx], ys[bestIdx], zs[bestIdx]);
    }

    public BlockPos getGate(int index) {
        return new BlockPos(xs[index], ys[index], zs[index]);
    }
}

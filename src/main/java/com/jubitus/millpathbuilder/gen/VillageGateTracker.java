package com.jubitus.millpathbuilder.gen;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.util.math.BlockPos;

/**
 * Tracks how many road connections already use each gate within a single generation job.
 * Passed to endpoint resolution so less-used gates are preferred, spreading traffic
 * across all available gates rather than funnelling everything through one entry.
 */
public final class VillageGateTracker {

    private final Long2IntOpenHashMap usageByXZ = new Long2IntOpenHashMap();

    public VillageGateTracker() {
        usageByXZ.defaultReturnValue(0);
    }

    public int getUsage(BlockPos gate) {
        return usageByXZ.get(xzKey(gate));
    }

    public int getUsage(int x, int z) {
        return usageByXZ.get(xzKey(x, z));
    }

    public void markUsed(BlockPos gate) {
        if (gate == null) return;
        long k = xzKey(gate);
        usageByXZ.put(k, usageByXZ.get(k) + 1);
    }

    /** Cost penalty added per existing connection through this gate. */
    public double usagePenalty(int x, int z) {
        return usageByXZ.get(xzKey(x, z)) * 80.0;
    }

    private static long xzKey(BlockPos pos) {
        return xzKey(pos.getX(), pos.getZ());
    }

    private static long xzKey(int x, int z) {
        return ((long) x) << 32 | (z & 0xFFFFFFFFL);
    }
}

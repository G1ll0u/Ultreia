package com.jubitus.ultreia.route;

import com.jubitus.ultreia.geom.Pos2D;
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

    public void markUsed(BlockPos gate) {
        if (gate == null) return;
        long k = Pos2D.xzKey(gate.getX(), gate.getZ());
        usageByXZ.put(k, usageByXZ.get(k) + 1);
    }

    /** Cost penalty added per existing connection through this gate. */
    public double usagePenalty(int x, int z) {
        return usageByXZ.get(Pos2D.xzKey(x, z)) * 80.0;
    }

}

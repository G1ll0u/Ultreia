package com.jubitus.ultreia.planner;

import com.jubitus.ultreia.MillPresence;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.mill.MillVillageIndex;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class VillageClassifier {

    public enum VillageSize { SMALL, MEDIUM, HUB }

    private VillageClassifier() {}

    public static VillageSize classify(World world, BlockPos center) {
        int pop = getPopulation(world, center);
        if (pop >= UltreiaConfig.NETWORK.hubMinVillagers)    return VillageSize.HUB;
        if (pop >= UltreiaConfig.NETWORK.mediumMinVillagers) return VillageSize.MEDIUM;
        return VillageSize.SMALL;
    }

    /**
     * Returns population for a village center. Tries live villager count first;
     * falls back to the last known value stored in NetworkPlanData. Returns 0 if unknown.
     */
    public static int getPopulation(World world, BlockPos center) {
        NetworkPlanData wd = NetworkPlanData.get(world);

        if (MillPresence.isPresent()) {
            try {
                int live = MillVillageIndex.countVillagersInVillage(world, center);
                if (live > 0) {
                    wd.storeVillagePopulation(center, live);
                    return live;
                }
            } catch (Throwable ignored) {}
        }

        return wd.getStoredPopulation(center);
    }
}

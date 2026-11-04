package com.jubitus.ultreia.route;

import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.roads.RoadIndex;
import com.jubitus.ultreia.terrain.ProtectedAreas;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.terrain.TerrainSampler.Mode;
import com.jubitus.ultreia.terrain.TerrainSampler.Sample;
import com.jubitus.ultreia.terrain.TreeProbe;
import com.jubitus.ultreia.terrain.WaterProbe;

/** Extra A* cost of stepping on a terrain cell (water, biome, buildings, existing roads, trees, farmland). */
public final class TerrainCost {

    // Additional cost if stepping on water (non-ocean).
    private static final double WATER_PENALTY = 24.0;
    // Extra penalty for crossing small water patches (puddles/marsh).
    private static final double SMALL_WATER_PENALTY = 260.0;
    // Cost per block of the near-water radius for a land cell next to water.
    private static final double NEAR_WATER_PENALTY_PER_BLOCK = 35.0;
    // Additional cost if stepping in a blacklisted biome when hardBlockBlacklistedBiomes=false.
    private static final double BLACKLISTED_BIOME_PENALTY = 5000.0;
    // Ring around building footprints: radius and cost.
    private static final int PENALTY_RING_RADIUS = 1;
    private static final double PENALTY_RING_COST = 15000.0;
    // Cost added when entering an XZ column that belongs to an existing bridge volume (deck, supports, lateral blocks).
    private static final double EXISTING_BRIDGE_VOLUME_AVOIDANCE_COST = 700.0;
    // Extra cost when a tree trunk/branch is in the headroom above a grid cell.
    private static final double TREE_PENALTY = 15.0;
    // Multiplier applied to TREE_PENALTY when a large DynamicTrees trunk is nearby.
    private static final double DT_LARGE_TREE_PENALTY_MULTIPLIER = 6.0;
    // Extra pathfinding cost when a cell contains farmland (or modded equivalent).
    private static final double FARMLAND_PENALTY = 4000.0;

    private TerrainCost() {}

    public static double extraCost(TerrainSampler sampler, Sample s, Mode mode) {
        double cost = 0.0;
        if (s.isWater && !s.isOcean) {
            cost += WATER_PENALTY;
            if (s.smallWater) cost += SMALL_WATER_PENALTY;
        }
        if (s.isBlacklisted && !UltreiaConfig.GENERAL.hardBlockBlacklistedBiomes) cost += BLACKLISTED_BIOME_PENALTY;

        ProtectedAreas protectedAreas = sampler.protectedAreas();
        if (mode == Mode.PATHFINDING && protectedAreas != null) {
            if (!protectedAreas.contains(s.x, s.z) && protectedAreas.containsWithin(s.x, s.z, PENALTY_RING_RADIUS)) {
                cost += PENALTY_RING_COST;
            }
            cost += protectedAreas.penaltyAt(s.x, s.z);
        }

        RoadIndex roadIndex = sampler.roadIndex();
        if (mode == Mode.PATHFINDING && roadIndex != null) {
            if (roadIndex.isBridgeVolumeXZ(s.x, s.z)) cost += EXISTING_BRIDGE_VOLUME_AVOIDANCE_COST;
        }

        if (!s.isWater && s.nearWaterLike) {
            cost += NEAR_WATER_PENALTY_PER_BLOCK * WaterProbe.NEAR_WATER_RADIUS;
        }
        if (mode == Mode.PATHFINDING && s.hasTreeTrunk && UltreiaConfig.PAINTING.clearTrees) {
            cost += TREE_PENALTY * treePenaltyMultiplier(s.trunkRadius);
        }
        if (mode == Mode.PATHFINDING && s.hasFarmland) {
            cost += FARMLAND_PENALTY;
        }

        return cost;
    }

    /** Large DynamicTrees trunks cost more; vanilla trees keep the base penalty. */
    private static double treePenaltyMultiplier(int radius) {
        if (radius == TreeProbe.VANILLA_TRUNK) return 1.0;
        return radius >= TreeProbe.DT_LARGE_TREE_RADIUS_THRESHOLD ? DT_LARGE_TREE_PENALTY_MULTIPLIER : 1.0;
    }
}

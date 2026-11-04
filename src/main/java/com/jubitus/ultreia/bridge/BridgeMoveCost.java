package com.jubitus.ultreia.bridge;

import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.route.RoadType;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.terrain.TerrainView;
import net.minecraft.util.math.BlockPos;

/** A* cost of a bridge move: building a new span, or reusing a built one. */
public final class BridgeMoveCost {

    // Flat overhead penalty applied to all water-dominant bridge moves.
    private static final double WATER_BRIDGE_BASE_PENALTY = 250.0;
    // Extra cost added per water interior cell for water-dominant bridges.
    private static final double WATER_BRIDGE_LENGTH_PENALTY_PER_BLOCK = 8.0;
    // Fixed extra cost added to every bridge move.
    private static final double BRIDGE_FIXED_COST = 230;
    // Multiplier applied to bridge geometric length cost.
    private static final double BRIDGE_LENGTH_COST_MULTIPLIER = 1.10;
    // Bonus per block of valley depth.
    private static final double BRIDGE_VALLEY_PREFERENCE_BONUS_PER_BLOCK = 1.23;
    // Minimum number of land blocks on each bridge side that must be connectable with strict 0.5-block road steps.
    private static final int BRIDGE_APPROACH_RAMP_BLOCKS = 3;
    // Cost bonus applied when reusing an existing bridge (higher = stronger preference for reuse).
    private static final double BRIDGE_REUSE_BONUS = 120.0;
    // Bridge cost multiplier for LONE_TRAIL routes (connections to lone buildings).
    private static final double LONE_TRAIL_BRIDGE_COST_MULTIPLIER = 5.0;

    private BridgeMoveCost() {}

    /** Multiplier on new-bridge costs: LONE_TRAIL strongly discourages bridges in favour of land detours. */
    public static double newBridgeMultiplier(RoadType roadType) {
        if (roadType == RoadType.LONE_TRAIL) {
            return Math.max(1.0, LONE_TRAIL_BRIDGE_COST_MULTIPLIER);
        }
        return 1.0;
    }

    /** Crossing a built bridge: its length, discounted by the reuse bonus. */
    static double reuse(int lengthBlocks) {
        double bridgeCost = lengthBlocks * 1.0;
        return Math.max(0.1, bridgeCost - Math.max(0.0, BRIDGE_REUSE_BONUS));
    }

    /** Building a new span; the ray scan's water/valley flags add a last nudge. */
    static double newSpan(TerrainView sampler, BridgeSpan span, RoadType roadType,
                          boolean scanWaterCrossing, boolean scanValleyCrossing) {
        double cost = spanCost(sampler, span, roadType);
        if (scanWaterCrossing) {
            cost += 40.0;
        } else if (scanValleyCrossing) {
            cost -= 20.0;
        }
        return cost;
    }

    private static double spanCost(TerrainView sampler, BridgeSpan span, RoadType roadType) {
        double euclid = Math.sqrt(Pos2D.distSq(span.start.getX(), span.start.getZ(), span.end.getX(), span.end.getZ()));

        double fixed = Math.max(0.0, BRIDGE_FIXED_COST);
        double mult = Math.max(1.0, BRIDGE_LENGTH_COST_MULTIPLIER);
        double valleyBonus = Math.max(0.0, BRIDGE_VALLEY_PREFERENCE_BONUS_PER_BLOCK);

        int baseline = Math.max(
                sampler.sample(span.start.getX(), span.start.getZ()).surfaceY,
                sampler.sample(span.end.getX(), span.end.getZ()).surfaceY
        );

        int deepCells = 0;
        int waterCells = 0;
        int meaningfulCells = 0;
        int sumDepth = 0;
        int maxDepth = 0;

        int[] deckHalf = BridgeDeckProfile.of(span);

        for (int i = 1; i < span.centerline.size() - 1; i++) {
            BlockPos p = span.centerline.get(i);
            TerrainSampler.Sample s = sampler.sample(p.getX(), p.getZ());
            if (s == null) continue;

            int terrainDepth = Math.max(0, baseline - s.surfaceY);
            int deckY = HalfY.surfaceBlockY(deckHalf[i]);
            int clearance = Math.max(0, deckY - s.surfaceY);

            sumDepth += terrainDepth;
            if (terrainDepth > maxDepth) maxDepth = terrainDepth;

            boolean water = s.isWater && !s.isOcean;
            if (water) waterCells++;

            if (terrainDepth >= 3) deepCells++;
            if (clearance >= BridgeClearanceCheck.MEANINGFUL_GROUND_CLEARANCE_BLOCKS
                    || (water && clearance >= BridgeClearanceCheck.MEANINGFUL_WATER_CLEARANCE_BLOCKS)) {
                meaningfulCells++;
            }
        }

        int interior = Math.max(1, span.centerline.size() - 2);
        double avgDepth = (double) sumDepth / (double) interior;
        double meaningfulPct = (100.0 * (double) meaningfulCells) / (double) interior;
        double waterPct = (100.0 * (double) waterCells) / (double) interior;

        // Not span.waterDominant (feasibility's rule): this cost has its own, kept apart on purpose (user).
        boolean mostlyWaterForCost = waterCells > 0 && waterCells >= deepCells && waterPct >= 30.0;

        double cost = fixed + euclid * mult;

        if (mostlyWaterForCost) {
            // Progressive penalty: longer water crossings cost proportionally more,
            // making A* strongly prefer narrow river crossings over wide lake crossings.
            cost += Math.max(0.0, WATER_BRIDGE_BASE_PENALTY)
                    + waterCells * Math.max(0.0, WATER_BRIDGE_LENGTH_PENALTY_PER_BLOCK);
        } else {
            cost -= avgDepth * valleyBonus * 0.75;
            cost -= Math.min(deepCells, 12) * 6.0;

            if (maxDepth <= 1) cost += 220.0;
            else if (maxDepth == 2) cost += 120.0;

            if (avgDepth < 2.0) cost += 140.0;
            else if (avgDepth < 3.0) cost += 70.0;

            if (meaningfulPct < 50.0) cost += 180.0;
            else if (meaningfulPct < 65.0) cost += 80.0;

            if (deepCells <= 2) cost += 120.0;
        }

        if (waterCells <= 0 && deepCells <= 2) cost += 200.0;
        int approachBlocks = Math.max(1, BRIDGE_APPROACH_RAMP_BLOCKS);
        if (!BridgeApproachCheck.canConnectBridgeApproaches(sampler, span, approachBlocks + 2)) {
            cost += 120.0;
        }

        return cost;
    }
}

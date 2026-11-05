package com.jubitus.ultreia.bridge;

import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.terrain.TerrainView;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.List;

/** Is there real clearance under (and room above) a candidate deck: valley or river, never near-flat ground. */
public final class BridgeClearanceCheck {

    // Maximum number of water interior cells a water-dominant bridge may span.
    private static final int MAX_WATER_BRIDGE_LEN_BLOCKS = 24;
    // Minimum air/gap wanted below bridge deck on interior cells.
    private static final int MIN_GROUND_CLEARANCE_BELOW_DECK = 4;
    // Minimum consecutive interior cells that really benefit from a bridge.
    public static final int MIN_MEANINGFUL_GAP_RUN = 5;
    // Clearance above bridge deck checked during feasibility.
    private static final int WALK_CLEARANCE_ABOVE_DECK = 3;
    // Minimum clearance below bridge deck for water cells.
    private static final int MIN_WATER_CLEARANCE_BELOW_DECK = 1;
    // If a bridge is at least this long, require stronger elevation above terrain.
    private static final int LONG_BRIDGE_MIN_LEN_FOR_EXTRA_CLEARANCE = 14;
    // Minimum average terrain clearance required for long bridges.
    private static final int LONG_BRIDGE_REQUIRED_AVERAGE_CLEARANCE = 5;
    // Minimum max terrain clearance required somewhere along a long bridge.
    private static final int LONG_BRIDGE_REQUIRED_MAX_CLEARANCE = 6;
    // Minimum consecutive interior water cells required before a water crossing counts as a real bridge candidate.
    public static final int MIN_WATER_RUN_BLOCKS = 3;
    // For water bridges, max dry interior cells allowed before the first real water run.
    private static final int MAX_WATER_BRIDGE_LEAD_IN_BLOCKS = 2;
    // For water bridges, max dry interior cells allowed after the last real water run.
    public static final int MAX_WATER_BRIDGE_LEAD_OUT_BLOCKS = 2;
    // Extra side clearance checked around bridge centerline during feasibility.
    private static final int SIDE_CLEARANCE_RADIUS = 1;
    static final int MEANINGFUL_GROUND_CLEARANCE_BLOCKS = 3;
    // Minimum water clearance required for a bridge cell to count as genuinely elevated above water.
    static final int MEANINGFUL_WATER_CLEARANCE_BLOCKS = 1;
    // Minimum percentage of interior bridge cells that must have meaningful clearance.
    private static final int MIN_MEANINGFUL_CLEARANCE_PERCENT = 65;
    // Ignore this many cells near each bridge end when evaluating global meaningful clearance.
    private static final int ENDPOINT_GRACE_CELLS = 2;
    // Require the final cells before bridge exit to stay clearly above terrain/water.
    private static final int EXIT_MIN_CLEARANCE_BLOCKS = 2;
    // How many cells before the bridge end must satisfy exitMinClearanceBlocks.
    private static final int EXIT_MIN_CLEARANCE_CHECK_CELLS = 3;
    // Minimum full air blocks required between an existing road below and the bridge deck underside.
    public static final int UNDER_BRIDGE_ROAD_MIN_CLEARANCE_BLOCKS = 2;
    // Dry ground at least this far below the deck is a real gap; nearer, the road could rest on it.
    private static final int DEEP_GAP_BLOCKS = 5;
    // Longest stretch of near-deck dry ground a bridge may span between two water or deep-gap stretches (islet).
    private static final int MAX_INTERIOR_LANDABLE_RUN_BLOCKS = 4;

    private BridgeClearanceCheck() {}

    static final class Result {
        /** Null when the deck passes. */
        @Nullable final String reason;
        final boolean waterDominant;
        final boolean waterCrossing;

        private Result(@Nullable String reason, boolean waterDominant, boolean waterCrossing) {
            this.reason = reason;
            this.waterDominant = waterDominant;
            this.waterCrossing = waterCrossing;
        }
    }

    private static Result fail(String reason) {
        return new Result(reason, false, false);
    }

    /** Walks every cell of the line under the given deck; the line's two ends are already valid endpoints. */
    static Result check(BridgeSite site, TerrainView sampler, List<BlockPos> line, int[] deckHalf) {
        final int len = Math.max(0, line.size() - 1);
        final int minGroundGap = Math.max(1, MIN_GROUND_CLEARANCE_BELOW_DECK);
        final int minWaterGap = Math.max(1, MIN_WATER_CLEARANCE_BELOW_DECK);
        final int clearAbove = Math.max(2, WALK_CLEARANCE_ABOVE_DECK);
        final int interiorMinGapRun = Math.max(2, MIN_MEANINGFUL_GAP_RUN);
        final int interiorMinWaterRun = Math.max(1, MIN_WATER_RUN_BLOCKS);

        final int meaningfulGroundGap = Math.max(1, MEANINGFUL_GROUND_CLEARANCE_BLOCKS);
        final int meaningfulWaterGap = Math.max(1, MEANINGFUL_WATER_CLEARANCE_BLOCKS);
        final int minMeaningfulPercent = Math.max(0, MIN_MEANINGFUL_CLEARANCE_PERCENT);
        final int endpointGrace = Math.max(0, ENDPOINT_GRACE_CELLS);
        final int exitMinClearance = Math.max(0, EXIT_MIN_CLEARANCE_BLOCKS);
        final int exitMinClearanceCells = Math.max(0, EXIT_MIN_CLEARANCE_CHECK_CELLS);
        final int sideRadius = Math.max(0, SIDE_CLEARANCE_RADIUS);
        final int minUnderRoadClearance = Math.max(0, UNDER_BRIDGE_ROAD_MIN_CLEARANCE_BLOCKS);

        int bestGroundGapRun = 0;
        int currentGroundGapRun = 0;

        int bestWaterRun = 0;
        int currentWaterRun = 0;

        int firstWaterInterior = -1;
        int lastWaterInterior = -1;

        int meaningfulInteriorCells = 0;
        int countedInteriorCells = 0;

        int waterInteriorCells = 0;
        int groundGapInteriorCells = 0;

        boolean usefulSeen = false;
        int landableRun = 0;

        int sumGap = 0;
        int countGap = 0;
        int maxGapSeen = 0;
        int strongGapCells = 0;
        int veryStrongGapCells = 0;

        for (int i = 0; i < line.size(); i++) {
            BlockPos p = line.get(i);
            boolean endpoint = (i == 0 || i == line.size() - 1);

            TerrainSampler.Sample sample = sampler.sample(p.getX(), p.getZ());
            if (sample == null) return fail("bridge cell sample null at i=" + i + " pos=(" + p.getX() + "," + p.getZ() + ")");

            if (sampler.isProtectedNoPaint(p.getX(), p.getZ())) {
                return fail("protected no-paint at i=" + i + " pos=(" + p.getX() + "," + p.getZ() + ")");
            }
            if (BridgeFeasibility.isTooNearSettlement(site, p, false)) {
                return fail("bridge crosses settlement zone at i=" + i + " pos=(" + p.getX() + "," + p.getZ() + ")");
            }

            HalfY.SlabPlacement slab = HalfY.slabPlacement(deckHalf[i]);
            int deckSurfaceY = HalfY.surfaceBlockY(deckHalf[i]);
            int deckBlockY = slab.blockY;

            if (!endpoint && sample.surfaceY > deckSurfaceY) {
                return fail("terrain pierces deck at i=" + i + " terrainY=" + sample.surfaceY + " deckY=" + deckSurfaceY);
            }

            if (!endpoint && minUnderRoadClearance > 0 && sampler.isExistingRoadXZ(p.getX(), p.getZ())) {
                int freeBlocksBelowDeck = deckBlockY - sample.surfaceY - 1;
                if (freeBlocksBelowDeck < minUnderRoadClearance) {
                    return fail("under-bridge road clearance too small at i=" + i + " free=" + freeBlocksBelowDeck + " need=" + minUnderRoadClearance);
                }
            }

            int gap = Math.max(0, deckSurfaceY - sample.surfaceY);

            boolean hasGroundBridgeGap = !sample.isWater && !sample.isOcean && gap >= minGroundGap;
            boolean hasWaterBridgeGap = sample.isWater && !sample.isOcean && gap >= minWaterGap;

            boolean hasMeaningfulGroundGap = !sample.isWater && !sample.isOcean && gap >= meaningfulGroundGap;
            boolean hasMeaningfulWaterGap = sample.isWater && !sample.isOcean && gap >= meaningfulWaterGap;

            if (!endpoint) {
                // A real bridge lands on the first ground it can: no long low stretch between two crossings.
                boolean waterCell = sample.isWater && !sample.isOcean;
                if (hasWaterBridgeGap || (!waterCell && gap >= DEEP_GAP_BLOCKS)) {
                    if (usefulSeen && landableRun > MAX_INTERIOR_LANDABLE_RUN_BLOCKS) {
                        return fail("bridge spans landable ground: " + landableRun + " low dry cells before i=" + i
                                + " max=" + MAX_INTERIOR_LANDABLE_RUN_BLOCKS);
                    }
                    usefulSeen = true;
                    landableRun = 0;
                } else if (!waterCell) {
                    landableRun++;
                }

                sumGap += gap;
                countGap++;
                if (gap > maxGapSeen) maxGapSeen = gap;

                if (hasGroundBridgeGap) {
                    groundGapInteriorCells++;
                    currentGroundGapRun++;
                    if (currentGroundGapRun > bestGroundGapRun) bestGroundGapRun = currentGroundGapRun;
                } else {
                    currentGroundGapRun = 0;
                }

                if (hasWaterBridgeGap) {
                    waterInteriorCells++;
                    if (firstWaterInterior < 0) firstWaterInterior = i;
                    lastWaterInterior = i;

                    currentWaterRun++;
                    if (currentWaterRun > bestWaterRun) bestWaterRun = currentWaterRun;
                } else {
                    currentWaterRun = 0;
                }

                if (gap >= Math.max(1, LONG_BRIDGE_REQUIRED_AVERAGE_CLEARANCE)) {
                    strongGapCells++;
                }
                if (gap >= Math.max(1, LONG_BRIDGE_REQUIRED_AVERAGE_CLEARANCE) + 2) {
                    veryStrongGapCells++;
                }

                boolean ignoredForRatio = (i <= endpointGrace) || (i >= line.size() - 1 - endpointGrace);
                if (!ignoredForRatio) {
                    countedInteriorCells++;
                    if (hasMeaningfulGroundGap || hasMeaningfulWaterGap) {
                        meaningfulInteriorCells++;
                    }
                }
            }

            if (!site.isWalkVolumePassable(p.getX(), p.getZ(), deckBlockY + 1, deckBlockY + clearAbove, endpoint)) {
                return fail("walk volume blocked at i=" + i + " pos=(" + p.getX() + "," + p.getZ() + ")");
            }

            if (!checkSideClearance(site, sampler, line, i, deckHalf[i], clearAbove, endpoint, sideRadius, minUnderRoadClearance)) {
                return fail("side clearance failed at i=" + i + " pos=(" + p.getX() + "," + p.getZ() + ")");
            }
        }

        boolean validValleyBridge = bestGroundGapRun >= interiorMinGapRun;
        boolean validWaterBridge = bestWaterRun >= interiorMinWaterRun;

        if (!validValleyBridge && !validWaterBridge) {
            return fail("no meaningful gap run waterRun=" + bestWaterRun + " groundRun=" + bestGroundGapRun);
        }

        int interiorCells = Math.max(1, line.size() - 2);
        int usefulCells = Math.max(bestGroundGapRun, bestWaterRun);
        double usefulPct = (100.0 * (double) usefulCells) / (double) interiorCells;

        boolean waterDominant = validWaterBridge
                && waterInteriorCells >= Math.max(3, interiorCells / 3)
                && waterInteriorCells >= groundGapInteriorCells;

        if (waterDominant) {
            int maxWaterLen = Math.max(4, MAX_WATER_BRIDGE_LEN_BLOCKS);
            if (waterInteriorCells > maxWaterLen) {
                return fail("water bridge too long: waterCells=" + waterInteriorCells + " max=" + maxWaterLen);
            }
            if (usefulPct < 35.0) return fail("water bridge usefulPct too low: " + usefulPct);
        } else {
            if (!validWaterBridge) {
                int usefulGroundPct = (int) Math.floor((100.0 * (double) bestGroundGapRun) / (double) interiorCells);
                if (usefulGroundPct < 60) return fail("valley bridge usefulGroundPct too low: " + usefulGroundPct);
            }
            if (usefulPct < 45.0) return fail("overall usefulPct too low: " + usefulPct);
        }

        if (countedInteriorCells > 0 && minMeaningfulPercent > 0) {
            int pct = (int) Math.floor((100.0 * (double) meaningfulInteriorCells) / (double) countedInteriorCells);

            int requiredPct = waterDominant
                    ? Math.min(minMeaningfulPercent, 45)
                    : minMeaningfulPercent;

            if (pct < requiredPct) {
                return fail("meaningful clearance pct too low pct=" + pct + " required=" + requiredPct);
            }

            if (!waterDominant && !validWaterBridge && pct < Math.max(minMeaningfulPercent, 75)) {
                return fail("valley bridge meaningful clearance too low pct=" + pct);
            }
        }

        // A valley bridge over a stream is not a water bridge: its banks are the valley sides.
        if (waterDominant) {
            int maxLeadIn = Math.max(0, MAX_WATER_BRIDGE_LEAD_IN_BLOCKS);
            int maxLeadOut = Math.max(0, MAX_WATER_BRIDGE_LEAD_OUT_BLOCKS);

            if (firstWaterInterior < 0 || lastWaterInterior < 0) return fail("water bridge bookkeeping failed");

            int dryLeadIn = Math.max(0, firstWaterInterior - 1);
            int dryLeadOut = Math.max(0, (line.size() - 2) - lastWaterInterior);

            if (dryLeadIn > maxLeadIn) return fail("water lead-in too long " + dryLeadIn + " > " + maxLeadIn);
            if (dryLeadOut > maxLeadOut) return fail("water lead-out too long " + dryLeadOut + " > " + maxLeadOut);
        }

        if (!waterDominant && exitMinClearance > 0 && exitMinClearanceCells > 0) {
            int from = Math.max(1, line.size() - 1 - exitMinClearanceCells);

            for (int i = from; i < line.size() - 1; i++) {
                BlockPos p = line.get(i);
                TerrainSampler.Sample sample = sampler.sample(p.getX(), p.getZ());
                if (sample == null) return fail("exit clearance sample null");

                int deckSurfaceY = HalfY.surfaceBlockY(deckHalf[i]);
                int gap = deckSurfaceY - sample.surfaceY;
                if (gap < exitMinClearance) {
                    return fail("exit clearance too low at i=" + i + " gap=" + gap + " need=" + exitMinClearance);
                }
            }
        }

        int longBridgeLen = Math.max(2, LONG_BRIDGE_MIN_LEN_FOR_EXTRA_CLEARANCE);
        if (!waterDominant && len >= longBridgeLen) {
            int requiredAvgGap = Math.max(1, LONG_BRIDGE_REQUIRED_AVERAGE_CLEARANCE);
            int requiredMaxGap = Math.max(requiredAvgGap, LONG_BRIDGE_REQUIRED_MAX_CLEARANCE);

            int avgGap = countGap <= 0 ? 0 : (int) Math.round((double) sumGap / (double) countGap);

            if (avgGap < requiredAvgGap) return fail("long bridge avg gap too low avg=" + avgGap + " need=" + requiredAvgGap);
            if (maxGapSeen < requiredMaxGap) return fail("long bridge max gap too low max=" + maxGapSeen + " need=" + requiredMaxGap);

            int strongPct = countGap <= 0 ? 0 : (int) Math.floor((100.0 * (double) strongGapCells) / (double) countGap);
            int veryStrongPct = countGap <= 0 ? 0 : (int) Math.floor((100.0 * (double) veryStrongGapCells) / (double) countGap);

            if (strongPct < 80) return fail("long bridge strongPct too low: " + strongPct);
            if (veryStrongPct < 35) return fail("long bridge veryStrongPct too low: " + veryStrongPct);
        }

        return new Result(null, waterDominant, validWaterBridge);
    }

    private static boolean checkSideClearance(BridgeSite site,
                                              TerrainView sampler,
                                              List<BlockPos> line,
                                              int idx,
                                              int deckHalfY,
                                              int clearAbove,
                                              boolean endpoint,
                                              int sideRadius,
                                              int minUnderRoadClearance) {
        if (sideRadius <= 0) return true;
        if (idx < 0 || idx >= line.size()) return false;

        BlockPos center = line.get(idx);
        BridgeGeometry.Dir2 dir = BridgeGeometry.localDir(line, idx);
        BridgeGeometry.Dir2 perp = dir.perp();

        HalfY.SlabPlacement slab = HalfY.slabPlacement(deckHalfY);
        int deckSurfaceY = HalfY.surfaceBlockY(deckHalfY);
        int deckBlockY = slab.blockY;

        for (int off = 1; off <= sideRadius; off++) {
            for (int side = -1; side <= 1; side += 2) {
                int x = center.getX() + perp.dx * off * side;
                int z = center.getZ() + perp.dz * off * side;

                TerrainSampler.Sample sample = sampler.sample(x, z);
                if (sample == null) return false;
                if (sampler.isProtectedNoPaint(x, z)) return false;

                if (!endpoint && sample.surfaceY > deckSurfaceY) {
                    return false;
                }

                if (!endpoint && minUnderRoadClearance > 0 && sampler.isExistingRoadXZ(x, z)) {
                    int freeBlocksBelowDeck = deckBlockY - sample.surfaceY - 1;
                    if (freeBlocksBelowDeck < minUnderRoadClearance) {
                        return false;
                    }
                }

                if (!site.isWalkVolumePassable(x, z, deckBlockY + 1, deckBlockY + clearAbove, endpoint)) {
                    return false;
                }
            }
        }

        return true;
    }
}

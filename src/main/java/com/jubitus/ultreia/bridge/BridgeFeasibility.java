package com.jubitus.ultreia.bridge;

import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.paint.HeadroomClearer;
import com.jubitus.ultreia.route.AStarPathfinder;
import com.jubitus.ultreia.geom.LineRaster;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.terrain.TerrainView;
import com.jubitus.ultreia.bridge.template.BridgeTemplate;
import com.jubitus.ultreia.bridge.template.BridgeTemplateSelector;

import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.terrain.BlockRules;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.mill.MillPathPalette;
import com.jubitus.ultreia.mill.MillVillageIndex;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;


public final class BridgeFeasibility {

    // Minimum bridge length in blocks for A* bridge moves.
    public static final int MIN_BRIDGE_LEN_BLOCKS = 8;
    // Maximum bridge probe length in blocks for A* bridge moves.
    public static final int MAX_PROBE_LEN_BLOCKS = 128;
    // Maximum number of water interior cells a water-dominant bridge may span.
    private static final int MAX_WATER_BRIDGE_LEN_BLOCKS = 24;
    // Minimum air/gap wanted below bridge deck on interior cells.
    private static final int MIN_GROUND_CLEARANCE_BELOW_DECK = 4;
    // Minimum consecutive interior cells that really benefit from a bridge.
    public static final int MIN_MEANINGFUL_GAP_RUN = 5;
    // Clearance above bridge deck checked during feasibility.
    private static final int WALK_CLEARANCE_ABOVE_DECK = 3;
    // No bridge start/end within this radius of a village.
    private static final int ENDPOINT_VILLAGE_RADIUS = 10;
    // No bridge start/end within this radius of a lone building.
    private static final int ENDPOINT_LONE_BUILDING_RADIUS = 10;
    // Bridge centerline must not pass within this radius of a village.
    private static final int CROSS_VILLAGE_RADIUS = 10;
    // Bridge centerline must not pass within this radius of a lone building.
    private static final int CROSS_LONE_BUILDING_RADIUS = 10;
    // Minimum clearance below bridge deck for water cells.
    private static final int MIN_WATER_CLEARANCE_BELOW_DECK = 1;
    // If a bridge is at least this long, require stronger elevation above terrain.
    private static final int LONG_BRIDGE_MIN_LEN_FOR_EXTRA_CLEARANCE = 14;
    // Minimum average terrain clearance required for long bridges.
    private static final int LONG_BRIDGE_REQUIRED_AVERAGE_CLEARANCE = 5;
    // Minimum max terrain clearance required somewhere along a long bridge.
    private static final int LONG_BRIDGE_REQUIRED_MAX_CLEARANCE = 6;
    // Require this many buildable blocks straight ahead after bridge exit.
    private static final int REQUIRED_EXIT_CORRIDOR_BLOCKS = 1;
    // Minimum consecutive interior water cells required before a water crossing counts as a real bridge candidate.
    public static final int MIN_WATER_RUN_BLOCKS = 7;
    // For water bridges, max dry interior cells allowed before the first real water run.
    private static final int MAX_WATER_BRIDGE_LEAD_IN_BLOCKS = 1;
    // For water bridges, max dry interior cells allowed after the last real water run.
    public static final int MAX_WATER_BRIDGE_LEAD_OUT_BLOCKS = 1;
    // Extra side clearance checked around bridge centerline during feasibility.
    private static final int SIDE_CLEARANCE_RADIUS = 1;
    // When snapping to an existing road, keep at least this many land blocks before merging into it.
    public static final int BRIDGE_ROAD_APPROACH_MIN_BLOCKS = 8;
    // Maximum search radius around a snapped road to find a small natural land approach before the road.
    public static final int BRIDGE_ROAD_APPROACH_MAX_BLOCKS = 8;
    private static final int MEANINGFUL_GROUND_CLEARANCE_BLOCKS = 3;
    // Minimum water clearance required for a bridge cell to count as genuinely elevated above water.
    private static final int MEANINGFUL_WATER_CLEARANCE_BLOCKS = 1;
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

    private BridgeFeasibility() {}

    public static BridgeSpan tryCreate(TerrainView sampler, BlockPos start, BlockPos end) {
        BridgeSite site = sampler.bridgeSite();
        // The template depends on the span (length, water), its crown changes the deck: check with the
        // global crown, pick the template, then check again with the deck that will really be built.
        BridgeSpan first = evaluate(site, sampler, start, end, null).span;
        if (first == null) return null;
        BridgeTemplate template = site.selectTemplate(first);
        if (template == null) return first;
        int crown = BridgeGeometry.effectiveCrownHalf(template.bridgeCrownHalfBlocks, first.centerline, sampler);
        int crownMin = BridgeGeometry.effectiveCrownMinLen(template.bridgeCrownMinLenBlocks);
        if (crown == first.crownHalf && crownMin == first.crownMinLen) return first.withTemplate(template);
        return evaluate(site, sampler, start, end, template).span;
    }

    private static final class BridgeEval {
        final BridgeSpan span;
        final String reason;

        BridgeEval(BridgeSpan span, String reason) {
            this.span = span;
            this.reason = reason;
        }
    }

    private static BridgeEval ok(BridgeSpan span) {
        return new BridgeEval(span, "ok");
    }

    private static BridgeEval fail(String reason) {
        return new BridgeEval(null, reason);
    }

    private static BridgeEval evaluate(BridgeSite site,
                                       TerrainView sampler,
                                       BlockPos start,
                                       BlockPos end,
                                       @Nullable BridgeTemplate template) {
        if (!UltreiaConfig.BRIDGES.enabled) return fail("bridges disabled");
        if (start == null || end == null) return fail("start/end null");
        if (sameXZ(start, end)) return fail("same xz");

        TerrainSampler.Sample startSample = sampler.sample(start.getX(), start.getZ());
        TerrainSampler.Sample endSample = sampler.sample(end.getX(), end.getZ());
        if (startSample == null || endSample == null) return fail("start/end sample null");

        if (!isValidBridgeEndpoint(site, sampler, startSample)) {
            return fail("invalid start endpoint at (" + startSample.x + "," + startSample.z + ")"
                    + " water=" + startSample.isWater
                    + " ocean=" + startSample.isOcean
                    + " forbidden=" + sampler.describeForbidden(startSample, TerrainSampler.Mode.PATHFINDING));
        }
        if (!isValidBridgeEndpoint(site, sampler, endSample)) {
            return fail("invalid end endpoint at (" + endSample.x + "," + endSample.z + ")"
                    + " water=" + endSample.isWater
                    + " ocean=" + endSample.isOcean
                    + " forbidden=" + sampler.describeForbidden(endSample, TerrainSampler.Mode.PATHFINDING));
        }

        if (isTooNearSettlement(site, start, true)) return fail("start too near settlement");
        if (isTooNearSettlement(site, end, true)) return fail("end too near settlement");

        List<BlockPos> rawLine = LineRaster.forBridges(start.getX(), start.getZ(), end.getX(), end.getZ(), 0);

        if (rawLine == null || rawLine.size() < 2) return fail("raw bridge line too short");

        TrimmedBridgeCandidate trimmed = trimBridgeEndBeforeTargetRoad(sampler, start, end, rawLine);
        if (trimmed == null) return fail("trimBridgeEndBeforeTargetRoad failed");

        end = trimmed.end;
        List<BlockPos> line = trimmed.line;

        startSample = sampler.sample(start.getX(), start.getZ());
        endSample = sampler.sample(end.getX(), end.getZ());
        if (startSample == null || endSample == null) return fail("trimmed start/end sample null");

        if (!isValidBridgeEndpoint(site, sampler, startSample)) {
            return fail("trimmed start endpoint invalid at (" + startSample.x + "," + startSample.z + ")");
        }
        if (!isValidBridgeEndpoint(site, sampler, endSample)) {
            return fail("trimmed end endpoint invalid at (" + endSample.x + "," + endSample.z + ")");
        }

        int len = Math.max(0, line.size() - 1);
        int minLen = Math.max(2, MIN_BRIDGE_LEN_BLOCKS);
        int maxLen = Math.min(
                Math.max(minLen, UltreiaConfig.BRIDGES.maxBridgeLenBlocks),
                Math.max(minLen, MAX_PROBE_LEN_BLOCKS)
        );
        if (len < minLen || len > maxLen) {
            return fail("bridge length out of range len=" + len + " min=" + minLen + " max=" + maxLen);
        }

        int startHalf = HalfY.ofBlockTop(startSample.surfaceY);
        int endHalf = HalfY.ofBlockTop(endSample.surfaceY);

        int maxDeltaHalf = BridgeGeometry.maxBridgeDeltaHalfForLen(len);
        if (Math.abs(endHalf - startHalf) > maxDeltaHalf) {
            return fail("endpoint delta too high startHalf=" + startHalf + " endHalf=" + endHalf + " max=" + maxDeltaHalf);
        }

        int crownHalf = BridgeGeometry.effectiveCrownHalf(
                template != null ? template.bridgeCrownHalfBlocks : null, line, sampler);
        int crownMinLen = BridgeGeometry.effectiveCrownMinLen(
                template != null ? template.bridgeCrownMinLenBlocks : null);
        int[] deckHalf = BridgeGeometry.buildDeckHalfProfile(startHalf, endHalf, line, crownHalf, crownMinLen);

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
            if (isTooNearSettlement(site, p, false)) {
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

            if (!isBridgeColumnAcceptable(site, sampler, p.getX(), p.getZ(), deckSurfaceY, endpoint)) {
                return fail("bridge column unacceptable at i=" + i + " pos=(" + p.getX() + "," + p.getZ() + ")");
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

        if (validWaterBridge) {
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

        int exitLookAhead = Math.max(1, REQUIRED_EXIT_CORRIDOR_BLOCKS);
        if (!exitCorridorIsBuildable(site, sampler, start, end, exitLookAhead)) {
            return fail("exitCorridorIsBuildable failed");
        }

        return ok(new BridgeSpan(start, end, line, startHalf, endHalf, waterDominant, validWaterBridge,
                template, crownHalf, crownMinLen));
    }

    private static final class TrimmedBridgeCandidate {
        final BlockPos end;
        final List<BlockPos> line;

        TrimmedBridgeCandidate(BlockPos end, List<BlockPos> line) {
            this.end = end;
            this.line = line;
        }
    }

    @Nullable
    private static TrimmedBridgeCandidate trimBridgeEndBeforeTargetRoad(TerrainView sampler,
                                                                        BlockPos start,
                                                                        BlockPos end,
                                                                        List<BlockPos> rawLine) {
        if (rawLine == null || rawLine.size() < 2) return null;

        int roadSearch = Math.max(1, BRIDGE_ROAD_APPROACH_MAX_BLOCKS);
        int minApproach = Math.max(1, BRIDGE_ROAD_APPROACH_MIN_BLOCKS);

        BlockPos targetRoad = sampler.findNearestExistingRoad(end.getX(), end.getZ(), roadSearch);
        if (targetRoad == null) {
            return new TrimmedBridgeCandidate(end, rawLine);
        }

        int roadHitIdx = -1;
        double roadHitDistSq = Double.MAX_VALUE;

        int scanFrom = Math.max(1, rawLine.size() - 1 - (roadSearch + minApproach + 4));

        for (int i = scanFrom; i < rawLine.size() - 1; i++) {
            BlockPos p = rawLine.get(i);

            if (!sampler.isExistingRoadXZ(p.getX(), p.getZ())) {
                continue;
            }

            double d2 = Pos2D.distSq(p.getX(), p.getZ(), targetRoad.getX(), targetRoad.getZ());
            if (d2 < roadHitDistSq) {
                roadHitDistSq = d2;
                roadHitIdx = i;
            }
        }

        if (roadHitIdx < 0) {
            return new TrimmedBridgeCandidate(end, rawLine);
        }

        int minBridgeLen = Math.max(2, MIN_BRIDGE_LEN_BLOCKS);
        int searchStart = Math.max(1, roadHitIdx - Math.max(roadSearch + minApproach + 4, 8));
        int searchEnd = Math.max(searchStart, roadHitIdx - minApproach);

        int bestIdx = -1;
        double bestScore = Double.MAX_VALUE;

        for (int i = searchEnd; i >= searchStart; i--) {
            BlockPos p = rawLine.get(i);

            if (i < minBridgeLen) continue;
            if (sampler.isExistingRoadXZ(p.getX(), p.getZ())) continue;
            if (!sampler.isNaturalLandEndpoint(p.getX(), p.getZ())) continue;

            double d2Road = Pos2D.distSq(p.getX(), p.getZ(), targetRoad.getX(), targetRoad.getZ());
            if (d2Road < minApproach * minApproach) continue;

            TerrainSampler.Sample s = sampler.sample(p.getX(), p.getZ());
            if (s == null) continue;

            double score = d2Road + (roadHitIdx - i) * 6;
            if (score < bestScore) {
                bestScore = score;
                bestIdx = i;
            }
        }

        if (bestIdx < 0) {
            return new TrimmedBridgeCandidate(end, rawLine);
        }

        List<BlockPos> trimmedLine = new ArrayList<BlockPos>(bestIdx + 1);
        for (int i = 0; i <= bestIdx; i++) {
            BlockPos p = rawLine.get(i);
            TerrainSampler.Sample s = sampler.sample(p.getX(), p.getZ());
            if (s == null) return null;
            trimmedLine.add(new BlockPos(p.getX(), s.surfaceY, p.getZ()));
        }

        BlockPos newEnd2D = rawLine.get(bestIdx);
        TerrainSampler.Sample newEndSample = sampler.sample(newEnd2D.getX(), newEnd2D.getZ());
        if (newEndSample == null) return null;

        BlockPos newEnd = new BlockPos(newEnd2D.getX(), newEndSample.surfaceY, newEnd2D.getZ());
        return new TrimmedBridgeCandidate(newEnd, trimmedLine);
    }

    private static boolean isValidBridgeEndpoint(BridgeSite site,
                                                 TerrainView sampler,
                                                 TerrainSampler.Sample sample) {
        if (sample.isWater || sample.isOcean) return false;
        if (sampler.forbidden(sample, TerrainSampler.Mode.PATHFINDING)) return false;
        if (sampler.isProtectedNoPaint(sample.x, sample.z)) return false;
        if (sampler.isExistingRoadXZ(sample.x, sample.z)) return false;

        int y = sample.surfaceY;
        if (!site.isNaturalGroundOnly(sample.x, y, sample.z)) return false;
        return site.isBridgeClearable(sample.x, y + 1, sample.z, true)
                && site.isBridgeClearable(sample.x, y + 2, sample.z, true);
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

    private static boolean isBridgeColumnAcceptable(BridgeSite site,
                                                    TerrainView sampler,
                                                    int x,
                                                    int z,
                                                    int deckSurfaceY,
                                                    boolean endpoint) {
        TerrainSampler.Sample sample = sampler.sample(x, z);
        if (sample == null) return false;
        if (sampler.isProtectedNoPaint(x, z)) return false;

        if (endpoint) {
            if (sample.isWater || sample.isOcean) return false;
            if (sampler.isExistingRoadXZ(x, z)) return false;

            return site.isNaturalGroundOnly(x, sample.surfaceY, z);
        }

        return sample.surfaceY <= deckSurfaceY;
    }

    private static boolean isTooNearSettlement(BridgeSite site, BlockPos pos, boolean endpoint) {
        int villageRadius = endpoint
                ? Math.max(0, ENDPOINT_VILLAGE_RADIUS)
                : Math.max(0, CROSS_VILLAGE_RADIUS);

        int loneRadius = endpoint
                ? Math.max(0, ENDPOINT_LONE_BUILDING_RADIUS)
                : Math.max(0, CROSS_LONE_BUILDING_RADIUS);

        if (villageRadius > 0 && site.isNearVillage(pos, villageRadius)) return true;
        if (loneRadius > 0 && site.isNearAnySettlement(pos, loneRadius)) return true;

        return false;
    }

    private static boolean exitCorridorIsBuildable(BridgeSite site,
                                                   TerrainView sampler,
                                                   BlockPos start,
                                                   BlockPos end,
                                                   int lookAhead) {
        if (lookAhead <= 0) return true;

        int dx = Integer.signum(end.getX() - start.getX());
        int dz = Integer.signum(end.getZ() - start.getZ());

        if (dx == 0 && dz == 0) return false;

        int maxDy = Math.max(1, AStarPathfinder.MAX_YDELTA_PER_STEP);

        TerrainSampler.Sample prev = sampler.sample(end.getX(), end.getZ());
        if (prev == null) return false;

        int x = end.getX();
        int z = end.getZ();

        for (int i = 1; i <= lookAhead; i++) {
            x += dx;
            z += dz;

            TerrainSampler.Sample sample = sampler.sample(x, z);
            if (sample == null) return false;
            if (sample.isWater || sample.isOcean) return false;
            if (sampler.forbidden(sample, TerrainSampler.Mode.PATHFINDING)) return false;
            if (sampler.isProtectedNoPaint(x, z)) return false;

            int dy = Math.abs(sample.surfaceY - prev.surfaceY);
            if (dy > maxDy + 1) return false;

            if (!site.isRoadGround(x, sample.surfaceY, z)) return false;

            prev = sample;
        }

        return true;
    }

    private static boolean sameXZ(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

}
package com.jubitus.ultreia.gen.bridge;

import com.jubitus.ultreia.config.MPBConfig;
import com.jubitus.ultreia.gen.MPBGround;
import com.jubitus.ultreia.gen.TerrainSampler;
import com.jubitus.ultreia.gen.mill.MPBMillPathPalette;
import com.jubitus.ultreia.gen.mill.MillVillageIndex;
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

    private BridgeFeasibility() {}

    public static BridgeSpan tryCreate(WorldServer world,
                                       TerrainSampler sampler,
                                       BlockPos start,
                                       BlockPos end,
                                       boolean micro) {
        return evaluate(world, sampler, start, end, micro).span;
    }

    public static String debugTryCreateReason(WorldServer world,
                                              TerrainSampler sampler,
                                              BlockPos start,
                                              BlockPos end,
                                              boolean micro) {
        return evaluate(world, sampler, start, end, micro).reason;
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

    private static BridgeEval evaluate(WorldServer world,
                                       TerrainSampler sampler,
                                       BlockPos start,
                                       BlockPos end,
                                       boolean micro) {
        if (!MPBConfig.BRIDGES.enabled) return fail("bridges disabled");
        if (!MPBConfig.BRIDGE_AWARE_PATHFINDING.enabled) return fail("bridge-aware pathfinding disabled");
        if (start == null || end == null) return fail("start/end null");
        if (sameXZ(start, end)) return fail("same xz");

        TerrainSampler.Sample startSample = sampler.sample(start.getX(), start.getZ());
        TerrainSampler.Sample endSample = sampler.sample(end.getX(), end.getZ());
        if (startSample == null || endSample == null) return fail("start/end sample null");

        if (!isValidBridgeEndpoint(world, sampler, startSample)) {
            return fail("invalid start endpoint at (" + startSample.x + "," + startSample.z + ")"
                    + " water=" + startSample.isWater
                    + " ocean=" + startSample.isOcean
                    + " forbidden=" + sampler.describeForbidden(startSample, TerrainSampler.Mode.PATHFINDING));
        }
        if (!isValidBridgeEndpoint(world, sampler, endSample)) {
            return fail("invalid end endpoint at (" + endSample.x + "," + endSample.z + ")"
                    + " water=" + endSample.isWater
                    + " ocean=" + endSample.isOcean
                    + " forbidden=" + sampler.describeForbidden(endSample, TerrainSampler.Mode.PATHFINDING));
        }

        if (isTooNearSettlement(world, start, true)) return fail("start too near settlement");
        if (isTooNearSettlement(world, end, true)) return fail("end too near settlement");

        List<BlockPos> rawLine = MPBConfig.BRIDGE_GEOMETRY.useRaster8ForBridges
                ? RasterLine8.raster(start.getX(), start.getZ(), end.getX(), end.getZ(), 0)
                : SupercoverLine.raster(start.getX(), start.getZ(), end.getX(), end.getZ(), 0);

        if (rawLine == null || rawLine.size() < 2) return fail("raw bridge line too short");

        TrimmedBridgeCandidate trimmed = trimBridgeEndBeforeTargetRoad(world, sampler, start, end, rawLine);
        if (trimmed == null) return fail("trimBridgeEndBeforeTargetRoad failed");

        end = trimmed.end;
        List<BlockPos> line = trimmed.line;

        startSample = sampler.sample(start.getX(), start.getZ());
        endSample = sampler.sample(end.getX(), end.getZ());
        if (startSample == null || endSample == null) return fail("trimmed start/end sample null");

        if (!isValidBridgeEndpoint(world, sampler, startSample)) {
            return fail("trimmed start endpoint invalid at (" + startSample.x + "," + startSample.z + ")");
        }
        if (!isValidBridgeEndpoint(world, sampler, endSample)) {
            return fail("trimmed end endpoint invalid at (" + endSample.x + "," + endSample.z + ")");
        }

        int len = Math.max(0, line.size() - 1);
        int minLen = Math.max(2, MPBConfig.BRIDGE_AWARE_PATHFINDING.minBridgeLenBlocks);
        int maxLen = Math.min(
                Math.max(minLen, MPBConfig.BRIDGES.maxBridgeLenBlocks),
                Math.max(minLen, MPBConfig.BRIDGE_AWARE_PATHFINDING.maxProbeLenBlocks)
        );
        if (len < minLen || len > maxLen) {
            return fail("bridge length out of range len=" + len + " min=" + minLen + " max=" + maxLen);
        }

        int startHalf = BridgeGeometry.naturalSurfaceHalfY(startSample.surfaceY);
        int endHalf = BridgeGeometry.naturalSurfaceHalfY(endSample.surfaceY);

        int maxDeltaHalf = BridgeGeometry.maxBridgeDeltaHalfForLen(len);
        if (Math.abs(endHalf - startHalf) > maxDeltaHalf) {
            return fail("endpoint delta too high startHalf=" + startHalf + " endHalf=" + endHalf + " max=" + maxDeltaHalf);
        }

        int[] deckHalf = BridgeGeometry.buildDeckHalfProfile(startHalf, endHalf, line, sampler);

        final int minGroundGap = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.minGroundClearanceBelowDeck);
        final int minWaterGap = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.minWaterClearanceBelowDeck);
        final int clearAbove = Math.max(2, MPBConfig.BRIDGE_AWARE_PATHFINDING.walkClearanceAboveDeck);
        final int interiorMinGapRun = Math.max(2, MPBConfig.BRIDGE_AWARE_PATHFINDING.minMeaningfulGapRun);
        final int interiorMinWaterRun = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.minWaterRunBlocks);

        final int meaningfulGroundGap = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.meaningfulGroundClearanceBlocks);
        final int meaningfulWaterGap = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.meaningfulWaterClearanceBlocks);
        final int minMeaningfulPercent = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.minMeaningfulClearancePercent);
        final int endpointGrace = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.endpointGraceCells);
        final int exitMinClearance = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.exitMinClearanceBlocks);
        final int exitMinClearanceCells = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.exitMinClearanceCheckCells);
        final int sideRadius = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.sideClearanceRadius);
        final int minUnderRoadClearance = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.underBridgeRoadMinClearanceBlocks);

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
            if (isTooNearSettlement(world, p, false)) {
                return fail("bridge crosses settlement zone at i=" + i + " pos=(" + p.getX() + "," + p.getZ() + ")");
            }

            BridgeGeometry.SlabPlacement slab = BridgeGeometry.slabPlacementForSurfaceHalfY(deckHalf[i]);
            int deckSurfaceY = BridgeGeometry.surfaceYFromHalf(deckHalf[i]);
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

                if (gap >= Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.longBridgeRequiredAverageClearance)) {
                    strongGapCells++;
                }
                if (gap >= Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.longBridgeRequiredAverageClearance) + 2) {
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

            if (!walkVolumePassable(world, p.getX(), p.getZ(), deckBlockY + 1, deckBlockY + clearAbove, endpoint)) {
                return fail("walk volume blocked at i=" + i + " pos=(" + p.getX() + "," + p.getZ() + ")");
            }

            if (!isBridgeColumnAcceptable(world, sampler, p.getX(), p.getZ(), deckSurfaceY, endpoint)) {
                return fail("bridge column unacceptable at i=" + i + " pos=(" + p.getX() + "," + p.getZ() + ")");
            }

            if (!checkSideClearance(world, sampler, line, i, deckHalf[i], clearAbove, endpoint, sideRadius, minUnderRoadClearance)) {
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
            int maxWaterLen = Math.max(4, MPBConfig.BRIDGE_AWARE_PATHFINDING.maxWaterBridgeLenBlocks);
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
            int maxLeadIn = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.maxWaterBridgeLeadInBlocks);
            int maxLeadOut = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.maxWaterBridgeLeadOutBlocks);

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

                int deckSurfaceY = BridgeGeometry.surfaceYFromHalf(deckHalf[i]);
                int gap = deckSurfaceY - sample.surfaceY;
                if (gap < exitMinClearance) {
                    return fail("exit clearance too low at i=" + i + " gap=" + gap + " need=" + exitMinClearance);
                }
            }
        }

        int longBridgeLen = Math.max(2, MPBConfig.BRIDGE_AWARE_PATHFINDING.longBridgeMinLenForExtraClearance);
        if (!waterDominant && len >= longBridgeLen) {
            int requiredAvgGap = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.longBridgeRequiredAverageClearance);
            int requiredMaxGap = Math.max(requiredAvgGap, MPBConfig.BRIDGE_AWARE_PATHFINDING.longBridgeRequiredMaxClearance);

            int avgGap = countGap <= 0 ? 0 : (int) Math.round((double) sumGap / (double) countGap);

            if (avgGap < requiredAvgGap) return fail("long bridge avg gap too low avg=" + avgGap + " need=" + requiredAvgGap);
            if (maxGapSeen < requiredMaxGap) return fail("long bridge max gap too low max=" + maxGapSeen + " need=" + requiredMaxGap);

            int strongPct = countGap <= 0 ? 0 : (int) Math.floor((100.0 * (double) strongGapCells) / (double) countGap);
            int veryStrongPct = countGap <= 0 ? 0 : (int) Math.floor((100.0 * (double) veryStrongGapCells) / (double) countGap);

            if (strongPct < 80) return fail("long bridge strongPct too low: " + strongPct);
            if (veryStrongPct < 35) return fail("long bridge veryStrongPct too low: " + veryStrongPct);
        }

        int forwardLook = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.requiredForwardExitBlocks);
        if (!exitDirectionStaysForward(start, end, forwardLook, sampler)) {
            return fail("exitDirectionStaysForward failed");
        }

        int exitLookAhead = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.requiredExitCorridorBlocks);
        if (!exitCorridorIsBuildable(world, sampler, start, end, exitLookAhead)) {
            return fail("exitCorridorIsBuildable failed");
        }

        return ok(new BridgeSpan(start, end, line, startHalf, endHalf, micro, waterDominant, validWaterBridge));
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
    private static TrimmedBridgeCandidate trimBridgeEndBeforeTargetRoad(WorldServer world,
                                                                        TerrainSampler sampler,
                                                                        BlockPos start,
                                                                        BlockPos end,
                                                                        List<BlockPos> rawLine) {
        if (rawLine == null || rawLine.size() < 2) return null;

        int roadSearch = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeRoadApproachMaxBlocks);
        int minApproach = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeRoadApproachMinBlocks);

        BlockPos targetRoad = sampler.findNearestExistingRoad(end.getX(), end.getZ(), roadSearch);
        if (targetRoad == null) {
            return new TrimmedBridgeCandidate(end, rawLine);
        }

        int roadHitIdx = -1;
        int roadHitDistSq = Integer.MAX_VALUE;

        int scanFrom = Math.max(1, rawLine.size() - 1 - (roadSearch + minApproach + 4));

        for (int i = scanFrom; i < rawLine.size() - 1; i++) {
            BlockPos p = rawLine.get(i);

            if (!sampler.isExistingRoadXZ(p.getX(), p.getZ())) {
                continue;
            }

            int d2 = distSq2D(p.getX(), p.getZ(), targetRoad.getX(), targetRoad.getZ());
            if (d2 < roadHitDistSq) {
                roadHitDistSq = d2;
                roadHitIdx = i;
            }
        }

        if (roadHitIdx < 0) {
            return new TrimmedBridgeCandidate(end, rawLine);
        }

        int minBridgeLen = Math.max(2, MPBConfig.BRIDGE_AWARE_PATHFINDING.minBridgeLenBlocks);
        int searchStart = Math.max(1, roadHitIdx - Math.max(roadSearch + minApproach + 4, 8));
        int searchEnd = Math.max(searchStart, roadHitIdx - minApproach);

        int bestIdx = -1;
        int bestScore = Integer.MAX_VALUE;

        for (int i = searchEnd; i >= searchStart; i--) {
            BlockPos p = rawLine.get(i);

            if (i < minBridgeLen) continue;
            if (sampler.isExistingRoadXZ(p.getX(), p.getZ())) continue;
            if (!sampler.isNaturalLandEndpoint(p.getX(), p.getZ())) continue;

            int d2Road = distSq2D(p.getX(), p.getZ(), targetRoad.getX(), targetRoad.getZ());
            if (d2Road < minApproach * minApproach) continue;

            TerrainSampler.Sample s = sampler.sample(p.getX(), p.getZ());
            if (s == null) continue;

            int score = d2Road + (roadHitIdx - i) * 6;
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

    private static boolean isValidBridgeEndpoint(WorldServer world,
                                                 TerrainSampler sampler,
                                                 TerrainSampler.Sample sample) {
        if (sample.isWater || sample.isOcean) return false;
        if (sampler.forbidden(sample, TerrainSampler.Mode.PATHFINDING)) return false;
        if (sampler.isProtectedNoPaint(sample.x, sample.z)) return false;
        if (sampler.isExistingRoadXZ(sample.x, sample.z)) return false;

        BlockPos top = new BlockPos(sample.x, sample.surfaceY, sample.z);
        IBlockState ground = world.getBlockState(top);
        if (!isNaturalGroundOnly(ground)) return false;

        IBlockState up1 = world.getBlockState(top.up());
        IBlockState up2 = world.getBlockState(top.up(2));

        if (!isBridgeClearable(world, top.up(), up1, true)) return false;
        if (!isBridgeClearable(world, top.up(2), up2, true)) return false;

        return true;
    }

    private static boolean walkVolumePassable(WorldServer world,
                                              int x,
                                              int z,
                                              int yFrom,
                                              int yTo,
                                              boolean allowDeckSlabAtEndpoint) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int y = yFrom; y <= yTo; y++) {
            p.setPos(x, y, z);
            IBlockState st = world.getBlockState(p);

            if (st == null) continue;
            if (st.getMaterial() == Material.AIR) continue;
            if (world.getTileEntity(p) != null) return false;

            if (isBridgeClearable(world, p, st, allowDeckSlabAtEndpoint)) continue;
            return false;
        }

        return true;
    }

    private static boolean checkSideClearance(WorldServer world,
                                              TerrainSampler sampler,
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

        BridgeGeometry.SlabPlacement slab = BridgeGeometry.slabPlacementForSurfaceHalfY(deckHalfY);
        int deckSurfaceY = BridgeGeometry.surfaceYFromHalf(deckHalfY);
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

                if (!walkVolumePassable(world, x, z, deckBlockY + 1, deckBlockY + clearAbove, endpoint)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean isBridgeColumnAcceptable(WorldServer world,
                                                    TerrainSampler sampler,
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

            BlockPos top = new BlockPos(x, sample.surfaceY, z);
            IBlockState ground = world.getBlockState(top);
            return isNaturalGroundOnly(ground);
        }

        return sample.surfaceY <= deckSurfaceY;
    }

    private static boolean isBridgeClearable(WorldServer world,
                                             BlockPos pos,
                                             IBlockState st,
                                             boolean allowTopSlab) {
        Material m = st.getMaterial();
        Block b = st.getBlock();

        if (m == Material.AIR) return true;
        if (m == Material.PLANTS || m == Material.VINE) return true;
        if (m.isReplaceable()) return true;
        if (b == Blocks.SNOW_LAYER) return true;
        if (b.isLeaves(st, world, pos) || m == Material.LEAVES) return true;

        if (MPBConfig.PAINTING.clearTrees && MPBConfig.PAINTING.clearTreeTrunks) {
            if (b.isWood(world, pos) || m == Material.WOOD) return true;
        }

        if (allowTopSlab && b == Blocks.WOODEN_SLAB) return true;

        return false;
    }

    private static boolean isTooNearSettlement(WorldServer world, BlockPos pos, boolean endpoint) {
        int villageRadius = endpoint
                ? Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.endpointVillageRadius)
                : Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.crossVillageRadius);

        int loneRadius = endpoint
                ? Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.endpointLoneBuildingRadius)
                : Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.crossLoneBuildingRadius);

        if (villageRadius > 0 && MillVillageIndex.isNearVillage(world, pos, villageRadius)) return true;
        if (loneRadius > 0 && MillVillageIndex.isNearAnySettlement(world, pos, loneRadius)) return true;

        return false;
    }

    private static boolean exitDirectionStaysForward(BlockPos start,
                                                     BlockPos end,
                                                     int lookAhead,
                                                     TerrainSampler sampler) {
        if (lookAhead <= 0) return true;

        int dx = Integer.signum(end.getX() - start.getX());
        int dz = Integer.signum(end.getZ() - start.getZ());

        if (dx == 0 && dz == 0) return false;

        int x = end.getX();
        int z = end.getZ();

        for (int i = 1; i <= lookAhead; i++) {
            x += dx;
            z += dz;

            TerrainSampler.Sample sample = sampler.sample(x, z);
            if (sample == null) return false;
            if (sample.isWater || sample.isOcean) return false;
            if (sampler.forbidden(sample, TerrainSampler.Mode.PATHFINDING)) return false;
        }

        return true;
    }

    private static boolean exitCorridorIsBuildable(WorldServer world,
                                                   TerrainSampler sampler,
                                                   BlockPos start,
                                                   BlockPos end,
                                                   int lookAhead) {
        if (lookAhead <= 0) return true;

        int dx = Integer.signum(end.getX() - start.getX());
        int dz = Integer.signum(end.getZ() - start.getZ());

        if (dx == 0 && dz == 0) return false;

        int maxDy = Math.max(1, MPBConfig.PATHFINDING.maxYDeltaPerStep);

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

            BlockPos top = new BlockPos(x, sample.surfaceY, z);
            if (!isNaturalOrRoad(world.getBlockState(top))) return false;

            prev = sample;
        }

        return true;
    }

    private static boolean isNaturalOrRoad(IBlockState state) {
        if (state == null) return false;
        return MPBGround.isRoadReplaceableGround(state) || MPBMillPathPalette.isMillPath(state.getBlock());
    }

    private static boolean isNaturalGroundOnly(IBlockState state) {
        if (state == null) return false;
        if (MPBMillPathPalette.isMillPath(state.getBlock())) return false;
        return MPBGround.isRoadReplaceableGround(state);
    }

    private static boolean sameXZ(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

    private static int distSq2D(int x1, int z1, int x2, int z2) {
        int dx = x1 - x2;
        int dz = z1 - z2;
        return dx * dx + dz * dz;
    }
}
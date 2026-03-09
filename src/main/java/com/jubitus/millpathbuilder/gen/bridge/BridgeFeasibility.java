package com.jubitus.millpathbuilder.gen.bridge;

import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.MPBGround;
import com.jubitus.millpathbuilder.gen.TerrainSampler;
import com.jubitus.millpathbuilder.gen.mill.MPBMillPathPalette;
import com.jubitus.millpathbuilder.gen.mill.MillVillageIndex;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockWoodSlab;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.List;


public final class BridgeFeasibility {

    private BridgeFeasibility() {}

    public static BridgeSpan tryCreate(WorldServer world,
                                       TerrainSampler sampler,
                                       BlockPos start,
                                       BlockPos end,
                                       boolean micro) {
        if (!MPBConfig.BRIDGES.enabled) return null;
        if (!MPBConfig.BRIDGE_AWARE_PATHFINDING.enabled) return null;
        if (start == null || end == null) return null;
        if (start.getX() == end.getX() && start.getZ() == end.getZ()) return null;

        TerrainSampler.Sample ss = sampler.sample(start.getX(), start.getZ());
        TerrainSampler.Sample es = sampler.sample(end.getX(), end.getZ());
        if (ss == null || es == null) return null;

        if (!isValidBridgeEndpoint(world, sampler, ss)) return null;
        if (!isValidBridgeEndpoint(world, sampler, es)) return null;

        if (isTooNearSettlement(world, start, true)) return null;
        if (isTooNearSettlement(world, end, true)) return null;

        List<BlockPos> line = MPBConfig.BRIDGE_GEOMETRY.useRaster8ForBridges
                ? RasterLine8.raster(start.getX(), start.getZ(), end.getX(), end.getZ(), 0)
                : SupercoverLine.raster(start.getX(), start.getZ(), end.getX(), end.getZ(), 0);

        if (line == null || line.size() < 2) return null;

        int len = Math.max(0, line.size() - 1);
        int minLen = Math.max(2, MPBConfig.BRIDGE_AWARE_PATHFINDING.minBridgeLenBlocks);
        int maxLen = Math.min(
                Math.max(minLen, MPBConfig.BRIDGES.maxBridgeLenBlocks),
                Math.max(minLen, MPBConfig.BRIDGE_AWARE_PATHFINDING.maxProbeLenBlocks)
        );

        if (len < minLen || len > maxLen) return null;

        int startHalf = ss.surfaceY * 2 + 3;
        int endHalf = es.surfaceY * 2 + 3;

        int maxDYHalf = Math.max(0, MPBConfig.BRIDGES.maxBridgeDeltaY) * 2;
        if (Math.abs(endHalf - startHalf) > maxDYHalf) return null;

        int[] deckHalf = buildDeckHalfProfile(startHalf, endHalf, line, sampler);

        int minGroundGap = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.minGroundClearanceBelowDeck);
        int minWaterGap = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.minWaterClearanceBelowDeck);
        int clearAbove = Math.max(2, MPBConfig.BRIDGE_AWARE_PATHFINDING.walkClearanceAboveDeck);
        int interiorMinGapRun = Math.max(2, MPBConfig.BRIDGE_AWARE_PATHFINDING.minMeaningfulGapRun);
        int interiorMinWaterRun = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.minWaterRunBlocks);

        int meaningfulGroundGap = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.meaningfulGroundClearanceBlocks);
        int meaningfulWaterGap = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.meaningfulWaterClearanceBlocks);
        int minMeaningfulPercent = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.minMeaningfulClearancePercent);
        int endpointGrace = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.endpointGraceCells);
        int exitMinClearance = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.exitMinClearanceBlocks);
        int exitMinClearanceCells = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.exitMinClearanceCheckCells);

        int goodGroundGapRun = 0;
        int bestGroundGapRun = 0;

        int goodWaterRun = 0;
        int bestWaterRun = 0;

        int firstWaterInterior = -1;
        int lastWaterInterior = -1;

        int meaningfulInteriorCells = 0;
        int countedInteriorCells = 0;

        final int sideRadius = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.sideClearanceRadius);

        for (int i = 0; i < line.size(); i++) {
            BlockPos p = line.get(i);
            TerrainSampler.Sample sp = sampler.sample(p.getX(), p.getZ());
            if (sp == null) return null;

            if (sampler.isProtectedNoPaint(p.getX(), p.getZ())) return null;
            if (isTooNearSettlement(world, p, false)) return null;

            SlabPlacement slab = slabPlacementForSurfaceHalfY(deckHalf[i]);
            int deckSurfaceY = surfaceYFromHalf(deckHalf[i]);
            int deckBlockY = slab.blockY;

            boolean endpoint = (i == 0 || i == line.size() - 1);

            if (!endpoint) {
                if (sp.surfaceY > deckSurfaceY) {
                    return null;
                }
            }

            int gap = deckSurfaceY - sp.surfaceY;

            boolean bridgeGroundGap = (!sp.isWater) && gap >= minGroundGap;
            boolean bridgeWaterGap = sp.isWater && !sp.isOcean && gap >= minWaterGap;

            boolean globallyMeaningfulGroundGap = (!sp.isWater) && gap >= meaningfulGroundGap;
            boolean globallyMeaningfulWaterGap = sp.isWater && !sp.isOcean && gap >= meaningfulWaterGap;

            if (!endpoint) {
                if (bridgeGroundGap) {
                    goodGroundGapRun++;
                    if (goodGroundGapRun > bestGroundGapRun) bestGroundGapRun = goodGroundGapRun;
                } else {
                    goodGroundGapRun = 0;
                }

                if (bridgeWaterGap) {
                    if (firstWaterInterior < 0) firstWaterInterior = i;
                    lastWaterInterior = i;

                    goodWaterRun++;
                    if (goodWaterRun > bestWaterRun) bestWaterRun = goodWaterRun;
                } else {
                    goodWaterRun = 0;
                }

                boolean ignoredForGlobalRatio = (i <= endpointGrace) || (i >= line.size() - 1 - endpointGrace);
                if (!ignoredForGlobalRatio) {
                    countedInteriorCells++;
                    if (globallyMeaningfulGroundGap || globallyMeaningfulWaterGap) {
                        meaningfulInteriorCells++;
                    }
                }
            }

            if (!walkVolumePassable(world, p.getX(), p.getZ(), deckBlockY + 1, deckBlockY + clearAbove, endpoint)) {
                return null;
            }

            if (!landingColumnNatural(world, sampler, p.getX(), p.getZ(), deckSurfaceY, endpoint)) {
                return null;
            }

            if (!checkSideClearance(world, sampler, line, i, deckHalf[i], clearAbove, endpoint, sideRadius)) {
                return null;
            }
        }

        boolean validValleyLikeGap = bestGroundGapRun >= interiorMinGapRun;
        boolean validWaterRun = bestWaterRun >= interiorMinWaterRun;

        if (!validValleyLikeGap && !validWaterRun) return null;

        if (countedInteriorCells > 0 && minMeaningfulPercent > 0) {
            int pct = (int) Math.floor((100.0 * (double) meaningfulInteriorCells) / (double) countedInteriorCells);
            if (pct < minMeaningfulPercent) return null;
        }

        if (exitMinClearance > 0 && exitMinClearanceCells > 0) {
            int endStart = Math.max(1, line.size() - 1 - exitMinClearanceCells);
            for (int i = endStart; i < line.size() - 1; i++) {
                BlockPos p = line.get(i);
                TerrainSampler.Sample sp = sampler.sample(p.getX(), p.getZ());
                if (sp == null) return null;

                int deckSurfaceY = surfaceYFromHalf(deckHalf[i]);
                int gap = deckSurfaceY - sp.surfaceY;

                if (gap < exitMinClearance) return null;
            }
        }

        if (validWaterRun) {
            int maxLeadIn = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.maxWaterBridgeLeadInBlocks);
            int maxLeadOut = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.maxWaterBridgeLeadOutBlocks);

            if (firstWaterInterior < 0 || lastWaterInterior < 0) return null;

            int dryLeadIn = Math.max(0, firstWaterInterior - 1);
            int dryLeadOut = Math.max(0, (line.size() - 2) - lastWaterInterior);

            if (dryLeadIn > maxLeadIn) return null;
            if (dryLeadOut > maxLeadOut) return null;
        }

        int longBridgeLen = Math.max(2, MPBConfig.BRIDGE_AWARE_PATHFINDING.longBridgeMinLenForExtraClearance);
        if (len >= longBridgeLen) {
            int requiredAvgGap = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.longBridgeRequiredAverageClearance);
            int requiredMaxGap = Math.max(requiredAvgGap, MPBConfig.BRIDGE_AWARE_PATHFINDING.longBridgeRequiredMaxClearance);

            int sumGap = 0;
            int countGap = 0;
            int maxGapSeen = 0;

            for (int i = 1; i < line.size() - 1; i++) {
                BlockPos p = line.get(i);
                TerrainSampler.Sample sp = sampler.sample(p.getX(), p.getZ());
                if (sp == null) return null;

                int deckSurfaceY = surfaceYFromHalf(deckHalf[i]);
                int gap2 = Math.max(0, deckSurfaceY - sp.surfaceY);

                sumGap += gap2;
                countGap++;
                if (gap2 > maxGapSeen) maxGapSeen = gap2;
            }

            int avgGap = countGap <= 0 ? 0 : (int) Math.round((double) sumGap / (double) countGap);

            if (avgGap < requiredAvgGap) return null;
            if (maxGapSeen < requiredMaxGap) return null;
        }

        int forwardLook = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.requiredForwardExitBlocks);
        if (!exitDirectionStaysForward(start, end, forwardLook, sampler)) {
            return null;
        }

        int exitLookAhead = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.requiredExitCorridorBlocks);
        if (!exitCorridorIsBuildable(world, sampler, start, end, exitLookAhead)) {
            return null;
        }

        return new BridgeSpan(start, end, line, startHalf, endHalf, micro);
    }

    private static boolean isValidBridgeEndpoint(WorldServer world, TerrainSampler sampler, TerrainSampler.Sample s) {
        if (s.isWater || s.isOcean) return false;
        if (sampler.forbidden(s, TerrainSampler.Mode.PATHFINDING)) return false;
        if (sampler.isProtectedNoPaint(s.x, s.z)) return false;

        BlockPos top = new BlockPos(s.x, s.surfaceY, s.z);
        IBlockState ground = world.getBlockState(top);
        if (!MPBGround.isRoadReplaceableGround(ground) && !MPBMillPathPalette.isMillPath(ground.getBlock())) return false;

        IBlockState up1 = world.getBlockState(top.up());
        IBlockState up2 = world.getBlockState(top.up(2));
        if (!isBridgeClearable(world, top.up(), up1, true)) return false;
        if (!isBridgeClearable(world, top.up(2), up2, true)) return false;

        return true;
    }

    private static boolean walkVolumePassable(WorldServer world, int x, int z, int yFrom, int yTo, boolean allowEndpointSolidAtDeck) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int y = yFrom; y <= yTo; y++) {
            p.setPos(x, y, z);
            IBlockState st = world.getBlockState(p);

            if (st == null) continue;
            if (st.getMaterial() == Material.AIR) continue;
            if (world.getTileEntity(p) != null) return false;

            if (isBridgeClearable(world, p, st, allowEndpointSolidAtDeck)) continue;

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
                                              int sideRadius) {
        if (sideRadius <= 0) return true;
        if (idx < 0 || idx >= line.size()) return false;

        BlockPos c = line.get(idx);
        Dir2 dir = localDir(line, idx);
        Dir2 perp = dir.perp();

        SlabPlacement slab = slabPlacementForSurfaceHalfY(deckHalfY);
        int deckSurfaceY = surfaceYFromHalf(deckHalfY);
        int deckBlockY = slab.blockY;

        for (int off = 1; off <= sideRadius; off++) {
            for (int s = -1; s <= 1; s += 2) {
                int x = c.getX() + perp.dx * off * s;
                int z = c.getZ() + perp.dz * off * s;

                TerrainSampler.Sample side = sampler.sample(x, z);
                if (side == null) return false;
                if (sampler.isProtectedNoPaint(x, z)) return false;

                if (!endpoint && side.surfaceY > deckSurfaceY) {
                    return false;
                }

                if (!walkVolumePassable(world, x, z, deckBlockY + 1, deckBlockY + clearAbove, endpoint)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean landingColumnNatural(WorldServer world, TerrainSampler sampler, int x, int z, int deckSurfaceY, boolean endpoint) {
        TerrainSampler.Sample s = sampler.sample(x, z);
        if (s == null) return false;

        if (endpoint) {
            if (s.isWater || s.isOcean) return false;
            BlockPos top = new BlockPos(x, s.surfaceY, z);
            IBlockState ground = world.getBlockState(top);
            return MPBGround.isRoadReplaceableGround(ground) || MPBMillPathPalette.isMillPath(ground.getBlock());
        }

        return true;
    }

    private static boolean isBridgeClearable(WorldServer world, BlockPos pos, IBlockState st, boolean allowTopSlab) {
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

    private static boolean isTooNearSettlement(WorldServer world, BlockPos p, boolean endpoint) {
        int villageR = endpoint
                ? Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.endpointVillageRadius)
                : Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.crossVillageRadius);

        int loneR = endpoint
                ? Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.endpointLoneBuildingRadius)
                : Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.crossLoneBuildingRadius);

        if (villageR > 0 && MillVillageIndex.isNearVillage(world, p, villageR)) return true;
        if (loneR > 0 && MillVillageIndex.isNearAnySettlement(world, p, loneR)) return true;

        return false;
    }

    private static int[] buildDeckHalfProfile(int startHalf, int endHalf, List<BlockPos> line, TerrainSampler sampler) {
        int n = line.size();
        int[] ideal = new int[n];

        for (int i = 0; i < n; i++) {
            double t = (n <= 1) ? 0.0 : ((double) i / (double) (n - 1));
            ideal[i] = (int) Math.round((1.0 - t) * (double) startHalf + t * (double) endHalf);
        }

        int len = Math.max(0, n - 1);
        int crown = 0;

        if (len >= Math.max(2, MPBConfig.BRIDGES.bridgeCrownMinLenBlocks)) {
            crown = Math.max(0, MPBConfig.BRIDGES.bridgeCrownHalfBlocks);

            if (MPBConfig.BRIDGES.extraWaterBridgeCrown && hasMeaningfulWaterInterior(line, sampler)) {
                crown += 1;
            }
        }

        if (crown > 0 && n >= 3) {
            for (int i = 1; i < n - 1; i++) {
                double t = (double) i / (double) (n - 1);
                double arch = 1.0 - Math.abs(2.0 * t - 1.0);
                ideal[i] += (int) Math.round((double) crown * arch);
            }
        }

        int maxStepHalf = Math.max(0, MPBConfig.BRIDGES.maxDeckDeltaHalfPerBlock);
        if (maxStepHalf <= 0) {
            return ideal;
        }

        int[] out = new int[n];
        out[0] = ideal[0];

        for (int i = 1; i < n; i++) {
            int want = ideal[i];
            int prev = out[i - 1];
            int d = want - prev;
            if (d > maxStepHalf) want = prev + maxStepHalf;
            else if (d < -maxStepHalf) want = prev - maxStepHalf;
            out[i] = want;
        }

        for (int i = n - 2; i >= 0; i--) {
            int want = out[i];
            int next = out[i + 1];
            int d = want - next;
            if (d > maxStepHalf) want = next + maxStepHalf;
            else if (d < -maxStepHalf) want = next - maxStepHalf;
            out[i] = want;
        }

        return out;
    }

    private static boolean hasMeaningfulWaterInterior(List<BlockPos> line, TerrainSampler sampler) {
        if (line == null || line.size() < 3 || sampler == null) return false;

        int run = 0;
        int best = 0;

        for (int i = 1; i < line.size() - 1; i++) {
            BlockPos p = line.get(i);
            TerrainSampler.Sample s = sampler.sample(p.getX(), p.getZ());

            boolean water = s != null && s.isWater && !s.isOcean;
            if (water) {
                run++;
                if (run > best) best = run;
            } else {
                run = 0;
            }
        }

        return best >= Math.max(2, MPBConfig.BRIDGE_AWARE_PATHFINDING.minWaterRunBlocks);
    }

    private static boolean hasMeaningfulWaterInterior(List<BlockPos> line) {
        return false;
    }

    private static int surfaceYFromHalf(int surfaceHalfY) {
        return (surfaceHalfY - 1) / 2;
    }

    private static final class SlabPlacement {
        final int blockY;
        final boolean top;

        SlabPlacement(int blockY, boolean top) {
            this.blockY = blockY;
            this.top = top;
        }
    }

    private static SlabPlacement slabPlacementForSurfaceHalfY(int surfaceHalfY) {
        if ((surfaceHalfY & 1) == 1) {
            int y = (surfaceHalfY - 1) / 2;
            return new SlabPlacement(y, false);
        } else {
            int y = (surfaceHalfY / 2) - 1;
            return new SlabPlacement(y, true);
        }
    }

    private static boolean exitDirectionStaysForward(BlockPos start,
                                                     BlockPos end,
                                                     int lookAhead,
                                                     TerrainSampler sampler) {
        if (lookAhead <= 0) return true;

        int bdx = Integer.signum(end.getX() - start.getX());
        int bdz = Integer.signum(end.getZ() - start.getZ());

        if (bdx == 0 && bdz == 0) return false;

        int x = end.getX();
        int z = end.getZ();

        for (int i = 1; i <= lookAhead; i++) {
            int nx = x + bdx;
            int nz = z + bdz;

            TerrainSampler.Sample s = sampler.sample(nx, nz);
            if (s == null) return false;
            if (s.isWater || s.isOcean) return false;
            if (sampler.forbidden(s, TerrainSampler.Mode.PATHFINDING)) return false;

            x = nx;
            z = nz;
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

        TerrainSampler.Sample prev = sampler.sample(end.getX(), end.getZ());
        if (prev == null) return false;

        int x = end.getX();
        int z = end.getZ();

        for (int i = 1; i <= lookAhead; i++) {
            x += dx;
            z += dz;

            TerrainSampler.Sample s = sampler.sample(x, z);
            if (s == null) return false;
            if (s.isWater || s.isOcean) return false;
            if (sampler.forbidden(s, TerrainSampler.Mode.PATHFINDING)) return false;
            if (sampler.isProtectedNoPaint(x, z)) return false;

            int dy = Math.abs(s.surfaceY - prev.surfaceY);
            int maxDy = Math.max(1, MPBConfig.PATHFINDING.maxYDeltaPerStep);

            if (dy > maxDy) return false;

            BlockPos top = new BlockPos(x, s.surfaceY, z);
            if (!isNaturalOrRoad(world, top)) return false;

            prev = s;
        }

        return true;
    }

    private static boolean isNaturalOrRoad(WorldServer world, BlockPos top) {
        IBlockState st = world.getBlockState(top);
        if (st == null) return false;
        return MPBGround.isRoadReplaceableGround(st) || MPBMillPathPalette.isMillPath(st.getBlock());
    }

    private static final class Dir2 {
        final int dx;
        final int dz;

        Dir2(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        Dir2 perp() {
            return new Dir2(-dz, dx);
        }
    }

    private static Dir2 localDir(List<BlockPos> line, int idx) {
        if (line == null || line.isEmpty()) return new Dir2(1, 0);
        if (line.size() == 1) return new Dir2(1, 0);

        BlockPos prev = line.get(Math.max(0, idx - 1));
        BlockPos next = line.get(Math.min(line.size() - 1, idx + 1));

        int dx = Integer.signum(next.getX() - prev.getX());
        int dz = Integer.signum(next.getZ() - prev.getZ());

        if (dx == 0 && dz == 0) {
            BlockPos cur = line.get(idx);
            if (idx > 0) {
                BlockPos p = line.get(idx - 1);
                dx = Integer.signum(cur.getX() - p.getX());
                dz = Integer.signum(cur.getZ() - p.getZ());
            } else if (idx + 1 < line.size()) {
                BlockPos n = line.get(idx + 1);
                dx = Integer.signum(n.getX() - cur.getX());
                dz = Integer.signum(n.getZ() - cur.getZ());
            }
        }

        if (dx == 0 && dz == 0) dx = 1;
        return new Dir2(dx, dz);
    }
}
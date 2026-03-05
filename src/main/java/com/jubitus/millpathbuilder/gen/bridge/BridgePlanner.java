package com.jubitus.millpathbuilder.gen.bridge;

import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.TerrainSampler;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.List;

public final class BridgePlanner {

    private final WorldServer world;
    private final TerrainSampler sampler;

    private int cooldownLeft = 0;

    public BridgePlanner(WorldServer world, TerrainSampler sampler) {
        this.world = world;
        this.sampler = sampler;
    }

    public void tickAdvanceOneBlock() {
        if (cooldownLeft > 0) cooldownLeft--;
    }

    public BridgeSpan tryPlanFromPath(List<BlockPos> path, int idx) {
        if (!MPBConfig.BRIDGES.enabled) return null;
        if (path == null || path.isEmpty()) return null;
        if (idx < 1 || idx >= path.size() - 1) return null;

        BlockPos prev = path.get(idx - 1);
        BlockPos cur = path.get(idx);

        TerrainSampler.Sample sCur = sampler.sample(cur.getX(), cur.getZ());

        // direct obstacle bridging (water/blocked column): handles rivers/holes/canyons where the centerline is not paintable
        if (isHardObstacle(sCur)) {
            return planSimpleGapBridge(path, idx);
        }

        // valley bridging: optional
        if (MPBConfig.VALLEY_BRIDGING.enabled && cooldownLeft <= 0) {
            BridgeSpan valley = planValleyBridge(path, idx);
            if (valley != null) {
                cooldownLeft = Math.max(0, MPBConfig.VALLEY_BRIDGING.cooldownBlocks);
                return valley;
            }
        }

        return null;
    }

    // BridgePlanner.java
    private boolean isHardObstacle(TerrainSampler.Sample s) {
        if (s == null) return true;
        if (s.isOcean) return true;

        // Only water/ice triggers the "simple gap" bridge.
        // Mountains/walls must never trigger a bridge.
        return s.isWater;
    }

    private BridgeSpan planSimpleGapBridge(List<BlockPos> path, int idx) {
        int maxLen = Math.max(8, MPBConfig.BRIDGES.maxBridgeLenBlocks);

        int stableNeed = Math.max(1, MPBConfig.BRIDGES.exitStableBlocks);
        int maxOverhang = Math.max(0, MPBConfig.BRIDGES.maxOverhangAfterObstacle);

        BlockPos entry = path.get(idx - 1);

        int j = idx;
        int walked = 0;

        boolean sawWater = false;
        int lastWaterJ = -1;

        int stableRun = 0;
        int candidateExitJ = -1;

        while (j < path.size() && walked <= maxLen) {
            BlockPos p = path.get(j);
            TerrainSampler.Sample sp = sampler.sample(p.getX(), p.getZ());

            boolean water = (sp != null && sp.isWater && !sp.isOcean);

            if (water) {
                sawWater = true;
                lastWaterJ = j;
                stableRun = 0;
                candidateExitJ = -1;
            } else {
                // We only accept an exit if we actually crossed water.
                if (sawWater) {
                    // Exit must be on non-water and non-ocean.
                    if (sp == null || sp.isOcean || sp.isWater) {
                        stableRun = 0;
                        candidateExitJ = -1;
                    } else {
                        stableRun++;
                        if (candidateExitJ < 0) candidateExitJ = j;

                        if (stableRun >= stableNeed) {
                            int exitJ = candidateExitJ;

                            // No overhang beyond last water.
                            if (lastWaterJ >= 0) {
                                int maxExit = lastWaterJ + 1 + maxOverhang;
                                if (exitJ > maxExit) exitJ = maxExit;
                            }

                            // Exit must not immediately climb.
                            if (!exitApproachIsStable(path, exitJ)) {
                                stableRun = 0;
                                candidateExitJ = -1;
                            } else {
                                BlockPos exit = path.get(exitJ);

                                // Extra guard: don't accept exits that are far higher/lower than entry (prevents weird ramps).
                                TerrainSampler.Sample sEntry = sampler.sample(entry.getX(), entry.getZ());
                                TerrainSampler.Sample sExit = sampler.sample(exit.getX(), exit.getZ());
                                if (sEntry != null && sExit != null) {
                                    int dy = Math.abs(sExit.surfaceY - sEntry.surfaceY);
                                    if (dy > Math.max(2, MPBConfig.BRIDGES.maxBridgeDeltaY)) {
                                        stableRun = 0;
                                        candidateExitJ = -1;
                                    } else {
                                        return buildSpan(entry, exit, false);
                                    }
                                } else {
                                    return buildSpan(entry, exit, false);
                                }
                            }
                        }
                    }
                }
            }

            j++;
            walked++;
        }

        // If we never saw water, never build a bridge.
        return null;
    }

    private boolean exitApproachIsStable(List<BlockPos> path, int exitJ) {
        int look = Math.max(0, MPBConfig.BRIDGES.exitLookaheadBlocks);
        int maxRise = Math.max(0, MPBConfig.BRIDGES.exitMaxRiseBlocks);
        if (look <= 0) return true;
        if (exitJ < 0 || exitJ >= path.size()) return true;

        BlockPos exit = path.get(exitJ);
        TerrainSampler.Sample se = sampler.sample(exit.getX(), exit.getZ());
        if (se == null) return true;

        int baseY = se.surfaceY;
        int bestMax = baseY;

        int end = Math.min(path.size() - 1, exitJ + look);
        for (int k = exitJ; k <= end; k++) {
            BlockPos p = path.get(k);
            TerrainSampler.Sample s = sampler.sample(p.getX(), p.getZ());
            if (s == null) continue;
            if (s.isOcean) return false; // don't exit into ocean
            bestMax = Math.max(bestMax, s.surfaceY);
            if ((bestMax - baseY) > maxRise) return false;
        }
        return true;
    }

    private BridgeSpan planValleyBridge(List<BlockPos> path, int idx) {
        int look = Math.max(16, MPBConfig.VALLEY_BRIDGING.maxLookahead);
        int minLen = Math.max(8, MPBConfig.VALLEY_BRIDGING.minSpanLen);
        int minDepth = Math.max(2, MPBConfig.VALLEY_BRIDGING.minValleyDepth);
        int maxDev = Math.max(1, MPBConfig.VALLEY_BRIDGING.maxLateralDeviation);

        final int minVoidLen = Math.max(6, minLen / 2);
        final int minWaterLen = Math.max(6, minLen / 2);

        int maxOverhang = Math.max(0, MPBConfig.BRIDGES.maxOverhangAfterObstacle);
        int ridgeRise = Math.max(0, MPBConfig.VALLEY_BRIDGING.ridgeRiseBlocks);

        BlockPos a = path.get(idx);
        TerrainSampler.Sample sa = sampler.sample(a.getX(), a.getZ());
        if (sa == null) return null;
        if (sa.isOcean || sa.isWater) return null;

        int baseY = sa.surfaceY;

        int bestJ = -1;
        int bestLen = Integer.MAX_VALUE;

        for (int j = idx + minLen; j < path.size() && (j - idx) <= look; j++) {
            BlockPos b = path.get(j);
            TerrainSampler.Sample sb = sampler.sample(b.getX(), b.getZ());
            if (sb == null) continue;
            if (sb.isOcean) continue;
            if (sb.isWater) continue; // valley bridges are not for "water crossing" (simple gap bridge handles that)

            int dyEnds = Math.abs(sb.surfaceY - baseY);
            if (dyEnds > MPBConfig.BRIDGES.maxBridgeDeltaY) continue;

            List<BlockPos> line = MPBConfig.BRIDGE_GEOMETRY.useRaster8ForBridges
                    ? RasterLine8.raster(a.getX(), a.getZ(), b.getX(), b.getZ(), 0)
                    : SupercoverLine.raster(a.getX(), a.getZ(), b.getX(), b.getZ(), 0);

            int len = Math.max(0, line.size() - 1);
            if (len < minLen) continue;
            if (len > MPBConfig.BRIDGES.maxBridgeLenBlocks) continue;

            if (!pathStaysNearLine(path, idx, j, a, b, maxDev)) continue;

            int baselineGroundY = Math.max(sa.surfaceY, sb.surfaceY);

            // Reject "mountain crossing": if terrain rises above baseline too much anywhere between idx..j.
            if (ridgeRise > 0 && segmentHasRidge(path, idx, j, baselineGroundY, ridgeRise)) {
                continue;
            }

            int deepestDrop = 0;
            int voidRun = 0, voidRunMax = 0;
            int waterRun = 0, waterRunMax = 0;

            // Track last "valley" index on the path segment for overhang clamp.
            int lastBadPathIndex = -1;

            for (int k = idx; k <= j; k++) {
                BlockPos pk = path.get(k);
                TerrainSampler.Sample sk = sampler.sample(pk.getX(), pk.getZ());
                if (sk == null) continue;

                int drop = baselineGroundY - sk.surfaceY;
                boolean bad = (drop >= minDepth) || (sk.isWater && !sk.isOcean);
                if (bad) lastBadPathIndex = k;
            }

            // Evaluate the straight line: must contain enough deep void/water AND must not hit a wall above baseline.
            boolean lineHitsWall = false;

            for (BlockPos p : line) {
                TerrainSampler.Sample sp = sampler.sample(p.getX(), p.getZ());
                if (sp == null) continue;
                if (sp.isOcean) { lineHitsWall = true; break; }

                // Wall / mountain intersection check
                if (ridgeRise > 0 && sp.surfaceY > baselineGroundY + ridgeRise) {
                    lineHitsWall = true;
                    break;
                }

                int drop = baselineGroundY - sp.surfaceY;
                if (drop > deepestDrop) deepestDrop = drop;

                boolean isDeepVoid = drop >= minDepth && !sp.isOcean;
                boolean isWater = sp.isWater && !sp.isOcean;

                if (isDeepVoid) {
                    voidRun++;
                    if (voidRun > voidRunMax) voidRunMax = voidRun;
                } else {
                    voidRun = 0;
                }

                if (isWater) {
                    waterRun++;
                    if (waterRun > waterRunMax) waterRunMax = waterRun;
                } else {
                    waterRun = 0;
                }
            }

            if (lineHitsWall) continue;

            if (deepestDrop < minDepth) continue;
            if (voidRunMax < minVoidLen && waterRunMax < minWaterLen) continue;

            // Prevent exits too far beyond last valley/water sign.
            if (lastBadPathIndex >= 0) {
                int maxExit = lastBadPathIndex + 1 + maxOverhang;
                if (j > maxExit) continue;
            } else {
                // No valley signal on the path segment itself => skip.
                continue;
            }

            // Exit must be stable.
            if (!exitApproachIsStable(path, j)) continue;

            if (len < bestLen) {
                bestLen = len;
                bestJ = j;
            }
        }

        if (bestJ < 0) return null;

        BlockPos b = path.get(bestJ);
        return buildSpan(a, b, false);
    }

    private boolean segmentHasRidge(List<BlockPos> path, int i0, int i1, int baselineY, int ridgeRiseBlocks) {
        int maxY = baselineY + ridgeRiseBlocks;
        for (int i = i0; i <= i1; i++) {
            BlockPos p = path.get(i);
            TerrainSampler.Sample s = sampler.sample(p.getX(), p.getZ());
            if (s == null) continue;
            if (s.isOcean) return true;
            if (s.surfaceY > maxY) return true;
        }
        return false;
    }

    private boolean pathStaysNearLine(List<BlockPos> path, int i0, int i1, BlockPos a, BlockPos b, int maxDev) {
        long ax = a.getX();
        long az = a.getZ();
        long bx = b.getX();
        long bz = b.getZ();

        long dx = bx - ax;
        long dz = bz - az;
        long lenSq = dx * dx + dz * dz;
        if (lenSq <= 0) return true;

        for (int i = i0; i <= i1; i++) {
            BlockPos p = path.get(i);
            long px = p.getX();
            long pz = p.getZ();

            long apx = px - ax;
            long apz = pz - az;

            double t = (double) (apx * dx + apz * dz) / (double) lenSq;
            if (t < 0.0) t = 0.0;
            else if (t > 1.0) t = 1.0;

            double cx = (double) ax + t * (double) dx;
            double cz = (double) az + t * (double) dz;

            double ddx = (double) px - cx;
            double ddz = (double) pz - cz;
            double dist = Math.sqrt(ddx * ddx + ddz * ddz);

            if (dist > (double) maxDev) return false;
        }

        return true;
    }

    private BridgeSpan buildSpan(BlockPos entry, BlockPos exit, boolean micro) {
        TerrainSampler.Sample se = sampler.sample(entry.getX(), entry.getZ());
        TerrainSampler.Sample sx = sampler.sample(exit.getX(), exit.getZ());
        if (se == null || sx == null) return null;

        // We want the walking surface slightly above the ground road:
        // road full block surface ~= surfaceY + 1. Using bottom slab at y=surfaceY+1 gives surfaceY+1.5 (halfY=2*surfaceY+3)
        int startHalf = se.surfaceY * 2 + 3;
        int endHalf = sx.surfaceY * 2 + 3;

        int maxDY = Math.max(0, MPBConfig.BRIDGES.maxBridgeDeltaY) * 2; // convert blocks -> half blocks
        if (Math.abs(endHalf - startHalf) > maxDY) {
            endHalf = startHalf + clamp(endHalf - startHalf, -maxDY, maxDY);
        }

        List<BlockPos> line = MPBConfig.BRIDGE_GEOMETRY.useRaster8ForBridges
                ? RasterLine8.raster(entry.getX(), entry.getZ(), exit.getX(), exit.getZ(), 0)
                : SupercoverLine.raster(entry.getX(), entry.getZ(), exit.getX(), exit.getZ(), 0);

        int len = Math.max(0, line.size() - 1);

        int maxLen = Math.max(8, MPBConfig.BRIDGES.maxBridgeLenBlocks);
        if (len > maxLen) return null;

        if (MPBConfig.BRIDGES.microBridges) {
            int microMax = Math.max(2, MPBConfig.BRIDGES.microBridgeMaxLen);
            if (len <= microMax) micro = true;
        } else {
            micro = false;
        }

        // Reject spans that would be underground relative to the straight line terrain.
        int baseline = Math.max(se.surfaceY, sx.surfaceY);
        int ridgeRise = Math.max(0, MPBConfig.VALLEY_BRIDGING.ridgeRiseBlocks);
        int maxAllowed = baseline + ridgeRise;

        int hits = 0;
        for (BlockPos p : line) {
            TerrainSampler.Sample sp = sampler.sample(p.getX(), p.getZ());
            if (sp == null) continue;
            if (sp.surfaceY > maxAllowed) {
                hits++;
                if (hits >= 2) return null; // intersects a wall/mountain
            }
        }

        return new BridgeSpan(entry, exit, line, startHalf, endHalf, micro);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

}
package com.jubitus.millpathbuilder.gen;

import com.jubitus.millpathbuilder.MillPathBuilderMod;
import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.bridge.*;
import com.jubitus.millpathbuilder.gen.bridge.template.BridgeBuildService;
import com.jubitus.millpathbuilder.gen.bridge.template.BridgeTemplate;
import com.jubitus.millpathbuilder.gen.bridge.template.BridgeTemplateRegistry;
import com.jubitus.millpathbuilder.gen.mill.MPBMillPathPalette;
import com.jubitus.millpathbuilder.gen.newp.MPBRoadHalfProfile;
import com.jubitus.millpathbuilder.gen.world.Earthworks;
import com.jubitus.millpathbuilder.gen.world.MPBRoadIndexData;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


import java.util.HashMap;
import java.util.Map;

public final class RoadPainter {


    public enum PaintResult { RUNNING, OK, FAILED }

    private static final class ForcedBridgeRef {
        final BridgeSpan span;
        final int startIdx;
        final int endIdx;

        ForcedBridgeRef(BridgeSpan span, int startIdx, int endIdx) {
            this.span = span;
            this.startIdx = startIdx;
            this.endIdx = endIdx;
        }
    }

    public static final class Session {
        private final it.unimi.dsi.fastutil.longs.LongOpenHashSet bridgeProtected = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

        private int[] gradeY = null;

        private final int baseR;
        private final WorldServer world;
        private final TerrainSampler sampler;
        private final MPBProtectedAreas protectedAreas;
        private final boolean dryRun;

        private final int r;

        private final List<BlockPos> coarse;
        private final List<BridgeSpan> forcedBridges;
        private final List<BlockPos> path = new ArrayList<>();

        private final Map<Long, ForcedBridgeRef> forcedByStartKey = new HashMap<>();

        private int densifyIndex = 1;
        private boolean densifyDone = false;

        private int i = 0;
        private boolean paintingCell = false;
        private BlockPos curCenter = null;
        private int curDx = 0;
        private int curDz = 0;

        private boolean failed = false;
        private String failReason = "unknown";

        private boolean profilesReady = false;
        private boolean planBuilt = false;
        private boolean planPruned = false;

        private int planIdx = 0;

        private int[] wobbleX;
        private int[] wobbleZ;
        private int[] radiusByIdx;
        private boolean[] slabByIdx;

        private final it.unimi.dsi.fastutil.longs.LongOpenHashSet planned = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        private final it.unimi.dsi.fastutil.longs.LongOpenHashSet core = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        private final it.unimi.dsi.fastutil.longs.LongOpenHashSet painted = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

        private final MPBRoadIndexData roadIndex;
        private final it.unimi.dsi.fastutil.longs.LongOpenHashSet newlyPlaced = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

        private final it.unimi.dsi.fastutil.longs.LongOpenHashSet forcePaint = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

        private boolean mergeStopArmed = false;
        private int mergeStopAt = Integer.MAX_VALUE;

        private boolean mergeActive = false;
        private int mergeTargetX = 0;
        private int mergeTargetZ = 0;
        private int mergeHitIndex = Integer.MAX_VALUE;
        private int mergeBlendStart = Integer.MAX_VALUE;

        private final boolean allowEarlyMerge;

        private boolean prunePrepared = false;
        private long[] planKeysSnapshot = null;
        private int pruneIdx = 0;
        private final BridgePlanner bridgePlanner;
        private BridgeSpan pendingBridge = null;
        private int pendingBridgeEndIdx = -1;

        private int[] centerHalf = null;

        private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap plannedHalfByXZ = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap plannedPriorityByXZ = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

        Session(WorldServer world,
                List<BlockPos> coarsePath,
                List<BridgeSpan> forcedBridges,
                TerrainSampler sampler,
                MPBProtectedAreas protectedAreas,
                MPBRoadIndexData roadIndex,
                boolean dryRun,
                boolean allowEarlyMerge) {
            this.world = world;
            this.allowEarlyMerge = allowEarlyMerge;
            this.sampler = sampler;
            this.protectedAreas = protectedAreas;
            this.roadIndex = roadIndex;
            this.dryRun = dryRun;
            this.forcedBridges = forcedBridges == null ? Collections.<BridgeSpan>emptyList() : forcedBridges;

            this.plannedHalfByXZ.defaultReturnValue(Integer.MIN_VALUE);
            this.plannedPriorityByXZ.defaultReturnValue(Integer.MAX_VALUE);

            this.bridgePlanner = new BridgePlanner(world, sampler);

            int w = Math.max(1, MPBConfig.PAINTING.roadWidth);
            if ((w % 2) == 0) w++;

            this.baseR = w / 2;
            this.r = w / 2;

            this.coarse = coarsePath == null ? Collections.<BlockPos>emptyList() : coarsePath;

            if (!this.coarse.isEmpty()) {
                BlockPos a = this.coarse.get(0);
                TerrainSampler.Sample sm = sampler.sample(a.getX(), a.getZ());
                path.add(new BlockPos(a.getX(), sm.surfaceY, a.getZ()));
            } else {
                densifyDone = true;
            }
        }

        public PaintResult advance(long deadlineNs) {
            if (failed) return PaintResult.FAILED;

            while (System.nanoTime() < deadlineNs) {

                if (!densifyDone) {
                    if (!densifyStep(deadlineNs)) return PaintResult.RUNNING;
                    densifyDone = true;

                    indexForcedBridges();
                    buildBridgeProtection();

                    detectEarlyMergeOnce();

                    if (MPBConfig.PATHFINDING.allowEarthworks) {
                        this.gradeY = buildGradeProfile();
                        if (failed) {
                            return PaintResult.FAILED;
                        }
                    }
                    continue;
                }

                if (!profilesReady) {
                    buildProfiles();
                    profilesReady = true;
                    continue;
                }

                if (!planBuilt) {
                    if (!buildPlanStep(deadlineNs)) return PaintResult.RUNNING;
                    planBuilt = true;
                    continue;
                }

                if (!planPruned) {
                    if (!pruneAndReinforceStep(deadlineNs)) return PaintResult.RUNNING;
                    planPruned = true;
                    continue;
                }

                if (mergeStopArmed && i > mergeStopAt) {
                    if (pendingBridge != null) {
                        if (!paintBridgeNow(pendingBridge)) {
                            MillPathBuilderMod.LOGGER.warn("Bridge build degraded at end of edge start=({}, {}) end=({}, {}) len={}",
                                    pendingBridge.start.getX(), pendingBridge.start.getZ(),
                                    pendingBridge.end.getX(), pendingBridge.end.getZ(),
                                    pendingBridge.lengthBlocks());
                        }
                        pendingBridge = null;
                    }
                    return PaintResult.OK;
                }

                if (i >= path.size()) {
                    if (pendingBridge != null) {
                        if (!paintBridgeNow(pendingBridge)) {
                            MillPathBuilderMod.LOGGER.warn("Bridge build degraded at final path end start=({}, {}) end=({}, {}) len={}",
                                    pendingBridge.start.getX(), pendingBridge.start.getZ(),
                                    pendingBridge.end.getX(), pendingBridge.end.getZ(),
                                    pendingBridge.lengthBlocks());
                        }
                        pendingBridge = null;
                    }
                    return PaintResult.OK;
                }

                if (paintingCell) {
                    if (!paintCells(deadlineNs)) return PaintResult.RUNNING;
                    paintingCell = false;
                    curCenter = null;
                    i++;
                    continue;
                }

                BlockPos p = path.get(i);
                bridgePlanner.tickAdvanceOneBlock();

                if (pendingBridge != null) {
                    if (i >= pendingBridgeEndIdx) {
                        BridgeSpan builtSpan = pendingBridge;
                        boolean bridgeOk = paintBridgeNow(builtSpan);

                        if (!bridgeOk) {
                            MillPathBuilderMod.LOGGER.warn("Bridge build degraded start=({}, {}) end=({}, {}) len={}",
                                    builtSpan.start.getX(), builtSpan.start.getZ(),
                                    builtSpan.end.getX(), builtSpan.end.getZ(),
                                    builtSpan.lengthBlocks());
                        }

                        reinforceBridgeExit(builtSpan, MPBConfig.BRIDGE_AWARE_PATHFINDING.forcePaintExitBlocks);

                        pendingBridge = null;
                        pendingBridgeEndIdx = -1;
                        i++;
                        continue;
                    }
                }

                ForcedBridgeRef forced = forcedByStartKey.get(xzKey(p.getX(), p.getZ()));
                if (forced != null && forced.startIdx == i) {
                    pendingBridge = forced.span;
                    pendingBridgeEndIdx = forced.endIdx;
                    i++;
                    continue;
                }

                if (!MPBConfig.BRIDGE_AWARE_PATHFINDING.disableLegacyBridgePlannerFallback) {
                    BridgeSpan planned = bridgePlanner.tryPlanFromPath(path, i);
                    if (planned != null) {
                        pendingBridge = planned;
                        pendingBridgeEndIdx = findIndexForXZ(planned.end.getX(), planned.end.getZ(), i + 1);

                        if (planned.centerline.size() <= 1 || pendingBridgeEndIdx < 0) {
                            pendingBridge = null;
                            pendingBridgeEndIdx = -1;
                        }

                        i++;
                        continue;
                    }
                }

                beginPaintSweep(p, i);
            }

            return PaintResult.RUNNING;
        }

        private void resetPaintCursor(int localR) {
            curDx = -localR;
            curDz = -localR;
        }


        public String failureReason() {
            return failReason;
        }

        private void indexForcedBridges() {
            forcedByStartKey.clear();
            for (BridgeSpan span : forcedBridges) {
                if (span == null) continue;
                int s = findIndexForXZ(span.start.getX(), span.start.getZ(), 0);
                int e = findIndexForXZ(span.end.getX(), span.end.getZ(), Math.max(0, s));
                if (s < 0 || e < 0 || e <= s) {
                    MillPathBuilderMod.LOGGER.warn("Skipping forced bridge span: unresolved indices start=({}, {}) end=({}, {}) s={} e={}",
                            span.start.getX(), span.start.getZ(),
                            span.end.getX(), span.end.getZ(),
                            s, e);
                    continue;
                }
                forcedByStartKey.put(xzKey(span.start.getX(), span.start.getZ()), new ForcedBridgeRef(span, s, e));
            }
        }

        private int findIndexForXZ(int x, int z, int fromIdx) {
            for (int idx = Math.max(0, fromIdx); idx < path.size(); idx++) {
                BlockPos p = path.get(idx);
                if (p.getX() == x && p.getZ() == z) return idx;
            }
            return -1;
        }

        private void detectEarlyMergeOnce() {
            if (!allowEarlyMerge) return;
            if (roadIndex == null) return;
            if (path.size() < 8) return;

            // Never merge before 20% of the path is painted — prevents premature stop
            // when path starts inside a village that already has Millénaire/MPB road blocks.
            int minIdx = Math.max(Math.max(6, baseR + 3), path.size() / 5);
            // Protect the end of the path symmetrically: don't merge within minIdx steps
            // of the destination (avoids stopping short when approaching a village entry path).
            int scanTo = Math.max(minIdx, path.size() - 1 - minIdx);

            int maxJoinDeltaHalf = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.maxRoadJoinDeltaHalfBlocks);
            int minAfterBridge = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.minBlocksAfterBridgeBeforeMerge);
            int minApproach = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeRoadApproachMinBlocks);
            int protectedMergeDistance = Math.max(minAfterBridge, minApproach);

            for (int idx = minIdx; idx <= scanTo; idx++) {
                BlockPos p = path.get(idx);
                if (forcedByStartKey.containsKey(xzKey(p.getX(), p.getZ()))) continue;
                if (isTooCloseToBridgeExit(idx, protectedMergeDistance)) continue;

                BlockPos near = roadIndex.findNearestRoad(p.getX(), p.getZ(), 2);
                if (near == null) continue;

                long nk = xzKey(near.getX(), near.getZ());
                if (newlyPlaced.contains(nk)) continue;

                if (!MPBRoadConnectionHelper.canMergeCleanlyToRoad(
                        world,
                        sampler,
                        roadIndex,
                        p.getX(),
                        p.getY(),
                        p.getZ(),
                        near.getX(),
                        near.getZ(),
                        maxJoinDeltaHalf
                )) {
                    continue;
                }

                mergeActive = true;
                mergeTargetX = near.getX();
                mergeTargetZ = near.getZ();
                mergeHitIndex = idx;

                int blend = Math.max(8, baseR + 6);
                mergeBlendStart = Math.max(0, mergeHitIndex - blend);

                mergeStopArmed = true;
                mergeStopAt = mergeHitIndex;

                MillPathBuilderMod.LOGGER.info(
                        "[MPB] Early merge: path index {}/{} -> existing road ({}, {})",
                        idx, path.size() - 1, near.getX(), near.getZ());

                return;
            }
        }

        private boolean isTooCloseToBridgeExit(int idx, int extraBlocks) {
            if (extraBlocks <= 0) return false;
            if (forcedByStartKey.isEmpty()) return false;

            for (ForcedBridgeRef ref : forcedByStartKey.values()) {
                if (ref == null) continue;
                if (idx >= ref.startIdx && idx <= ref.endIdx + extraBlocks) {
                    return true;
                }
            }

            return false;
        }

        private boolean densifyStep(long deadlineNs) {
            while (densifyIndex < coarse.size() && System.nanoTime() < deadlineNs) {
                BlockPos a = coarse.get(densifyIndex - 1);
                BlockPos b = coarse.get(densifyIndex);

                List<BlockPos> line;
                try {
                    line = MPBConfig.BRIDGE_GEOMETRY.useRaster8ForBridges
                            ? RasterLine8.raster(a.getX(), a.getZ(), b.getX(), b.getZ(), 0)
                            : SupercoverLine.raster(a.getX(), a.getZ(), b.getX(), b.getZ(), 0);
                } catch (Throwable t) {
                    failed = true;
                    failReason = "densify failed on segment (" + a.getX() + "," + a.getZ() + ") -> (" + b.getX() + "," + b.getZ() + "): " + t.getMessage();
                    return true;
                }

                if (line == null || line.isEmpty()) {
                    densifyIndex++;
                    continue;
                }

                for (int k = 1; k < line.size() && System.nanoTime() < deadlineNs; k++) {
                    BlockPos p = line.get(k);
                    TerrainSampler.Sample sm = sampler.sample(p.getX(), p.getZ());
                    path.add(new BlockPos(p.getX(), sm.surfaceY, p.getZ()));
                }

                densifyIndex++;
            }
            return densifyIndex >= coarse.size();
        }

        private void buildProfiles() {
            int n = path.size();
            wobbleX = new int[n];
            wobbleZ = new int[n];
            radiusByIdx = new int[n];
            slabByIdx = new boolean[n];

            for (int idx = 0; idx < n; idx++) {
                wobbleX[idx] = 0;
                wobbleZ[idx] = 0;
                radiusByIdx[idx] = baseR;
                slabByIdx[idx] = false;
            }

            try {
                centerHalf = MPBRoadHalfProfile.build(path, forcedBridges, sampler);
            } catch (IllegalStateException ex) {
                failed = true;
                failReason = "No feasible final road profile with max 0.5-block steps: " + ex.getMessage();
                centerHalf = new int[n];
                return;
            } catch (Throwable t) {
                failed = true;
                failReason = "Road profile generation failed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
                centerHalf = new int[n];
                return;
            }

            for (int idx = 0; idx < n; idx++) {
                slabByIdx[idx] = (centerHalf[idx] & 1) != 0;
            }
        }

        private int centerXAt(int idx) {
            int cx = path.get(idx).getX() + wobbleX[idx];
            if (!mergeActive) return cx;
            if (idx < mergeBlendStart) return cx;

            int dx = mergeTargetX - cx;
            int steps = Math.max(1, (mergeHitIndex - idx) + 1);
            int step = (int) Math.round((double) dx / (double) steps);
            return cx + step;
        }

        private int centerZAt(int idx) {
            int cz = path.get(idx).getZ() + wobbleZ[idx];
            if (!mergeActive) return cz;
            if (idx < mergeBlendStart) return cz;

            int dz = mergeTargetZ - cz;
            int steps = Math.max(1, (mergeHitIndex - idx) + 1);
            int step = (int) Math.round((double) dz / (double) steps);
            return cz + step;
        }

        private boolean buildPlanStep(long deadlineNs) {
            boolean earthworks = earthworksEnabled();

            while (planIdx < path.size() && System.nanoTime() < deadlineNs) {
                int idx = planIdx;
                BlockPos center = path.get(idx);

                int cx = centerXAt(idx);
                int cz = centerZAt(idx);

                core.add(xzKey(cx, cz));

                if (idx == 0) {
                    int localR = radiusByIdx[idx];
                    int targetHalf = centerHalf != null && idx < centerHalf.length
                            ? centerHalf[idx]
                            : BridgeGeometry.naturalSurfaceHalfY(center.getY());

                    addEndCapToPlan(cx, cz, localR, targetHalf, idx, earthworks);
                    planIdx++;
                    continue;
                }

                int prevIdx = idx - 1;
                BlockPos prev = path.get(prevIdx);

                int x0 = centerXAt(prevIdx);
                int z0 = centerZAt(prevIdx);
                int x1 = cx;
                int z1 = cz;

                int r0 = radiusByIdx[prevIdx];
                int r1 = radiusByIdx[idx];
                int localR = Math.max(r0, r1);

                int half0 = centerHalf != null && prevIdx < centerHalf.length
                        ? centerHalf[prevIdx]
                        : BridgeGeometry.naturalSurfaceHalfY(prev.getY());

                int half1 = centerHalf != null && idx < centerHalf.length
                        ? centerHalf[idx]
                        : BridgeGeometry.naturalSurfaceHalfY(center.getY());

                addRibbonSegmentToPlan(x0, z0, x1, z1, localR, half0, half1, idx, earthworks);

                planIdx++;
            }

            return planIdx >= path.size();
        }

        private void addEndCapToPlan(int cx,
                                     int cz,
                                     int localR,
                                     int targetHalf,
                                     int idx,
                                     boolean earthworks) {
            int pad = localR + 1;
            double radius = (double) localR + 0.5;

            for (int x = cx - pad; x <= cx + pad; x++) {
                for (int z = cz - pad; z <= cz + pad; z++) {
                    if (!cellIntersectsDisk(x, z, cx + 0.5, cz + 0.5, radius)) {
                        continue;
                    }

                    double dx = ((double) x + 0.5) - ((double) cx + 0.5);
                    double dz = ((double) z + 0.5) - ((double) cz + 0.5);
                    double distSq = dx * dx + dz * dz;

                    addPlannedRibbonCell(x, z, distSq, targetHalf, idx, earthworks);
                }
            }
        }

        private void addRibbonSegmentToPlan(int x0,
                                            int z0,
                                            int x1,
                                            int z1,
                                            int localR,
                                            int half0,
                                            int half1,
                                            int idx,
                                            boolean earthworks) {
            int pad = localR + 2;

            int minX = Math.min(x0, x1) - pad;
            int maxX = Math.max(x0, x1) + pad;
            int minZ = Math.min(z0, z1) - pad;
            int maxZ = Math.max(z0, z1) + pad;

            double ax = x0 + 0.5;
            double az = z0 + 0.5;
            double bx = x1 + 0.5;
            double bz = z1 + 0.5;

            double halfWidth = (double) localR + 0.5;

            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!cellIntersectsRibbon(x, z, ax, az, bx, bz, halfWidth)) {
                        continue;
                    }

                    double px = x + 0.5;
                    double pz = z + 0.5;
                    double t = segmentParam01(px, pz, ax, az, bx, bz);
                    double distSq = pointToSegmentDistanceSqClamped(px, pz, ax, az, bx, bz);

                    int targetHalf = (int) Math.round((1.0 - t) * (double) half0 + t * (double) half1);
                    addPlannedRibbonCell(x, z, distSq, targetHalf, idx, earthworks);
                }
            }
        }

        private void addPlannedRibbonCell(int x,
                                          int z,
                                          double distSq,
                                          int targetHalf,
                                          int idx,
                                          boolean earthworks) {
            if (isForcedBridgeCell(x, z) || isBridgeProtectedCell(x, z)) return;
            if (protectedAreas != null && protectedAreas.contains(x, z)) return;

            TerrainSampler.Sample s = sampler.sample(x, z);
            if (s == null) return;
            if (sampler.forbiddenHard(s)) return;
            if (s.isOcean || s.isWater) return;
            if (sampler.forbidden(s, TerrainSampler.Mode.PAINTING)) return;

            if (!earthworks) {
                BlockPos top = new BlockPos(x, s.surfaceY, z);
                IBlockState g = world.getBlockState(top);
                if (!isNaturalGround(g) && !MPBMillPathPalette.isMillPath(g.getBlock())) return;
            }

            boolean centerish = distSq <= 0.26;

            if (isExistingRoadNotOurs(x, z) || nearExistingRoadNotOurs(x, z)) {
                if (!centerish) return;
            }

            long k = xzKey(x, z);
            planned.add(k);

            int priority = (int) Math.round(distSq * 1024.0) + Math.abs(idx);
            if (forcePaint.contains(k)) {
                priority -= 100000;
            }

            int prev = plannedPriorityByXZ.get(k);
            if (priority < prev) {
                plannedPriorityByXZ.put(k, priority);
                plannedHalfByXZ.put(k, targetHalf);
            }
        }

        private double segmentParam01(double px, double pz,
                                      double ax, double az,
                                      double bx, double bz) {
            double dx = bx - ax;
            double dz = bz - az;
            double lenSq = dx * dx + dz * dz;

            if (lenSq <= 1.0e-9) return 0.0;

            double t = ((px - ax) * dx + (pz - az) * dz) / lenSq;
            if (t < 0.0) return 0.0;
            if (t > 1.0) return 1.0;
            return t;
        }

        private double pointToSegmentDistanceSq(double px, double pz,
                                                double ax, double az,
                                                double bx, double bz) {
            double t = segmentParam01(px, pz, ax, az, bx, bz);
            double cx = ax + (bx - ax) * t;
            double cz = az + (bz - az) * t;

            double dx = px - cx;
            double dz = pz - cz;
            return dx * dx + dz * dz;
        }

        private boolean isForcedBridgeCell(int x, int z) {
            for (BridgeSpan span : forcedBridges) {
                if (span == null || span.centerline == null) continue;
                for (BlockPos p : span.centerline) {
                    if (p.getX() == x && p.getZ() == z) return true;
                }
            }
            return false;
        }

        private boolean isExistingRoadNotOurs(int x, int z) {
            if (roadIndex == null) return false;
            long k = xzKey(x, z);
            return roadIndex.isRoadXZ(x, z) && !newlyPlaced.contains(k);
        }

        private boolean nearExistingRoadNotOurs(int x, int z) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (isExistingRoadNotOurs(x + dx, z + dz)) return true;
                }
            }
            return false;
        }

        private boolean pruneAndReinforceStep(long deadlineNs) {
            if (!prunePrepared) {
                planKeysSnapshot = planned.toLongArray();
                pruneIdx = 0;
                prunePrepared = true;
            }

            while (pruneIdx < planKeysSnapshot.length && System.nanoTime() < deadlineNs) {
                pruneIdx++;
            }

            if (pruneIdx < planKeysSnapshot.length) return false;

            it.unimi.dsi.fastutil.longs.LongOpenHashSet connected = keepOnlyConnectedToCore(planned, core);

            it.unimi.dsi.fastutil.longs.LongOpenHashSet afterNoise = applyGentleBoundaryNoise(connected);
            planned.clear();
            planned.addAll(afterNoise);

            junctionReinforcement();

            planKeysSnapshot = null;
            prunePrepared = false;
            return true;
        }

        private it.unimi.dsi.fastutil.longs.LongOpenHashSet keepOnlyConnectedToCore(it.unimi.dsi.fastutil.longs.LongOpenHashSet plan, it.unimi.dsi.fastutil.longs.LongOpenHashSet coreSet) {
            it.unimi.dsi.fastutil.longs.LongOpenHashSet keep = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(Math.max(16, plan.size()));
            ArrayDeque<Long> q = new ArrayDeque<>();

            int seeded = 0;
            long[] coreArr = coreSet.toLongArray();
            for (long ck : coreArr) {
                if (!plan.contains(ck)) continue;
                if (keep.add(ck)) {
                    q.add(ck);
                    seeded++;
                }
            }

            if (seeded == 0) {
                long[] any = plan.toLongArray();
                if (any.length > 0) {
                    keep.add(any[0]);
                    q.add(any[0]);
                }
            }

            while (!q.isEmpty()) {
                long k = q.poll();
                int x = (int) (k >> 32);
                int z = (int) k;

                long n0 = xzKey(x + 1, z);
                long n1 = xzKey(x - 1, z);
                long n2 = xzKey(x, z + 1);
                long n3 = xzKey(x, z - 1);

                if (plan.contains(n0) && keep.add(n0)) q.add(n0);
                if (plan.contains(n1) && keep.add(n1)) q.add(n1);
                if (plan.contains(n2) && keep.add(n2)) q.add(n2);
                if (plan.contains(n3) && keep.add(n3)) q.add(n3);
            }

            return keep;
        }

        private it.unimi.dsi.fastutil.longs.LongOpenHashSet applyGentleBoundaryNoise(it.unimi.dsi.fastutil.longs.LongOpenHashSet in) {
            return in;
        }



        private int neigh4Count(it.unimi.dsi.fastutil.longs.LongOpenHashSet set, int x, int z) {
            int hits = 0;
            if (set.contains(xzKey(x + 1, z))) hits++;
            if (set.contains(xzKey(x - 1, z))) hits++;
            if (set.contains(xzKey(x, z + 1))) hits++;
            if (set.contains(xzKey(x, z - 1))) hits++;
            return hits;
        }

        private void junctionReinforcement() {
            int reinR = baseR + 1;

            long[] coreArr = core.toLongArray();
            for (long k : coreArr) {
                int x = (int) (k >> 32);
                int z = (int) k;

                boolean existing = (roadIndex != null && roadIndex.isRoadXZ(x, z));
                int n4 = neigh4Count(planned, x, z);

                boolean junction = existing || n4 >= 3;
                if (!junction) continue;

                fillDiskForced(x, z, reinR);
            }

            long[] planArr = planned.toLongArray();
            for (long k : planArr) {
                int x = (int) (k >> 32);
                int z = (int) k;

                boolean existing = (roadIndex != null && roadIndex.isRoadXZ(x, z));
                if (!existing) continue;

                fillDiskForced(x, z, reinR);
            }

            if (mergeActive) {
                fillDiskForced(mergeTargetX, mergeTargetZ, reinR);
            }
        }

        private void fillDiskForced(int cx, int cz, int rr) {
            for (int dx = -rr; dx <= rr; dx++) {
                for (int dz = -rr; dz <= rr; dz++) {
                    if (dx * dx + dz * dz > rr * rr) continue;

                    int x = cx + dx;
                    int z = cz + dz;

                    if (isForcedBridgeCell(x, z) || isBridgeProtectedCell(x, z)) continue;
                    if (protectedAreas != null && protectedAreas.contains(x, z)) continue;

                    TerrainSampler.Sample s = sampler.sample(x, z);
                    if (sampler.forbiddenHard(s)) continue;
                    if (s.isOcean || s.isWater) continue;
                    if (sampler.forbidden(s, TerrainSampler.Mode.PAINTING)) continue;

                    long k = xzKey(x, z);
                    planned.add(k);
                    forcePaint.add(k);
                }
            }
        }

        private boolean shouldPaintCell(int x, int z) {
            if (isForcedBridgeCell(x, z) || isBridgeProtectedCell(x, z)) return false;
            long k = xzKey(x, z);
            return forcePaint.contains(k) || planned.contains(k);
        }

        private boolean paintCells(long deadlineNs) {
            while (System.nanoTime() < deadlineNs) {
                if (curCenter == null) return true;

                int idx = i;
                int localR = radiusByIdx[idx];

                if (curDx > localR) {
                    return true;
                }

                int localDx = curDx;
                int localDz = curDz;

                advancePaintCursor(localR);

                int cx = centerXAt(idx);
                int cz = centerZAt(idx);

                int xx = cx + localDx;
                int zz = cz + localDz;

                if (!shouldPaintCell(xx, zz)) {
                    continue;
                }

                long k = xzKey(xx, zz);
                if (!painted.add(k)) {
                    continue;
                }
                if (isBridgeProtectedCell(xx, zz)) {
                    continue;
                }

                TerrainSampler.Sample s = sampler.sample(xx, zz);
                if (s == null) {
                    continue;
                }
                if (sampler.forbiddenHard(s)) {
                    continue;
                }
                if (s.isOcean || s.isWater) {
                    continue;
                }
                if (sampler.forbidden(s, TerrainSampler.Mode.PAINTING)) {
                    continue;
                }
                if (protectedAreas != null && protectedAreas.contains(xx, zz)) {
                    continue;
                }

                paintSingle(xx, zz, s.surfaceY);
            }

            return false;
        }

        private double pointToSegmentDistanceSqClamped(double px, double pz,
                                                       double ax, double az,
                                                       double bx, double bz) {
            double t = segmentParam01(px, pz, ax, az, bx, bz);
            double cx = ax + (bx - ax) * t;
            double cz = az + (bz - az) * t;

            double dx = px - cx;
            double dz = pz - cz;
            return dx * dx + dz * dz;
        }

        private boolean cellIntersectsRibbon(int x,
                                             int z,
                                             double ax,
                                             double az,
                                             double bx,
                                             double bz,
                                             double halfWidth) {
            double nearestX = x + 0.5;
            double nearestZ = z + 0.5;
            double bestDistSq = Double.POSITIVE_INFINITY;

            for (int sx = 0; sx <= 1; sx++) {
                double px = x + sx;
                for (int sz = 0; sz <= 1; sz++) {
                    double pz = z + sz;
                    double d2 = pointToSegmentDistanceSqClamped(px, pz, ax, az, bx, bz);
                    if (d2 < bestDistSq) {
                        bestDistSq = d2;
                        nearestX = px;
                        nearestZ = pz;
                    }
                }
            }

            double centerD2 = pointToSegmentDistanceSqClamped(x + 0.5, z + 0.5, ax, az, bx, bz);
            if (centerD2 < bestDistSq) {
                bestDistSq = centerD2;
                nearestX = x + 0.5;
                nearestZ = z + 0.5;
            }

            if (bestDistSq <= halfWidth * halfWidth) {
                return true;
            }

            double segDx = bx - ax;
            double segDz = bz - az;
            double segLen = Math.sqrt(segDx * segDx + segDz * segDz);
            if (segLen <= 1.0e-9) {
                return false;
            }

            double nx = -segDz / segLen;
            double nz = segDx / segLen;

            double support = 0.5 * Math.abs(nx) + 0.5 * Math.abs(nz);

            double t = segmentParam01(nearestX, nearestZ, ax, az, bx, bz);
            double cx = ax + (bx - ax) * t;
            double cz = az + (bz - az) * t;

            double perp = Math.abs((nearestX - cx) * nx + (nearestZ - cz) * nz);
            return perp <= halfWidth + support;
        }

        private boolean cellIntersectsDisk(int x,
                                           int z,
                                           double cx,
                                           double cz,
                                           double radius) {
            double nearestX = Math.max((double) x, Math.min(cx, (double) x + 1.0));
            double nearestZ = Math.max((double) z, Math.min(cz, (double) z + 1.0));

            double dx = nearestX - cx;
            double dz = nearestZ - cz;
            return dx * dx + dz * dz <= radius * radius;
        }



        private void beginPaintSweep(BlockPos center, int idx) {
            this.curCenter = center;

            int localR = radiusByIdx[idx];
            this.curDx = -localR;
            this.curDz = -localR;
            this.paintingCell = true;
        }

        private void advancePaintCursor(int localR) {
            curDz++;
            if (curDz > localR) {
                curDz = -localR;
                curDx++;
            }
        }

        private boolean isInsidePaintRadius(int dx, int dz, int radius) {
            return dx * dx + dz * dz <= radius * radius;
        }

        private void paintSingle(int x, int z, int surfaceY) {
            if (isBridgeProtectedCell(x, z)) return;

            long k = xzKey(x, z);
            int targetHalf = plannedHalfByXZ.get(k);
            if (targetHalf == Integer.MIN_VALUE) {
                targetHalf = BridgeGeometry.naturalSurfaceHalfY(surfaceY);
            }

            BridgeGeometry.SlabPlacement sp = BridgeGeometry.slabPlacementForSurfaceHalfY(targetHalf);
            boolean useSlab = (targetHalf & 1) != 0;
            int supportTopY = useSlab ? (sp.blockY - 1) : sp.blockY;

            boolean earthworks = earthworksEnabled();
            if (isBridgeJoinIndex(i)) {
                earthworks = false;
            }

            boolean nearNoPaint = false;
            if (protectedAreas != null) {
                for (int dx = -1; dx <= 1 && !nearNoPaint; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (protectedAreas.contains(x + dx, z + dz)) {
                            nearNoPaint = true;
                            break;
                        }
                    }
                }
            }
            if (nearNoPaint) {
                earthworks = false;
            }

            if (earthworks) {
                int rr = r;
                int shoulder = Math.max(0, MPBConfig.PAINTING.earthworksShoulder);
                Earthworks.Settings st = new Earthworks.Settings(
                        rr,
                        shoulder,
                        1,
                        Math.max(0, MPBConfig.PAINTING.earthworksMaxCut),
                        Math.max(0, MPBConfig.PAINTING.earthworksMaxFill)
                );

                int cx = centerXAt(i);
                int cz = centerZAt(i);

                int dx0 = x - cx;
                int dz0 = z - cz;
                int dist = (int) Math.round(Math.sqrt((double) dx0 * (double) dx0 + (double) dz0 * (double) dz0));

                supportTopY = Earthworks.gradeCell(world, sampler, x, z, supportTopY, dist, st, dryRun);
                sampler.invalidateAround(x, z, 1);
            }

            BlockPos supportTop = new BlockPos(x, supportTopY, z);

            if (!dryRun && !nearNoPaint && !isBridgeJoinIndex(i)) {
                MPBRoadColumns.clearAtAndAbove(world, supportTop.up(), 2, false, earthworks);
            }

            IBlockState ground = world.getBlockState(supportTop);
            if (!earthworks) {
                if (ground == null) return;
                if (!isNaturalGround(ground) && !MPBMillPathPalette.isMillPath(ground.getBlock())) return;
            }

            MPBMillPathPalette.Kind kind = pickKindAveraged(x, z);
            placeRoadBlockFromHalf(supportTop, kind, targetHalf, earthworks);
        }

        private void placeRoadBlockFromHalf(BlockPos supportTop, MPBMillPathPalette.Kind kind, int targetHalf, boolean allowSolidCut) {
            if (dryRun) return;

            int x = supportTop.getX();
            int z = supportTop.getZ();

            BridgeGeometry.SlabPlacement sp = BridgeGeometry.slabPlacementForSurfaceHalfY(targetHalf);
            boolean useBottomSlab = (targetHalf & 1) != 0;

            BlockPos placePos = useBottomSlab ? new BlockPos(x, sp.blockY, z) : new BlockPos(x, sp.blockY, z);

            if (columnHasAnyMillPath(placePos)) {
                if (roadIndex != null) roadIndex.markRoad(x, z);
                return;
            }

            if (useBottomSlab) {
                ensureSolidSupportUnderBottomSlab(placePos.down(), kind);
            }

            MPBRoadColumns.clearAtAndAbove(world, placePos, 2, false, allowSolidCut);

            IBlockState place = useBottomSlab
                    ? MPBMillPathPalette.slabBottom(kind)
                    : MPBMillPathPalette.full(kind);

            world.setBlockState(placePos, place, 2);

            if (MPBConfig.PAINTING.clearHeadroom) {
                MPBRoadColumns.clearRoadColumn(world, placePos, 3, false, allowSolidCut);
            }

            if (roadIndex != null) {
                roadIndex.markRoad(x, z);
                newlyPlaced.add(xzKey(x, z));
            }

            sampler.invalidateAround(x, z, 1);
        }

        private void ensureSolidSupportUnderBottomSlab(BlockPos supportPos, MPBMillPathPalette.Kind kind) {
            IBlockState st = world.getBlockState(supportPos);
            Material m = st.getMaterial();

            if (m.isSolid() && m != Material.LEAVES && m != Material.WOOD) return;
            if (world.getTileEntity(supportPos) != null) return;

            IBlockState support = pickSupportBlock(supportPos, kind);
            world.setBlockState(supportPos, support, 3);
        }

        private boolean columnHasAnyMillPath(BlockPos baseTop) {
            int x = baseTop.getX();
            int z = baseTop.getZ();
            int y0 = baseTop.getY();

            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int dy = -2; dy <= 3; dy++) {
                int yy = y0 + dy;
                if (yy < 1 || yy > 255) continue;
                p.setPos(x, yy, z);
                IBlockState st = world.getBlockState(p);
                if (st != null && MPBMillPathPalette.isMillPath(st.getBlock())) return true;
            }
            return false;
        }

        private boolean earthworksEnabled() {
            return MPBConfig.PATHFINDING.allowEarthworks && gradeY != null && i >= 0 && i < gradeY.length;
        }

        private int[] buildGradeProfile() {
            int n = path.size();
            if (n == 0) return new int[0];

            try {
                if (centerHalf == null || centerHalf.length != n) {
                    centerHalf = MPBRoadHalfProfile.build(path, forcedBridges, sampler);
                }
            } catch (IllegalStateException ex) {
                failed = true;
                failReason = "No feasible final road profile with max 0.5-block steps: " + ex.getMessage();
                return null;
            } catch (Throwable t) {
                failed = true;
                failReason = "Road profile generation failed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
                return null;
            }

            int[] out = new int[n];
            for (int i = 0; i < n; i++) {
                BridgeGeometry.SlabPlacement sp = BridgeGeometry.slabPlacementForSurfaceHalfY(centerHalf[i]);
                out[i] = ((centerHalf[i] & 1) != 0) ? (sp.blockY - 1) : sp.blockY;
            }

            return out;
        }

        private IBlockState pickSupportBlock(BlockPos supportPos, MPBMillPathPalette.Kind kind) {
            IBlockState below = world.getBlockState(supportPos.down());
            Block b = below.getBlock();
            Material m = below.getMaterial();

            if (b == Blocks.SAND || m == Material.SAND) return Blocks.SAND.getDefaultState();
            if (b == Blocks.GRAVEL) return Blocks.GRAVEL.getDefaultState();
            if (b == Blocks.CLAY || m == Material.CLAY) return Blocks.CLAY.getDefaultState();
            if (m == Material.ROCK || b == Blocks.STONE) return Blocks.STONE.getDefaultState();

            if (kind == MPBMillPathPalette.Kind.GRAVEL) return Blocks.GRAVEL.getDefaultState();
            if (kind == MPBMillPathPalette.Kind.SANDSTONE) return Blocks.SAND.getDefaultState();
            if (kind == MPBMillPathPalette.Kind.SNOW) return Blocks.SNOW.getDefaultState();
            if (kind == MPBMillPathPalette.Kind.SLABS) return Blocks.STONE.getDefaultState();

            return Blocks.DIRT.getDefaultState();
        }

        private static long xzKey(int x, int z) {
            return (((long) x) << 32) ^ (z & 0xffffffffL);
        }

        private MPBMillPathPalette.Kind pickKindAveraged(int x, int z) {
            final int R = 2;

            int[] score = new int[MPBMillPathPalette.Kind.values().length];

            for (int dz = -R; dz <= R; dz++) {
                for (int dx = -R; dx <= R; dx++) {
                    int xx = x + dx;
                    int zz = z + dz;

                    TerrainSampler.Sample sm = sampler.sample(xx, zz);
                    if (sm == null) continue;
                    if (sm.isOcean || sm.isWater) continue;

                    BlockPos top = new BlockPos(xx, sm.surfaceY, zz);
                    IBlockState st = world.getBlockState(top);

                    if (st != null && MPBMillPathPalette.isMillPath(st.getBlock())) {
                        IBlockState below = world.getBlockState(top.down());
                        if (below != null) st = below;
                    }

                    if (st == null) continue;

                    Material m = st.getMaterial();
                    if (m == Material.AIR) continue;
                    if (m == Material.PLANTS || m == Material.VINE) continue;
                    if (m.isReplaceable()) continue;

                    MPBMillPathPalette.Kind k = MPBMillPathPalette.pickKind(world, top, st);

                    int md = Math.abs(dx) + Math.abs(dz);
                    int w = Math.max(0, (R + 2) - md);
                    if (w > 0) score[k.ordinal()] += w;
                }
            }

            if (roadIndex != null) {
                BlockPos near = roadIndex.findNearestRoad(x, z, 2);
                if (near != null) {
                    MPBMillPathPalette.Kind ek = roadIndex.findExistingKindAt(world, sampler, near.getX(), near.getZ());
                    if (ek != null) score[ek.ordinal()] += 10;
                }
            }

            int bestIdx = -1;
            int best = -1;
            for (int i = 0; i < score.length; i++) {
                if (score[i] > best) {
                    best = score[i];
                    bestIdx = i;
                }
            }

            if (bestIdx >= 0 && best > 0) {
                return MPBMillPathPalette.Kind.values()[bestIdx];
            }

            TerrainSampler.Sample sm0 = sampler.sample(x, z);
            BlockPos top0 = new BlockPos(x, sm0.surfaceY, z);
            IBlockState st0 = world.getBlockState(top0);
            return MPBMillPathPalette.pickKind(world, top0, st0);
        }

        private boolean paintBridgeNow(BridgeSpan span) {
            if (span == null) return true;
            if (!MPBConfig.PAINTING.buildBridges) return false;
            if (!MPBConfig.BRIDGES.enabled) return false;

            int roadW = Math.max(1, MPBConfig.PAINTING.roadWidth);
            if ((roadW % 2) == 0) roadW++;

            if (protectedAreas != null) {
                if (protectedAreas.contains(span.start.getX(), span.start.getZ())) return false;
                if (protectedAreas.contains(span.end.getX(), span.end.getZ())) return false;
            }

            return BridgeBuildService.build(world, sampler, span, roadW, dryRun);
        }

        private void reinforceBridgeExit(BridgeSpan span, int blocks) {
            if (span == null || blocks <= 0) return;
            if (span.centerline == null || span.centerline.size() < 2) return;

            int[] exitStep = bridgeExitStep(span);
            int dx = exitStep[0];
            int dz = exitStep[1];

            if (dx == 0 && dz == 0) return;

            int[] deckHalf = BridgeGeometry.buildDeckHalfProfile(span, sampler);
            int exitHalf = deckHalf[deckHalf.length - 1];

            int maxHalfStep = Math.max(1, MPBConfig.PAINTING.maxRoadStepHalfPerBlock);

            int x = span.end.getX();
            int z = span.end.getZ();
            int half = exitHalf;

            for (int i = 1; i <= blocks; i++) {
                x += dx;
                z += dz;

                if (isForcedBridgeCell(x, z) || isBridgeProtectedCell(x, z)) continue;
                if (protectedAreas != null && protectedAreas.contains(x, z)) continue;

                TerrainSampler.Sample s = sampler.sample(x, z);
                if (s == null) continue;
                if (sampler.forbiddenHard(s)) continue;
                if (s.isOcean || s.isWater) continue;
                if (sampler.forbidden(s, TerrainSampler.Mode.PAINTING)) continue;

                int naturalHalf = BridgeGeometry.naturalSurfaceHalfY(s.surfaceY);
                half = stepHalfToward(half, naturalHalf, maxHalfStep);

                long k = xzKey(x, z);
                planned.add(k);
                forcePaint.add(k);
                core.add(k);

                plannedHalfByXZ.put(k, half);

                int priority = -200000 + i;
                int prev = plannedPriorityByXZ.get(k);
                if (priority < prev) {
                    plannedPriorityByXZ.put(k, priority);
                }
            }
        }

        private int[] bridgeExitStep(BridgeSpan span) {
            if (span == null || span.centerline == null || span.centerline.size() < 2) {
                return new int[]{0, 0};
            }

            for (int i = span.centerline.size() - 1; i > 0; i--) {
                BlockPos a = span.centerline.get(i - 1);
                BlockPos b = span.centerline.get(i);

                int dx = Integer.signum(b.getX() - a.getX());
                int dz = Integer.signum(b.getZ() - a.getZ());

                if (dx != 0 || dz != 0) {
                    return new int[]{dx, dz};
                }
            }

            return new int[]{0, 0};
        }

        private int stepHalfToward(int curHalf, int targetHalf, int maxHalfStep) {
            int d = targetHalf - curHalf;
            if (d > maxHalfStep) return curHalf + maxHalfStep;
            if (d < -maxHalfStep) return curHalf - maxHalfStep;
            return targetHalf;
        }

        private void buildBridgeProtection() {
            bridgeProtected.clear();

            int lateral = bridgeProtectionHalfWidth();

            for (BridgeSpan span : forcedBridges) {
                if (span == null || span.centerline == null || span.centerline.isEmpty()) continue;

                for (int i = 0; i < span.centerline.size(); i++) {
                    BlockPos c = span.centerline.get(i);
                    BridgeGeometry.Dir2 perp = BridgeGeometry.localDir(span.centerline, i).perp();

                    markBridgeStripe(c.getX(), c.getZ(), perp, lateral);

                    if (i > 0) {
                        BlockPos p = span.centerline.get(i - 1);
                        int stepX = Integer.signum(c.getX() - p.getX());
                        int stepZ = Integer.signum(c.getZ() - p.getZ());

                        if (stepX != 0 && stepZ != 0) {
                            markBridgeDiagonalGap(p.getX(), p.getZ(), c.getX(), c.getZ(), perp, lateral);
                        }
                    }
                }
            }
        }

        private int bridgeProtectionHalfWidth() {
            int half = 1;

            BridgeTemplate active = BridgeTemplateRegistry.getDefaultTemplate();
            if (active != null) {
                int tHalf = Math.max(Math.abs(active.minLateral), Math.abs(active.maxLateral));
                if (tHalf > half) half = tHalf;
            }

            return half;
        }

        private void markBridgeStripe(int cx, int cz, BridgeGeometry.Dir2 perp, int half) {
            for (int o = -half; o <= half; o++) {
                int x = cx + perp.dx * o;
                int z = cz + perp.dz * o;
                bridgeProtected.add(xzKey(x, z));
            }
        }

        private void markBridgeDiagonalGap(int px, int pz, int cx, int cz, BridgeGeometry.Dir2 perp, int half) {
            int stepX = Integer.signum(cx - px);
            int stepZ = Integer.signum(cz - pz);

            for (int o = -half; o <= half; o++) {
                int ax = px + perp.dx * o;
                int az = pz + perp.dz * o;

                int bx = cx + perp.dx * o;
                int bz = cz + perp.dz * o;

                int c1x = ax + stepX;
                int c1z = az;
                if (isBetween2D(c1x, c1z, ax, az, bx, bz)) {
                    bridgeProtected.add(xzKey(c1x, c1z));
                }

                int c2x = ax;
                int c2z = az + stepZ;
                if (isBetween2D(c2x, c2z, ax, az, bx, bz)) {
                    bridgeProtected.add(xzKey(c2x, c2z));
                }
            }
        }

        private boolean isBridgeProtectedCell(int x, int z) {
            return bridgeProtected.contains(xzKey(x, z));
        }

        private boolean isBridgeJoinIndex(int idx) {
            int protect = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeJoinProtectionBlocks);
            if (protect <= 0) return false;
            if (forcedByStartKey.isEmpty()) return false;

            for (ForcedBridgeRef ref : forcedByStartKey.values()) {
                if (ref == null) continue;

                if (idx >= ref.startIdx - protect && idx <= ref.startIdx + protect) return true;
                if (idx >= ref.endIdx - protect && idx <= ref.endIdx + protect) return true;
            }

            return false;
        }

        private static boolean isBetween2D(int x, int z, int x0, int z0, int x1, int z1) {
            int minX = Math.min(x0, x1);
            int maxX = Math.max(x0, x1);
            int minZ = Math.min(z0, z1);
            int maxZ = Math.max(z0, z1);
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private RoadPainter() {}

    public static Session start(WorldServer world,
                                List<BlockPos> coarsePath,
                                List<BridgeSpan> forcedBridges,
                                TerrainSampler sampler,
                                MPBProtectedAreas protectedAreas,
                                MPBRoadIndexData roadIndex,
                                boolean dryRun,
                                boolean allowEarlyMerge) {
        if (coarsePath == null || coarsePath.isEmpty()) {
            return new Session(world, Collections.<BlockPos>emptyList(), forcedBridges, sampler, protectedAreas, roadIndex, dryRun, allowEarlyMerge);
        }
        return new Session(world, coarsePath, forcedBridges, sampler, protectedAreas, roadIndex, dryRun, allowEarlyMerge);
    }

    private static boolean isNaturalGround(IBlockState st) {
        return MPBGround.isRoadReplaceableGround(st);
    }

}
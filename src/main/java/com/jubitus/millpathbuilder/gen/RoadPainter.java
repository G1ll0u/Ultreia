package com.jubitus.millpathbuilder.gen;

import com.jubitus.millpathbuilder.MillPathBuilderMod;
import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.bridge.*;
import com.jubitus.millpathbuilder.gen.bridge.template.BridgeBuildService;
import com.jubitus.millpathbuilder.gen.bridge.template.BridgeTemplate;
import com.jubitus.millpathbuilder.gen.bridge.template.BridgeTemplateRegistry;
import com.jubitus.millpathbuilder.gen.mill.MPBMillPathPalette;
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

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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

        private final int baseWidth;
        private final int baseR;
        private final long organicSeed;
        private final WorldServer world;
        private final TerrainSampler sampler;
        private final MPBProtectedAreas protectedAreas;
        private final boolean dryRun;

        private final int width;
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

            this.bridgePlanner = new BridgePlanner(world, sampler);

            int w = Math.max(1, MPBConfig.PAINTING.roadWidth);
            if ((w % 2) == 0) w++;

            this.baseWidth = w;
            this.baseR = w / 2;
            this.width = w;
            this.r = w / 2;

            this.organicSeed = world.getSeed() ^ (((long) w) << 32) ^ (long) world.provider.getDimension();
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

                curCenter = p;
                curDx = -radiusByIdx[i];
                curDz = -radiusByIdx[i];
                paintingCell = true;
            }

            return PaintResult.RUNNING;
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

            int minIdx = Math.max(6, baseR + 3);
            int scanTo = Math.max(minIdx, path.size() - 1);

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

                List<BlockPos> line = MPBConfig.BRIDGE_GEOMETRY.useRaster8ForBridges
                        ? RasterLine8.raster(a.getX(), a.getZ(), b.getX(), b.getZ(), 0)
                        : SupercoverLine.raster(a.getX(), a.getZ(), b.getX(), b.getZ(), 0);

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

            int ox = 0;
            int oz = 0;
            for (int idx = 0; idx < n; idx++) {
                int tx = rawWobble(idx, 0xA11CE);
                int tz = rawWobble(idx, 0xBADC0);

                ox = stepToward(ox, tx, 1);
                oz = stepToward(oz, tz, 1);

                ox = clamp(ox, -2, 2);
                oz = clamp(oz, -2, 2);

                wobbleX[idx] = ox;
                wobbleZ[idx] = oz;
            }

            for (int idx = 0; idx < n; idx++) {
                int cx = centerXAt(idx);
                int cz = centerZAt(idx);

                int localR = baseR + organicRadiusDelta(cx, cz, idx);
                if (localR < 1) localR = 1;

                if (mergeActive && idx >= mergeBlendStart) {
                    localR = Math.max(localR, baseR);
                }

                radiusByIdx[idx] = localR;
            }

            boolean earthworks = earthworksEnabled();
            for (int idx = 0; idx < n; idx++) {
                slabByIdx[idx] = shouldUseSlabOnThisCell(idx, earthworks);
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
                BlockPos center = path.get(planIdx);
                if (isForcedBridgeCell(center.getX(), center.getZ())) {
                    core.add(xzKey(center.getX(), center.getZ()));
                    planIdx++;
                    continue;
                }

                int cx = centerXAt(planIdx);
                int cz = centerZAt(planIdx);
                int localR = radiusByIdx[planIdx];

                core.add(xzKey(cx, cz));

                for (int dx = -localR; dx <= localR; dx++) {
                    for (int dz = -localR; dz <= localR; dz++) {
                        if (dx * dx + dz * dz > localR * localR) continue;

                        int x = cx + dx;
                        int z = cz + dz;

                        if (isForcedBridgeCell(x, z) || isBridgeProtectedCell(x, z)) continue;
                        if (protectedAreas != null && protectedAreas.contains(x, z)) continue;

                        TerrainSampler.Sample s = sampler.sample(x, z);
                        if (sampler.forbiddenHard(s)) continue;
                        if (s.isOcean || s.isWater) continue;
                        if (sampler.forbidden(s, TerrainSampler.Mode.PAINTING)) continue;

                        if (!earthworks) {
                            BlockPos top = new BlockPos(x, s.surfaceY, z);
                            IBlockState g = world.getBlockState(top);
                            if (!isNaturalGround(g) && !MPBMillPathPalette.isMillPath(g.getBlock())) continue;
                        }

                        if (isExistingRoadNotOurs(x, z) || nearExistingRoadNotOurs(x, z)) {
                            if (dx != 0 || dz != 0) continue;
                        }

                        planned.add(xzKey(x, z));
                    }
                }

                planIdx++;
            }

            return planIdx >= path.size();
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
            if (in.size() < 64) return in;

            it.unimi.dsi.fastutil.longs.LongOpenHashSet removed = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

            long[] keys = in.toLongArray();
            for (long k : keys) {
                if (core.contains(k)) continue;
                if (forcePaint.contains(k)) continue;

                int x = (int) (k >> 32);
                int z = (int) k;

                int n4 = neigh4Count(in, x, z);
                if (n4 >= 3) continue;
                if (!isBoundaryCell(in, x, z)) continue;

                int v = hash32((int) (organicSeed ^ 0x51EDBEEF), x, z);
                int r0 = (v >>> 28) & 15;
                if (r0 != 0 && r0 != 1) continue;

                if (wouldCreateNotch(in, x, z)) continue;

                removed.add(k);
            }

            if (removed.isEmpty()) return in;

            it.unimi.dsi.fastutil.longs.LongOpenHashSet tmp = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(in);
            tmp.removeAll(removed);

            return keepOnlyConnectedToCore(tmp, core);
        }

        private boolean wouldCreateNotch(it.unimi.dsi.fastutil.longs.LongOpenHashSet set, int x, int z) {
            boolean e = set.contains(xzKey(x + 1, z));
            boolean w = set.contains(xzKey(x - 1, z));
            boolean s = set.contains(xzKey(x, z + 1));
            boolean n = set.contains(xzKey(x, z - 1));

            int count = 0;
            if (e) count++;
            if (w) count++;
            if (s) count++;
            if (n) count++;

            if (count <= 1) return true;

            if (e && w && !s && !n) return true;
            if (s && n && !e && !w) return true;

            return false;
        }

        private boolean isBoundaryCell(it.unimi.dsi.fastutil.longs.LongOpenHashSet set, int x, int z) {
            if (!set.contains(xzKey(x + 1, z))) return true;
            if (!set.contains(xzKey(x - 1, z))) return true;
            if (!set.contains(xzKey(x, z + 1))) return true;
            if (!set.contains(xzKey(x, z - 1))) return true;
            return false;
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

                int cx = centerXAt(idx);
                int cz = centerZAt(idx);

                if (curDx > localR) return true;

                int xx = cx + curDx;
                int zz = cz + curDz;

                curDz++;
                if (curDz > localR) {
                    curDz = -localR;
                    curDx++;
                }

                if (curDx * curDx + (curDz - 1) * (curDz - 1) > localR * localR) continue;
                if (!shouldPaintCell(xx, zz)) continue;

                long k = xzKey(xx, zz);
                if (!painted.add(k)) continue;
                if (isBridgeProtectedCell(xx, zz)) continue;

                TerrainSampler.Sample s = sampler.sample(xx, zz);
                if (sampler.forbiddenHard(s)) continue;
                if (s.isOcean || s.isWater) continue;
                if (sampler.forbidden(s, TerrainSampler.Mode.PAINTING)) continue;
                if (protectedAreas != null && protectedAreas.contains(xx, zz)) continue;

                paintSingle(xx, zz, s.surfaceY);
            }
            return false;
        }

        private void paintSingle(int x, int z, int surfaceY) {
            if (isBridgeProtectedCell(x, z)) return;

            int placeY = surfaceY;

            boolean earthworks = earthworksEnabled();
            if (isBridgeJoinIndex(i)) {
                earthworks = false;
            }

            boolean nearNoPaint = false;
            if (protectedAreas != null) {
                for (int dx = -1; dx <= 1 && !nearNoPaint; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (protectedAreas.contains(x + dx, z + dz)) { nearNoPaint = true; break; }
                    }
                }
            }
            if (nearNoPaint) {
                earthworks = false;
            }

            if (earthworks) {
                int centerY = gradeY[i];

                int rr = r;
                int shoulder = 2;
                Earthworks.Settings st = new Earthworks.Settings(rr, shoulder, 1, 64, 64);

                int cx = centerXAt(i);
                int cz = centerZAt(i);

                int dx0 = x - cx;
                int dz0 = z - cz;
                int dist = (int) Math.round(Math.sqrt((double) dx0 * (double) dx0 + (double) dz0 * (double) dz0));

                placeY = Earthworks.gradeCell(world, sampler, x, z, centerY, dist, st, dryRun);
                sampler.invalidateAround(x, z, 1);
            }

            BlockPos top = new BlockPos(x, placeY, z);

            if (!dryRun && !nearNoPaint && !isBridgeJoinIndex(i)) {
                MPBRoadColumns.clearAtAndAbove(world, top, 2, false, earthworks);
            }

            IBlockState ground = world.getBlockState(top);
            if (!earthworks) {
                if (ground == null) return;
                if (!isNaturalGround(ground) && !MPBMillPathPalette.isMillPath(ground.getBlock())) return;
            }

            MPBMillPathPalette.Kind kind = pickKindAveraged(x, z);

            boolean useSlab = slabByIdx[i];

            placeRoadBlock(top, kind, useSlab, earthworks);
        }

        private void placeRoadBlock(BlockPos baseTop, MPBMillPathPalette.Kind kind, boolean useSlab, boolean allowSolidCut) {
            if (dryRun) return;

            int x = baseTop.getX();
            int z = baseTop.getZ();

            if (columnHasAnyMillPath(baseTop)) {
                if (roadIndex != null) roadIndex.markRoad(x, z);
                return;
            }

            BlockPos placePos = useSlab ? baseTop.up() : baseTop;

            if (useSlab) {
                ensureSolidSupportUnderSlab(baseTop, kind);
            }

            MPBRoadColumns.clearAtAndAbove(world, placePos, 2, false, allowSolidCut);

            IBlockState place = useSlab ? MPBMillPathPalette.slabBottom(kind) : MPBMillPathPalette.full(kind);
            world.setBlockState(placePos, place, 2);

            if (MPBConfig.PAINTING.clearHeadroom) {
                MPBRoadColumns.clearRoadColumn(world, placePos, 2, false, allowSolidCut);
            }

            if (roadIndex != null) {
                roadIndex.markRoad(x, z);
                newlyPlaced.add(xzKey(x, z));
            }

            sampler.invalidateAround(placePos.getX(), placePos.getZ(), 1);
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

            int[] raw = new int[n];
            for (int i = 0; i < n; i++) raw[i] = path.get(i).getY();

            int[] noisy = new int[n];
            for (int i = 0; i < n; i++) {
                int v = hash32((int)(organicSeed ^ 0xC0FFEE), path.get(i).getX(), path.get(i).getZ());
                int r = (v >>> 28) & 15;
                int d = 0;
                if (r == 0) d = -1;
                else if (r == 1) d = 1;
                noisy[i] = raw[i] + d;
            }

            int[] sm = new int[n];
            int win = 5;
            for (int i = 0; i < n; i++) {
                int a = Math.max(0, i - win);
                int b = Math.min(n - 1, i + win);
                int sum = 0;
                int cnt = 0;
                for (int k = a; k <= b; k++) { sum += noisy[k]; cnt++; }
                sm[i] = (cnt == 0) ? noisy[i] : Math.round((float)sum / (float)cnt);
            }

            int maxDelta = 1;
            int[] out = new int[n];
            out[0] = sm[0];

            for (int i = 1; i < n; i++) {
                int want = sm[i];
                int prev = out[i - 1];
                int d = want - prev;
                if (d > maxDelta) want = prev + maxDelta;
                else if (d < -maxDelta) want = prev - maxDelta;
                out[i] = want;
            }

            for (int i = n - 2; i >= 0; i--) {
                int want = out[i];
                int next = out[i + 1];
                int d = want - next;
                if (d > maxDelta) want = next + maxDelta;
                else if (d < -maxDelta) want = next - maxDelta;
                out[i] = want;
            }

            return out;
        }

        private boolean shouldUseSlabOnThisCell(int idx, boolean earthworks) {
            int y = currentCenterlineY(idx, earthworks);
            int prev = (idx > 0) ? currentCenterlineY(idx - 1, earthworks) : y;
            int next = (idx + 1 < path.size()) ? currentCenterlineY(idx + 1, earthworks) : y;

            if (prev == y + 1) return true;
            if (next == y + 1) return true;

            return false;
        }

        private int currentCenterlineY(int idx, boolean earthworks) {
            if (earthworks && gradeY != null && idx >= 0 && idx < gradeY.length) return gradeY[idx];
            return path.get(idx).getY();
        }

        private void ensureSolidSupportUnderSlab(BlockPos supportPos, MPBMillPathPalette.Kind kind) {
            IBlockState st = world.getBlockState(supportPos);
            Material m = st.getMaterial();

            if (m.isSolid() && m != Material.LEAVES && m != Material.WOOD) return;
            if (world.getTileEntity(supportPos) != null) return;

            IBlockState support = pickSupportBlock(supportPos, kind);
            world.setBlockState(supportPos, support, 3);
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

        private int rawWobble(int idx, int salt) {
            int v = hash32((int)(organicSeed ^ (long)salt), idx * 31, idx * 17);
            int r = (v >>> 28) & 15;
            if (r == 0) return -1;
            if (r == 1) return 1;
            return 0;
        }

        private int organicRadiusDelta(int x, int z, int idx) {
            int v = hash32((int)(organicSeed ^ 0x9E3779B9L), x + idx * 17, z - idx * 23);
            int r = (v >>> 29) & 7;
            if (r == 0) return -1;
            if (r == 1) return 1;
            return 0;
        }

        private int hash32(int seed, int x, int z) {
            int h = seed;
            h ^= x * 0x27d4eb2d;
            h = Integer.rotateLeft(h, 13);
            h ^= z * 0x165667b1;
            h = Integer.rotateLeft(h, 15);
            h *= 0x85ebca6b;
            h ^= (h >>> 16);
            return h;
        }

        private static int stepToward(int cur, int target, int maxStep) {
            int d = target - cur;
            if (d > maxStep) return cur + maxStep;
            if (d < -maxStep) return cur - maxStep;
            return target;
        }

        private static int clamp(int v, int lo, int hi) {
            return v < lo ? lo : (v > hi ? hi : v);
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

            int dx = Integer.signum(span.end.getX() - span.start.getX());
            int dz = Integer.signum(span.end.getZ() - span.start.getZ());

            if (dx == 0 && dz == 0) return;

            int x = span.end.getX() + dx;
            int z = span.end.getZ() + dz;

            for (int i = 0; i < blocks; i++) {
                long k = xzKey(x, z);
                planned.add(k);
                forcePaint.add(k);
                core.add(k);

                x += dx;
                z += dz;
            }
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

            BridgeTemplate active = BridgeTemplateRegistry.getActiveTemplate();
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
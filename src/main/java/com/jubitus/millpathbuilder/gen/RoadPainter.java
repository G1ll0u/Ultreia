package com.jubitus.millpathbuilder.gen;

import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.bridge.BridgeBuilder2;
import com.jubitus.millpathbuilder.gen.bridge.BridgePlanner;
import com.jubitus.millpathbuilder.gen.bridge.BridgeSpan;
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

public final class RoadPainter {

    public enum PaintResult { RUNNING, OK, FAILED }

    public static final class Session {

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
        private final List<BlockPos> path = new ArrayList<>();

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

        Session(WorldServer world, List<BlockPos> coarsePath, TerrainSampler sampler, MPBProtectedAreas protectedAreas, MPBRoadIndexData roadIndex, boolean dryRun, boolean allowEarlyMerge) {
            this.world = world;
            this.allowEarlyMerge = allowEarlyMerge;
            this.sampler = sampler;
            this.protectedAreas = protectedAreas;
            this.roadIndex = roadIndex;
            this.dryRun = dryRun;

            this.bridgePlanner = new BridgePlanner(world, sampler);

            int w = Math.max(1, MPBConfig.PAINTING.roadWidth);
            if ((w % 2) == 0) w++;

            this.baseWidth = w;
            this.baseR = w / 2;
            this.width = w;
            this.r = w / 2;

            this.organicSeed = world.getSeed() ^ (((long) w) << 32) ^ (long) world.provider.getDimension();
            this.coarse = coarsePath == null ? Collections.emptyList() : coarsePath;

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
                    // If a bridge was planned and we stopped early, place it before finishing
                    if (pendingBridge != null) {
                        if (!paintBridgeNow(pendingBridge)) {
                            failed = true;
                            failReason = "bridge build failed";
                            return PaintResult.FAILED;
                        }
                        pendingBridge = null;
                    }
                    return PaintResult.OK;
                }

                if (i >= path.size()) {
                    // End of path: if a bridge is still pending, place it now
                    if (pendingBridge != null) {
                        if (!paintBridgeNow(pendingBridge)) {
                            failed = true;
                            failReason = "bridge build failed";
                            return PaintResult.FAILED;
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
                // one "block" progressed on centerline: decay bridge cooldown
                bridgePlanner.tickAdvanceOneBlock();

// If we are currently inside a planned bridge, skip painting road cells until its end
                if (pendingBridge != null) {
                    // advance i until we reach bridge end in the path
                    BlockPos cur = path.get(i);
                    if (cur.getX() == pendingBridge.end.getX() && cur.getZ() == pendingBridge.end.getZ()) {
                        // place bridge now and then continue after the exit point
                        if (!paintBridgeNow(pendingBridge)) {
                            failed = true;
                            failReason = "bridge build failed";
                            return PaintResult.FAILED;
                        }
                        pendingBridge = null;
                        i++; // move past exit
                        continue;
                    } else {
                        i++;
                        continue;
                    }
                }

// Try to start a bridge at this index (covers water/holes + valley bridging)
                BridgeSpan planned = bridgePlanner.tryPlanFromPath(path, i);
                if (planned != null) {
                    // build immediately and skip the interior indices (handled by pendingBridge)
                    pendingBridge = planned;

                    // optional: build immediately here if start == end edge case
                    if (planned.centerline.size() <= 1) {
                        pendingBridge = null;
                    }

                    // do not paint the current cell as road (bridge deck will cover)
                    i++;
                    continue;
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

        private void detectEarlyMergeOnce() {
            if (!allowEarlyMerge) return;
            if (roadIndex == null) return;
            if (path.size() < 8) return;

            int minIdx = Math.max(6, baseR + 3);
            int scanTo = Math.max(minIdx, path.size() - 1);

            for (int idx = minIdx; idx <= scanTo; idx++) {
                BlockPos p = path.get(idx);
                BlockPos near = roadIndex.findNearestRoad(p.getX(), p.getZ(), 2);
                if (near == null) continue;

                long nk = xzKey(near.getX(), near.getZ());
                if (newlyPlaced.contains(nk)) continue;

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

        private boolean densifyStep(long deadlineNs) {
            while (densifyIndex < coarse.size() && System.nanoTime() < deadlineNs) {
                BlockPos a = coarse.get(densifyIndex - 1);
                BlockPos b = coarse.get(densifyIndex);

                int x0 = a.getX(), z0 = a.getZ();
                int x1 = b.getX(), z1 = b.getZ();

                int dx = Integer.signum(x1 - x0);
                int dz = Integer.signum(z1 - z0);

                int steps = Math.max(Math.abs(x1 - x0), Math.abs(z1 - z0));
                steps = Math.max(1, steps);

                int x = x0, z = z0;
                for (int s = 0; s < steps && System.nanoTime() < deadlineNs; s++) {
                    x += dx;
                    z += dz;
                    TerrainSampler.Sample sm = sampler.sample(x, z);
                    path.add(new BlockPos(x, sm.surfaceY, z));
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
                int cx = centerXAt(planIdx);
                int cz = centerZAt(planIdx);
                int localR = radiusByIdx[planIdx];

                core.add(xzKey(cx, cz));

                for (int dx = -localR; dx <= localR; dx++) {
                    for (int dz = -localR; dz <= localR; dz++) {
                        if (dx * dx + dz * dz > localR * localR) continue;

                        int x = cx + dx;
                        int z = cz + dz;

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
            int placeY = surfaceY;

            boolean earthworks = earthworksEnabled();

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

            if (!dryRun && !nearNoPaint) {
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

            // Respect legacy toggle if you want:
            if (!MPBConfig.PAINTING.buildBridges) return true;
            if (!MPBConfig.BRIDGES.enabled) return true;

            int roadW = Math.max(1, MPBConfig.PAINTING.roadWidth);
            if ((roadW % 2) == 0) roadW++;

            // avoid protected areas: if either endpoint is protected, abort the bridge
            if (protectedAreas != null) {
                if (protectedAreas.contains(span.start.getX(), span.start.getZ())) return true;
                if (protectedAreas.contains(span.end.getX(), span.end.getZ())) return true;
            }

            return BridgeBuilder2.build(world, sampler, span, roadW, dryRun);
        }
    }

    private RoadPainter() {}

    public static Session start(WorldServer world, List<BlockPos> coarsePath, TerrainSampler sampler, MPBProtectedAreas protectedAreas, MPBRoadIndexData roadIndex, boolean dryRun, boolean allowEarlyMerge) {
        if (coarsePath == null || coarsePath.isEmpty()) {
            return new Session(world, Collections.emptyList(), sampler, protectedAreas, roadIndex, dryRun, allowEarlyMerge);
        }
        return new Session(world, coarsePath, sampler, protectedAreas, roadIndex, dryRun, allowEarlyMerge);
    }

    private static boolean isNaturalGround(IBlockState st) {
        return MPBGround.isRoadReplaceableGround(st);
    }
}
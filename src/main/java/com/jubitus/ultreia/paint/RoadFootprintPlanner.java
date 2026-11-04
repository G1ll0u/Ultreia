package com.jubitus.ultreia.paint;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.bridge.BridgeSegmentPainter;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.geom.LineRaster;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.roads.RoadIndex;
import com.jubitus.ultreia.roads.RoadJoins;
import com.jubitus.ultreia.terrain.ProtectedAreas;
import com.jubitus.ultreia.terrain.TerrainSampler;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Which cells of the world get a road block, and at what height: the ribbon along the centerline, end caps,
 * pruning (connected to the centerline, edge variation), junction disks, gap and merge connectors, bridge exits,
 * and the diagonal elbow fills of 1-wide trails. Painting only reads the result.
 */
final class RoadFootprintPlanner {

    private final WorldServer world;
    private final TerrainSampler sampler;
    @Nullable private final ProtectedAreas protectedAreas;
    @Nullable private final RoadIndex roadIndex;
    private final BridgeSegmentPainter bridges;
    private final RoadCenterline line;
    private final List<BlockPos> path;
    private final RoadMergePlanner merge;
    private final LongSet newlyPlaced;

    final LongOpenHashSet planned = new LongOpenHashSet();
    /** Centerline cells (and bridge exits): never pruned. */
    final LongOpenHashSet core = new LongOpenHashSet();
    /** Cells painted even outside the sweep (junctions, connectors, bridge exits). */
    final LongOpenHashSet forcePaint = new LongOpenHashSet();
    /** Planned surface height per cell (half-blocks), MIN_VALUE when unplanned. */
    final Long2IntOpenHashMap plannedHalfByXZ = new Long2IntOpenHashMap();
    private final Long2IntOpenHashMap plannedPriorityByXZ = new Long2IntOpenHashMap();
    private int planIdx = 0;

    // Elbow fill cells for 1-wide paths: painted directly after the main loop (cursor never reaches them).
    List<int[]> elbowFillList = null;
    // Connector cells [x, z, surfaceY, targetHalf] to paint explicitly when merge-stop fires.
    List<int[]> mergeConnectorList = null;

    RoadFootprintPlanner(WorldServer world, TerrainSampler sampler, @Nullable ProtectedAreas protectedAreas,
                         @Nullable RoadIndex roadIndex, BridgeSegmentPainter bridges, RoadCenterline line,
                         RoadMergePlanner merge, LongSet newlyPlaced) {
        this.world = world;
        this.sampler = sampler;
        this.protectedAreas = protectedAreas;
        this.roadIndex = roadIndex;
        this.bridges = bridges;
        this.line = line;
        this.path = line.points;
        this.merge = merge;
        this.newlyPlaced = newlyPlaced;
        plannedHalfByXZ.defaultReturnValue(Integer.MIN_VALUE);
        plannedPriorityByXZ.defaultReturnValue(Integer.MAX_VALUE);
    }

    private int centerXAt(int idx) {
        return path.get(idx).getX();
    }

    private int centerZAt(int idx) {
        return path.get(idx).getZ();
    }

    /** Forces the first road cells past a bridge exit; each entry is {x, z, half, n} (see BridgeSegmentPainter.exitCells). */
    void addBridgeExitCells(List<int[]> cells) {
        for (int[] c : cells) {
            long key = Pos2D.xzKey(c[0], c[1]);
            planned.add(key);
            forcePaint.add(key);
            core.add(key);

            plannedHalfByXZ.put(key, c[2]);

            int priority = -200000 + c[3];
            int prev = plannedPriorityByXZ.get(key);
            if (priority < prev) {
                plannedPriorityByXZ.put(key, priority);
            }
        }
    }

    // For 1-wide paths, add a fill block at each diagonal step to smooth the staircase.
    // Pattern: for step (x0,z0)→(x0+dx,z0+dz) where |dx|=|dz|=1,
    // add (x0+dx, z0) — the "X-first companion" that rounds the diagonal corner.
    void addDiagonalElbowFills() {
        // Never add elbow fills past the merge point — post-merge path cells belong to the
        // existing road and must not produce stray blocks beside it.
        int limit = (merge.active && merge.hitIndex >= 0)
                ? Math.min(path.size(), merge.hitIndex + 1)
                : path.size();
        for (int idx = 1; idx < limit; idx++) {
            int x0 = centerXAt(idx - 1);
            int z0 = centerZAt(idx - 1);
            int x1 = centerXAt(idx);
            int z1 = centerZAt(idx);
            int dx = x1 - x0;
            int dz = z1 - z0;

            if (Math.abs(dx) != 1 || Math.abs(dz) != 1) continue;
            if (merge.isGap(idx) || merge.isGap(idx - 1)) continue;

            // prevDz==0 means the previous step was horizontal (X-run) → X-first fill
            // otherwise (previous step had Z component) → Z-first fill
            int prevDz = (idx >= 2) ? (centerZAt(idx - 1) - centerZAt(idx - 2)) : dz;
            int fillX = (prevDz == 0) ? (x0 + dx) : x0;
            int fillZ = (prevDz == 0) ? z0 : (z0 + dz);

            if (bridges.isBridgeCell(fillX, fillZ)) continue;
            if (roadIndex != null && roadIndex.isBridgeVolumeXZ(fillX, fillZ)) continue;
            if (bridges.isNearExistingBridgeDeck(fillX, fillZ)) continue;
            if (protectedAreas != null && protectedAreas.contains(fillX, fillZ)) continue;

            TerrainSampler.Sample s = sampler.sample(fillX, fillZ);
            if (s == null || sampler.forbiddenHard(s) || s.isOcean || s.isWater) continue;
            if (sampler.forbidden(s, TerrainSampler.Mode.PAINTING)) continue;

            if (elbowFillList == null) elbowFillList = new ArrayList<>();
            elbowFillList.add(new int[]{fillX, fillZ, s.surfaceY});
        }
    }

    // Cells: [x, z, surfaceY, targetHalf] — targetHalf is interpolated between path end and road.
    private List<int[]> buildMergeConnectorList(int x0, int z0, int startHalf,
                                                int x1, int z1, int endHalf) {
        List<BlockPos> line = LineRaster.supercover(x0, z0, x1, z1, 0);
        if (line == null) return null;
        int n = line.size();
        List<int[]> cells = new ArrayList<>();
        for (int ci = 0; ci < n; ci++) {
            BlockPos p = line.get(ci);
            int x = p.getX(), z = p.getZ();
            if (bridges.isBridgeCell(x, z)) continue;
            if (roadIndex != null && roadIndex.isBridgeXZ(x, z)) continue;
            if (protectedAreas != null && protectedAreas.contains(x, z)) continue;
            TerrainSampler.Sample s = sampler.sample(x, z);
            if (s == null || sampler.forbiddenHard(s) || s.isOcean || s.isWater) continue;
            int targetHalf = n > 1
                    ? startHalf + (endHalf - startHalf) * ci / (n - 1)
                    : startHalf;
            cells.add(new int[]{x, z, s.surfaceY, targetHalf});
        }
        return cells;
    }

    boolean buildPlanStep(long deadlineNs, int baseR) {
        while (planIdx < path.size() && System.nanoTime() < deadlineNs) {
            int idx = planIdx;

            // Once a merge is active, stop planning any new ribbon cells.
            // The existing road at mergeTarget provides the continuation — painting nothing
            // is correct here. Junction reinforcement (in pruneAndReinforce) will still
            // ensure a physical connection at the merge point via force-paint.
            if (merge.active && idx > merge.hitIndex) {
                planIdx++;
                continue;
            }

            if (merge.isGap(idx)) {
                planIdx++;
                continue;
            }

            int cx = centerXAt(idx);
            int cz = centerZAt(idx);

            core.add(Pos2D.xzKey(cx, cz));

            if (idx == 0) {
                int localR = baseR;
                int targetHalf = line.halfAt(idx);

                addEndCapToPlan(cx, cz, localR, targetHalf, idx);
                planIdx++;
                continue;
            }

            int prevIdx = idx - 1;

            int x0 = centerXAt(prevIdx);
            int z0 = centerZAt(prevIdx);
            int x1 = cx;
            int z1 = cz;

            int r0 = baseR;
            int r1 = baseR;
            int localR = Math.max(r0, r1);

            int half0 = line.halfAt(prevIdx);

            int half1 = line.halfAt(idx);

            addRibbonSegmentToPlan(x0, z0, x1, z1, localR, half0, half1, idx);

            planIdx++;
        }

        return planIdx >= path.size();
    }

    private void addEndCapToPlan(int cx,
                                 int cz,
                                 int localR,
                                 int targetHalf,
                                 int idx) {
        int pad = localR + 1;
        double radius = (double) localR + 0.5;

        for (int x = cx - pad; x <= cx + pad; x++) {
            for (int z = cz - pad; z <= cz + pad; z++) {
                if (!RibbonGeometry.cellIntersectsDisk(x, z, cx + 0.5, cz + 0.5, radius)) {
                    continue;
                }

                double dx = ((double) x + 0.5) - ((double) cx + 0.5);
                double dz = ((double) z + 0.5) - ((double) cz + 0.5);
                double distSq = dx * dx + dz * dz;

                addPlannedRibbonCell(x, z, distSq, targetHalf, idx);
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
                                        int idx) {
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
                if (!RibbonGeometry.cellIntersectsRibbon(x, z, ax, az, bx, bz, halfWidth)) {
                    continue;
                }

                double px = x + 0.5;
                double pz = z + 0.5;
                double t = RibbonGeometry.segmentParam01(px, pz, ax, az, bx, bz);
                double distSq = RibbonGeometry.pointToSegmentDistanceSqClamped(px, pz, ax, az, bx, bz);

                int targetHalf = (int) Math.round((1.0 - t) * (double) half0 + t * (double) half1);
                addPlannedRibbonCell(x, z, distSq, targetHalf, idx);
            }
        }
    }

    private void addPlannedRibbonCell(int x,
                                      int z,
                                      double distSq,
                                      int targetHalf,
                                      int idx) {
        if (bridges.isBridgeCell(x, z)) return;
        // Never plan road cells inside an existing bridge volume (includes supports, lateral blocks).
        if (roadIndex != null && roadIndex.isBridgeVolumeXZ(x, z)) return;
        if (bridges.isNearExistingBridgeDeck(x, z)) return;
        if (protectedAreas != null && protectedAreas.contains(x, z)) return;

        TerrainSampler.Sample s = sampler.sample(x, z);
        if (s == null) return;
        if (sampler.forbiddenHard(s)) return;
        if (s.isOcean || s.isWater) return;
        if (sampler.forbidden(s, TerrainSampler.Mode.PAINTING)) return;

        boolean centerish = distSq <= 0.26;

        if (isExistingRoadNotOurs(x, z) || nearExistingRoadNotOurs(x, z)) {
            if (!centerish) return;
        }

        long k = Pos2D.xzKey(x, z);
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

    private boolean isExistingRoadNotOurs(int x, int z) {
        if (roadIndex == null) return false;
        long k = Pos2D.xzKey(x, z);
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

    void pruneAndReinforce(int baseR) {
        LongOpenHashSet connected = keepOnlyConnectedToCore(planned, core);
        planned.clear();
        planned.addAll(connected);

        junctionReinforcement(baseR);

        // Runs after junctionReinforcement: it may also trim forced cells on the edge.
        applyEdgeNoisePrune(baseR);
    }

    // Removes planned edge cells that fall outside the edge noise boundary for their nearest
    // centerline segment. Core cells (centerline + bridge exits) are always kept.
    // Runs after junctionReinforcement — also removes from forcePaint so shouldPaintCell
    // correctly skips pruned cells even if junctionReinforcement over-filled them.
    private void applyEdgeNoisePrune(int baseR) {
        int[] edgeNoise = line.edgeNoise;
        if (edgeNoise == null || path.isEmpty() || baseR <= 0) return;
        if (RoadCenterline.ROAD_EDGE_VARIATION <= 0) return;

        int n = path.size();

        // Map each centerline XZ position to its path index.
        Long2IntOpenHashMap coreIndexByXZ =
                new Long2IntOpenHashMap(n);
        coreIndexByXZ.defaultReturnValue(-1);
        for (int idx = 0; idx < n; idx++) {
            coreIndexByXZ.put(Pos2D.xzKey(centerXAt(idx), centerZAt(idx)), idx);
        }

        int searchR = baseR + 2;
        long[] planArr = planned.toLongArray();

        for (long k : planArr) {
            if (core.contains(k)) continue; // centerline is never removed

            int x = Pos2D.xFromKey(k);
            int z = Pos2D.zFromKey(k);

            // Find the nearest centerline cell index within searchR.
            double minD2 = Double.MAX_VALUE;
            int bestIdx = -1;
            for (int dcx = -searchR; dcx <= searchR; dcx++) {
                for (int dcz = -searchR; dcz <= searchR; dcz++) {
                    int idx = coreIndexByXZ.get(Pos2D.xzKey(x + dcx, z + dcz));
                    if (idx < 0) continue;
                    double d2 = dcx * dcx + dcz * dcz;
                    if (d2 < minD2) { minD2 = d2; bestIdx = idx; }
                }
            }
            if (bestIdx < 0) continue;

            int en = edgeNoise[bestIdx];
            if (en == 0) continue;

            // Compute local road direction using prev→next centerline cells.
            int prevIdx = Math.max(0, bestIdx - 1);
            int nextIdx = Math.min(n - 1, bestIdx + 1);
            double ax = centerXAt(prevIdx) + 0.5;
            double az = centerZAt(prevIdx) + 0.5;
            double bx = centerXAt(nextIdx) + 0.5;
            double bz = centerZAt(nextIdx) + 0.5;
            double segDx = bx - ax;
            double segDz = bz - az;
            double segLen = Math.sqrt(segDx * segDx + segDz * segDz);
            if (segLen < 1e-9) continue;

            // Signed perpendicular offset: positive = left of A→B, negative = right.
            double px = x + 0.5;
            double pz = z + 0.5;
            double cross = (px - ax) * segDz - (pz - az) * segDx;
            double signedPerp = cross / segLen;
            double halfWidth = baseR + 0.5;

            boolean prune = (en > 0 && signedPerp > (halfWidth - en))
                    || (en < 0 && signedPerp < -(halfWidth + en));
            if (prune) {
                planned.remove(k);
                forcePaint.remove(k);
            }
        }
    }

    private LongOpenHashSet keepOnlyConnectedToCore(LongOpenHashSet plan, LongOpenHashSet coreSet) {
        LongOpenHashSet keep = new LongOpenHashSet(Math.max(16, plan.size()));
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
            int x = Pos2D.xFromKey(k);
            int z = Pos2D.zFromKey(k);

            long n0 = Pos2D.xzKey(x + 1, z);
            long n1 = Pos2D.xzKey(x - 1, z);
            long n2 = Pos2D.xzKey(x, z + 1);
            long n3 = Pos2D.xzKey(x, z - 1);

            if (plan.contains(n0) && keep.add(n0)) q.add(n0);
            if (plan.contains(n1) && keep.add(n1)) q.add(n1);
            if (plan.contains(n2) && keep.add(n2)) q.add(n2);
            if (plan.contains(n3) && keep.add(n3)) q.add(n3);
        }

        return keep;
    }

    private void junctionReinforcement(int baseR) {
        int reinR = baseR + 1;

        // INVARIANT: a disk is forced only where the plan meets another road (any planned neighbour count would
        // fire on every centerline cell of a road wider than 1 and widen the whole road with unsmoothed cells).
        long[] planArr = planned.toLongArray();
        for (long k : planArr) {
            int x = Pos2D.xFromKey(k);
            int z = Pos2D.zFromKey(k);

            boolean existing = (roadIndex != null && roadIndex.isRoadXZ(x, z));
            if (!existing) continue;

            fillDiskForced(x, z, reinR);
        }

        // Gap boundary connectors: force-paint a line + disk at each gap entry/exit so the
        // new path is physically connected to the existing road across the suppressed section.
        if (merge.gapBoundaryCount > 0) {
            for (int gi = 0; gi < merge.gapBoundaryCount; gi++) {
                int pi = merge.gapBoundaryPathIdx[gi];
                if (pi < 0 || pi >= path.size()) continue;
                int lx = centerXAt(pi);
                int lz = centerZAt(pi);
                fillDiskForced(merge.gapBoundaryRoadX[gi], merge.gapBoundaryRoadZ[gi], reinR);
                fillLineForced(lx, lz, merge.gapBoundaryRoadX[gi], merge.gapBoundaryRoadZ[gi]);
            }
        }

        if (merge.active) {
            fillDiskForced(merge.targetX, merge.targetZ, reinR);
            if (merge.hitIndex >= 0 && merge.hitIndex < path.size()) {
                int lx = centerXAt(merge.hitIndex);
                int lz = centerZAt(merge.hitIndex);
                fillLineForced(lx, lz, merge.targetX, merge.targetZ);

                int startHalf = line.halfAt(merge.hitIndex);
                Integer roadHalfVal = RoadJoins.findRoadSurfaceHalfY(
                        world, sampler, merge.targetX, merge.targetZ);
                int endHalf = roadHalfVal != null ? roadHalfVal : startHalf;

                mergeConnectorList = buildMergeConnectorList(
                        lx, lz, startHalf, merge.targetX, merge.targetZ, endHalf);
                if (UltreiaConfig.DEBUG.logPainting) {
                    Ultreia.LOGGER.info(
                            "[Ultreia] Merge connector: ({},{}) half={} -> ({},{}) half={} cells={}",
                            lx, lz, startHalf, merge.targetX, merge.targetZ, endHalf,
                            mergeConnectorList == null ? 0 : mergeConnectorList.size());
                }
            }
        }
    }

    private void fillDiskForced(int cx, int cz, int rr) {
        for (int dx = -rr; dx <= rr; dx++) {
            for (int dz = -rr; dz <= rr; dz++) {
                if (dx * dx + dz * dz > rr * rr) continue;

                int x = cx + dx;
                int z = cz + dz;

                if (bridges.isBridgeCell(x, z)) continue;
                // Never force-paint on an existing bridge deck — that would place road blocks
                // at the wrong Y and break the bridge structure.
                if (roadIndex != null && roadIndex.isBridgeXZ(x, z)) continue;
                if (protectedAreas != null && protectedAreas.contains(x, z)) continue;

                TerrainSampler.Sample s = sampler.sample(x, z);
                if (sampler.forbiddenHard(s)) continue;
                if (s.isOcean || s.isWater) continue;
                if (sampler.forbidden(s, TerrainSampler.Mode.PAINTING)) continue;

                long k = Pos2D.xzKey(x, z);
                planned.add(k);
                forcePaint.add(k);
            }
        }
    }

    private void fillLineForced(int x0, int z0, int x1, int z1) {
        List<BlockPos> line = LineRaster.supercover(x0, z0, x1, z1, 0);
        if (line == null) return;
        for (BlockPos p : line) {
            int x = p.getX();
            int z = p.getZ();
            if (bridges.isBridgeCell(x, z)) continue;
            if (roadIndex != null && roadIndex.isBridgeXZ(x, z)) continue;
            if (protectedAreas != null && protectedAreas.contains(x, z)) continue;
            TerrainSampler.Sample s = sampler.sample(x, z);
            if (s == null || sampler.forbiddenHard(s) || s.isOcean || s.isWater) continue;
            if (sampler.forbidden(s, TerrainSampler.Mode.PAINTING)) continue;
            long k = Pos2D.xzKey(x, z);
            planned.add(k);
            forcePaint.add(k);
        }
    }

    boolean shouldPaintCell(int x, int z) {
        if (bridges.isBridgeCell(x, z)) return false;
        long k = Pos2D.xzKey(x, z);
        return forcePaint.contains(k) || planned.contains(k);
    }
}

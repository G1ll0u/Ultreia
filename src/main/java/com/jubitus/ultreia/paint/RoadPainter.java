package com.jubitus.ultreia.paint;

import com.jubitus.ultreia.bridge.BridgeDeckProfile;
import com.jubitus.ultreia.terrain.ColumnSurface;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.geom.LineRaster;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.roads.RoadJoins;
import com.jubitus.ultreia.bridge.BridgeGeometry;
import com.jubitus.ultreia.bridge.BridgeSegmentPainter;
import com.jubitus.ultreia.bridge.BridgeSpan;
import com.jubitus.ultreia.terrain.BlockRules;
import com.jubitus.ultreia.terrain.ProtectedAreas;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.route.RoadType;
import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.bridge.template.BridgeBuildService;
import com.jubitus.ultreia.bridge.template.BridgeTemplate;
import com.jubitus.ultreia.bridge.template.BridgeTemplateRegistry;
import com.jubitus.ultreia.mill.MillPathPalette;
import com.jubitus.ultreia.roads.RoadIndex;
import com.jubitus.ultreia.roads.RoadNetwork;
import com.jubitus.ultreia.roads.RoadSegment;
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

    // Clear 2 blocks above the road to ensure walkability.
    private static final boolean CLEAR_HEADROOM = true;
    // Maximum number of blocks trimmed from one edge of the road to create organic width variation.
    private static final int ROAD_EDGE_VARIATION = 1;
    // Average number of road blocks between edge-variation changes.
    private static final int ROAD_EDGE_VARIATION_PERIOD = 8;
    // If true, the painter skips waypoints that run within road-width of an already-painted road, then resumes...
    private static final boolean SUPPRESS_PARALLEL_PAINTING = true;
    // Shoulder width used to taper earthworks away from the road surface.
    private static final int EARTHWORKS_SHOULDER = 3;
    // Force-paint this many blocks after bridge exit to secure road continuation.
    private static final int FORCE_PAINT_EXIT_BLOCKS = 2;


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
        private int[] gradeY = null;

        private int baseR;
        private final WorldServer world;
        private final TerrainSampler sampler;
        private final ProtectedAreas protectedAreas;
        private final boolean dryRun;

        private int r;

        private RoadType roadType = RoadType.VILLAGE_MAIN;

        private final List<BlockPos> coarse;
        private final List<BridgeSpan> forcedBridges;
        private final BridgeSegmentPainter bridges;
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

        private int[] edgeNoise;

        private final it.unimi.dsi.fastutil.longs.LongOpenHashSet planned = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        private final it.unimi.dsi.fastutil.longs.LongOpenHashSet core = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        private final it.unimi.dsi.fastutil.longs.LongOpenHashSet painted = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

        private final RoadIndex roadIndex;
        private final it.unimi.dsi.fastutil.longs.LongOpenHashSet newlyPlaced = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        // Paint diagnostics (DEBUG.logPainting): one summary line per edge.
        private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap placedHalf = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        private int dbgForced, dbgForcedNoFit, dbgNoSupport, dbgDtRoot;
        private final StringBuilder dbgForcedNoFitAt = new StringBuilder(), dbgNoSupportAt = new StringBuilder(), dbgDtRootAt = new StringBuilder();
        @javax.annotation.Nullable private RoadNetwork roadNetwork;
        @javax.annotation.Nullable private net.minecraft.util.math.BlockPos fromVillage;
        @javax.annotation.Nullable private net.minecraft.util.math.BlockPos toVillage;

        private final it.unimi.dsi.fastutil.longs.LongOpenHashSet forcePaint = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

        private boolean talusPassDone = false;

        // Elbow fill cells for 1-wide paths: painted directly after the main loop (cursor never reaches them).
        private List<int[]> elbowFillList = null;
        private boolean elbowFillsPainted = false;
        private boolean forcedLeftoversPainted = false;
        // Talus work list, built once at the end of the edge and applied over several ticks.
        private long[] talusCells = null;
        private int talusCursor = 0;
        private it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap talusDist;
        private it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap talusNearestRoad;
        private it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap roadSurfaceY;
        private double talusSlopeRatio;
        private int talusMaxCut, talusMaxFill;

        private boolean mergeStopArmed = false;
        private int mergeStopAt = Integer.MAX_VALUE;

        private boolean mergeActive = false;
        private int mergeTargetX = 0;
        private int mergeTargetZ = 0;
        private int mergeHitIndex = Integer.MAX_VALUE;

        // Connector cells [x, z, surfaceY] to paint explicitly when merge-stop fires.
        private List<int[]> mergeConnectorList = null;
        private boolean mergeConnectorPainted = false;

        private boolean[] gapByIdx = null;
        private it.unimi.dsi.fastutil.longs.LongSet reusedBridgeXZ = it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET;

        // Gap boundary info: (pathIdx, roadX, roadZ) tuples used in junctionReinforcement
        // to force-paint connectors between the new path and the existing road at each gap edge.
        private int[] gapBoundaryPathIdx = null;
        private int[] gapBoundaryRoadX = null;
        private int[] gapBoundaryRoadZ = null;
        private int gapBoundaryCount = 0;

        private final boolean allowEarlyMerge;

        private BridgeSpan pendingBridge = null;
        private int pendingBridgeEndIdx = -1;

        private int[] centerHalf = null;

        private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap plannedHalfByXZ = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap plannedPriorityByXZ = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

        Session(WorldServer world,
                List<BlockPos> coarsePath,
                List<BridgeSpan> forcedBridges,
                TerrainSampler sampler,
                ProtectedAreas protectedAreas,
                RoadIndex roadIndex,
                boolean dryRun,
                boolean allowEarlyMerge) {
            this.world = world;
            this.allowEarlyMerge = allowEarlyMerge;
            this.sampler = sampler;
            this.protectedAreas = protectedAreas;
            this.roadIndex = roadIndex;
            this.dryRun = dryRun;
            this.forcedBridges = forcedBridges == null ? Collections.<BridgeSpan>emptyList() : forcedBridges;
            this.bridges = new BridgeSegmentPainter(world, sampler, protectedAreas, roadIndex, this.forcedBridges);

            this.plannedHalfByXZ.defaultReturnValue(Integer.MIN_VALUE);
            this.plannedPriorityByXZ.defaultReturnValue(Integer.MAX_VALUE);


            this.baseR = roadType.roadWidthBlocks() / 2;
            this.r = baseR;

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
                    bridges.buildFootprint();

                    detectEarlyMergeOnce();

                    if (roadType.allowsEarthworks()) {
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
                    if (baseR == 0) {
                        addDiagonalElbowFills();
                    }
                    continue;
                }

                if (!planPruned) {
                    pruneAndReinforce();
                    planPruned = true;
                    continue;
                }

                if (mergeStopArmed && i > mergeStopAt) {
                    if (pendingBridge != null) finishPendingBridge(" at end of edge", false);
                    if (!mergeConnectorPainted) {
                        mergeConnectorPainted = true;
                        paintMergeConnectorDirect();
                    }
                    if (!elbowFillsPainted) {
                        elbowFillsPainted = true;
                        paintElbowFillsDirect();
                    }
                    if (!forcedLeftoversPainted) {
                        forcedLeftoversPainted = true;
                        paintForcedLeftovers();
                    }
                    if (!talusPassDone) {
                        if (!talusStep(deadlineNs)) return PaintResult.RUNNING;
                        talusPassDone = true;
                    }
                    logPaintSummary();
                    registerRoadSegment();
                    return PaintResult.OK;
                }

                if (i >= path.size()) {
                    if (pendingBridge != null) finishPendingBridge(" at final path end", false);
                    if (!elbowFillsPainted) {
                        elbowFillsPainted = true;
                        paintElbowFillsDirect();
                    }
                    if (!forcedLeftoversPainted) {
                        forcedLeftoversPainted = true;
                        paintForcedLeftovers();
                    }
                    if (!talusPassDone) {
                        if (!talusStep(deadlineNs)) return PaintResult.RUNNING;
                        talusPassDone = true;
                    }
                    logPaintSummary();
                    registerRoadSegment();
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

                if (pendingBridge != null) {
                    if (i >= pendingBridgeEndIdx) {
                        finishPendingBridge("", true);
                        i++;
                        continue;
                    }
                }

                ForcedBridgeRef forced = forcedByStartKey.get(Pos2D.xzKey(p.getX(), p.getZ()));
                if (forced != null && forced.startIdx == i) {
                    pendingBridge = forced.span;
                    pendingBridgeEndIdx = forced.endIdx;
                    i++;
                    continue;
                }

                beginPaintSweep(p, i);
            }

            return PaintResult.RUNNING;
        }


        public String failureReason() {
            return failReason;
        }

        /** Must be called before advance(): deck centerline xz keys of built bridges the path crosses. */
        public void setReusedBridges(it.unimi.dsi.fastutil.longs.LongSet deckXZ) {
            this.reusedBridgeXZ = deckXZ;
        }

        public void setRoadNetwork(@javax.annotation.Nullable RoadNetwork network,
                                   @javax.annotation.Nullable net.minecraft.util.math.BlockPos from,
                                   @javax.annotation.Nullable net.minecraft.util.math.BlockPos to) {
            this.roadNetwork = network;
            this.fromVillage = from;
            this.toVillage = to;
        }

        /**
         * Must be called before advance(). Adjusts road width and earthworks behaviour
         * based on the road importance type.
         */
        public void setRoadType(RoadType type) {
            this.roadType = (type != null) ? type : RoadType.VILLAGE_MAIN;
            this.baseR = this.roadType.roadWidthBlocks() / 2;
            this.r = this.baseR;
        }

        private int paintedWidthBlocks() {
            return baseR * 2 + 1;
        }

        private void indexForcedBridges() {
            forcedByStartKey.clear();
            for (BridgeSpan span : forcedBridges) {
                if (span == null) continue;
                int s = findIndexForXZ(span.start.getX(), span.start.getZ(), 0);
                int e = findIndexForXZ(span.end.getX(), span.end.getZ(), Math.max(0, s));
                if (s < 0 || e < 0 || e <= s) {
                    Ultreia.LOGGER.warn("Skipping forced bridge span: unresolved indices start=({}, {}) end=({}, {}) s={} e={}",
                            span.start.getX(), span.start.getZ(),
                            span.end.getX(), span.end.getZ(),
                            s, e);
                    continue;
                }
                forcedByStartKey.put(Pos2D.xzKey(span.start.getX(), span.start.getZ()), new ForcedBridgeRef(span, s, e));
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

            boolean isLoneTrail = roadType == RoadType.LONE_TRAIL;

            // LONE_TRAIL may have very short paths (lonebuilding right next to a road).
            if (!isLoneTrail && path.size() < 8) return;
            if (path.size() < 3) return;

            int exitBlocks = Math.max(0, FORCE_PAINT_EXIT_BLOCKS);

            // Hard minimum: never merge before own forced bridges are fully painted.
            int absoluteMin = Math.max(isLoneTrail ? 3 : 6, baseR + 3);
            for (ForcedBridgeRef ref : forcedByStartKey.values()) {
                int past = ref.endIdx + exitBlocks + 1;
                if (past > absoluteMin) absoluteMin = past;
            }

            // LONE_TRAIL scans to the very end of the path so it can merge even when the road
            // is reached late (e.g. after A* road-snap, or when the road runs at the destination).
            int scanTo = isLoneTrail
                    ? path.size() - 2
                    : path.size() - 1 - Math.max(6, baseR + 3);
            if (scanTo < absoluteMin) return;

            int maxJoinDeltaHalf = Math.max(0, HalfY.MAX_ROAD_JOIN_DELTA_HALF_BLOCKS);
            if (isLoneTrail) {
                // LONE_TRAIL uses a more lenient height tolerance so terrain roughness doesn't
                // prevent a valid graft onto the network.
                maxJoinDeltaHalf = Math.max(maxJoinDeltaHalf, 3);
            }

            int mergeRadius = isLoneTrail
                    ? Math.max(2, RoadType.LONE_TRAIL_MERGE_SEARCH_RADIUS)
                    : 2;

            // Minimum world-distance (squared) the merge target must be from the path origin.
            // Prevents merging into road blocks belonging to the same village or lonebuilding
            // from which the path departs (e.g. Millénaire internal paths).
            BlockPos origin = path.get(0);
            int startMinBlocks = isLoneTrail
                    ? Math.max(20, RoadType.LONE_TRAIL_MERGE_SEARCH_RADIUS + 10)
                    : 12;
            int startMinDistSq = startMinBlocks * startMinBlocks;

            for (int idx = absoluteMin; idx <= scanTo; idx++) {
                BlockPos p = path.get(idx);

                BlockPos near = roadIndex.findNearestRoad(p.getX(), p.getZ(), mergeRadius);
                if (near == null) continue;

                long nk = Pos2D.xzKey(near.getX(), near.getZ());
                if (newlyPlaced.contains(nk)) continue;

                // Skip road blocks inside any village/building footprint — they are internal paths
                // that belong to the departure or arrival structure, not the inter-village network.
                if (protectedAreas != null && protectedAreas.contains(near.getX(), near.getZ())) continue;

                // Skip road blocks that are too close to our own starting position (same-building paths).
                int odx = near.getX() - origin.getX();
                int odz = near.getZ() - origin.getZ();
                if (odx * odx + odz * odz < startMinDistSq) continue;

                if (roadIndex.isBridgeXZ(near.getX(), near.getZ())) {
                    near = findNearestNonBridgeRoad(p.getX(), p.getZ(), mergeRadius);
                    if (near == null) continue;
                    // Re-check protected areas and start distance after bridge fallback.
                    if (protectedAreas != null && protectedAreas.contains(near.getX(), near.getZ())) continue;
                    odx = near.getX() - origin.getX();
                    odz = near.getZ() - origin.getZ();
                    if (odx * odx + odz * odz < startMinDistSq) continue;
                }

                // LONE_TRAIL grafts onto any existing road; other types stop only on a road that leads to the destination.
                if (!isLoneTrail && !isRoadConnectedToDestination(near.getX(), near.getZ())) continue;

                // The connector climbs at most one half-block per cell, so the height gap is capped by its length.
                int connectorCells = Math.max(Math.abs(p.getX() - near.getX()), Math.abs(p.getZ() - near.getZ()));
                if (!RoadJoins.canMergeCleanlyToRoad(
                        world, sampler, roadIndex,
                        p.getX(), p.getY(), p.getZ(),
                        near.getX(), near.getZ(),
                        Math.min(maxJoinDeltaHalf, connectorCells)
                )) {
                    continue;
                }

                mergeActive = true;
                mergeTargetX = near.getX();
                mergeTargetZ = near.getZ();
                mergeHitIndex = idx;
                mergeStopArmed = true;
                mergeStopAt = mergeHitIndex;

                Ultreia.LOGGER.info(
                        "[Ultreia] Early merge: path index {}/{} -> road ({}, {}), stopping paint here",
                        idx, path.size() - 1, near.getX(), near.getZ());
                return;
            }

            // After the end-merge scan: detect mid-path sections that run parallel to existing roads.
            detectParallelGaps(absoluteMin, exitBlocks);
        }

        /**
         * Scans the full (non-merged) path for sections running within road-width of an existing road.
         * Marks those sections in {@code gapByIdx} so the painter skips them. Records gap boundary
         * path indices in {@code gapBoundary*} arrays so {@link #junctionReinforcement()} can
         * force-paint physical connectors at each entry/exit.
         */
        private void detectParallelGaps(int absoluteMin, int exitBlocks) {
            if (!SUPPRESS_PARALLEL_PAINTING) return;
            if (roadIndex == null) return;

            // Proximity radius: fire when any existing road block is this close to our path center.
            int overlapRadius = Math.max(2, baseR + 2);
            // Suppress only runs longer than this to avoid spurious single-block skips.
            int minGapLen = Math.max(3, baseR * 2 + 2);

            // Stop before the end-merge region (if armed) and leave a clean tail.
            int scanEnd = mergeActive
                    ? mergeHitIndex - 1
                    : (path.size() - 1 - Math.max(6, baseR + 3));
            if (scanEnd < absoluteMin + minGapLen) return;

            // Bridge-protection buffer: don't gap into or out of any forced bridge span.
            int bridgeBuffer = Math.max(0, exitBlocks + 2);

            BlockPos origin = path.get(0);
            int startMinDistSq = 16 * 16;

            // Per-index overlap state.
            boolean[] inOverlap = new boolean[path.size()];
            int[] nearX = new int[path.size()];
            int[] nearZ = new int[path.size()];

            for (int idx = absoluteMin; idx <= scanEnd; idx++) {
                BlockPos p = path.get(idx);
                // A built bridge the path crosses: the road stops at its start and resumes at its end.
                if (reusedBridgeXZ.contains(Pos2D.xzKey(p.getX(), p.getZ()))) {
                    inOverlap[idx] = true;
                    nearX[idx] = p.getX();
                    nearZ[idx] = p.getZ();
                    continue;
                }

                // Skip bridge-protected indices.
                boolean inBridge = false;
                for (ForcedBridgeRef ref : forcedByStartKey.values()) {
                    if (idx >= ref.startIdx - bridgeBuffer && idx <= ref.endIdx + bridgeBuffer) {
                        inBridge = true;
                        break;
                    }
                }
                if (inBridge) continue;

                BlockPos near = roadIndex.findNearestRoad(p.getX(), p.getZ(), overlapRadius);
                if (near == null) continue;

                long nk = Pos2D.xzKey(near.getX(), near.getZ());
                if (newlyPlaced.contains(nk)) continue;
                if (roadIndex.isBridgeXZ(near.getX(), near.getZ())) continue;
                if (protectedAreas != null && protectedAreas.contains(near.getX(), near.getZ())) continue;

                int odx = near.getX() - origin.getX();
                int odz = near.getZ() - origin.getZ();
                if (odx * odx + odz * odz < startMinDistSq) continue;

                inOverlap[idx] = true;
                nearX[idx] = near.getX();
                nearZ[idx] = near.getZ();
            }

            // Collect gap runs and mark gapByIdx.
            boolean[] gaps = new boolean[path.size()];
            // Temporary boundary storage: up to (runs * 2) entries; 64 should be more than enough.
            int maxBoundaries = 64;
            int[] bPathIdx = new int[maxBoundaries];
            int[] bRoadX   = new int[maxBoundaries];
            int[] bRoadZ   = new int[maxBoundaries];
            int bCount = 0;

            boolean anyGap = false;
            int runStart = -1;

            for (int idx = absoluteMin; idx <= scanEnd + 1; idx++) {
                boolean overlapping = idx <= scanEnd && inOverlap[idx];

                if (overlapping && runStart < 0) {
                    runStart = idx;
                } else if (!overlapping && runStart >= 0) {
                    int runEnd = idx - 1;
                    int runLen = runEnd - runStart + 1;

                    if (runLen >= minGapLen) {
                        for (int gi = runStart; gi <= runEnd; gi++) {
                            gaps[gi] = true;
                            anyGap = true;
                        }

                        // Entry boundary: last painted waypoint before gap → road.
                        if (runStart > absoluteMin && bCount < maxBoundaries - 1) {
                            bPathIdx[bCount] = runStart - 1;
                            bRoadX[bCount]   = nearX[runStart];
                            bRoadZ[bCount]   = nearZ[runStart];
                            bCount++;
                        }

                        // Exit boundary: road → first painted waypoint after gap.
                        if (runEnd + 1 < path.size() && bCount < maxBoundaries - 1) {
                            bPathIdx[bCount] = runEnd + 1;
                            bRoadX[bCount]   = nearX[runEnd];
                            bRoadZ[bCount]   = nearZ[runEnd];
                            bCount++;
                        }

                        if (UltreiaConfig.DEBUG.logPainting) {
                            Ultreia.LOGGER.info(
                                    "[Ultreia] Parallel gap suppressed: path[{}..{}] len={} near=({},{})",
                                    runStart, runEnd, runLen, nearX[runStart], nearZ[runStart]);
                        }
                    }
                    runStart = -1;
                }
            }

            if (!anyGap) return;

            gapByIdx = gaps;
            if (bCount > 0) {
                gapBoundaryPathIdx = java.util.Arrays.copyOf(bPathIdx, bCount);
                gapBoundaryRoadX   = java.util.Arrays.copyOf(bRoadX, bCount);
                gapBoundaryRoadZ   = java.util.Arrays.copyOf(bRoadZ, bCount);
                gapBoundaryCount   = bCount;
            }
        }

        private boolean isRoadConnectedToDestination(int x, int z) {
            if (roadNetwork == null || toVillage == null) return false;
            int tvx = toVillage.getX(), tvz = toVillage.getZ();
            for (RoadSegment seg : roadNetwork.getSegmentsNear(x, z, 20)) {
                if (villageMatches(seg.toVillage, tvx, tvz) || villageMatches(seg.fromVillage, tvx, tvz))
                    return true;
            }
            return false;
        }

        private static boolean villageMatches(@javax.annotation.Nullable BlockPos v, int x, int z) {
            if (v == null) return false;
            int dx = v.getX() - x, dz = v.getZ() - z;
            return dx * dx + dz * dz <= 256; // 16-block tolerance
        }

        private BlockPos findNearestNonBridgeRoad(int x, int z, int maxRadius) {
            for (int r = 0; r <= maxRadius; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (r > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                        int nx = x + dx, nz = z + dz;
                        if (!roadIndex.isRoadXZ(nx, nz)) continue;
                        if (roadIndex.isBridgeXZ(nx, nz)) continue;
                        if (newlyPlaced.contains(Pos2D.xzKey(nx, nz))) continue;
                        return new BlockPos(nx, 0, nz);
                    }
                }
            }
            return null;
        }

        private boolean densifyStep(long deadlineNs) {
            while (densifyIndex < coarse.size() && System.nanoTime() < deadlineNs) {
                BlockPos a = coarse.get(densifyIndex - 1);
                BlockPos b = coarse.get(densifyIndex);

                List<BlockPos> line;
                try {
                    line = LineRaster.forBridges(a.getX(), a.getZ(), b.getX(), b.getZ(), 0);
                } catch (Throwable t) {
                    failed = true;
                    failReason = "densify failed on segment (" + a.getX() + "," + a.getZ() + ") -> (" + b.getX() + "," + b.getZ() + "): " + t.getMessage();
                    return true;
                }

                if (line == null || line.isEmpty()) {
                    densifyIndex++;
                    continue;
                }

                // Always finish the segment: densifyIndex only advances per whole segment.
                for (int k = 1; k < line.size(); k++) {
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
            edgeNoise = new int[n];

            try {
                centerHalf = RoadHeightProfile.build(path, forcedBridges, sampler, roadType);
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

            buildEdgeVariationProfile();

        }

        private void buildEdgeVariationProfile() {
            int n = edgeNoise.length;
            if (n == 0) return;

            int maxOffset = ROAD_EDGE_VARIATION;
            if (maxOffset <= 0 || baseR <= 0) return;

            // Clamp maxOffset so we never reduce the road to 0-wide.
            maxOffset = Math.min(maxOffset, baseR);

            int period = Math.max(2, ROAD_EDGE_VARIATION_PERIOD);

            BlockPos start = path.get(0);
            java.util.Random rng = new java.util.Random(
                    (long) start.getX() * 73856093L ^ (long) start.getZ() * 19349663L);

            // For a 3-wide road (baseR == 1) one side must always be trimmed: never allow current == 0.
            boolean forceNonZero = (baseR == 1 && maxOffset >= 1);
            int current = forceNonZero ? (rng.nextBoolean() ? 1 : -1) : 0;
            // Stagger the first change so different paths don't all change at the same step.
            int stepsUntilChange = period / 2 + rng.nextInt(Math.max(1, period));

            for (int idx = 0; idx < n; idx++) {
                // Keep endpoints at 0 for clean terminal connections.
                if (idx == 0 || idx == n - 1) {
                    edgeNoise[idx] = 0;
                    continue;
                }
                if (stepsUntilChange <= 0) {
                    int delta = rng.nextInt(3) - 1; // -1, 0, +1
                    current = Math.max(-maxOffset, Math.min(maxOffset, current + delta));
                    // On a 3-wide road, skip 0 — flip to the same sign the delta was heading.
                    if (forceNonZero && current == 0) current = (delta >= 0) ? 1 : -1;
                    stepsUntilChange = period / 2 + rng.nextInt(Math.max(1, period));
                }
                edgeNoise[idx] = current;
                stepsUntilChange--;
            }

            if (UltreiaConfig.DEBUG.logPainting) {
                Ultreia.LOGGER.debug("[Ultreia] edgeNoise built: n={} maxOffset={} period={}", n, maxOffset, period);
            }
        }

        private int centerXAt(int idx) {
            return path.get(idx).getX();
        }

        private int centerZAt(int idx) {
            return path.get(idx).getZ();
        }

        // For 1-wide paths, add a fill block at each diagonal step to smooth the staircase.
        // Pattern: for step (x0,z0)→(x0+dx,z0+dz) where |dx|=|dz|=1,
        // add (x0+dx, z0) — the "X-first companion" that rounds the diagonal corner.
        private void addDiagonalElbowFills() {
            // Never add elbow fills past the merge point — post-merge path cells belong to the
            // existing road and must not produce stray blocks beside it.
            int limit = (mergeActive && mergeHitIndex >= 0)
                    ? Math.min(path.size(), mergeHitIndex + 1)
                    : path.size();
            for (int idx = 1; idx < limit; idx++) {
                int x0 = centerXAt(idx - 1);
                int z0 = centerZAt(idx - 1);
                int x1 = centerXAt(idx);
                int z1 = centerZAt(idx);
                int dx = x1 - x0;
                int dz = z1 - z0;

                if (Math.abs(dx) != 1 || Math.abs(dz) != 1) continue;

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

        private void paintElbowFillsDirect() {
            if (elbowFillList == null) return;
            for (int[] ef : elbowFillList) {
                long k = Pos2D.xzKey(ef[0], ef[1]);
                if (!painted.contains(k)) {
                    paintSingle(ef[0], ef[1], ef[2]);
                    painted.add(k);
                }
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

        /**
         * Forced cells (junction disks, gap connectors, bridge exits) can lie outside the sweep radius.
         * Each takes the terrain height clamped to within half a block of every placed or planned
         * 4-neighbour, spreading outward; a cell with no neighbour or no such height stays unpainted.
         */
        private void paintForcedLeftovers() {
            it.unimi.dsi.fastutil.longs.LongArrayList pending = new it.unimi.dsi.fastutil.longs.LongArrayList();
            for (long k : forcePaint) {
                if (!painted.contains(k)) pending.add(k);
            }
            boolean progress = true;
            while (progress && !pending.isEmpty()) {
                progress = false;
                for (int pi = pending.size() - 1; pi >= 0; pi--) {
                    long k = pending.getLong(pi);
                    int x = Pos2D.xFromKey(k);
                    int z = Pos2D.zFromKey(k);
                    int half = walkableForcedHalf(x, z);
                    if (half == Integer.MIN_VALUE) continue;
                    dbgForced++;
                    plannedHalfByXZ.put(k, half);
                    paintSingle(x, z, sampler.sample(x, z).surfaceY);
                    painted.add(k);
                    pending.removeLong(pi);
                    progress = true;
                }
            }
            dbgForcedNoFit += pending.size();
            for (int pi = 0; pi < pending.size(); pi++) {
                long k = pending.getLong(pi);
                dbgSample(dbgForcedNoFitAt, pi + 1, Pos2D.xFromKey(k),
                        HalfY.ofBlockTop(sampler.sample(Pos2D.xFromKey(k), Pos2D.zFromKey(k)).surfaceY),
                        Pos2D.zFromKey(k));
            }
        }

        // INVARIANT: a forced cell never steps more than half a block from a placed or planned 4-neighbour.
        private int walkableForcedHalf(int x, int z) {
            int lo = Integer.MIN_VALUE;
            int hi = Integer.MAX_VALUE;
            int[][] n4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : n4) {
                long nk = Pos2D.xzKey(x + d[0], z + d[1]);
                int h = placedHalf.containsKey(nk) ? placedHalf.get(nk) : plannedHalfByXZ.get(nk);
                if (h == Integer.MIN_VALUE) continue;
                lo = Math.max(lo, h - HalfY.MAX_ROAD_STEP_HALF_PER_BLOCK);
                hi = Math.min(hi, h + HalfY.MAX_ROAD_STEP_HALF_PER_BLOCK);
            }
            if (lo == Integer.MIN_VALUE || lo > hi) return Integer.MIN_VALUE;
            int terrain = HalfY.ofBlockTop(sampler.sample(x, z).surfaceY);
            return Math.max(lo, Math.min(hi, terrain));
        }

        private static void dbgSample(StringBuilder sb, int count, int x, int half, int z) {
            if (count <= 5) sb.append(" (").append(x).append(',').append(HalfY.walkBlockYFloor(half)).append(',').append(z).append(')');
        }

        /** One summary line per edge: suspicious side cells and steps above half a block between painted cells. */
        private void logPaintSummary() {
            if (!UltreiaConfig.DEBUG.logPainting) return;
            int steps = 0;
            StringBuilder stepAt = new StringBuilder();
            for (it.unimi.dsi.fastutil.longs.Long2IntMap.Entry e : placedHalf.long2IntEntrySet()) {
                int x = Pos2D.xFromKey(e.getLongKey());
                int z = Pos2D.zFromKey(e.getLongKey());
                int h = e.getIntValue();
                int[][] n2 = {{1, 0}, {0, 1}};
                for (int[] d : n2) {
                    long nk = Pos2D.xzKey(x + d[0], z + d[1]);
                    if (!placedHalf.containsKey(nk)) continue;
                    if (Math.abs(placedHalf.get(nk) - h) > 1) {
                        steps++;
                        dbgSample(stepAt, steps, x, Math.max(h, placedHalf.get(nk)), z);
                    }
                }
            }
            Ultreia.LOGGER.info("[Ultreia paint] edge done: placed={} stepsOverHalf={}{} forced={} forcedNoWalkableHeight={}{}"
                            + " skippedNoSupport={}{} dtRootRepaints={}{}",
                    placedHalf.size(), steps, stepAt, dbgForced, dbgForcedNoFit, dbgForcedNoFitAt,
                    dbgNoSupport, dbgNoSupportAt, dbgDtRoot, dbgDtRootAt);
        }

        private void paintMergeConnectorDirect() {
            if (mergeConnectorList == null) return;
            for (int[] cell : mergeConnectorList) {
                long k = Pos2D.xzKey(cell[0], cell[1]);
                if (!painted.contains(k)) {
                    if (cell.length > 3) {
                        plannedHalfByXZ.put(k, cell[3]);
                    }
                    paintSingle(cell[0], cell[1], cell[2]);
                    painted.add(k);
                }
            }
        }

        private boolean buildPlanStep(long deadlineNs) {
            boolean earthworks = earthworksEnabled();

            while (planIdx < path.size() && System.nanoTime() < deadlineNs) {
                int idx = planIdx;

                // Once a merge is active, stop planning any new ribbon cells.
                // The existing road at mergeTarget provides the continuation — painting nothing
                // is correct here. Junction reinforcement (in pruneAndReinforce) will still
                // ensure a physical connection at the merge point via force-paint.
                if (mergeActive && idx > mergeHitIndex) {
                    planIdx++;
                    continue;
                }

                if (gapByIdx != null && idx < gapByIdx.length && gapByIdx[idx]) {
                    planIdx++;
                    continue;
                }

                BlockPos center = path.get(idx);

                int cx = centerXAt(idx);
                int cz = centerZAt(idx);

                core.add(Pos2D.xzKey(cx, cz));

                if (idx == 0) {
                    int localR = baseR;
                    int targetHalf = centerHalf != null && idx < centerHalf.length
                            ? centerHalf[idx]
                            : HalfY.ofBlockTop(center.getY());

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

                int r0 = baseR;
                int r1 = baseR;
                int localR = Math.max(r0, r1);

                int half0 = centerHalf != null && prevIdx < centerHalf.length
                        ? centerHalf[prevIdx]
                        : HalfY.ofBlockTop(prev.getY());

                int half1 = centerHalf != null && idx < centerHalf.length
                        ? centerHalf[idx]
                        : HalfY.ofBlockTop(center.getY());

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

            if (!earthworks) {
                BlockPos top = new BlockPos(x, s.surfaceY, z);
                IBlockState g = world.getBlockState(top);
                if (!BlockRules.isRoadReplaceableGround(g)) return;
            }

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

        private void pruneAndReinforce() {
            it.unimi.dsi.fastutil.longs.LongOpenHashSet connected = keepOnlyConnectedToCore(planned, core);
            planned.clear();
            planned.addAll(connected);

            junctionReinforcement();

            // Runs after junctionReinforcement: it may also trim forced cells on the edge.
            applyEdgeNoisePrune();
        }

        // Removes planned edge cells that fall outside the edge noise boundary for their nearest
        // centerline segment. Core cells (centerline + bridge exits) are always kept.
        // Runs after junctionReinforcement — also removes from forcePaint so shouldPaintCell
        // correctly skips pruned cells even if junctionReinforcement over-filled them.
        private void applyEdgeNoisePrune() {
            if (edgeNoise == null || path.isEmpty() || baseR <= 0) return;
            if (ROAD_EDGE_VARIATION <= 0) return;

            int n = path.size();

            // Map each centerline XZ position to its path index.
            it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap coreIndexByXZ =
                    new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(n);
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



        private int neigh4Count(it.unimi.dsi.fastutil.longs.LongOpenHashSet set, int x, int z) {
            int hits = 0;
            if (set.contains(Pos2D.xzKey(x + 1, z))) hits++;
            if (set.contains(Pos2D.xzKey(x - 1, z))) hits++;
            if (set.contains(Pos2D.xzKey(x, z + 1))) hits++;
            if (set.contains(Pos2D.xzKey(x, z - 1))) hits++;
            return hits;
        }

        private void junctionReinforcement() {
            int reinR = baseR + 1;

            long[] coreArr = core.toLongArray();
            for (long k : coreArr) {
                int x = Pos2D.xFromKey(k);
                int z = Pos2D.zFromKey(k);

                boolean existing = (roadIndex != null && roadIndex.isRoadXZ(x, z));
                int n4 = neigh4Count(planned, x, z);

                boolean junction = existing || n4 >= 3;
                if (!junction) continue;

                fillDiskForced(x, z, reinR);
            }

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
            if (gapBoundaryCount > 0) {
                for (int gi = 0; gi < gapBoundaryCount; gi++) {
                    int pi = gapBoundaryPathIdx[gi];
                    if (pi < 0 || pi >= path.size()) continue;
                    int lx = centerXAt(pi);
                    int lz = centerZAt(pi);
                    fillDiskForced(gapBoundaryRoadX[gi], gapBoundaryRoadZ[gi], reinR);
                    fillLineForced(lx, lz, gapBoundaryRoadX[gi], gapBoundaryRoadZ[gi]);
                }
            }

            if (mergeActive) {
                fillDiskForced(mergeTargetX, mergeTargetZ, reinR);
                if (mergeHitIndex >= 0 && mergeHitIndex < path.size()) {
                    int lx = centerXAt(mergeHitIndex);
                    int lz = centerZAt(mergeHitIndex);
                    fillLineForced(lx, lz, mergeTargetX, mergeTargetZ);

                    int startHalf = (centerHalf != null && mergeHitIndex < centerHalf.length)
                            ? centerHalf[mergeHitIndex]
                            : HalfY.ofBlockTop(path.get(mergeHitIndex).getY());
                    Integer roadHalfVal = RoadJoins.findRoadSurfaceHalfY(
                            world, sampler, mergeTargetX, mergeTargetZ);
                    int endHalf = roadHalfVal != null ? roadHalfVal : startHalf;

                    mergeConnectorList = buildMergeConnectorList(
                            lx, lz, startHalf, mergeTargetX, mergeTargetZ, endHalf);
                    if (UltreiaConfig.DEBUG.logPainting) {
                        Ultreia.LOGGER.debug(
                                "[Ultreia] Merge connector: ({},{}) half={} -> ({},{}) half={} cells={}",
                                lx, lz, startHalf, mergeTargetX, mergeTargetZ, endHalf,
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
            java.util.List<BlockPos> line = LineRaster.supercover(x0, z0, x1, z1, 0);
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

        private boolean shouldPaintCell(int x, int z) {
            if (bridges.isBridgeCell(x, z)) return false;
            long k = Pos2D.xzKey(x, z);
            return forcePaint.contains(k) || planned.contains(k);
        }

        private boolean paintCells(long deadlineNs) {
            while (System.nanoTime() < deadlineNs) {
                if (curCenter == null) return true;

                int idx = i;
                int localR = baseR;

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

                long k = Pos2D.xzKey(xx, zz);
                if (!painted.add(k)) {
                    continue;
                }
                if (bridges.isBridgeCell(xx, zz)) {
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

            int localR = baseR;
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

        private void paintSingle(int x, int z, int surfaceY) {
            if (bridges.isBridgeCell(x, z)) return;
            // INVARIANT: no painting, clearing or earthworks inside protected areas or on Millénaire paths.
            if (protectedAreas != null && protectedAreas.contains(x, z)) return;
            // Never touch any column that belongs to an existing bridge volume (deck, supports, lateral blocks).
            if (roadIndex != null && roadIndex.isBridgeVolumeXZ(x, z)) return;
            if (roadIndex != null && roadIndex.isBridgeXZ(x, z)) return;

            long k = Pos2D.xzKey(x, z);
            int targetHalf = plannedHalfByXZ.get(k);
            if (targetHalf == Integer.MIN_VALUE) {
                targetHalf = HalfY.ofBlockTop(surfaceY);
            }

            HalfY.SlabPlacement sp = HalfY.slabPlacement(targetHalf);
            int supportTopY = HalfY.supportTopY(targetHalf);

            if (columnHasAnyMillPath(new BlockPos(x, sp.blockY, z))) {
                if (roadIndex != null && !dryRun) roadIndex.markRoad(x, z);
                return;
            }

            boolean earthworks = earthworksEnabled();

            boolean nearNoPaint = protectedAreas != null && protectedAreas.containsWithin(x, z, 1);
            if (nearNoPaint) {
                earthworks = false;
            }

            if (earthworks) {
                int rr = r;
                int shoulder = Math.max(0, EARTHWORKS_SHOULDER);
                Earthworks.Settings st = new Earthworks.Settings(
                        rr,
                        shoulder,
                        Math.max(0, UltreiaConfig.PAINTING.earthworksMaxCut),
                        Math.max(0, UltreiaConfig.PAINTING.earthworksMaxFill)
                );

                int cx = centerXAt(i);
                int cz = centerZAt(i);

                int dx0 = x - cx;
                int dz0 = z - cz;
                int dist = (int) Math.round(Math.sqrt((double) dx0 * (double) dx0 + (double) dz0 * (double) dz0));
                // A painted cell is road surface: graded to the road height; the talus pass shapes beyond it.
                dist = Math.min(dist, rr);

                Earthworks.gradeCell(world, sampler, x, z, supportTopY, dist, st, dryRun);
                sampler.invalidateAround(x, z, 1);
            }

            BlockPos supportTop = new BlockPos(x, supportTopY, z);

            if (!dryRun && !nearNoPaint) {
                // Always allow clearing solid natural blocks above the road surface for proper headroom.
                HeadroomClearer.clearAtAndAbove(world, supportTop.up(), 2, false, true);
            }

            if (!hasSupportWithinOneBlock(supportTop)) {
                dbgNoSupport++;
                dbgSample(dbgNoSupportAt, dbgNoSupport, x, targetHalf, z);
                return;
            }

            MillPathPalette.Kind kind = pickKindAveraged(x, z);
            placeRoadBlockFromHalf(supportTop, kind, targetHalf, earthworks);
        }

        private void placeRoadBlockFromHalf(BlockPos supportTop, MillPathPalette.Kind kind, int targetHalf, boolean allowSolidCut) {
            if (dryRun) return;

            int x = supportTop.getX();
            int z = supportTop.getZ();

            HalfY.SlabPlacement sp = HalfY.slabPlacement(targetHalf);
            boolean useBottomSlab = HalfY.isBottomSlab(targetHalf);

            BlockPos placePos = new BlockPos(x, sp.blockY, z);

            if (useBottomSlab) {
                ensureSolidSupportUnderBottomSlab(placePos.down(), kind);
            }

            // Always allow clearing solid natural blocks when placing the road block and clearing headroom.
            HeadroomClearer.clearAtAndAbove(world, placePos, 2, false, true);

            IBlockState place = useBottomSlab
                    ? MillPathPalette.slabBottom(kind)
                    : MillPathPalette.full(kind);

            world.setBlockState(placePos, place, 2);
            placedHalf.put(Pos2D.xzKey(x, z), targetHalf);

            if (CLEAR_HEADROOM) {
                HeadroomClearer.clearRoadColumn(world, placePos, 3, false, true);
            }

            if (BlockRules.treesClearable()) {
                net.minecraft.util.math.BlockPos dtRootPos =
                        HeadroomClearer.clearTreesAbove(world, placePos, 4, false);
                // If a whole DT tree was removed, paint a road block at the rootydirt position
                // (now plain dirt) so no bare earth patch is left where the tree stood.
                if (dtRootPos != null) {
                    dbgDtRoot++;
                    dbgSample(dbgDtRootAt, dbgDtRoot, dtRootPos.getX(), dtRootPos.getY() * 2, dtRootPos.getZ());
                    paintSingle(dtRootPos.getX(), dtRootPos.getZ(), dtRootPos.getY());
                    sampler.invalidateAround(dtRootPos.getX(), dtRootPos.getZ(), 1);
                }
            }

            if (roadIndex != null) {
                roadIndex.markRoad(x, z);
                newlyPlaced.add(Pos2D.xzKey(x, z));
            }

            sampler.invalidateAround(x, z, 1);
        }

        // INVARIANT: a road block never floats: at most one block is filled under it, else the cell is skipped.
        private boolean hasSupportWithinOneBlock(BlockPos supportTop) {
            IBlockState ground = world.getBlockState(supportTop);
            if (BlockRules.isRoadReplaceableGround(ground)) return true;
            Material m = ground.getMaterial();
            if (m == Material.WATER) return true; // small streams are paved over
            if (m == Material.LAVA || !m.isReplaceable()) return false;
            return world.getBlockState(supportTop.down()).getMaterial().isSolid();
        }

        private void ensureSolidSupportUnderBottomSlab(BlockPos supportPos, MillPathPalette.Kind kind) {
            IBlockState st = world.getBlockState(supportPos);
            Material m = st.getMaterial();

            if (m.isSolid() && m != Material.LEAVES && m != Material.WOOD) return;
            if (world.getTileEntity(supportPos) != null) return;

            IBlockState support = pickSupportBlock(supportPos, kind);
            world.setBlockState(supportPos, support, 3);
        }

        private boolean columnHasAnyMillPath(BlockPos roadBlock) {
            return ColumnSurface.findPathY(world, new BlockPos.MutableBlockPos(), roadBlock.getX(), roadBlock.getZ(),
                    roadBlock.getY(), false) != ColumnSurface.NO_PATH;
        }

        private boolean earthworksEnabled() {
            if (!roadType.allowsEarthworks()) return false;
            return gradeY != null && i >= 0 && i < gradeY.length;
        }

        private double effectiveSlopeRatio() {
            boolean isLoneTrail = roadType == RoadType.LONE_TRAIL;
            double ltr = UltreiaConfig.PAINTING.earthworksSlopeRatioLoneTrail;
            if (isLoneTrail && ltr > 0.0) return ltr;
            return UltreiaConfig.PAINTING.earthworksSlopeRatio;
        }

        /**
         * Post-painting pass: extends sloped talus outside the road surface.
         * For each newly-placed road cell, BFS outward to talusRadius and cut/fill
         * terrain to a natural slope rather than leaving vertical walls.
         */
        /** Builds the talus work list once; returns false when there is nothing to do. */
        private boolean prepareTalus() {
            if (dryRun) return false;
            if (!roadType.allowsEarthworks()) return false;
            if (newlyPlaced.isEmpty()) return false;

            double slopeRatio = effectiveSlopeRatio();
            int maxCut = Math.max(0, UltreiaConfig.PAINTING.earthworksMaxCut);
            int maxFill = Math.max(0, UltreiaConfig.PAINTING.earthworksMaxFill);
            if (slopeRatio <= 0.0 || (maxCut == 0 && maxFill == 0)) return false;

            int talusRadius = (int) Math.ceil(Math.max(maxCut, maxFill) * slopeRatio) + 1;
            if (talusRadius <= 0) return false;

            // Build road surface Y lookup for newly placed cells.
            roadSurfaceY = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(newlyPlaced.size());
            roadSurfaceY.defaultReturnValue(Integer.MIN_VALUE);

            for (it.unimi.dsi.fastutil.longs.LongIterator rit = newlyPlaced.iterator(); rit.hasNext(); ) {
                long k = rit.nextLong();
                int rx = Pos2D.xFromKey(k);
                int rz = Pos2D.zFromKey(k);
                TerrainSampler.Sample s = sampler.sample(rx, rz);
                if (s != null) roadSurfaceY.put(k, s.surfaceY);
            }

            // BFS: propagate distance and nearest-road-cell from all newly placed cells.
            talusDist = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
            it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap distMap = talusDist;
            distMap.defaultReturnValue(-1);
            talusNearestRoad = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
            it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap nearestRoad = talusNearestRoad;
            nearestRoad.defaultReturnValue(Long.MIN_VALUE);

            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>(newlyPlaced.size() * 4);

            for (it.unimi.dsi.fastutil.longs.LongIterator rit = newlyPlaced.iterator(); rit.hasNext(); ) {
                long k = rit.nextLong();
                distMap.put(k, 0);
                nearestRoad.put(k, k);
                queue.add(k);
            }

            while (!queue.isEmpty()) {
                long k = queue.poll();
                int d = distMap.get(k);
                if (d >= talusRadius) continue;
                long nr = nearestRoad.get(k);
                int cx = Pos2D.xFromKey(k);
                int cz = Pos2D.zFromKey(k);
                long[] neighbors = {
                    Pos2D.xzKey(cx + 1, cz), Pos2D.xzKey(cx - 1, cz),
                    Pos2D.xzKey(cx, cz + 1), Pos2D.xzKey(cx, cz - 1)
                };
                for (long nk : neighbors) {
                    if (distMap.containsKey(nk)) continue;
                    distMap.put(nk, d + 1);
                    nearestRoad.put(nk, nr);
                    queue.add(nk);
                }
            }

            talusSlopeRatio = slopeRatio;
            talusMaxCut = maxCut;
            talusMaxFill = maxFill;
            talusCells = distMap.keySet().toLongArray();
            talusCursor = 0;
            return true;
        }

        /** Applies the talus within the tick budget; returns true when finished. */
        private boolean talusStep(long deadlineNs) {
            if (talusCells == null) {
                if (!prepareTalus()) return true;
            }
            it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap distMap = talusDist;
            it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap nearestRoad = talusNearestRoad;
            double slopeRatio = talusSlopeRatio;
            int maxCut = talusMaxCut;
            int maxFill = talusMaxFill;
            while (talusCursor < talusCells.length && System.nanoTime() < deadlineNs) {
                long k = talusCells[talusCursor++];
                int d = distMap.get(k);
                if (d <= 0) continue;                // road cell itself
                if (newlyPlaced.contains(k)) continue;

                int tx = Pos2D.xFromKey(k);
                int tz = Pos2D.zFromKey(k);

                if (protectedAreas != null && protectedAreas.contains(tx, tz)) continue;
                if (roadIndex != null && roadIndex.isRoadXZ(tx, tz)) continue;
                if (roadIndex != null && roadIndex.isBridgeVolumeXZ(tx, tz)) continue;

                long nr = nearestRoad.get(k);
                if (nr == Long.MIN_VALUE) continue;
                int roadY = roadSurfaceY.get(nr);
                if (roadY == Integer.MIN_VALUE) continue;
                if (columnHasAnyMillPath(new BlockPos(tx, roadY, tz))) continue;

                int rx = Pos2D.xFromKey(nr);
                int rz = Pos2D.zFromKey(nr);
                double lateralDist = Math.sqrt((double)(tx - rx) * (tx - rx) + (double)(tz - rz) * (tz - rz));

                Earthworks.applyTalusAt(world, sampler, tx, tz, roadY,
                        lateralDist, slopeRatio, maxCut, maxFill, dryRun);
            }

            if (talusCursor < talusCells.length) return false;
            if (UltreiaConfig.DEBUG.logPainting) {
                Ultreia.LOGGER.info("[Ultreia] Talus pass: {} road cells, {} talus cells, slopeRatio={}",
                        newlyPlaced.size(), talusCells.length, slopeRatio);
            }
            return true;
        }


        private int[] buildGradeProfile() {
            int n = path.size();
            if (n == 0) return new int[0];

            try {
                if (centerHalf == null || centerHalf.length != n) {
                    centerHalf = RoadHeightProfile.build(path, forcedBridges, sampler, roadType);
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
                out[i] = HalfY.supportTopY(centerHalf[i]);
            }

            return out;
        }

        private IBlockState pickSupportBlock(BlockPos supportPos, MillPathPalette.Kind kind) {
            IBlockState below = world.getBlockState(supportPos.down());
            Block b = below.getBlock();
            Material m = below.getMaterial();

            if (b == Blocks.SAND || m == Material.SAND) return Blocks.SAND.getDefaultState();
            if (b == Blocks.GRAVEL) return Blocks.GRAVEL.getDefaultState();
            if (b == Blocks.CLAY || m == Material.CLAY) return Blocks.CLAY.getDefaultState();
            if (m == Material.ROCK || b == Blocks.STONE) return Blocks.STONE.getDefaultState();

            if (kind == MillPathPalette.Kind.GRAVEL) return Blocks.GRAVEL.getDefaultState();
            if (kind == MillPathPalette.Kind.SANDSTONE) return Blocks.SAND.getDefaultState();
            if (kind == MillPathPalette.Kind.SNOW) return Blocks.SNOW.getDefaultState();
            if (kind == MillPathPalette.Kind.SLABS) return Blocks.STONE.getDefaultState();

            return Blocks.DIRT.getDefaultState();
        }


        private MillPathPalette.Kind pickKindAveraged(int x, int z) {
            final int R = 2;

            int[] score = new int[MillPathPalette.Kind.values().length];

            for (int dz = -R; dz <= R; dz++) {
                for (int dx = -R; dx <= R; dx++) {
                    int xx = x + dx;
                    int zz = z + dz;

                    TerrainSampler.Sample sm = sampler.sample(xx, zz);
                    if (sm == null) continue;
                    if (sm.isOcean || sm.isWater) continue;

                    BlockPos top = new BlockPos(xx, sm.surfaceY, zz);
                    IBlockState st = world.getBlockState(top);

                    if (st != null && MillPathPalette.isMillPath(st.getBlock())) {
                        IBlockState below = world.getBlockState(top.down());
                        if (below != null) st = below;
                    }

                    if (st == null) continue;

                    Material m = st.getMaterial();
                    if (m == Material.AIR) continue;
                    if (m == Material.PLANTS || m == Material.VINE) continue;
                    if (m.isReplaceable()) continue;

                    MillPathPalette.Kind k = MillPathPalette.pickKind(world, top, st);

                    int md = Math.abs(dx) + Math.abs(dz);
                    int w = Math.max(0, (R + 2) - md);
                    if (w > 0) score[k.ordinal()] += w;
                }
            }

            if (roadIndex != null) {
                BlockPos near = roadIndex.findNearestRoad(x, z, 2);
                if (near != null) {
                    MillPathPalette.Kind ek = roadIndex.findExistingKindAt(world, sampler, near.getX(), near.getZ());
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
                return MillPathPalette.Kind.values()[bestIdx];
            }

            TerrainSampler.Sample sm0 = sampler.sample(x, z);
            BlockPos top0 = new BlockPos(x, sm0.surfaceY, z);
            IBlockState st0 = world.getBlockState(top0);
            return MillPathPalette.pickKind(world, top0, st0);
        }

        private void registerRoadSegment() {
            if (roadNetwork == null || dryRun || path.isEmpty()) return;
            roadNetwork.addRoadSegment(path, roadIndex, fromVillage, toVillage, roadType, paintedWidthBlocks());
        }

        /** Builds the bridge waiting in {@code pendingBridge}; past its exit, forces the first road cells when asked. */
        private void finishPendingBridge(String where, boolean reinforceExit) {
            BridgeSpan span = pendingBridge;
            if (!bridges.paint(span, roadNetwork, fromVillage, toVillage, roadType)) {
                Ultreia.LOGGER.warn("Bridge build degraded{} start=({}, {}) end=({}, {}) len={}", where,
                        span.start.getX(), span.start.getZ(),
                        span.end.getX(), span.end.getZ(),
                        span.lengthBlocks());
            }
            if (reinforceExit) {
                for (int[] c : bridges.exitCells(span, FORCE_PAINT_EXIT_BLOCKS)) {
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
            pendingBridge = null;
            pendingBridgeEndIdx = -1;
        }
    }

    private RoadPainter() {}

    public static Session start(WorldServer world,
                                List<BlockPos> coarsePath,
                                List<BridgeSpan> forcedBridges,
                                TerrainSampler sampler,
                                ProtectedAreas protectedAreas,
                                RoadIndex roadIndex,
                                boolean dryRun,
                                boolean allowEarlyMerge) {
        if (coarsePath == null || coarsePath.isEmpty()) {
            return new Session(world, Collections.<BlockPos>emptyList(), forcedBridges, sampler, protectedAreas, roadIndex, dryRun, allowEarlyMerge);
        }
        return new Session(world, coarsePath, forcedBridges, sampler, protectedAreas, roadIndex, dryRun, allowEarlyMerge);
    }

}
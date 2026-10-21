package com.jubitus.ultreia.gen;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.MPBConfig;
import com.jubitus.ultreia.gen.bridge.*;
import com.jubitus.ultreia.gen.newp.MPBBridgeApproachFeasibility;
import com.jubitus.ultreia.gen.newp.MPBPathProfileFeasibility;
import com.jubitus.ultreia.gen.newp.MPBRoadHalfConstraintSolver;
import com.jubitus.ultreia.gen.util.MPBPos2D;
import com.jubitus.ultreia.gen.world.MPBRoadIndexData;
import com.jubitus.ultreia.gen.world.MPBRoadNetwork;
import com.jubitus.ultreia.gen.world.RoadSegment;
import com.jubitus.ultreia.gen.world.RoadWaypoint;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public final class AStarPathfinder {

    public enum StepResult { RUNNING, FOUND, PARTIAL, FAILED }

    private static final int DIR_NONE = 16;

    private static final class Node {
        final int x, z;
        final int dir;
        final double f;

        Node(int x, int z, int dir, double f) {
            this.x = x;
            this.z = z;
            this.dir = dir;
            this.f = f;
        }
    }

    private static final class BridgeMove {
        final int nx;
        final int nz;
        final int ndir;
        final double cost;
        final BridgeSpan span;
        /** Set only for a move that enters an already-built bridge via {@code buildExistingBridgeMoves}. */
        @javax.annotation.Nullable final MPBRoadNetwork.BridgeCrossing reusedCrossing;

        BridgeMove(int nx, int nz, int ndir, double cost, BridgeSpan span) {
            this(nx, nz, ndir, cost, span, null);
        }

        BridgeMove(int nx, int nz, int ndir, double cost, BridgeSpan span,
                   @javax.annotation.Nullable MPBRoadNetwork.BridgeCrossing reusedCrossing) {
            this.nx = nx;
            this.nz = nz;
            this.ndir = ndir;
            this.cost = cost;
            this.span = span;
            this.reusedCrossing = reusedCrossing;
        }
    }

    public static final class Session {
        private int minX, maxX, minZ, maxZ;
        private int sx, sz;
        private int maxRadSq;

        private final WorldServer world;
        private final TerrainSampler sampler;
        private final int step;
        private final int configStep;
        private int gx, gz;

        @javax.annotation.Nullable private MPBRoadNetwork roadNetwork;
        private RoadType roadType = RoadType.VILLAGE_MAIN;

        // Multi-gate support: when set, A* can terminate at any gate in this field.
        @javax.annotation.Nullable private VillageGateField destGates;
        @javax.annotation.Nullable private net.minecraft.util.math.BlockPos reachedGate;

        // Road-snap: for LONE_TRAIL, terminate A* as soon as we are within snapRadius of any
        // existing road block (that we didn't place ourselves).
        @javax.annotation.Nullable private MPBRoadIndexData snapRoadIndex = null;
        private int snapRadius = 0;
        private double snapMinDistSq = 0;

        private final Long2DoubleOpenHashMap gScore = new Long2DoubleOpenHashMap();
        private final Long2LongOpenHashMap cameFrom = new Long2LongOpenHashMap();
        private final Long2ObjectOpenHashMap<BridgeSpan> cameBridge = new Long2ObjectOpenHashMap<>();
        // Separate from cameBridge because a reused-bridge move stores span=null (nothing new to build),
        // which is indistinguishable from "no bridge at all" in a Long2ObjectOpenHashMap lookup.
        private final Long2ObjectOpenHashMap<MPBRoadNetwork.BridgeCrossing> cameReusedBridge = new Long2ObjectOpenHashMap<>();
        private final Long2IntOpenHashMap distSinceBridge = new Long2IntOpenHashMap();

        private final PriorityQueue<Node> open = new PriorityQueue<>(new Comparator<Node>() {
            @Override
            public int compare(Node a, Node b) {
                return Double.compare(a.f, b.f);
            }
        });
        private final LongOpenHashSet closed = new LongOpenHashSet();

        private int it = 0;
        private int maxIt;
        private long sessionStartNs = System.nanoTime();
        private long lastProgressLogNs = 0;
        private int profileRejectionCount = 0;

        private boolean failed = false;
        private String failReason = "unknown";

        private long foundState = Long.MIN_VALUE;
        private boolean reconstructing = false;

        private final ArrayList<Long> revStates = new ArrayList<>();
        private long recCur = Long.MIN_VALUE;

        // Index in the reconstructed path list at which each bridge span begins (prior road points end).
        private final ArrayList<Integer> bridgePathStartIndices = new ArrayList<>();
        private int recGuard = 0;

        private BridgeAwarePathResult result = new BridgeAwarePathResult(Collections.<BlockPos>emptyList(), Collections.<BridgeSpan>emptyList());

        private int skipOcean = 0, skipBlacklist = 0, skipBlocked = 0, skipSlope = 0, skipBridge = 0;

        private int corridorHalfWidth;
        private long ax, az, bx, bz;
        private long segDx, segDz;
        private long segLenSq;

        private long bestApproxState = Long.MIN_VALUE;
        private double bestApproxHeuristic = Double.POSITIVE_INFINITY;
        private double bestApproxScore = Double.POSITIVE_INFINITY;

        private boolean partialResult = false;
        private String partialReason = null;

        private int stagnationSteps = 0;
        private double lastProgressHeuristic = Double.POSITIVE_INFINITY;
        private double startHeuristic = Double.POSITIVE_INFINITY;

        // Surface Y at start/goal grid nodes — used for altitude-aware stagnation detection.
        private int startY = 0;
        private int goalY = 0;

        private static final int[][] NEIGHBOR_DIRS = new int[][]{
                { 1, 0}, {-1, 0}, {0, 1}, {0, -1},
                { 1, 1}, { 1,-1}, {-1, 1}, {-1,-1},
                { 2, 1}, { 2,-1}, {-2, 1}, {-2,-1},
                { 1, 2}, { 1,-2}, {-1, 2}, {-1,-2}
        };

        private static final int[][] BRIDGE_DIRS = new int[][]{
                { 1, 0}, {-1, 0}, {0, 1}, {0, -1},
                { 1, 1}, { 1,-1}, {-1, 1}, {-1,-1},
                { 2, 1}, { 2,-1}, {-2, 1}, {-2,-1},
                { 1, 2}, { 1,-2}, {-1, 2}, {-1,-2}
        };

        private static final int BRIDGE_DEBUG_LIMIT = 16;
        private int bridgeDebugPrinted = 0;
        private int bridgeDebugSuppressed = 0;

        Session(WorldServer world, BlockPos start, BlockPos goal, TerrainSampler sampler) {
            this.world = world;
            this.sampler = sampler;

            this.configStep = Math.max(2, MPBConfig.PATHFINDING.gridStep);
            {
                if (MPBConfig.PATHFINDING.adaptiveGridStep) {
                    double roughDist = Math.sqrt(
                        (double)(start.getX() - goal.getX()) * (double)(start.getX() - goal.getX()) +
                        (double)(start.getZ() - goal.getZ()) * (double)(start.getZ() - goal.getZ())
                    );
                    // +2 only for routes > 1200 blocks; grade penalty is rescaled below
                    if (roughDist > 1200) {
                        this.step = Math.min(10, configStep + 2);
                    } else {
                        this.step = configStep;
                    }
                } else {
                    this.step = configStep;
                }
            }

            int sx0 = snap(start.getX(), step);
            int sz0 = snap(start.getZ(), step);
            int gx0 = snap(goal.getX(), step);
            int gz0 = snap(goal.getZ(), step);

            int[] sFix = nearestValidGrid(sx0, sz0, step, sampler);
            int[] gFix = nearestValidGrid(gx0, gz0, step, sampler);

            if (sFix == null) {
                failed = true;
                failReason = "start forbidden (no valid grid point near snapped start)";
                return;
            }
            if (gFix == null) {
                failed = true;
                failReason = "goal forbidden (no valid grid point near snapped goal)";
                return;
            }

            this.sx = sFix[0];
            this.sz = sFix[1];
            this.gx = gFix[0];
            this.gz = gFix[1];

            if (sampler.isProtectedNoPaint(this.sx, this.sz) || sampler.isProtectedNoPaint(this.gx, this.gz)) {
                failed = true;
                failReason = "start/goal snapped inside protected no-paint zone";
                return;
            }

            int startFree = countLocalFreeNeighbors(this.sx, this.sz, step, sampler);
            int goalFree = countLocalFreeNeighbors(this.gx, this.gz, step, sampler);

            if (startFree <= 0) {
                failed = true;
                failReason = "start snapped into blocked pocket";
                return;
            }
            if (goalFree <= 0) {
                failed = true;
                failReason = "goal snapped into blocked pocket";
                return;
            }

            this.ax = this.sx;
            this.az = this.sz;
            this.bx = this.gx;
            this.bz = this.gz;

            this.segDx = bx - ax;
            this.segDz = bz - az;
            this.segLenSq = segDx * segDx + segDz * segDz;

            int marginBase = Math.max(48, MPBConfig.PATHFINDING.searchMargin);

            TerrainSampler.Sample ss = sampler.sample(this.sx, this.sz);
            TerrainSampler.Sample gs = sampler.sample(this.gx, this.gz);
            int dyGoal = Math.abs(gs.surfaceY - ss.surfaceY);
            this.startY = (ss != null) ? ss.surfaceY : 64;
            this.goalY  = (gs != null) ? gs.surfaceY : 64;

            int m = Math.max(64, MPBConfig.PATHFINDING.searchMargin / 2);
            // climbBoost scales up to corridorHalfWidthMax so the corridor covers full switchback room.
            int climbBoost = Math.min(Math.max(256, MPBConfig.PATHFINDING.corridorHalfWidthMax), dyGoal * 8);

            int routeLen = (int) Math.round(Math.sqrt((double) segLenSq));
            int longRouteAllowance = Math.min(768, Math.max(128, routeLen / 3));

            this.corridorHalfWidth = Math.max(m + climbBoost, longRouteAllowance);

            // Apply user-configured cap before adaptive step scaling.
            int configuredMax = Math.max(64, MPBConfig.PATHFINDING.corridorHalfWidthMax);
            this.corridorHalfWidth = Math.min(this.corridorHalfWidth, configuredMax);

            // When using an adaptive larger step, tighten the corridor so the total
            // state count stays within the iteration budget.
            if (step > configStep) {
                int scaledCorridor = (int) Math.round((double) this.corridorHalfWidth
                        * (double) configStep / (double) step);
                this.corridorHalfWidth = Math.max(Math.max(128, m), scaledCorridor);
            }

            int margin = marginBase + Math.min(2048, dyGoal * 6);

            this.minX = Math.min(sx, gx) - margin;
            this.maxX = Math.max(sx, gx) + margin;
            this.minZ = Math.min(sz, gz) - margin;
            this.maxZ = Math.max(sz, gz) + margin;

            int mr = Math.max(0, MPBConfig.PATHFINDING.maxSearchRadius);
            this.maxRadSq = (mr > 0) ? (mr * mr) : -1;

            // For adaptive step: increase iteration budget proportionally to step ratio,
            // since each iteration covers more ground and the search finishes faster per cell.
            int configMaxIt = MPBConfig.PATHFINDING.maxIterations;
            this.maxIt = (step == configStep)
                    ? configMaxIt
                    : (int) Math.min((long) configMaxIt * 2L, (long) configMaxIt * (long) step / (long) configStep);

            gScore.defaultReturnValue(Double.POSITIVE_INFINITY);
            cameFrom.defaultReturnValue(Long.MIN_VALUE);
            distSinceBridge.defaultReturnValue(Integer.MAX_VALUE);

            if (sampler.forbidden(ss, TerrainSampler.Mode.PATHFINDING)) {
                failed = true;
                failReason = "start forbidden: " + sampler.describeForbidden(ss, TerrainSampler.Mode.PATHFINDING);
                return;
            }
            if (sampler.forbidden(gs, TerrainSampler.Mode.PATHFINDING)) {
                failed = true;
                failReason = "goal forbidden: " + sampler.describeForbidden(gs, TerrainSampler.Mode.PATHFINDING);
                return;
            }

            long startState = stateKey(sx, sz, DIR_NONE);
            gScore.put(startState, 0.0);
            distSinceBridge.put(startState, Integer.MAX_VALUE / 4);

            double h0 = heuristic(sx, sz, gx, gz); // primary goal used until destGates is set
            bestApproxState = startState;
            bestApproxHeuristic = h0;
            bestApproxScore = h0;
            lastProgressHeuristic = h0;
            startHeuristic = h0;

            open.add(new Node(sx, sz, DIR_NONE, h0));
        }

        public void setRoadNetwork(@javax.annotation.Nullable MPBRoadNetwork network) {
            this.roadNetwork = network;
        }

        public void setRoadType(RoadType type) {
            this.roadType = (type != null) ? type : RoadType.VILLAGE_MAIN;
        }

        /**
         * Registers all valid gate positions for the destination village so A* can terminate
         * at any of them rather than a single pre-selected goal point.
         * Must be called before the first {@link #advance} invocation.
         */
        public void setDestinationGates(@javax.annotation.Nullable VillageGateField gf) {
            this.destGates = (gf != null && !gf.isEmpty()) ? gf : null;
        }

        /**
         * Enables road-snap termination: A* stops as soon as it reaches within {@code radius} blocks
         * of any road in {@code index}. Only activates after leaving a minimum distance from the start.
         * Intended for LONE_TRAIL routes that should graft onto the nearest existing road.
         */
        public void setSnapToRoad(@javax.annotation.Nullable MPBRoadIndexData index, int radius) {
            this.snapRoadIndex = index;
            this.snapRadius = Math.max(1, radius);
            // Don't snap within the first ~6 A* steps to avoid immediate self-termination.
            double minDist = step * 6.0;
            this.snapMinDistSq = minDist * minDist;
        }

        /** Returns the gate position where A* actually terminated, or null if it reached the primary goal. */
        @javax.annotation.Nullable
        public net.minecraft.util.math.BlockPos getReachedGate() {
            return reachedGate;
        }

        public StepResult advance(long deadlineNs) {
            if (failed) return StepResult.FAILED;

            if (!reconstructing) {
                long nowNs = System.nanoTime();

                // Wall-clock timeout guard — prevents infinite-seeming hangs on impossible terrain.
                int maxWallSec = Math.max(30, MPBConfig.PATHFINDING.maxWallTimeSeconds);
                if (nowNs - sessionStartNs > (long) maxWallSec * 1_000_000_000L) {
                    if (beginPartialReconstruction("wall-clock timeout (" + maxWallSec + "s)")) {
                        return StepResult.RUNNING;
                    }
                    failed = true;
                    failReason = "wall-clock timeout (" + maxWallSec + "s)";
                    flushBridgeDebugSummary();
                    return StepResult.FAILED;
                }

                // Periodic progress log so the user can see A* is still alive.
                if (MPBConfig.GENERAL.printFailureBreakdown
                        && nowNs - lastProgressLogNs > 10_000_000_000L) {
                    lastProgressLogNs = nowNs;
                    long elapsedSec = (nowNs - sessionStartNs) / 1_000_000_000L;
                    Ultreia.LOGGER.info(
                            "[MPB A*] still running: it={} closed={} open={} best_dist={} elapsed={}s",
                            it, closed.size(), open.size(),
                            Double.isFinite(bestApproxHeuristic) ? (int) bestApproxHeuristic : -1,
                            elapsedSec);
                }

                while (!open.isEmpty() && it < maxIt && System.nanoTime() < deadlineNs) {
                    it++;
                    Node cur = open.poll();
                    long ck = stateKey(cur.x, cur.z, cur.dir);

                    if (!closed.add(ck)) continue;

                    updateBestApprox(ck, cur.x, cur.z);

                    if (distSq(cur.x, cur.z, gx, gz) <= (double) step * (double) step) {
                        foundState = ck;
                        reconstructing = true;
                        recCur = ck;
                        recGuard = 0;
                        partialResult = false;
                        partialReason = null;
                        break;
                    }

                    // Multi-gate termination: also stop when reaching any destination gate.
                    if (destGates != null && destGates.isAtGate(cur.x, cur.z, step)) {
                        reachedGate = destGates.nearestGate(cur.x, cur.z);
                        foundState = ck;
                        reconstructing = true;
                        recCur = ck;
                        recGuard = 0;
                        partialResult = false;
                        partialReason = null;
                        break;
                    }

                    // Road-snap termination for LONE_TRAIL: stop as soon as we are adjacent to
                    // an existing road, so the path grafts onto the network rather than continuing
                    // all the way to the destination village.
                    if (snapRoadIndex != null && distSq(cur.x, cur.z, sx, sz) >= snapMinDistSq) {
                        BlockPos nearRoad = snapRoadIndex.findNearestRoad(cur.x, cur.z, snapRadius);
                        if (nearRoad != null
                                && !snapRoadIndex.isBridgeXZ(nearRoad.getX(), nearRoad.getZ())
                                // The road block itself must also be far from the start so we don't
                                // snap back onto internal building paths of the departure structure.
                                && distSq(nearRoad.getX(), nearRoad.getZ(), sx, sz) >= snapMinDistSq) {
                            Ultreia.LOGGER.info(
                                    "[MPB A*] Road-snap: ({},{}) snapped to road at ({},{})",
                                    cur.x, cur.z, nearRoad.getX(), nearRoad.getZ());
                            foundState = ck;
                            reconstructing = true;
                            recCur = ck;
                            recGuard = 0;
                            partialResult = false;
                            partialReason = null;
                            break;
                        }
                    }

                    TerrainSampler.Sample cs = sampler.sample(cur.x, cur.z);
                    if (sampler.forbidden(cs, TerrainSampler.Mode.PATHFINDING)) {
                        bumpForbidden(cs);
                        continue;
                    }

                    expandNormalMoves(cur, ck, cs);
                    expandBridgeMoves(cur, ck, cs);
                }

                if (!reconstructing) {
                    if (open.isEmpty()) {
                        if (beginPartialReconstruction("open exhausted")) {
                            return StepResult.RUNNING;
                        }
                        failed = true;
                        failReason = "open exhausted";
                        flushBridgeDebugSummary();
                        return StepResult.FAILED;
                    }

                    if (shouldAbortForNoProgress()) {
                        if (beginPartialReconstruction("no meaningful progress toward goal")) {
                            return StepResult.RUNNING;
                        }
                        failed = true;
                        failReason = "no meaningful progress toward goal";
                        flushBridgeDebugSummary();
                        return StepResult.FAILED;
                    }

                    if (it >= maxIt) {
                        if (beginPartialReconstruction("max iterations reached")) {
                            return StepResult.RUNNING;
                        }
                        failed = true;
                        failReason = "max iterations reached";
                        flushBridgeDebugSummary();
                        return StepResult.FAILED;
                    }
                    return StepResult.RUNNING;
                }
            }

            while (reconstructing && System.nanoTime() < deadlineNs) {
                if (recCur == Long.MIN_VALUE) break;

                revStates.add(recCur);

                long prev = cameFrom.get(recCur);
                recCur = prev == Long.MIN_VALUE ? Long.MIN_VALUE : prev;

                recGuard++;
                if (recGuard > 600_000) {
                    failed = true;
                    failReason = "reconstruct guard hit (cycle?)";
                    reconstructing = false;
                    flushBridgeDebugSummary();
                    return StepResult.FAILED;
                }

                if (recCur == Long.MIN_VALUE) {
                    reconstructing = false;
                    Collections.reverse(revStates);
                    buildResultFromReconstruction();

                    if (!failed) {
                        BridgeAwarePathResult rr = result;
                        if (rr == null || rr.getPath() == null || rr.getPath().isEmpty()) {
                            failed = true;
                            failReason = "reconstruction produced empty path";
                            return StepResult.FAILED;
                        }

                        boolean fullProfileOk = MPBPathProfileFeasibility.isFullPathFeasible(
                                rr.getPath(),
                                rr.getBridges(),
                                sampler
                        );
                        boolean clearanceOk = bridgesHaveApproachPathClearance(rr.getPath(), rr.getBridges());

                        if (!fullProfileOk || !clearanceOk) {
                            long badState = foundState;
                            boolean wasPartial = partialResult;
                            profileRejectionCount++;

                            reconstructing = false;
                            foundState = Long.MIN_VALUE;
                            recCur = Long.MIN_VALUE;
                            recGuard = 0;
                            revStates.clear();

                            closed.add(badState);
                            result = new BridgeAwarePathResult(
                                    Collections.<BlockPos>emptyList(),
                                    Collections.<BridgeSpan>emptyList()
                            );

                            // If the partial fallback itself fails profile, bestApproxState hasn't
                            // changed (search never ran since reconstructing=true the whole time),
                            // so calling beginPartialReconstruction again would loop forever silently.
                            if (wasPartial) {
                                failed = true;
                                failReason = "partial fallback also failed profile feasibility (" + profileRejectionCount + " total rejections)";
                                flushBridgeDebugSummary();
                                return StepResult.FAILED;
                            }

                            // Guard: after too many full-path rejections, try a partial fallback.
                            // Note: beginPartialReconstruction must only be called here (not from
                            // inside a partial reconstruction) or it would loop silently.
                            int maxRejections = Math.max(24, MPBConfig.PATHFINDING.profileRejectionLimit);
                            if (profileRejectionCount >= maxRejections) {
                                if (beginPartialReconstruction("non-feasible road profile after " + profileRejectionCount + " attempts")) {
                                    return StepResult.RUNNING;
                                }
                                failed = true;
                                failReason = "non-feasible road profile after " + profileRejectionCount + " attempts";
                                flushBridgeDebugSummary();
                                return StepResult.FAILED;
                            }

                            // Return RUNNING without calling beginPartialReconstruction here.
                            // The next advance() enters !reconstructing, fires the progress log,
                            // and handles "open exhausted" through the normal path.
                            return StepResult.RUNNING;
                        }
                    }

                    flushBridgeDebugSummary();
                    return partialResult ? StepResult.PARTIAL : StepResult.FOUND;
                }
            }

            return StepResult.RUNNING;
        }

        private void updateBestApprox(long state, int x, int z) {
            double h = heuristicToGoal(x, z);
            double g = gScore.get(state);

            if (state == Long.MIN_VALUE) return;
            if (!Double.isFinite(h) || !Double.isFinite(g)) return;

            if (bestApproxState == Long.MIN_VALUE) {
                bestApproxState = state;
                bestApproxHeuristic = h;
                bestApproxScore = g + h * 0.25;
                return;
            }

            double score = g + h * 0.25;

            if (h + 0.001 < bestApproxHeuristic) {
                bestApproxState = state;
                bestApproxHeuristic = h;
                bestApproxScore = score;
                return;
            }

            if (Math.abs(h - bestApproxHeuristic) <= (double) step) {
                if (score + 0.001 < bestApproxScore) {
                    bestApproxState = state;
                    bestApproxHeuristic = h;
                    bestApproxScore = score;
                }
            }
        }

        private boolean beginPartialReconstruction(String reason) {
            if (!MPBConfig.PATHFINDING.allowPartialPathFallback) return false;
            if (!hasUsefulPartialFallback()) return false;

            partialResult = true;
            partialReason = reason;

            foundState = bestApproxState;
            reconstructing = true;
            recCur = bestApproxState;
            recGuard = 0;
            revStates.clear();

            return true;
        }

        private boolean hasUsefulPartialFallback() {
            if (bestApproxState == Long.MIN_VALUE) return false;

            int bx = stateX(bestApproxState);
            int bz = stateZ(bestApproxState);

            double startH = heuristicToGoal(sx, sz);
            double gain = startH - bestApproxHeuristic;

            if (bx == sx && bz == sz) {
                return false;
            }

            return gain >= Math.max(64.0, startH * 0.18);
        }

        public String partialSummary() {
            if (!partialResult) return "";
            int bx = bestApproxState == Long.MIN_VALUE ? sx : stateX(bestApproxState);
            int bz = bestApproxState == Long.MIN_VALUE ? sz : stateZ(bestApproxState);
            int dist = (int) Math.round(heuristic(bx, bz, gx, gz));
            return "partial=true reason=" + partialReason
                    + " best=(" + bx + "," + bz + ")"
                    + " goal=(" + gx + "," + gz + ")"
                    + " remaining=" + dist;
        }

        // When destGates is set, use distance to the nearest gate as the heuristic so A*
        // approaches the village generally and terminates at whichever gate it reaches first.
        // Admissible: min(dist to gate) <= true cost to any reachable gate.
        private double heuristicToGoal(int x, int z) {
            if (destGates != null) {
                return destGates.distToNearest(x, z);
            }
            return heuristic(x, z, gx, gz);
        }

        private void expandNormalMoves(Node cur, long ck, TerrainSampler.Sample cs) {
            final int configMaxDy = Math.max(1, MPBConfig.PATHFINDING.maxYDeltaPerStep);
            // LONE_TRAIL: no earthworks → path follows terrain strictly.
            final boolean isLoneTrail = MPBConfig.ROAD_HIERARCHY.enabled && roadType == RoadType.LONE_TRAIL;
            final boolean earthworks = MPBConfig.PATHFINDING.allowEarthworks
                    && !(isLoneTrail && !MPBConfig.ROAD_HIERARCHY.loneTrailEarthworksEnabled);

            // Scale maxDy proportionally when using an adaptive (larger) step,
            // so the grade limit stays the same regardless of step size.
            final int configStep = Math.max(2, MPBConfig.PATHFINDING.gridStep);
            final int effectiveMaxDy = (step == configStep)
                    ? configMaxDy
                    : (int) Math.ceil((double) configMaxDy * (double) step / (double) configStep);

            // LONE_TRAIL uses a higher slope multiplier to make the trail wind around hills.
            final double slopeMultiplier = (isLoneTrail)
                    ? Math.max(1.0, MPBConfig.ROAD_HIERARCHY.loneTrailSlopePenaltyMultiplier)
                    : 1.0;

            // Magnetism: cache radii once per call to avoid per-neighbor lookups when disabled.
            final boolean magnetismActive = MPBConfig.ROAD_HIERARCHY.magnetismEnabled;
            final int magRadius = magnetismActive ? Math.max(4, MPBConfig.ROAD_HIERARCHY.magnetismRadius) : 0;

            // Bridge-endpoint magnetism: pulls the search toward a known existing bridge crossing's
            // entrance from a distance, so buildExistingBridgeMoves' validated single-cell snap
            // (bug A fix) actually has a real adjacent node to trigger from instead of drifting past it.
            final int bridgeReuseRadius = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeReuseSearchRadius);
            final double bridgeMagnetismBonus = Math.max(0.0, MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeEndpointMagnetismBonus);
            boolean bridgeMagnetismActive = MPBConfig.BRIDGE_AWARE_PATHFINDING.enabled
                    && roadNetwork != null && bridgeReuseRadius > 0 && bridgeMagnetismBonus > 0.0;
            List<MPBRoadNetwork.BridgeCrossing> nearbyCrossings = null;
            double curDistToCrossingStart = Double.POSITIVE_INFINITY;
            if (bridgeMagnetismActive) {
                nearbyCrossings = roadNetwork.getBridgeCrossingsNear(cur.x, cur.z, bridgeReuseRadius);
                if (nearbyCrossings.isEmpty()) {
                    bridgeMagnetismActive = false;
                } else {
                    curDistToCrossingStart = nearestCrossingStartDist(cur.x, cur.z, nearbyCrossings);
                }
            }

            for (int dirIdx = 0; dirIdx < NEIGHBOR_DIRS.length; dirIdx++) {
                int[] dir = NEIGHBOR_DIRS[dirIdx];
                int dx = dir[0] * step;
                int dz = dir[1] * step;

                int nx = cur.x + dx;
                int nz = cur.z + dz;

                if (!baseXZChecks(nx, nz)) continue;

                TerrainSampler.Sample ns = sampler.sample(nx, nz);
                if (sampler.forbidden(ns, TerrainSampler.Mode.PATHFINDING)) {
                    bumpForbidden(ns);
                    continue;
                }

                SegmentWalkability sw = segmentWalkability(cur.x, cur.z, nx, nz);
                if (sw.crossesWater) {
                    skipBridge++;
                    continue;
                }
                if (!sw.walkable) {
                    skipSlope++;
                    continue;
                }

                int dy = Math.abs(ns.surfaceY - cs.surfaceY);

                double base = Math.sqrt((double) dx * (double) dx + (double) dz * (double) dz);
                double slopeCost = (double) Math.min(dy, effectiveMaxDy) * MPBConfig.PATHFINDING.slopePenalty * slopeMultiplier;
                double roughCost = (double) sw.roughness * MPBConfig.PATHFINDING.surfaceRoughnessPenalty;
                double terrainCutCost = sw.terrainPenalty;
                double mountainCost = mountainTraversePenalty(cur.x, cur.z, dirIdx, cs.surfaceY, ns.surfaceY);

                if (earthworks && dy > effectiveMaxDy) {
                    double extra = (double) (dy - effectiveMaxDy);
                    slopeCost += extra * extra * 30.0;
                } else if (!earthworks && dy > effectiveMaxDy) {
                    skipSlope++;
                    continue;
                }

                long nk = stateKey(nx, nz, dirIdx);
                if (closed.contains(nk)) continue;

                double tentative = gScore.get(ck)
                        + base
                        + slopeCost
                        + roughCost
                        + terrainCutCost
                        + mountainCost
                        + sampler.extraCost(ns, TerrainSampler.Mode.PATHFINDING);

                // Direction-change penalty: produces smooth natural roads and prevents zigzagging on flat terrain.
                // Same formula used for entering/exiting a bridge (see turnCost) — one turn-cost model for
                // every kind of move, so a bridge never gets to attach at an angle a road turn wouldn't.
                tentative += turnCost(cur.dir, dirIdx, step, MPBConfig.PATHFINDING.turnPenalty);

                // Road magnetism: direction-aware attraction/repulsion around existing roads.
                // Moving toward an existing road is discounted; moving away is penalised.
                if (magnetismActive && magRadius > 0) {
                    int distNeighbor = sampler.nearestRoadDistManhattan(nx, nz, magRadius, 2);
                    if (distNeighbor < magRadius) {
                        int distCurrent = sampler.nearestRoadDistManhattan(cur.x, cur.z, magRadius, 2);
                        double scale = (double) magRadius;
                        if (distNeighbor < distCurrent) {
                            // Moving toward road → attraction bonus, strongest when almost on it.
                            double frac = 1.0 - (double) distNeighbor / scale;
                            tentative -= Math.max(0.0, MPBConfig.ROAD_HIERARCHY.magnetismAttractionBonus) * frac;
                        } else if (distCurrent < magRadius && distNeighbor > distCurrent) {
                            // Moving away from road while inside the attraction zone → repulsion.
                            double frac = 1.0 - (double) distCurrent / scale;
                            tentative += Math.max(0.0, MPBConfig.ROAD_HIERARCHY.magnetismRepulsionPenalty) * frac;
                        }
                    }
                }

                // Bridge-endpoint magnetism: discount moving closer to a known crossing's entrance.
                if (bridgeMagnetismActive) {
                    double distNeighborBridge = nearestCrossingStartDist(nx, nz, nearbyCrossings);
                    if (distNeighborBridge < bridgeReuseRadius && distNeighborBridge < curDistToCrossingStart) {
                        double frac = 1.0 - distNeighborBridge / (double) bridgeReuseRadius;
                        tentative -= bridgeMagnetismBonus * frac;
                    }
                }

                if (tentative < gScore.get(nk)) {
                    cameFrom.put(nk, ck);
                    cameBridge.remove(nk);
                    cameReusedBridge.remove(nk);
                    gScore.put(nk, tentative);

                    int prevDist = distSinceBridge.get(ck);
                    int nextDist = (prevDist >= Integer.MAX_VALUE / 8)
                            ? prevDist
                            : Math.min(1000000, prevDist + (int) Math.round(base));
                    distSinceBridge.put(nk, nextDist);

                    double f = tentative + heuristicToGoal(nx, nz);
                    open.add(new Node(nx, nz, dirIdx, f));
                }
            }
        }

        private double mountainTraversePenalty(int x, int z, int dirIdx, int curY, int nextY) {
            if (dirIdx < 0 || dirIdx >= NEIGHBOR_DIRS.length) return 0.0;

            int lookahead = Math.max(1, MPBConfig.PATHFINDING.mountainAscentLookahead);
            int[] dir = NEIGHBOR_DIRS[dirIdx];

            int wallMinHeight = Math.max(1, MPBConfig.PATHFINDING.enclosedWallMinHeight);
            int uphillMinRise = Math.max(1, MPBConfig.PATHFINDING.uphillLookaheadMinRise);
            int allowedCut = Math.max(0, MPBConfig.PATHFINDING.predictedMaxCutDepth);

            int stepDy = nextY - curY;

            int cx = x;
            int cz = z;

            int maxPredictedCut = 0;
            int sumPredictedCut = 0;

            int enclosedAhead = 0;
            int currentEnclosedRun = 0;
            int longestEnclosedRun = 0;
            int maxWallMin = 0;

            int maxRiseAhead = 0;
            int maxDropAhead = 0;
            int maxHardnessCat = 0;

            for (int i = 1; i <= lookahead; i++) {
                cx += dir[0] * step;
                cz += dir[1] * step;

                if (!baseXZChecks(cx, cz)) break;

                // Light sample: surfaceY, water/ocean, and hardness category.
                int lv = sampler.sampleLightIfLoaded(cx, cz);
                if (lv == TerrainSampler.LIGHT_NOT_LOADED) break;
                boolean sWater = (lv & TerrainSampler.LIGHT_WATER_BIT)  != 0;
                boolean sOcean = (lv & TerrainSampler.LIGHT_OCEAN_BIT) != 0;
                if (sWater || sOcean) break;
                int sSurfaceY = lv & TerrainSampler.LIGHT_SURFACE_MASK;
                int hardnessCat = (lv >> TerrainSampler.LIGHT_HARDNESS_SHIFT) & TerrainSampler.LIGHT_HARDNESS_MASK;
                if (hardnessCat > maxHardnessCat) maxHardnessCat = hardnessCat;

                int projectedRoadY = nextY + stepDy * (i - 1);

                int riseAhead = sSurfaceY - nextY;
                int dropAhead = nextY - sSurfaceY;
                if (riseAhead > maxRiseAhead) maxRiseAhead = riseAhead;
                if (dropAhead > maxDropAhead) maxDropAhead = dropAhead;

                int predictedCut = Math.max(0, sSurfaceY - projectedRoadY);
                if (predictedCut > maxPredictedCut) maxPredictedCut = predictedCut;
                sumPredictedCut += predictedCut;

                // Only scan lateral walls when the road actually cuts into terrain.
                if (predictedCut > 0) {
                    int[] walls = lateralWallHeights(cx, cz, projectedRoadY, dir[0], dir[1]);
                    int wallMin = Math.min(walls[0], walls[1]);
                    if (wallMin > maxWallMin) maxWallMin = wallMin;

                    boolean enclosed = walls[0] >= wallMinHeight && walls[1] >= wallMinHeight;
                    if (enclosed) {
                        enclosedAhead++;
                        currentEnclosedRun++;
                        if (currentEnclosedRun > longestEnclosedRun) longestEnclosedRun = currentEnclosedRun;
                    } else {
                        currentEnclosedRun = 0;
                    }
                } else {
                    currentEnclosedRun = 0;
                }
            }

            if (sumPredictedCut <= 0 && enclosedAhead <= 0) {
                return 0.0;
            }

            double penalty = 0.0;

            // Hard rock (stone, granite, etc.) multiplies cut cost — soft terrain (dirt, snow) stays at 1x.
            double hardnessMult = (maxHardnessCat >= 2)
                    ? Math.max(1.0, MPBConfig.PATHFINDING.hardRockCutMultiplier)
                    : 1.0;

            // Open hillside cuts (terrain only on one side = natural embankment/talus) look realistic
            // and are much cheaper than enclosed trench cuts (terrain on both sides = saw-effect through a ridge).
            // Scale base cut penalty 0.3x when fully open, linearly up to 1.0x when fully enclosed.
            double enclosedFraction = (enclosedAhead > 0 && lookahead > 0)
                    ? Math.min(1.0, (double) enclosedAhead / (double) lookahead)
                    : 0.0;
            double openHillsideFactor = 0.3 + 0.7 * enclosedFraction;
            penalty += (double) sumPredictedCut * Math.max(0.0, MPBConfig.PATHFINDING.predictedCutPenaltyPerBlock) * 0.75 * hardnessMult * openHillsideFactor;

            if (maxPredictedCut > allowedCut) {
                double extra = (double) (maxPredictedCut - allowedCut);
                penalty += extra * extra * 120.0;
            }

            if (enclosedAhead > 0) {
                penalty += Math.max(0.0, MPBConfig.PATHFINDING.enclosedCutPenalty) * 0.5;
                penalty += (double) enclosedAhead * 26.0;
                penalty += (double) Math.max(0, longestEnclosedRun - 1)
                        * Math.max(0.0, MPBConfig.PATHFINDING.sustainedEnclosedCutPenalty) * 0.6;
                penalty += (double) maxWallMin * 10.0;
            }

            boolean sustainedDirectAscent = nextY > curY && maxRiseAhead >= uphillMinRise;
            boolean sustainedDirectDescent = nextY < curY && maxDropAhead >= uphillMinRise;

            if ((sustainedDirectAscent || sustainedDirectDescent) && enclosedAhead > 0) {
                penalty += Math.max(0.0, MPBConfig.PATHFINDING.mountainAscentPenalty);

                int verticalCommit = Math.max(maxRiseAhead, maxDropAhead);
                penalty += (double) verticalCommit * 8.0;
            }

            return penalty;
        }

        private int[] lateralWallHeights(int x, int z, int roadY, int dirDx, int dirDz) {
            int r = Math.max(1, MPBConfig.PATHFINDING.enclosedCutScanRadius);

            double len = Math.sqrt((double) dirDx * (double) dirDx + (double) dirDz * (double) dirDz);
            if (len <= 0.0) return new int[]{0, 0};

            double px = -((double) dirDz) / len;
            double pz = ((double) dirDx) / len;

            int leftWall = 0;
            int rightWall = 0;

            for (int off = 1; off <= r; off++) {
                int lx = (int) Math.round((double) x + px * (double) off);
                int lz = (int) Math.round((double) z + pz * (double) off);
                int lv = sampler.sampleLightIfLoaded(lx, lz);
                if (lv != TerrainSampler.LIGHT_NOT_LOADED
                        && (lv & TerrainSampler.LIGHT_WATER_BIT) == 0
                        && (lv & TerrainSampler.LIGHT_OCEAN_BIT) == 0) {
                    int sy = lv & TerrainSampler.LIGHT_SURFACE_MASK;
                    leftWall = Math.max(leftWall, sy - roadY);
                }

                int rx = (int) Math.round((double) x - px * (double) off);
                int rz = (int) Math.round((double) z - pz * (double) off);
                int rv = sampler.sampleLightIfLoaded(rx, rz);
                if (rv != TerrainSampler.LIGHT_NOT_LOADED
                        && (rv & TerrainSampler.LIGHT_WATER_BIT) == 0
                        && (rv & TerrainSampler.LIGHT_OCEAN_BIT) == 0) {
                    int sy = rv & TerrainSampler.LIGHT_SURFACE_MASK;
                    rightWall = Math.max(rightWall, sy - roadY);
                }
            }

            return new int[]{leftWall, rightWall};
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

        /** Bridge cost multiplier — LONE_TRAIL strongly discourages bridges in favour of land detours. */
        private double bridgeCostMultiplier() {
            if (MPBConfig.ROAD_HIERARCHY.enabled && roadType == RoadType.LONE_TRAIL) {
                return Math.max(1.0, MPBConfig.ROAD_HIERARCHY.loneTrailBridgeCostMultiplier);
            }
            return 1.0;
        }

        private void expandBridgeMoves(Node cur, long ck, TerrainSampler.Sample cs) {
            if (!MPBConfig.BRIDGE_AWARE_PATHFINDING.enabled) return;
            if (!MPBConfig.BRIDGES.enabled) return;

            int minSpacing = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.minDistanceBetweenBridgesBlocks);
            int distFromLastBridge = distSinceBridge.get(ck);
            if (distFromLastBridge < minSpacing) return;

            final double bridgeMult = bridgeCostMultiplier();

            // Offer existing bridge crossings as cheap moves before probing new ones.
            // This prevents building parallel bridges when one already exists nearby.
            List<BridgeMove> moves = new ArrayList<>(buildExistingBridgeMoves(cur, ck, cs));

            // Only probe new bridges near water or valley edges — avoids stadium chunk loading
            if (isNearWaterOrValleyEdge(cur.x, cur.z, cs)) {
                moves.addAll(enumerateBridgeMoves(cur.x, cur.z, cur.dir, ck, cs));
            }

            for (BridgeMove mv : moves) {
                if (!baseXZChecks(mv.nx, mv.nz)) continue;

                long nk = stateKey(mv.nx, mv.nz, mv.ndir);
                if (closed.contains(nk)) continue;

                TerrainSampler.Sample ns = sampler.sample(mv.nx, mv.nz);
                if (sampler.forbidden(ns, TerrainSampler.Mode.PATHFINDING)) {
                    bumpForbidden(ns);
                    continue;
                }

                double tentative = gScore.get(ck) + mv.cost * bridgeMult + sampler.extraCost(ns, TerrainSampler.Mode.PATHFINDING);
                if (tentative < gScore.get(nk)) {
                    cameFrom.put(nk, ck);
                    if (mv.reusedCrossing != null) {
                        cameBridge.remove(nk);
                        cameReusedBridge.put(nk, mv.reusedCrossing);
                        if (MPBConfig.GENERAL.printBridgeDebug) {
                            Ultreia.LOGGER.info(
                                    "[MPB Bridge] reuse transition taken: seg={} ({},{}) -> ({},{})",
                                    mv.reusedCrossing.segmentId, cur.x, cur.z, mv.nx, mv.nz);
                        }
                    } else {
                        cameBridge.put(nk, mv.span);
                        cameReusedBridge.remove(nk);
                    }
                    gScore.put(nk, tentative);
                    distSinceBridge.put(nk, 0);

                    double f = tentative + heuristicToGoal(mv.nx, mv.nz);
                    open.add(new Node(mv.nx, mv.nz, mv.ndir, f));
                }
            }
        }

        /**
         * Build cheap bridge moves that route through existing bridge crossings from MPBRoadNetwork.
         * A* will prefer these over building a new parallel bridge.
         *
         * The transition onto an existing bridge is only offered once the current A* node has actually,
         * validly walked (via expandNormalMoves) to within one grid step of the crossing's exact entrance —
         * never as a radius-wide teleport. The cost of getting there is already paid for by those normal
         * moves (and by bridgeEndpointMagnetism biasing the search toward the entrance from a distance);
         * this method must not add a separate straight-line "walk to start" cost on top of that.
         */
        private List<BridgeMove> buildExistingBridgeMoves(Node cur, long ck, TerrainSampler.Sample cs) {
            if (roadNetwork == null) return Collections.<BridgeMove>emptyList();

            int reuseRadius = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeReuseSearchRadius);
            if (reuseRadius <= 0) return Collections.<BridgeMove>emptyList();

            List<MPBRoadNetwork.BridgeCrossing> crossings = roadNetwork.getBridgeCrossingsNear(cur.x, cur.z, reuseRadius);
            if (crossings.isEmpty()) return Collections.<BridgeMove>emptyList();

            double bonus = Math.max(0.0, MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeReuseBonus);
            boolean debug = MPBConfig.GENERAL.printBridgeDebug;
            // A validated "adjacent" tolerance, not a teleport radius: on the step-spaced A* grid, the
            // nearest lattice point to any target coordinate is at most step*sqrt(2)/2 away, so this is
            // always reachable once the search has genuinely arrived at the crossing's entrance cell.
            double snapTol = (double) step;
            double snapTolSq = snapTol * snapTol;
            List<BridgeMove> out = new ArrayList<>();

            for (MPBRoadNetwork.BridgeCrossing crossing : crossings) {
                double distToStartSq = distSq(cur.x, cur.z, crossing.startX, crossing.startZ);
                if (distToStartSq > snapTolSq) {
                    if (debug) {
                        Ultreia.LOGGER.info(
                                "[MPB Bridge] reuse candidate seg={} rejected: not yet adjacent to entrance (dist={}, tol={})",
                                crossing.segmentId, Math.sqrt(distToStartSq), snapTol);
                    }
                    continue;
                }

                int endX = crossing.endX, endZ = crossing.endZ;
                if (!baseXZChecks(endX, endZ)) continue;

                TerrainSampler.Sample endSample = sampler.sampleIfLoaded(endX, endZ);
                if (endSample == null) continue;
                if (endSample.isOcean) {
                    if (debug) Ultreia.LOGGER.info(
                            "[MPB Bridge] reuse candidate seg={} rejected: far end is ocean", crossing.segmentId);
                    continue;
                }

                // Prefer crossings that go roughly toward our goal
                double toGoalDot = normalizedDot(endX - cur.x, endZ - cur.z, gx - cur.x, gz - cur.z);
                if (toGoalDot < -0.3) {
                    if (debug) Ultreia.LOGGER.info(
                            "[MPB Bridge] reuse candidate seg={} rejected: leads away from goal (dot={})",
                            crossing.segmentId, toGoalDot);
                    continue; // bridge goes away from goal — skip
                }

                // Cost: bridge traversal only, discounted by the reuse bonus. No walk-to-start cost:
                // the hop from cur onto the exact entrance is bounded by snapTol and already implicitly
                // covered by the normal moves that got A* here.
                double bridgeCost = crossing.lengthBlocks * 1.0;
                double cost = Math.max(0.1, bridgeCost - bonus);

                int ndir = nearestDirIndex(endX - cur.x, endZ - cur.z);
                cost += turnCost(cur.dir, ndir, step, MPBConfig.PATHFINDING.turnPenalty);

                if (debug) {
                    Ultreia.LOGGER.info(
                            "[MPB Bridge] reuse candidate seg={} accepted at ({},{}) -> ({},{}) cost={}",
                            crossing.segmentId, cur.x, cur.z, endX, endZ, cost);
                }
                // span = null: this is an existing bridge, RoadPainter must not rebuild it.
                out.add(new BridgeMove(endX, endZ, ndir, cost, null, crossing));
            }

            return out;
        }

        private boolean isNearWaterOrValleyEdge(int x, int z, TerrainSampler.Sample cur) {
            if (cur.nearWaterLike) return true;
            if (cur.smallWater) return true;

            int valleyDrop = MPBConfig.VALLEY_BRIDGING.enabled
                    ? Math.max(4, MPBConfig.VALLEY_BRIDGING.minValleyDepth)
                    : Integer.MAX_VALUE;

            for (int[] d : NEIGHBOR_DIRS) {
                int nx = x + d[0] * step;
                int nz = z + d[1] * step;
                TerrainSampler.Sample ns = sampler.sampleIfLoaded(nx, nz);
                if (ns == null) continue;
                if (ns.isWater && !ns.isOcean) return true;
                if (!ns.isWater && !ns.isOcean && (cur.surfaceY - ns.surfaceY) >= valleyDrop) return true;
            }
            return false;
        }

        private List<BridgeMove> enumerateBridgeMoves(int x, int z, int curDir, long ck, TerrainSampler.Sample cur) {
            ArrayList<BridgeMove> out = new ArrayList<BridgeMove>();

            if (cur == null) return out;
            if (cur.isWater || cur.isOcean) return out;
            if (sampler.isProtectedNoPaint(x, z)) return out;

            int minSpacing = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.minDistanceBetweenBridgesBlocks);
            int distFromLastBridge = distSinceBridge.get(ck);
            if (distFromLastBridge < minSpacing) return out;

            long parentK = cameFrom.get(ck);

            int inDx = 0;
            int inDz = 0;
            boolean hasIncoming = false;

            if (curDir != DIR_NONE) {
                int[] d = NEIGHBOR_DIRS[curDir];
                inDx = d[0];
                inDz = d[1];
                hasIncoming = true;
            } else if (parentK != Long.MIN_VALUE) {
                int px = stateX(parentK);
                int pz = stateZ(parentK);
                inDx = Integer.signum(x - px);
                inDz = Integer.signum(z - pz);
                hasIncoming = (inDx != 0 || inDz != 0);
            }

            final int maxCandidates = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.maxBridgeCandidatesPerNode);

            ArrayList<BridgeDirScore> dirs = new ArrayList<BridgeDirScore>();

            for (int[] dir : BRIDGE_DIRS) {
                if (MPBConfig.BRIDGE_AWARE_PATHFINDING.requireAlignedApproach && hasIncoming) {
                    double dot = normalizedDot(inDx, inDz, dir[0], dir[1]);
                    if (dot < MPBConfig.BRIDGE_AWARE_PATHFINDING.minApproachDot) continue;
                }

                QuickBridgeRayInfo info = scanBridgeRayQuick(x, z, cur.surfaceY, dir[0], dir[1]);
                if (!info.canLaunch) continue;

                double dirScore = 0.0;

                if (info.waterCrossing) dirScore += 80.0;
                if (info.valleyCrossing) dirScore -= 20.0;

                dirScore += info.firstObstacleDist * 7.0;
                dirScore += info.landingDist * 2.0;

                if (hasIncoming) {
                    dirScore -= normalizedDot(inDx, inDz, dir[0], dir[1]) * 18.0;
                }

                dirs.add(new BridgeDirScore(dir[0], dir[1], dirScore, info));
            }

            if (dirs.isEmpty()) return out;

            Collections.sort(dirs, new Comparator<BridgeDirScore>() {
                @Override
                public int compare(BridgeDirScore a, BridgeDirScore b) {
                    return Double.compare(a.score, b.score);
                }
            });

            int dirLimit = Math.min(8, dirs.size());
            for (int i = 0; i < dirLimit; i++) {
                BridgeDirScore d = dirs.get(i);
                List<BridgeMove> moves = buildBridgeMovesFromQuickInfo(x, z, cur, curDir, d.dx, d.dz, d.info);

                for (BridgeMove mv : moves) {
                    out.add(mv);
                    if (out.size() >= maxCandidates) break;
                }

                if (out.size() >= maxCandidates) break;
            }

            if (out.size() > 1) {
                Collections.sort(out, new Comparator<BridgeMove>() {
                    @Override
                    public int compare(BridgeMove a, BridgeMove b) {
                        return Double.compare(a.cost, b.cost);
                    }
                });

                while (out.size() > maxCandidates) {
                    out.remove(out.size() - 1);
                }
            }

            return out;
        }
        private QuickBridgeRayInfo scanBridgeRayQuick(int x, int z, int baselineY, int dirX, int dirZ) {
            final int minLen = Math.max(step * 2, MPBConfig.BRIDGE_AWARE_PATHFINDING.minBridgeLenBlocks);
            final int maxLen = Math.max(minLen, MPBConfig.BRIDGE_AWARE_PATHFINDING.maxProbeLenBlocks);

            final int configuredApproach = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeRoadApproachMaxBlocks);
            final int maxLaunchSearch = Math.min(
                    maxLen,
                    Math.max(16, Math.min(48, minLen + configuredApproach * 2))
            );

            final int minWaterRun = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.minWaterRunBlocks);
            final int minGapRun = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.minMeaningfulGapRun);
            final int maxLeadOut = Math.max(2, MPBConfig.BRIDGE_AWARE_PATHFINDING.maxWaterBridgeLeadOutBlocks + 2);

            int waterRun = 0;
            int gapRun = 0;

            boolean obstacleSeen = false;
            boolean waterCrossing = false;
            boolean valleyCrossing = false;

            int firstObstacleDist = -1;
            int dryAfterObstacle = 0;

            int lastNX = Integer.MIN_VALUE;
            int lastNZ = Integer.MIN_VALUE;

            for (int len = step; len <= maxLen; len += step) {
                int nx = snap(x + dirX * len, step);
                int nz = snap(z + dirZ * len, step);

                if (nx == x && nz == z) continue;
                if (nx == lastNX && nz == lastNZ) continue;

                lastNX = nx;
                lastNZ = nz;

                if (!baseXZChecks(nx, nz)) break;

                // Before finding any obstacle: don't load new chunks — stop the probe.
                // After finding water/valley: allow loading to locate the bridge landing.
                TerrainSampler.Sample s = obstacleSeen
                        ? sampler.sample(nx, nz)
                        : sampler.sampleIfLoaded(nx, nz);
                if (s == null) break;
                if (sampler.isProtectedNoPaint(nx, nz)) break;

                boolean water = s.isWater && !s.isOcean;
                boolean land = !s.isWater && !s.isOcean;
                boolean valley = land && isQuickValleyGap(baselineY, s);

                if (water) {
                    if (!obstacleSeen) firstObstacleDist = len;
                    obstacleSeen = true;
                    waterCrossing = true;
                    waterRun++;
                    gapRun = 0;
                    dryAfterObstacle = 0;
                    continue;
                }

                if (valley) {
                    if (!obstacleSeen) firstObstacleDist = len;
                    obstacleSeen = true;
                    valleyCrossing = true;
                    gapRun++;
                    waterRun = 0;
                    dryAfterObstacle = 0;
                    continue;
                }

                if (!land) {
                    if (obstacleSeen) {
                        dryAfterObstacle++;
                        if (dryAfterObstacle > maxLeadOut) break;
                    }
                    continue;
                }

                if (sampler.forbidden(s, TerrainSampler.Mode.PATHFINDING)) {
                    if (obstacleSeen) {
                        dryAfterObstacle++;
                        if (dryAfterObstacle > maxLeadOut) break;
                    }
                    continue;
                }

                if (!obstacleSeen) {
                    if (len > maxLaunchSearch) {
                        break;
                    }
                    continue;
                }

                boolean goodWater = waterCrossing && waterRun >= minWaterRun;
                boolean goodValley = valleyCrossing && gapRun >= minGapRun;

                if ((goodWater || goodValley) && len >= minLen) {
                    return new QuickBridgeRayInfo(
                            true,
                            goodWater,
                            goodValley,
                            firstObstacleDist < 0 ? len : firstObstacleDist,
                            len,
                            nx,
                            nz
                    );
                }

                dryAfterObstacle++;
                if (dryAfterObstacle > maxLeadOut) {
                    break;
                }
            }

            return new QuickBridgeRayInfo(false, false, false, -1, -1, x, z);
        }

        private boolean isQuickValleyGap(int baselineY, TerrainSampler.Sample s) {
            if (s == null) return false;
            if (s.isWater || s.isOcean) return false;
            if (!MPBConfig.VALLEY_BRIDGING.enabled) return false;

            int drop = baselineY - s.surfaceY;
            return drop >= Math.max(2, MPBConfig.VALLEY_BRIDGING.minValleyDepth);
        }

        private List<BridgeMove> buildBridgeMovesFromQuickInfo(int x,
                                                               int z,
                                                               TerrainSampler.Sample cur,
                                                               int curDir,
                                                               int dirX,
                                                               int dirZ,
                                                               QuickBridgeRayInfo info) {
            ArrayList<BridgeMove> out = new ArrayList<BridgeMove>();
            if (info == null || !info.canLaunch) return out;

            int bridgeDir = nearestDirIndex(dirX, dirZ);

            int[] landingOffsets = new int[] {0, step, -step};

            for (int off : landingOffsets) {
                int probeDist = info.landingDist + off;
                if (probeDist <= 0) continue;

                int nx = snap(x + dirX * probeDist, step);
                int nz = snap(z + dirZ * probeDist, step);

                if (nx == x && nz == z) continue;

                if (!baseXZChecks(nx, nz)) {
                    debugBridge("reject landing outside corridor start=(" + x + "," + z + ") dir=(" + dirX + "," + dirZ + ")"
                            + " probeDist=" + probeDist + " landing=(" + nx + "," + nz + ")");
                    continue;
                }

                TerrainSampler.Sample ns = sampler.sample(nx, nz);
                if (ns == null) {
                    debugBridge("reject landing null sample start=(" + x + "," + z + ") landing=(" + nx + "," + nz + ")");
                    continue;
                }
                if (ns.isWater || ns.isOcean) {
                    debugBridge("reject landing still water start=(" + x + "," + z + ") landing=(" + nx + "," + nz + ")"
                            + " water=" + ns.isWater + " ocean=" + ns.isOcean);
                    continue;
                }
                if (sampler.forbidden(ns, TerrainSampler.Mode.PATHFINDING)) {
                    debugBridge("reject landing forbidden start=(" + x + "," + z + ") landing=(" + nx + "," + nz + ")"
                            + " reason=" + sampler.describeForbidden(ns, TerrainSampler.Mode.PATHFINDING));
                    continue;
                }
                if (sampler.isProtectedNoPaint(nx, nz)) {
                    debugBridge("reject landing protected start=(" + x + "," + z + ") landing=(" + nx + "," + nz + ")");
                    continue;
                }

                BlockPos startPos = new BlockPos(x, cur.surfaceY, z);
                BlockPos endPos = new BlockPos(nx, ns.surfaceY, nz);

                BridgeSpan span = BridgeFeasibility.tryCreate(world, sampler, startPos, endPos, false);

                if (span == null) {
                    continue;
                }

                double cost = bridgeSpanCost(span);

                if (info.waterCrossing) {
                    cost += 40.0;
                } else if (info.valleyCrossing) {
                    cost -= 20.0;
                }

                cost += turnCost(curDir, bridgeDir, step, MPBConfig.PATHFINDING.turnPenalty);

                debugBridge("accept span start=(" + x + "," + cur.surfaceY + "," + z + ")"
                        + " end=(" + nx + "," + ns.surfaceY + "," + nz + ")"
                        + " len=" + span.lengthBlocks()
                        + " cost=" + cost
                        + " waterCrossing=" + info.waterCrossing
                        + " valleyCrossing=" + info.valleyCrossing);

                out.add(new BridgeMove(nx, nz, bridgeDir, cost, span));
            }

            if (out.size() > 1) {
                Collections.sort(out, new Comparator<BridgeMove>() {
                    @Override
                    public int compare(BridgeMove a, BridgeMove b) {
                        return Double.compare(a.cost, b.cost);
                    }
                });

                while (out.size() > 2) {
                    out.remove(out.size() - 1);
                }
            }

            return out;
        }

        private static final class QuickBridgeRayInfo {
            final boolean canLaunch;
            final boolean waterCrossing;
            final boolean valleyCrossing;
            final int firstObstacleDist;
            final int landingDist;
            final int landingX;
            final int landingZ;

            QuickBridgeRayInfo(boolean canLaunch,
                               boolean waterCrossing,
                               boolean valleyCrossing,
                               int firstObstacleDist,
                               int landingDist,
                               int landingX,
                               int landingZ) {
                this.canLaunch = canLaunch;
                this.waterCrossing = waterCrossing;
                this.valleyCrossing = valleyCrossing;
                this.firstObstacleDist = firstObstacleDist;
                this.landingDist = landingDist;
                this.landingX = landingX;
                this.landingZ = landingZ;
            }
        }

        private static final class BridgeDirScore {
            final int dx;
            final int dz;
            final double score;
            final QuickBridgeRayInfo info;

            BridgeDirScore(int dx, int dz, double score, QuickBridgeRayInfo info) {
                this.dx = dx;
                this.dz = dz;
                this.score = score;
                this.info = info;
            }
        }

        private List<BridgeMove> probeBridgeMovesInDirection(int x,
                                                             int z,
                                                             TerrainSampler.Sample startSample,
                                                             int dirX,
                                                             int dirZ) {
            ArrayList<BridgeMove> out = new ArrayList<BridgeMove>();

            final int minLen = Math.max(step * 2, MPBConfig.BRIDGE_AWARE_PATHFINDING.minBridgeLenBlocks);
            final int maxLen = Math.max(minLen, MPBConfig.BRIDGE_AWARE_PATHFINDING.maxProbeLenBlocks);

            final int minWaterRun = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.minWaterRunBlocks);
            final int minGapRun = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.minMeaningfulGapRun);

            final int maxLeadOut = Math.max(
                    2,
                    MPBConfig.BRIDGE_AWARE_PATHFINDING.maxWaterBridgeLeadOutBlocks + 2
            );

            final int bridgeDir = nearestDirIndex(dirX, dirZ);

            int waterRun = 0;
            int gapRun = 0;
            boolean obstacleSeen = false;
            boolean waterCrossingSeen = false;
            boolean valleyCrossingSeen = false;
            int dryAfterObstacle = 0;

            int lastNX = Integer.MIN_VALUE;
            int lastNZ = Integer.MIN_VALUE;

            int baselineY = startSample.surfaceY;

            for (int len = step; len <= maxLen; len += step) {
                int nx = snap(x + dirX * len, step);
                int nz = snap(z + dirZ * len, step);

                if (nx == x && nz == z) continue;
                if (nx == lastNX && nz == lastNZ) continue;

                lastNX = nx;
                lastNZ = nz;

                if (!baseXZChecks(nx, nz)) break;

                TerrainSampler.Sample ns = sampler.sample(nx, nz);
                if (ns == null) break;

                if (sampler.isProtectedNoPaint(nx, nz)) break;

                boolean water = ns.isWater && !ns.isOcean;
                boolean land = !ns.isWater && !ns.isOcean;
                boolean valleyGap = land && isMeaningfulValleyRayCell(baselineY, ns);

                if (water) {
                    waterRun++;
                    gapRun = 0;
                    obstacleSeen = true;
                    waterCrossingSeen = true;
                    dryAfterObstacle = 0;
                    continue;
                }

                if (valleyGap) {
                    gapRun++;
                    waterRun = 0;
                    obstacleSeen = true;
                    valleyCrossingSeen = true;
                    dryAfterObstacle = 0;
                    continue;
                }

                if (!land) {
                    if (obstacleSeen) {
                        dryAfterObstacle++;
                        if (dryAfterObstacle > maxLeadOut) break;
                    }
                    continue;
                }

                if (sampler.forbidden(ns, TerrainSampler.Mode.PATHFINDING)) {
                    if (obstacleSeen) {
                        dryAfterObstacle++;
                        if (dryAfterObstacle > maxLeadOut) break;
                    }
                    continue;
                }

                if (len < minLen) {
                    if (obstacleSeen) {
                        dryAfterObstacle++;
                        if (dryAfterObstacle > maxLeadOut) break;
                    }
                    continue;
                }

                boolean realWaterCrossing = waterCrossingSeen && waterRun >= minWaterRun;
                boolean realValleyCrossing = valleyCrossingSeen && gapRun >= minGapRun;

                if (!realWaterCrossing && !realValleyCrossing) {
                    if (obstacleSeen) {
                        dryAfterObstacle++;
                        if (dryAfterObstacle > maxLeadOut) break;
                    }
                    continue;
                }

                // --- Narrow crossing search ---
                // For water crossings, scan perpendicular landing offsets to find the shortest span.
                int bestLandX = nx, bestLandZ = nz;
                int bestLandY = ns.surfaceY;
                int bestWaterWidth = realWaterCrossing ? measureWaterWidth(x, z, dirX, dirZ, nx, nz) : Integer.MAX_VALUE;

                if (realWaterCrossing) {
                    int lateralScan = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.narrowCrossingLateralScan);
                    if (lateralScan > 0) {
                        // Perpendicular unit direction (rotated 90°)
                        int perpX = -dirZ, perpZ = dirX;
                        double perpLen = Math.sqrt(perpX * perpX + perpZ * perpZ);
                        if (perpLen > 0.5) {
                            int upx = (int) Math.round(perpX / perpLen);
                            int upz = (int) Math.round(perpZ / perpLen);

                            for (int sign = -1; sign <= 1; sign += 2) {
                                for (int off = step; off <= lateralScan; off += step) {
                                    int candX = nx + upx * off * sign;
                                    int candZ = nz + upz * off * sign;
                                    if (!baseXZChecks(candX, candZ)) break;
                                    TerrainSampler.Sample cs2 = sampler.sampleIfLoaded(candX, candZ);
                                    if (cs2 == null) break;
                                    if (cs2.isWater || cs2.isOcean) break;
                                    if (sampler.forbidden(cs2, TerrainSampler.Mode.PATHFINDING)) break;

                                    int w = measureWaterWidth(x, z, dirX, dirZ, candX, candZ);
                                    if (w < bestWaterWidth) {
                                        bestWaterWidth = w;
                                        bestLandX = candX;
                                        bestLandZ = candZ;
                                        bestLandY = cs2.surfaceY;
                                    }
                                }
                            }
                        }
                    }
                }
                // --- End narrow crossing search ---

                BridgeSpan span = BridgeFeasibility.tryCreate(
                        world,
                        sampler,
                        new BlockPos(x, startSample.surfaceY, z),
                        new BlockPos(bestLandX, bestLandY, bestLandZ),
                        false
                );

                if (span != null) {
                    double cost = bridgeSpanCost(span);

                    if (realWaterCrossing) {
                        cost -= 80.0;
                        cost -= Math.min(12, span.lengthBlocks()) * 2.0;
                        // Extra discount when we found a shorter crossing
                        int origWidth = measureWaterWidth(x, z, dirX, dirZ, nx, nz);
                        int savedBlocks = origWidth - bestWaterWidth;
                        if (savedBlocks > 0) {
                            cost -= savedBlocks * Math.max(0.0, MPBConfig.BRIDGE_AWARE_PATHFINDING.narrowCrossingSavingDiscountPerBlock);
                        }
                    } else if (realValleyCrossing) {
                        cost -= 40.0;
                    }

                    out.add(new BridgeMove(bestLandX, bestLandZ, bridgeDir, cost, span));

                    if (realWaterCrossing) {
                        break;
                    }
                }

                if (obstacleSeen) {
                    dryAfterObstacle++;
                    if (dryAfterObstacle > maxLeadOut) break;
                }
            }

            if (out.size() > 1) {
                Collections.sort(out, new Comparator<BridgeMove>() {
                    @Override
                    public int compare(BridgeMove a, BridgeMove b) {
                        return Double.compare(a.cost, b.cost);
                    }
                });

                while (out.size() > 3) {
                    out.remove(out.size() - 1);
                }
            }

            return out;
        }
        private boolean isMeaningfulValleyRayCell(int baselineY, TerrainSampler.Sample s) {
            if (s == null) return false;
            if (s.isWater || s.isOcean) return false;
            if (!MPBConfig.VALLEY_BRIDGING.enabled) return false;

            int drop = baselineY - s.surfaceY;
            return drop >= Math.max(2, MPBConfig.VALLEY_BRIDGING.minValleyDepth);
        }

        /**
         * Count how many water blocks lie along the direct line from (startX,startZ)
         * to (landX,landZ), used to prefer narrow river crossings over wide lake crossings.
         */
        private int measureWaterWidth(int startX, int startZ, int dirX, int dirZ, int landX, int landZ) {
            int dx = landX - startX, dz = landZ - startZ;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1) return 0;

            int steps = Math.max(1, (int) Math.round(len));
            int waterCount = 0;
            for (int t = 1; t < steps; t++) {
                int px = startX + (int) Math.round(dx * t / (double) steps);
                int pz = startZ + (int) Math.round(dz * t / (double) steps);
                TerrainSampler.Sample s = sampler.sampleIfLoaded(px, pz);
                if (s != null && s.isWater && !s.isOcean) waterCount++;
            }
            return waterCount;
        }

        private double bridgeSpanCost(BridgeSpan span) {
            double euclid = Math.sqrt(distSq(span.start.getX(), span.start.getZ(), span.end.getX(), span.end.getZ()));

            double fixed = Math.max(0.0, MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeFixedCost);
            double mult = Math.max(1.0, MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeLengthCostMultiplier);
            double valleyBonus = Math.max(0.0, MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeValleyPreferenceBonusPerBlock);

            int baseline = Math.max(
                    sampler.sample(span.start.getX(), span.start.getZ()).surfaceY,
                    sampler.sample(span.end.getX(), span.end.getZ()).surfaceY
            );

            int deepCells = 0;
            int waterCells = 0;
            int meaningfulCells = 0;
            int sumDepth = 0;
            int maxDepth = 0;
            int longestWaterRun = 0;
            int curWaterRun = 0;

            int[] deckHalf = BridgeGeometry.buildDeckHalfProfile(span, sampler);

            for (int i = 1; i < span.centerline.size() - 1; i++) {
                BlockPos p = span.centerline.get(i);
                TerrainSampler.Sample s = sampler.sample(p.getX(), p.getZ());
                if (s == null) continue;

                int terrainDepth = Math.max(0, baseline - s.surfaceY);
                int deckY = BridgeGeometry.surfaceYFromHalf(deckHalf[i]);
                int clearance = Math.max(0, deckY - s.surfaceY);

                sumDepth += terrainDepth;
                if (terrainDepth > maxDepth) maxDepth = terrainDepth;

                boolean water = s.isWater && !s.isOcean;
                if (water) {
                    waterCells++;
                    curWaterRun++;
                    if (curWaterRun > longestWaterRun) longestWaterRun = curWaterRun;
                } else {
                    curWaterRun = 0;
                }

                if (terrainDepth >= 3) deepCells++;
                if (clearance >= 3 || (water && clearance >= 1)) meaningfulCells++;
            }

            int interior = Math.max(1, span.centerline.size() - 2);
            double avgDepth = (double) sumDepth / (double) interior;
            double meaningfulPct = (100.0 * (double) meaningfulCells) / (double) interior;
            double waterPct = (100.0 * (double) waterCells) / (double) interior;

            boolean waterDominant = waterCells > 0 && waterCells >= deepCells && waterPct >= 30.0;

            double cost = fixed + euclid * mult;

            if (waterDominant) {
                cost += waterBridgePreferencePenalty(longestWaterRun, waterPct, meaningfulPct, euclid, waterCells);
            } else {
                cost -= avgDepth * valleyBonus * 0.75;
                cost -= Math.min(deepCells, 12) * 6.0;

                if (maxDepth <= 1) cost += 220.0;
                else if (maxDepth == 2) cost += 120.0;

                if (avgDepth < 2.0) cost += 140.0;
                else if (avgDepth < 3.0) cost += 70.0;

                if (meaningfulPct < 50.0) cost += 180.0;
                else if (meaningfulPct < 65.0) cost += 80.0;

                if (deepCells <= 2) cost += 120.0;
            }

            if (waterCells <= 0 && deepCells <= 2) cost += 200.0;
            int approachBlocks = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeApproachRampBlocks);
            if (!MPBBridgeApproachFeasibility.canConnectBridgeApproaches(sampler, span, approachBlocks + 2)) {
                cost += 120.0;
            }

            return cost;
        }

        private double waterBridgePreferencePenalty(int longestWaterRun,
                                                    double waterPct,
                                                    double meaningfulPct,
                                                    double euclid,
                                                    int waterCells) {
            double base = Math.max(0.0, MPBConfig.BRIDGE_AWARE_PATHFINDING.waterBridgeBasePenalty);
            double perBlock = Math.max(0.0, MPBConfig.BRIDGE_AWARE_PATHFINDING.waterBridgeLengthPenaltyPerBlock);
            // Progressive penalty: longer water crossings cost proportionally more,
            // making A* strongly prefer narrow river crossings over wide lake crossings.
            return base + waterCells * perBlock;
        }

        private boolean baseXZChecks(int nx, int nz) {
            if (!inCorridor(nx, nz)) return false;
            if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) return false;

            if (maxRadSq > 0) {
                long dx0 = (long) nx - (long) sx;
                long dz0 = (long) nz - (long) sz;
                if ((dx0 * dx0 + dz0 * dz0) > (long) maxRadSq) return false;
            }

            return true;
        }

        /**
         * Rejects paths where a bridge deck passes over its own approach path without sufficient
         * clearance. Only the bridge interior is checked — the first {@code step} centerline cells
         * are skipped because they are the ramp connection to the road and will always be at road Y.
         *
         * A bridge over a DIFFERENT road segment (valley-cross) is fine as long as clearance ≥ min.
         */
        private boolean bridgesHaveApproachPathClearance(List<BlockPos> path, List<BridgeSpan> spans) {
            if (spans.isEmpty() || bridgePathStartIndices.size() != spans.size()) return true;

            int minClearance = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.underBridgeRoadMinClearanceBlocks);
            if (minClearance <= 0) return true;

            // Skip the launch-ramp zone so the bridge connection point never triggers a false reject.
            final int startGrace = Math.max(step, 2);
            // Tight XZ match: 1 block tolerance avoids false matches with off-axis approach nodes.
            final int xzRadius = 1;

            for (int b = 0; b < spans.size(); b++) {
                BridgeSpan span = spans.get(b);
                if (span.centerline == null || span.centerline.isEmpty()) continue;

                int priorEnd = Math.min(bridgePathStartIndices.get(b), path.size() - 1);
                if (priorEnd < 0) continue;

                int[] deckHalf = BridgeGeometry.buildDeckHalfProfile(span, sampler);

                for (int ci = startGrace; ci < span.centerline.size(); ci++) {
                    BlockPos bridgePoint = span.centerline.get(ci);
                    int deckSurfaceY = BridgeGeometry.surfaceYFromHalf(deckHalf[Math.min(ci, deckHalf.length - 1)]);

                    for (int k = 0; k <= priorEnd; k++) {
                        BlockPos roadPoint = path.get(k);
                        if (Math.abs(bridgePoint.getX() - roadPoint.getX()) > xzRadius) continue;
                        if (Math.abs(bridgePoint.getZ() - roadPoint.getZ()) > xzRadius) continue;

                        int freeBlocks = deckSurfaceY - roadPoint.getY() - 1;
                        if (freeBlocks < minClearance) {
                            if (MPBConfig.GENERAL.printBridgeDebug) {
                                Ultreia.LOGGER.info(
                                        "[MPB Bridge] self-overlap bridge[{}]: deck Y={} at ({},{}) over approach road Y={} at ({},{}), clearance={} < {}",
                                        b, deckSurfaceY, bridgePoint.getX(), bridgePoint.getZ(),
                                        roadPoint.getY(), roadPoint.getX(), roadPoint.getZ(),
                                        freeBlocks, minClearance);
                            }
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        private void buildResultFromReconstruction() {
            ArrayList<BlockPos> out = new ArrayList<BlockPos>();
            ArrayList<BridgeSpan> spans = new ArrayList<BridgeSpan>();
            bridgePathStartIndices.clear();

            if (revStates.isEmpty()) {
                result = new BridgeAwarePathResult(Collections.<BlockPos>emptyList(), Collections.<BridgeSpan>emptyList());
                return;
            }

            long first = revStates.get(0);
            int fx = stateX(first);
            int fz = stateZ(first);
            TerrainSampler.Sample fs = sampler.sample(fx, fz);
            out.add(new BlockPos(fx, fs.surfaceY, fz));

            for (int i = 1; i < revStates.size(); i++) {
                long curK = revStates.get(i);
                int cx = stateX(curK);
                int cz = stateZ(curK);

                MPBRoadNetwork.BridgeCrossing reused = cameReusedBridge.get(curK);
                if (reused != null) {
                    appendReusedBridgeWaypoints(out, reused);
                    continue;
                }

                BridgeSpan span = cameBridge.get(curK);
                if (span != null) {
                    spans.add(span);
                    bridgePathStartIndices.add(out.isEmpty() ? 0 : out.size() - 1);

                    int[] deckHalf = BridgeGeometry.buildDeckHalfProfile(span, sampler);

                    if (!out.isEmpty() && span.centerline != null && !span.centerline.isEmpty()) {
                        BlockPos firstBridge = span.centerline.get(0);
                        int firstDeckY = BridgeGeometry.walkSurfaceBlockYFloor(deckHalf[0]);
                        BlockPos prevOut = out.get(out.size() - 1);

                        if (prevOut.getX() == firstBridge.getX() && prevOut.getZ() == firstBridge.getZ()) {
                            out.set(out.size() - 1, new BlockPos(firstBridge.getX(), firstDeckY, firstBridge.getZ()));
                        }
                    }

                    for (int j = 1; j < span.centerline.size(); j++) {
                        BlockPos p = span.centerline.get(j);
                        int deckY = BridgeGeometry.walkSurfaceBlockYFloor(deckHalf[j]);

                        if (out.isEmpty() || out.get(out.size() - 1).getX() != p.getX() || out.get(out.size() - 1).getZ() != p.getZ()) {
                            out.add(new BlockPos(p.getX(), deckY, p.getZ()));
                        }
                    }
                } else {
                    TerrainSampler.Sample s = sampler.sample(cx, cz);

                    if (!out.isEmpty()) {
                        BlockPos prev = out.get(out.size() - 1);
                        long ddx = (long) cx - (long) prev.getX();
                        long ddz = (long) cz - (long) prev.getZ();
                        long manhattan = Math.abs(ddx) + Math.abs(ddz);

                        if (manhattan > 2048L) {
                            failed = true;
                            failReason = "reconstructed path contains absurd jump: (" + prev.getX() + "," + prev.getZ() + ") -> (" + cx + "," + cz + ")";
                            result = new BridgeAwarePathResult(Collections.<BlockPos>emptyList(), Collections.<BridgeSpan>emptyList());
                            return;
                        }
                    }

                    if (out.isEmpty() || out.get(out.size() - 1).getX() != cx || out.get(out.size() - 1).getZ() != cz) {
                        out.add(new BlockPos(cx, s.surfaceY, cz));
                    }
                }
            }

            result = new BridgeAwarePathResult(out, spans);
        }

        /**
         * Splices the exact persisted geometry of a reused bridge into the reconstructed coarse path,
         * instead of the single ground-level point the naive "no span" branch would otherwise emit.
         * Not added to {@code spans}/{@code forcedBridges}: RoadPainter must never rebuild this bridge —
         * it treats these points as ordinary coarse waypoints, and its existing roadIndex.isBridgeXZ /
         * isBridgeVolumeXZ checks already skip painting, earthworks and tree-clearing on them since they
         * were marked in the persistent index when the bridge was first built.
         */
        private void appendReusedBridgeWaypoints(ArrayList<BlockPos> out, MPBRoadNetwork.BridgeCrossing crossing) {
            if (roadNetwork == null) return;
            RoadSegment seg = roadNetwork.getSegment(crossing.segmentId);
            if (seg == null || seg.waypoints.isEmpty()) return;

            List<RoadWaypoint> wps = seg.waypoints;
            // The crossing tells us which physical end of the persisted segment we are entering from,
            // so we walk the stored centerline in the matching direction.
            boolean forward = (seg.startX() == crossing.startX && seg.startZ() == crossing.startZ);

            if (forward) {
                for (RoadWaypoint wp : wps) {
                    appendIfNewXZ(out, wp.x, wp.y, wp.z);
                }
            } else {
                for (int i = wps.size() - 1; i >= 0; i--) {
                    RoadWaypoint wp = wps.get(i);
                    appendIfNewXZ(out, wp.x, wp.y, wp.z);
                }
            }
        }

        private static void appendIfNewXZ(ArrayList<BlockPos> out, int x, int y, int z) {
            if (out.isEmpty() || out.get(out.size() - 1).getX() != x || out.get(out.size() - 1).getZ() != z) {
                out.add(new BlockPos(x, y, z));
            }
        }

        private static int[] nearestValidGrid(int x0, int z0, int step, TerrainSampler sampler) {
            int maxR = Math.max(24, step * 8);

            int bestX = 0, bestZ = 0;
            double best = Double.POSITIVE_INFINITY;
            boolean found = false;

            for (int r = 0; r <= maxR; r += step) {
                for (int dz = -r; dz <= r; dz += step) {
                    for (int dx = -r; dx <= r; dx += step) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;

                        int x = x0 + dx;
                        int z = z0 + dz;

                        TerrainSampler.Sample s = sampler.sample(x, z);
                        if (s == null) continue;
                        if (sampler.forbidden(s, TerrainSampler.Mode.PATHFINDING)) continue;
                        if (s.isOcean || s.isWater) continue;
                        if (sampler.isProtectedNoPaint(x, z)) continue;
                        if (!hasLocalExitFreedom(x, z, step, sampler)) continue;

                        double d2 = (double) dx * (double) dx + (double) dz * (double) dz;

                        if (sampler.isExistingRoadXZ(x, z)) d2 -= 64.0;

                        int exits = countLocalFreeNeighbors(x, z, step, sampler);
                        d2 -= exits * 6.0;

                        if (d2 < best) {
                            best = d2;
                            bestX = x;
                            bestZ = z;
                            found = true;
                        }
                    }
                }

                if (found) return new int[]{bestX, bestZ};
            }

            return found ? new int[]{bestX, bestZ} : null;
        }

        private static boolean hasLocalExitFreedom(int x, int z, int step, TerrainSampler sampler) {
            return countLocalFreeNeighbors(x, z, step, sampler) >= 2;
        }

        private static int countLocalFreeNeighbors(int x, int z, int step, TerrainSampler sampler) {
            int free = 0;

            for (int[] dir : NEIGHBOR_DIRS) {
                int nx = x + dir[0] * step;
                int nz = z + dir[1] * step;

                TerrainSampler.Sample ns = sampler.sample(nx, nz);
                if (ns == null) continue;
                if (sampler.forbidden(ns, TerrainSampler.Mode.PATHFINDING)) continue;
                if (ns.isOcean || ns.isWater) continue;
                if (sampler.isProtectedNoPaint(nx, nz)) continue;

                free++;
            }

            return free;
        }

        public List<BlockPos> getPath() {
            return result.getPath();
        }

        public BridgeAwarePathResult getResult() {
            return result;
        }

        public String failureSummary(boolean detailed) {
            if (!detailed) return failReason;
            return failReason
                    + " it=" + it
                    + " closed=" + closed.size()
                    + " open=" + open.size()
                    + " rejects{ocean=" + skipOcean
                    + ", blacklisted=" + skipBlacklist
                    + ", blocked=" + skipBlocked
                    + ", slope=" + skipSlope
                    + ", bridge=" + skipBridge + "}";
        }

        private static final class SegmentWalkability {
            final boolean walkable;
            final boolean crossesWater;
            final int worstStepDy;
            final int roughness;
            final double terrainPenalty;
            final int maxPredictedCut;
            final int enclosedCells;
            final int longestEnclosedRun;

            SegmentWalkability(boolean walkable,
                               boolean crossesWater,
                               int worstStepDy,
                               int roughness,
                               double terrainPenalty,
                               int maxPredictedCut,
                               int enclosedCells,
                               int longestEnclosedRun) {
                this.walkable = walkable;
                this.crossesWater = crossesWater;
                this.worstStepDy = worstStepDy;
                this.roughness = roughness;
                this.terrainPenalty = terrainPenalty;
                this.maxPredictedCut = maxPredictedCut;
                this.enclosedCells = enclosedCells;
                this.longestEnclosedRun = longestEnclosedRun;
            }
        }

        private SegmentWalkability segmentWalkability(int x0, int z0, int x1, int z1) {
            List<BlockPos> line = SupercoverLine.raster(x0, z0, x1, z1, 0);
            if (line == null || line.size() < 2) {
                return new SegmentWalkability(true, false, 0, 0, 0.0, 0, 0, 0);
            }

            TerrainSampler.Sample first = sampler.sample(line.get(0).getX(), line.get(0).getZ());
            TerrainSampler.Sample last = sampler.sample(line.get(line.size() - 1).getX(), line.get(line.size() - 1).getZ());
            if (first == null || last == null) {
                return new SegmentWalkability(false, true, Integer.MAX_VALUE, Integer.MAX_VALUE, 0.0, Integer.MAX_VALUE, 0, 0);
            }

            int prevY = first.surfaceY;
            int worst = 0;
            int roughness = 0;

            double terrainPenalty = 0.0;
            int maxPredictedCut = 0;
            int enclosedCells = 0;
            int currentEnclosedRun = 0;
            int longestEnclosedRun = 0;

            double ax = (double) x0 + 0.5;
            double az = (double) z0 + 0.5;
            double bx = (double) x1 + 0.5;
            double bz = (double) z1 + 0.5;

            for (int i = 1; i < line.size(); i++) {
                BlockPos p = line.get(i);

                // Interior raster points only need surfaceY/water — use light sample to avoid
                // polluting the full cache with off-grid positions.
                int pSurfaceY;
                boolean pIsWater;
                boolean pIsOcean;

                boolean isInterior = i < line.size() - 1;
                if (isInterior) {
                    int lv = sampler.sampleLightIfLoaded(p.getX(), p.getZ());
                    if (lv == TerrainSampler.LIGHT_NOT_LOADED) {
                        // Chunk not loaded yet — fall back to full sample (will load chunk).
                        TerrainSampler.Sample fs = sampler.sample(p.getX(), p.getZ());
                        if (fs == null) return new SegmentWalkability(false, true, Integer.MAX_VALUE, Integer.MAX_VALUE, 0.0, Integer.MAX_VALUE, 0, 0);
                        pSurfaceY = fs.surfaceY;
                        pIsWater  = fs.isWater;
                        pIsOcean  = fs.isOcean;
                    } else {
                        pSurfaceY = lv & TerrainSampler.LIGHT_SURFACE_MASK;
                        pIsWater  = (lv & TerrainSampler.LIGHT_WATER_BIT)  != 0;
                        pIsOcean  = (lv & TerrainSampler.LIGHT_OCEAN_BIT) != 0;
                    }
                } else {
                    // Last point — use full sample (= grid node, already cached).
                    TerrainSampler.Sample s = sampler.sample(p.getX(), p.getZ());
                    if (s == null) return new SegmentWalkability(false, true, Integer.MAX_VALUE, Integer.MAX_VALUE, 0.0, Integer.MAX_VALUE, 0, 0);
                    pSurfaceY = s.surfaceY;
                    pIsWater  = s.isWater;
                    pIsOcean  = s.isOcean;
                }

                if (pIsOcean || pIsWater) {
                    return new SegmentWalkability(false, true, Integer.MAX_VALUE, Integer.MAX_VALUE, 0.0, Integer.MAX_VALUE, 0, 0);
                }

                int d = Math.abs(pSurfaceY - prevY);
                if (d > worst) worst = d;
                if (d > 1) roughness += (d - 1);
                prevY = pSurfaceY;

                if (isInterior) {
                    double ppx = (double) p.getX() + 0.5;
                    double ppz = (double) p.getZ() + 0.5;
                    double t = segmentParam01(ppx, ppz, ax, az, bx, bz);

                    int roadY = (int) Math.round((1.0 - t) * (double) first.surfaceY + t * (double) last.surfaceY);
                    int predictedCut = Math.max(0, pSurfaceY - roadY);
                    int predictedFill = Math.max(0, roadY - pSurfaceY);

                    if (predictedCut > maxPredictedCut) maxPredictedCut = predictedCut;

                    if (predictedFill > 0) {
                        terrainPenalty += (double) predictedFill * Math.max(0.0, MPBConfig.PATHFINDING.predictedFillPenaltyPerBlock);
                    }

                    if (predictedCut > 0) {
                        terrainPenalty += (double) predictedCut * Math.max(0.0, MPBConfig.PATHFINDING.predictedCutPenaltyPerBlock);

                        // Only probe lateral walls when terrain actually needs cutting.
                        int wallMinHeight = Math.max(1, MPBConfig.PATHFINDING.enclosedWallMinHeight);
                        int[] walls = lateralWallHeights(p.getX(), p.getZ(), roadY, x1 - x0, z1 - z0);

                        boolean enclosed = walls[0] >= wallMinHeight && walls[1] >= wallMinHeight;
                        if (enclosed) {
                            int wallMin = Math.min(walls[0], walls[1]);
                            enclosedCells++;
                            currentEnclosedRun++;
                            if (currentEnclosedRun > longestEnclosedRun) longestEnclosedRun = currentEnclosedRun;
                            terrainPenalty += Math.max(0.0, MPBConfig.PATHFINDING.enclosedCutPenalty);
                            terrainPenalty += (double) wallMin * 8.0;
                        } else {
                            currentEnclosedRun = 0;
                        }
                    } else {
                        currentEnclosedRun = 0;
                    }
                }
            }

            if (longestEnclosedRun > 1) {
                terrainPenalty += (double) (longestEnclosedRun - 1) * Math.max(0.0, MPBConfig.PATHFINDING.sustainedEnclosedCutPenalty);
            }

            int maxRough = Math.max(0, MPBConfig.PATHFINDING.maxSegmentRoughness);
            int maxPredictedCutAllowed = Math.max(0, MPBConfig.PATHFINDING.predictedMaxCutDepth);

            // Extra tolerance applied only during pathfinding — lets A* find routes through steeper terrain
            // without changing what the painter actually does. Painter always uses its own earthworks limits.
            int pfTolerance = Math.max(0, MPBConfig.PATHFINDING.pathfindingEarthworksTolerance);

            // Scale roughness limit proportionally with adaptive step (more raster points = higher budget).
            int baseStep = Math.max(2, MPBConfig.PATHFINDING.gridStep);
            int scaledMaxRough = (step == baseStep)
                    ? maxRough
                    : (int) Math.ceil((double) maxRough * (double) step / (double) baseStep);

            boolean severeEnclosedCut = longestEnclosedRun >= 2
                    && enclosedCells >= 2
                    && maxPredictedCut > Math.max(1, maxPredictedCutAllowed + pfTolerance);

            boolean coarseTerrainOk = roughness <= (scaledMaxRough + 2)
                    && maxPredictedCut <= (maxPredictedCutAllowed + 1 + pfTolerance)
                    && !severeEnclosedCut;

            if (!coarseTerrainOk) {
                return new SegmentWalkability(false, false, worst, roughness, terrainPenalty, maxPredictedCut, enclosedCells, longestEnclosedRun);
            }

            int startHalf = BridgeGeometry.naturalSurfaceHalfY(first.surfaceY);
            int endHalf = BridgeGeometry.naturalSurfaceHalfY(last.surfaceY);

            int cutHalf = MPBConfig.PATHFINDING.allowEarthworks
                    ? Math.max(0, MPBConfig.PAINTING.earthworksMaxCut + pfTolerance) * 2
                    : 0;
            int fillHalf = MPBConfig.PATHFINDING.allowEarthworks
                    ? Math.max(0, MPBConfig.PAINTING.earthworksMaxFill + pfTolerance) * 2
                    : 0;
            int maxStepHalf = Math.max(1, MPBConfig.PAINTING.maxRoadStepHalfPerBlock);

            boolean gradeFeasible = MPBRoadHalfConstraintSolver.isLineFeasible(
                    line,
                    sampler,
                    startHalf,
                    endHalf,
                    MPBConfig.PATHFINDING.allowEarthworks,
                    cutHalf,
                    fillHalf,
                    maxStepHalf
            );

            if (!gradeFeasible) {
                return new SegmentWalkability(false, false, worst, roughness, terrainPenalty, maxPredictedCut, enclosedCells, longestEnclosedRun);
            }

            return new SegmentWalkability(true, false, worst, roughness, terrainPenalty, maxPredictedCut, enclosedCells, longestEnclosedRun);
        }

        private void bumpForbidden(TerrainSampler.Sample s) {
            TerrainSampler.ForbiddenReason r = sampler.forbiddenReason(s, TerrainSampler.Mode.PATHFINDING);
            if (r == TerrainSampler.ForbiddenReason.OCEAN) skipOcean++;
            else if (r == TerrainSampler.ForbiddenReason.BLACKLIST) skipBlacklist++;
            else if (r == TerrainSampler.ForbiddenReason.WATER) skipBridge++;
            else if (r == TerrainSampler.ForbiddenReason.BLOCKED) skipBlocked++;
            else skipBlocked++;
        }

        private boolean inCorridor(int x, int z) {
            if (segLenSq <= 0) return true;

            long px = x;
            long pz = z;

            long apx = px - ax;
            long apz = pz - az;

            double t = (double) (apx * segDx + apz * segDz) / (double) segLenSq;
            if (t < 0.0) t = 0.0;
            else if (t > 1.0) t = 1.0;

            double cx = (double) ax + t * (double) segDx;
            double cz = (double) az + t * (double) segDz;

            double dx = (double) px - cx;
            double dz = (double) pz - cz;

            double d2 = dx * dx + dz * dz;
            double r = (double) corridorHalfWidth;

            return d2 <= r * r;
        }
        public String debugStartArea() {
            return debugAreaAround(sx, sz, "start");
        }

        public String debugGoalArea() {
            return debugAreaAround(gx, gz, "goal");
        }

        private String debugAreaAround(int x, int z, String label) {
            int step = Math.max(2, this.step);

            int free1 = AStarPathfinder.countFreeNeighbors(x, z, step, sampler, 1);
            int free2 = AStarPathfinder.countFreeNeighbors(x, z, step, sampler, 2);
            int free3 = AStarPathfinder.countFreeNeighbors(x, z, step, sampler, 3);

            int blocked1 = AStarPathfinder.countBlockedNeighbors(x, z, step, sampler, 1);
            int blocked2 = AStarPathfinder.countBlockedNeighbors(x, z, step, sampler, 2);
            int blocked3 = AStarPathfinder.countBlockedNeighbors(x, z, step, sampler, 3);

            TerrainSampler.Sample s = sampler.sample(x, z);
            TerrainSampler.ForbiddenReason fr = sampler.forbiddenReason(s, TerrainSampler.Mode.PATHFINDING);

            int roadNear = Integer.MAX_VALUE;
            if (sampler.findNearestExistingRoad(x, z, 16) != null) {
                BlockPos p = sampler.findNearestExistingRoad(x, z, 16);
                roadNear = Math.abs(p.getX() - x) + Math.abs(p.getZ() - z);
            }

            return label
                    + "=(" + x + "," + z + ")"
                    + " surfY=" + (s != null ? s.surfaceY : -1)
                    + " forbidden=" + fr
                    + " protected=" + sampler.isProtectedNoPaint(x, z)
                    + " roadNear=" + (roadNear == Integer.MAX_VALUE ? -1 : roadNear)
                    + " free{r1=" + free1 + ",r2=" + free2 + ",r3=" + free3 + "}"
                    + " blocked{r1=" + blocked1 + ",r2=" + blocked2 + ",r3=" + blocked3 + "}"
                    + " closed=" + closed.size()
                    + " open=" + open.size();
        }

        private boolean shouldAbortForNoProgress() {
            if (bestApproxState == Long.MIN_VALUE) return false;
            if (!Double.isFinite(bestApproxHeuristic) || !Double.isFinite(startHeuristic)) return false;

            double minMeaningfulGain = Math.max((double) step * 3.0, startHeuristic * 0.03);
            boolean improved = (lastProgressHeuristic - bestApproxHeuristic) >= minMeaningfulGain;

            if (improved) {
                lastProgressHeuristic = bestApproxHeuristic;
                stagnationSteps = 0;
                return false;
            }

            // Altitude progress: A* may be executing a switchback (lateral traversal with no XZ gain).
            // If it has climbed/descended at least 20% of the needed altitude, don't count as stagnant.
            if (MPBConfig.PATHFINDING.switchbackStagnationBoost && hasAltitudeProgressTowardGoal()) {
                stagnationSteps = 0;
                return false;
            }

            stagnationSteps++;

            int routeLen = (int) Math.round(startHeuristic);

            int threshold;
            if (routeLen <= 256) {
                threshold = 6000;
            } else if (routeLen <= 512) {
                threshold = 9000;
            } else if (routeLen <= 1024) {
                threshold = 14000;
            } else {
                threshold = 22000;
            }

            threshold = Math.min(threshold, Math.max(4000, maxIt / 12));

            // On steep mountain routes, switchbacks require more lateral exploration before making XZ progress.
            // Multiply the stagnation threshold so A* doesn't abort mid-switchback.
            if (MPBConfig.PATHFINDING.switchbackStagnationBoost) {
                int totalDY = Math.abs(goalY - startY);
                if (totalDY >= 12) {
                    int boostFactor = Math.min(4, 1 + totalDY / 16);
                    threshold = Math.min(threshold * boostFactor, maxIt / 4);
                }
            }

            if (open.isEmpty()) {
                return true;
            }

            Node bestOpen = open.peek();
            if (bestOpen == null) {
                return true;
            }

            double bestOpenH = heuristicToGoal(bestOpen.x, bestOpen.z);

            boolean frontierStillPromising = bestOpenH + (double) (step * 2) < bestApproxHeuristic;
            if (frontierStillPromising) {
                return false;
            }

            return stagnationSteps >= threshold;
        }

        private boolean hasAltitudeProgressTowardGoal() {
            int totalDY = Math.abs(goalY - startY);
            if (totalDY < 8) return false;
            if (bestApproxState == Long.MIN_VALUE) return false;

            int bx = stateX(bestApproxState);
            int bz = stateZ(bestApproxState);
            TerrainSampler.Sample bs = sampler.sampleIfLoaded(bx, bz);
            if (bs == null) return false;

            int progressY = Math.abs(bs.surfaceY - startY);
            return (double) progressY / (double) totalDY >= 0.20;
        }
        private void debugBridge(String msg) {
            if (!MPBConfig.GENERAL.printBridgeDebug) return;

            if (bridgeDebugPrinted < BRIDGE_DEBUG_LIMIT) {
                Ultreia.LOGGER.info("[MPB bridge-debug] {}", msg);
                bridgeDebugPrinted++;
            } else {
                bridgeDebugSuppressed++;
            }
        }

        private void flushBridgeDebugSummary() {
            if (!MPBConfig.GENERAL.printBridgeDebug) return;
            if (bridgeDebugSuppressed > 0) {
                Ultreia.LOGGER.info("[MPB bridge-debug] suppressed {} additional bridge debug lines", bridgeDebugSuppressed);
                bridgeDebugSuppressed = 0;
            }
        }

        private static double nearestCrossingStartDist(int x, int z, List<MPBRoadNetwork.BridgeCrossing> crossings) {
            double best = Double.POSITIVE_INFINITY;
            for (MPBRoadNetwork.BridgeCrossing c : crossings) {
                double d = Math.sqrt(distSq(x, z, c.startX, c.startZ));
                if (d < best) best = d;
            }
            return best;
        }
    }

    public Session startSession(WorldServer world, BlockPos start, BlockPos goal, TerrainSampler sampler) {
        return new Session(world, start, goal, sampler);
    }

    private static int snap(int v, int step) {
        return MPBPos2D.snapDown(v, step);
    }

    private static double heuristic(int x, int z, int gx, int gz) {
        return Math.sqrt(MPBPos2D.distSq(x, z, gx, gz));
    }

    private static double distSq(int x1, int z1, int x2, int z2) {
        return MPBPos2D.distSq(x1, z1, x2, z2);
    }

    private static final int STATE_DIR_BITS = 5;
    private static final int STATE_COORD_BITS = 26;
    private static final int STATE_COORD_MASK = (1 << STATE_COORD_BITS) - 1;
    private static final int STATE_COORD_BIAS = 1 << (STATE_COORD_BITS - 1);

    private static long stateKey(int x, int z, int dir) {
        int px = packCoord26(x);
        int pz = packCoord26(z);
        long ud = ((long) dir) & 0x1fL; // 5 bits: covers directions 0-15 and DIR_NONE=16

        return (((long) px) << (STATE_COORD_BITS + STATE_DIR_BITS))
                | (((long) pz) << STATE_DIR_BITS)
                | ud;
    }

    private static int stateX(long key) {
        int px = (int) ((key >> (STATE_COORD_BITS + STATE_DIR_BITS)) & STATE_COORD_MASK);
        return unpackCoord26(px);
    }

    private static int stateZ(long key) {
        int pz = (int) ((key >> STATE_DIR_BITS) & STATE_COORD_MASK);
        return unpackCoord26(pz);
    }

    private static int stateDir(long key) {
        return (int) (key & 0x1fL); // 5 bits to match stateKey encoding
    }

    private static int packCoord26(int v) {
        int p = v + STATE_COORD_BIAS;
        if (p < 0 || p > STATE_COORD_MASK) {
            throw new IllegalArgumentException("Coordinate out of supported range for packed A* state: " + v);
        }
        return p;
    }

    private static int unpackCoord26(int p) {
        return p - STATE_COORD_BIAS;
    }

    private static int nearestDirIndex(int dx, int dz) {
        if (dx == 0 && dz == 0) return 0;

        int best = 0;
        double bestDot = -Double.MAX_VALUE;

        for (int i = 0; i < Session.NEIGHBOR_DIRS.length; i++) {
            int[] d = Session.NEIGHBOR_DIRS[i];
            double dot = normalizedDot(dx, dz, d[0], d[1]);
            if (dot > bestDot) {
                bestDot = dot;
                best = i;
            }
        }

        return best;
    }
    private static double normalizedDot(int ax, int az, int bx, int bz) {
        double al = Math.sqrt((double) ax * (double) ax + (double) az * (double) az);
        double bl = Math.sqrt((double) bx * (double) bx + (double) bz * (double) bz);
        if (al <= 0.0 || bl <= 0.0) return 1.0;
        return ((double) ax * (double) bx + (double) az * (double) bz) / (al * bl);
    }

    /**
     * Single turn-cost model shared by every kind of move — plain road steps, launching onto a fresh
     * bridge, and snapping onto a reused one. Quadratic on turn angle: nearly free for gentle curves
     * (≤45°), significant at 90°, prohibitive for reversals. Bridges used to be exempt from this (cost
     * driven only by their own geometry, gated by a separate hard alignment cutoff), which let a bridge
     * attach at an angle no ordinary road turn would ever be allowed to take.
     */
    private static double turnCost(int fromDir, int toDir, int step, double turnPenalty) {
        if (fromDir == DIR_NONE || fromDir == toDir) return 0.0;
        int[] fromD = Session.NEIGHBOR_DIRS[fromDir];
        int[] toD = Session.NEIGHBOR_DIRS[toDir];
        double dot = normalizedDot(fromD[0], fromD[1], toD[0], toD[1]);
        double t = (1.0 - dot) * 0.5; // 0=straight, 0.5=90°, 1=reversal
        return t * t * turnPenalty * step;
    }
    public static String debugEndpoint(WorldServer world,
                                       TerrainSampler sampler,
                                       MPBProtectedAreas protectedAreas,
                                       MPBRoadIndexData roadIndex,
                                       BlockPos pos) {
        if (pos == null) return "endpoint=null";

        TerrainSampler.Sample s = sampler.sample(pos.getX(), pos.getZ());
        if (s == null) return "endpoint=(" + pos.getX() + "," + pos.getZ() + ") sample=null";

        int free1 = countFreeNeighbors(pos.getX(), pos.getZ(), Math.max(2, MPBConfig.PATHFINDING.gridStep), sampler, 1);
        int free2 = countFreeNeighbors(pos.getX(), pos.getZ(), Math.max(2, MPBConfig.PATHFINDING.gridStep), sampler, 2);
        int free3 = countFreeNeighbors(pos.getX(), pos.getZ(), Math.max(2, MPBConfig.PATHFINDING.gridStep), sampler, 3);

        int blocked1 = countBlockedNeighbors(pos.getX(), pos.getZ(), Math.max(2, MPBConfig.PATHFINDING.gridStep), sampler, 1);
        int blocked2 = countBlockedNeighbors(pos.getX(), pos.getZ(), Math.max(2, MPBConfig.PATHFINDING.gridStep), sampler, 2);

        int protectPenalty = protectedAreas != null ? protectedAreas.penaltyAt(pos.getX(), pos.getZ()) : 0;
        boolean noPaint = protectedAreas != null && protectedAreas.contains(pos.getX(), pos.getZ());
        boolean road = roadIndex != null && roadIndex.isRoadXZ(pos.getX(), pos.getZ());

        BlockPos nearRoad = roadIndex != null ? roadIndex.findNearestRoad(pos.getX(), pos.getZ(), 12) : null;
        int nearRoadDist = nearRoad == null ? -1
                : (Math.abs(nearRoad.getX() - pos.getX()) + Math.abs(nearRoad.getZ() - pos.getZ()));

        return "endpoint=(" + pos.getX() + "," + pos.getY() + "," + pos.getZ() + ")"
                + " surfY=" + s.surfaceY
                + " water=" + s.isWater
                + " ocean=" + s.isOcean
                + " blockedPath=" + s.blockedPath
                + " blockedPaint=" + s.blockedPaint
                + " noPaint=" + noPaint
                + " protectPenalty=" + protectPenalty
                + " road=" + road
                + " nearRoadDist=" + nearRoadDist
                + " free{r1=" + free1 + ",r2=" + free2 + ",r3=" + free3 + "}"
                + " blocked{r1=" + blocked1 + ",r2=" + blocked2 + "}";
    }

    private static int countFreeNeighbors(int x, int z, int step, TerrainSampler sampler, int ring) {
        int free = 0;

        for (int dz = -ring; dz <= ring; dz++) {
            for (int dx = -ring; dx <= ring; dx++) {
                if (dx == 0 && dz == 0) continue;
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;

                int xx = x + dx * step;
                int zz = z + dz * step;

                TerrainSampler.Sample s = sampler.sample(xx, zz);
                if (s == null) continue;
                if (sampler.forbidden(s, TerrainSampler.Mode.PATHFINDING)) continue;
                if (s.isWater || s.isOcean) continue;
                if (sampler.isProtectedNoPaint(xx, zz)) continue;

                free++;
            }
        }

        return free;
    }

    private static int countBlockedNeighbors(int x, int z, int step, TerrainSampler sampler, int ring) {
        int blocked = 0;

        for (int dz = -ring; dz <= ring; dz++) {
            for (int dx = -ring; dx <= ring; dx++) {
                if (dx == 0 && dz == 0) continue;
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;

                int xx = x + dx * step;
                int zz = z + dz * step;

                TerrainSampler.Sample s = sampler.sample(xx, zz);
                if (s == null) {
                    blocked++;
                    continue;
                }
                if (sampler.forbidden(s, TerrainSampler.Mode.PATHFINDING) || s.isWater || s.isOcean || sampler.isProtectedNoPaint(xx, zz)) {
                    blocked++;
                }
            }
        }

        return blocked;
    }

}
package com.jubitus.ultreia.route;

import com.jubitus.ultreia.bridge.BridgeClearanceCheck;
import com.jubitus.ultreia.bridge.BridgeDeckProfile;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.geom.LineRaster;
import com.jubitus.ultreia.terrain.TerrainView;
import com.jubitus.ultreia.bridge.BridgeFeasibility;
import com.jubitus.ultreia.bridge.BridgeGeometry;
import com.jubitus.ultreia.bridge.BridgeSpan;
import com.jubitus.ultreia.terrain.ProtectedAreas;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.bridge.BridgeMoveCost;
import com.jubitus.ultreia.bridge.BridgeMoveGenerator;
import com.jubitus.ultreia.bridge.BridgeMoveGenerator.BridgeMove;
import com.jubitus.ultreia.paint.RoadHeightSolver;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.roads.RoadIndex;
import com.jubitus.ultreia.roads.RoadNetwork;
import com.jubitus.ultreia.roads.RoadSegment;
import com.jubitus.ultreia.roads.RoadWaypoint;
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

import static com.jubitus.ultreia.route.GridMoves.DIR_NONE;
import static com.jubitus.ultreia.route.GridMoves.NEIGHBOR_DIRS;
import static com.jubitus.ultreia.route.GridMoves.nearestDirIndex;
import static com.jubitus.ultreia.route.GridMoves.normalizedDot;
import static com.jubitus.ultreia.route.GridMoves.snap;
import static com.jubitus.ultreia.route.GridMoves.turnCost;

public final class AStarPathfinder {

    // Grid step in blocks.
    private static final int GRID_STEP = 2;
    // Max iterations for A* expansions per edge.
    private static final int MAX_ITERATIONS = 10000000;
    // Wall-clock timeout in seconds per path.
    private static final int MAX_WALL_TIME_SECONDS = 320;
    // Max vertical delta allowed between neighbor grid nodes.
    public static final int MAX_YDELTA_PER_STEP = 2;
    // Extra cost per unit of local terrain roughness along a segment.
    private static final double SURFACE_ROUGHNESS_PENALTY = 3.0;
    // Hard cap for cumulative roughness along one neighbor segment.
    private static final int MAX_SEGMENT_ROUGHNESS = 4;
    // Extra margin around the start-goal bounding box (blocks).
    private static final int SEARCH_MARGIN = 230;
    // Hard cap radius from start (blocks).
    private static final int MAX_SEARCH_RADIUS = 0;
    // Maximum half-width of the A* search corridor (blocks).
    private static final int CORRIDOR_HALF_WIDTH_MAX = 256;
    // Minimum total rise within lookahead before a straight uphill is considered 'direct climb'.
    private static final int UPHILL_LOOKAHEAD_MIN_RISE = 3;
    // Predicted maximum cut depth pathfinding treats as normal terrain shaping on one move.
    private static final int PREDICTED_MAX_CUT_DEPTH = 4;
    // Penalty per block of predicted cut depth when a move cuts into terrain.
    private static final double PREDICTED_CUT_PENALTY_PER_BLOCK = 50.0;
    // Penalty per block of fill required along a segment.
    private static final double PREDICTED_FILL_PENALTY_PER_BLOCK = 120.0;
    // Lateral scan radius used to detect enclosed mountain cutting.
    private static final int ENCLOSED_CUT_SCAN_RADIUS = 3;
    // Minimum wall height on both sides for a cell to count as enclosed cutting.
    private static final int ENCLOSED_WALL_MIN_HEIGHT = 2;
    // Penalty added when a move enters enclosed cutting on both sides (trench/saw-through-ridge).
    private static final double ENCLOSED_CUT_PENALTY = 150.0;
    // Extra penalty per additional enclosed cell in a sustained enclosed cut run.
    private static final double SUSTAINED_ENCLOSED_CUT_PENALTY = 100.0;
    // Forward lookahead used to discourage direct enclosed mountain ascent.
    private static final int MOUNTAIN_ASCENT_LOOKAHEAD = 4;
    // Penalty for continuing into a sustained enclosed uphill push.
    private static final double MOUNTAIN_ASCENT_PENALTY = 50.0;
    // Cut cost multiplier applied when the terrain being cut is hard rock (stone, granite, etc.).
    private static final double HARD_ROCK_CUT_MULTIPLIER = 1.5;
    // Maximum number of full-path profile rejections before A* gives up.
    private static final int PROFILE_REJECTION_LIMIT = 300;
    // If true, multiply the stagnation-abort threshold when the route has a large elevation change.
    private static final boolean SWITCHBACK_STAGNATION_BOOST = true;
    // Extra earthworks tolerance (blocks) added at pathfinding time only, on top of earthworksMaxCut/Fill.
    private static final int PATHFINDING_EARTHWORKS_TOLERANCE = 4;
    // If true, automatically increases gridStep for very long routes to limit search space.
    private static final boolean ADAPTIVE_GRID_STEP = true;
    // Cost discount applied to normal A* moves that step closer to a known existing bridge crossing's entrance,...
    private static final double BRIDGE_ENDPOINT_MAGNETISM_BONUS = 12.0;

    public enum StepResult { RUNNING, FOUND, PARTIAL, FAILED }

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

    public static final class Session {
        private int minX, maxX, minZ, maxZ;
        private int sx, sz;
        private int maxRadSq;

        private final WorldServer world;
        private final TerrainView sampler;
        private final int step;
        private final int configStep;
        private final BridgeMoveGenerator bridgeMoves;
        private int gx, gz;

        @javax.annotation.Nullable private RoadNetwork roadNetwork;
        private RoadType roadType = RoadType.VILLAGE_MAIN;

        // Multi-gate support: when set, A* can terminate at any gate in this field.
        @javax.annotation.Nullable private VillageGateField destGates;
        @javax.annotation.Nullable private net.minecraft.util.math.BlockPos reachedGate;

        // Road-snap: for LONE_TRAIL, terminate A* as soon as we are within snapRadius of any
        // existing road block (that we didn't place ourselves).
        @javax.annotation.Nullable private RoadIndex snapRoadIndex = null;
        private int snapRadius = 0;
        private double snapMinDistSq = 0;

        private final Long2DoubleOpenHashMap gScore = new Long2DoubleOpenHashMap();
        private final Long2LongOpenHashMap cameFrom = new Long2LongOpenHashMap();
        private final Long2ObjectOpenHashMap<BridgeSpan> cameBridge = new Long2ObjectOpenHashMap<>();
        // Separate from cameBridge because a reused-bridge move stores span=null (nothing new to build),
        // which is indistinguishable from "no bridge at all" in a Long2ObjectOpenHashMap lookup.
        private final Long2ObjectOpenHashMap<RoadNetwork.BridgeCrossing> cameReusedBridge = new Long2ObjectOpenHashMap<>();
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

        private PathResult result = new PathResult(Collections.<BlockPos>emptyList(), Collections.<BridgeSpan>emptyList());

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

        private static final int BRIDGE_DEBUG_LIMIT = 16;
        private int bridgeDebugPrinted = 0;
        private int bridgeDebugSuppressed = 0;

        Session(WorldServer world, BlockPos start, BlockPos goal, TerrainView sampler) {
            this.world = world;
            this.sampler = sampler;

            this.configStep = Math.max(2, GRID_STEP);
            {
                if (ADAPTIVE_GRID_STEP) {
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
            this.bridgeMoves = new BridgeMoveGenerator(sampler, step, this::baseXZChecks, this::debugBridge);

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

            int marginBase = Math.max(48, SEARCH_MARGIN);

            TerrainSampler.Sample ss = sampler.sample(this.sx, this.sz);
            TerrainSampler.Sample gs = sampler.sample(this.gx, this.gz);
            int dyGoal = Math.abs(gs.surfaceY - ss.surfaceY);
            this.startY = (ss != null) ? ss.surfaceY : 64;
            this.goalY  = (gs != null) ? gs.surfaceY : 64;

            int m = Math.max(64, SEARCH_MARGIN / 2);
            // climbBoost scales up to corridorHalfWidthMax so the corridor covers full switchback room.
            int climbBoost = Math.min(Math.max(256, CORRIDOR_HALF_WIDTH_MAX), dyGoal * 8);

            int routeLen = (int) Math.round(Math.sqrt((double) segLenSq));
            int longRouteAllowance = Math.min(768, Math.max(128, routeLen / 3));

            this.corridorHalfWidth = Math.max(m + climbBoost, longRouteAllowance);

            // Apply user-configured cap before adaptive step scaling.
            int configuredMax = Math.max(64, CORRIDOR_HALF_WIDTH_MAX);
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

            int mr = Math.max(0, MAX_SEARCH_RADIUS);
            this.maxRadSq = (mr > 0) ? (mr * mr) : -1;

            // For adaptive step: increase iteration budget proportionally to step ratio,
            // since each iteration covers more ground and the search finishes faster per cell.
            int configMaxIt = MAX_ITERATIONS;
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

        public void setRoadNetwork(@javax.annotation.Nullable RoadNetwork network) {
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
        public void setSnapToRoad(@javax.annotation.Nullable RoadIndex index, int radius) {
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
                int maxWallSec = Math.max(30, MAX_WALL_TIME_SECONDS);
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
                if (UltreiaConfig.DEBUG.logPathfinding
                        && nowNs - lastProgressLogNs > 10_000_000_000L) {
                    lastProgressLogNs = nowNs;
                    long elapsedSec = (nowNs - sessionStartNs) / 1_000_000_000L;
                    Ultreia.LOGGER.info(
                            "[Ultreia A*] still running: it={} closed={} open={} best_dist={} elapsed={}s",
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

                    if (Pos2D.distSq(cur.x, cur.z, gx, gz) <= (double) step * (double) step) {
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
                    if (snapRoadIndex != null && Pos2D.distSq(cur.x, cur.z, sx, sz) >= snapMinDistSq) {
                        BlockPos nearRoad = snapRoadIndex.findNearestRoad(cur.x, cur.z, snapRadius);
                        if (nearRoad != null
                                && !snapRoadIndex.isBridgeXZ(nearRoad.getX(), nearRoad.getZ())
                                // The road block itself must also be far from the start so we don't
                                // snap back onto internal building paths of the departure structure.
                                && Pos2D.distSq(nearRoad.getX(), nearRoad.getZ(), sx, sz) >= snapMinDistSq) {
                            Ultreia.LOGGER.info(
                                    "[Ultreia A*] Road-snap: ({},{}) snapped to road at ({},{})",
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
                        PathResult rr = result;
                        if (rr == null || rr.getPath() == null || rr.getPath().isEmpty()) {
                            failed = true;
                            failReason = "reconstruction produced empty path";
                            return StepResult.FAILED;
                        }

                        boolean fullProfileOk = PathProfileCheck.isFullPathFeasible(
                                rr.getPath(),
                                rr.getBridges(),
                                sampler,
                                roadType
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
                            result = new PathResult(
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
                            int maxRejections = Math.max(24, PROFILE_REJECTION_LIMIT);
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
            if (!UltreiaConfig.PATHFINDING.allowPartialPathFallback) return false;
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
            final int configMaxDy = Math.max(1, MAX_YDELTA_PER_STEP);
            // LONE_TRAIL: no earthworks → path follows terrain strictly.
            final boolean isLoneTrail = RoadType.ROAD_HIERARCHY_ENABLED && roadType == RoadType.LONE_TRAIL;
            final boolean earthworks = roadType.allowsEarthworks();

            // Scale maxDy proportionally when using an adaptive (larger) step,
            // so the grade limit stays the same regardless of step size.
            final int configStep = Math.max(2, GRID_STEP);
            final int effectiveMaxDy = (step == configStep)
                    ? configMaxDy
                    : (int) Math.ceil((double) configMaxDy * (double) step / (double) configStep);

            // LONE_TRAIL uses a higher slope multiplier to make the trail wind around hills.
            final double slopeMultiplier = (isLoneTrail)
                    ? Math.max(1.0, UltreiaConfig.ROAD_HIERARCHY.loneTrailSlopePenaltyMultiplier)
                    : 1.0;

            // Magnetism: cache radii once per call to avoid per-neighbor lookups when disabled.
            final boolean magnetismActive = UltreiaConfig.ROAD_HIERARCHY.magnetismEnabled;
            final int magRadius = magnetismActive ? Math.max(4, UltreiaConfig.ROAD_HIERARCHY.magnetismRadius) : 0;

            // Bridge-endpoint magnetism: pulls the search toward a known existing bridge crossing's
            // entrance from a distance, so buildExistingBridgeMoves' validated single-cell snap
            // (bug A fix) actually has a real adjacent node to trigger from instead of drifting past it.
            final int bridgeReuseRadius = Math.max(0, BridgeMoveGenerator.BRIDGE_REUSE_SEARCH_RADIUS);
            final double bridgeMagnetismBonus = Math.max(0.0, BRIDGE_ENDPOINT_MAGNETISM_BONUS);
            boolean bridgeMagnetismActive = UltreiaConfig.BRIDGES.enabled
                    && roadNetwork != null && bridgeReuseRadius > 0 && bridgeMagnetismBonus > 0.0;
            List<RoadNetwork.BridgeCrossing> nearbyCrossings = null;
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
                double slopeCost = (double) Math.min(dy, effectiveMaxDy) * UltreiaConfig.PATHFINDING.slopePenalty * slopeMultiplier;
                double roughCost = (double) sw.roughness * SURFACE_ROUGHNESS_PENALTY;
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
                tentative += turnCost(cur.dir, dirIdx, step, UltreiaConfig.PATHFINDING.turnPenalty);

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
                            tentative -= Math.max(0.0, UltreiaConfig.ROAD_HIERARCHY.magnetismAttractionBonus) * frac;
                        } else if (distCurrent < magRadius && distNeighbor > distCurrent) {
                            // Moving away from road while inside the attraction zone → repulsion.
                            double frac = 1.0 - (double) distCurrent / scale;
                            tentative += Math.max(0.0, UltreiaConfig.ROAD_HIERARCHY.magnetismRepulsionPenalty) * frac;
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

            int lookahead = Math.max(1, MOUNTAIN_ASCENT_LOOKAHEAD);
            int[] dir = NEIGHBOR_DIRS[dirIdx];

            int wallMinHeight = Math.max(1, ENCLOSED_WALL_MIN_HEIGHT);
            int uphillMinRise = Math.max(1, UPHILL_LOOKAHEAD_MIN_RISE);
            int allowedCut = Math.max(0, PREDICTED_MAX_CUT_DEPTH);

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
                boolean sWater = TerrainSampler.lightIsWater(lv);
                boolean sOcean = TerrainSampler.lightIsOcean(lv);
                if (sWater || sOcean) break;
                int sSurfaceY = TerrainSampler.lightSurfaceY(lv);
                int hardnessCat = TerrainSampler.lightHardness(lv);
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
                    ? Math.max(1.0, HARD_ROCK_CUT_MULTIPLIER)
                    : 1.0;

            // Open hillside cuts (terrain only on one side = natural embankment/talus) look realistic
            // and are much cheaper than enclosed trench cuts (terrain on both sides = saw-effect through a ridge).
            // Scale base cut penalty 0.3x when fully open, linearly up to 1.0x when fully enclosed.
            double enclosedFraction = (enclosedAhead > 0 && lookahead > 0)
                    ? Math.min(1.0, (double) enclosedAhead / (double) lookahead)
                    : 0.0;
            double openHillsideFactor = 0.3 + 0.7 * enclosedFraction;
            penalty += (double) sumPredictedCut * Math.max(0.0, PREDICTED_CUT_PENALTY_PER_BLOCK) * 0.75 * hardnessMult * openHillsideFactor;

            if (maxPredictedCut > allowedCut) {
                double extra = (double) (maxPredictedCut - allowedCut);
                penalty += extra * extra * 120.0;
            }

            if (enclosedAhead > 0) {
                penalty += Math.max(0.0, ENCLOSED_CUT_PENALTY) * 0.5;
                penalty += (double) enclosedAhead * 26.0;
                penalty += (double) Math.max(0, longestEnclosedRun - 1)
                        * Math.max(0.0, SUSTAINED_ENCLOSED_CUT_PENALTY) * 0.6;
                penalty += (double) maxWallMin * 10.0;
            }

            boolean sustainedDirectAscent = nextY > curY && maxRiseAhead >= uphillMinRise;
            boolean sustainedDirectDescent = nextY < curY && maxDropAhead >= uphillMinRise;

            if ((sustainedDirectAscent || sustainedDirectDescent) && enclosedAhead > 0) {
                penalty += Math.max(0.0, MOUNTAIN_ASCENT_PENALTY);

                int verticalCommit = Math.max(maxRiseAhead, maxDropAhead);
                penalty += (double) verticalCommit * 8.0;
            }

            return penalty;
        }

        private int[] lateralWallHeights(int x, int z, int roadY, int dirDx, int dirDz) {
            int r = Math.max(1, ENCLOSED_CUT_SCAN_RADIUS);

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
                        && !TerrainSampler.lightIsWater(lv)
                        && !TerrainSampler.lightIsOcean(lv)) {
                    int sy = TerrainSampler.lightSurfaceY(lv);
                    leftWall = Math.max(leftWall, sy - roadY);
                }

                int rx = (int) Math.round((double) x - px * (double) off);
                int rz = (int) Math.round((double) z - pz * (double) off);
                int rv = sampler.sampleLightIfLoaded(rx, rz);
                if (rv != TerrainSampler.LIGHT_NOT_LOADED
                        && !TerrainSampler.lightIsWater(rv)
                        && !TerrainSampler.lightIsOcean(rv)) {
                    int sy = TerrainSampler.lightSurfaceY(rv);
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

        private void expandBridgeMoves(Node cur, long ck, TerrainSampler.Sample cs) {
            if (!UltreiaConfig.BRIDGES.enabled) return;

            int minSpacing = Math.max(0, BridgeMoveGenerator.MIN_DISTANCE_BETWEEN_BRIDGES_BLOCKS);
            int distFromLastBridge = distSinceBridge.get(ck);
            if (distFromLastBridge < minSpacing) return;

            final double bridgeMult = BridgeMoveCost.newBridgeMultiplier(roadType);

            // Offer existing bridge crossings as cheap moves before probing new ones.
            // This prevents building parallel bridges when one already exists nearby.
            List<BridgeMove> moves = new ArrayList<>(
                    bridgeMoves.existingBridgeMoves(roadNetwork, cur.x, cur.z, cur.dir, cs, gx, gz));

            // Only probe new bridges near water or valley edges — avoids stadium chunk loading
            if (bridgeMoves.isNearWaterOrValleyEdge(cur.x, cur.z, cs)) {
                int inDx = 0;
                int inDz = 0;
                boolean hasIncoming = false;
                long parentK = cameFrom.get(ck);
                if (cur.dir != DIR_NONE) {
                    int[] d = NEIGHBOR_DIRS[cur.dir];
                    inDx = d[0];
                    inDz = d[1];
                    hasIncoming = true;
                } else if (parentK != Long.MIN_VALUE) {
                    inDx = Integer.signum(cur.x - stateX(parentK));
                    inDz = Integer.signum(cur.z - stateZ(parentK));
                    hasIncoming = (inDx != 0 || inDz != 0);
                }
                moves.addAll(bridgeMoves.newBridgeMoves(cur.x, cur.z, cur.dir, inDx, inDz, hasIncoming, cs, roadType));
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

                // Reusing a built bridge builds nothing: the lone-trail multiplier only deters new bridges.
                double moveMult = mv.reusedCrossing != null ? 1.0 : bridgeMult;
                double tentative = gScore.get(ck) + mv.cost * moveMult + mv.turnCost
                        + sampler.extraCost(ns, TerrainSampler.Mode.PATHFINDING);
                if (tentative < gScore.get(nk)) {
                    cameFrom.put(nk, ck);
                    if (mv.reusedCrossing != null) {
                        cameBridge.remove(nk);
                        cameReusedBridge.put(nk, mv.reusedCrossing);
                        if (UltreiaConfig.DEBUG.logBridges) {
                            Ultreia.LOGGER.info(
                                    "[Ultreia Bridge] reuse transition taken: seg={} ({},{}) -> ({},{})",
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

            int minClearance = Math.max(0, BridgeClearanceCheck.UNDER_BRIDGE_ROAD_MIN_CLEARANCE_BLOCKS);
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

                int[] deckHalf = BridgeDeckProfile.of(span);

                for (int ci = startGrace; ci < span.centerline.size(); ci++) {
                    BlockPos bridgePoint = span.centerline.get(ci);
                    int deckSurfaceY = HalfY.surfaceBlockY(deckHalf[Math.min(ci, deckHalf.length - 1)]);

                    for (int k = 0; k <= priorEnd; k++) {
                        BlockPos roadPoint = path.get(k);
                        if (Math.abs(bridgePoint.getX() - roadPoint.getX()) > xzRadius) continue;
                        if (Math.abs(bridgePoint.getZ() - roadPoint.getZ()) > xzRadius) continue;

                        int freeBlocks = deckSurfaceY - roadPoint.getY() - 1;
                        if (freeBlocks < minClearance) {
                            if (UltreiaConfig.DEBUG.logBridges) {
                                Ultreia.LOGGER.info(
                                        "[Ultreia Bridge] self-overlap bridge[{}]: deck Y={} at ({},{}) over approach road Y={} at ({},{}), clearance={} < {}",
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
                result = new PathResult(Collections.<BlockPos>emptyList(), Collections.<BridgeSpan>emptyList());
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

                RoadNetwork.BridgeCrossing reused = cameReusedBridge.get(curK);
                if (reused != null) {
                    appendReusedBridgeWaypoints(out, reused);
                    continue;
                }

                BridgeSpan span = cameBridge.get(curK);
                if (span != null) {
                    spans.add(span);
                    bridgePathStartIndices.add(out.isEmpty() ? 0 : out.size() - 1);

                    int[] deckHalf = BridgeDeckProfile.of(span);

                    if (!out.isEmpty() && span.centerline != null && !span.centerline.isEmpty()) {
                        BlockPos firstBridge = span.centerline.get(0);
                        int firstDeckY = HalfY.walkBlockYFloor(deckHalf[0]);
                        BlockPos prevOut = out.get(out.size() - 1);

                        if (prevOut.getX() == firstBridge.getX() && prevOut.getZ() == firstBridge.getZ()) {
                            out.set(out.size() - 1, new BlockPos(firstBridge.getX(), firstDeckY, firstBridge.getZ()));
                        }
                    }

                    for (int j = 1; j < span.centerline.size(); j++) {
                        BlockPos p = span.centerline.get(j);
                        int deckY = HalfY.walkBlockYFloor(deckHalf[j]);

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
                            result = new PathResult(Collections.<BlockPos>emptyList(), Collections.<BridgeSpan>emptyList());
                            return;
                        }
                    }

                    if (out.isEmpty() || out.get(out.size() - 1).getX() != cx || out.get(out.size() - 1).getZ() != cz) {
                        out.add(new BlockPos(cx, s.surfaceY, cz));
                    }
                }
            }

            result = new PathResult(out, spans);
        }

        /**
         * Splices the exact persisted geometry of a reused bridge into the reconstructed coarse path,
         * instead of the single ground-level point the naive "no span" branch would otherwise emit.
         * Not added to {@code spans}/{@code forcedBridges}: RoadPainter must never rebuild this bridge —
         * it treats these points as ordinary coarse waypoints, and its existing roadIndex.isBridgeXZ /
         * isBridgeVolumeXZ checks already skip painting, earthworks and tree-clearing on them since they
         * were marked in the persistent index when the bridge was first built.
         */
        private void appendReusedBridgeWaypoints(ArrayList<BlockPos> out, RoadNetwork.BridgeCrossing crossing) {
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

        private static int[] nearestValidGrid(int x0, int z0, int step, TerrainView sampler) {
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

        private static boolean hasLocalExitFreedom(int x, int z, int step, TerrainView sampler) {
            return countLocalFreeNeighbors(x, z, step, sampler) >= 2;
        }

        private static int countLocalFreeNeighbors(int x, int z, int step, TerrainView sampler) {
            int free = 0;

            for (int[] dir : NEIGHBOR_DIRS) {
                int nx = x + dir[0] * step;
                int nz = z + dir[1] * step;

                if (isOpenCell(nx, nz, sampler)) free++;
            }

            return free;
        }

        public PathResult getResult() {
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
            List<BlockPos> line = LineRaster.supercover(x0, z0, x1, z1, 0);
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
                        pSurfaceY = TerrainSampler.lightSurfaceY(lv);
                        pIsWater  = TerrainSampler.lightIsWater(lv);
                        pIsOcean  = TerrainSampler.lightIsOcean(lv);
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
                        terrainPenalty += (double) predictedFill * Math.max(0.0, PREDICTED_FILL_PENALTY_PER_BLOCK);
                    }

                    if (predictedCut > 0) {
                        terrainPenalty += (double) predictedCut * Math.max(0.0, PREDICTED_CUT_PENALTY_PER_BLOCK);

                        // Only probe lateral walls when terrain actually needs cutting.
                        int wallMinHeight = Math.max(1, ENCLOSED_WALL_MIN_HEIGHT);
                        int[] walls = lateralWallHeights(p.getX(), p.getZ(), roadY, x1 - x0, z1 - z0);

                        boolean enclosed = walls[0] >= wallMinHeight && walls[1] >= wallMinHeight;
                        if (enclosed) {
                            int wallMin = Math.min(walls[0], walls[1]);
                            enclosedCells++;
                            currentEnclosedRun++;
                            if (currentEnclosedRun > longestEnclosedRun) longestEnclosedRun = currentEnclosedRun;
                            terrainPenalty += Math.max(0.0, ENCLOSED_CUT_PENALTY);
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
                terrainPenalty += (double) (longestEnclosedRun - 1) * Math.max(0.0, SUSTAINED_ENCLOSED_CUT_PENALTY);
            }

            int maxRough = Math.max(0, MAX_SEGMENT_ROUGHNESS);
            int maxPredictedCutAllowed = Math.max(0, PREDICTED_MAX_CUT_DEPTH);

            // Extra tolerance applied only during pathfinding — lets A* find routes through steeper terrain
            // without changing what the painter actually does. Painter always uses its own earthworks limits.
            int pfTolerance = Math.max(0, PATHFINDING_EARTHWORKS_TOLERANCE);

            // Scale roughness limit proportionally with adaptive step (more raster points = higher budget).
            int baseStep = Math.max(2, GRID_STEP);
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

            int startHalf = HalfY.ofBlockTop(first.surfaceY);
            int endHalf = HalfY.ofBlockTop(last.surfaceY);

            boolean earthworks = roadType.allowsEarthworks();
            int cutHalf = earthworks ? Math.max(0, UltreiaConfig.PAINTING.earthworksMaxCut + pfTolerance) * 2 : 0;
            int fillHalf = earthworks ? Math.max(0, UltreiaConfig.PAINTING.earthworksMaxFill + pfTolerance) * 2 : 0;
            int maxStepHalf = Math.max(1, HalfY.MAX_ROAD_STEP_HALF_PER_BLOCK);

            boolean gradeFeasible = RoadHeightSolver.isLineFeasible(
                    line,
                    sampler,
                    startHalf,
                    endHalf,
                    earthworks,
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
            if (SWITCHBACK_STAGNATION_BOOST && hasAltitudeProgressTowardGoal()) {
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
            if (SWITCHBACK_STAGNATION_BOOST) {
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
            if (!UltreiaConfig.DEBUG.logBridges) return;

            if (bridgeDebugPrinted < BRIDGE_DEBUG_LIMIT) {
                Ultreia.LOGGER.info("[Ultreia bridge-debug] {}", msg);
                bridgeDebugPrinted++;
            } else {
                bridgeDebugSuppressed++;
            }
        }

        private void flushBridgeDebugSummary() {
            if (!UltreiaConfig.DEBUG.logBridges) return;
            if (bridgeDebugSuppressed > 0) {
                Ultreia.LOGGER.info("[Ultreia bridge-debug] suppressed {} additional bridge debug lines", bridgeDebugSuppressed);
                bridgeDebugSuppressed = 0;
            }
        }

        private static double nearestCrossingStartDist(int x, int z, List<RoadNetwork.BridgeCrossing> crossings) {
            double best = Double.POSITIVE_INFINITY;
            for (RoadNetwork.BridgeCrossing c : crossings) {
                double d = Math.sqrt(Pos2D.distSq(x, z, c.startX, c.startZ));
                if (d < best) best = d;
            }
            return best;
        }
    }

    public Session startSession(WorldServer world, BlockPos start, BlockPos goal, TerrainView sampler) {
        return new Session(world, start, goal, sampler);
    }

    private static double heuristic(int x, int z, int gx, int gz) {
        return Math.sqrt(Pos2D.distSq(x, z, gx, gz));
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

    public static String debugEndpoint(WorldServer world,
                                       TerrainView sampler,
                                       ProtectedAreas protectedAreas,
                                       RoadIndex roadIndex,
                                       BlockPos pos) {
        if (pos == null) return "endpoint=null";

        TerrainSampler.Sample s = sampler.sample(pos.getX(), pos.getZ());
        if (s == null) return "endpoint=(" + pos.getX() + "," + pos.getZ() + ") sample=null";

        int free1 = countFreeNeighbors(pos.getX(), pos.getZ(), Math.max(2, GRID_STEP), sampler, 1);
        int free2 = countFreeNeighbors(pos.getX(), pos.getZ(), Math.max(2, GRID_STEP), sampler, 2);
        int free3 = countFreeNeighbors(pos.getX(), pos.getZ(), Math.max(2, GRID_STEP), sampler, 3);

        int blocked1 = countBlockedNeighbors(pos.getX(), pos.getZ(), Math.max(2, GRID_STEP), sampler, 1);
        int blocked2 = countBlockedNeighbors(pos.getX(), pos.getZ(), Math.max(2, GRID_STEP), sampler, 2);

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

    /** Dry, allowed, unprotected land a road could pass through. */
    private static boolean isOpenCell(int x, int z, TerrainView sampler) {
        TerrainSampler.Sample s = sampler.sample(x, z);
        if (s == null) return false;
        if (sampler.forbidden(s, TerrainSampler.Mode.PATHFINDING)) return false;
        if (s.isWater || s.isOcean) return false;
        return !sampler.isProtectedNoPaint(x, z);
    }

    private static int countFreeNeighbors(int x, int z, int step, TerrainView sampler, int ring) {
        int free = 0;

        for (int dz = -ring; dz <= ring; dz++) {
            for (int dx = -ring; dx <= ring; dx++) {
                if (dx == 0 && dz == 0) continue;
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;

                int xx = x + dx * step;
                int zz = z + dz * step;

                if (isOpenCell(xx, zz, sampler)) free++;
            }
        }

        return free;
    }

    private static int countBlockedNeighbors(int x, int z, int step, TerrainView sampler, int ring) {
        int blocked = 0;

        for (int dz = -ring; dz <= ring; dz++) {
            for (int dx = -ring; dx <= ring; dx++) {
                if (dx == 0 && dz == 0) continue;
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;

                int xx = x + dx * step;
                int zz = z + dz * step;

                if (!isOpenCell(xx, zz, sampler)) blocked++;
            }
        }

        return blocked;
    }

}
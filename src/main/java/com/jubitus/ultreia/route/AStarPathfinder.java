package com.jubitus.ultreia.route;

import com.jubitus.ultreia.bridge.BridgeClearanceCheck;
import com.jubitus.ultreia.bridge.BridgeDeckProfile;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.geom.LineRaster;
import com.jubitus.ultreia.terrain.TerrainView;
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
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.jubitus.ultreia.route.GridMoves.DIR_NONE;
import static com.jubitus.ultreia.route.GridMoves.GRID_STEP;
import static com.jubitus.ultreia.route.GridMoves.countFreeExits;
import static com.jubitus.ultreia.route.GridMoves.isOpenCell;
import static com.jubitus.ultreia.route.GridMoves.nearestValidCell;
import static com.jubitus.ultreia.route.GridMoves.scaleUpToStep;
import static com.jubitus.ultreia.route.SearchState.stateKey;
import static com.jubitus.ultreia.route.SearchState.stateX;
import static com.jubitus.ultreia.route.SearchState.stateZ;
import static com.jubitus.ultreia.route.GridMoves.NEIGHBOR_DIRS;
import static com.jubitus.ultreia.route.GridMoves.snap;
import static com.jubitus.ultreia.route.GridMoves.turnCost;

public final class AStarPathfinder {

    // Wall-clock timeout in seconds per path.
    private static final int MAX_WALL_TIME_SECONDS = 320;
    // Max vertical delta allowed between neighbor grid nodes.
    public static final int MAX_YDELTA_PER_STEP = 2;
    // Extra cost per unit of local terrain roughness along a segment.
    private static final double SURFACE_ROUGHNESS_PENALTY = 3.0;
    // Hard cap for cumulative roughness along one neighbor segment.
    private static final int MAX_SEGMENT_ROUGHNESS = 4;
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
    // Penalty per block of the lower wall in an enclosed cut cell of the move itself.
    private static final double ENCLOSED_WALL_PENALTY_PER_BLOCK = 8.0;
    // Forward lookahead used to discourage direct enclosed mountain ascent.
    private static final int MOUNTAIN_ASCENT_LOOKAHEAD = 4;
    // Penalty for continuing into a sustained enclosed uphill push.
    private static final double MOUNTAIN_ASCENT_PENALTY = 50.0;
    // Penalty per block of sustained rise or drop ahead while the look-ahead is enclosed.
    private static final double VERTICAL_COMMIT_PENALTY_PER_BLOCK = 8.0;
    // Cut cost multiplier applied when the terrain being cut is hard rock (stone, granite, etc.).
    private static final double HARD_ROCK_CUT_MULTIPLIER = 1.5;
    // Light-sample hardness category from which the surface counts as hard rock (2 = stone).
    private static final int HARD_ROCK_CATEGORY = 2;
    // Look-ahead weights: cut cost, enclosed entry, per enclosed cell, sustained run, per block of wall.
    private static final double LOOKAHEAD_CUT_WEIGHT = 0.75;
    private static final double LOOKAHEAD_ENCLOSED_WEIGHT = 0.5;
    private static final double LOOKAHEAD_ENCLOSED_CELL_PENALTY = 26.0;
    private static final double LOOKAHEAD_SUSTAINED_WEIGHT = 0.6;
    private static final double LOOKAHEAD_WALL_PENALTY_PER_BLOCK = 10.0;
    // Cost per squared block of look-ahead cut deeper than PREDICTED_MAX_CUT_DEPTH.
    private static final double LOOKAHEAD_OVER_CUT_PENALTY = 120.0;
    // Look-ahead cut cost factor with terrain on one side only; rises linearly to 1 when fully enclosed.
    private static final double OPEN_HILLSIDE_CUT_FACTOR = 0.3;
    // Cost per squared block of height step beyond the grade limit.
    private static final double OVER_GRADE_PENALTY = 30.0;
    // Slack over the roughness and cut-depth limits before a move is rejected.
    private static final int ROUGHNESS_SLACK = 2;
    private static final int CUT_DEPTH_SLACK = 1;
    // A move with an enclosed run this long that also cuts beyond the limit is rejected.
    private static final int SEVERE_ENCLOSED_MIN_RUN = 2;
    // Maximum number of full-path profile rejections before A* gives up.
    private static final int PROFILE_REJECTION_LIMIT = 300;
    // Extra earthworks tolerance (blocks) added at pathfinding time only, on top of earthworksMaxCut/Fill.
    private static final int PATHFINDING_EARTHWORKS_TOLERANCE = 4;
    // Cost discount applied to normal A* moves that step closer to a known existing bridge crossing's entrance,...
    private static final double BRIDGE_ENDPOINT_MAGNETISM_BONUS = 12.0;
    // Road distance scan step of road magnetism.
    private static final int MAGNETISM_SCAN_STEP = 2;
    // Road-snap only triggers this many grid steps away from the start.
    private static final double SNAP_MIN_DIST_STEPS = 6.0;
    // Weight of the remaining distance in the score that picks the partial-path end point.
    private static final double BEST_APPROX_DISTANCE_WEIGHT = 0.25;
    // A partial path must get this much closer to the goal: blocks, or fraction of the start distance.
    private static final double PARTIAL_MIN_GAIN_BLOCKS = 64.0;
    private static final double PARTIAL_MIN_GAIN_FRACTION = 0.18;
    // Progress that resets the stagnation counter: grid steps, or fraction of the start distance.
    private static final double STAGNATION_MIN_GAIN_STEPS = 3.0;
    private static final double STAGNATION_MIN_GAIN_FRACTION = 0.03;
    // Stagnation abort threshold (expansions) by route length (blocks); the last threshold is for longer routes.
    private static final int[] STAGNATION_ROUTE_LENGTHS = {256, 512, 1024};
    private static final int[] STAGNATION_THRESHOLDS = {6000, 9000, 14000, 22000};
    // Stagnation threshold bounds: a floor, and a fraction (1/divisor) of the iteration budget.
    private static final int STAGNATION_MIN_THRESHOLD = 4000;
    private static final int STAGNATION_MAX_IT_DIVISOR = 12;
    // Steep routes multiply the stagnation threshold to leave room for switchbacks.
    private static final int STEEP_ROUTE_MIN_DY = 12;
    private static final int STEEP_ROUTE_DY_PER_BOOST = 16;
    private static final int STEEP_ROUTE_MAX_BOOST = 4;
    private static final int STEEP_ROUTE_MAX_IT_DIVISOR = 4;
    // The frontier still counts as promising when this many grid steps closer than the best point.
    private static final int FRONTIER_PROMISE_STEPS = 2;
    // Climbing or descending this fraction of the height difference counts as progress (switchbacks).
    private static final int ALTITUDE_PROGRESS_MIN_DY = 8;
    private static final double ALTITUDE_PROGRESS_FRACTION = 0.20;
    private static final double SCORE_EPSILON = 0.001;
    private static final int RECONSTRUCT_GUARD = 600_000;
    private static final long ABSURD_JUMP_BLOCKS = 2048L;
    private static final long PROGRESS_LOG_INTERVAL_NS = 10_000_000_000L;
    public enum StepResult { RUNNING, FOUND, PARTIAL, FAILED }

    public static final class Session {
        private int sx, sz;

        private final TerrainView sampler;
        private final int step;
        private final SearchState st = new SearchState();
        private SearchBounds bounds;
        private BridgeMoveGenerator bridgeMoves;
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

        Session(BlockPos start, BlockPos goal, TerrainView sampler) {
            this.sampler = sampler;

            this.step = GridMoves.stepFor(start, goal);

            int sx0 = snap(start.getX(), step);
            int sz0 = snap(start.getZ(), step);
            int gx0 = snap(goal.getX(), step);
            int gz0 = snap(goal.getZ(), step);

            int[] sFix = nearestValidCell(sx0, sz0, step, sampler);
            int[] gFix = nearestValidCell(gx0, gz0, step, sampler);

            if (sFix == null) {
                fail("start forbidden (no valid grid point near snapped start)");
                return;
            }
            if (gFix == null) {
                fail("goal forbidden (no valid grid point near snapped goal)");
                return;
            }

            this.sx = sFix[0];
            this.sz = sFix[1];
            this.gx = gFix[0];
            this.gz = gFix[1];

            if (sampler.isProtectedNoPaint(this.sx, this.sz) || sampler.isProtectedNoPaint(this.gx, this.gz)) {
                fail("start/goal snapped inside protected no-paint zone");
                return;
            }

            if (countFreeExits(this.sx, this.sz, step, sampler) <= 0) {
                fail("start snapped into blocked pocket");
                return;
            }
            if (countFreeExits(this.gx, this.gz, step, sampler) <= 0) {
                fail("goal snapped into blocked pocket");
                return;
            }

            TerrainSampler.Sample ss = sampler.sample(this.sx, this.sz);
            TerrainSampler.Sample gs = sampler.sample(this.gx, this.gz);
            this.startY = ss.surfaceY;
            this.goalY = gs.surfaceY;

            this.bounds = new SearchBounds(sx, sz, gx, gz, Math.abs(gs.surfaceY - ss.surfaceY), step);
            this.bridgeMoves = new BridgeMoveGenerator(sampler, step, bounds, this::debugBridge);

            if (sampler.forbidden(ss, TerrainSampler.Mode.PATHFINDING)) {
                fail("start forbidden: " + sampler.describeForbidden(ss, TerrainSampler.Mode.PATHFINDING));
                return;
            }
            if (sampler.forbidden(gs, TerrainSampler.Mode.PATHFINDING)) {
                fail("goal forbidden: " + sampler.describeForbidden(gs, TerrainSampler.Mode.PATHFINDING));
                return;
            }

            long startState = stateKey(sx, sz, DIR_NONE);
            double h0 = heuristic(sx, sz, gx, gz); // primary goal used until destGates is set
            bestApproxState = startState;
            bestApproxHeuristic = h0;
            bestApproxScore = h0;
            lastProgressHeuristic = h0;
            startHeuristic = h0;

            st.addStart(sx, sz, h0);
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
            // Not right at the start, to avoid immediate self-termination.
            double minDist = step * SNAP_MIN_DIST_STEPS;
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
                if (nowNs - sessionStartNs > (long) MAX_WALL_TIME_SECONDS * 1_000_000_000L) {
                    return failOrPartial("wall-clock timeout (" + MAX_WALL_TIME_SECONDS + "s)");
                }

                // Periodic progress log so the user can see A* is still alive.
                if (UltreiaConfig.DEBUG.logPathfinding
                        && nowNs - lastProgressLogNs > PROGRESS_LOG_INTERVAL_NS) {
                    lastProgressLogNs = nowNs;
                    long elapsedSec = (nowNs - sessionStartNs) / 1_000_000_000L;
                    Ultreia.LOGGER.info(
                            "[Ultreia A*] still running: it={} closed={} open={} best_dist={} elapsed={}s",
                            st.iterations, st.closed.size(), st.open.size(),
                            Double.isFinite(bestApproxHeuristic) ? (int) bestApproxHeuristic : -1,
                            elapsedSec);
                }

                while (!st.open.isEmpty() && st.iterations < bounds.maxIterations && System.nanoTime() < deadlineNs) {
                    st.iterations++;
                    SearchState.Node cur = st.open.poll();
                    long ck = stateKey(cur.x, cur.z, cur.dir);

                    if (!st.closed.add(ck)) continue;

                    updateBestApprox(ck, cur.x, cur.z);

                    if (Pos2D.distSq(cur.x, cur.z, gx, gz) <= (double) step * (double) step) {
                        beginReconstruction(ck);
                        break;
                    }

                    // Multi-gate termination: also stop when reaching any destination gate.
                    if (destGates != null && destGates.isAtGate(cur.x, cur.z, step)) {
                        reachedGate = destGates.nearestGate(cur.x, cur.z);
                        beginReconstruction(ck);
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
                            beginReconstruction(ck);
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
                    if (st.open.isEmpty()) return failOrPartial("open exhausted");
                    if (shouldAbortForNoProgress()) return failOrPartial("no meaningful progress toward goal");
                    if (st.iterations >= bounds.maxIterations) return failOrPartial("max iterations reached");
                    return StepResult.RUNNING;
                }
            }

            while (reconstructing && System.nanoTime() < deadlineNs) {
                if (recCur == Long.MIN_VALUE) break;

                revStates.add(recCur);

                long prev = st.cameFrom.get(recCur);
                recCur = prev == Long.MIN_VALUE ? Long.MIN_VALUE : prev;

                recGuard++;
                if (recGuard > RECONSTRUCT_GUARD) {
                    reconstructing = false;
                    return fail("reconstruct guard hit (cycle?)");
                }

                if (recCur == Long.MIN_VALUE) {
                    reconstructing = false;
                    Collections.reverse(revStates);
                    buildResultFromReconstruction();
                    if (failed) return fail(failReason);

                    PathResult rr = result;
                    if (rr == null || rr.getPath() == null || rr.getPath().isEmpty()) {
                        return fail("reconstruction produced empty path");
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

                        st.closed.add(badState);
                        result = new PathResult(
                                Collections.<BlockPos>emptyList(),
                                Collections.<BridgeSpan>emptyList()
                        );

                        // If the partial fallback itself fails profile, bestApproxState hasn't
                        // changed (search never ran since reconstructing=true the whole time),
                        // so calling beginPartialReconstruction again would loop forever silently.
                        if (wasPartial) {
                            return fail("partial fallback also failed profile feasibility (" + profileRejectionCount + " total rejections)");
                        }

                        // Guard: after too many full-path rejections, try a partial fallback.
                        // Note: beginPartialReconstruction must only be called here (not from
                        // inside a partial reconstruction) or it would loop silently.
                        if (profileRejectionCount >= PROFILE_REJECTION_LIMIT) {
                            return failOrPartial("non-feasible road profile after " + profileRejectionCount + " attempts");
                        }

                        // Return RUNNING without calling beginPartialReconstruction here.
                        // The next advance() enters !reconstructing, fires the progress log,
                        // and handles "open exhausted" through the normal path.
                        return StepResult.RUNNING;
                    }

                    flushBridgeDebugSummary();
                    return partialResult ? StepResult.PARTIAL : StepResult.FOUND;
                }
            }

            return StepResult.RUNNING;
        }

        private StepResult fail(String reason) {
            failed = true;
            failReason = reason;
            flushBridgeDebugSummary();
            return StepResult.FAILED;
        }

        private StepResult failOrPartial(String reason) {
            if (beginPartialReconstruction(reason)) return StepResult.RUNNING;
            return fail(reason);
        }

        private void beginReconstruction(long state) {
            foundState = state;
            reconstructing = true;
            recCur = state;
            recGuard = 0;
            partialResult = false;
            partialReason = null;
        }

        private void updateBestApprox(long state, int x, int z) {
            double h = heuristicToGoal(x, z);
            double g = st.gScore.get(state);

            if (state == Long.MIN_VALUE) return;
            if (!Double.isFinite(h) || !Double.isFinite(g)) return;

            double score = g + h * BEST_APPROX_DISTANCE_WEIGHT;

            if (bestApproxState == Long.MIN_VALUE) {
                bestApproxState = state;
                bestApproxHeuristic = h;
                bestApproxScore = score;
                return;
            }

            if (h + SCORE_EPSILON < bestApproxHeuristic) {
                bestApproxState = state;
                bestApproxHeuristic = h;
                bestApproxScore = score;
                return;
            }

            if (Math.abs(h - bestApproxHeuristic) <= (double) step) {
                if (score + SCORE_EPSILON < bestApproxScore) {
                    bestApproxState = state;
                    bestApproxHeuristic = h;
                    bestApproxScore = score;
                }
            }
        }

        private boolean beginPartialReconstruction(String reason) {
            if (!UltreiaConfig.PATHFINDING.allowPartialPathFallback) return false;
            if (!hasUsefulPartialFallback()) return false;

            beginReconstruction(bestApproxState);
            partialResult = true;
            partialReason = reason;
            revStates.clear();

            return true;
        }

        private boolean hasUsefulPartialFallback() {
            if (bestApproxState == Long.MIN_VALUE) return false;

            int bestX = stateX(bestApproxState);
            int bestZ = stateZ(bestApproxState);

            double startH = heuristicToGoal(sx, sz);
            double gain = startH - bestApproxHeuristic;

            if (bestX == sx && bestZ == sz) {
                return false;
            }

            return gain >= Math.max(PARTIAL_MIN_GAIN_BLOCKS, startH * PARTIAL_MIN_GAIN_FRACTION);
        }

        public String partialSummary() {
            if (!partialResult) return "";
            int bestX = bestApproxState == Long.MIN_VALUE ? sx : stateX(bestApproxState);
            int bestZ = bestApproxState == Long.MIN_VALUE ? sz : stateZ(bestApproxState);
            int dist = (int) Math.round(heuristic(bestX, bestZ, gx, gz));
            return "partial=true reason=" + partialReason
                    + " best=(" + bestX + "," + bestZ + ")"
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

        private void expandNormalMoves(SearchState.Node cur, long ck, TerrainSampler.Sample cs) {
            final boolean earthworks = roadType.allowsEarthworks();

            // The grade limit stays the same whatever the step size.
            final int effectiveMaxDy = scaleUpToStep(MAX_YDELTA_PER_STEP, step);

            // LONE_TRAIL uses a higher slope multiplier to make the trail wind around hills.
            final double slopeMultiplier = (roadType == RoadType.LONE_TRAIL)
                    ? Math.max(1.0, UltreiaConfig.ROAD_HIERARCHY.loneTrailSlopePenaltyMultiplier)
                    : 1.0;

            // Magnetism: cache radii once per call to avoid per-neighbor lookups when disabled.
            final boolean magnetismActive = UltreiaConfig.ROAD_HIERARCHY.magnetismEnabled;
            final int magRadius = magnetismActive ? Math.max(4, UltreiaConfig.ROAD_HIERARCHY.magnetismRadius) : 0;

            // Bridge-endpoint magnetism: pulls the search toward a known existing bridge crossing's
            // entrance from a distance, so buildExistingBridgeMoves' validated single-cell snap
            // (bug A fix) actually has a real adjacent node to trigger from instead of drifting past it.
            final int bridgeReuseRadius = BridgeMoveGenerator.BRIDGE_REUSE_SEARCH_RADIUS;
            boolean bridgeMagnetismActive = UltreiaConfig.BRIDGES.enabled && roadNetwork != null;
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

                if (!bounds.contains(nx, nz)) continue;

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
                    slopeCost += extra * extra * OVER_GRADE_PENALTY;
                } else if (!earthworks && dy > effectiveMaxDy) {
                    skipSlope++;
                    continue;
                }

                long nk = stateKey(nx, nz, dirIdx);
                if (st.closed.contains(nk)) continue;

                double tentative = st.gScore.get(ck)
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
                if (magnetismActive) {
                    int distNeighbor = sampler.nearestRoadDistManhattan(nx, nz, magRadius, MAGNETISM_SCAN_STEP);
                    if (distNeighbor < magRadius) {
                        int distCurrent = sampler.nearestRoadDistManhattan(cur.x, cur.z, magRadius, MAGNETISM_SCAN_STEP);
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
                        tentative -= BRIDGE_ENDPOINT_MAGNETISM_BONUS * frac;
                    }
                }

                if (tentative < st.gScore.get(nk)) {
                    st.relaxRoad(ck, nk, nx, nz, dirIdx, tentative, tentative + heuristicToGoal(nx, nz), base);
                }
            }
        }

        private double mountainTraversePenalty(int x, int z, int dirIdx, int curY, int nextY) {
            int lookahead = MOUNTAIN_ASCENT_LOOKAHEAD;
            int[] dir = NEIGHBOR_DIRS[dirIdx];

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

                if (!bounds.contains(cx, cz)) break;

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

                    boolean enclosed = walls[0] >= ENCLOSED_WALL_MIN_HEIGHT && walls[1] >= ENCLOSED_WALL_MIN_HEIGHT;
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
            double hardnessMult = (maxHardnessCat >= HARD_ROCK_CATEGORY) ? HARD_ROCK_CUT_MULTIPLIER : 1.0;

            // Open hillside cuts (terrain only on one side = natural embankment/talus) look realistic
            // and are much cheaper than enclosed trench cuts (terrain on both sides = saw-effect through a ridge).
            double enclosedFraction = (enclosedAhead > 0)
                    ? Math.min(1.0, (double) enclosedAhead / (double) lookahead)
                    : 0.0;
            double openHillsideFactor = OPEN_HILLSIDE_CUT_FACTOR + (1.0 - OPEN_HILLSIDE_CUT_FACTOR) * enclosedFraction;
            penalty += (double) sumPredictedCut * PREDICTED_CUT_PENALTY_PER_BLOCK * LOOKAHEAD_CUT_WEIGHT * hardnessMult * openHillsideFactor;

            if (maxPredictedCut > PREDICTED_MAX_CUT_DEPTH) {
                double extra = (double) (maxPredictedCut - PREDICTED_MAX_CUT_DEPTH);
                penalty += extra * extra * LOOKAHEAD_OVER_CUT_PENALTY;
            }

            if (enclosedAhead > 0) {
                penalty += ENCLOSED_CUT_PENALTY * LOOKAHEAD_ENCLOSED_WEIGHT;
                penalty += (double) enclosedAhead * LOOKAHEAD_ENCLOSED_CELL_PENALTY;
                penalty += (double) Math.max(0, longestEnclosedRun - 1)
                        * SUSTAINED_ENCLOSED_CUT_PENALTY * LOOKAHEAD_SUSTAINED_WEIGHT;
                penalty += (double) maxWallMin * LOOKAHEAD_WALL_PENALTY_PER_BLOCK;
            }

            boolean sustainedDirectAscent = nextY > curY && maxRiseAhead >= UPHILL_LOOKAHEAD_MIN_RISE;
            boolean sustainedDirectDescent = nextY < curY && maxDropAhead >= UPHILL_LOOKAHEAD_MIN_RISE;

            if ((sustainedDirectAscent || sustainedDirectDescent) && enclosedAhead > 0) {
                penalty += MOUNTAIN_ASCENT_PENALTY;

                int verticalCommit = Math.max(maxRiseAhead, maxDropAhead);
                penalty += (double) verticalCommit * VERTICAL_COMMIT_PENALTY_PER_BLOCK;
            }

            return penalty;
        }

        private int[] lateralWallHeights(int x, int z, int roadY, int dirDx, int dirDz) {
            double len = Math.sqrt((double) dirDx * (double) dirDx + (double) dirDz * (double) dirDz);
            if (len <= 0.0) return new int[]{0, 0};

            double px = -((double) dirDz) / len;
            double pz = ((double) dirDx) / len;

            int leftWall = 0;
            int rightWall = 0;

            for (int off = 1; off <= ENCLOSED_CUT_SCAN_RADIUS; off++) {
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
                                      double segStartX, double segStartZ,
                                      double segEndX, double segEndZ) {
            double dx = segEndX - segStartX;
            double dz = segEndZ - segStartZ;
            double lenSq = dx * dx + dz * dz;

            if (lenSq <= 1.0e-9) return 0.0;

            double t = ((px - segStartX) * dx + (pz - segStartZ) * dz) / lenSq;
            if (t < 0.0) return 0.0;
            if (t > 1.0) return 1.0;
            return t;
        }

        private void expandBridgeMoves(SearchState.Node cur, long ck, TerrainSampler.Sample cs) {
            if (!UltreiaConfig.BRIDGES.enabled) return;

            int distFromLastBridge = st.distSinceBridge.get(ck);
            if (distFromLastBridge < BridgeMoveGenerator.MIN_DISTANCE_BETWEEN_BRIDGES_BLOCKS) return;

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
                long parentK = st.cameFrom.get(ck);
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
                if (!bounds.contains(mv.nx, mv.nz)) continue;

                long nk = stateKey(mv.nx, mv.nz, mv.ndir);
                if (st.closed.contains(nk)) continue;

                TerrainSampler.Sample ns = sampler.sample(mv.nx, mv.nz);
                if (sampler.forbidden(ns, TerrainSampler.Mode.PATHFINDING)) {
                    bumpForbidden(ns);
                    continue;
                }

                // Reusing a built bridge builds nothing: the lone-trail multiplier only deters new bridges.
                double moveMult = mv.reusedCrossing != null ? 1.0 : bridgeMult;
                double tentative = st.gScore.get(ck) + mv.cost * moveMult + mv.turnCost
                        + sampler.extraCost(ns, TerrainSampler.Mode.PATHFINDING);
                if (tentative < st.gScore.get(nk)) {
                    double f = tentative + heuristicToGoal(mv.nx, mv.nz);
                    if (mv.reusedCrossing != null) {
                        st.relaxReusedBridge(ck, nk, mv.nx, mv.nz, mv.ndir, tentative, f, mv.reusedCrossing);
                        if (UltreiaConfig.DEBUG.logBridges) {
                            Ultreia.LOGGER.info(
                                    "[Ultreia Bridge] reuse transition taken: seg={} ({},{}) -> ({},{})",
                                    mv.reusedCrossing.segmentId, cur.x, cur.z, mv.nx, mv.nz);
                        }
                    } else {
                        st.relaxNewBridge(ck, nk, mv.nx, mv.nz, mv.ndir, tentative, f, mv.span);
                    }
                }
            }
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

            int minClearance = BridgeClearanceCheck.UNDER_BRIDGE_ROAD_MIN_CLEARANCE_BLOCKS;

            // Skip the launch-ramp zone so the bridge connection point never triggers a false reject.
            final int startGrace = step;
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

                RoadNetwork.BridgeCrossing reused = st.cameReusedBridge.get(curK);
                if (reused != null) {
                    appendReusedBridgeWaypoints(out, reused);
                    continue;
                }

                BridgeSpan span = st.cameBridge.get(curK);
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

                        if (manhattan > ABSURD_JUMP_BLOCKS) {
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

        public PathResult getResult() {
            return result;
        }

        public String failureSummary(boolean detailed) {
            if (!detailed) return failReason;
            return failReason
                    + " it=" + st.iterations
                    + " closed=" + st.closed.size()
                    + " open=" + st.open.size()
                    + " rejects{ocean=" + skipOcean
                    + ", blacklisted=" + skipBlacklist
                    + ", blocked=" + skipBlocked
                    + ", slope=" + skipSlope
                    + ", bridge=" + skipBridge + "}";
        }

        private static final class SegmentWalkability {
            static final SegmentWalkability TRIVIAL = new SegmentWalkability(true, false, 0, 0.0);
            static final SegmentWalkability WATER = new SegmentWalkability(false, true, Integer.MAX_VALUE, 0.0);

            final boolean walkable;
            final boolean crossesWater;
            final int roughness;
            final double terrainPenalty;

            SegmentWalkability(boolean walkable, boolean crossesWater, int roughness, double terrainPenalty) {
                this.walkable = walkable;
                this.crossesWater = crossesWater;
                this.roughness = roughness;
                this.terrainPenalty = terrainPenalty;
            }
        }

        private SegmentWalkability segmentWalkability(int x0, int z0, int x1, int z1) {
            List<BlockPos> line = LineRaster.supercover(x0, z0, x1, z1, 0);
            if (line == null || line.size() < 2) {
                return SegmentWalkability.TRIVIAL;
            }

            TerrainSampler.Sample first = sampler.sample(line.get(0).getX(), line.get(0).getZ());
            TerrainSampler.Sample last = sampler.sample(line.get(line.size() - 1).getX(), line.get(line.size() - 1).getZ());
            if (first == null || last == null) {
                return SegmentWalkability.WATER;
            }

            int prevY = first.surfaceY;
            int roughness = 0;

            double terrainPenalty = 0.0;
            int maxPredictedCut = 0;
            int currentEnclosedRun = 0;
            int longestEnclosedRun = 0;

            double segStartX = (double) x0 + 0.5;
            double segStartZ = (double) z0 + 0.5;
            double segEndX = (double) x1 + 0.5;
            double segEndZ = (double) z1 + 0.5;

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
                        if (fs == null) return SegmentWalkability.WATER;
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
                    if (s == null) return SegmentWalkability.WATER;
                    pSurfaceY = s.surfaceY;
                    pIsWater  = s.isWater;
                    pIsOcean  = s.isOcean;
                }

                if (pIsOcean || pIsWater) {
                    return SegmentWalkability.WATER;
                }

                int d = Math.abs(pSurfaceY - prevY);
                if (d > 1) roughness += (d - 1);
                prevY = pSurfaceY;

                if (isInterior) {
                    double ppx = (double) p.getX() + 0.5;
                    double ppz = (double) p.getZ() + 0.5;
                    double t = segmentParam01(ppx, ppz, segStartX, segStartZ, segEndX, segEndZ);

                    int roadY = (int) Math.round((1.0 - t) * (double) first.surfaceY + t * (double) last.surfaceY);
                    int predictedCut = Math.max(0, pSurfaceY - roadY);
                    int predictedFill = Math.max(0, roadY - pSurfaceY);

                    if (predictedCut > maxPredictedCut) maxPredictedCut = predictedCut;

                    if (predictedFill > 0) {
                        terrainPenalty += (double) predictedFill * PREDICTED_FILL_PENALTY_PER_BLOCK;
                    }

                    if (predictedCut > 0) {
                        terrainPenalty += (double) predictedCut * PREDICTED_CUT_PENALTY_PER_BLOCK;

                        // Only probe lateral walls when terrain actually needs cutting.
                        int[] walls = lateralWallHeights(p.getX(), p.getZ(), roadY, x1 - x0, z1 - z0);

                        boolean enclosed = walls[0] >= ENCLOSED_WALL_MIN_HEIGHT && walls[1] >= ENCLOSED_WALL_MIN_HEIGHT;
                        if (enclosed) {
                            int wallMin = Math.min(walls[0], walls[1]);
                            currentEnclosedRun++;
                            if (currentEnclosedRun > longestEnclosedRun) longestEnclosedRun = currentEnclosedRun;
                            terrainPenalty += ENCLOSED_CUT_PENALTY;
                            terrainPenalty += (double) wallMin * ENCLOSED_WALL_PENALTY_PER_BLOCK;
                        } else {
                            currentEnclosedRun = 0;
                        }
                    } else {
                        currentEnclosedRun = 0;
                    }
                }
            }

            if (longestEnclosedRun > 1) {
                terrainPenalty += (double) (longestEnclosedRun - 1) * SUSTAINED_ENCLOSED_CUT_PENALTY;
            }

            // Extra tolerance applied only during pathfinding — lets A* find routes through steeper terrain
            // without changing what the painter actually does. Painter always uses its own earthworks limits.
            int pfTolerance = PATHFINDING_EARTHWORKS_TOLERANCE;

            // More raster points on a larger step: the roughness budget grows with it.
            int scaledMaxRough = scaleUpToStep(MAX_SEGMENT_ROUGHNESS, step);

            boolean severeEnclosedCut = longestEnclosedRun >= SEVERE_ENCLOSED_MIN_RUN
                    && maxPredictedCut > PREDICTED_MAX_CUT_DEPTH + pfTolerance;

            boolean coarseTerrainOk = roughness <= (scaledMaxRough + ROUGHNESS_SLACK)
                    && maxPredictedCut <= (PREDICTED_MAX_CUT_DEPTH + CUT_DEPTH_SLACK + pfTolerance)
                    && !severeEnclosedCut;

            if (!coarseTerrainOk) {
                return new SegmentWalkability(false, false, roughness, terrainPenalty);
            }

            int startHalf = HalfY.ofBlockTop(first.surfaceY);
            int endHalf = HalfY.ofBlockTop(last.surfaceY);

            boolean earthworks = roadType.allowsEarthworks();
            int cutHalf = earthworks ? Math.max(0, UltreiaConfig.PAINTING.earthworksMaxCut + pfTolerance) * 2 : 0;
            int fillHalf = earthworks ? Math.max(0, UltreiaConfig.PAINTING.earthworksMaxFill + pfTolerance) * 2 : 0;

            boolean gradeFeasible = RoadHeightSolver.isLineFeasible(
                    line,
                    sampler,
                    startHalf,
                    endHalf,
                    earthworks,
                    cutHalf,
                    fillHalf,
                    HalfY.MAX_ROAD_STEP_HALF_PER_BLOCK
            );

            return new SegmentWalkability(gradeFeasible, false, roughness, terrainPenalty);
        }

        private void bumpForbidden(TerrainSampler.Sample s) {
            TerrainSampler.ForbiddenReason r = sampler.forbiddenReason(s, TerrainSampler.Mode.PATHFINDING);
            if (r == TerrainSampler.ForbiddenReason.OCEAN) skipOcean++;
            else if (r == TerrainSampler.ForbiddenReason.BLACKLIST) skipBlacklist++;
            else if (r == TerrainSampler.ForbiddenReason.WATER) skipBridge++;
            else skipBlocked++;
        }

        public String debugStartArea() {
            return debugAreaAround(sx, sz, "start");
        }

        public String debugGoalArea() {
            return debugAreaAround(gx, gz, "goal");
        }

        private String debugAreaAround(int x, int z, String label) {
            int free1 = AStarPathfinder.countFreeNeighbors(x, z, step, sampler, 1);
            int free2 = AStarPathfinder.countFreeNeighbors(x, z, step, sampler, 2);
            int free3 = AStarPathfinder.countFreeNeighbors(x, z, step, sampler, 3);

            TerrainSampler.Sample s = sampler.sample(x, z);
            TerrainSampler.ForbiddenReason fr = sampler.forbiddenReason(s, TerrainSampler.Mode.PATHFINDING);

            BlockPos near = sampler.findNearestExistingRoad(x, z, 16);
            int roadNear = near == null ? -1 : Math.abs(near.getX() - x) + Math.abs(near.getZ() - z);

            return label
                    + "=(" + x + "," + z + ")"
                    + " surfY=" + (s != null ? s.surfaceY : -1)
                    + " forbidden=" + fr
                    + " protected=" + sampler.isProtectedNoPaint(x, z)
                    + " roadNear=" + roadNear
                    + " free{r1=" + free1 + ",r2=" + free2 + ",r3=" + free3 + "}"
                    + " blocked{r1=" + (ringSize(1) - free1) + ",r2=" + (ringSize(2) - free2) + ",r3=" + (ringSize(3) - free3) + "}"
                    + " closed=" + st.closed.size()
                    + " open=" + st.open.size();
        }

        private boolean shouldAbortForNoProgress() {
            if (bestApproxState == Long.MIN_VALUE) return false;
            if (!Double.isFinite(bestApproxHeuristic) || !Double.isFinite(startHeuristic)) return false;

            double minMeaningfulGain = Math.max((double) step * STAGNATION_MIN_GAIN_STEPS, startHeuristic * STAGNATION_MIN_GAIN_FRACTION);
            boolean improved = (lastProgressHeuristic - bestApproxHeuristic) >= minMeaningfulGain;

            if (improved) {
                lastProgressHeuristic = bestApproxHeuristic;
                stagnationSteps = 0;
                return false;
            }

            // Altitude progress: A* may be executing a switchback (lateral traversal with no XZ gain).
            if (hasAltitudeProgressTowardGoal()) {
                stagnationSteps = 0;
                return false;
            }

            stagnationSteps++;

            int routeLen = (int) Math.round(startHeuristic);

            int threshold = STAGNATION_THRESHOLDS[STAGNATION_ROUTE_LENGTHS.length];
            for (int i = 0; i < STAGNATION_ROUTE_LENGTHS.length; i++) {
                if (routeLen <= STAGNATION_ROUTE_LENGTHS[i]) {
                    threshold = STAGNATION_THRESHOLDS[i];
                    break;
                }
            }

            threshold = Math.min(threshold, Math.max(STAGNATION_MIN_THRESHOLD, bounds.maxIterations / STAGNATION_MAX_IT_DIVISOR));

            // On steep mountain routes, switchbacks require more lateral exploration before making XZ progress.
            int totalDY = Math.abs(goalY - startY);
            if (totalDY >= STEEP_ROUTE_MIN_DY) {
                int boostFactor = Math.min(STEEP_ROUTE_MAX_BOOST, 1 + totalDY / STEEP_ROUTE_DY_PER_BOOST);
                threshold = Math.min(threshold * boostFactor, bounds.maxIterations / STEEP_ROUTE_MAX_IT_DIVISOR);
            }

            // Only called with a non-empty open set.
            SearchState.Node bestOpen = st.open.peek();
            double bestOpenH = heuristicToGoal(bestOpen.x, bestOpen.z);

            boolean frontierStillPromising = bestOpenH + (double) (step * FRONTIER_PROMISE_STEPS) < bestApproxHeuristic;
            if (frontierStillPromising) {
                return false;
            }

            return stagnationSteps >= threshold;
        }

        private boolean hasAltitudeProgressTowardGoal() {
            int totalDY = Math.abs(goalY - startY);
            if (totalDY < ALTITUDE_PROGRESS_MIN_DY) return false;
            if (bestApproxState == Long.MIN_VALUE) return false;

            int bestX = stateX(bestApproxState);
            int bestZ = stateZ(bestApproxState);
            TerrainSampler.Sample bs = sampler.sampleIfLoaded(bestX, bestZ);
            if (bs == null) return false;

            int progressY = Math.abs(bs.surfaceY - startY);
            return (double) progressY / (double) totalDY >= ALTITUDE_PROGRESS_FRACTION;
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

    public Session startSession(BlockPos start, BlockPos goal, TerrainView sampler) {
        return new Session(start, goal, sampler);
    }

    private static double heuristic(int x, int z, int gx, int gz) {
        return Math.sqrt(Pos2D.distSq(x, z, gx, gz));
    }


    public static String debugEndpoint(TerrainView sampler,
                                       ProtectedAreas protectedAreas,
                                       RoadIndex roadIndex,
                                       BlockPos pos) {
        if (pos == null) return "endpoint=null";

        TerrainSampler.Sample s = sampler.sample(pos.getX(), pos.getZ());
        if (s == null) return "endpoint=(" + pos.getX() + "," + pos.getZ() + ") sample=null";

        int free1 = countFreeNeighbors(pos.getX(), pos.getZ(), GRID_STEP, sampler, 1);
        int free2 = countFreeNeighbors(pos.getX(), pos.getZ(), GRID_STEP, sampler, 2);
        int free3 = countFreeNeighbors(pos.getX(), pos.getZ(), GRID_STEP, sampler, 3);

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
                + " blocked{r1=" + (ringSize(1) - free1) + ",r2=" + (ringSize(2) - free2) + "}";
    }

    /** Number of cells on the square ring at Chebyshev distance {@code ring}. */
    private static int ringSize(int ring) {
        return 8 * ring;
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

}

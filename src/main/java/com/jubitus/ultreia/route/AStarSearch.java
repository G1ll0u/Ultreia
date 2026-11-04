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
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
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

/**
 * One A* search between two points, run in slices within the tick budget: grid moves with their terrain cost,
 * bridge moves, moves along the road network, partial fallback, then path reconstruction and the whole-path check.
 */
public final class AStarSearch {

    // Wall-clock timeout in seconds per path.
    private static final int MAX_WALL_TIME_SECONDS = 320;
    // Maximum number of full-path profile rejections before A* gives up.
    private static final int PROFILE_REJECTION_LIMIT = 300;
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
    // How far back along the path (blocks) a move is checked for closing a steep hairpin.
    private static final int HAIRPIN_LOOKBACK_BLOCKS = 32;
    public enum StepResult { RUNNING, FOUND, PARTIAL, FAILED }

    private int sx, sz;

    private final TerrainView sampler;
    private final int step;
    private final SearchState st = new SearchState();
    private SearchBounds bounds;
    private BridgeMoveGenerator bridgeMoves;
    private TerrainMoveCost moveCost;
    private NetworkMoves networkMoves;
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
    private int profileRejectionCount = 0;

    private boolean failed = false;
    private String failReason = "unknown";

    // Non-null while the path of a reached (or partial) end node is being rebuilt.
    @javax.annotation.Nullable private PathReconstructor reconstructor;

    private PathResult result = new PathResult(Collections.<BlockPos>emptyList(), Collections.<BridgeSpan>emptyList());

    private final AStarDiagnostics diag = new AStarDiagnostics();
    private final ReuseTrace reuseTrace = new ReuseTrace();

    // Recent path of the node being expanded (itself first, then its ancestors): x, z, ground y, blocks along from it.
    private final IntArrayList recentX = new IntArrayList();
    private final IntArrayList recentZ = new IntArrayList();
    private final IntArrayList recentY = new IntArrayList();
    private final DoubleArrayList recentAlong = new DoubleArrayList();

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


    public AStarSearch(BlockPos start, BlockPos goal, TerrainView sampler) {
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
        this.bridgeMoves = new BridgeMoveGenerator(sampler, step, bounds, diag::bridge);
        this.moveCost = new TerrainMoveCost(sampler, step, bounds);
        this.networkMoves = new NetworkMoves(sampler, step, bounds);

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

        if (reconstructor == null) {
            long nowNs = System.nanoTime();

            // Wall-clock timeout guard — prevents infinite-seeming hangs on impossible terrain.
            if (nowNs - sessionStartNs > (long) MAX_WALL_TIME_SECONDS * 1_000_000_000L) {
                return failOrPartial("wall-clock timeout (" + MAX_WALL_TIME_SECONDS + "s)");
            }

            diag.logProgress(nowNs, sessionStartNs, st, bestApproxHeuristic);

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
                    diag.countForbidden(sampler, cs);
                    continue;
                }

                expandNormalMoves(cur, ck, cs);
                expandBridgeMoves(cur, ck, cs);
                expandNetworkMoves(cur, ck, cs);
            }

            if (reconstructor == null) {
                if (st.open.isEmpty()) return failOrPartial("open exhausted");
                if (shouldAbortForNoProgress()) return failOrPartial("no meaningful progress toward goal");
                if (st.iterations >= bounds.maxIterations) return failOrPartial("max iterations reached");
                return StepResult.RUNNING;
            }
        }

        PathReconstructor.Status rs = reconstructor.advance(deadlineNs);
        if (rs == PathReconstructor.Status.RUNNING) return StepResult.RUNNING;

        PathReconstructor rec = reconstructor;
        reconstructor = null;
        if (rs == PathReconstructor.Status.FAILED) return fail(rec.failReason());

        PathResult rr = rec.result();
        if (rr.isEmpty()) return fail("reconstruction produced empty path");

        if (!PathProfileCheck.isAcceptable(rr.getPath(), rr.getBridges(), rec.bridgeStartIndices(),
                rr.getFollowedXZ(), sampler, step)) {
            profileRejectionCount++;
            if (rec.followedCount() > 0) reuseTrace.countRejectedFollowing();
            st.closed.add(rec.endState);

            // If the partial fallback itself fails profile, bestApproxState hasn't
            // changed (search never ran while the partial path was rebuilt),
            // so calling beginPartialReconstruction again would loop forever silently.
            if (partialResult) {
                return fail("partial fallback also failed profile feasibility (" + profileRejectionCount + " total rejections)");
            }

            // Guard: after too many full-path rejections, try a partial fallback.
            // Note: beginPartialReconstruction must only be called here (not from
            // inside a partial reconstruction) or it would loop silently.
            if (profileRejectionCount >= PROFILE_REJECTION_LIMIT) {
                return failOrPartial("non-feasible road profile after " + profileRejectionCount + " attempts");
            }

            // Return RUNNING without calling beginPartialReconstruction here.
            // The next advance() resumes the search, fires the progress log,
            // and handles "open exhausted" through the normal path.
            return StepResult.RUNNING;
        }

        result = rr;
        AStarDiagnostics.logHairpins(rr.getPath(), roadType);
        AStarDiagnostics.logCostBreakdown(rec.states(), st, sampler, moveCost, roadType, step, sx, sz,
                stateX(rec.endState), stateZ(rec.endState));
        reuseTrace.logFoundPath(st, rec.states(), sampler, roadType, roadNetwork, step);
        flushBridgeLog();
        return partialResult ? StepResult.PARTIAL : StepResult.FOUND;
    }

    private StepResult fail(String reason) {
        failed = true;
        failReason = reason;
        flushBridgeLog();
        return StepResult.FAILED;
    }

    private void flushBridgeLog() {
        diag.flushBridge();
    }

    private StepResult failOrPartial(String reason) {
        if (beginPartialReconstruction(reason)) return StepResult.RUNNING;
        return fail(reason);
    }

    private void beginReconstruction(long state) {
        reconstructor = new PathReconstructor(st, sampler, state);
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

    // Open-set order: the weighted remaining distance makes the search head for the goal instead of
    // exploring the whole corridor, at the price of a slightly costlier road.
    private double priority(double g, int x, int z) {
        return g + UltreiaConfig.PATHFINDING.searchSpeedFactor * heuristicToGoal(x, z);
    }

    private void expandNormalMoves(SearchState.Node cur, long ck, TerrainSampler.Sample cs) {
        collectRecentPath(ck, cur, cs);

        for (int dirIdx = 0; dirIdx < NEIGHBOR_DIRS.length; dirIdx++) {
            int[] dir = NEIGHBOR_DIRS[dirIdx];
            int dx = dir[0] * step;
            int dz = dir[1] * step;

            int nx = cur.x + dx;
            int nz = cur.z + dz;

            if (!bounds.contains(nx, nz)) continue;

            TerrainSampler.Sample ns = sampler.sample(nx, nz);
            if (sampler.forbidden(ns, TerrainSampler.Mode.PATHFINDING)) {
                diag.countForbidden(sampler, ns);
                continue;
            }

            TerrainMoveCost.MoveCost mc = moveCost.evaluate(cur.x, cur.z, cs, dirIdx, nx, nz, ns, roadType);
            if (mc.verdict == TerrainMoveCost.Verdict.WATER) {
                diag.countWaterMove();
                continue;
            }
            if (mc.verdict == TerrainMoveCost.Verdict.TOO_STEEP) {
                diag.countTooSteep();
                continue;
            }

            double base = Math.sqrt((double) dx * (double) dx + (double) dz * (double) dz);

            if (closesSteepHairpin(nx, nz, ns.surfaceY, base)) {
                diag.countSteepHairpin();
                continue;
            }

            long nk = stateKey(nx, nz, dirIdx);
            if (st.closed.contains(nk)) continue;

            double tentative = st.gScore.get(ck)
                    + base
                    + mc.slope
                    + mc.roughness
                    + mc.cut
                    + mc.ahead
                    + sampler.extraCost(ns, TerrainSampler.Mode.PATHFINDING);

            // Direction-change penalty: produces smooth natural roads and prevents zigzagging on flat terrain.
            // Same formula used for entering/exiting a bridge (see turnCost) — one turn-cost model for
            // every kind of move, so a bridge never gets to attach at an angle a road turn wouldn't.
            tentative += turnCost(cur.dir, dirIdx, step, UltreiaConfig.PATHFINDING.turnPenalty);

            if (tentative < st.gScore.get(nk)) {
                st.relaxRoad(ck, nk, nx, nz, dirIdx, tentative, priority(tentative, nx, nz), base);
            }
        }
    }

    /** Fills the recent-path buffers from the expanded node back along its road links (stops at a bridge). */
    private void collectRecentPath(long ck, SearchState.Node cur, TerrainSampler.Sample cs) {
        recentX.clear();
        recentZ.clear();
        recentY.clear();
        recentAlong.clear();
        recentX.add(cur.x);
        recentZ.add(cur.z);
        recentY.add(cs.surfaceY);
        recentAlong.add(0.0);

        long k = ck;
        int x = cur.x, z = cur.z;
        double along = 0.0;
        while (along < HAIRPIN_LOOKBACK_BLOCKS
                && !st.cameBridge.containsKey(k) && !st.cameFollow.containsKey(k)) {
            long pk = st.cameFrom.get(k);
            if (pk == Long.MIN_VALUE) break;
            int px = stateX(pk), pz = stateZ(pk);
            along += Math.sqrt((double) (px - x) * (px - x) + (double) (pz - z) * (pz - z));
            recentX.add(px);
            recentZ.add(pz);
            recentY.add(sampler.sample(px, pz).surfaceY);
            recentAlong.add(along);
            k = pk;
            x = px;
            z = pz;
        }
    }

    /**
     * True when stepping to (nx, nz) brings the road back beside its own recent path with a height gap
     * the painter cannot make walkable (a steep hairpin). {@code stepLength} is the move's own length.
     */
    private boolean closesSteepHairpin(int nx, int nz, int ny, double stepLength) {
        int width = roadType.roadWidthBlocks();
        double contactSq = width * TerrainMoveCost.HAIRPIN_CONTACT_WIDTHS;
        contactSq *= contactSq;
        double minPathGap = width * TerrainMoveCost.HAIRPIN_MIN_PATH_WIDTHS;

        for (int i = 0; i < recentX.size(); i++) {
            if (stepLength + recentAlong.getDouble(i) < minPathGap) continue;
            double ddx = nx - recentX.getInt(i);
            double ddz = nz - recentZ.getInt(i);
            double dSq = ddx * ddx + ddz * ddz;
            if (dSq > contactSq) continue;
            if (TerrainMoveCost.isSteepHairpinGap(Math.abs(ny - recentY.getInt(i)), Math.sqrt(dSq))) return true;
        }
        return false;
    }

    private void expandBridgeMoves(SearchState.Node cur, long ck, TerrainSampler.Sample cs) {
        if (!UltreiaConfig.BRIDGES.enabled) return;

        int distFromLastBridge = st.distSinceBridge.get(ck);
        if (distFromLastBridge < BridgeMoveGenerator.MIN_DISTANCE_BETWEEN_BRIDGES_BLOCKS) return;

        final double bridgeMult = BridgeMoveCost.newBridgeMultiplier(roadType);

        List<BridgeMove> moves = Collections.emptyList();
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
            moves = bridgeMoves.newBridgeMoves(cur.x, cur.z, cur.dir, inDx, inDz, hasIncoming, cs, roadType);
        }

        for (BridgeMove mv : moves) {
            if (!bounds.contains(mv.nx, mv.nz)) continue;

            long nk = stateKey(mv.nx, mv.nz, mv.ndir);
            if (st.closed.contains(nk)) continue;

            TerrainSampler.Sample ns = sampler.sample(mv.nx, mv.nz);
            if (sampler.forbidden(ns, TerrainSampler.Mode.PATHFINDING)) {
                diag.countForbidden(sampler, ns);
                continue;
            }

            double tentative = st.gScore.get(ck) + mv.cost * bridgeMult + mv.turnCost
                    + sampler.extraCost(ns, TerrainSampler.Mode.PATHFINDING);
            if (tentative < st.gScore.get(nk)) {
                st.relaxNewBridge(ck, nk, mv.nx, mv.nz, mv.ndir, tentative, priority(tentative, mv.nx, mv.nz), mv.span);
            }
        }
    }

    /** Joins an existing road beside the node and follows it to an exit point; no terrain cost at the landing. */
    private void expandNetworkMoves(SearchState.Node cur, long ck, TerrainSampler.Sample cs) {
        for (NetworkMoves.Move mv : networkMoves.from(roadNetwork, cur.x, cur.z, cur.dir, cs.surfaceY, roadType)) {
            long nk = stateKey(mv.nx, mv.nz, mv.ndir);
            if (st.closed.contains(nk)) continue;

            double g = st.gScore.get(ck);
            double tentative = g + mv.hop + mv.follow + mv.turn;
            boolean better = tentative < st.gScore.get(nk);
            reuseTrace.offer(mv, cur.x, cur.z, g, tentative, nk, better);
            if (better) st.relaxFollow(ck, nk, tentative, priority(tentative, mv.nx, mv.nz), mv);
        }
    }

    public PathResult getResult() {
        return result;
    }

    public String failureSummary(boolean detailed) {
        return diag.failureSummary(failReason, st, detailed);
    }

    public String debugStartArea(@javax.annotation.Nullable ProtectedAreas protectedAreas) {
        return AStarDiagnostics.describeCell("start", sampler, protectedAreas, sx, sz, step);
    }

    public String debugGoalArea(@javax.annotation.Nullable ProtectedAreas protectedAreas) {
        return AStarDiagnostics.describeCell("goal", sampler, protectedAreas, gx, gz, step);
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

    private static double heuristic(int x, int z, int gx, int gz) {
        return Math.sqrt(Pos2D.distSq(x, z, gx, gz));
    }
}

package com.jubitus.ultreia.route;

import com.jubitus.ultreia.bridge.BridgeMoveGenerator;

import static com.jubitus.ultreia.route.GridMoves.GRID_STEP;

/** Where one A* search may go (a corridor around the start-goal segment, inside a bounding box) and its iteration budget. */
final class SearchBounds implements BridgeMoveGenerator.SearchArea {

    // Max iterations for A* expansions per edge.
    private static final int MAX_ITERATIONS = 10000000;
    // On the coarser grid the iteration budget grows with the step, up to this factor.
    private static final int ADAPTIVE_MAX_ITERATIONS_FACTOR = 2;
    // Extra margin around the start-goal bounding box (blocks).
    private static final int SEARCH_MARGIN = 230;
    // Bounding-box margin gained per block of height difference, and its cap.
    private static final int MARGIN_PER_CLIMB_BLOCK = 6;
    private static final int MAX_CLIMB_MARGIN = 2048;
    // Maximum half-width of the A* search corridor (blocks).
    private static final int CORRIDOR_HALF_WIDTH_MAX = 256;
    // Corridor half-width gained per block of height difference between start and goal.
    private static final int CORRIDOR_PER_CLIMB_BLOCK = 8;
    // Long routes get routeLength / divisor as corridor half-width, within these bounds.
    private static final int LONG_ROUTE_CORRIDOR_DIVISOR = 3;
    private static final int LONG_ROUTE_CORRIDOR_MIN = 128;
    private static final int LONG_ROUTE_CORRIDOR_MAX = 768;
    // Smallest corridor half-width on the coarser grid.
    private static final int ADAPTIVE_CORRIDOR_MIN = 128;

    final int maxIterations;

    private final int startX, startZ;
    private final long segDx, segDz;
    private final long segLenSq;
    private final int corridorHalfWidth;
    private final int minX, maxX, minZ, maxZ;

    SearchBounds(int startX, int startZ, int goalX, int goalZ, int dyGoal, int step) {
        this.startX = startX;
        this.startZ = startZ;
        this.segDx = (long) goalX - (long) startX;
        this.segDz = (long) goalZ - (long) startZ;
        this.segLenSq = segDx * segDx + segDz * segDz;

        int corridorBase = SEARCH_MARGIN / 2;
        // climbBoost scales up to the corridor cap so the corridor covers full switchback room.
        int climbBoost = Math.min(CORRIDOR_HALF_WIDTH_MAX, dyGoal * CORRIDOR_PER_CLIMB_BLOCK);

        int routeLen = (int) Math.round(Math.sqrt((double) segLenSq));
        int longRouteAllowance = Math.min(LONG_ROUTE_CORRIDOR_MAX,
                Math.max(LONG_ROUTE_CORRIDOR_MIN, routeLen / LONG_ROUTE_CORRIDOR_DIVISOR));

        int halfWidth = Math.min(Math.max(corridorBase + climbBoost, longRouteAllowance), CORRIDOR_HALF_WIDTH_MAX);

        // When using an adaptive larger step, tighten the corridor so the total
        // state count stays within the iteration budget.
        if (step > GRID_STEP) {
            int scaledCorridor = (int) Math.round((double) halfWidth * (double) GRID_STEP / (double) step);
            halfWidth = Math.max(ADAPTIVE_CORRIDOR_MIN, scaledCorridor);
        }
        this.corridorHalfWidth = halfWidth;

        int margin = SEARCH_MARGIN + Math.min(MAX_CLIMB_MARGIN, dyGoal * MARGIN_PER_CLIMB_BLOCK);
        this.minX = Math.min(startX, goalX) - margin;
        this.maxX = Math.max(startX, goalX) + margin;
        this.minZ = Math.min(startZ, goalZ) - margin;
        this.maxZ = Math.max(startZ, goalZ) + margin;

        // For adaptive step: increase iteration budget proportionally to step ratio,
        // since each iteration covers more ground and the search finishes faster per cell.
        this.maxIterations = (step == GRID_STEP)
                ? MAX_ITERATIONS
                : (int) Math.min((long) MAX_ITERATIONS * ADAPTIVE_MAX_ITERATIONS_FACTOR,
                                 (long) MAX_ITERATIONS * (long) step / (long) GRID_STEP);
    }

    @Override
    public boolean contains(int x, int z) {
        if (!inCorridor(x, z)) return false;
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    private boolean inCorridor(int x, int z) {
        if (segLenSq <= 0) return true;

        long apx = (long) x - (long) startX;
        long apz = (long) z - (long) startZ;

        double t = (double) (apx * segDx + apz * segDz) / (double) segLenSq;
        if (t < 0.0) t = 0.0;
        else if (t > 1.0) t = 1.0;

        double cx = (double) startX + t * (double) segDx;
        double cz = (double) startZ + t * (double) segDz;

        double dx = (double) x - cx;
        double dz = (double) z - cz;

        double d2 = dx * dx + dz * dz;
        double r = (double) corridorHalfWidth;

        return d2 <= r * r;
    }
}

package com.jubitus.ultreia.route;

import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.terrain.TerrainView;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/** The A* move grid: step size, the 16 move directions, grid snapping and endpoint placement, turn cost. */
public final class GridMoves {

    // Grid step in blocks.
    static final int GRID_STEP = 2;
    // Routes longer than this (blocks) use a coarser grid step, up to MAX_ADAPTIVE_STEP.
    private static final int LONG_ROUTE_DIST = 1200;
    private static final int LONG_ROUTE_STEP_INCREASE = 2;
    private static final int MAX_ADAPTIVE_STEP = 10;
    // Endpoint snap: search radius (blocks, or grid steps) and score bonuses (squared blocks).
    private static final int ENDPOINT_SNAP_MIN_RADIUS = 24;
    private static final int ENDPOINT_SNAP_RADIUS_STEPS = 8;
    private static final double ENDPOINT_ON_ROAD_BONUS = 64.0;
    private static final double ENDPOINT_FREE_EXIT_BONUS = 6.0;
    private static final int MIN_FREE_EXITS = 2;

    public static final int[][] NEIGHBOR_DIRS = new int[][]{
            { 1, 0}, {-1, 0}, {0, 1}, {0, -1},
            { 1, 1}, { 1,-1}, {-1, 1}, {-1,-1},
            { 2, 1}, { 2,-1}, {-2, 1}, {-2,-1},
            { 1, 2}, { 1,-2}, {-1, 2}, {-1,-2}
    };

    /** Direction index of a node with no incoming move (the start). */
    public static final int DIR_NONE = 16;

    private GridMoves() {}

    public static int snap(int v, int step) {
        return Pos2D.snapDown(v, step);
    }

    public static int nearestDirIndex(int dx, int dz) {
        if (dx == 0 && dz == 0) return 0;

        int best = 0;
        double bestDot = -Double.MAX_VALUE;

        for (int i = 0; i < NEIGHBOR_DIRS.length; i++) {
            int[] d = NEIGHBOR_DIRS[i];
            double dot = normalizedDot(dx, dz, d[0], d[1]);
            if (dot > bestDot) {
                bestDot = dot;
                best = i;
            }
        }

        return best;
    }

    public static double normalizedDot(int ax, int az, int bx, int bz) {
        double al = Math.sqrt((double) ax * (double) ax + (double) az * (double) az);
        double bl = Math.sqrt((double) bx * (double) bx + (double) bz * (double) bz);
        if (al <= 0.0 || bl <= 0.0) return 1.0;
        return ((double) ax * (double) bx + (double) az * (double) bz) / (al * bl);
    }

    /**
     * Single turn-cost model shared by every kind of move — plain road steps, launching onto a fresh
     * bridge, and snapping onto a reused one. Quadratic on turn angle: nearly free for gentle curves
     * (≤45°), significant at 90°, prohibitive for reversals.
     */
    public static double turnCost(int fromDir, int toDir, int step, double turnPenalty) {
        if (fromDir == DIR_NONE || fromDir == toDir) return 0.0;
        int[] fromD = NEIGHBOR_DIRS[fromDir];
        int[] toD = NEIGHBOR_DIRS[toDir];
        double dot = normalizedDot(fromD[0], fromD[1], toD[0], toD[1]);
        double t = (1.0 - dot) * 0.5; // 0=straight, 0.5=90°, 1=reversal
        return t * t * turnPenalty * step;
    }

    /** Grid step of a search between these two points: coarser for long routes. */
    static int stepFor(BlockPos start, BlockPos goal) {
        double roughDist = Math.sqrt(Pos2D.distSq(start.getX(), start.getZ(), goal.getX(), goal.getZ()));
        return roughDist > LONG_ROUTE_DIST
                ? Math.min(MAX_ADAPTIVE_STEP, GRID_STEP + LONG_ROUTE_STEP_INCREASE)
                : GRID_STEP;
    }

    /** Scales a per-grid-step limit to the actual step, rounding up. */
    static int scaleUpToStep(int perGridStep, int step) {
        return (step == GRID_STEP)
                ? perGridStep
                : (int) Math.ceil((double) perGridStep * (double) step / (double) GRID_STEP);
    }

    /**
     * Nearest grid cell around a snapped point that is open land with enough free exits;
     * prefers existing roads and cells with more exits. Null when none within the search radius.
     */
    @Nullable
    static int[] nearestValidCell(int x0, int z0, int step, TerrainView sampler) {
        int maxR = Math.max(ENDPOINT_SNAP_MIN_RADIUS, step * ENDPOINT_SNAP_RADIUS_STEPS);

        int bestX = 0, bestZ = 0;
        double best = Double.POSITIVE_INFINITY;
        boolean found = false;

        for (int r = 0; r <= maxR; r += step) {
            for (int dz = -r; dz <= r; dz += step) {
                for (int dx = -r; dx <= r; dx += step) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;

                    int x = x0 + dx;
                    int z = z0 + dz;

                    if (!isOpenCell(x, z, sampler)) continue;
                    int exits = countFreeExits(x, z, step, sampler);
                    if (exits < MIN_FREE_EXITS) continue;

                    double d2 = (double) dx * (double) dx + (double) dz * (double) dz;

                    if (sampler.isExistingRoadXZ(x, z)) d2 -= ENDPOINT_ON_ROAD_BONUS;

                    d2 -= exits * ENDPOINT_FREE_EXIT_BONUS;

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

        return null;
    }

    /** Number of the 16 move targets from (x, z) that are open cells. */
    static int countFreeExits(int x, int z, int step, TerrainView sampler) {
        int free = 0;
        for (int[] dir : NEIGHBOR_DIRS) {
            if (isOpenCell(x + dir[0] * step, z + dir[1] * step, sampler)) free++;
        }
        return free;
    }

    /** Dry, allowed, unprotected land a road could pass through. */
    static boolean isOpenCell(int x, int z, TerrainView sampler) {
        TerrainSampler.Sample s = sampler.sample(x, z);
        if (s == null) return false;
        if (sampler.forbidden(s, TerrainSampler.Mode.PATHFINDING)) return false;
        if (s.isWater || s.isOcean) return false;
        return !sampler.isProtectedNoPaint(x, z);
    }
}

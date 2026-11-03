package com.jubitus.ultreia.route;

import com.jubitus.ultreia.geom.Pos2D;

/** The A* move grid: the 16 move directions, grid snapping, turn cost. */
public final class GridMoves {

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
}

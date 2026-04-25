package com.jubitus.millpathbuilder.gen.newp;

import com.jubitus.millpathbuilder.gen.TerrainSampler;
import com.jubitus.millpathbuilder.gen.bridge.BridgeGeometry;
import net.minecraft.util.math.BlockPos;

import java.util.Arrays;
import java.util.List;

public final class MPBRoadHalfConstraintSolver {

    public static final int FIXED_NONE = Integer.MIN_VALUE;

    private MPBRoadHalfConstraintSolver() {}

    public static boolean isLineFeasible(List<BlockPos> line,
                                         TerrainSampler sampler,
                                         int startHalf,
                                         int endHalf,
                                         boolean allowEarthworks,
                                         int cutHalf,
                                         int fillHalf,
                                         int maxStepHalf) {
        if (line == null || line.isEmpty() || sampler == null) return false;
        if (maxStepHalf < 1) maxStepHalf = 1;

        int n = line.size();
        int[] minHalf = new int[n];
        int[] maxHalf = new int[n];

        for (int i = 0; i < n; i++) {
            BlockPos p = line.get(i);
            TerrainSampler.Sample s = sampler.sample(p.getX(), p.getZ());
            if (s == null) return false;

            int terrainHalf = BridgeGeometry.naturalSurfaceHalfY(s.surfaceY);

            if (allowEarthworks) {
                minHalf[i] = terrainHalf - Math.max(0, cutHalf);
                maxHalf[i] = terrainHalf + Math.max(0, fillHalf);
            } else {
                minHalf[i] = terrainHalf;
                maxHalf[i] = terrainHalf;
            }
        }

        minHalf[0] = startHalf;
        maxHalf[0] = startHalf;
        minHalf[n - 1] = endHalf;
        maxHalf[n - 1] = endHalf;

        return isFeasible(minHalf, maxHalf, maxStepHalf);
    }

    public static int[] solveProfile(int[] terrainHalf,
                                     int[] fixedHalf,
                                     int cutHalf,
                                     int fillHalf,
                                     int maxStepHalf,
                                     int smoothRadius) {
        if (terrainHalf == null || terrainHalf.length == 0) return new int[0];
        if (maxStepHalf < 1) maxStepHalf = 1;

        int n = terrainHalf.length;

        int[] target = Arrays.copyOf(terrainHalf, n);
        if (smoothRadius > 0) {
            target = movingAverageSkippingFixed(target, fixedHalf, smoothRadius);
        }

        int[] minHalf = new int[n];
        int[] maxHalf = new int[n];

        for (int i = 0; i < n; i++) {
            int th = terrainHalf[i];

            minHalf[i] = th - Math.max(0, cutHalf);
            maxHalf[i] = th + Math.max(0, fillHalf);

            if (fixedHalf != null && i < fixedHalf.length && fixedHalf[i] != FIXED_NONE) {
                minHalf[i] = fixedHalf[i];
                maxHalf[i] = fixedHalf[i];
                target[i] = fixedHalf[i];
            } else {
                target[i] = clamp(target[i], minHalf[i], maxHalf[i]);
            }
        }

        return solveIntervals(minHalf, maxHalf, target, maxStepHalf);
    }

    private static boolean isFeasible(int[] minHalf, int[] maxHalf, int maxStepHalf) {
        int n = minHalf.length;
        int[] reachMin = new int[n];
        int[] reachMax = new int[n];

        reachMin[0] = minHalf[0];
        reachMax[0] = maxHalf[0];

        if (reachMin[0] > reachMax[0]) return false;

        for (int i = 1; i < n; i++) {
            int lo = Math.max(minHalf[i], reachMin[i - 1] - maxStepHalf);
            int hi = Math.min(maxHalf[i], reachMax[i - 1] + maxStepHalf);

            if (lo > hi) return false;

            reachMin[i] = lo;
            reachMax[i] = hi;
        }

        return true;
    }

    private static int[] solveIntervals(int[] minHalf,
                                        int[] maxHalf,
                                        int[] target,
                                        int maxStepHalf) {
        int n = minHalf.length;

        int[] reachMin = new int[n];
        int[] reachMax = new int[n];

        reachMin[0] = minHalf[0];
        reachMax[0] = maxHalf[0];
        if (reachMin[0] > reachMax[0]) return null;

        for (int i = 1; i < n; i++) {
            int lo = Math.max(minHalf[i], reachMin[i - 1] - maxStepHalf);
            int hi = Math.min(maxHalf[i], reachMax[i - 1] + maxStepHalf);

            if (lo > hi) {
                return null;
            }

            reachMin[i] = lo;
            reachMax[i] = hi;
        }

        int[] out = new int[n];
        out[n - 1] = clamp(target[n - 1], reachMin[n - 1], reachMax[n - 1]);

        for (int i = n - 2; i >= 0; i--) {
            int lo = Math.max(reachMin[i], out[i + 1] - maxStepHalf);
            int hi = Math.min(reachMax[i], out[i + 1] + maxStepHalf);

            if (lo > hi) {
                return null;
            }

            out[i] = clamp(target[i], lo, hi);
        }

        for (int i = 1; i < n; i++) {
            if (Math.abs(out[i] - out[i - 1]) > maxStepHalf) {
                return null;
            }
            if (out[i] < minHalf[i] || out[i] > maxHalf[i]) {
                return null;
            }
        }

        return out;
    }

    private static int[] movingAverageSkippingFixed(int[] in, int[] fixedHalf, int radius) {
        int n = in.length;
        int[] out = new int[n];

        for (int i = 0; i < n; i++) {
            if (fixedHalf != null && i < fixedHalf.length && fixedHalf[i] != FIXED_NONE) {
                out[i] = fixedHalf[i];
                continue;
            }

            int a = Math.max(0, i - radius);
            int b = Math.min(n - 1, i + radius);

            int sum = 0;
            int cnt = 0;

            for (int k = a; k <= b; k++) {
                if (fixedHalf != null && k < fixedHalf.length && fixedHalf[k] != FIXED_NONE) {
                    continue;
                }
                sum += in[k];
                cnt++;
            }

            out[i] = cnt <= 0 ? in[i] : Math.round((float) sum / (float) cnt);
        }

        return out;
    }

    private static int clamp(int v, int lo, int hi) {
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
package com.jubitus.ultreia.gen.newp;

import com.jubitus.ultreia.gen.RoadType;

import com.jubitus.ultreia.config.MPBConfig;
import com.jubitus.ultreia.gen.TerrainSampler;
import com.jubitus.ultreia.gen.bridge.BridgeGeometry;
import com.jubitus.ultreia.gen.bridge.BridgeSpan;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.util.math.BlockPos;

import java.util.Arrays;
import java.util.List;

public final class MPBRoadHalfProfile {

    private MPBRoadHalfProfile() {}

    public static int[] build(List<BlockPos> path, List<BridgeSpan> bridges, TerrainSampler sampler, RoadType roadType) {
        int n = path == null ? 0 : path.size();
        if (n <= 0) return new int[0];

        int[] terrainHalf = new int[n];
        for (int i = 0; i < n; i++) {
            terrainHalf[i] = BridgeGeometry.naturalSurfaceHalfY(path.get(i).getY());
        }

        int[] fixedHalf = buildExactBridgeHalfByPathIndex(path, bridges, sampler);

        boolean earthworks = roadType.allowsEarthworks();
        int cutHalf = earthworks ? Math.max(0, MPBConfig.PAINTING.earthworksMaxCut) * 2 : 0;
        int fillHalf = earthworks ? Math.max(0, MPBConfig.PAINTING.earthworksMaxFill) * 2 : 0;

        int maxStepHalf = Math.max(1, MPBConfig.PAINTING.maxRoadStepHalfPerBlock);
        int smoothRadius = Math.max(0, MPBConfig.PAINTING.verticalSmoothRadius);

        int[] solved = MPBRoadHalfConstraintSolver.solveProfile(
                terrainHalf,
                fixedHalf,
                cutHalf,
                fillHalf,
                maxStepHalf,
                smoothRadius
        );

        if (solved == null) {
            throw new IllegalStateException("No feasible road half-height profile with max step "
                    + maxStepHalf + " half-blocks");
        }

        return solved;
    }

    private static int[] buildExactBridgeHalfByPathIndex(List<BlockPos> path,
                                                         List<BridgeSpan> bridges,
                                                         TerrainSampler sampler) {
        int n = path == null ? 0 : path.size();
        int[] out = new int[n];
        Arrays.fill(out, MPBRoadHalfConstraintSolver.FIXED_NONE);

        if (path == null || bridges == null || bridges.isEmpty() || sampler == null) {
            return out;
        }

        for (BridgeSpan span : bridges) {
            if (span == null || span.centerline == null || span.centerline.isEmpty()) continue;

            int[] deckHalf = BridgeGeometry.buildDeckHalfProfile(span);

            int from = 0;
            for (int j = 0; j < span.centerline.size() && j < deckHalf.length; j++) {
                BlockPos p = span.centerline.get(j);
                int idx = findIndex(path, p.getX(), p.getZ(), from);
                if (idx >= 0 && idx < out.length) {
                    out[idx] = deckHalf[j];
                    from = idx;
                }
            }
        }

        return out;
    }

    private static int findIndex(List<BlockPos> path, int x, int z, int from) {
        for (int i = Math.max(0, from); i < path.size(); i++) {
            BlockPos p = path.get(i);
            if (p.getX() == x && p.getZ() == z) return i;
        }
        return -1;
    }
}
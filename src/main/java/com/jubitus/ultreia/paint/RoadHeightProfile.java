package com.jubitus.ultreia.paint;

import com.jubitus.ultreia.bridge.BridgeDeckProfile;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.terrain.TerrainView;
import com.jubitus.ultreia.route.RoadType;

import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.bridge.BridgeGeometry;
import com.jubitus.ultreia.bridge.BridgeSpan;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.util.math.BlockPos;

import java.util.Arrays;
import java.util.List;

public final class RoadHeightProfile {

    // Smoothing radius for the road centerline height profile.
    private static final int VERTICAL_SMOOTH_RADIUS = 2;

    private RoadHeightProfile() {}

    public static int[] build(List<BlockPos> path, List<BridgeSpan> bridges, TerrainView sampler) {
        int n = path == null ? 0 : path.size();
        if (n <= 0) return new int[0];

        int[] terrainHalf = new int[n];
        for (int i = 0; i < n; i++) {
            terrainHalf[i] = HalfY.ofBlockTop(path.get(i).getY());
        }

        int[] fixedHalf = buildExactBridgeHalfByPathIndex(path, bridges, sampler);

        int cutHalf = Math.max(0, UltreiaConfig.PAINTING.earthworksMaxCut) * 2;
        int fillHalf = Math.max(0, UltreiaConfig.PAINTING.earthworksMaxFill) * 2;

        int maxStepHalf = Math.max(1, HalfY.MAX_ROAD_STEP_HALF_PER_BLOCK);
        int smoothRadius = Math.max(0, VERTICAL_SMOOTH_RADIUS);

        int[] solved = RoadHeightSolver.solveProfile(
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
                                                         TerrainView sampler) {
        int n = path == null ? 0 : path.size();
        int[] out = new int[n];
        Arrays.fill(out, RoadHeightSolver.FIXED_NONE);

        if (path == null || bridges == null || bridges.isEmpty() || sampler == null) {
            return out;
        }

        for (BridgeSpan span : bridges) {
            if (span == null || span.centerline == null || span.centerline.isEmpty()) continue;

            int[] deckHalf = BridgeDeckProfile.of(span);

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
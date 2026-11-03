package com.jubitus.ultreia.gen.newp;

import com.jubitus.ultreia.gen.RoadType;

import com.jubitus.ultreia.config.MPBConfig;
import com.jubitus.ultreia.gen.TerrainSampler;
import com.jubitus.ultreia.gen.bridge.BridgeGeometry;
import com.jubitus.ultreia.gen.bridge.BridgeSpan;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class MPBBridgeApproachFeasibility {

    private MPBBridgeApproachFeasibility() {}

    public static boolean canConnectBridgeApproaches(TerrainSampler sampler,
                                                     BridgeSpan span,
                                                     int approachBlocks,
                                                     RoadType roadType) {
        if (sampler == null || span == null || span.centerline == null || span.centerline.size() < 2) {
            return false;
        }

        int n = span.centerline.size();
        int[] deckHalf = BridgeGeometry.buildDeckHalfProfile(span);

        int maxStepHalf = Math.max(1, MPBConfig.PAINTING.maxRoadStepHalfPerBlock);
        boolean earthworks = roadType.allowsEarthworks();
        int cutHalf = earthworks ? Math.max(0, MPBConfig.PAINTING.earthworksMaxCut) * 2 : 0;
        int fillHalf = earthworks ? Math.max(0, MPBConfig.PAINTING.earthworksMaxFill) * 2 : 0;

        int need = Math.max(1, approachBlocks);

        if (!checkOneSide(sampler, span.centerline.get(0), span.centerline.get(1), deckHalf[0], need, earthworks, cutHalf, fillHalf, maxStepHalf)) {
            return false;
        }

        if (!checkOneSide(sampler, span.centerline.get(n - 1), span.centerline.get(n - 2), deckHalf[n - 1], need, earthworks, cutHalf, fillHalf, maxStepHalf)) {
            return false;
        }

        return true;
    }

    private static boolean checkOneSide(TerrainSampler sampler,
                                        BlockPos bridgeEnd,
                                        BlockPos inwardNeighbor,
                                        int bridgeDeckHalf,
                                        int neededBlocks,
                                        boolean earthworks,
                                        int cutHalf,
                                        int fillHalf,
                                        int maxStepHalf) {
        // Away from the bridge, on both sides.
        int dx = Integer.signum(bridgeEnd.getX() - inwardNeighbor.getX());
        int dz = Integer.signum(bridgeEnd.getZ() - inwardNeighbor.getZ());

        if (dx == 0 && dz == 0) {
            return false;
        }

        List<BlockPos> line = new ArrayList<BlockPos>(neededBlocks + 1);
        line.add(new BlockPos(bridgeEnd.getX(), bridgeEnd.getY(), bridgeEnd.getZ()));

        int x = bridgeEnd.getX();
        int z = bridgeEnd.getZ();

        for (int i = 1; i <= neededBlocks; i++) {
            x += dx;
            z += dz;

            TerrainSampler.Sample s = sampler.sample(x, z);
            if (s == null) return false;
            if (s.isWater || s.isOcean) return false;
            if (sampler.forbidden(s, TerrainSampler.Mode.PATHFINDING)) return false;
            if (sampler.isProtectedNoPaint(x, z)) return false;

            line.add(new BlockPos(x, s.surfaceY, z));
        }

        BlockPos far = line.get(line.size() - 1);
        int farHalf = BridgeGeometry.naturalSurfaceHalfY(far.getY());

        return MPBRoadHalfConstraintSolver.isLineFeasible(
                line,
                sampler,
                bridgeDeckHalf,
                farHalf,
                earthworks,
                cutHalf,
                fillHalf,
                maxStepHalf
        );
    }
}
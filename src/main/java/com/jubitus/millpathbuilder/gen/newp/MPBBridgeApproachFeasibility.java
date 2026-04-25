package com.jubitus.millpathbuilder.gen.newp;

import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.TerrainSampler;
import com.jubitus.millpathbuilder.gen.bridge.BridgeGeometry;
import com.jubitus.millpathbuilder.gen.bridge.BridgeSpan;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class MPBBridgeApproachFeasibility {

    private MPBBridgeApproachFeasibility() {}

    public static boolean canConnectBridgeApproaches(TerrainSampler sampler,
                                                     BridgeSpan span,
                                                     int approachBlocks) {
        if (sampler == null || span == null || span.centerline == null || span.centerline.size() < 2) {
            return false;
        }

        int n = span.centerline.size();
        int[] deckHalf = BridgeGeometry.buildDeckHalfProfile(span, sampler);

        int maxStepHalf = Math.max(1, MPBConfig.PAINTING.maxRoadStepHalfPerBlock);
        int cutHalf = MPBConfig.PATHFINDING.allowEarthworks
                ? Math.max(0, MPBConfig.PAINTING.earthworksMaxCut) * 2
                : 0;
        int fillHalf = MPBConfig.PATHFINDING.allowEarthworks
                ? Math.max(0, MPBConfig.PAINTING.earthworksMaxFill) * 2
                : 0;

        int need = Math.max(1, approachBlocks);

        if (!checkOneSide(sampler, span.centerline.get(0), span.centerline.get(1), deckHalf[0], need, false, cutHalf, fillHalf, maxStepHalf)) {
            return false;
        }

        if (!checkOneSide(sampler, span.centerline.get(n - 1), span.centerline.get(n - 2), deckHalf[n - 1], need, true, cutHalf, fillHalf, maxStepHalf)) {
            return false;
        }

        return true;
    }

    private static boolean checkOneSide(TerrainSampler sampler,
                                        BlockPos bridgeEnd,
                                        BlockPos inwardNeighbor,
                                        int bridgeDeckHalf,
                                        int neededBlocks,
                                        boolean outwardFromEnd,
                                        int cutHalf,
                                        int fillHalf,
                                        int maxStepHalf) {
        int dx = Integer.signum(bridgeEnd.getX() - inwardNeighbor.getX());
        int dz = Integer.signum(bridgeEnd.getZ() - inwardNeighbor.getZ());

        if (!outwardFromEnd) {
            dx = -dx;
            dz = -dz;
        }

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
                MPBConfig.PATHFINDING.allowEarthworks,
                cutHalf,
                fillHalf,
                maxStepHalf
        );
    }
}
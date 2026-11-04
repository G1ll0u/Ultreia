package com.jubitus.ultreia.bridge;

import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.terrain.TerrainView;
import com.jubitus.ultreia.paint.RoadHeightSolver;
import com.jubitus.ultreia.route.TerrainMoveCost;
import com.jubitus.ultreia.route.RoadType;

import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.terrain.TerrainSampler;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/** Can roads reach both ends of a bridge: approach walks (an A* cost) and the exit corridor (a feasibility gate). */
public final class BridgeApproachCheck {

    // Require this many buildable blocks straight ahead after bridge exit.
    private static final int REQUIRED_EXIT_CORRIDOR_BLOCKS = 1;

    private BridgeApproachCheck() {}

    public static boolean canConnectBridgeApproaches(TerrainView sampler,
                                                     BridgeSpan span,
                                                     int approachBlocks) {
        if (sampler == null || span == null || span.centerline == null || span.centerline.size() < 2) {
            return false;
        }

        int n = span.centerline.size();
        int[] deckHalf = BridgeDeckProfile.of(span);

        int maxStepHalf = Math.max(1, HalfY.MAX_ROAD_STEP_HALF_PER_BLOCK);
        int cutHalf = Math.max(0, UltreiaConfig.PAINTING.earthworksMaxCut) * 2;
        int fillHalf = Math.max(0, UltreiaConfig.PAINTING.earthworksMaxFill) * 2;

        int need = Math.max(1, approachBlocks);

        if (!checkOneSide(sampler, span.centerline.get(0), span.centerline.get(1), deckHalf[0], need, cutHalf, fillHalf, maxStepHalf)) {
            return false;
        }

        if (!checkOneSide(sampler, span.centerline.get(n - 1), span.centerline.get(n - 2), deckHalf[n - 1], need, cutHalf, fillHalf, maxStepHalf)) {
            return false;
        }

        return true;
    }

    private static boolean checkOneSide(TerrainView sampler,
                                        BlockPos bridgeEnd,
                                        BlockPos inwardNeighbor,
                                        int bridgeDeckHalf,
                                        int neededBlocks,
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
        int farHalf = HalfY.ofBlockTop(far.getY());

        return RoadHeightSolver.isLineFeasible(
                line,
                sampler,
                bridgeDeckHalf,
                farHalf,
                cutHalf,
                fillHalf,
                maxStepHalf
        );
    }

    /** The ground just past the bridge end, straight ahead, can take a road. */
    static boolean exitCorridorIsBuildable(BridgeSite site, TerrainView sampler, BlockPos start, BlockPos end) {
        int lookAhead = Math.max(1, REQUIRED_EXIT_CORRIDOR_BLOCKS);

        int dx = Integer.signum(end.getX() - start.getX());
        int dz = Integer.signum(end.getZ() - start.getZ());

        if (dx == 0 && dz == 0) return false;

        int maxDy = Math.max(1, TerrainMoveCost.MAX_YDELTA_PER_STEP);

        TerrainSampler.Sample prev = sampler.sample(end.getX(), end.getZ());
        if (prev == null) return false;

        int x = end.getX();
        int z = end.getZ();

        for (int i = 1; i <= lookAhead; i++) {
            x += dx;
            z += dz;

            TerrainSampler.Sample sample = sampler.sample(x, z);
            if (sample == null) return false;
            if (sample.isWater || sample.isOcean) return false;
            if (sampler.forbidden(sample, TerrainSampler.Mode.PATHFINDING)) return false;
            if (sampler.isProtectedNoPaint(x, z)) return false;

            int dy = Math.abs(sample.surfaceY - prev.surfaceY);
            if (dy > maxDy + 1) return false;

            if (!site.isRoadGround(x, sample.surfaceY, z)) return false;

            prev = sample;
        }

        return true;
    }
}

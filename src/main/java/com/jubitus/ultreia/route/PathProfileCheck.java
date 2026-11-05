package com.jubitus.ultreia.route;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.bridge.BridgeClearanceCheck;
import com.jubitus.ultreia.bridge.BridgeDeckProfile;
import com.jubitus.ultreia.bridge.BridgeGeometry;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.geom.LineRaster;
import com.jubitus.ultreia.geom.Pos2D;
import it.unimi.dsi.fastutil.longs.LongSet;
import com.jubitus.ultreia.terrain.TerrainView;
import com.jubitus.ultreia.paint.RoadHeightProfile;

import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.bridge.BridgeSpan;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The only whole-path check of a found A* path: walkable end to end (followed roads excepted), and never under one of its own bridges. */
public final class PathProfileCheck {

    private PathProfileCheck() {}

    /**
     * @param bridgeStartIndices index in {@code coarsePath} at which each bridge of {@code bridges} begins
     * @param roadHalf half width of the road, beside its centre point
     * @param step A* grid step: road points within it of a bridge end are that bridge's approach
     * @param followedXZ cells on followed existing roads: steps between two of them are that road's, not judged here
     */
    static boolean isAcceptable(List<BlockPos> coarsePath, List<BridgeSpan> bridges, List<Integer> bridgeStartIndices,
                                LongSet followedXZ, TerrainView sampler, int roadHalf, int step) {
        boolean walkable = isFullPathFeasible(coarsePath, bridges, followedXZ, sampler);
        boolean clear = bridgesStayOffOwnPath(coarsePath, bridges, bridgeStartIndices, roadHalf, step);
        return walkable && clear;
    }

    private static boolean isFullPathFeasible(List<BlockPos> coarsePath,
                                             List<BridgeSpan> bridges,
                                             LongSet followedXZ,
                                             TerrainView sampler) {
        if (coarsePath == null || coarsePath.isEmpty() || sampler == null) {
            return false;
        }

        List<BlockPos> dense = densify(coarsePath, sampler);
        if (dense.isEmpty()) {
            return false;
        }

        try {
            int[] half = RoadHeightProfile.build(dense, bridges, sampler);
            if (half == null || half.length != dense.size()) {
                if (UltreiaConfig.DEBUG.logPathfinding) {
                    Ultreia.LOGGER.info("[Ultreia A*] whole-path check refused: no height profile for {} blocks", dense.size());
                }
                return false;
            }

            int maxStepHalf = Math.max(1, HalfY.MAX_ROAD_STEP_HALF_PER_BLOCK);
            for (int i = 1; i < half.length; i++) {
                if (isFollowed(followedXZ, dense.get(i)) && isFollowed(followedXZ, dense.get(i - 1))) continue;
                if (Math.abs(half[i] - half[i - 1]) > maxStepHalf) {
                    if (UltreiaConfig.DEBUG.logPathfinding) {
                        BlockPos p = dense.get(i);
                        Ultreia.LOGGER.info("[Ultreia A*] whole-path check refused: step of {} half-blocks at ({},{},{}), block {}/{} of the path",
                                Math.abs(half[i] - half[i - 1]), p.getX(), p.getY(), p.getZ(), i, half.length);
                    }
                    return false;
                }
            }

            return true;
        } catch (IllegalStateException noProfile) {
            if (UltreiaConfig.DEBUG.logPathfinding) {
                Ultreia.LOGGER.info("[Ultreia A*] whole-path check refused: {}", noProfile.getMessage());
            }
            return false;
        } catch (Throwable t) {
            Ultreia.LOGGER.warn("[Ultreia A*] whole-path check failed with an error, path refused", t);
            return false;
        }
    }

    private static boolean isFollowed(LongSet followedXZ, BlockPos p) {
        return followedXZ.contains(Pos2D.xzKey(p.getX(), p.getZ()));
    }

    private static List<BlockPos> densify(List<BlockPos> coarse, TerrainView sampler) {
        if (coarse == null || coarse.isEmpty()) {
            return Collections.emptyList();
        }

        ArrayList<BlockPos> out = new ArrayList<BlockPos>();
        BlockPos first = coarse.get(0);
        TerrainSampler.Sample fs = sampler.sample(first.getX(), first.getZ());
        if (fs == null) {
            return Collections.emptyList();
        }
        out.add(new BlockPos(first.getX(), fs.surfaceY, first.getZ()));

        for (int i = 1; i < coarse.size(); i++) {
            BlockPos a = coarse.get(i - 1);
            BlockPos b = coarse.get(i);

            List<BlockPos> line = LineRaster.forBridges(a.getX(), a.getZ(), b.getX(), b.getZ(), 0);

            if (line == null || line.isEmpty()) {
                continue;
            }

            for (int k = 1; k < line.size(); k++) {
                BlockPos p = line.get(k);
                TerrainSampler.Sample s = sampler.sample(p.getX(), p.getZ());
                if (s == null) {
                    return Collections.emptyList();
                }
                out.add(new BlockPos(p.getX(), s.surfaceY, p.getZ()));
            }
        }

        return out;
    }

    /** Rejects paths that set foot under one of their own bridges, before or after it. */
    private static boolean bridgesStayOffOwnPath(List<BlockPos> path, List<BridgeSpan> spans,
                                                 List<Integer> bridgeStartIndices, int roadHalf, int step) {
        if (spans.isEmpty() || bridgeStartIndices.size() != spans.size()) return true;

        for (int b = 0; b < spans.size(); b++) {
            BridgeSpan span = spans.get(b);
            if (span.centerline == null || span.centerline.isEmpty()) continue;

            int startIdx = bridgeStartIndices.get(b);
            int endIdx = startIdx;
            while (endIdx < path.size() - 1 && !sameXZ(path.get(endIdx), span.end)) endIdx++;

            for (int k = 0; k < path.size(); k++) {
                if (k >= startIdx && k <= endIdx) continue;
                BlockPos roadPoint = path.get(k);
                if (!underOwnBridge(span, roadHalf, step, roadPoint.getX(), roadPoint.getZ())) continue;
                if (UltreiaConfig.DEBUG.logBridges) {
                    Ultreia.LOGGER.info("[Ultreia Bridge] path runs under its own bridge[{}] ({},{})->({},{}) at road point ({},{},{})",
                            b, span.start.getX(), span.start.getZ(), span.end.getX(), span.end.getZ(),
                            roadPoint.getX(), roadPoint.getY(), roadPoint.getZ());
                }
                return false;
            }
        }
        return true;
    }

    /**
     * A road centre point (x, z) whose road would touch the span's footprint away from its two ends:
     * a road never runs under a bridge of its own path, whatever the deck height. A* applies the same rule.
     */
    static boolean underOwnBridge(BridgeSpan span, int roadHalf, int step, int x, int z) {
        double lateral = BridgeGeometry.lateralHalfWidth(span.template) + roadHalf + 0.5;
        return BridgeGeometry.isUnderSpan(span, lateral, step, x, z);
    }

    private static boolean sameXZ(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }
}

package com.jubitus.ultreia.route;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.bridge.BridgeClearanceCheck;
import com.jubitus.ultreia.bridge.BridgeDeckProfile;
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

/** The only whole-path check of a found A* path: walkable end to end (followed roads excepted), and no bridge deck too low over its own approach road. */
public final class PathProfileCheck {

    // Tight XZ match between a bridge cell and an approach road point: avoids false matches with off-axis nodes.
    static final int SELF_OVERLAP_XZ_RADIUS = 1;

    private PathProfileCheck() {}

    /**
     * @param bridgeStartIndices index in {@code coarsePath} at which each bridge of {@code bridges} begins
     * @param step A* grid step: the first {@code step} deck cells are the launch ramp and are not checked for overlap
     * @param followedXZ cells on followed existing roads: steps between two of them are that road's, not judged here
     */
    static boolean isAcceptable(List<BlockPos> coarsePath, List<BridgeSpan> bridges, List<Integer> bridgeStartIndices,
                                LongSet followedXZ, TerrainView sampler, int step) {
        boolean walkable = isFullPathFeasible(coarsePath, bridges, followedXZ, sampler);
        boolean clear = bridgesClearOwnApproach(coarsePath, bridges, bridgeStartIndices, step);
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

    /**
     * Rejects paths where a bridge deck passes over its own approach path without sufficient
     * clearance. Only the bridge interior is checked: the first {@code step} centerline cells
     * are the ramp connection to the road and are always at road Y.
     * A bridge over a DIFFERENT road segment (valley-cross) is fine as long as clearance >= min.
     */
    private static boolean bridgesClearOwnApproach(List<BlockPos> path, List<BridgeSpan> spans,
                                                   List<Integer> bridgeStartIndices, int step) {
        if (spans.isEmpty() || bridgeStartIndices.size() != spans.size()) return true;

        for (int b = 0; b < spans.size(); b++) {
            BridgeSpan span = spans.get(b);
            if (span.centerline == null || span.centerline.isEmpty()) continue;

            int priorEnd = Math.min(bridgeStartIndices.get(b), path.size() - 1);
            if (priorEnd < 0) continue;

            int[] deckHalf = BridgeDeckProfile.of(span);

            for (int k = 0; k <= priorEnd; k++) {
                BlockPos roadPoint = path.get(k);
                int ci = lowDeckCell(span, deckHalf, step, roadPoint.getX(), roadPoint.getY(), roadPoint.getZ());
                if (ci < 0) continue;
                if (UltreiaConfig.DEBUG.logBridges) {
                    BlockPos bridgePoint = span.centerline.get(ci);
                    int deckSurfaceY = deckSurfaceY(deckHalf, ci);
                    Ultreia.LOGGER.info(
                            "[Ultreia Bridge] self-overlap bridge[{}] ({},{})->({},{}): deck Y={} at ({},{}) over approach road Y={} at ({},{}), clearance={} < {}",
                            b, span.start.getX(), span.start.getZ(), span.end.getX(), span.end.getZ(),
                            deckSurfaceY, bridgePoint.getX(), bridgePoint.getZ(),
                            roadPoint.getY(), roadPoint.getX(), roadPoint.getZ(),
                            deckSurfaceY - roadPoint.getY() - 1, BridgeClearanceCheck.UNDER_BRIDGE_ROAD_MIN_CLEARANCE_BLOCKS);
                }
                return false;
            }
        }
        return true;
    }

    /**
     * The first deck cell of {@code span}, past its launch ramp of {@code step} cells, that passes over the road point
     * (x, y, z) with too little room above it; -1 if none. A* applies the same rule to its bridge moves.
     */
    static int lowDeckCell(BridgeSpan span, int[] deckHalf, int step, int x, int y, int z) {
        int minClearance = BridgeClearanceCheck.UNDER_BRIDGE_ROAD_MIN_CLEARANCE_BLOCKS;
        for (int ci = step; ci < span.centerline.size(); ci++) {
            BlockPos bridgePoint = span.centerline.get(ci);
            if (Math.abs(bridgePoint.getX() - x) > SELF_OVERLAP_XZ_RADIUS) continue;
            if (Math.abs(bridgePoint.getZ() - z) > SELF_OVERLAP_XZ_RADIUS) continue;
            if (deckSurfaceY(deckHalf, ci) - y - 1 < minClearance) return ci;
        }
        return -1;
    }

    private static int deckSurfaceY(int[] deckHalf, int ci) {
        return HalfY.surfaceBlockY(deckHalf[Math.min(ci, deckHalf.length - 1)]);
    }
}

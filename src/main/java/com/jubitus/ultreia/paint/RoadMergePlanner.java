package com.jubitus.ultreia.paint;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.roads.RoadIndex;
import com.jubitus.ultreia.roads.RoadJoins;
import com.jubitus.ultreia.roads.RoadNetwork;
import com.jubitus.ultreia.roads.RoadSegment;
import com.jubitus.ultreia.route.RoadType;
import com.jubitus.ultreia.terrain.ProtectedAreas;
import com.jubitus.ultreia.terrain.TerrainSampler;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * How a new road meets the roads already built: the stretches A* followed on the network are not painted (gaps,
 * with their entry and exit points for the connectors), and a lone trail stops on the first road it meets.
 */
final class RoadMergePlanner {

    // Longest off-network piece (path blocks) between two followed stretches that is left unpainted too.
    private static final int MAX_REJOIN_HOLE_BLOCKS = 8;

    private final WorldServer world;
    private final TerrainSampler sampler;
    @Nullable private final ProtectedAreas protectedAreas;
    @Nullable private final RoadIndex roadIndex;
    private final List<BlockPos> path;
    private final LongSet newlyPlaced;

    private LongSet followedXZ = LongSets.EMPTY_SET;
    private Collection<RoadPaintSession.ForcedBridgeRef> forced = Collections.emptyList();

    /** The road stops at path index {@code hitIndex} and joins the existing road at (targetX, targetZ). */
    boolean active = false;
    int targetX = 0;
    int targetZ = 0;
    int hitIndex = Integer.MAX_VALUE;

    private boolean[] gapByIdx = null;
    // Gap boundary info: (pathIdx, roadX, roadZ) tuples used by the junction reinforcement
    // to force-paint connectors between the new path and the existing road at each gap edge.
    int[] gapBoundaryPathIdx = null;
    int[] gapBoundaryRoadX = null;
    int[] gapBoundaryRoadZ = null;
    int gapBoundaryCount = 0;

    RoadMergePlanner(WorldServer world, TerrainSampler sampler, @Nullable ProtectedAreas protectedAreas,
                     @Nullable RoadIndex roadIndex, List<BlockPos> path, LongSet newlyPlaced) {
        this.world = world;
        this.sampler = sampler;
        this.protectedAreas = protectedAreas;
        this.roadIndex = roadIndex;
        this.path = path;
        this.newlyPlaced = newlyPlaced;
    }

    /** True when the path index lies in a skipped stretch. */
    boolean isGap(int idx) {
        return gapByIdx != null && idx < gapByIdx.length && gapByIdx[idx];
    }

    /**
     * Decides, once the centerline is known, which stretches the road follows on the network (gaps) and, for a lone
     * trail, where it stops on the first road it meets.
     */
    void detect(RoadType roadType, int baseR, LongSet followedXZ, Collection<RoadPaintSession.ForcedBridgeRef> forced) {
        this.followedXZ = followedXZ;
        this.forced = forced;
        if (roadType == RoadType.LONE_TRAIL) detectLoneTrailMerge(baseR);
        detectFollowedGaps(active ? hitIndex : path.size() - 1);
    }

    /** A lone trail grafts onto the first existing road it reaches (after leaving its own building's paths). */
    private void detectLoneTrailMerge(int baseR) {
        if (roadIndex == null || path.size() < 3) return;

        int exitBlocks = Math.max(0, RoadPaintSession.FORCE_PAINT_EXIT_BLOCKS);

        // Hard minimum: never merge before own forced bridges are fully painted.
        int absoluteMin = Math.max(3, baseR + 3);
        for (RoadPaintSession.ForcedBridgeRef ref : forced) {
            int past = ref.endIdx + exitBlocks + 1;
            if (past > absoluteMin) absoluteMin = past;
        }

        // Scans to the very end of the path: the road may be reached late (A* road-snap, or at the destination).
        int scanTo = path.size() - 2;
        if (scanTo < absoluteMin) return;

        // A more lenient height tolerance than roads, so terrain roughness doesn't prevent a valid graft.
        int maxJoinDeltaHalf = Math.max(Math.max(0, HalfY.MAX_ROAD_JOIN_DELTA_HALF_BLOCKS), 3);
        int mergeRadius = Math.max(2, RoadType.LONE_TRAIL_MERGE_SEARCH_RADIUS);

        // The merge target must be this far from the path origin: never the internal paths of the departure building.
        BlockPos origin = path.get(0);
        int startMinBlocks = Math.max(20, RoadType.LONE_TRAIL_MERGE_SEARCH_RADIUS + 10);
        int startMinDistSq = startMinBlocks * startMinBlocks;

        for (int idx = absoluteMin; idx <= scanTo; idx++) {
            BlockPos p = path.get(idx);

            BlockPos near = roadIndex.findNearestRoad(p.getX(), p.getZ(), mergeRadius);
            if (near == null) continue;

            long nk = Pos2D.xzKey(near.getX(), near.getZ());
            if (newlyPlaced.contains(nk)) continue;

            // Skip road blocks inside any village/building footprint — they are internal paths
            // that belong to the departure or arrival structure, not the inter-village network.
            if (protectedAreas != null && protectedAreas.contains(near.getX(), near.getZ())) continue;

            int odx = near.getX() - origin.getX();
            int odz = near.getZ() - origin.getZ();
            if (odx * odx + odz * odz < startMinDistSq) continue;

            if (roadIndex.isBridgeXZ(near.getX(), near.getZ())) {
                near = findNearestNonBridgeRoad(p.getX(), p.getZ(), mergeRadius);
                if (near == null) continue;
                // Re-check protected areas and start distance after bridge fallback.
                if (protectedAreas != null && protectedAreas.contains(near.getX(), near.getZ())) continue;
                odx = near.getX() - origin.getX();
                odz = near.getZ() - origin.getZ();
                if (odx * odx + odz * odz < startMinDistSq) continue;
            }

            // The connector climbs at most one half-block per cell, so the height gap is capped by its length.
            int connectorCells = Math.max(Math.abs(p.getX() - near.getX()), Math.abs(p.getZ() - near.getZ()));
            if (!RoadJoins.canMergeCleanlyToRoad(
                    world, sampler, roadIndex,
                    p.getX(), p.getY(), p.getZ(),
                    near.getX(), near.getZ(),
                    Math.min(maxJoinDeltaHalf, connectorCells)
            )) {
                continue;
            }

            active = true;
            targetX = near.getX();
            targetZ = near.getZ();
            hitIndex = idx;

            if (UltreiaConfig.DEBUG.logPainting) {
                Ultreia.LOGGER.info(
                        "[Ultreia] Lone trail merge: path index {}/{} -> road ({}, {}), stopping paint here",
                        idx, path.size() - 1, near.getX(), near.getZ());
            }
            return;
        }
    }

    /**
     * The stretches A* followed on the network (up to {@code lastIdx}) are gaps: nothing is painted there. A short
     * off-network piece between two followed stretches is a gap too (the road would only be repainted). Each gap
     * records its entry and exit so the footprint planner forces a connector to the followed road.
     */
    private void detectFollowedGaps(int lastIdx) {
        if (followedXZ.isEmpty() || lastIdx < 0) return;
        int n = Math.min(path.size(), lastIdx + 1);

        boolean[] gaps = new boolean[path.size()];
        boolean any = false;
        for (int idx = 0; idx < n; idx++) {
            BlockPos p = path.get(idx);
            gaps[idx] = followedXZ.contains(Pos2D.xzKey(p.getX(), p.getZ()));
            any |= gaps[idx];
        }
        if (!any) return;
        fillShortHoles(gaps, n);

        int maxBoundaries = 64;
        int[] bPathIdx = new int[maxBoundaries];
        int[] bRoadX = new int[maxBoundaries];
        int[] bRoadZ = new int[maxBoundaries];
        int bCount = 0;

        for (int idx = 0; idx < n; idx++) {
            if (!gaps[idx] || (idx > 0 && gaps[idx - 1])) continue;
            int runEnd = idx;
            while (runEnd + 1 < n && gaps[runEnd + 1]) runEnd++;

            // Entry: last painted cell before the gap -> first followed cell.
            if (idx > 0 && bCount < maxBoundaries) {
                bPathIdx[bCount] = idx - 1;
                bRoadX[bCount] = path.get(idx).getX();
                bRoadZ[bCount] = path.get(idx).getZ();
                bCount++;
            }
            // Exit: last followed cell -> first painted cell after the gap.
            if (runEnd + 1 < n && bCount < maxBoundaries) {
                bPathIdx[bCount] = runEnd + 1;
                bRoadX[bCount] = path.get(runEnd).getX();
                bRoadZ[bCount] = path.get(runEnd).getZ();
                bCount++;
            }
            if (UltreiaConfig.DEBUG.logPainting) {
                Ultreia.LOGGER.info("[Ultreia] Followed road: path[{}..{}] not painted", idx, runEnd);
            }
        }

        gapByIdx = gaps;
        if (bCount > 0) {
            gapBoundaryPathIdx = Arrays.copyOf(bPathIdx, bCount);
            gapBoundaryRoadX = Arrays.copyOf(bRoadX, bCount);
            gapBoundaryRoadZ = Arrays.copyOf(bRoadZ, bCount);
            gapBoundaryCount = bCount;
        }
    }

    /** Marks as gap every non-gap run of at most {@code MAX_REJOIN_HOLE_BLOCKS} between two gaps, unless it holds a new bridge. */
    private void fillShortHoles(boolean[] gaps, int n) {
        int lastGap = -1;
        for (int idx = 0; idx < n; idx++) {
            if (!gaps[idx]) continue;
            int holeLen = idx - lastGap - 1;
            if (lastGap >= 0 && holeLen > 0 && holeLen <= MAX_REJOIN_HOLE_BLOCKS && !touchesNewBridge(lastGap + 1, idx - 1)) {
                for (int h = lastGap + 1; h < idx; h++) gaps[h] = true;
            }
            lastGap = idx;
        }
    }

    private boolean touchesNewBridge(int from, int to) {
        for (RoadPaintSession.ForcedBridgeRef ref : forced) {
            if (ref.startIdx <= to && ref.endIdx >= from) return true;
        }
        return false;
    }

    private BlockPos findNearestNonBridgeRoad(int x, int z, int maxRadius) {
        for (int r = 0; r <= maxRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (r > 0 && Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int nx = x + dx, nz = z + dz;
                    if (!roadIndex.isRoadXZ(nx, nz)) continue;
                    if (roadIndex.isBridgeXZ(nx, nz)) continue;
                    if (newlyPlaced.contains(Pos2D.xzKey(nx, nz))) continue;
                    return new BlockPos(nx, 0, nz);
                }
            }
        }
        return null;
    }
}

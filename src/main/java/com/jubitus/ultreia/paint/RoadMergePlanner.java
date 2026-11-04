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
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * How a new road meets the roads already built: where it stops on an existing road that leads to its destination
 * (lone trails: on the first road), and which stretches it skips because they run along an existing road or cross a
 * built bridge (gaps, with their entry and exit points for the connectors).
 */
final class RoadMergePlanner {

    private final WorldServer world;
    private final TerrainSampler sampler;
    @Nullable private final ProtectedAreas protectedAreas;
    @Nullable private final RoadIndex roadIndex;
    private final List<BlockPos> path;
    private final LongSet newlyPlaced;

    @Nullable private RoadNetwork roadNetwork;
    @Nullable private BlockPos toVillage;
    private LongSet reusedBridgeXZ = LongSets.EMPTY_SET;
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

    /** Decides, once the centerline is known, where the road stops on an existing road and which stretches it skips. */
    void detect(RoadType roadType, int baseR, @Nullable RoadNetwork roadNetwork, @Nullable BlockPos toVillage,
                LongSet reusedBridgeXZ, Collection<RoadPaintSession.ForcedBridgeRef> forced) {
        this.roadNetwork = roadNetwork;
        this.toVillage = toVillage;
        this.reusedBridgeXZ = reusedBridgeXZ;
        this.forced = forced;

        if (roadIndex == null) return;

        boolean isLoneTrail = roadType == RoadType.LONE_TRAIL;

        // LONE_TRAIL may have very short paths (lonebuilding right next to a road).
        if (!isLoneTrail && path.size() < 8) return;
        if (path.size() < 3) return;

        int exitBlocks = Math.max(0, RoadPaintSession.FORCE_PAINT_EXIT_BLOCKS);

        // Hard minimum: never merge before own forced bridges are fully painted.
        int absoluteMin = Math.max(isLoneTrail ? 3 : 6, baseR + 3);
        for (RoadPaintSession.ForcedBridgeRef ref : forced) {
            int past = ref.endIdx + exitBlocks + 1;
            if (past > absoluteMin) absoluteMin = past;
        }

        // LONE_TRAIL scans to the very end of the path so it can merge even when the road
        // is reached late (e.g. after A* road-snap, or when the road runs at the destination).
        int scanTo = isLoneTrail
                ? path.size() - 2
                : path.size() - 1 - Math.max(6, baseR + 3);
        if (scanTo < absoluteMin) return;

        int maxJoinDeltaHalf = Math.max(0, HalfY.MAX_ROAD_JOIN_DELTA_HALF_BLOCKS);
        if (isLoneTrail) {
            // LONE_TRAIL uses a more lenient height tolerance so terrain roughness doesn't
            // prevent a valid graft onto the network.
            maxJoinDeltaHalf = Math.max(maxJoinDeltaHalf, 3);
        }

        int mergeRadius = isLoneTrail
                ? Math.max(2, RoadType.LONE_TRAIL_MERGE_SEARCH_RADIUS)
                : 2;

        // Minimum world-distance (squared) the merge target must be from the path origin.
        // Prevents merging into road blocks belonging to the same village or lonebuilding
        // from which the path departs (e.g. Millénaire internal paths).
        BlockPos origin = path.get(0);
        int startMinBlocks = isLoneTrail
                ? Math.max(20, RoadType.LONE_TRAIL_MERGE_SEARCH_RADIUS + 10)
                : 12;
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

            // Skip road blocks that are too close to our own starting position (same-building paths).
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

            // LONE_TRAIL grafts onto any existing road; other types stop only on a road that leads to the destination.
            if (!isLoneTrail && !isRoadConnectedToDestination(near.getX(), near.getZ())) continue;

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
                        "[Ultreia] Early merge: path index {}/{} -> road ({}, {}), stopping paint here",
                        idx, path.size() - 1, near.getX(), near.getZ());
            }
            return;
        }

        // After the end-merge scan: detect mid-path sections that run parallel to existing roads.
        detectParallelGaps(baseR, absoluteMin, exitBlocks);
    }

    /**
     * Scans the full (non-merged) path for sections running within road-width of an existing road.
     * Marks those sections in {@code gapByIdx} so the painter skips them. Records gap boundary
     * path indices in {@code gapBoundary*} arrays so the junction reinforcement can
     * force-paint physical connectors at each entry/exit.
     */
    private void detectParallelGaps(int baseR, int absoluteMin, int exitBlocks) {
        if (roadIndex == null) return;

        // Proximity radius: fire when any existing road block is this close to our path center.
        int overlapRadius = Math.max(2, baseR + 2);
        // Suppress only runs longer than this to avoid spurious single-block skips.
        int minGapLen = Math.max(3, baseR * 2 + 2);

        // Stop before the end-merge region (if armed) and leave a clean tail.
        int scanEnd = active
                ? hitIndex - 1
                : (path.size() - 1 - Math.max(6, baseR + 3));
        if (scanEnd < absoluteMin + minGapLen) return;

        // Bridge-protection buffer: don't gap into or out of any forced bridge span.
        int bridgeBuffer = Math.max(0, exitBlocks + 2);

        BlockPos origin = path.get(0);
        int startMinDistSq = 16 * 16;

        // Per-index overlap state.
        boolean[] inOverlap = new boolean[path.size()];
        int[] nearX = new int[path.size()];
        int[] nearZ = new int[path.size()];

        for (int idx = absoluteMin; idx <= scanEnd; idx++) {
            BlockPos p = path.get(idx);
            // A built bridge the path crosses: the road stops at its start and resumes at its end.
            if (reusedBridgeXZ.contains(Pos2D.xzKey(p.getX(), p.getZ()))) {
                inOverlap[idx] = true;
                nearX[idx] = p.getX();
                nearZ[idx] = p.getZ();
                continue;
            }

            // Skip bridge-protected indices.
            boolean inBridge = false;
            for (RoadPaintSession.ForcedBridgeRef ref : forced) {
                if (idx >= ref.startIdx - bridgeBuffer && idx <= ref.endIdx + bridgeBuffer) {
                    inBridge = true;
                    break;
                }
            }
            if (inBridge) continue;

            BlockPos near = roadIndex.findNearestRoad(p.getX(), p.getZ(), overlapRadius);
            if (near == null) continue;

            long nk = Pos2D.xzKey(near.getX(), near.getZ());
            if (newlyPlaced.contains(nk)) continue;
            if (roadIndex.isBridgeXZ(near.getX(), near.getZ())) continue;
            if (protectedAreas != null && protectedAreas.contains(near.getX(), near.getZ())) continue;

            int odx = near.getX() - origin.getX();
            int odz = near.getZ() - origin.getZ();
            if (odx * odx + odz * odz < startMinDistSq) continue;

            inOverlap[idx] = true;
            nearX[idx] = near.getX();
            nearZ[idx] = near.getZ();
        }

        // Collect gap runs and mark gapByIdx.
        boolean[] gaps = new boolean[path.size()];
        // Temporary boundary storage: up to (runs * 2) entries; 64 should be more than enough.
        int maxBoundaries = 64;
        int[] bPathIdx = new int[maxBoundaries];
        int[] bRoadX   = new int[maxBoundaries];
        int[] bRoadZ   = new int[maxBoundaries];
        int bCount = 0;

        boolean anyGap = false;
        int runStart = -1;

        for (int idx = absoluteMin; idx <= scanEnd + 1; idx++) {
            boolean overlapping = idx <= scanEnd && inOverlap[idx];

            if (overlapping && runStart < 0) {
                runStart = idx;
            } else if (!overlapping && runStart >= 0) {
                int runEnd = idx - 1;
                int runLen = runEnd - runStart + 1;

                if (runLen >= minGapLen) {
                    for (int gi = runStart; gi <= runEnd; gi++) {
                        gaps[gi] = true;
                        anyGap = true;
                    }

                    // Entry boundary: last painted waypoint before gap → road.
                    if (runStart > absoluteMin && bCount < maxBoundaries - 1) {
                        bPathIdx[bCount] = runStart - 1;
                        bRoadX[bCount]   = nearX[runStart];
                        bRoadZ[bCount]   = nearZ[runStart];
                        bCount++;
                    }

                    // Exit boundary: road → first painted waypoint after gap.
                    if (runEnd + 1 < path.size() && bCount < maxBoundaries - 1) {
                        bPathIdx[bCount] = runEnd + 1;
                        bRoadX[bCount]   = nearX[runEnd];
                        bRoadZ[bCount]   = nearZ[runEnd];
                        bCount++;
                    }

                    if (UltreiaConfig.DEBUG.logPainting) {
                        Ultreia.LOGGER.info(
                                "[Ultreia] Parallel gap suppressed: path[{}..{}] len={} near=({},{})",
                                runStart, runEnd, runLen, nearX[runStart], nearZ[runStart]);
                    }
                }
                runStart = -1;
            }
        }

        if (!anyGap) return;

        gapByIdx = gaps;
        if (bCount > 0) {
            gapBoundaryPathIdx = java.util.Arrays.copyOf(bPathIdx, bCount);
            gapBoundaryRoadX   = java.util.Arrays.copyOf(bRoadX, bCount);
            gapBoundaryRoadZ   = java.util.Arrays.copyOf(bRoadZ, bCount);
            gapBoundaryCount   = bCount;
        }
    }

    private boolean isRoadConnectedToDestination(int x, int z) {
        if (roadNetwork == null || toVillage == null) return false;
        int tvx = toVillage.getX(), tvz = toVillage.getZ();
        for (RoadSegment seg : roadNetwork.getSegmentsNear(x, z, 20)) {
            if (villageMatches(seg.toVillage, tvx, tvz) || villageMatches(seg.fromVillage, tvx, tvz))
                return true;
        }
        return false;
    }

    private static boolean villageMatches(@Nullable BlockPos v, int x, int z) {
        if (v == null) return false;
        int dx = v.getX() - x, dz = v.getZ() - z;
        return dx * dx + dz * dz <= 256; // 16-block tolerance
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

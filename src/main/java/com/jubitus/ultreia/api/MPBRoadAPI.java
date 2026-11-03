package com.jubitus.ultreia.api;

import com.jubitus.ultreia.route.RoadType;
import com.jubitus.ultreia.roads.RoadIndex;
import com.jubitus.ultreia.roads.RoadNavigator;
import com.jubitus.ultreia.roads.RoadNetwork;
import com.jubitus.ultreia.roads.RoadSegment;
import com.jubitus.ultreia.roads.RoadWaypoint;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Public integration API for Ultreïa.
 *
 * External mods (e.g. Travellers) should guard access to this class with a presence check:
 *
 * <pre>
 *   private static final boolean MPB_PRESENT = isMPBPresent();
 *   private static boolean isMPBPresent() {
 *       try {
 *           Class.forName("com.jubitus.ultreia.api.MPBRoadAPI");
 *           return true;
 *       } catch (ClassNotFoundException e) {
 *           return false;
 *       }
 *   }
 * </pre>
 *
 * All methods are safe to call on the server thread. None force-load chunks.
 * Methods return null / false / -1 when data is unavailable.
 */
public final class MPBRoadAPI {

    private MPBRoadAPI() {}

    // -------------------------------------------------------------------------
    // Presence / road queries
    // -------------------------------------------------------------------------

    /** Returns true — used by callers to verify the class loaded successfully. */
    public static boolean isAvailable() {
        return true;
    }

    /** True if this XZ column is part of an MPB road or bridge deck. */
    public static boolean isOnRoad(WorldServer world, int x, int z) {
        return RoadIndex.get(world).isRoadXZ(x, z);
    }

    /** True if this XZ column is a bridge deck (a subset of road). */
    public static boolean isBridge(WorldServer world, int x, int z) {
        return RoadIndex.get(world).isBridgeXZ(x, z);
    }

    /**
     * Returns the Y coordinate of the road surface at (x, z), or -1 if unknown.
     * The Y comes from the persistent road index; no world scan is performed.
     */
    public static int getRoadY(WorldServer world, int x, int z) {
        return RoadIndex.get(world).getRoadY(x, z);
    }

    // -------------------------------------------------------------------------
    // Spatial queries — all chunk-load-safe
    // -------------------------------------------------------------------------

    /**
     * Finds the nearest road block to (x, z) within maxRadius blocks (Manhattan spiral).
     * Returns a BlockPos with the correct road surface Y, or null if none found.
     *
     * Does NOT force-load chunks; only queries the in-memory index.
     */
    @Nullable
    public static BlockPos findNearestRoadPos(WorldServer world, int x, int z, int maxRadius) {
        RoadIndex idx = RoadIndex.get(world);
        BlockPos raw = idx.findNearestRoad(x, z, maxRadius);
        if (raw == null) return null;
        return resolveY(idx, raw.getX(), raw.getZ());
    }

    /**
     * Finds the nearest bridge-deck block to (x, z) within maxRadius blocks.
     * Returns a BlockPos with the correct Y, or null if none found.
     */
    @Nullable
    public static BlockPos findNearestBridgePos(WorldServer world, int x, int z, int maxRadius) {
        RoadIndex idx = RoadIndex.get(world);
        return idx.findNearestBridge(x, z, maxRadius);
    }

    /**
     * Main routing API for road-following travel.
     *
     * Given a traveller currently at (fromX, fromZ) heading toward (toX, toZ),
     * projects {@code stepBlocks} ahead along that direction and searches within
     * {@code searchRadius} blocks for the nearest road block. Returns a usable
     * waypoint with correct Y, or null if no road found in that area.
     *
     * Call this in place of your straight-line waypoint projection. If null is
     * returned, fall back to the normal (no-road) waypoint logic.
     *
     * Recommended values: stepBlocks = 16..48, searchRadius = 20.
     *
     * Thread-safety: call only on the server thread.
     */
    @Nullable
    public static BlockPos findNextRoadWaypointToward(
            WorldServer world,
            int fromX, int fromZ,
            int toX, int toZ,
            int stepBlocks,
            int searchRadius
    ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0) return null;

        // Clamp step so we don't overshoot the destination
        int clampedStep = (int) Math.min(stepBlocks, len * 0.9);
        if (clampedStep < 4) return null;

        double nx = dx / len;
        double nz = dz / len;

        int projX = fromX + (int) Math.round(nx * clampedStep);
        int projZ = fromZ + (int) Math.round(nz * clampedStep);

        RoadIndex idx = RoadIndex.get(world);
        BlockPos raw = idx.findNearestRoad(projX, projZ, searchRadius);
        if (raw == null) return null;

        return resolveY(idx, raw.getX(), raw.getZ());
    }

    /**
     * Finds a road block suitable for spawning at, near (x, z) within maxRadius.
     * Prefers non-bridge road blocks (on solid ground).
     * Returns a BlockPos with correct Y, or null if no road found.
     */
    @Nullable
    public static BlockPos findRoadSpawnPos(WorldServer world, int x, int z, int maxRadius) {
        RoadIndex idx = RoadIndex.get(world);

        // Search in expanding rings; skip bridge deck blocks for spawn preference
        for (int r = 0; r <= maxRadius; r++) {
            int minX = x - r, maxX = x + r, minZ = z - r, maxZ = z + r;

            for (int xx = minX; xx <= maxX; xx++) {
                if (idx.isRoadXZ(xx, minZ) && !idx.isBridgeXZ(xx, minZ)) {
                    BlockPos p = resolveY(idx, xx, minZ);
                    if (p != null) return p;
                }
                if (r > 0 && idx.isRoadXZ(xx, maxZ) && !idx.isBridgeXZ(xx, maxZ)) {
                    BlockPos p = resolveY(idx, xx, maxZ);
                    if (p != null) return p;
                }
            }
            for (int zz = minZ + 1; zz <= maxZ - 1; zz++) {
                if (idx.isRoadXZ(minX, zz) && !idx.isBridgeXZ(minX, zz)) {
                    BlockPos p = resolveY(idx, minX, zz);
                    if (p != null) return p;
                }
                if (r > 0 && idx.isRoadXZ(maxX, zz) && !idx.isBridgeXZ(maxX, zz)) {
                    BlockPos p = resolveY(idx, maxX, zz);
                    if (p != null) return p;
                }
            }
        }

        // Fall back: accept bridge deck if that's all we have
        return findNearestRoadPos(world, x, z, maxRadius);
    }

    // -------------------------------------------------------------------------
    // Route planning — graph-level A* over the road segment network
    // -------------------------------------------------------------------------

    /**
     * Plan a route through the road segment network from near (fromX,fromZ) to near (toX,toZ).
     *
     * Returns an ordered list of road-surface BlockPos (Y = road block surface).
     * The caller should add +1 to Y to get entity feet position.
     * Returns an empty list if no route could be found.
     *
     * maxSearchRadius controls how far from from/to we look for the nearest road entry point.
     * Recommended: 256 for long-distance travel, 64 for short hops.
     *
     * Thread-safety: call only on the server thread.
     */
    public static List<BlockPos> findRoadRoute(WorldServer world,
                                               int fromX, int fromZ,
                                               int toX, int toZ,
                                               int maxSearchRadius) {
        RoadNetwork network = RoadNetwork.get(world);
        List<RoadWaypoint> waypoints = network.findRoute(fromX, fromZ, toX, toZ, maxSearchRadius);
        if (waypoints.isEmpty()) return Collections.emptyList();
        List<BlockPos> result = new ArrayList<>(waypoints.size());
        for (RoadWaypoint wp : waypoints) {
            result.add(new BlockPos(wp.x, wp.y, wp.z));
        }
        return result;
    }

    /**
     * Same as {@link #findRoadRoute} but structured as legs.
     *
     * Each inner list is a physically contiguous road polyline; a new leg starts at every
     * village gate crossing (the previous leg ends at one gate, the next starts at another,
     * and the entity must cross the village in between off-road).
     *
     * Returns an empty list if no route could be found.
     * Thread-safety: call only on the server thread.
     */
    public static List<List<BlockPos>> findRoadRouteLegs(WorldServer world,
                                                         int fromX, int fromZ,
                                                         int toX, int toZ,
                                                         int maxSearchRadius) {
        RoadNetwork network = RoadNetwork.get(world);
        List<List<RoadWaypoint>> legs =
                RoadNavigator.findRouteLegs(network, fromX, fromZ, toX, toZ, maxSearchRadius);
        if (legs.isEmpty()) return Collections.emptyList();
        List<List<BlockPos>> result = new ArrayList<>(legs.size());
        for (List<RoadWaypoint> leg : legs) {
            List<BlockPos> conv = new ArrayList<>(leg.size());
            for (RoadWaypoint wp : leg) conv.add(new BlockPos(wp.x, wp.y, wp.z));
            result.add(conv);
        }
        return result;
    }

    /**
     * Returns village center positions (XZ, Y=0) that are endpoints of any road segment.
     * Use this to filter village destinations to those reachable via the road network.
     * Returns an empty list if no road segments have village endpoints.
     */
    public static List<BlockPos> getConnectedVillages(WorldServer world) {
        RoadNetwork network = RoadNetwork.get(world);
        Set<Long> seen = new HashSet<>();
        List<BlockPos> result = new ArrayList<>();
        for (RoadSegment seg : network.allSegments()) {
            if (seg == null) continue;
            if (seg.fromVillage != null) {
                long key = RoadWaypoint.packXZ(seg.fromVillage.getX(), seg.fromVillage.getZ());
                if (seen.add(key)) result.add(seg.fromVillage);
            }
            if (seg.toVillage != null) {
                long key = RoadWaypoint.packXZ(seg.toVillage.getX(), seg.toVillage.getZ());
                if (seen.add(key)) result.add(seg.toVillage);
            }
        }
        return result;
    }

    /**
     * Every road and bridge segment touching the XZ rectangle, each as its full ordered waypoint
     * polyline (road surface Y). Lets a route planner treat roads as cheap ground inside its own
     * search, including roads that don't reach its destination. Server thread only.
     */
    public static List<List<BlockPos>> getRoadPolylinesInBox(WorldServer world,
                                                             int minX, int minZ, int maxX, int maxZ) {
        RoadNetwork network = RoadNetwork.get(world);
        int cx = (minX + maxX) >> 1;
        int cz = (minZ + maxZ) >> 1;
        int radius = Math.max(maxX - minX, maxZ - minZ) / 2 + 16;
        List<List<BlockPos>> result = new ArrayList<>();
        for (RoadSegment seg : network.getSegmentsNear(cx, cz, radius)) {
            if (seg == null || seg.waypoints.size() < 2) continue;
            boolean inside = false;
            for (RoadWaypoint wp : seg.waypoints) {
                if (wp.x >= minX && wp.x <= maxX && wp.z >= minZ && wp.z <= maxZ) {
                    inside = true;
                    break;
                }
            }
            if (!inside) continue;
            List<BlockPos> poly = new ArrayList<>(seg.waypoints.size());
            for (RoadWaypoint wp : seg.waypoints) poly.add(new BlockPos(wp.x, wp.y, wp.z));
            result.add(poly);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Map rendering
    // -------------------------------------------------------------------------

    /**
     * Changes whenever the set of road segments changes (added, removed, cleared, loaded).
     * Never persisted; compare for inequality only. Server thread only.
     */
    public static long getRoadNetworkRevision(WorldServer world) {
        return RoadNetwork.get(world).getRevision();
    }

    /**
     * Every road and bridge segment of this world, one int[] each:
     * [kind, widthBlocks, isBridge, x0, z0, x1, z1, ...]
     * kind: 0 = village road (RoadType.VILLAGE_MAIN), 1 = lone trail (RoadType.LONE_TRAIL).
     * widthBlocks: widest width painted for this segment (deck width for a bridge).
     * isBridge: 1 for a bridge segment, else 0.
     * Then the centerline waypoints in order (x, z pairs). Server thread only.
     */
    public static List<int[]> getRoadSegmentsForMap(WorldServer world) {
        List<RoadSegment> all = RoadNetwork.get(world).allSegments();
        List<int[]> result = new ArrayList<>(all.size());
        for (RoadSegment seg : all) {
            if (seg == null || seg.waypoints.isEmpty()) continue;
            int[] out = new int[3 + seg.waypoints.size() * 2];
            out[0] = seg.roadType == RoadType.LONE_TRAIL ? 1 : 0;
            out[1] = seg.widthBlocks;
            out[2] = seg.isBridge ? 1 : 0;
            int k = 3;
            for (RoadWaypoint wp : seg.waypoints) {
                out[k++] = wp.x;
                out[k++] = wp.z;
            }
            result.add(out);
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Returns a BlockPos with the known road Y for (x, z), or null if Y is unknown.
     * Y comes purely from the persistent index — no world access needed.
     */
    @Nullable
    private static BlockPos resolveY(RoadIndex idx, int x, int z) {
        int y = idx.getRoadY(x, z);
        if (y < 0) return null;
        return new BlockPos(x, y, z);
    }
}

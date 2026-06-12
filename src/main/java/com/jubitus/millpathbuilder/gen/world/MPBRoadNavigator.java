package com.jubitus.millpathbuilder.gen.world;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Entity navigation over the MPBRoadNetwork.
 *
 * Provides A* routing through the stored road segment graph.
 * Intended for use by entity mods that want to navigate along MPB roads.
 *
 * Usage:
 *   MPBRoadNetwork network = MPBRoadNetwork.get(world);
 *   List<RoadWaypoint> path = MPBRoadNavigator.findRoute(network, fromX, fromZ, toX, toZ, 256);
 *   // Walk path waypoints in order
 */
public final class MPBRoadNavigator {

    /** Physical endpoint proximity fallback (for segments without village data). */
    private static final int JUNCTION_SNAP_RADIUS = 12;
    /** Village center match radius — segments sharing a village within this distance are adjacent. */
    private static final int VILLAGE_SNAP_RADIUS = 32;
    /** Max iterations for the routing A*. */
    private static final int MAX_ITER = 50_000;
    /**
     * Consecutive route parts whose join points are farther apart than this start a new leg.
     * Physical junctions join within JUNCTION_SNAP_RADIUS; village gate crossings are 50-200 blocks.
     */
    private static final int LEG_BREAK_DIST = 24;

    private MPBRoadNavigator() {}

    /**
     * Find a route from near (fromX,fromZ) to near (toX,toZ) through the road network.
     *
     * @param network         The road network to route through.
     * @param fromX fromZ     Starting world position.
     * @param toX   toZ       Destination world position.
     * @param maxSearchRadius Maximum radius to snap from/to a road segment.
     * @return Ordered list of waypoints to follow, or empty if no route found.
     */
    public static List<RoadWaypoint> findRoute(MPBRoadNetwork network,
                                               int fromX, int fromZ,
                                               int toX, int toZ,
                                               int maxSearchRadius) {
        List<List<RoadWaypoint>> legs = findRouteLegs(network, fromX, fromZ, toX, toZ, maxSearchRadius);
        if (legs.isEmpty()) return Collections.emptyList();
        List<RoadWaypoint> flat = new ArrayList<>();
        for (List<RoadWaypoint> leg : legs) flat.addAll(leg);
        return flat;
    }

    /**
     * Same as {@link #findRoute} but keeps the route structured as legs.
     *
     * Each leg is a physically contiguous polyline the entity can walk without leaving the road.
     * A new leg starts wherever two consecutive route parts do not physically connect — i.e. at
     * village gate crossings, where the entity must traverse the village to reach the next road.
     */
    public static List<List<RoadWaypoint>> findRouteLegs(MPBRoadNetwork network,
                                                         int fromX, int fromZ,
                                                         int toX, int toZ,
                                                         int maxSearchRadius) {
        if (network == null) return Collections.emptyList();

        // Find entry and exit waypoints
        RoadWaypoint entry = network.findNearestWaypoint(fromX, fromZ, maxSearchRadius);
        RoadWaypoint exit  = network.findNearestWaypoint(toX, toZ, maxSearchRadius);
        if (entry == null || exit == null) return Collections.emptyList();

        // Find which segments contain these waypoints
        List<RoadSegment> allSegs = network.allSegments();

        int entrySegId = findSegmentContaining(allSegs, entry);
        int exitSegId  = findSegmentContaining(allSegs, exit);
        if (entrySegId < 0 || exitSegId < 0) return Collections.emptyList();
        if (entrySegId == exitSegId) {
            List<RoadWaypoint> single = extractSubpath(allSegs.get(entrySegId), entry, exit);
            return single.isEmpty() ? Collections.emptyList()
                    : Collections.singletonList(single);
        }

        // Build junction graph and run Dijkstra over segments
        Map<Integer, List<int[]>> adjacency = buildJunctionGraph(allSegs);

        // Dijkstra: node = segment ID, cost = accumulated Euclidean distance
        Map<Integer, Double> dist = new HashMap<>();
        Map<Integer, Integer> prev = new HashMap<>();
        PriorityQueue<int[]> pq = new PriorityQueue<>(Comparator.comparingDouble(a -> a[1]));

        dist.put(entrySegId, 0.0);
        pq.offer(new int[]{entrySegId, 0});

        int iter = 0;
        while (!pq.isEmpty() && iter++ < MAX_ITER) {
            int[] cur = pq.poll();
            int curId = cur[0];
            double curDist = cur[1] / 10.0;

            if (curId == exitSegId) break;

            Double best = dist.get(curId);
            if (best != null && curDist > best + 0.001) continue;

            List<int[]> neighbors = adjacency.getOrDefault(curId, Collections.emptyList());
            for (int[] nb : neighbors) {
                int nbId = nb[0];
                double edgeCost = nb[1]; // encoded as int, actual = edgeCost/10.0
                double newDist = curDist + edgeCost / 10.0;
                Double nbBest = dist.get(nbId);
                if (nbBest == null || newDist < nbBest - 0.001) {
                    dist.put(nbId, newDist);
                    prev.put(nbId, curId);
                    pq.offer(new int[]{nbId, (int) (newDist * 10)});
                }
            }
        }

        if (!prev.containsKey(exitSegId)) {
            return Collections.emptyList();
        }

        // Reconstruct segment path
        List<Integer> segPath = new ArrayList<>();
        int cur = exitSegId;
        int guard = 0;
        while (cur != entrySegId && guard++ < 10_000) {
            segPath.add(cur);
            Integer p = prev.get(cur);
            if (p == null) return Collections.emptyList();
            cur = p;
        }
        segPath.add(entrySegId);
        Collections.reverse(segPath);

        // Stitch waypoints from the segment path, splitting into legs at physical breaks
        List<List<RoadWaypoint>> legs = new ArrayList<>();
        RoadWaypoint lastPoint = null;

        for (int i = 0; i < segPath.size(); i++) {
            int segId = segPath.get(i);
            if (segId < 0 || segId >= allSegs.size()) continue;
            RoadSegment seg = allSegs.get(segId);
            if (seg == null) continue;

            List<RoadWaypoint> part;
            if (i == 0) {
                // Use next-segment proximity to decide direction, same as middle segments.
                // The "far end" heuristic in extractFromWaypoint fails when the spawn position
                // happens to be closer to the wrong end of the segment.
                RoadSegment nextSeg = null;
                int nextSegId = segPath.get(1);
                if (nextSegId >= 0 && nextSegId < allSegs.size()) nextSeg = allSegs.get(nextSegId);
                part = orderToConnect(seg, entry, nextSeg);
            } else if (i == segPath.size() - 1) {
                // Last segment: from start to exit waypoint
                part = extractToWaypoint(seg, exit, lastPoint);
            } else {
                // Middle segment: from entry point toward next segment
                RoadSegment nextSeg = null;
                int nextSegId = segPath.get(i + 1);
                if (nextSegId >= 0 && nextSegId < allSegs.size()) nextSeg = allSegs.get(nextSegId);
                part = orderToConnect(seg, lastPoint, nextSeg);
            }
            if (part.isEmpty()) continue;

            appendPart(legs, part);
            lastPoint = part.get(part.size() - 1);
        }

        return legs;
    }

    /** Appends a route part, starting a new leg if it does not physically connect to the last one. */
    private static void appendPart(List<List<RoadWaypoint>> legs, List<RoadWaypoint> part) {
        if (legs.isEmpty()) {
            legs.add(new ArrayList<>(part));
            return;
        }
        List<RoadWaypoint> currentLeg = legs.get(legs.size() - 1);
        RoadWaypoint legEnd = currentLeg.get(currentLeg.size() - 1);
        RoadWaypoint partStart = part.get(0);
        if (distSq(legEnd.x, legEnd.z, partStart.x, partStart.z) > LEG_BREAK_DIST * LEG_BREAK_DIST) {
            legs.add(new ArrayList<>(part));
            return;
        }
        int from = (legEnd.x == partStart.x && legEnd.z == partStart.z) ? 1 : 0;
        for (int i = from; i < part.size(); i++) currentLeg.add(part.get(i));
    }

    // --- Internal helpers ---

    private static int findSegmentContaining(List<RoadSegment> segs, RoadWaypoint wp) {
        long threshold = (long) JUNCTION_SNAP_RADIUS * JUNCTION_SNAP_RADIUS;
        int bestId = -1;
        long bestDist = Long.MAX_VALUE;

        for (RoadSegment seg : segs) {
            if (seg == null) continue;
            for (RoadWaypoint w : seg.waypoints) {
                long dx = w.x - wp.x, dz = w.z - wp.z;
                long d2 = dx * dx + dz * dz;
                if (d2 <= threshold && d2 < bestDist) {
                    bestDist = d2;
                    bestId = seg.id;
                }
            }
        }
        return bestId;
    }

    /**
     * Two segments are adjacent if they share a village endpoint, or if an endpoint of one
     * segment is within JUNCTION_SNAP_RADIUS of any waypoint of the other.
     * The second check handles early-merge junctions where a LONE_TRAIL terminates
     * at a mid-point of a VILLAGE_MAIN rather than at its endpoint.
     */
    private static Map<Integer, List<int[]>> buildJunctionGraph(List<RoadSegment> segs) {
        Map<Integer, List<int[]>> adj = new HashMap<>();

        for (int i = 0; i < segs.size(); i++) {
            RoadSegment a = segs.get(i);
            if (a == null || a.waypoints.size() < 2) continue;

            for (int j = i + 1; j < segs.size(); j++) {
                RoadSegment b = segs.get(j);
                if (b == null || b.waypoints.size() < 2) continue;

                if (!shareVillage(a, b) && !endpointsClose(a, b)) continue;

                int cost = (int) ((a.lengthBlocks() + b.lengthBlocks()) * 5); // *10/2
                adj.computeIfAbsent(a.id, k -> new ArrayList<>()).add(new int[]{b.id, cost});
                adj.computeIfAbsent(b.id, k -> new ArrayList<>()).add(new int[]{a.id, cost});
            }
        }
        return adj;
    }

    /** True if the two segments share a village endpoint (within VILLAGE_SNAP_RADIUS). */
    private static boolean shareVillage(RoadSegment a, RoadSegment b) {
        return villageMatch(a.fromVillage, b.fromVillage)
            || villageMatch(a.fromVillage, b.toVillage)
            || villageMatch(a.toVillage,   b.fromVillage)
            || villageMatch(a.toVillage,   b.toVillage);
    }

    private static boolean villageMatch(@Nullable net.minecraft.util.math.BlockPos v1,
                                        @Nullable net.minecraft.util.math.BlockPos v2) {
        if (v1 == null || v2 == null) return false;
        int dx = v1.getX() - v2.getX();
        int dz = v1.getZ() - v2.getZ();
        return dx * dx + dz * dz <= VILLAGE_SNAP_RADIUS * VILLAGE_SNAP_RADIUS;
    }

    /**
     * Returns true if an endpoint of A is within JUNCTION_SNAP_RADIUS of any waypoint of B,
     * or vice versa. This covers both normal endpoint-to-endpoint junctions and early-merge
     * cases where a LONE_TRAIL's endpoint lands on the middle of a VILLAGE_MAIN.
     */
    private static boolean endpointsClose(RoadSegment a, RoadSegment b) {
        int snapSq = JUNCTION_SNAP_RADIUS * JUNCTION_SNAP_RADIUS;
        int axS = a.startX(), azS = a.startZ(), axE = a.endX(), azE = a.endZ();
        int bxS = b.startX(), bzS = b.startZ(), bxE = b.endX(), bzE = b.endZ();
        for (RoadWaypoint wb : b.waypoints) {
            int dxS = axS - wb.x, dzS = azS - wb.z;
            if (dxS * dxS + dzS * dzS <= snapSq) return true;
            int dxE = axE - wb.x, dzE = azE - wb.z;
            if (dxE * dxE + dzE * dzE <= snapSq) return true;
        }
        for (RoadWaypoint wa : a.waypoints) {
            int dxS = bxS - wa.x, dzS = bzS - wa.z;
            if (dxS * dxS + dzS * dzS <= snapSq) return true;
            int dxE = bxE - wa.x, dzE = bzE - wa.z;
            if (dxE * dxE + dzE * dzE <= snapSq) return true;
        }
        return false;
    }

    private static List<RoadWaypoint> extractSubpath(RoadSegment seg, RoadWaypoint from, RoadWaypoint to) {
        int fromIdx = nearestWaypointIndex(seg, from.x, from.z);
        int toIdx   = nearestWaypointIndex(seg, to.x, to.z);
        if (fromIdx < 0 || toIdx < 0) return Collections.emptyList();

        List<RoadWaypoint> sub = new ArrayList<>();
        if (fromIdx <= toIdx) {
            for (int i = fromIdx; i <= toIdx; i++) sub.add(seg.waypoints.get(i));
        } else {
            for (int i = fromIdx; i >= toIdx; i--) sub.add(seg.waypoints.get(i));
        }
        return sub;
    }

    private static List<RoadWaypoint> extractFromWaypoint(RoadSegment seg, RoadWaypoint from, @Nullable RoadWaypoint to) {
        int fromIdx = nearestWaypointIndex(seg, from.x, from.z);
        if (fromIdx < 0) return Collections.emptyList();

        if (to != null) return extractSubpath(seg, from, to);

        // Determine direction: pick the end farther from from
        int toStart = distSq(seg.startX(), seg.startZ(), from.x, from.z);
        int toEnd   = distSq(seg.endX(), seg.endZ(), from.x, from.z);
        boolean forward = toEnd >= toStart;

        List<RoadWaypoint> result = new ArrayList<>();
        if (forward) {
            for (int i = fromIdx; i < seg.waypoints.size(); i++) result.add(seg.waypoints.get(i));
        } else {
            for (int i = fromIdx; i >= 0; i--) result.add(seg.waypoints.get(i));
        }
        return result;
    }

    private static List<RoadWaypoint> extractToWaypoint(RoadSegment seg, RoadWaypoint to, @Nullable RoadWaypoint prevEnd) {
        int toIdx = nearestWaypointIndex(seg, to.x, to.z);
        if (toIdx < 0) return Collections.emptyList();

        int startIdx;
        if (prevEnd != null) {
            int fromStart = distSq(seg.startX(), seg.startZ(), prevEnd.x, prevEnd.z);
            int fromEnd   = distSq(seg.endX(), seg.endZ(), prevEnd.x, prevEnd.z);
            startIdx = fromStart <= fromEnd ? 0 : seg.waypoints.size() - 1;
        } else {
            startIdx = 0;
        }

        List<RoadWaypoint> result = new ArrayList<>();
        if (startIdx <= toIdx) {
            for (int i = startIdx; i <= toIdx; i++) result.add(seg.waypoints.get(i));
        } else {
            for (int i = startIdx; i >= toIdx; i--) result.add(seg.waypoints.get(i));
        }
        return result;
    }

    /**
     * Returns the waypoints of {@code seg} starting from the entry point (nearest waypoint to
     * {@code prevEnd}) and directed toward the end of {@code seg} that is closest to
     * {@code nextSeg}. This correctly handles early-merge junctions where the entry point is
     * in the middle of the segment, avoiding the U-turn that the old endpoint-distance heuristic
     * would produce.
     */
    private static List<RoadWaypoint> orderToConnect(RoadSegment seg,
                                                     @Nullable RoadWaypoint prevEnd,
                                                     @Nullable RoadSegment nextSeg) {
        if (seg.waypoints.isEmpty()) return Collections.emptyList();

        // Start from the nearest waypoint to where we came from, not from an endpoint.
        int entryIdx = (prevEnd != null) ? nearestWaypointIndex(seg, prevEnd.x, prevEnd.z) : 0;

        boolean forward;
        if (nextSeg != null && !nextSeg.waypoints.isEmpty()) {
            // Use next-segment proximity to decide direction so that early-merge
            // mid-segment entries go toward the correct end.
            int segStartToNext = Math.min(
                    distSq(seg.startX(), seg.startZ(), nextSeg.startX(), nextSeg.startZ()),
                    distSq(seg.startX(), seg.startZ(), nextSeg.endX(), nextSeg.endZ()));
            int segEndToNext = Math.min(
                    distSq(seg.endX(), seg.endZ(), nextSeg.startX(), nextSeg.startZ()),
                    distSq(seg.endX(), seg.endZ(), nextSeg.endX(), nextSeg.endZ()));
            forward = segEndToNext < segStartToNext;
        } else if (prevEnd != null) {
            // No next segment: go away from where we came.
            int toStart = distSq(seg.startX(), seg.startZ(), prevEnd.x, prevEnd.z);
            int toEnd   = distSq(seg.endX(), seg.endZ(), prevEnd.x, prevEnd.z);
            forward = toEnd >= toStart;
        } else {
            forward = true;
        }

        List<RoadWaypoint> result = new ArrayList<>();
        if (forward) {
            for (int i = entryIdx; i < seg.waypoints.size(); i++) result.add(seg.waypoints.get(i));
        } else {
            for (int i = entryIdx; i >= 0; i--) result.add(seg.waypoints.get(i));
        }
        return result;
    }

    private static int nearestWaypointIndex(RoadSegment seg, int x, int z) {
        int best = -1;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < seg.waypoints.size(); i++) {
            RoadWaypoint wp = seg.waypoints.get(i);
            long dx = wp.x - x, dz = wp.z - z;
            long d2 = dx * dx + dz * dz;
            if (d2 < bestDist) { bestDist = d2; best = i; }
        }
        return best;
    }

    private static int distSq(int x1, int z1, int x2, int z2) {
        int dx = x1 - x2, dz = z1 - z2;
        return dx * dx + dz * dz;
    }
}

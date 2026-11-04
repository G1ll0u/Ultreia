package com.jubitus.ultreia.route;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.bridge.BridgeSpan;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.roads.RoadNetwork;
import com.jubitus.ultreia.roads.RoadSegment;
import com.jubitus.ultreia.roads.RoadWaypoint;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.terrain.TerrainView;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.jubitus.ultreia.route.GridMoves.DIR_NONE;
import static com.jubitus.ultreia.route.SearchState.stateKey;
import static com.jubitus.ultreia.route.SearchState.stateX;
import static com.jubitus.ultreia.route.SearchState.stateZ;

/**
 * Debug trace of one A* search (logPathfinding): how much of the found path follows existing roads, where it runs
 * beside one without following it, and which offered roads it did not take. With logBridges, each new bridge and
 * the nearest built one. One block of lines per found path.
 */
final class ReuseTrace {

    // Path nodes this close (blocks) to an existing road without being on it run alongside it.
    private static final int ALONGSIDE_RADIUS = 8;
    private static final int ALONGSIDE_LOG_LIMIT = 3;
    private static final int OFFER_LOG_LIMIT = 4;
    // Radius (blocks) in which a new bridge reports the nearest built bridge end.
    private static final int NEAR_BRIDGE_RADIUS = 36;

    /** Cheapest offer of one segment over the whole search. */
    private static final class Offer {
        int offered, lost;
        double best = Double.POSITIVE_INFINITY;
        double gEntry;
        int entryX, entryZ;
        NetworkMoves.Move move;
        long landingState;
    }

    private final Int2ObjectOpenHashMap<Offer> offers = new Int2ObjectOpenHashMap<>();
    private int rejectedFollowing = 0;

    /** A network move was evaluated; {@code relaxed} is false when its landing already had a cheaper way. */
    void offer(NetworkMoves.Move mv, int entryX, int entryZ, double gEntry, double tentative, long landingState,
               boolean relaxed) {
        if (!UltreiaConfig.DEBUG.logPathfinding) return;
        Offer o = offers.get(mv.segment.id);
        if (o == null) {
            o = new Offer();
            offers.put(mv.segment.id, o);
        }
        o.offered++;
        if (!relaxed) o.lost++;
        if (tentative < o.best) {
            o.best = tentative;
            o.gEntry = gEntry;
            o.entryX = entryX;
            o.entryZ = entryZ;
            o.move = mv;
            o.landingState = landingState;
        }
    }

    /** A found path that followed a road was refused by the whole-path check. */
    void countRejectedFollowing() {
        rejectedFollowing++;
    }

    /** Logs the trace of an accepted path; {@code states} are its A* states from start to end. */
    void logFoundPath(SearchState st, List<Long> states, TerrainView sampler, RoadType roadType,
                      @Nullable RoadNetwork network, int step) {
        if (UltreiaConfig.DEBUG.logPathfinding) logRoads(st, states, sampler, roadType, step);
        if (UltreiaConfig.DEBUG.logBridges) logNewBridges(st, states, network);
    }

    // --- Roads -----------------------------------------------------------------------------------

    private static final class Stretch {
        int fromIdx, toIdx;
        double length;
        int minGap = Integer.MAX_VALUE, maxGap = 0;
        int minGapIdx;
        BlockPos minGapRoad;
    }

    private void logRoads(SearchState st, List<Long> states, TerrainView sampler, RoadType roadType, int step) {
        double followed = 0, onRoad = 0, alongside = 0, free = 0, newBridgeBlocks = 0;
        int follows = 0, newBridges = 0;
        List<Stretch> stretches = new ArrayList<>();
        StringBuilder followLines = new StringBuilder();
        Stretch open = null;

        for (int i = 1; i < states.size(); i++) {
            long k = states.get(i);
            long pk = states.get(i - 1);
            int x = stateX(k), z = stateZ(k);

            NetworkMoves.Move mv = st.cameFollow.get(k);
            if (mv != null) {
                follows++;
                followed += mv.lengthBlocks;
                open = null;
                appendFollow(followLines, mv, st.gScore.get(k) - st.gScore.get(pk));
                continue;
            }
            double len = Math.sqrt(Pos2D.distSq(stateX(pk), stateZ(pk), x, z));
            if (st.cameBridge.containsKey(k)) {
                newBridges++;
                newBridgeBlocks += len;
                open = null;
                continue;
            }
            if (sampler.isExistingRoadXZ(x, z)) {
                onRoad += len;
                open = null;
                continue;
            }
            BlockPos road = sampler.findNearestExistingRoad(x, z, ALONGSIDE_RADIUS);
            if (road == null) {
                free += len;
                open = null;
                continue;
            }

            alongside += len;
            int gap = Math.max(Math.abs(road.getX() - x), Math.abs(road.getZ() - z));
            if (open == null) {
                open = new Stretch();
                open.fromIdx = i - 1;
                stretches.add(open);
            }
            open.toIdx = i;
            open.length += len;
            open.maxGap = Math.max(open.maxGap, gap);
            if (gap < open.minGap) {
                open.minGap = gap;
                open.minGapIdx = i;
                open.minGapRoad = road;
            }
        }

        long first = states.get(0), last = states.get(states.size() - 1);
        StringBuilder sb = new StringBuilder();
        sb.append("[Ultreia reuse] path (").append(stateX(first)).append(',').append(stateZ(first))
                .append(") -> (").append(stateX(last)).append(',').append(stateZ(last)).append(") ").append(roadType)
                .append(" step=").append(step)
                .append(": blocks followed on the network=").append(round(followed)).append(" (").append(follows).append(" moves)")
                .append(", on an existing road cell=").append(round(onRoad))
                .append(", alongside one (within ").append(ALONGSIDE_RADIUS).append(")=").append(round(alongside))
                .append(", away from roads=").append(round(free))
                .append(", new bridges=").append(newBridges).append(" (").append(round(newBridgeBlocks)).append(" blocks)")
                .append("; existingRoadCost=").append(UltreiaConfig.PATHFINDING.existingRoadCost)
                .append(", found paths through a followed road refused by the whole-path check=").append(rejectedFollowing)
                .append(followLines);

        stretches.sort(Comparator.comparingDouble((Stretch s) -> s.length).reversed());
        for (int n = 0; n < Math.min(ALONGSIDE_LOG_LIMIT, stretches.size()); n++) {
            appendStretch(sb, st, states, sampler, stretches.get(n), step);
        }
        if (stretches.size() > ALONGSIDE_LOG_LIMIT) {
            sb.append("\n  ... ").append(stretches.size() - ALONGSIDE_LOG_LIMIT).append(" shorter stretches alongside a road");
        }
        appendUnusedOffers(sb, st, states);
        Ultreia.LOGGER.info(sb.toString());
    }

    private static void appendFollow(StringBuilder sb, NetworkMoves.Move mv, double cost) {
        RoadWaypoint a = mv.segment.waypoints.get(mv.fromIdx);
        RoadWaypoint b = mv.segment.waypoints.get(mv.toIdx);
        sb.append("\n  followed ").append(mv.segment.isBridge ? "bridge" : "road").append(" seg=").append(mv.segment.id)
                .append(' ').append(mv.segment.roadType)
                .append(" (").append(a.x).append(',').append(a.y).append(',').append(a.z).append(") -> (")
                .append(b.x).append(',').append(b.y).append(',').append(b.z).append(") ")
                .append(round(mv.lengthBlocks)).append(" blocks, cost ").append(round(cost))
                .append(" = hop ").append(round(mv.hop)).append(" + follow ").append(round(mv.follow))
                .append(" + turn ").append(round(mv.turn));
    }

    /**
     * One stretch alongside a road: where it runs, and at its closest point what A* knew of the road cell
     * on its own grid (forbidden, extra cost, cheapest cost reached there) versus the path node.
     */
    private static void appendStretch(StringBuilder sb, SearchState st, List<Long> states, TerrainView sampler,
                                      Stretch s, int step) {
        long from = states.get(s.fromIdx), to = states.get(s.toIdx), at = states.get(s.minGapIdx);
        int ax = stateX(at), az = stateZ(at);
        BlockPos r = s.minGapRoad;
        int lx = ax + (int) Math.round((r.getX() - ax) / (double) step) * step;
        int lz = az + (int) Math.round((r.getZ() - az) / (double) step) * step;

        sb.append("\n  alongside (").append(stateX(from)).append(',').append(stateZ(from))
                .append(") -> (").append(stateX(to)).append(',').append(stateZ(to)).append(") ")
                .append(round(s.length)).append(" blocks, gap ").append(s.minGap).append("..").append(s.maxGap)
                .append(" | path node (").append(ax).append(',').append(az).append(") g=").append(round(st.gScore.get(at)))
                .append(" extra=").append(round(extraAt(sampler, ax, az)))
                .append(" | road (").append(r.getX()).append(',').append(r.getZ()).append(") grid cell (")
                .append(lx).append(',').append(lz).append(") onRoad=").append(sampler.isExistingRoadXZ(lx, lz));

        TerrainSampler.Sample ls = sampler.sample(lx, lz);
        if (ls == null) {
            sb.append(" sample=null");
            return;
        }
        if (sampler.forbidden(ls, TerrainSampler.Mode.PATHFINDING)) {
            sb.append(" forbidden=").append(sampler.forbiddenReason(ls, TerrainSampler.Mode.PATHFINDING));
            return;
        }
        double bestG = Double.POSITIVE_INFINITY;
        boolean closed = false;
        for (int d = 0; d <= DIR_NONE; d++) {
            long k = stateKey(lx, lz, d);
            bestG = Math.min(bestG, st.gScore.get(k));
            closed |= st.closed.contains(k);
        }
        sb.append(" extra=").append(round(extraAt(sampler, lx, lz)))
                .append(" surfY=").append(ls.surfaceY)
                .append(Double.isFinite(bestG) ? " cheapestG=" + round(bestG) : " never reached")
                .append(closed ? " expanded" : "");
    }

    /** Segments offered during the search but not in the found path: cheapest offer vs the path near its landing. */
    private void appendUnusedOffers(StringBuilder sb, SearchState st, List<Long> states) {
        List<Offer> list = new ArrayList<>();
        for (Offer o : offers.values()) {
            boolean used = false;
            for (int i = 1; i < states.size() && !used; i++) {
                NetworkMoves.Move mv = st.cameFollow.get(states.get(i).longValue());
                used = mv != null && mv.segment.id == o.move.segment.id;
            }
            if (!used) list.add(o);
        }
        list.sort(Comparator.comparingDouble((Offer o) -> o.best));
        for (int n = 0; n < Math.min(OFFER_LOG_LIMIT, list.size()); n++) {
            Offer o = list.get(n);
            NetworkMoves.Move mv = o.move;
            int lx = stateX(o.landingState), lz = stateZ(o.landingState);
            sb.append("\n  not taken: ").append(mv.segment.isBridge ? "bridge" : "road").append(" seg=").append(mv.segment.id)
                    .append(" offered ").append(o.offered).append("x (").append(o.lost).append(" beaten by a cheaper way to the landing)")
                    .append("; best from (").append(o.entryX).append(',').append(o.entryZ).append(") g=").append(round(o.gEntry))
                    .append(" + hop ").append(round(mv.hop)).append(" + follow ").append(round(mv.follow))
                    .append(" (").append(round(mv.lengthBlocks)).append(" blocks) + turn ").append(round(mv.turn))
                    .append(" = ").append(round(o.best))
                    .append(" landing (").append(lx).append(',').append(lz).append(") final g=")
                    .append(round(st.gScore.get(o.landingState)))
                    .append(st.closed.contains(o.landingState) ? " expanded" : " not expanded");
            appendClosestPathNode(sb, st, states, lx, lz);
        }
        if (list.size() > OFFER_LOG_LIMIT) {
            sb.append("\n  ... ").append(list.size() - OFFER_LOG_LIMIT).append(" more roads offered and not taken");
        }
    }

    private static void appendClosestPathNode(StringBuilder sb, SearchState st, List<Long> states, int x, int z) {
        int bestIdx = -1;
        double bestD = Double.POSITIVE_INFINITY;
        for (int i = 0; i < states.size(); i++) {
            long k = states.get(i);
            double d = Pos2D.distSq(stateX(k), stateZ(k), x, z);
            if (d < bestD) {
                bestD = d;
                bestIdx = i;
            }
        }
        if (bestIdx < 0) return;
        long k = states.get(bestIdx);
        sb.append("; found path passes ").append(round(Math.sqrt(bestD))).append(" from it at (")
                .append(stateX(k)).append(',').append(stateZ(k)).append(") g=").append(round(st.gScore.get(k)));
    }

    // --- Bridges ---------------------------------------------------------------------------------

    private static void logNewBridges(SearchState st, List<Long> states, @Nullable RoadNetwork network) {
        StringBuilder sb = new StringBuilder("[Ultreia reuse] new bridges of the found path:");
        boolean any = false;
        for (int i = 1; i < states.size(); i++) {
            long k = states.get(i);
            BridgeSpan span = st.cameBridge.get(k);
            if (span == null) continue;
            any = true;
            long pk = states.get(i - 1);
            sb.append("\n  NEW bridge (").append(span.start.getX()).append(',').append(span.start.getZ())
                    .append(") -> (").append(span.end.getX()).append(',').append(span.end.getZ()).append(") ")
                    .append(span.centerline.size()).append(" blocks, move cost=")
                    .append(round(st.gScore.get(k) - st.gScore.get(pk)));
            appendNearestBuilt(sb, network, span.start);
        }
        if (any) Ultreia.LOGGER.info(sb.toString());
    }

    private static void appendNearestBuilt(StringBuilder sb, @Nullable RoadNetwork network, BlockPos p) {
        RoadSegment best = null;
        double bestD = Double.POSITIVE_INFINITY;
        if (network != null) {
            for (RoadSegment seg : network.getSegmentsNear(p.getX(), p.getZ(), NEAR_BRIDGE_RADIUS)) {
                if (!seg.isBridge || seg.waypoints.isEmpty()) continue;
                double d = Math.sqrt(Math.min(Pos2D.distSq(p.getX(), p.getZ(), seg.startX(), seg.startZ()),
                        Pos2D.distSq(p.getX(), p.getZ(), seg.endX(), seg.endZ())));
                if (d < bestD) {
                    bestD = d;
                    best = seg;
                }
            }
        }
        if (best == null || bestD > NEAR_BRIDGE_RADIUS) {
            sb.append(", no built bridge end within ").append(NEAR_BRIDGE_RADIUS);
        } else {
            sb.append(", nearest built bridge seg=").append(best.id).append(" end at ").append(round(bestD));
        }
    }

    private static double extraAt(TerrainView sampler, int x, int z) {
        TerrainSampler.Sample s = sampler.sample(x, z);
        return s == null ? Double.NaN : sampler.extraCost(s, TerrainSampler.Mode.PATHFINDING);
    }

    private static double round(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}

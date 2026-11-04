package com.jubitus.ultreia.route;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.bridge.BridgeMoveGenerator;
import com.jubitus.ultreia.bridge.BridgeSpan;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.roads.RoadNetwork;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.terrain.TerrainView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
 * Debug trace of one A* search: why the found path did or did not follow existing roads (logPathfinding)
 * and built bridges (logBridges). One block of lines per found path.
 */
final class ReuseTrace {

    // Path nodes this close (blocks) to an existing road without being on it run alongside it.
    private static final int ALONGSIDE_RADIUS = 8;
    private static final int ALONGSIDE_LOG_LIMIT = 3;
    private static final int CROSSING_LOG_LIMIT = 4;

    /** Best offer of one built-bridge entrance over the whole search. */
    private static final class Offer {
        final RoadNetwork.BridgeCrossing crossing;
        int offered, lost;
        double best = Double.POSITIVE_INFINITY;
        double gEntry, move, turn, extra;
        int entryX, entryZ;
        long landingState;

        Offer(RoadNetwork.BridgeCrossing crossing) {
            this.crossing = crossing;
        }
    }

    private final Long2ObjectOpenHashMap<Offer> offers = new Long2ObjectOpenHashMap<>();

    /** A built-bridge move was evaluated; {@code relaxed} is false when the landing already had a cheaper way. */
    void offer(RoadNetwork.BridgeCrossing c, int entryX, int entryZ, double gEntry, double move, double turn,
               double extra, double tentative, long landingState, boolean relaxed) {
        if (!UltreiaConfig.DEBUG.logBridges) return;
        long key = Pos2D.xzKey(c.startX, c.startZ);
        Offer o = offers.get(key);
        if (o == null) {
            o = new Offer(c);
            offers.put(key, o);
        }
        o.offered++;
        if (!relaxed) o.lost++;
        if (tentative < o.best) {
            o.best = tentative;
            o.gEntry = gEntry;
            o.move = move;
            o.turn = turn;
            o.extra = extra;
            o.entryX = entryX;
            o.entryZ = entryZ;
            o.landingState = landingState;
        }
    }

    /** Logs the trace of an accepted path; {@code states} are its A* states from start to end. */
    void logFoundPath(SearchState st, List<Long> states, TerrainView sampler, RoadType roadType,
                      @Nullable RoadNetwork network, int step) {
        if (UltreiaConfig.DEBUG.logPathfinding) logRoads(st, states, sampler, roadType, step);
        if (UltreiaConfig.DEBUG.logBridges) logBridges(st, states, network);
    }

    // --- Roads -----------------------------------------------------------------------------------

    private static final class Stretch {
        int fromIdx, toIdx;
        double length;
        int minGap = Integer.MAX_VALUE, maxGap = 0;
        int minGapIdx;
        BlockPos minGapRoad;
    }

    private static void logRoads(SearchState st, List<Long> states, TerrainView sampler, RoadType roadType, int step) {
        double onRoad = 0, alongside = 0, free = 0, newBridgeBlocks = 0, reusedBlocks = 0;
        int newBridges = 0, reused = 0;
        List<Stretch> stretches = new ArrayList<>();
        Stretch open = null;

        for (int i = 1; i < states.size(); i++) {
            long k = states.get(i);
            long pk = states.get(i - 1);
            int x = stateX(k), z = stateZ(k);
            double len = Math.sqrt(Pos2D.distSq(stateX(pk), stateZ(pk), x, z));

            BridgeSpan span = st.cameBridge.get(k);
            if (st.cameReusedBridge.containsKey(k) || span != null) {
                if (span != null) { newBridges++; newBridgeBlocks += len; }
                else { reused++; reusedBlocks += len; }
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
                .append(": blocks on existing road=").append(round(onRoad))
                .append(", alongside one (within ").append(ALONGSIDE_RADIUS).append(")=").append(round(alongside))
                .append(", away from roads=").append(round(free))
                .append(", new bridges=").append(newBridges).append(" (").append(round(newBridgeBlocks)).append(" blocks)")
                .append(", built bridges used=").append(reused).append(" (").append(round(reusedBlocks)).append(" blocks)");

        stretches.sort(Comparator.comparingDouble((Stretch s) -> s.length).reversed());
        for (int n = 0; n < Math.min(ALONGSIDE_LOG_LIMIT, stretches.size()); n++) {
            appendStretch(sb, st, states, sampler, stretches.get(n), step);
        }
        if (stretches.size() > ALONGSIDE_LOG_LIMIT) {
            sb.append("\n  ... ").append(stretches.size() - ALONGSIDE_LOG_LIMIT).append(" shorter stretches alongside a road");
        }
        Ultreia.LOGGER.info(sb.toString());
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

    // --- Bridges ---------------------------------------------------------------------------------

    private void logBridges(SearchState st, List<Long> states, @Nullable RoadNetwork network) {
        StringBuilder sb = new StringBuilder("[Ultreia reuse] bridges of the found path:");
        boolean any = false;

        for (int i = 1; i < states.size(); i++) {
            long k = states.get(i);
            BridgeSpan span = st.cameBridge.get(k);
            RoadNetwork.BridgeCrossing used = st.cameReusedBridge.get(k);
            if (span == null && used == null) continue;
            any = true;
            long pk = states.get(i - 1);
            double cost = st.gScore.get(k) - st.gScore.get(pk);
            if (used != null) {
                sb.append("\n  built bridge seg=").append(used.segmentId).append(" used (")
                        .append(used.startX).append(',').append(used.startZ).append(") -> (")
                        .append(used.endX).append(',').append(used.endZ).append(") move cost=").append(round(cost));
                continue;
            }
            sb.append("\n  NEW bridge (").append(span.start.getX()).append(',').append(span.start.getZ())
                    .append(") -> (").append(span.end.getX()).append(',').append(span.end.getZ()).append(") ")
                    .append(span.centerline.size()).append(" blocks, move cost=").append(round(cost))
                    .append(" (g ").append(round(st.gScore.get(pk))).append(" -> ").append(round(st.gScore.get(k))).append(')');
            appendNearestBuilt(sb, network, span.start);
        }

        List<Offer> list = new ArrayList<>(offers.values());
        list.sort(Comparator.comparingDouble((Offer o) -> o.best));
        for (int n = 0; n < Math.min(CROSSING_LOG_LIMIT, list.size()); n++) {
            any = true;
            appendOffer(sb, st, states, list.get(n));
        }
        if (list.size() > CROSSING_LOG_LIMIT) {
            sb.append("\n  ... ").append(list.size() - CROSSING_LOG_LIMIT).append(" more built-bridge entrances offered");
        }
        if (any) Ultreia.LOGGER.info(sb.toString());
    }

    private static void appendNearestBuilt(StringBuilder sb, @Nullable RoadNetwork network, BlockPos start) {
        int radius = BridgeMoveGenerator.BRIDGE_REUSE_SEARCH_RADIUS;
        List<RoadNetwork.BridgeCrossing> near = network == null
                ? new ArrayList<>() : network.getBridgeCrossingsNear(start.getX(), start.getZ(), radius);
        RoadNetwork.BridgeCrossing best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (RoadNetwork.BridgeCrossing c : near) {
            double d = Math.sqrt(Pos2D.distSq(start.getX(), start.getZ(), c.startX, c.startZ));
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        if (best == null) {
            sb.append(", no built bridge entrance within ").append(radius);
        } else {
            sb.append(", nearest built bridge seg=").append(best.segmentId).append(" entrance (")
                    .append(best.startX).append(',').append(best.startZ).append(") at ").append(round(bestD));
        }
    }

    /** One built-bridge entrance: its cheapest offer split into terms, and how it compares with the found path. */
    private static void appendOffer(StringBuilder sb, SearchState st, List<Long> states, Offer o) {
        RoadNetwork.BridgeCrossing c = o.crossing;
        int lx = stateX(o.landingState), lz = stateZ(o.landingState);
        sb.append("\n  built bridge seg=").append(c.segmentId).append(" entrance (").append(c.startX).append(',')
                .append(c.startZ).append(") landing (").append(lx).append(',').append(lz).append("): offered ")
                .append(o.offered).append("x (").append(o.lost).append(" beaten by a cheaper way to the landing)")
                .append("; best from (").append(o.entryX).append(',').append(o.entryZ).append("): g=").append(round(o.gEntry))
                .append(" + crossing ").append(round(o.move))
                .append(" + turn ").append(round(o.turn))
                .append(" + landing cell extra ").append(round(o.extra))
                .append(" = ").append(round(o.best))
                .append("; landing state final g=").append(round(st.gScore.get(o.landingState)))
                .append(st.closed.contains(o.landingState) ? " expanded" : " not expanded");

        int bestIdx = -1;
        double bestD = Double.POSITIVE_INFINITY;
        for (int i = 0; i < states.size(); i++) {
            long k = states.get(i);
            double d = Pos2D.distSq(stateX(k), stateZ(k), lx, lz);
            if (d < bestD) {
                bestD = d;
                bestIdx = i;
            }
        }
        if (bestIdx >= 0) {
            long k = states.get(bestIdx);
            sb.append("; found path passes ").append(round(Math.sqrt(bestD))).append(" from the landing at (")
                    .append(stateX(k)).append(',').append(stateZ(k)).append(") g=").append(round(st.gScore.get(k)));
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

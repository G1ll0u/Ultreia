package com.jubitus.ultreia.route;

import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.geom.LineRaster;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.roads.RoadNetwork;
import com.jubitus.ultreia.roads.RoadSegment;
import com.jubitus.ultreia.roads.RoadWaypoint;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.terrain.TerrainView;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.jubitus.ultreia.route.GridMoves.nearestDirIndex;
import static com.jubitus.ultreia.route.GridMoves.turnCost;

/**
 * Moves along the recorded road network (bridges included): join a road point near the node by a short connector,
 * follow its stored waypoints, land at an exit point; the search leaves the road from there by normal moves.
 */
final class NetworkMoves {

    // Farthest road point (blocks) a node may join by a connector: wider than the search grid so that roads
    // running beside an existing one join it instead of doubling it (the straight-line estimate never detours).
    private static final int JOIN_RADIUS_BLOCKS = 8;

    // Road blocks between two exit points along a followed road (its far end is always an exit).
    private static final int EXIT_SPACING_BLOCKS = 16;

    /** Following one segment from waypoint {@code fromIdx} (the join) to {@code toIdx} (the exit). */
    static final class Move {
        final RoadSegment segment;
        final int fromIdx, toIdx;
        final int nx, nz, ndir;
        /** Connector from the node to the join point: its length plus the terrain cost of its cells. */
        final double hop;
        /** Length followed times {@code existingRoadCost}. */
        final double follow;
        final double turn;
        final double lengthBlocks;

        Move(RoadSegment segment, int fromIdx, int toIdx, int ndir, double hop, double follow, double turn,
             double lengthBlocks) {
            this.segment = segment;
            this.fromIdx = fromIdx;
            this.toIdx = toIdx;
            RoadWaypoint exit = segment.waypoints.get(toIdx);
            this.nx = exit.x;
            this.nz = exit.z;
            this.ndir = ndir;
            this.hop = hop;
            this.follow = follow;
            this.turn = turn;
            this.lengthBlocks = lengthBlocks;
        }
    }

    private final TerrainView sampler;
    private final int step;
    private final SearchBounds bounds;

    NetworkMoves(TerrainView sampler, int step, SearchBounds bounds) {
        this.sampler = sampler;
        this.step = step;
        this.bounds = bounds;
    }

    List<Move> from(@Nullable RoadNetwork network, int x, int z, int dir, int surfaceY, RoadType roadType) {
        if (network == null) return Collections.emptyList();
        List<Move> out = null;

        for (RoadSegment seg : network.getSegmentsNear(x, z, JOIN_RADIUS_BLOCKS)) {
            if (seg.waypoints.size() < 2) continue;
            // INVARIANT: a village main road follows only main roads; a lone trail follows any road.
            if (roadType == RoadType.VILLAGE_MAIN && seg.roadType != RoadType.VILLAGE_MAIN) continue;

            int join = joinIndex(seg, x, z);
            if (join < 0) continue;
            RoadWaypoint jw = seg.waypoints.get(join);
            int hopCells = Math.max(1, Math.max(Math.abs(jw.x - x), Math.abs(jw.z - z)));
            if (Math.abs(HalfY.ofBlockTop(jw.y) - HalfY.ofBlockTop(surfaceY)) > hopCells) continue;
            double hop = connectorCost(x, z, jw);
            if (Double.isNaN(hop)) continue;

            for (int d = -1; d <= 1; d += 2) {
                if (join + d < 0 || join + d >= seg.waypoints.size()) continue;
                RoadWaypoint first = seg.waypoints.get(join + d);
                double turn = turnCost(dir, nearestDirIndex(first.x - jw.x, first.z - jw.z), step,
                        UltreiaConfig.PATHFINDING.turnPenalty);
                out = addExits(out, seg, join, d, hop, turn);
            }
        }
        return out == null ? Collections.<Move>emptyList() : out;
    }

    /** Waypoint where the node joins the segment (nearest within the join radius), or -1; a bridge only at its ends. */
    private int joinIndex(RoadSegment seg, int x, int z) {
        long maxSq = (long) JOIN_RADIUS_BLOCKS * JOIN_RADIUS_BLOCKS;
        int best = -1;
        long bestSq = Long.MAX_VALUE;
        int last = seg.waypoints.size() - 1;
        for (int i = 0; i <= last; i++) {
            if (seg.isBridge && i != 0 && i != last) continue;
            RoadWaypoint w = seg.waypoints.get(i);
            long dx = w.x - x, dz = w.z - z;
            long sq = dx * dx + dz * dz;
            if (sq <= maxSq && sq < bestSq) {
                bestSq = sq;
                best = i;
            }
        }
        return best;
    }

    /**
     * Length of the straight connector from the node to the join point, plus the terrain cost of one cell per grid
     * step along it (as normal moves pay); NaN when a cell is forbidden or water.
     */
    private double connectorCost(int x, int z, RoadWaypoint jw) {
        List<BlockPos> line = LineRaster.forBridges(x, z, jw.x, jw.z, 0);
        double extra = 0.0;
        for (int i = 1; i < line.size() - 1; i++) {
            BlockPos c = line.get(i);
            TerrainSampler.Sample s = sampler.sample(c.getX(), c.getZ());
            if (s == null || s.isWater || sampler.forbidden(s, TerrainSampler.Mode.PATHFINDING)) return Double.NaN;
            if (i % step == 0) extra += sampler.extraCost(s, TerrainSampler.Mode.PATHFINDING);
        }
        return Math.sqrt(Pos2D.distSq(x, z, jw.x, jw.z)) + extra;
    }

    private List<Move> addExits(@Nullable List<Move> out, RoadSegment seg, int join, int d, double hop, double turn) {
        double cost = UltreiaConfig.PATHFINDING.existingRoadCost;
        int last = seg.waypoints.size() - 1;
        double along = 0.0;
        double nextExit = EXIT_SPACING_BLOCKS;

        for (int k = join + d; k >= 0 && k <= last; k += d) {
            RoadWaypoint a = seg.waypoints.get(k - d);
            RoadWaypoint b = seg.waypoints.get(k);
            along += Math.sqrt(Pos2D.distSq(a.x, a.z, b.x, b.z));

            boolean end = k == 0 || k == last;
            boolean exit = end || (!seg.isBridge && along >= nextExit);
            if (!exit) continue;
            if (!end) nextExit = along + EXIT_SPACING_BLOCKS;
            if (!isLandingCell(b)) continue;

            if (out == null) out = new ArrayList<>();
            out.add(new Move(seg, join, k, nearestDirIndex(b.x - a.x, b.z - a.z), hop, along * cost, turn, along));
        }
        return out;
    }

    /** An exit must be a cell the search may stand on (in bounds, not water, not forbidden). */
    private boolean isLandingCell(RoadWaypoint w) {
        if (!bounds.contains(w.x, w.z)) return false;
        TerrainSampler.Sample s = sampler.sample(w.x, w.z);
        return s != null && !sampler.forbidden(s, TerrainSampler.Mode.PATHFINDING);
    }
}

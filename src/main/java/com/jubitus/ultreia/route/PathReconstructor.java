package com.jubitus.ultreia.route;

import com.jubitus.ultreia.bridge.BridgeDeckProfile;
import com.jubitus.ultreia.bridge.BridgeSpan;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.geom.LineRaster;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.roads.RoadWaypoint;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.terrain.TerrainView;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.jubitus.ultreia.route.SearchState.stateX;
import static com.jubitus.ultreia.route.SearchState.stateZ;

/**
 * Walks the A* back-links from an end node to the start, within the tick budget, and builds the coarse path:
 * road nodes at ground level, new bridge decks at deck height, followed roads with their stored waypoints.
 */
final class PathReconstructor {

    private static final int RECONSTRUCT_GUARD = 600_000;
    private static final long ABSURD_JUMP_BLOCKS = 2048L;

    enum Status { RUNNING, DONE, FAILED }

    private final SearchState st;
    private final TerrainView sampler;
    final long endState;

    private final ArrayList<Long> revStates = new ArrayList<>();
    private long cur;
    private int guard = 0;

    private PathResult result = new PathResult(Collections.<BlockPos>emptyList(), Collections.<BridgeSpan>emptyList());
    // Index in the path at which each new bridge span begins (the prior road points end there).
    private final ArrayList<Integer> bridgeStartIndices = new ArrayList<>();
    private String failReason = null;
    private int followedCount = 0;
    private final LongOpenHashSet followedXZ = new LongOpenHashSet();

    PathReconstructor(SearchState st, TerrainView sampler, long endState) {
        this.st = st;
        this.sampler = sampler;
        this.endState = endState;
        this.cur = endState;
    }

    Status advance(long deadlineNs) {
        while (System.nanoTime() < deadlineNs) {
            revStates.add(cur);
            cur = st.cameFrom.get(cur);

            guard++;
            if (guard > RECONSTRUCT_GUARD) {
                failReason = "reconstruct guard hit (cycle?)";
                return Status.FAILED;
            }

            if (cur == Long.MIN_VALUE) {
                Collections.reverse(revStates);
                return build() ? Status.DONE : Status.FAILED;
            }
        }
        return Status.RUNNING;
    }

    PathResult result() {
        return result;
    }

    List<Integer> bridgeStartIndices() {
        return bridgeStartIndices;
    }

    /** The path's A* states from start to end (once done). */
    List<Long> states() {
        return revStates;
    }

    int followedCount() {
        return followedCount;
    }

    String failReason() {
        return failReason;
    }

    private boolean build() {
        ArrayList<BlockPos> out = new ArrayList<BlockPos>();
        ArrayList<BridgeSpan> spans = new ArrayList<BridgeSpan>();

        long first = revStates.get(0);
        int fx = stateX(first);
        int fz = stateZ(first);
        TerrainSampler.Sample fs = sampler.sample(fx, fz);
        out.add(new BlockPos(fx, fs.surfaceY, fz));

        for (int i = 1; i < revStates.size(); i++) {
            long curK = revStates.get(i);
            int cx = stateX(curK);
            int cz = stateZ(curK);

            NetworkMoves.Move followed = st.cameFollow.get(curK);
            if (followed != null) {
                followedCount++;
                appendFollowedWaypoints(out, followed);
                continue;
            }

            BridgeSpan span = st.cameBridge.get(curK);
            if (span != null) {
                spans.add(span);
                bridgeStartIndices.add(out.size() - 1);

                int[] deckHalf = BridgeDeckProfile.of(span);

                if (span.centerline != null && !span.centerline.isEmpty()) {
                    BlockPos firstBridge = span.centerline.get(0);
                    int firstDeckY = HalfY.walkBlockYFloor(deckHalf[0]);
                    BlockPos prevOut = out.get(out.size() - 1);

                    if (prevOut.getX() == firstBridge.getX() && prevOut.getZ() == firstBridge.getZ()) {
                        out.set(out.size() - 1, new BlockPos(firstBridge.getX(), firstDeckY, firstBridge.getZ()));
                    }
                }

                for (int j = 1; j < span.centerline.size(); j++) {
                    BlockPos p = span.centerline.get(j);
                    appendIfNewXZ(out, p.getX(), HalfY.walkBlockYFloor(deckHalf[j]), p.getZ());
                }
            } else {
                BlockPos prev = out.get(out.size() - 1);
                long manhattan = Math.abs((long) cx - (long) prev.getX()) + Math.abs((long) cz - (long) prev.getZ());
                if (manhattan > ABSURD_JUMP_BLOCKS) {
                    failReason = "reconstructed path contains absurd jump: (" + prev.getX() + "," + prev.getZ() + ") -> (" + cx + "," + cz + ")";
                    return false;
                }

                appendIfNewXZ(out, cx, sampler.sample(cx, cz).surfaceY, cz);
            }
        }

        result = new PathResult(out, spans, followedXZ);
        return true;
    }

    /**
     * Splices the followed waypoints (join to exit, at their stored heights) into the path, and marks every cell
     * the painter will rasterise between them as followed: nothing is painted there.
     */
    private void appendFollowedWaypoints(ArrayList<BlockPos> out, NetworkMoves.Move mv) {
        List<RoadWaypoint> wps = mv.segment.waypoints;
        int d = mv.toIdx >= mv.fromIdx ? 1 : -1;
        RoadWaypoint prev = null;
        for (int n = mv.fromIdx; n != mv.toIdx + d; n += d) {
            RoadWaypoint wp = wps.get(n);
            appendIfNewXZ(out, wp.x, wp.y, wp.z);
            if (prev == null) {
                followedXZ.add(Pos2D.xzKey(wp.x, wp.z));
            } else {
                // Same rasterisation as the painter's densify and the whole-path check.
                for (BlockPos c : LineRaster.forBridges(prev.x, prev.z, wp.x, wp.z, 0)) {
                    followedXZ.add(Pos2D.xzKey(c.getX(), c.getZ()));
                }
            }
            prev = wp;
        }
    }

    private static void appendIfNewXZ(ArrayList<BlockPos> out, int x, int y, int z) {
        if (out.isEmpty() || out.get(out.size() - 1).getX() != x || out.get(out.size() - 1).getZ() != z) {
            out.add(new BlockPos(x, y, z));
        }
    }
}

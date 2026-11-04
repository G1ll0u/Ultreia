package com.jubitus.ultreia.route;

import com.jubitus.ultreia.bridge.BridgeDeckProfile;
import com.jubitus.ultreia.bridge.BridgeSpan;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.roads.RoadNetwork;
import com.jubitus.ultreia.roads.RoadSegment;
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
 * road nodes at ground level, new bridge decks at deck height, reused bridges with their stored geometry.
 */
final class PathReconstructor {

    private static final int RECONSTRUCT_GUARD = 600_000;
    private static final long ABSURD_JUMP_BLOCKS = 2048L;

    enum Status { RUNNING, DONE, FAILED }

    private final SearchState st;
    private final TerrainView sampler;
    @Nullable private final RoadNetwork roadNetwork;
    final long endState;

    private final ArrayList<Long> revStates = new ArrayList<>();
    private long cur;
    private int guard = 0;

    private PathResult result = new PathResult(Collections.<BlockPos>emptyList(), Collections.<BridgeSpan>emptyList());
    // Index in the path at which each new bridge span begins (the prior road points end there).
    private final ArrayList<Integer> bridgeStartIndices = new ArrayList<>();
    private String failReason = null;
    private int reusedBridgeCount = 0;
    private final LongOpenHashSet reusedBridgeXZ = new LongOpenHashSet();

    PathReconstructor(SearchState st, TerrainView sampler, @Nullable RoadNetwork roadNetwork, long endState) {
        this.st = st;
        this.sampler = sampler;
        this.roadNetwork = roadNetwork;
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

    int reusedBridgeCount() {
        return reusedBridgeCount;
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

            RoadNetwork.BridgeCrossing reused = st.cameReusedBridge.get(curK);
            if (reused != null) {
                reusedBridgeCount++;
                appendReusedBridgeWaypoints(out, reused);
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

        result = new PathResult(out, spans, reusedBridgeXZ);
        return true;
    }

    /**
     * Splices the exact persisted geometry of a reused bridge into the reconstructed coarse path,
     * instead of the single ground-level point the naive "no span" branch would otherwise emit.
     * Not added to {@code spans}: the painter must never rebuild this bridge. Its deck cells go to
     * {@code reusedBridgeXZ}, which the painter skips as a gap (road stops at the bridge start, resumes at its end).
     */
    private void appendReusedBridgeWaypoints(ArrayList<BlockPos> out, RoadNetwork.BridgeCrossing crossing) {
        if (roadNetwork == null) return;
        RoadSegment seg = roadNetwork.getSegment(crossing.segmentId);
        if (seg == null || seg.waypoints.isEmpty()) return;

        List<RoadWaypoint> wps = seg.waypoints;
        // The crossing tells us which physical end of the persisted segment we are entering from,
        // so we walk the stored centerline in the matching direction.
        boolean forward = (seg.startX() == crossing.startX && seg.startZ() == crossing.startZ);

        for (int n = 0; n < wps.size(); n++) {
            RoadWaypoint wp = wps.get(forward ? n : wps.size() - 1 - n);
            appendIfNewXZ(out, wp.x, wp.y, wp.z);
            reusedBridgeXZ.add(Pos2D.xzKey(wp.x, wp.z));
        }
    }

    private static void appendIfNewXZ(ArrayList<BlockPos> out, int x, int y, int z) {
        if (out.isEmpty() || out.get(out.size() - 1).getX() != x || out.get(out.size() - 1).getZ() != z) {
            out.add(new BlockPos(x, y, z));
        }
    }
}

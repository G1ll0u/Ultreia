package com.jubitus.ultreia.route;

import com.jubitus.ultreia.bridge.BridgeSpan;
import com.jubitus.ultreia.roads.RoadNetwork;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.Comparator;
import java.util.PriorityQueue;

import static com.jubitus.ultreia.route.GridMoves.DIR_NONE;

/** State shared by one A* search: open and closed sets, scores, back-links (road step, new bridge, reused bridge). */
final class SearchState {

    // distSinceBridge value meaning "no bridge yet on this path"; real distances stay capped far below it.
    static final int NO_BRIDGE_YET = Integer.MAX_VALUE / 4;
    private static final int DIST_SINCE_BRIDGE_CAP = 1_000_000;

    static final class Node {
        final int x, z;
        final int dir;
        final double f;

        Node(int x, int z, int dir, double f) {
            this.x = x;
            this.z = z;
            this.dir = dir;
            this.f = f;
        }
    }

    final Long2DoubleOpenHashMap gScore = new Long2DoubleOpenHashMap();
    final Long2LongOpenHashMap cameFrom = new Long2LongOpenHashMap();
    final Long2ObjectOpenHashMap<BridgeSpan> cameBridge = new Long2ObjectOpenHashMap<>();
    // Separate from cameBridge because a reused-bridge move stores span=null (nothing new to build),
    // which is indistinguishable from "no bridge at all" in a Long2ObjectOpenHashMap lookup.
    final Long2ObjectOpenHashMap<RoadNetwork.BridgeCrossing> cameReusedBridge = new Long2ObjectOpenHashMap<>();
    final Long2IntOpenHashMap distSinceBridge = new Long2IntOpenHashMap();

    final PriorityQueue<Node> open = new PriorityQueue<>(new Comparator<Node>() {
        @Override
        public int compare(Node a, Node b) {
            return Double.compare(a.f, b.f);
        }
    });
    final LongOpenHashSet closed = new LongOpenHashSet();

    int iterations = 0;

    SearchState() {
        gScore.defaultReturnValue(Double.POSITIVE_INFINITY);
        cameFrom.defaultReturnValue(Long.MIN_VALUE);
        distSinceBridge.defaultReturnValue(Integer.MAX_VALUE);
    }

    void addStart(int x, int z, double f) {
        long k = stateKey(x, z, DIR_NONE);
        gScore.put(k, 0.0);
        distSinceBridge.put(k, NO_BRIDGE_YET);
        open.add(new Node(x, z, DIR_NONE, f));
    }

    /** Records a plain road step from {@code from} to the node (x, z, dir) of key {@code to}. */
    void relaxRoad(long from, long to, int x, int z, int dir, double g, double f, double stepLength) {
        cameBridge.remove(to);
        cameReusedBridge.remove(to);
        int prevDist = distSinceBridge.get(from);
        int nextDist = (prevDist >= NO_BRIDGE_YET)
                ? prevDist
                : Math.min(DIST_SINCE_BRIDGE_CAP, prevDist + (int) Math.round(stepLength));
        link(from, to, x, z, dir, g, f, nextDist);
    }

    void relaxNewBridge(long from, long to, int x, int z, int dir, double g, double f, BridgeSpan span) {
        cameBridge.put(to, span);
        cameReusedBridge.remove(to);
        link(from, to, x, z, dir, g, f, 0);
    }

    void relaxReusedBridge(long from, long to, int x, int z, int dir, double g, double f,
                           RoadNetwork.BridgeCrossing crossing) {
        cameBridge.remove(to);
        cameReusedBridge.put(to, crossing);
        link(from, to, x, z, dir, g, f, 0);
    }

    private void link(long from, long to, int x, int z, int dir, double g, double f, int distSince) {
        cameFrom.put(to, from);
        gScore.put(to, g);
        distSinceBridge.put(to, distSince);
        open.add(new Node(x, z, dir, f));
    }

    private static final int STATE_DIR_BITS = 5;
    private static final int STATE_COORD_BITS = 26;
    private static final int STATE_COORD_MASK = (1 << STATE_COORD_BITS) - 1;
    private static final int STATE_COORD_BIAS = 1 << (STATE_COORD_BITS - 1);

    static long stateKey(int x, int z, int dir) {
        int px = packCoord26(x);
        int pz = packCoord26(z);
        long ud = ((long) dir) & 0x1fL; // 5 bits: covers directions 0-15 and DIR_NONE=16

        return (((long) px) << (STATE_COORD_BITS + STATE_DIR_BITS))
                | (((long) pz) << STATE_DIR_BITS)
                | ud;
    }

    static int stateX(long key) {
        int px = (int) ((key >> (STATE_COORD_BITS + STATE_DIR_BITS)) & STATE_COORD_MASK);
        return px - STATE_COORD_BIAS;
    }

    static int stateZ(long key) {
        int pz = (int) ((key >> STATE_DIR_BITS) & STATE_COORD_MASK);
        return pz - STATE_COORD_BIAS;
    }

    private static int packCoord26(int v) {
        int p = v + STATE_COORD_BIAS;
        if (p < 0 || p > STATE_COORD_MASK) {
            throw new IllegalArgumentException("Coordinate out of supported range for packed A* state: " + v);
        }
        return p;
    }
}

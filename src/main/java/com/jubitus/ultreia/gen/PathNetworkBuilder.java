package com.jubitus.ultreia.gen;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.MPBConfig;
import com.jubitus.ultreia.gen.mill.MillVillageIndex;
import com.jubitus.ultreia.gen.world.LogicalEdge;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.*;

public final class PathNetworkBuilder {

    // -------------------------------------------------------------------------
    // Public types
    // -------------------------------------------------------------------------

    public static final class Edge {
        public final BlockPos a;
        public final BlockPos b;
        public final boolean isLoneTrail;

        public Edge(BlockPos a, BlockPos b) { this(a, b, false); }

        public Edge(BlockPos a, BlockPos b, boolean isLoneTrail) {
            this.a = a;
            this.b = b;
            this.isLoneTrail = isLoneTrail;
        }

        public String key() {
            String k1 = a.getX() + "," + a.getZ();
            String k2 = b.getX() + "," + b.getZ();
            return (k1.compareTo(k2) <= 0) ? (k1 + "->" + k2) : (k2 + "->" + k1);
        }

        public double distSq() {
            long dx = (long) a.getX() - (long) b.getX();
            long dz = (long) a.getZ() - (long) b.getZ();
            return (double) dx * dx + (double) dz * dz;
        }
    }

    public static final class NetworkResult {
        public final List<Edge> edges;
        /** Village size for each village node (not populated for lone buildings). */
        public final Map<BlockPos, VillageClassifier.VillageSize> villageSizes;

        NetworkResult(List<Edge> edges, Map<BlockPos, VillageClassifier.VillageSize> villageSizes) {
            this.edges = edges;
            this.villageSizes = villageSizes;
        }
    }

    private PathNetworkBuilder() {}

    // -------------------------------------------------------------------------
    // Entry point
    // -------------------------------------------------------------------------

    public static NetworkResult build(WorldServer world, List<BlockPos> nodes) {
        if (nodes.size() < 2) return new NetworkResult(Collections.emptyList(), Collections.emptyMap());

        int maxDist = MPBConfig.NETWORK.maxEdgeDistance;
        double maxDistSq = (double) maxDist * (double) maxDist;

        // 1. Classify nodes
        List<BlockPos> villages = new ArrayList<>();
        List<BlockPos> lone     = new ArrayList<>();
        Map<BlockPos, VillageClassifier.VillageSize> sizes = new HashMap<>();

        for (BlockPos n : nodes) {
            MillVillageIndex.VillageInfo vi = MillVillageIndex.findExact(world, n);
            if (vi == null) {
                // Unknown node treated as village to keep connectivity
                villages.add(n);
                sizes.put(n, VillageClassifier.VillageSize.SMALL);
                continue;
            }
            if (vi.isVillage) {
                villages.add(n);
                sizes.put(n, VillageClassifier.classify(world, n));
            } else {
                lone.add(n);
            }
        }

        if (MPBConfig.NETWORK.debugNetwork) {
            long hubs   = sizes.values().stream().filter(s -> s == VillageClassifier.VillageSize.HUB).count();
            long medium = sizes.values().stream().filter(s -> s == VillageClassifier.VillageSize.MEDIUM).count();
            Ultreia.LOGGER.debug("[PNB] nodes={} villages={} (hubs={} medium={} small={}) lone={}",
                    nodes.size(), villages.size(), hubs, medium, villages.size() - hubs - medium, lone.size());
        }

        LinkedHashMap<String, Edge> edgesMap = new LinkedHashMap<>();
        List<Edge> committed = new ArrayList<>(); // ordered for crossing checks

        // 2. Backbone MST on villages
        if (MPBConfig.NETWORK.buildMST && villages.size() >= 2) {
            List<Edge> mst = buildMST(villages, sizes, maxDistSq);
            for (Edge e : mst) { edgesMap.put(e.key(), e); committed.add(e); }
        }

        // 3. Extra edges: RNG or KNN
        if (villages.size() >= 2) {
            if (MPBConfig.NETWORK.useRNG) {
                addRngEdges(villages, sizes, maxDistSq, committed, edgesMap);
            } else {
                int k = Math.max(0, MPBConfig.NETWORK.nearestNeighbors);
                if (k > 0) addKnnEdges(villages, k, maxDistSq, edgesMap);
            }
        }

        // 4. Lone buildings: connect to nearest village only
        for (BlockPos lb : lone) {
            BlockPos target = nearest(lb, villages, maxDistSq);
            if (target == null && !villages.isEmpty()) target = villages.get(0);
            if (target == null) continue;
            Edge e = new Edge(lb, target, true);
            if (e.distSq() <= maxDistSq) edgesMap.put(e.key(), e);
        }

        List<Edge> result = new ArrayList<>(edgesMap.values());

        // 5. Persist logical network in world data for entity navigation
        persistLogicalNetwork(world, result);

        return new NetworkResult(result, sizes);
    }

    // -------------------------------------------------------------------------
    // MST (Prim's, hub-aware distances)
    // -------------------------------------------------------------------------

    private static List<Edge> buildMST(List<BlockPos> nodes,
                                        Map<BlockPos, VillageClassifier.VillageSize> sizes,
                                        double maxDistSq) {
        List<Edge> out = new ArrayList<>();
        Set<Integer> visited = new HashSet<>();
        visited.add(0);

        while (visited.size() < nodes.size()) {
            double best    = Double.MAX_VALUE;
            int bestFrom   = -1, bestTo = -1;

            for (int i : visited) {
                BlockPos a = nodes.get(i);
                for (int j = 0; j < nodes.size(); j++) {
                    if (visited.contains(j)) continue;
                    BlockPos b = nodes.get(j);
                    if (distSq2D(a, b) > maxDistSq) continue;
                    double eff = effDistSq(a, b, sizes);
                    if (eff < best) { best = eff; bestFrom = i; bestTo = j; }
                }
            }

            if (bestFrom == -1) break;
            out.add(new Edge(nodes.get(bestFrom), nodes.get(bestTo)));
            visited.add(bestTo);
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // RNG + redundancy + crossing filter
    // -------------------------------------------------------------------------

    private static void addRngEdges(List<BlockPos> nodes,
                                     Map<BlockPos, VillageClassifier.VillageSize> sizes,
                                     double maxDistSq,
                                     List<Edge> committed,
                                     LinkedHashMap<String, Edge> out) {
        // Build MST adjacency for redundancy check
        Map<BlockPos, List<BlockPos>> mstAdj = buildAdjacency(committed);

        int n = nodes.size();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                BlockPos u = nodes.get(i);
                BlockPos v = nodes.get(j);

                double realD2 = distSq2D(u, v);
                if (realD2 > maxDistSq) continue;

                double effD2 = effDistSq(u, v, sizes);

                // RNG condition: no third node w lies inside the (effective) lune
                boolean inRng = true;
                for (int k = 0; k < n; k++) {
                    if (k == i || k == j) continue;
                    BlockPos w = nodes.get(k);
                    if (distSq2D(u, w) < effD2 && distSq2D(v, w) < effD2) {
                        inRng = false;
                        break;
                    }
                }
                if (!inRng) continue;

                String edgeKey = edgeKey(u, v);
                if (out.containsKey(edgeKey)) continue; // already in MST

                // Redundancy check: skip if MST path is already short enough
                if (MPBConfig.NETWORK.redundancyRatio > 0) {
                    double mstLen = mstPathLength(u, v, mstAdj);
                    if (mstLen != Double.POSITIVE_INFINITY) {
                        double direct = Math.sqrt(realD2);
                        if (mstLen <= MPBConfig.NETWORK.redundancyRatio * direct) {
                            if (MPBConfig.NETWORK.debugNetwork)
                                Ultreia.LOGGER.debug("[PNB] RNG edge pruned (redundant ratio={:.2f}): ({},{})→({},{})",
                                        mstLen / direct, u.getX(), u.getZ(), v.getX(), v.getZ());
                            continue;
                        }
                    }
                }

                // Anti-crossing check
                if (MPBConfig.NETWORK.avoidEdgeCrossings) {
                    boolean crosses = false;
                    for (Edge e : committed) {
                        if (intersects2D(u, v, e.a, e.b)) { crosses = true; break; }
                    }
                    if (crosses) {
                        if (MPBConfig.NETWORK.debugNetwork)
                            Ultreia.LOGGER.debug("[PNB] RNG edge pruned (crossing): ({},{})→({},{})",
                                    u.getX(), u.getZ(), v.getX(), v.getZ());
                        continue;
                    }
                }

                Edge candidate = new Edge(u, v);
                out.put(edgeKey, candidate);
                committed.add(candidate);
            }
        }
    }

    // -------------------------------------------------------------------------
    // KNN fallback
    // -------------------------------------------------------------------------

    private static void addKnnEdges(List<BlockPos> nodes, int k, double maxDistSq,
                                     Map<String, Edge> out) {
        for (BlockPos n : nodes) {
            List<BlockPos> others = new ArrayList<>(nodes);
            others.remove(n);
            others.sort(Comparator.comparingDouble(o -> distSq2D(n, o)));
            int added = 0;
            for (BlockPos o : others) {
                if (distSq2D(n, o) > maxDistSq) break;
                Edge e = new Edge(n, o);
                out.put(e.key(), e);
                if (++added >= k) break;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Effective squared distance — hub-hub pairs get a discount to survive RNG pruning. */
    private static double effDistSq(BlockPos a, BlockPos b,
                                     Map<BlockPos, VillageClassifier.VillageSize> sizes) {
        double d2 = distSq2D(a, b);
        if (sizes.get(a) == VillageClassifier.VillageSize.HUB
                && sizes.get(b) == VillageClassifier.VillageSize.HUB) {
            double scale = 1.0 - MPBConfig.NETWORK.hubConnectionBonus;
            return d2 * scale * scale;
        }
        return d2;
    }

    /** Finds the nearest node in {@code to} within maxDistSq, or null. */
    private static BlockPos nearest(BlockPos from, List<BlockPos> to, double maxDistSq) {
        BlockPos best = null;
        double bestD  = Double.POSITIVE_INFINITY;
        for (BlockPos t : to) {
            double d = distSq2D(from, t);
            if (d <= maxDistSq && d < bestD) { bestD = d; best = t; }
        }
        return best;
    }

    private static double distSq2D(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - (long) b.getX();
        long dz = (long) a.getZ() - (long) b.getZ();
        return (double) dx * dx + (double) dz * dz;
    }

    private static String edgeKey(BlockPos a, BlockPos b) {
        String k1 = a.getX() + "," + a.getZ();
        String k2 = b.getX() + "," + b.getZ();
        return (k1.compareTo(k2) <= 0) ? (k1 + "->" + k2) : (k2 + "->" + k1);
    }

    /** Builds an undirected adjacency map from the given edges. */
    private static Map<BlockPos, List<BlockPos>> buildAdjacency(List<Edge> edges) {
        Map<BlockPos, List<BlockPos>> adj = new HashMap<>();
        for (Edge e : edges) {
            adj.computeIfAbsent(e.a, k -> new ArrayList<>()).add(e.b);
            adj.computeIfAbsent(e.b, k -> new ArrayList<>()).add(e.a);
        }
        return adj;
    }

    /**
     * Returns the total Euclidean length of the unique path from start to end in
     * the given tree adjacency, or Double.POSITIVE_INFINITY if unreachable.
     */
    private static double mstPathLength(BlockPos start, BlockPos end,
                                         Map<BlockPos, List<BlockPos>> adj) {
        if (start.equals(end)) return 0;
        Deque<BlockPos> stack = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        stack.push(start);
        parent.put(start, null);

        while (!stack.isEmpty()) {
            BlockPos cur = stack.pop();
            if (cur.equals(end)) {
                double len  = 0;
                BlockPos node = end;
                while (parent.get(node) != null) {
                    BlockPos prev = parent.get(node);
                    len += Math.sqrt(distSq2D(prev, node));
                    node = prev;
                }
                return len;
            }
            List<BlockPos> neighbors = adj.get(cur);
            if (neighbors == null) continue;
            for (BlockPos nb : neighbors) {
                if (!parent.containsKey(nb)) {
                    parent.put(nb, cur);
                    stack.push(nb);
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    /**
     * Returns true if segment (a1→a2) properly intersects (b1→b2).
     * "Proper" means neither endpoint of one lies on the other (avoids shared endpoints).
     */
    private static boolean intersects2D(BlockPos a1, BlockPos a2, BlockPos b1, BlockPos b2) {
        double ax = a2.getX() - a1.getX(), az = a2.getZ() - a1.getZ();
        double bx = b2.getX() - b1.getX(), bz = b2.getZ() - b1.getZ();
        double cx = b1.getX() - a1.getX(), cz = b1.getZ() - a1.getZ();

        double denom = ax * bz - az * bx;
        if (Math.abs(denom) < 1e-9) return false; // parallel

        double t = (cx * bz - cz * bx) / denom;
        double s = (cx * az - cz * ax) / denom;
        double eps = 1e-6;
        return t > eps && t < 1.0 - eps && s > eps && s < 1.0 - eps;
    }

    /** Converts the edge list to LogicalEdge and persists it in world data. */
    private static void persistLogicalNetwork(WorldServer world, List<Edge> edges) {
        List<LogicalEdge> logical = new ArrayList<>(edges.size());
        for (Edge e : edges) {
            logical.add(new LogicalEdge(e.a.getX(), e.a.getZ(), e.b.getX(), e.b.getZ(), e.isLoneTrail));
        }
        MPBWorldData.get(world).setLogicalNetwork(logical);
    }
}

package com.jubitus.millpathbuilder.gen;

import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.mill.MillVillageIndex;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.*;

public final class PathNetworkBuilder {

    public static final class Edge {
        public final BlockPos a;
        public final BlockPos b;

        public Edge(BlockPos a, BlockPos b) {
            this.a = a;
            this.b = b;
        }

        public String key() {
            String k1 = a.getX() + "," + a.getZ();
            String k2 = b.getX() + "," + b.getZ();
            return (k1.compareTo(k2) <= 0) ? (k1 + "->" + k2) : (k2 + "->" + k1);
        }

        public double distSq() {
            long dx = (long) a.getX() - (long) b.getX();
            long dz = (long) a.getZ() - (long) b.getZ();
            return (double) dx * (double) dx + (double) dz * (double) dz;
        }
    }

    private PathNetworkBuilder() {}

    public static List<Edge> build(WorldServer world, List<BlockPos> nodes) {
        if (nodes.size() < 2) return java.util.Collections.emptyList();

        int maxDist = MPBConfig.NETWORK.maxEdgeDistance;
        double maxDistSq = (double) maxDist * (double) maxDist;

        ArrayList<BlockPos> villages = new ArrayList<>();
        ArrayList<BlockPos> lone = new ArrayList<>();
        ArrayList<BlockPos> unknown = new ArrayList<>();

        for (BlockPos n : nodes) {
            MillVillageIndex.VillageInfo vi = MillVillageIndex.findExact(world, n);
            if (vi == null) {
                unknown.add(n);
                continue;
            }
            if (vi.isVillage) villages.add(n);
            else lone.add(n);
        }

        // Treat unknown as villages to keep connectivity if Mill data is incomplete
        villages.addAll(unknown);

        LinkedHashMap<String, Edge> edges = new LinkedHashMap<>();

        // 1) Backbone: villages only
        if (villages.size() >= 2) {
            if (MPBConfig.NETWORK.buildMST) {
                edges.putAll(buildMST(villages, maxDistSq));
            }
            int k = Math.max(0, MPBConfig.NETWORK.nearestNeighbors);
            if (k > 0) {
                addKnnEdges(villages, k, maxDistSq, edges);
            }
        }

        // 2) Branch each lone building to the nearest VILLAGE (or nearest backbone node)
        for (BlockPos lb : lone) {
            BlockPos target = nearest(lb, villages, maxDistSq);
            if (target == null && !villages.isEmpty()) target = villages.get(0);
            if (target == null) continue;

            Edge e = new Edge(lb, target);
            if (e.distSq() <= maxDistSq) {
                edges.put(e.key(), e);
            }
        }

        return new ArrayList<>(edges.values());
    }

    private static void addKnnEdges(List<BlockPos> nodes, int k, double maxDistSq, Map<String, Edge> out) {
        for (BlockPos n : nodes) {
            ArrayList<BlockPos> others = new ArrayList<>(nodes);
            others.remove(n);
            others.sort(java.util.Comparator.comparingDouble(o -> distSq2D(n, o)));

            int added = 0;
            for (BlockPos o : others) {
                if (distSq2D(n, o) > maxDistSq) continue;
                Edge e = new Edge(n, o);
                out.put(e.key(), e);
                added++;
                if (added >= k) break;
            }
        }
    }

    private static BlockPos nearest(BlockPos from, List<BlockPos> to, double maxDistSq) {
        BlockPos best = null;
        double bestD = Double.POSITIVE_INFINITY;
        for (BlockPos t : to) {
            double d = distSq2D(from, t);
            if (d <= maxDistSq && d < bestD) {
                bestD = d;
                best = t;
            }
        }
        return best;
    }

    private static Map<String, Edge> buildMST(List<BlockPos> nodes, double maxDistSq) {
        Map<String, Edge> out = new LinkedHashMap<>();
        Set<Integer> visited = new HashSet<>();
        visited.add(0);

        while (visited.size() < nodes.size()) {
            double best = Double.MAX_VALUE;
            int bestFrom = -1;
            int bestTo = -1;

            for (int i : visited) {
                BlockPos a = nodes.get(i);
                for (int j = 0; j < nodes.size(); j++) {
                    if (visited.contains(j)) continue;
                    BlockPos b = nodes.get(j);
                    double d = distSq2D(a, b);
                    if (d > maxDistSq) continue;
                    if (d < best) {
                        best = d;
                        bestFrom = i;
                        bestTo = j;
                    }
                }
            }

            if (bestFrom == -1) break;
            Edge e = new Edge(nodes.get(bestFrom), nodes.get(bestTo));
            out.put(e.key(), e);
            visited.add(bestTo);
        }

        return out;
    }

    private static double distSq2D(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - (long) b.getX();
        long dz = (long) a.getZ() - (long) b.getZ();
        return (double) dx * (double) dx + (double) dz * (double) dz;
    }
}
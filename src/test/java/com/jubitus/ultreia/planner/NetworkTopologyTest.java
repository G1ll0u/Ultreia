package com.jubitus.ultreia.planner;

import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.planner.VillageClassifier.VillageSize;
import com.jubitus.ultreia.testutil.Golden;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** MST + RNG network on a fixed random map of villages and lone buildings, default config. */
class NetworkTopologyTest {

    private final List<BlockPos> villages = new ArrayList<>();
    private final List<BlockPos> lone = new ArrayList<>();
    private final Map<BlockPos, VillageSize> sizes = new HashMap<>();

    NetworkTopologyTest() {
        Random r = new Random(42);
        for (int i = 0; i < 25; i++) {
            BlockPos p = new BlockPos(r.nextInt(6000) - 3000, 64, r.nextInt(6000) - 3000);
            villages.add(p);
            sizes.put(p, i % 7 == 0 ? VillageSize.HUB : i % 3 == 0 ? VillageSize.MEDIUM : VillageSize.SMALL);
        }
        for (int i = 0; i < 6; i++) {
            lone.add(new BlockPos(r.nextInt(6000) - 3000, 64, r.nextInt(6000) - 3000));
        }
    }

    @Test
    void villagesAreConnectedAndLoneBuildingsJoinTheNearestVillage() {
        List<NetworkPlanner.Edge> edges = NetworkPlanner.connect(villages, lone, sizes);
        double maxD = UltreiaConfig.NETWORK.maxEdgeDistance;

        Map<BlockPos, Set<BlockPos>> adj = new HashMap<>();
        for (NetworkPlanner.Edge e : edges) {
            assertTrue(Math.sqrt(e.distSq()) <= maxD, "edge longer than maxEdgeDistance");
            adj.computeIfAbsent(e.a, k -> new HashSet<>()).add(e.b);
            adj.computeIfAbsent(e.b, k -> new HashSet<>()).add(e.a);
        }

        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> todo = new ArrayDeque<>(Collections.singleton(villages.get(0)));
        while (!todo.isEmpty()) {
            BlockPos p = todo.pop();
            if (!seen.add(p)) continue;
            todo.addAll(adj.getOrDefault(p, Collections.emptySet()));
        }
        assertTrue(seen.containsAll(villages), "all villages reachable");

        for (BlockPos lb : lone) {
            Set<BlockPos> n = adj.get(lb);
            assertEquals(1, n.size(), "lone building has exactly one trail");
            BlockPos target = n.iterator().next();
            for (BlockPos v : villages) {
                assertTrue(lb.distanceSq(target) <= lb.distanceSq(v), "trail goes to the nearest village");
            }
        }
        for (NetworkPlanner.Edge e : edges) {
            assertEquals(lone.contains(e.a) || lone.contains(e.b), e.isLoneTrail);
        }
    }

    @Test
    void networkUnchanged() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (NetworkPlanner.Edge e : NetworkPlanner.connect(villages, lone, sizes)) {
            sb.append(e.key()).append(e.isLoneTrail ? " lone" : "").append('\n');
        }
        Golden.check("network_topology", sb.toString());
    }
}

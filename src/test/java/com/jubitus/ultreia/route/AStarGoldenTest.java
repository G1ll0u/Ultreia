package com.jubitus.ultreia.route;

import com.jubitus.ultreia.bridge.BridgeSpan;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.roads.RoadNetwork;
import com.jubitus.ultreia.testutil.Golden;
import com.jubitus.ultreia.testutil.SyntheticTerrain;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden paths on synthetic terrain, default config; bridges off except in the gorge test.
 * A refactor must keep every path (and bridge) identical.
 */
class AStarGoldenTest {

    private static boolean bridgesWere;

    @BeforeAll
    static void bridgesOff() {
        bridgesWere = UltreiaConfig.BRIDGES.enabled;
        UltreiaConfig.BRIDGES.enabled = false;
    }

    @AfterAll
    static void restore() {
        UltreiaConfig.BRIDGES.enabled = bridgesWere;
    }

    @Test
    void flatWithObstacle() throws IOException {
        SyntheticTerrain t = new SyntheticTerrain() {
            @Override protected int height(int x, int z) { return 64; }
            @Override protected boolean blocked(int x, int z) { return x >= 80 && x <= 120 && z >= -40 && z <= 40; }
        };
        Golden.check("astar_flat_obstacle", run(t, 0, 0, 200, 0, RoadType.VILLAGE_MAIN));
    }

    @Test
    void hills() throws IOException {
        SyntheticTerrain t = new SyntheticTerrain() {
            @Override protected int height(int x, int z) {
                return 64 + (int) Math.round(10 * Math.sin(x / 20.0) + 8 * Math.cos(z / 17.0));
            }
        };
        Golden.check("astar_hills", run(t, 0, 0, 160, 40, RoadType.VILLAGE_MAIN));
    }

    @Test
    void riverValleyLoneTrail() throws IOException {
        SyntheticTerrain t = new SyntheticTerrain() {
            @Override protected int height(int x, int z) { return 64 - Math.max(0, 30 - Math.abs(x - 100)) / 2; }
            @Override protected boolean water(int x, int z) { return Math.abs(x - 100) <= 3 && z < 40; }
        };
        Golden.check("astar_river_valley_lone_trail", run(t, 0, 0, 200, 0, RoadType.LONE_TRAIL));
    }

    @Test
    void gorgeBridge() throws IOException {
        // A 20-deep gorge 30 blocks wide across the whole search area: a bridge beats the long way down and up.
        SyntheticTerrain t = new SyntheticTerrain() {
            @Override protected int height(int x, int z) { return (x >= 85 && x <= 115) ? 44 : 64; }
        };
        UltreiaConfig.BRIDGES.enabled = true;
        try {
            Golden.check("astar_gorge_bridge", run(t, 0, 0, 200, 0, RoadType.VILLAGE_MAIN));
        } finally {
            UltreiaConfig.BRIDGES.enabled = false;
        }
    }

    @Test
    void narrowRiverGetsSmallBridge() {
        // A 3-block river across the whole search area on flat ground: only a small bridge gets the road across.
        SyntheticTerrain t = new SyntheticTerrain() {
            @Override protected int height(int x, int z) { return 64; }
            @Override protected boolean water(int x, int z) { return x >= 99 && x <= 101; }
        };
        UltreiaConfig.BRIDGES.enabled = true;
        try {
            String out = run(t, 0, 0, 200, 0, RoadType.VILLAGE_MAIN);
            assertTrue(out.startsWith("result FOUND"), out);
            assertTrue(!out.contains("bridges 0"), out);
        } finally {
            UltreiaConfig.BRIDGES.enabled = false;
        }
    }

    @Test
    void roadMeetsBridgeInItsAxis() {
        // A river with a narrows, and a rock wall just past the straight crossing: the road must not land and turn
        // off the deck's side; it enters and leaves every bridge in its axis.
        SyntheticTerrain t = new SyntheticTerrain() {
            @Override protected int height(int x, int z) { return 64; }
            @Override protected boolean water(int x, int z) {
                return (x >= 99 && x <= 103) || (x >= 75 && x <= 103 && Math.abs(z) > 8);
            }
            @Override protected boolean blocked(int x, int z) { return x >= 107 && x <= 111 && z >= -4 && z <= 20; }
        };
        UltreiaConfig.BRIDGES.enabled = true;
        try {
            BlockPos start = new BlockPos(0, 64, 60);
            AStarSearch s = new AStarSearch(start, new BlockPos(200, 64, 60), t);
            s.setRoadType(RoadType.VILLAGE_MAIN);
            AStarSearch.StepResult r;
            do {
                r = s.advance(System.nanoTime() + 1_000_000_000L);
            } while (r == AStarSearch.StepResult.RUNNING);
            assertEquals(AStarSearch.StepResult.FOUND, r);

            List<BlockPos> path = s.getResult().getPath();
            assertTrue(!s.getResult().getBridges().isEmpty(), "no bridge");
            for (BridgeSpan b : s.getResult().getBridges()) {
                int si = indexOfXZ(path, b.start), ei = indexOfXZ(path, b.end);
                if (si > 0) assertAligned(b, path.get(si - 1), b.start);
                if (ei < path.size() - 1) assertAligned(b, b.end, path.get(ei + 1));
            }
        } finally {
            UltreiaConfig.BRIDGES.enabled = false;
        }
    }

    private static int indexOfXZ(List<BlockPos> path, BlockPos p) {
        for (int i = 0; i < path.size(); i++) {
            if (path.get(i).getX() == p.getX() && path.get(i).getZ() == p.getZ()) return i;
        }
        throw new AssertionError("bridge end not on path: " + p);
    }

    private static void assertAligned(BridgeSpan b, BlockPos from, BlockPos to) {
        double dot = GridMoves.normalizedDot(b.end.getX() - b.start.getX(), b.end.getZ() - b.start.getZ(),
                to.getX() - from.getX(), to.getZ() - from.getZ());
        assertTrue(dot >= 0.8, "road meets bridge " + b.start + "->" + b.end + " at " + from + "->" + to + " dot=" + dot);
    }

    @Test
    void steepSlopeNoHairpin() throws IOException {
        // A slope steeper than a road may climb straight: the road must zigzag without coming back beside itself too high.
        SyntheticTerrain t = new SyntheticTerrain() {
            @Override protected int height(int x, int z) { return 64 + (int) Math.max(0, Math.min(24, (x - 40) * 0.8)); }
        };
        Golden.check("astar_steep_slope_no_hairpin", run(t, 0, 0, 120, 0, RoadType.VILLAGE_MAIN));
    }

    @Test
    void followsExistingMainRoad() throws IOException {
        // A main road 6 blocks beside the direct line: joining it and following it at half price beats a new road.
        SyntheticTerrain t = flat();
        String out = run(t, 0, 0, 200, 0, RoadType.VILLAGE_MAIN, straightRoad(10, 190, 6, RoadType.VILLAGE_MAIN));
        assertTrue(out.contains("follows a road true"), "the path must follow the existing road");
        Golden.check("astar_follows_main_road", out);
    }

    @Test
    void mainRoadIgnoresLoneTrail() {
        // A village main road never follows a lone trail: same path as with no network at all.
        SyntheticTerrain t = flat();
        String withTrail = run(t, 0, 0, 200, 0, RoadType.VILLAGE_MAIN, straightRoad(10, 190, 6, RoadType.LONE_TRAIL));
        assertEquals(run(flat(), 0, 0, 200, 0, RoadType.VILLAGE_MAIN, new RoadNetwork("empty")), withTrail);
    }

    private static SyntheticTerrain flat() {
        return new SyntheticTerrain() {
            @Override protected int height(int x, int z) { return 64; }
        };
    }

    private static RoadNetwork straightRoad(int x0, int x1, int z, RoadType type) {
        List<BlockPos> path = new ArrayList<>();
        for (int x = x0; x <= x1; x++) path.add(new BlockPos(x, 64, z));
        RoadNetwork net = new RoadNetwork("test");
        net.addRoadSegment(path, null, null, null, type, type.roadWidthBlocks());
        return net;
    }

    private static String run(SyntheticTerrain t, int sx, int sz, int gx, int gz, RoadType type) {
        return run(t, sx, sz, gx, gz, type, null);
    }

    private static String run(SyntheticTerrain t, int sx, int sz, int gx, int gz, RoadType type, RoadNetwork network) {
        BlockPos start = new BlockPos(sx, t.sample(sx, sz).surfaceY, sz);
        BlockPos goal = new BlockPos(gx, t.sample(gx, gz).surfaceY, gz);
        AStarSearch s = new AStarSearch(start, goal, t);
        s.setRoadType(type);
        s.setRoadNetwork(network);

        AStarSearch.StepResult r;
        do {
            r = s.advance(System.nanoTime() + 1_000_000_000L);
        } while (r == AStarSearch.StepResult.RUNNING);

        StringBuilder out = new StringBuilder();
        out.append("result ").append(r).append('\n');
        out.append("bridges ").append(s.getResult().getBridges().size()).append('\n');
        if (network != null) {
            out.append("follows a road ").append(!s.getResult().getFollowedXZ().isEmpty()).append('\n');
        }
        for (BridgeSpan b : s.getResult().getBridges()) {
            out.append("bridge ").append(b.start.getX()).append(' ').append(b.start.getZ())
                    .append(" -> ").append(b.end.getX()).append(' ').append(b.end.getZ())
                    .append(" deck ").append(b.startDeckSurfaceHalfY).append(' ').append(b.endDeckSurfaceHalfY)
                    .append(" crown ").append(b.crownHalf).append('\n');
        }
        for (BlockPos p : s.getResult().getPath()) {
            out.append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ()).append('\n');
        }
        return out.toString();
    }
}

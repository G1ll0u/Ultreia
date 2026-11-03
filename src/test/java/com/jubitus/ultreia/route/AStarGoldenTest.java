package com.jubitus.ultreia.route;

import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.testutil.Golden;
import com.jubitus.ultreia.testutil.SyntheticTerrain;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Golden paths on synthetic terrain, default config, bridges off (bridge creation reads the world;
 * a bridge golden comes with the bridge split). A refactor must keep every path identical.
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

    private static String run(SyntheticTerrain t, int sx, int sz, int gx, int gz, RoadType type) {
        BlockPos start = new BlockPos(sx, t.sample(sx, sz).surfaceY, sz);
        BlockPos goal = new BlockPos(gx, t.sample(gx, gz).surfaceY, gz);
        AStarPathfinder.Session s = new AStarPathfinder().startSession(null, start, goal, t);
        s.setRoadType(type);

        AStarPathfinder.StepResult r;
        do {
            r = s.advance(System.nanoTime() + 1_000_000_000L);
        } while (r == AStarPathfinder.StepResult.RUNNING);

        StringBuilder out = new StringBuilder();
        out.append("result ").append(r).append('\n');
        out.append("bridges ").append(s.getResult().getBridges().size()).append('\n');
        for (BlockPos p : s.getResult().getPath()) {
            out.append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ()).append('\n');
        }
        return out.toString();
    }
}

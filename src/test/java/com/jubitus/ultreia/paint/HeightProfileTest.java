package com.jubitus.ultreia.paint;

import com.jubitus.ultreia.bridge.BridgeGeometry;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.testutil.Golden;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Walkability: road and deck profiles never step more than the allowed half-blocks between cells. */
class HeightProfileTest {

    private static final int NONE = RoadHeightSolver.FIXED_NONE;

    @Test
    void solvedProfileRespectsStepCutFillAndFixedPoints() {
        int[] terrain = {130, 131, 134, 136, 137, 135, 132, 132, 134, 137, 138, 138};
        int[] fixed = new int[terrain.length];
        Arrays.fill(fixed, NONE);
        fixed[0] = 130;
        fixed[terrain.length - 1] = 138;

        int[] out = RoadHeightSolver.solveProfile(terrain, fixed, 4, 4, 1, 2);
        assertNotNull(out);
        assertEquals(130, out[0]);
        assertEquals(138, out[out.length - 1]);
        for (int i = 0; i < out.length; i++) {
            assertTrue(out[i] >= terrain[i] - 4 && out[i] <= terrain[i] + 4, "within cut/fill at " + i);
            if (i > 0) assertTrue(Math.abs(out[i] - out[i - 1]) <= 1, "step at " + i);
        }
    }

    @Test
    void impossibleProfileIsRejected() {
        int[] terrain = {100, 100, 120, 120};
        int[] fixed = {100, NONE, NONE, 120};
        assertNull(RoadHeightSolver.solveProfile(terrain, fixed, 2, 2, 1, 0));
    }

    @Test
    void deckProfileKeepsEndsAndStepLimit() {
        int maxStep = Math.max(1, HalfY.MAX_DECK_DELTA_HALF_PER_BLOCK);
        int[][] cases = {{130, 130, 20, 6, 4}, {130, 140, 30, 6, 4}, {150, 132, 25, 12, 4}, {130, 131, 3, 6, 4}};
        for (int[] c : cases) {
            int[] deck = BridgeGeometry.buildDeckHalfProfile(c[0], c[1], line(c[2]), c[3], c[4]);
            assertEquals(c[0], deck[0]);
            assertEquals(c[1], deck[deck.length - 1]);
            for (int i = 1; i < deck.length; i++) {
                assertTrue(Math.abs(deck[i] - deck[i - 1]) <= maxStep, "deck step at " + i + " in " + Arrays.toString(deck));
            }
        }
    }

    @Test
    void flatDeckGetsACrown() {
        int[] deck = BridgeGeometry.buildDeckHalfProfile(130, 130, line(20), 6, 4);
        assertTrue(deck[deck.length / 2] > 130);
    }

    @Test
    void profilesUnchanged() throws IOException {
        StringBuilder sb = new StringBuilder();
        int[] terrain = {130, 131, 134, 136, 137, 135, 132, 132, 134, 137, 138, 138};
        int[] fixed = new int[terrain.length];
        Arrays.fill(fixed, NONE);
        fixed[0] = 130;
        fixed[terrain.length - 1] = 138;
        sb.append("road r2 ").append(Arrays.toString(RoadHeightSolver.solveProfile(terrain, fixed, 4, 4, 1, 2))).append('\n');
        sb.append("road r0 ").append(Arrays.toString(RoadHeightSolver.solveProfile(terrain, fixed, 4, 4, 1, 0))).append('\n');
        sb.append("deck flat ").append(Arrays.toString(BridgeGeometry.buildDeckHalfProfile(130, 130, line(20), 6, 4))).append('\n');
        sb.append("deck rise ").append(Arrays.toString(BridgeGeometry.buildDeckHalfProfile(130, 140, line(30), 6, 4))).append('\n');
        sb.append("deck fall ").append(Arrays.toString(BridgeGeometry.buildDeckHalfProfile(150, 132, line(25), 12, 4))).append('\n');
        Golden.check("height_profiles", sb.toString());
    }

    private static List<BlockPos> line(int len) {
        List<BlockPos> l = new ArrayList<>();
        for (int i = 0; i <= len; i++) l.add(new BlockPos(i, 64, 0));
        return l;
    }
}

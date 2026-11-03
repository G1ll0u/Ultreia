package com.jubitus.ultreia.geom;

import com.jubitus.ultreia.testutil.Golden;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeomTest {

    private static final int[][] LINES = {
            {0, 0, 0, 0}, {0, 0, 7, 0}, {0, 0, 0, -5}, {0, 0, 6, 6}, {0, 0, 7, 3},
            {-3, 4, 5, -9}, {10, 10, -4, 2}, {-30000000, 5, -29999990, -2}
    };

    @Test
    void xzKeyRoundTrip() {
        int[] values = {0, 1, -1, 12345, -98765, 29999999, -30000000, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int x : values) {
            for (int z : values) {
                long k = Pos2D.xzKey(x, z);
                assertEquals(x, Pos2D.xFromKey(k));
                assertEquals(z, Pos2D.zFromKey(k));
            }
        }
    }

    @Test
    void snapDownAndDistance() {
        assertEquals(8, Pos2D.snapDown(9, 4));
        assertEquals(-12, Pos2D.snapDown(-9, 4));
        assertEquals(0, Pos2D.snapDown(0, 4));
        assertEquals(25.0, Pos2D.distSq(0, 0, 3, -4));
        assertEquals(2.0 * 60000000.0 * 60000000.0, Pos2D.distSq(-30000000, -30000000, 30000000, 30000000));
    }

    @Test
    void rasterLine8IsEightConnectedAndExact() {
        for (int[] l : LINES) {
            List<BlockPos> cells = LineRaster.eight(l[0], l[1], l[2], l[3], 0);
            assertEnds(cells, l);
            assertEquals(Math.max(Math.abs(l[2] - l[0]), Math.abs(l[3] - l[1])) + 1, cells.size());
            for (int i = 1; i < cells.size(); i++) {
                int dx = Math.abs(cells.get(i).getX() - cells.get(i - 1).getX());
                int dz = Math.abs(cells.get(i).getZ() - cells.get(i - 1).getZ());
                assertEquals(1, Math.max(dx, dz), "8-neighbour step");
            }
        }
    }

    @Test
    void supercoverStepsAreAdjacent() {
        for (int[] l : LINES) {
            List<BlockPos> cells = LineRaster.supercover(l[0], l[1], l[2], l[3], 0);
            assertEnds(cells, l);
            for (int i = 1; i < cells.size(); i++) {
                int dx = Math.abs(cells.get(i).getX() - cells.get(i - 1).getX());
                int dz = Math.abs(cells.get(i).getZ() - cells.get(i - 1).getZ());
                assertTrue(dx + dz >= 1 && dx <= 1 && dz <= 1, "adjacent step");
            }
        }
    }

    @Test
    void rasterOutputsUnchanged() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int[] l : LINES) {
            sb.append("line ").append(l[0]).append(',').append(l[1]).append(" -> ").append(l[2]).append(',').append(l[3]).append('\n');
            sb.append("  r8 ").append(fmt(LineRaster.eight(l[0], l[1], l[2], l[3], 0))).append('\n');
            sb.append("  sc ").append(fmt(LineRaster.supercover(l[0], l[1], l[2], l[3], 0))).append('\n');
        }
        Golden.check("geom_rasters", sb.toString());
    }

    private static void assertEnds(List<BlockPos> cells, int[] l) {
        assertEquals(l[0], cells.get(0).getX());
        assertEquals(l[1], cells.get(0).getZ());
        assertEquals(l[2], cells.get(cells.size() - 1).getX());
        assertEquals(l[3], cells.get(cells.size() - 1).getZ());
    }

    private static String fmt(List<BlockPos> cells) {
        StringBuilder sb = new StringBuilder();
        for (BlockPos p : cells) sb.append(p.getX()).append(',').append(p.getZ()).append(' ');
        return sb.toString().trim();
    }
}

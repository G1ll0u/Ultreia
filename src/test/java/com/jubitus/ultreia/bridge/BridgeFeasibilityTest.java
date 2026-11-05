package com.jubitus.ultreia.bridge;

import com.jubitus.ultreia.testutil.SyntheticTerrain;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeFeasibilityTest {

    /** Along +x: a pond, then low dry land the road could walk on, then a river. */
    private static final SyntheticTerrain POND_LAND_RIVER = new SyntheticTerrain() {
        @Override protected int height(int x, int z) {
            if (x >= 1 && x <= 2) return 62;
            if (x >= 3 && x <= 30) return 61;
            if (x >= 31 && x <= 50) return 60;
            return 64;
        }

        @Override protected boolean water(int x, int z) {
            return (x >= 1 && x <= 2) || (x >= 31 && x <= 50);
        }
    };

    @Test
    void refusesSpanOverLandBetweenPondAndRiver() {
        BridgeFeasibility.Result r = BridgeFeasibility.tryCreate(POND_LAND_RIVER,
                new BlockPos(0, 64, 0), new BlockPos(53, 64, 0));
        assertNull(r.span, "span accepted");
        assertTrue(r.reason.contains("landable"), r.reason);
    }

    @Test
    void acceptsSpanOverRiverOnly() {
        BridgeFeasibility.Result r = BridgeFeasibility.tryCreate(POND_LAND_RIVER,
                new BlockPos(30, 61, 0), new BlockPos(52, 64, 0));
        assertNotNull(r.span, r.reason);
    }
}

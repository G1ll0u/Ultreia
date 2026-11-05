package com.jubitus.ultreia.route;

import com.jubitus.ultreia.bridge.BridgeSpan;
import com.jubitus.ultreia.geom.LineRaster;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mountain bridge whose road climbed under its own deck to reach the start, then crossed back over itself. */
class OwnBridgeFootprintTest {

    private static final BlockPos A = new BlockPos(689, 103, -1046);
    private static final BlockPos B = new BlockPos(808, 99, -1106);
    private static final BridgeSpan SPAN = new BridgeSpan(A, B,
            LineRaster.forBridges(A.getX(), A.getZ(), B.getX(), B.getZ(), 0),
            206, 198, false, false, null, 0, 0);

    private static boolean under(int x, int z) {
        return PathProfileCheck.underOwnBridge(SPAN, 1, 2, x, z);
    }

    @Test
    void roadClimbingUnderTheDeckIsUnder() {
        assertTrue(under(701, -1054));
        assertTrue(under(706, -1054));
    }

    @Test
    void approachAndExitAreNotUnder() {
        assertFalse(under(685, -1048));
        assertFalse(under(687, -1046));
        assertFalse(under(813, -1108));
    }

    @Test
    void roadBesideTheBridgeIsNotUnder() {
        assertFalse(under(750, -1067));
    }
}

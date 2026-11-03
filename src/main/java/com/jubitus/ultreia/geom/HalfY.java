package com.jubitus.ultreia.geom;

/**
 * Walking-surface heights in half-blocks: a full block at Y has its surface at 2Y+2, a bottom slab at 2Y+1.
 * Walkability limits are in half-blocks (one half-slab per step at most).
 */
public final class HalfY {

    // Maximum height change per road block in HALF blocks.
    public static final int MAX_ROAD_STEP_HALF_PER_BLOCK = 1;
    // Max delta in bridge deck surface per block, in HALF blocks.
    public static final int MAX_DECK_DELTA_HALF_PER_BLOCK = 1;
    // Maximum allowed vertical difference with an existing road when merging, in HALF blocks.
    public static final int MAX_ROAD_JOIN_DELTA_HALF_BLOCKS = 1;

    private HalfY() {}

    /** Where a deck/road block goes for a surface: odd half = bottom slab at blockY, even = top slab (or full block) at blockY. */
    public static final class SlabPlacement {
        public final int blockY;
        public final boolean top;

        public SlabPlacement(int blockY, boolean top) {
            this.blockY = blockY;
            this.top = top;
        }
    }

    public static SlabPlacement slabPlacement(int surfaceHalfY) {
        if (isBottomSlab(surfaceHalfY)) {
            return new SlabPlacement((surfaceHalfY - 1) / 2, false);
        }
        return new SlabPlacement((surfaceHalfY / 2) - 1, true);
    }

    /** True when the surface sits half-way up a block (a bottom slab). */
    public static boolean isBottomSlab(int surfaceHalfY) {
        return (surfaceHalfY & 1) != 0;
    }

    /** Surface half-Y of a full block whose top is at block Y. */
    public static int ofBlockTop(int blockY) {
        return blockY * 2 + 2;
    }

    /** Surface half-Y of a bottom slab placed at block Y. */
    public static int ofBottomSlab(int blockY) {
        return blockY * 2 + 1;
    }

    public static int surfaceBlockY(int surfaceHalfY) {
        return (surfaceHalfY - 1) / 2;
    }

    public static int walkBlockYFloor(int surfaceHalfY) {
        return surfaceHalfY / 2;
    }

    /** Y of the block a road surface rests on: under the slab for a bottom slab, the block itself otherwise. */
    public static int supportTopY(int surfaceHalfY) {
        SlabPlacement sp = slabPlacement(surfaceHalfY);
        return isBottomSlab(surfaceHalfY) ? sp.blockY - 1 : sp.blockY;
    }
}

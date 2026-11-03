package com.jubitus.ultreia.geom;

/** Walkability limits, in half-blocks (one half-slab per step at most). */
public final class HalfY {

    // Maximum height change per road block in HALF blocks.
    public static final int MAX_ROAD_STEP_HALF_PER_BLOCK = 1;
    // Max delta in bridge deck surface per block, in HALF blocks.
    public static final int MAX_DECK_DELTA_HALF_PER_BLOCK = 1;
    // Maximum allowed vertical difference with an existing road when merging, in HALF blocks.
    public static final int MAX_ROAD_JOIN_DELTA_HALF_BLOCKS = 1;

    private HalfY() {}
}

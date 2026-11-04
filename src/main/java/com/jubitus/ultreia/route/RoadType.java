package com.jubitus.ultreia.route;

import com.jubitus.ultreia.config.UltreiaConfig;

/**
 * Road importance type set per A* session.
 * Controls slope tolerance, bridge costs, and road width.
 */
public enum RoadType {
    /** Main village road: wider, normal bridge costs. */
    VILLAGE_MAIN,
    /** Trail to a lone building: narrow, terrain-following, bridges strongly discouraged. */
    LONE_TRAIL;

    // Search radius (blocks) used to detect a nearby existing road when deciding to merge a LONE_TRAIL.
    public static final int LONE_TRAIL_MERGE_SEARCH_RADIUS = 5;

    /** Widest painted width in blocks; roadEdgeVariation may trim one block on stretches. */
    public int roadWidthBlocks() {
        int w = Math.max(1, UltreiaConfig.PAINTING.roadWidth);
        if ((w % 2) == 0) w++;
        if (this == LONE_TRAIL) w = Math.max(1, w - 2);
        return w;
    }
}

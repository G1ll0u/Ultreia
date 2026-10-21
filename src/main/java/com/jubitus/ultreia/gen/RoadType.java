package com.jubitus.ultreia.gen;

import com.jubitus.ultreia.config.MPBConfig;

/**
 * Road importance type set per A* session.
 * Controls slope tolerance, bridge costs, and road width.
 */
public enum RoadType {
    /** Main village road: wider, allows earthworks, normal bridge costs. */
    VILLAGE_MAIN,
    /** Trail to a lone building: narrow, terrain-following, bridges strongly discouraged. */
    LONE_TRAIL;

    /** Widest painted width in blocks; roadEdgeVariation may trim one block on stretches. */
    public int roadWidthBlocks() {
        int w = Math.max(1, MPBConfig.PAINTING.roadWidth);
        if ((w % 2) == 0) w++;
        if (this == LONE_TRAIL && MPBConfig.ROAD_HIERARCHY.enabled) w = Math.max(1, w - 2);
        return w;
    }
}

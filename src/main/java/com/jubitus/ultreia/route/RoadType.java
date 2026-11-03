package com.jubitus.ultreia.route;

import com.jubitus.ultreia.config.UltreiaConfig;

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
        int w = Math.max(1, UltreiaConfig.PAINTING.roadWidth);
        if ((w % 2) == 0) w++;
        if (this == LONE_TRAIL && UltreiaConfig.ROAD_HIERARCHY.enabled) w = Math.max(1, w - 2);
        return w;
    }

    /** Whether roads of this type may cut and fill terrain (pathfinding, profile and painting alike). */
    public boolean allowsEarthworks() {
        if (!UltreiaConfig.PATHFINDING.allowEarthworks) return false;
        return !(this == LONE_TRAIL && UltreiaConfig.ROAD_HIERARCHY.enabled
                && !UltreiaConfig.ROAD_HIERARCHY.loneTrailEarthworksEnabled);
    }
}

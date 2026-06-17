package com.jubitus.ultreia.gen;

/**
 * Road importance type set per A* session.
 * Controls slope tolerance, bridge costs, and road width.
 */
public enum RoadType {
    /** Main village road: wider, allows earthworks, normal bridge costs. */
    VILLAGE_MAIN,
    /** Trail to a lone building: narrow, terrain-following, bridges strongly discouraged. */
    LONE_TRAIL
}

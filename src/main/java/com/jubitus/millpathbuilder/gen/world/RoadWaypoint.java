package com.jubitus.millpathbuilder.gen.world;

/** A single point in the road network with its world position and type. */
public final class RoadWaypoint {

    public final int x;
    public final int y;
    public final int z;
    /** True if this waypoint is part of a bridge deck (elevated over water or valley). */
    public final boolean isBridge;

    public RoadWaypoint(int x, int y, int z, boolean isBridge) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.isBridge = isBridge;
    }

    public static long packXZ(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xFFFFFFFFL);
    }

    public long xzKey() {
        return packXZ(x, z);
    }

    @Override
    public String toString() {
        return "(" + x + "," + y + "," + z + (isBridge ? ",bridge" : "") + ")";
    }
}

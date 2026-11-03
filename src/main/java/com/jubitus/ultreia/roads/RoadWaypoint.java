package com.jubitus.ultreia.roads;

import com.jubitus.ultreia.geom.Pos2D;

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

    public long xzKey() {
        return Pos2D.xzKey(x, z);
    }

    @Override
    public String toString() {
        return "(" + x + "," + y + "," + z + (isBridge ? ",bridge" : "") + ")";
    }
}

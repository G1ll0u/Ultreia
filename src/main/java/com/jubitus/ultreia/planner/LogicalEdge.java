package com.jubitus.ultreia.planner;

/** A logical road edge in the planned network (pre-pathfinding). Used for entity navigation. */
public final class LogicalEdge {
    public final int x1, z1;
    public final int x2, z2;
    public final boolean isLoneTrail;

    public LogicalEdge(int x1, int z1, int x2, int z2, boolean isLoneTrail) {
        this.x1 = x1; this.z1 = z1;
        this.x2 = x2; this.z2 = z2;
        this.isLoneTrail = isLoneTrail;
    }

}

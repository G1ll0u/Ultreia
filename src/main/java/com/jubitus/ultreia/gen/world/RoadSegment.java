package com.jubitus.ultreia.gen.world;

import com.jubitus.ultreia.gen.RoadType;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.List;

/**
 * An ordered sequence of waypoints representing one road or bridge segment.
 * Waypoints are subsampled every few blocks for efficiency; entity navigation
 * interpolates between them.
 */
public final class RoadSegment {

    public final int id;
    public final List<RoadWaypoint> waypoints;
    /** True if this segment is primarily a bridge deck. */
    public final boolean isBridge;
    /** Village center (XZ) this segment originates from, or null. */
    @Nullable public final BlockPos fromVillage;
    /** Village center (XZ) this segment leads to, or null. */
    @Nullable public final BlockPos toVillage;

    /** Road type of the route this segment belongs to (a bridge takes its route's type). */
    public final RoadType roadType;
    /** Width actually painted, in blocks (deck width for a bridge). */
    public final int widthBlocks;

    public RoadSegment(int id,
                       List<RoadWaypoint> waypoints,
                       boolean isBridge,
                       @Nullable BlockPos fromVillage,
                       @Nullable BlockPos toVillage,
                       RoadType roadType,
                       int widthBlocks) {
        this.id = id;
        this.waypoints = waypoints;
        this.isBridge = isBridge;
        this.fromVillage = fromVillage;
        this.toVillage = toVillage;
        this.roadType = roadType;
        this.widthBlocks = widthBlocks;
    }

    public int startX() { return waypoints.isEmpty() ? 0 : waypoints.get(0).x; }
    public int startZ() { return waypoints.isEmpty() ? 0 : waypoints.get(0).z; }
    public int endX()   { return waypoints.isEmpty() ? 0 : waypoints.get(waypoints.size() - 1).x; }
    public int endZ()   { return waypoints.isEmpty() ? 0 : waypoints.get(waypoints.size() - 1).z; }

    public int lengthBlocks() {
        if (waypoints.size() < 2) return 0;
        RoadWaypoint a = waypoints.get(0), b = waypoints.get(waypoints.size() - 1);
        int dx = b.x - a.x, dz = b.z - a.z;
        return (int) Math.round(Math.sqrt(dx * dx + dz * dz));
    }
}

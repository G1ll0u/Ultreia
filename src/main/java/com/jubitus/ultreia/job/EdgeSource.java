package com.jubitus.ultreia.job;

import com.jubitus.ultreia.route.AStarPathfinder;
import com.jubitus.ultreia.route.RoadType;
import com.jubitus.ultreia.route.VillageGateField;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/** Where a road job takes its edges from (village network, manual path) and how it records their outcome. */
interface EdgeSource {

    /** One edge to path and paint, with its resolved endpoints. */
    final class EdgeRequest {
        final BlockPos start;
        final BlockPos goal;
        final RoadType roadType;
        // Villages recorded on the placed road segments (null for a manual path).
        @Nullable final BlockPos fromVillage;
        @Nullable final BlockPos toVillage;
        // A* may end at any of these gates of the destination village.
        @Nullable final VillageGateField destinationGates;
        // A* ends at the first existing road it reaches.
        final boolean endAtExistingRoad;

        EdgeRequest(BlockPos start, BlockPos goal, RoadType roadType,
                    @Nullable BlockPos fromVillage, @Nullable BlockPos toVillage,
                    @Nullable VillageGateField destinationGates, boolean endAtExistingRoad) {
            this.start = start;
            this.goal = goal;
            this.roadType = roadType;
            this.fromVillage = fromVillage;
            this.toVillage = toVillage;
            this.destinationGates = destinationGates;
            this.endAtExistingRoad = endAtExistingRoad;
        }
    }

    boolean hasMore();

    /** The next edge to path, or null when the source settled the edge itself (skipped, already connected). */
    @Nullable
    EdgeRequest next();

    /** A* found a path (full or partial) for the current edge. */
    void onPathFound(AStarPathfinder.Session session);

    /** The current edge is finished; reason is null on success. */
    void onEdgeDone(@Nullable String failureReason);

    String statusLine(RoadJob.Phase phase);

    String completionMessage();

    /** The job ended normally or was cancelled. */
    void onJobComplete();

    void onServerStopping();

    void shutdown();
}

package com.jubitus.ultreia.job;

import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.route.AStarPathfinder;
import com.jubitus.ultreia.route.EndpointResolver;
import com.jubitus.ultreia.route.RoadType;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/** One road between two points chosen by a player; its ends are kept exactly where the player put them. */
final class ManualPathSource implements EdgeSource {

    // Search radius (blocks) for open ground around each chosen point.
    private static final int ANCHOR_RADIUS = 16;

    private final JobContext ctx;
    private final BlockPos requestedStart;
    private final BlockPos requestedGoal;
    private final RoadType roadType;

    @Nullable private BlockPos start;
    @Nullable private BlockPos goal;
    private boolean issued = false;
    private boolean failed = false;

    ManualPathSource(JobContext ctx, BlockPos start, BlockPos goal, @Nullable RoadType roadType) {
        this.ctx = ctx;
        this.requestedStart = start;
        this.requestedGoal = goal;
        this.roadType = roadType != null ? roadType : RoadType.VILLAGE_MAIN;
    }

    @Override
    public boolean hasMore() {
        return !issued;
    }

    @Nullable
    @Override
    public EdgeRequest next() {
        issued = true;
        start = EndpointResolver.findAnchor(ctx.world, requestedStart, ctx.sampler, ANCHOR_RADIUS);
        goal = EndpointResolver.findAnchor(ctx.world, requestedGoal, ctx.sampler, ANCHOR_RADIUS);

        if (Pos2D.sameXZ(start, goal)) {
            ctx.msg("Manual path skipped: start and end resolve to the same anchor.");
            return null;
        }

        ctx.scanRoadsAround(start);
        ctx.scanRoadsAround(goal);

        ctx.msg("Manual pathfinding: " + endpoints());
        return new EdgeRequest(start, goal, roadType, null, null, null, false);
    }

    @Override
    public void onPathFound(AStarPathfinder.Session session) {
    }

    @Override
    public void onEdgeDone(@Nullable String failureReason) {
        if (failureReason == null) {
            ctx.msg("Manual path OK.");
        } else {
            failed = true;
            ctx.msg("Manual path FAILED: " + failureReason);
        }
    }

    @Override
    public String statusLine(RoadJob.Phase phase) {
        if (phase == RoadJob.Phase.PATHFINDING) return "Manual path job pathfinding: " + endpoints();
        if (phase == RoadJob.Phase.PAINTING) return "Manual path job painting: " + endpoints();
        return issued ? "Manual path job running." : "Manual path job preparing.";
    }

    private String endpoints() {
        return "(" + start.getX() + "," + start.getZ() + ") -> (" + goal.getX() + "," + goal.getZ() + ")";
    }

    @Override
    public String completionMessage() {
        return failed
                ? "Ultreïa: custom path generation finished with failure."
                : "Ultreïa: custom path generation completed.";
    }

    @Override
    public void onJobComplete() {
    }

    @Override
    public void onServerStopping() {
    }

    @Override
    public void shutdown() {
    }
}

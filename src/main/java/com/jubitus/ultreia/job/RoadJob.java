package com.jubitus.ultreia.job;

import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.paint.RoadPainter;
import com.jubitus.ultreia.route.AStarDiagnostics;
import com.jubitus.ultreia.route.AStarSearch;
import com.jubitus.ultreia.route.PathResult;
import com.jubitus.ultreia.route.RoadType;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.List;

/** The generation job: takes edges from its source and runs each one through A* then the painter, inside the tick budget. */
public final class RoadJob {

    enum Phase { BETWEEN_EDGES, PATHFINDING, PAINTING }

    private final JobContext ctx;
    private final EdgeSource source;

    @Nullable private EdgeSource.EdgeRequest cur;
    @Nullable private AStarSearch session;
    @Nullable private RoadPainter.Session painter;
    private boolean done = false;

    private RoadJob(JobContext ctx, EdgeSource source) {
        this.ctx = ctx;
        this.source = source;
    }

    static RoadJob villageNetwork(WorldServer world, ICommandSender sender) {
        JobContext ctx = new JobContext(world, sender);
        return new RoadJob(ctx, new VillageNetworkSource(ctx));
    }

    static RoadJob resume(WorldServer world, JobCheckpoint checkpoint) {
        JobContext ctx = new JobContext(world, null);
        return new RoadJob(ctx, new VillageNetworkSource(ctx, checkpoint));
    }

    static RoadJob manualPath(WorldServer world, ICommandSender sender, BlockPos start, BlockPos goal, RoadType roadType) {
        JobContext ctx = new JobContext(world, sender);
        return new RoadJob(ctx, new ManualPathSource(ctx, start, goal, roadType));
    }

    int getDim() {
        return ctx.dim();
    }

    @Nullable
    ICommandSender sender() {
        return ctx.senderRef.get();
    }

    boolean isDone() {
        return done;
    }

    String statusLine() {
        Phase phase = session != null ? Phase.PATHFINDING : painter != null ? Phase.PAINTING : Phase.BETWEEN_EDGES;
        return source.statusLine(phase);
    }

    String completionMessage() {
        return source.completionMessage();
    }

    void onJobComplete() {
        source.onJobComplete();
    }

    void onServerStopping() {
        source.onServerStopping();
    }

    void tick(long budgetNs) {
        if (done) return;
        long end = System.nanoTime() + budgetNs;

        while (System.nanoTime() < end && !done) {
            if (session == null && painter == null) {
                if (!source.hasMore()) {
                    done = true;
                    return;
                }
                cur = source.next();
                if (cur != null) startPathfinding(cur);
                continue;
            }

            if (session != null) {
                AStarSearch.StepResult sr = session.advance(end);
                if (sr == AStarSearch.StepResult.RUNNING) return;

                if (sr == AStarSearch.StepResult.FOUND || sr == AStarSearch.StepResult.PARTIAL) {
                    if (sr == AStarSearch.StepResult.PARTIAL) ctx.msg("  PARTIAL: " + session.partialSummary());
                    source.onPathFound(session);
                    PathResult result = session.getResult();
                    session = null;

                    // A* chunks are released before painting: the path needs far fewer than the search area.
                    ctx.releaseEdge();

                    List<BlockPos> coarse = result.getPath();
                    if (coarse == null || coarse.isEmpty()) {
                        finishEdge("pathfinding returned an empty path");
                        continue;
                    }
                    startPainting(coarse, result);
                    continue;
                }

                String reason = "pathfinding: " + session.failureSummary(UltreiaConfig.DEBUG.logPathfinding);
                if (UltreiaConfig.DEBUG.logPathfinding) {
                    ctx.msg("  start " + session.debugStartArea(ctx.protectedAreas));
                    ctx.msg("  goal  " + session.debugGoalArea(ctx.protectedAreas));
                }
                session = null;
                finishEdge(reason);
                continue;
            }

            RoadPainter.PaintResult pr = painter.advance(end);
            if (pr == RoadPainter.PaintResult.RUNNING) return;
            String reason = pr == RoadPainter.PaintResult.OK ? null : "painting: " + painter.failureReason();
            painter = null;
            finishEdge(reason);
        }
    }

    private void startPathfinding(EdgeSource.EdgeRequest req) {
        if (UltreiaConfig.DEBUG.logPathfinding) {
            ctx.msg("  " + AStarDiagnostics.describeEndpoint("start", ctx.sampler, ctx.protectedAreas, req.start));
            ctx.msg("  " + AStarDiagnostics.describeEndpoint("goal", ctx.sampler, ctx.protectedAreas, req.goal));
        }
        session = new AStarSearch(req.start, req.goal, ctx.sampler);
        session.setRoadNetwork(ctx.roadNetwork);
        session.setRoadType(req.roadType);
        if (req.destinationGates != null) session.setDestinationGates(req.destinationGates);
        if (req.endAtExistingRoad) session.setSnapToRoad(ctx.roadIndex, RoadType.LONE_TRAIL_MERGE_SEARCH_RADIUS);
    }

    private void startPainting(List<BlockPos> coarse, PathResult result) {
        painter = RoadPainter.start(ctx.world, coarse, result.getBridges(), ctx.sampler, ctx.protectedAreas,
                ctx.roadIndex, false, true);
        painter.setRoadNetwork(ctx.roadNetwork, cur.fromVillage, cur.toVillage);
        painter.setRoadType(cur.roadType);
    }

    private void finishEdge(@Nullable String failureReason) {
        ctx.releaseEdge();
        source.onEdgeDone(failureReason);
        cur = null;
    }

    void shutdown() {
        try {
            ctx.sampler.clearCache();
        } catch (Throwable ignored) {
        }
        try {
            ctx.chunkManager.close(ctx.world);
        } catch (Throwable ignored) {
        }
        try {
            source.shutdown();
        } catch (Throwable ignored) {
        }
    }
}

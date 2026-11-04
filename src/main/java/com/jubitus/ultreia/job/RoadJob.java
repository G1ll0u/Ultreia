package com.jubitus.ultreia.job;

import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.paint.RoadPaintSession;
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
    @Nullable private RoadPaintSession painter;
    private boolean done = false;
    private final EdgePerf perf = new EdgePerf();

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
        long tickStart = System.nanoTime();
        try {
            tickWithin(tickStart + budgetNs);
        } finally {
            perf.addTick(System.nanoTime() - tickStart);
        }
    }

    private void tickWithin(long end) {
        while (System.nanoTime() < end && !done) {
            if (session == null && painter == null) {
                if (!source.hasMore()) {
                    done = true;
                    return;
                }
                perf.startEdge();
                long t = System.nanoTime();
                cur = source.next();
                perf.add(EdgePerf.Step.PREP, t);
                if (cur != null) startPathfinding(cur);
                continue;
            }

            if (session != null) {
                long t = System.nanoTime();
                AStarSearch.StepResult sr = session.advance(end);
                perf.add(EdgePerf.Step.SEARCH, t);
                if (sr == AStarSearch.StepResult.RUNNING) return;

                if (sr == AStarSearch.StepResult.FOUND || sr == AStarSearch.StepResult.PARTIAL) {
                    if (sr == AStarSearch.StepResult.PARTIAL) ctx.msg("  PARTIAL: " + session.partialSummary());
                    source.onPathFound(session);
                    PathResult result = session.getResult();
                    session = null;

                    // A* chunks are released before painting: the path needs far fewer than the search area.
                    long tr = System.nanoTime();
                    ctx.releaseEdge();
                    perf.add(EdgePerf.Step.RELEASE, tr);

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

            long t = System.nanoTime();
            RoadPaintSession.PaintResult pr = painter.advance(end);
            perf.add(EdgePerf.Step.PAINT, t);
            if (pr == RoadPaintSession.PaintResult.RUNNING) return;
            String reason = pr == RoadPaintSession.PaintResult.OK ? null : "painting: " + painter.failureReason();
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
        painter = new RoadPaintSession(ctx.world, coarse, result.getBridges(), ctx.sampler, ctx.protectedAreas,
                ctx.roadIndex);
        painter.setRoadNetwork(ctx.roadNetwork, cur.fromVillage, cur.toVillage);
        painter.setRoadType(cur.roadType);
        painter.setFollowedRoads(result.getFollowedXZ());
    }

    private void finishEdge(@Nullable String failureReason) {
        long t = System.nanoTime();
        ctx.releaseEdge();
        perf.add(EdgePerf.Step.RELEASE, t);
        t = System.nanoTime();
        source.onEdgeDone(failureReason);
        perf.add(EdgePerf.Step.SAVE, t);
        if (UltreiaConfig.DEBUG.logPerformance && cur != null) perf.log(cur.start, cur.goal, ctx.chunkManager);
        cur = null;
    }

    void shutdown() {
        long t = System.nanoTime();
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
        if (UltreiaConfig.DEBUG.logPerformance) {
            com.jubitus.ultreia.Ultreia.LOGGER.info("[Ultreia] Perf job close: {} ms", EdgePerf.ms(System.nanoTime() - t));
        }
    }
}

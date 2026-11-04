package com.jubitus.ultreia.job;

import com.jubitus.ultreia.mill.Villages;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.planner.NetworkPlanData;
import com.jubitus.ultreia.planner.NetworkPlanner;
import com.jubitus.ultreia.roads.RoadJoins;
import com.jubitus.ultreia.route.AStarSearch;
import com.jubitus.ultreia.route.EndpointResolver;
import com.jubitus.ultreia.route.RoadType;
import com.jubitus.ultreia.route.VillageGateField;
import com.jubitus.ultreia.route.VillageGateTracker;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Edges of the planned village network: gates, lone-building spurs, edges already built, checkpoint for resuming.
 * The network is planned once when the job starts (or read back from the checkpoint).
 */
final class VillageNetworkSource implements EdgeSource {

    // A lone building within this distance (blocks) of an existing road gets a short spur to it.
    private static final int LONE_SPUR_RADIUS = 28;

    private final JobContext ctx;
    private final NetworkPlanData saved;
    private final JobCheckpoint checkpoint;
    private final List<NetworkPlanner.Edge> edges;
    private final VillageGateTracker gateTracker = new VillageGateTracker();

    private int edgeIndex = 0;
    private int built = 0, skipped = 0, failed = 0;

    // Current edge and its gate endpoints; the tracker is updated once A* succeeds.
    @Nullable private NetworkPlanner.Edge curEdge;
    @Nullable private BlockPos curGateA;
    @Nullable private BlockPos curGateB;
    private boolean curAIsVillage = false;
    private boolean curBIsVillage = false;

    /** Fresh start: plans the network over every known village and lone building. */
    VillageNetworkSource(JobContext ctx) {
        this.ctx = ctx;
        this.saved = NetworkPlanData.get(ctx.world);
        this.checkpoint = JobCheckpoint.get(ctx.world);

        Villages.logNodeSummary(ctx.world);
        List<BlockPos> nodes = Villages.nodes(ctx.world);
        if (nodes.size() < 2) {
            ctx.msg("Not enough nodes (need at least 2).");
            this.edges = new ArrayList<>();
            return;
        }

        this.edges = NetworkPlanner.build(ctx.world, nodes).edges;
        ctx.msg("Edges planned: " + edges.size());
        Ultreia.LOGGER.info("Async generation: nodes={}, edges={}", nodes.size(), edges.size());

        // Persist the edge list immediately so a crash during the first edge is recoverable.
        checkpoint.save(edges, 0, 0, 0, 0);
        flushData();
    }

    /** Resume: rebuilds the edge list and counters from the checkpoint instead of planning again. */
    VillageNetworkSource(JobContext ctx, JobCheckpoint cp) {
        this.ctx = ctx;
        this.saved = NetworkPlanData.get(ctx.world);
        this.checkpoint = cp;
        this.edgeIndex = cp.getEdgeIndex();
        this.built = cp.getBuilt();
        this.skipped = cp.getSkipped();
        this.failed = cp.getFailed();

        Villages.logNodeSummary(ctx.world);

        List<BlockPos[]> stored = cp.getEdges();
        this.edges = new ArrayList<>(stored.size());
        for (BlockPos[] pair : stored) {
            this.edges.add(new NetworkPlanner.Edge(pair[0], pair[1]));
        }

        if (edges.isEmpty()) {
            Ultreia.LOGGER.warn("[Ultreia] Resumed job has no edges — marking complete.");
            return;
        }
        Ultreia.LOGGER.info("[Ultreia] Resumed generation: edges={}, resuming at {}/{}, built={} skipped={} failed={}",
                edges.size(), edgeIndex, edges.size(), built, skipped, failed);
    }

    @Override
    public boolean hasMore() {
        return edgeIndex < edges.size();
    }

    @Nullable
    @Override
    public EdgeRequest next() {
        NetworkPlanner.Edge e = edges.get(edgeIndex);
        String key = e.key();

        if (saved.isBuilt(key)) {
            skipped++;
            settleEdge();
            return null;
        }

        Villages.Settlement viA = Villages.at(ctx.world, e.a);
        Villages.Settlement viB = Villages.at(ctx.world, e.b);

        boolean aIsLone = (viA != null && !viA.isVillage);
        boolean bIsLone = (viB != null && !viB.isVillage);
        boolean aIsVillage = (viA != null && viA.isVillage);
        boolean bIsVillage = (viB != null && viB.isVillage);

        // At least one lone building endpoint → narrow terrain-following trail.
        RoadType roadType = (aIsLone || bIsLone) ? RoadType.LONE_TRAIL : RoadType.VILLAGE_MAIN;
        if (UltreiaConfig.DEBUG.logPathfinding) {
            Ultreia.LOGGER.info("[Ultreia] Edge {} roadType={} aIsLone={} bIsLone={}", key, roadType, aIsLone, bIsLone);
        }

        // Each edge resolves its own gate, weighted by prior usage so edges spread across the available gates.
        VillageGateField[] gateFieldA = new VillageGateField[1];
        VillageGateField[] gateFieldB = new VillageGateField[1];

        BlockPos a0 = EndpointResolver.resolveEndpointAnchor(
                ctx.world, e.a, e.b, ctx.sampler, ctx.protectedAreas,
                aIsVillage ? gateTracker : null,
                aIsVillage ? gateFieldA : null);

        BlockPos b0 = EndpointResolver.resolveEndpointAnchor(
                ctx.world, e.b, e.a, ctx.sampler, ctx.protectedAreas,
                bIsVillage ? gateTracker : null,
                bIsVillage ? gateFieldB : null);

        if (UltreiaConfig.DEBUG.logPathfinding) {
            ctx.msg("  anchorA node=(" + e.a.getX() + "," + e.a.getZ() + ") resolved=(" + a0.getX() + "," + a0.getY() + "," + a0.getZ() + ")");
            ctx.msg("  anchorB node=(" + e.b.getX() + "," + e.b.getZ() + ") resolved=(" + b0.getX() + "," + b0.getY() + "," + b0.getZ() + ")");
        }

        ctx.scanRoadsAround(a0);
        ctx.scanRoadsAround(b0);
        BlockPos nearA = ctx.roadIndex.findNearestRoad(a0.getX(), a0.getZ(), LONE_SPUR_RADIUS);
        BlockPos nearB = ctx.roadIndex.findNearestRoad(b0.getX(), b0.getZ(), LONE_SPUR_RADIUS);

        // Lone building near an existing road: a short spur to that road instead of the full edge.
        if (aIsLone && nearA != null && !ctx.protectedAreas.contains(nearA.getX(), nearA.getZ())) {
            BlockPos spurGoal = RoadJoins.resolveSnappedApproachAnchor(
                    ctx.world, ctx.sampler, ctx.roadIndex, a0, e.b, nearA, ctx.protectedAreas);
            if (spurGoal != null) b0 = spurGoal;
        } else if (bIsLone && nearB != null && !ctx.protectedAreas.contains(nearB.getX(), nearB.getZ())) {
            BlockPos spurGoal = RoadJoins.resolveSnappedApproachAnchor(
                    ctx.world, ctx.sampler, ctx.roadIndex, b0, e.a, nearB, ctx.protectedAreas);
            if (spurGoal != null) a0 = spurGoal;
        }
        // Village endpoints are already on their Millénaire path entry: no further snapping.

        if (a0.getX() == b0.getX() && a0.getZ() == b0.getZ()) {
            saved.markBuilt(key);
            built++;
            ctx.msg("  OK (already connected via existing road)");
            settleEdge();
            return null;
        }

        ctx.msg("Pathfinding: " + key + " (" + (edgeIndex + 1) + "/" + edges.size() + ")"
                + " endpoints=(" + a0.getX() + "," + a0.getZ() + ")->(" + b0.getX() + "," + b0.getZ() + ")");

        curEdge = e;
        curGateA = a0;
        curGateB = b0;
        curAIsVillage = aIsVillage;
        curBIsVillage = bIsVillage;

        // LONE_TRAIL: A* ends at the nearest existing road instead of the destination.
        boolean endAtRoad = roadType == RoadType.LONE_TRAIL;
        return new EdgeRequest(a0, b0, roadType, e.a, e.b, gateFieldB[0], endAtRoad);
    }

    @Override
    public void onPathFound(AStarSearch session) {
        // Record the gates actually used (A* may reach a different destination gate than planned).
        if (curAIsVillage && curGateA != null) gateTracker.markUsed(curGateA);
        if (curBIsVillage) {
            BlockPos reached = session.getReachedGate();
            gateTracker.markUsed(reached != null ? reached : curGateB);
        }
    }

    @Override
    public void onEdgeDone(@Nullable String failureReason) {
        if (failureReason == null) {
            saved.markBuilt(curEdge.key());
            built++;
            ctx.msg("  OK");
        } else {
            failed++;
            ctx.msg("  FAILED: " + failureReason);
        }
        curEdge = null;
        edgeIndex++;
        saveProgress();
    }

    /** An edge finished without pathing (already built, already connected). */
    private void settleEdge() {
        edgeIndex++;
        ctx.releaseEdge();
        saveProgress();
    }

    private void saveProgress() {
        checkpoint.save(edges, edgeIndex, built, skipped, failed);
        flushData();
    }

    // Flush all dirty WorldSavedData to disk immediately.
    private void flushData() {
        try {
            ctx.world.getPerWorldStorage().saveAllData();
        } catch (Throwable t) {
            Ultreia.LOGGER.warn("[Ultreia] Force-save failed: {}", t.getMessage());
        }
    }

    @Override
    public String statusLine(RoadJob.Phase phase) {
        return "Running. edge=" + edgeIndex + "/" + edges.size() + " built=" + built + " skipped=" + skipped
                + " failed=" + failed + (curEdge != null ? (" current=" + curEdge.key()) : "");
    }

    @Override
    public String completionMessage() {
        return "Ultreïa: generation completed. built=" + built + " skipped=" + skipped + " failed=" + failed;
    }

    @Override
    public void onJobComplete() {
        checkpoint.clear();
        flushData();
    }

    @Override
    public void onServerStopping() {
        checkpoint.save(edges, edgeIndex, built, skipped, failed);
        flushData();
    }

    @Override
    public void shutdown() {
        saved.markDirty();
    }
}

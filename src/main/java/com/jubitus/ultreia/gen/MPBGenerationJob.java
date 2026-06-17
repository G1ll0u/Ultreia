package com.jubitus.ultreia.gen;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.MPBConfig;
import com.jubitus.ultreia.gen.bridge.BridgeAwarePathResult;
import com.jubitus.ultreia.gen.bridge.BridgeSpan;
import com.jubitus.ultreia.gen.bridge.MPBRoadConnectionHelper;
import com.jubitus.ultreia.gen.bridge.template.BridgeTemplateRegistry;
import com.jubitus.ultreia.gen.mill.MillBuildingObstacles2;
import com.jubitus.ultreia.gen.mill.MillVillageIndex;
import com.jubitus.ultreia.gen.world.MPBRoadIndexData;
import com.jubitus.ultreia.gen.world.MPBRoadNetwork;
import com.jubitus.ultreia.integration.MillIntegration;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public final class MPBGenerationJob implements MPBAsyncJob {

    private final WorldServer world;
    private final WeakReference<ICommandSender> senderRef;
    private final boolean force;
    private final boolean dryRun;

    private final MPBWorldData saved;
    private final MPBJobCheckpoint checkpoint;
    private final TerrainSampler sampler;
    private final MPBProtectedAreas protectedAreas;

    private final List<BlockPos> nodes;
    private final Long2ObjectOpenHashMap<BlockPos> anchorByXZ = new Long2ObjectOpenHashMap<>();
    private final VillageGateTracker gateTracker = new VillageGateTracker();
    private final List<PathNetworkBuilder.Edge> edges;

    private final AStarPathfinder astar = new AStarPathfinder();

    private int edgeIndex = 0;
    private int built = 0, skipped = 0, failed = 0;

    private PathNetworkBuilder.Edge curEdge;
    private AStarPathfinder.Session curSession;
    private RoadPainter.Session curPainter;
    @Nullable private BlockPos curFromVillage;
    @Nullable private BlockPos curToVillage;
    private RoadType curRoadType = RoadType.VILLAGE_MAIN;
    // Gate endpoints resolved for the current edge; recorded to update the tracker after success.
    @Nullable private BlockPos curGateA;
    @Nullable private BlockPos curGateB;
    private boolean curAIsVillage = false;
    private boolean curBIsVillage = false;

    private boolean done = false;

    private final MPBChunkManager chunkManager;

    private final MPBRoadIndexData roadIndex;
    private final MPBRoadNetwork roadNetwork;

    // Fresh-start constructor
    public MPBGenerationJob(WorldServer world, ICommandSender sender, boolean force, boolean dryRun) {
        this.world = world;
        this.senderRef = new WeakReference<>(sender);
        this.force = force;
        this.dryRun = dryRun;

        BridgeTemplateRegistry.invalidateTemplateCache();
        this.chunkManager = new MPBChunkManager(MPBConfig.GENERAL.maxForcedChunksPerJob);
        this.sampler = new TerrainSampler(world);
        this.sampler.setChunkManager(chunkManager);

        MillIntegration.logNodeSummary(world);
        this.nodes = MillIntegration.getAllNodePositions(world);
        this.saved = MPBWorldData.get(world);
        this.checkpoint = MPBJobCheckpoint.get(world);

        this.roadIndex = MPBRoadIndexData.get(world);
        this.roadIndex.attachChunkManager(this.chunkManager);
        this.sampler.setRoadIndex(this.roadIndex);
        this.roadNetwork = MPBRoadNetwork.get(world);

        this.protectedAreas = buildProtectedAreas(world, sampler);
        this.sampler.setProtectedAreas(this.protectedAreas);

        for (BlockPos n : nodes) {
            anchorByXZ.put(xzKey(n), findAnchor(world, n, sampler, 32));
        }

        if (nodes.size() < 2) {
            msg("Not enough nodes (need at least 2).");
            this.edges = new ArrayList<>();
            this.done = true;
            return;
        }

        this.edges = PathNetworkBuilder.build(world, nodes).edges;
        msg("Edges planned: " + edges.size());
        Ultreia.LOGGER.info("Async generation: nodes={}, edges={}", nodes.size(), edges.size());

        // Persist the edge list immediately so a crash during the first edge is recoverable.
        if (!dryRun) {
            checkpoint.save(edges, 0, 0, 0, 0, force, false);
            flushData();
        }
    }

    // Resume constructor — restores an interrupted job from a persisted checkpoint.
    MPBGenerationJob(WorldServer world, MPBJobCheckpoint cp) {
        this.world = world;
        this.senderRef = new WeakReference<>(null);
        this.force = cp.isForce();
        this.dryRun = cp.isDryRun();
        this.edgeIndex = cp.getEdgeIndex();
        this.built = cp.getBuilt();
        this.skipped = cp.getSkipped();
        this.failed = cp.getFailed();

        BridgeTemplateRegistry.invalidateTemplateCache();
        this.chunkManager = new MPBChunkManager(MPBConfig.GENERAL.maxForcedChunksPerJob);
        this.sampler = new TerrainSampler(world);
        this.sampler.setChunkManager(chunkManager);

        MillIntegration.logNodeSummary(world);
        this.nodes = MillIntegration.getAllNodePositions(world);
        this.saved = MPBWorldData.get(world);
        this.checkpoint = cp;

        this.roadIndex = MPBRoadIndexData.get(world);
        this.roadIndex.attachChunkManager(this.chunkManager);
        this.sampler.setRoadIndex(this.roadIndex);
        this.roadNetwork = MPBRoadNetwork.get(world);

        this.protectedAreas = buildProtectedAreas(world, sampler);
        this.sampler.setProtectedAreas(this.protectedAreas);

        for (BlockPos n : nodes) {
            anchorByXZ.put(xzKey(n), findAnchor(world, n, sampler, 32));
        }

        // Rebuild edges from persisted BlockPos pairs instead of re-running the network builder.
        List<BlockPos[]> stored = cp.getEdges();
        this.edges = new ArrayList<>(stored.size());
        for (BlockPos[] pair : stored) {
            this.edges.add(new PathNetworkBuilder.Edge(pair[0], pair[1]));
        }

        if (this.edges.isEmpty()) {
            Ultreia.LOGGER.warn("[MPB] Resumed job has no edges — marking complete.");
            this.done = true;
            return;
        }

        Ultreia.LOGGER.info("[MPB] Resumed generation: edges={}, resuming at {}/{}, built={} skipped={} failed={}",
                edges.size(), edgeIndex, edges.size(), built, skipped, failed);
    }

    public int getDim() {
        return world.provider.getDimension();
    }

    public WeakReference<ICommandSender> getSenderRef() {
        return senderRef;
    }

    public boolean isDone() {
        return done;
    }

    public String statusLine() {
        int total = edges != null ? edges.size() : 0;
        return "Running. edge=" + edgeIndex + "/" + total + " built=" + built + " skipped=" + skipped + " failed=" + failed
                + (curEdge != null ? (" current=" + curEdge.key()) : "");
    }

    public void tick(long budgetNs) {
        if (done) return;

        long end = System.nanoTime() + budgetNs;

        while (System.nanoTime() < end && !done) {

            if (curEdge == null && curSession == null && curPainter == null) {
                if (edgeIndex >= edges.size()) {
                    msg("Done. built=" + built + " skipped=" + skipped + " failed=" + failed + " dryRun=" + dryRun);
                    done = true;
                    return;
                }

                PathNetworkBuilder.Edge e = edges.get(edgeIndex);
                String key = e.key();

                if (!force && saved.isBuilt(key)) {
                    skipped++;
                    edgeIndex++;
                    onEdgeFinished();
                    continue;
                }

                curEdge = e;

                MillVillageIndex.VillageInfo viA = MillVillageIndex.findExact(world, e.a);
                MillVillageIndex.VillageInfo viB = MillVillageIndex.findExact(world, e.b);

                boolean aIsLone = (viA != null && !viA.isVillage);
                boolean bIsLone = (viB != null && !viB.isVillage);
                boolean aIsVillage = (viA != null && viA.isVillage);
                boolean bIsVillage = (viB != null && viB.isVillage);

                // At least one lone building endpoint → narrow terrain-following trail.
                curRoadType = (aIsLone || bIsLone) ? RoadType.LONE_TRAIL : RoadType.VILLAGE_MAIN;
                if (MPBConfig.ROAD_HIERARCHY.debugLogRoadType) {
                    Ultreia.LOGGER.info("[MPB] Edge {} roadType={} aIsLone={} bIsLone={}",
                            e.key(), curRoadType, aIsLone, bIsLone);
                }

                // Each edge resolves its own gate independently, weighted by prior usage so
                // different edges naturally spread across the available gates.
                VillageGateField[] gateFieldA = new VillageGateField[1];
                VillageGateField[] gateFieldB = new VillageGateField[1];

                BlockPos a0 = MPBEndpointResolver.resolveEndpointAnchor(
                        world, e.a, e.b, sampler, protectedAreas,
                        aIsVillage ? gateTracker : null,
                        aIsVillage ? gateFieldA : null);

                BlockPos b0 = MPBEndpointResolver.resolveEndpointAnchor(
                        world, e.b, e.a, sampler, protectedAreas,
                        bIsVillage ? gateTracker : null,
                        bIsVillage ? gateFieldB : null);

                if (MPBConfig.GENERAL.printFailureBreakdown) {
                    msg("  anchorA " + MPBEndpointResolver.debugResolveEndpointAnchor(world, e.a, e.b, sampler, protectedAreas));
                    msg("  anchorB " + MPBEndpointResolver.debugResolveEndpointAnchor(world, e.b, e.a, sampler, protectedAreas));
                }

                anchorByXZ.put(xzKey(e.a), a0);
                anchorByXZ.put(xzKey(e.b), b0);

                int snapR = Math.max(0, MPBConfig.GENERAL.snapToExistingRoadRadius);
                if (snapR <= 0) snapR = 48;

                roadIndex.ensureScannedRadius(world, a0.getX(), a0.getZ(), snapR + 8);
                roadIndex.ensureScannedRadius(world, b0.getX(), b0.getZ(), snapR + 8);

                BlockPos nearA = roadIndex.findNearestRoad(a0.getX(), a0.getZ(), snapR);
                BlockPos nearB = roadIndex.findNearestRoad(b0.getX(), b0.getZ(), snapR);

// Lone building rule: if a lone endpoint is near an existing road, build a short spur to it.
                if (aIsLone && nearA != null && (protectedAreas == null || !protectedAreas.contains(nearA.getX(), nearA.getZ()))) {
                    BlockPos spurGoal = MPBRoadConnectionHelper.resolveSnappedApproachAnchor(
                            world, sampler, roadIndex, a0, e.b, nearA, protectedAreas
                    );
                    if (spurGoal != null) {
                        b0 = spurGoal;
                    }
                } else if (bIsLone && nearB != null && (protectedAreas == null || !protectedAreas.contains(nearB.getX(), nearB.getZ()))) {
                    BlockPos spurGoal = MPBRoadConnectionHelper.resolveSnappedApproachAnchor(
                            world, sampler, roadIndex, b0, e.a, nearB, protectedAreas
                    );
                    if (spurGoal != null) {
                        a0 = spurGoal;
                    }
                }
                // Village endpoints are already resolved to their Millénaire path entry by
                // MPBEndpointResolver — no further road-snapping needed here.

                if (a0.getX() == b0.getX() && a0.getZ() == b0.getZ()) {
                    if (!dryRun) saved.markBuilt(curEdge.key());
                    built++;
                    msg("  OK (already connected via existing road)");
                    curEdge = null;
                    edgeIndex++;
                    sampler.clearCache();
                    onEdgeFinished();
                    continue;
                }

                msg("Pathfinding: " + key + " (" + (edgeIndex + 1) + "/" + edges.size() + ")"
                        + " endpoints=(" + a0.getX() + "," + a0.getZ() + ")->(" + b0.getX() + "," + b0.getZ() + ")");

                if (MPBConfig.GENERAL.printFailureBreakdown) {
                    msg("  start " + AStarPathfinder.debugEndpoint(world, sampler, protectedAreas, roadIndex, a0));
                    msg("  goal  " + AStarPathfinder.debugEndpoint(world, sampler, protectedAreas, roadIndex, b0));
                }

                curFromVillage = e.a;
                curToVillage = e.b;
                curGateA = a0;
                curGateB = b0;
                curAIsVillage = aIsVillage;
                curBIsVillage = bIsVillage;
                curSession = astar.startSession(world, a0, b0, sampler);
                curSession.setRoadNetwork(roadNetwork);
                curSession.setRoadType(curRoadType);
                // Pass all destination gates so A* can terminate at whichever gate it reaches first.
                if (gateFieldB[0] != null) curSession.setDestinationGates(gateFieldB[0]);
                // LONE_TRAIL: terminate A* at the nearest existing road instead of the destination village.
                if (curRoadType == RoadType.LONE_TRAIL && MPBConfig.ROAD_HIERARCHY.enabled && roadIndex != null) {
                    curSession.setSnapToRoad(roadIndex, MPBConfig.ROAD_HIERARCHY.loneTrailMergeSearchRadius);
                }
            }

            if (curSession != null) {
                AStarPathfinder.StepResult sr = curSession.advance(end);
                if (sr == AStarPathfinder.StepResult.RUNNING) {
                    return;
                }
                if (sr == AStarPathfinder.StepResult.FOUND || sr == AStarPathfinder.StepResult.PARTIAL) {
                    BridgeAwarePathResult pathResult = curSession.getResult();
                    List<BlockPos> coarse = pathResult.getPath();
                    List<BridgeSpan> bridges = pathResult.getBridges();

                    if (sr == AStarPathfinder.StepResult.PARTIAL) {
                        msg("  PARTIAL: " + curSession.partialSummary());
                    }

                    // Record gate usage: the gate A* actually reached (may differ from curGateB
                    // if the path found a different gate due to terrain).
                    if (curAIsVillage && curGateA != null) gateTracker.markUsed(curGateA);
                    if (curBIsVillage) {
                        BlockPos reached = curSession.getReachedGate();
                        gateTracker.markUsed(reached != null ? reached : curGateB);
                    }

                    curSession = null;

                    // Release A* exploration chunks before painting — the path set is much smaller
                    // than the A* search area, so painting will only re-load the chunks it needs.
                    chunkManager.releaseEdge(world);
                    sampler.clearCache();

                    boolean allowEarlyMerge = true;
                    curPainter = RoadPainter.start(world, coarse, bridges, sampler, protectedAreas, roadIndex, dryRun, allowEarlyMerge);
                    curPainter.setRoadNetwork(roadNetwork, curFromVillage, curToVillage);
                    curPainter.setRoadType(curRoadType);
                    continue;
                }

                failed++;
                msg("  FAILED: " + curSession.failureSummary(MPBConfig.GENERAL.printFailureBreakdown));
                if (MPBConfig.GENERAL.printFailureBreakdown) {
                    msg("  start " + curSession.debugStartArea());
                    msg("  goal  " + curSession.debugGoalArea());
                }
                curSession = null;
                curEdge = null;
                edgeIndex++;
                onEdgeFinished();
                continue;
            }

            if (curPainter != null) {
                RoadPainter.PaintResult pr = curPainter.advance(end);
                if (pr == RoadPainter.PaintResult.RUNNING) {
                    return;
                }

                if (pr == RoadPainter.PaintResult.OK) {
                    if (!dryRun) saved.markBuilt(curEdge.key());
                    built++;
                    msg("  OK");
                } else {
                    failed++;
                    msg("  FAILED: " + curPainter.failureReason());
                }

                curPainter = null;
                curEdge = null;
                edgeIndex++;
                onEdgeFinished();
            }
        }
    }

    // Called after every edge completes (built, skipped, or failed).
    private void onEdgeFinished() {
        if (dryRun) return;
        sampler.clearCache();
        chunkManager.releaseEdge(world);
        updateCheckpoint();
        flushData();
    }

    private void updateCheckpoint() {
        if (dryRun) return;
        checkpoint.save(edges, edgeIndex, built, skipped, failed, force, false);
    }

    // Flush all dirty WorldSavedData to disk immediately.
    private void flushData() {
        try {
            world.getPerWorldStorage().saveAllData();
        } catch (Throwable t) {
            Ultreia.LOGGER.warn("[MPB] Force-save failed: {}", t.getMessage());
        }
    }

    // Called by the manager when the job finishes normally or is cancelled by the user.
    void onJobComplete() {
        if (!dryRun) {
            checkpoint.clear();
            flushData();
        }
    }

    // Called by the manager just before a clean server stop.
    void onServerStopping() {
        if (!dryRun) {
            updateCheckpoint();
            flushData();
        }
    }

    private void msg(String s) {
        ICommandSender sender = senderRef.get();
        if (sender != null) sender.sendMessage(new TextComponentString(s));
    }

    private static long xzKey(BlockPos p) {
        return (((long) p.getX()) << 32) ^ (p.getZ() & 0xffffffffL);
    }

    static MPBProtectedAreas buildProtectedAreas(WorldServer world, TerrainSampler sampler) {
        MPBProtectedAreas pa = new MPBProtectedAreas();

        MillWorldData data = MillIntegration.ensureLoaded(world);

        int vR = Math.max(0, MPBConfig.GENERAL.villageNoPaintRadius);
        int lR = Math.max(0, MPBConfig.GENERAL.loneBuildingNoPaintRadius);
        int penalty = (int) Math.max(0.0, MPBConfig.PATHFINDING.protectedZonePenalty);

        if (data != null) {
            // penalty-only zones (NOT hard)
            if (data.villagesList != null && data.villagesList.pos != null) {
                for (Point p : data.villagesList.pos) {
                    if (p == null) continue;
                    pa.addPenaltySquare(p.getiX(), p.getiZ(), vR, penalty);
                }
            }
            if (data.loneBuildingsList != null && data.loneBuildingsList.pos != null) {
                for (Point p : data.loneBuildingsList.pos) {
                    if (p == null) continue;
                    pa.addPenaltySquare(p.getiX(), p.getiZ(), lR, penalty);
                }
            }
        }

        // hard obstacles: each building bbox (exact) from BuildingLocation
        int hardPenalty = Math.max(0, penalty) + 5000;
        MillBuildingObstacles2.addBuildingsFromLocationsAsNoPaint(world, pa, 1, hardPenalty);

        return pa;
    }

    @Override
    public String completionMessage() {
        return "Ultreïa: generation completed.";
    }

    // Made public for MPBEndpointResolver
    public static BlockPos findAnchor(WorldServer world, BlockPos raw, TerrainSampler sampler, int maxR) {
        int cx = raw.getX();
        int cz = raw.getZ();

        for (int r = 0; r <= maxR; r++) {
            for (int dx = -r; dx <= r; dx++) {
                BlockPos p1 = tryAnchorAt(cx + dx, cz - r, sampler);
                if (p1 != null) return p1;
                BlockPos p2 = tryAnchorAt(cx + dx, cz + r, sampler);
                if (p2 != null) return p2;
            }
            for (int dz = -r + 1; dz <= r - 1; dz++) {
                BlockPos p1 = tryAnchorAt(cx - r, cz + dz, sampler);
                if (p1 != null) return p1;
                BlockPos p2 = tryAnchorAt(cx + r, cz + dz, sampler);
                if (p2 != null) return p2;
            }
        }

        TerrainSampler.Sample s = sampler.sample(cx, cz);
        return new BlockPos(cx, s.surfaceY, cz);
    }

    @Nullable
    private static BlockPos tryAnchorAt(int x, int z, TerrainSampler sampler) {
        TerrainSampler.Sample s = sampler.sample(x, z);
        if (sampler.forbidden(s, TerrainSampler.Mode.PATHFINDING)) return null;
        if (s.isOcean || s.isWater) return null;
        return new BlockPos(x, s.surfaceY, z);
    }

    public void shutdown() {
        try {
            sampler.clearCache();
        } catch (Throwable ignored) {
        }
        try {
            chunkManager.close(world);
        } catch (Throwable ignored) {
        }
        try {
            saved.markDirty();
        } catch (Throwable ignored) {
        }
    }

    private BlockPos anchorFromXZ(int x, int z) {
        BlockPos raw = new BlockPos(x, 0, z);
        return findAnchor(world, raw, sampler, 12);
    }
}

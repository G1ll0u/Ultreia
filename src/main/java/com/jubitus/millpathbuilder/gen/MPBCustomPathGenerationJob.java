package com.jubitus.millpathbuilder.gen;

import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.bridge.BridgeAwarePathResult;
import com.jubitus.millpathbuilder.gen.bridge.BridgeSpan;
import com.jubitus.millpathbuilder.gen.bridge.template.BridgeTemplateRegistry;
import com.jubitus.millpathbuilder.gen.util.MPBPos2D;
import com.jubitus.millpathbuilder.gen.world.MPBRoadIndexData;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

import java.lang.ref.WeakReference;
import java.util.List;

public final class MPBCustomPathGenerationJob implements MPBAsyncJob {

    private final WorldServer world;
    private final WeakReference<ICommandSender> senderRef;
    private final boolean dryRun;

    private final BlockPos requestedStart;
    private final BlockPos requestedGoal;

    private final MPBChunkManager chunkManager;
    private final TerrainSampler sampler;
    private final MPBProtectedAreas protectedAreas;
    private final MPBRoadIndexData roadIndex;
    private final AStarPathfinder astar = new AStarPathfinder();

    private BlockPos resolvedStart;
    private BlockPos resolvedGoal;

    private AStarPathfinder.Session curSession;
    private RoadPainter.Session curPainter;

    private boolean initialized = false;
    private boolean done = false;
    private boolean failed = false;

    public MPBCustomPathGenerationJob(WorldServer world, ICommandSender sender, BlockPos start, BlockPos goal, boolean dryRun) {
        this.world = world;
        this.senderRef = new WeakReference<>(sender);
        this.dryRun = dryRun;
        this.requestedStart = start;
        this.requestedGoal = goal;

        BridgeTemplateRegistry.invalidateTemplateCache();
        this.chunkManager = new MPBChunkManager(MPBConfig.GENERAL.maxForcedChunksPerJob);

        this.sampler = new TerrainSampler(world);
        this.sampler.setChunkManager(chunkManager);

        this.roadIndex = MPBRoadIndexData.get(world);
        this.roadIndex.attachChunkManager(this.chunkManager);
        this.sampler.setRoadIndex(this.roadIndex);

        this.protectedAreas = MPBGenerationJob.buildProtectedAreas(world, sampler);
        this.sampler.setProtectedAreas(this.protectedAreas);
    }

    @Override
    public int getDim() {
        return world.provider.getDimension();
    }

    @Override
    public WeakReference<ICommandSender> getSenderRef() {
        return senderRef;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public String statusLine() {
        if (done) {
            return failed ? "Manual path job failed." : "Manual path job finished.";
        }
        if (!initialized) {
            return "Manual path job preparing.";
        }
        if (curSession != null) {
            return "Manual path job pathfinding: (" + resolvedStart.getX() + "," + resolvedStart.getZ() + ") -> (" + resolvedGoal.getX() + "," + resolvedGoal.getZ() + ")";
        }
        if (curPainter != null) {
            return "Manual path job painting: (" + resolvedStart.getX() + "," + resolvedStart.getZ() + ") -> (" + resolvedGoal.getX() + "," + resolvedGoal.getZ() + ")";
        }
        return "Manual path job running.";
    }

    @Override
    public void tick(long budgetNs) {
        if (done) return;

        long end = System.nanoTime() + budgetNs;

        while (System.nanoTime() < end && !done) {
            if (!initialized) {
                initialized = true;

                resolvedStart = MPBGenerationJob.findAnchor(world, requestedStart, sampler, 16);
                resolvedGoal = MPBGenerationJob.findAnchor(world, requestedGoal, sampler, 16);

                if (resolvedStart == null || resolvedGoal == null) {
                    fail("Could not resolve valid start/end anchors.");
                    return;
                }

                if (MPBPos2D.sameXZ(resolvedStart, resolvedGoal)) {
                    msg("Manual path skipped: start and end resolve to the same anchor.");
                    done = true;
                    return;
                }

                int scanR = Math.max(16, MPBConfig.GENERAL.snapToExistingRoadRadius + 8);
                roadIndex.ensureScannedRadius(world, resolvedStart.getX(), resolvedStart.getZ(), scanR);
                roadIndex.ensureScannedRadius(world, resolvedGoal.getX(), resolvedGoal.getZ(), scanR);

                msg("Manual pathfinding: (" + resolvedStart.getX() + "," + resolvedStart.getZ() + ") -> (" + resolvedGoal.getX() + "," + resolvedGoal.getZ() + ")");
                curSession = astar.startSession(world, resolvedStart, resolvedGoal, sampler);
                continue;
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
                        msg("Manual path PARTIAL: " + curSession.partialSummary());
                    }

                    curSession = null;

                    if (coarse == null || coarse.isEmpty()) {
                        fail("Pathfinding returned an empty path.");
                        return;
                    }

                    boolean allowEarlyMerge = true;
                    curPainter = RoadPainter.start(world, coarse, bridges, sampler, protectedAreas, roadIndex, dryRun, allowEarlyMerge);

                    if (MPBConfig.GENERAL.unloadChunksAfterEachEdge) {
                        chunkManager.releaseAll(world);
                    }
                    continue;
                }

                fail("Pathfinding failed: " + curSession.failureSummary(MPBConfig.GENERAL.printFailureBreakdown));
                return;
            }

            if (curPainter != null) {
                RoadPainter.PaintResult pr = curPainter.advance(end);

                if (pr == RoadPainter.PaintResult.RUNNING) {
                    return;
                }

                if (pr == RoadPainter.PaintResult.OK) {
                    msg("Manual path OK.");
                    curPainter = null;
                    sampler.clearCache();
                    if (MPBConfig.GENERAL.unloadChunksAfterEachEdge) {
                        chunkManager.releaseAll(world);
                    }
                    done = true;
                    return;
                }

                fail("Painting failed: " + curPainter.failureReason());
                return;
            }

            done = true;
        }
    }

    @Override
    public void shutdown() {
        try {
            sampler.clearCache();
        } catch (Throwable ignored) {
        }
        try {
            chunkManager.close(world);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public String completionMessage() {
        return failed
                ? "MillPathBuilder: custom path generation finished with failure."
                : "MillPathBuilder: custom path generation completed.";
    }

    private void fail(String message) {
        failed = true;
        done = true;
        msg(message);
        sampler.clearCache();
        if (MPBConfig.GENERAL.unloadChunksAfterEachEdge) {
            chunkManager.releaseAll(world);
        }
    }

    private void msg(String s) {
        ICommandSender sender = senderRef.get();
        if (sender != null) {
            sender.sendMessage(new TextComponentString(s));
        }
    }
}
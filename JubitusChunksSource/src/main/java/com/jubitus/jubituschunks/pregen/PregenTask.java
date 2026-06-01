package com.jubitus.jubituschunks.pregen;

import com.google.common.collect.ImmutableSetMultimap;
import com.jubitus.jubituschunks.JubitusChunksMod;
import com.jubitus.jubituschunks.config.JubitusChunksConfig;
import com.jubitus.jubituschunks.pregen.fun.FollowManager;
import com.jubitus.jubituschunks.pregen.state.PregenState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.ForgeChunkManager;

import java.util.ArrayList;
import java.util.List;

public class PregenTask {

    private boolean calibrated = false;

    private final MinecraftServer server;
    private final WorldServer world;
    private final EntityPlayerMP initiator;

    private final int centerChunkX;
    private final int centerChunkZ;
    private final int chunkRadius;
    private final boolean skipExisting;
    private final boolean verifyExisting;
    private final boolean populateViaGenerator;
    private final int bulkRUsed;
    private final int strideChunks;
    private final SpiralChunkIterator iterator;
    private final long totalSteps;

    private long generatedChunks = 0;
    private long skippedExisting = 0;
    private long processedSteps = 0;
    private long tickCounter = 0;

    private int lastFailX = Integer.MAX_VALUE;
    private int lastFailZ = Integer.MAX_VALUE;
    private int consecutiveFailsSameCoord = 0;
    private static final int MAX_FAILS_SAME_COORD = 10;

    private volatile int headChunkX;
    private volatile int headChunkZ;
    private volatile int headRadiusChunks;
    private volatile long headStepIndex;

    private long lastMemCheckMs = 0;
    private boolean gcRequested = false;
    private long usedBeforeGc = 0;
    private int consecutiveGcFailures = 0;

    private long lastProgressMsgMs = 0;
    private long perfLastMs = System.currentTimeMillis();
    private long perfLastSteps = 0;
    private double emaStepsPerSec = 0.0;

    private final java.util.Map<Long, Long> protectUntilTick = new java.util.HashMap<>();
    private final java.util.Set<Long> forcedChunkKeys = new java.util.HashSet<>();
    private long lastForcedRefreshTick = 0;
    private static final int FORCED_REFRESH_INTERVAL_TICKS = 200;

    /**
     * Normal start constructor — spiral shape derived from current config.
     */
    public PregenTask(MinecraftServer server, WorldServer world, BlockPos start, int radiusBlocks,
                      EntityPlayerMP initiator, boolean skipExisting, boolean verifyExisting,
                      boolean populateViaGenerator) {
        this(server, world, start, radiusBlocks, initiator, skipExisting, verifyExisting, populateViaGenerator, 0, 0);
    }

    /**
     * Resume constructor — pass saved radiusSteps and strideChunks to restore the exact spiral shape.
     * If both are 0, falls back to computing from current config.
     */
    public PregenTask(MinecraftServer server, WorldServer world, BlockPos start, int radiusBlocks,
                      EntityPlayerMP initiator, boolean skipExisting, boolean verifyExisting,
                      boolean populateViaGenerator, int overrideRadiusSteps, int overrideStrideChunks) {
        this.server = server;
        this.world = world;
        this.initiator = initiator;
        this.skipExisting = skipExisting;
        this.verifyExisting = verifyExisting;
        this.populateViaGenerator = populateViaGenerator;

        this.centerChunkX = start.getX() >> 4;
        this.centerChunkZ = start.getZ() >> 4;
        this.chunkRadius = (int) Math.ceil(radiusBlocks / 16.0);

        int radiusSteps;
        if (overrideRadiusSteps > 0 && overrideStrideChunks > 0) {
            this.bulkRUsed = (overrideStrideChunks - 1) / 2;
            this.strideChunks = overrideStrideChunks;
            radiusSteps = overrideRadiusSteps;
        } else {
            int bulkR = 0;
            int stride = 1;
            if (JubitusChunksConfig.GENERAL.bulkPopulateEnabled) {
                bulkR = JubitusChunksConfig.GENERAL.bulkRadiusChunks;
                stride = 2 * bulkR + 1;
            }
            this.bulkRUsed = bulkR;
            this.strideChunks = stride;
            radiusSteps = (int) Math.ceil((this.chunkRadius + this.bulkRUsed) / (double) this.strideChunks);
        }

        this.iterator = new SpiralChunkIterator(centerChunkX, centerChunkZ, radiusSteps, this.strideChunks);
        this.totalSteps = iterator.getMaxSteps();
        this.headChunkX = centerChunkX;
        this.headChunkZ = centerChunkZ;
        this.headStepIndex = 0;
    }

    public boolean tickStep() {
        tickCounter++;
        cleanupProtection();
        if (world.getMinecraftServer() == null || world.getMinecraftServer().isServerStopped()) return true;

        MemAction mem = memoryWatchdog();
        if (mem == MemAction.PAUSE) return false;
        if (mem == MemAction.TERMINATE) return true;

        ChunkProviderServer cps = world.getChunkProvider();
        int targetStepsPerTick = computeEffectiveStepsPerTick();
        int did = 0;

        while (did < targetStepsPerTick) {
            if (cps.getLoadedChunkCount() > JubitusChunksConfig.GENERAL.maxLoadedChunksSoftLimit) {
                break;
            }

            if (!iterator.hasNext()) return finishAndNotify(cps);

            SpiralChunkIterator.ChunkCoord coord = iterator.peek();
            headChunkX = coord.x;
            headChunkZ = coord.z;
            headStepIndex = iterator.getSteps();
            headRadiusChunks = JubitusChunksConfig.GENERAL.preloadRadiusChunks
                    + (JubitusChunksConfig.GENERAL.bulkPopulateEnabled ? this.bulkRUsed : 0);

            try {
                if (processOne(cps, coord.x, coord.z)) {
                    iterator.advance();
                    processedSteps++;
                    did++;
                    consecutiveFailsSameCoord = 0;
                } else {
                    break;
                }
            } catch (Throwable t) {
                JubitusChunksMod.LOGGER.error("Error at {},{}: {}", coord.x, coord.z, t.getMessage());
                handleFailure(coord);
                break;
            }
        }

        applyUnloadPressure(cps, headChunkX, headChunkZ);
        maybeSendProgress();

        return !iterator.hasNext() && finishAndNotify(cps);
    }

    private boolean processOne(ChunkProviderServer cps, int chunkX, int chunkZ) {
        final int preloadR = JubitusChunksConfig.GENERAL.preloadRadiusChunks;
        int bulkR = JubitusChunksConfig.GENERAL.bulkPopulateEnabled ? this.bulkRUsed : 0;
        final int range = preloadR + bulkR;

        List<Chunk> touched = new ArrayList<>();
        for (int dx = -range; dx <= range; dx++) {
            for (int dz = -range; dz <= range; dz++) {
                Chunk c = loadOrGenerateRaw(cps, chunkX + dx, chunkZ + dz);
                if (c != null) {
                    touched.add(c);
                    protectChunk(chunkX + dx, chunkZ + dz, JubitusChunksConfig.GENERAL.keepNeighborChunksLoadedTicks);
                }
            }
        }

        if (!JubitusChunksConfig.GENERAL.bulkPopulateEnabled) {
            processSingleChunk(cps, chunkX, chunkZ);
        } else {
            for (int dx = -bulkR; dx <= bulkR; dx++) {
                for (int dz = -bulkR; dz <= bulkR; dz++) {
                    processSingleChunk(cps, chunkX + dx, chunkZ + dz);
                }
            }
        }

        if (JubitusChunksConfig.GENERAL.queueUnloadPreloadedNeighbors) {
            for (Chunk c : touched) {
                if (!shouldNeverUnload(c.x, c.z)) cps.queueUnload(c);
            }
        }
        return true;
    }

    private void processSingleChunk(ChunkProviderServer cps, int x, int z) {
        if (Math.abs(x - centerChunkX) > chunkRadius || Math.abs(z - centerChunkZ) > chunkRadius) return;

        boolean existed = cps.isChunkGeneratedAt(x, z);
        if (skipExisting && !verifyExisting && existed) {
            skippedExisting++;
            return;
        }

        Chunk c = loadOrGenerateRaw(cps, x, z);
        if (c == null) return;

        if (!existed || !c.isTerrainPopulated()) {
            populateChunk(cps, x, z, c);
            generatedChunks++;
            protectChunk(x, z, JubitusChunksConfig.GENERAL.keepPopulatedChunksLoadedTicks);
        } else if (skipExisting) {
            skippedExisting++;
        }

        if (JubitusChunksConfig.GENERAL.queueUnloadCenterChunk && !shouldNeverUnload(x, z)) {
            cps.queueUnload(c);
        }
    }

    private void populateChunk(ChunkProviderServer cps, int chunkX, int chunkZ, Chunk c) {
        if (!populateViaGenerator) {
            c.populate(cps, cps.chunkGenerator);
        } else {
            net.minecraftforge.event.ForgeEventFactory.onChunkPopulate(true, cps.chunkGenerator, world, world.rand, chunkX, chunkZ, false);
            cps.chunkGenerator.populate(chunkX, chunkZ);
            net.minecraftforge.event.ForgeEventFactory.onChunkPopulate(false, cps.chunkGenerator, world, world.rand, chunkX, chunkZ, false);
            c.setTerrainPopulated(true);
            c.setLightPopulated(true);
            c.markDirty();
        }
    }

    private void handleFailure(SpiralChunkIterator.ChunkCoord coord) {
        if (coord.x == lastFailX && coord.z == lastFailZ) {
            consecutiveFailsSameCoord++;
        } else {
            lastFailX = coord.x;
            lastFailZ = coord.z;
            consecutiveFailsSameCoord = 1;
        }
        if (consecutiveFailsSameCoord >= MAX_FAILS_SAME_COORD) {
            JubitusChunksMod.LOGGER.error("Skipping stuck chunk {},{}", coord.x, coord.z);
            iterator.advance();
            processedSteps++;
            consecutiveFailsSameCoord = 0;
        }
    }

    private boolean finishAndNotify(ChunkProviderServer cps) {
        notify("Jubitus Chunks pregeneration complete. steps=" + processedSteps + "/" + totalSteps);
        try { drainUnloadsAndFlush(cps); } catch (Throwable ignored) {}
        PregenState.delete(world);
        return true;
    }

    private void maybeSendProgress() {
        long now = System.currentTimeMillis();
        long intervalMs = JubitusChunksConfig.GENERAL.progressMessageIntervalSeconds * 1000L;
        if (now - lastProgressMsgMs < intervalMs) return;
        lastProgressMsgMs = now;

        long finished = iterator.getSteps();

        if (!calibrated) {
            perfLastMs = now;
            perfLastSteps = finished;
            calibrated = true;
            return;
        }

        long dtMs = Math.max(1, now - perfLastMs);
        double dtSec = dtMs / 1000.0;
        double instSteps = (finished - perfLastSteps) / dtSec;

        if (dtMs > 1000) {
            double alpha = 0.35;
            emaStepsPerSec = (emaStepsPerSec == 0) ? instSteps : (emaStepsPerSec * (1 - alpha) + instSteps * alpha);
        }

        perfLastMs = now;
        perfLastSteps = finished;

        long remainingSteps = Math.max(0, totalSteps - finished);
        long etaSec = (emaStepsPerSec > 0.001) ? (long) Math.ceil(remainingSteps / emaStepsPerSec) : -1;
        int pct = (int) ((finished * 100L) / Math.max(1L, totalSteps));

        notify("Jubitus Chunks: step=" + finished + "/" + totalSteps + " (" + pct + "%)"
                + " | loaded=" + world.getChunkProvider().getLoadedChunkCount()
                + " | " + memString()
                + " | speed=" + String.format(java.util.Locale.ROOT, "%.2f steps/s", emaStepsPerSec)
                + " | eta=" + (etaSec < 0 ? "?" : formatDuration(etaSec)));

        saveState();
    }

    private MemAction memoryWatchdog() {
        if (!JubitusChunksConfig.GENERAL.stopServerOnLowMemory) return MemAction.NONE;

        long now = System.currentTimeMillis();
        if (now - lastMemCheckMs < JubitusChunksConfig.GENERAL.memoryCheckIntervalSeconds * 1000L) return MemAction.NONE;
        lastMemCheckMs = now;

        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        int usedPct = (int) ((used * 100L) / Math.max(1L, rt.maxMemory()));

        if (usedPct < JubitusChunksConfig.GENERAL.criticalHeapUsedPercent) {
            gcRequested = false;
            consecutiveGcFailures = 0;
            return MemAction.NONE;
        }

        if (!gcRequested) {
            gcRequested = true;
            usedBeforeGc = used;
            System.gc();
            notify("Memory critical (" + usedPct + "%). Forcing GC...");
            return MemAction.PAUSE;
        }

        long freedMb = (usedBeforeGc - used) / (1024L * 1024L);
        if (freedMb < JubitusChunksConfig.GENERAL.minRecoveredAfterGcMB) {
            consecutiveGcFailures++;
        } else {
            consecutiveGcFailures = 0;
        }

        if (consecutiveGcFailures >= JubitusChunksConfig.GENERAL.consecutiveGcFailuresToStop) {
            saveState();
            drainUnloadsAndFlush(world.getChunkProvider());
            notify("OOM prevention: stopping server. Use /jubituschunks resume after restart.");
            server.initiateShutdown();
            return MemAction.TERMINATE;
        }
        return MemAction.PAUSE;
    }

    private void applyUnloadPressure(ChunkProviderServer cps, int keepCenterX, int keepCenterZ) {
        if (!JubitusChunksConfig.GENERAL.aggressiveUnloadPressure) return;

        // Always queue distant chunks — catches side-effect loads from mods like Millénaire
        // that call provideChunk() internally during population without us knowing.
        int keepR = JubitusChunksConfig.GENERAL.keepLoadedRadiusChunks;
        for (Chunk c : cps.getLoadedChunks()) {
            if (shouldNeverUnload(c.x, c.z)) continue;
            if (Math.abs(c.x - keepCenterX) > keepR || Math.abs(c.z - keepCenterZ) > keepR) {
                cps.queueUnload(c);
            }
        }

        // Extra forced save passes only as emergency drain if loaded is still above the hard limit.
        if (cps.getLoadedChunkCount() > JubitusChunksConfig.GENERAL.loadedChunksHardLimit) {
            for (int i = 0; i < JubitusChunksConfig.GENERAL.extraUnloadPassesWhenOverLimit; i++) cps.tick();
        }
    }

    private void drainUnloadsAndFlush(ChunkProviderServer cps) {
        try {
            for (int i = 0; i < 20; i++) cps.tick();
            cps.saveChunks(true);
            cps.flushToDisk();
            net.minecraft.world.storage.ThreadedFileIOBase.getThreadedIOInstance().waitForFinish();
        } catch (Throwable t) {
            JubitusChunksMod.LOGGER.warn("Flush failed", t);
        }
    }

    private int computeEffectiveStepsPerTick() {
        int requested = JubitusChunksConfig.GENERAL.stepsPerTick;
        if (!JubitusChunksConfig.GENERAL.adaptiveStepCap) return requested;
        // Estimate net new chunks per spiral step: a strip of one stride width along the preload side.
        // Consecutive spiral steps share most of their preload area, so side² overcounts significantly.
        int side = 2 * (JubitusChunksConfig.GENERAL.preloadRadiusChunks + bulkRUsed) + 1;
        int newChunksPerStep = Math.max(1, strideChunks * side);
        int safe = Math.max(1, JubitusChunksConfig.GENERAL.unloadBudgetPerTick / newChunksPerStep);
        return Math.min(requested, safe);
    }

    private void saveState() {
        PregenState s = new PregenState();
        s.dim = world.provider.getDimension();
        s.centerChunkX = centerChunkX;
        s.centerChunkZ = centerChunkZ;
        s.chunkRadius = chunkRadius;
        s.spiralSteps = iterator.getSteps();
        s.skipExisting = skipExisting;
        s.verifyExisting = verifyExisting;
        s.populateViaGenerator = populateViaGenerator;
        s.generatedChunks = generatedChunks;
        s.skippedExisting = skippedExisting;
        s.radiusSteps = iterator.getRadiusSteps();
        s.strideChunks = iterator.getStrideChunks();
        PregenState.save(world, s);
    }

    private Chunk loadOrGenerateRaw(ChunkProviderServer cps, int x, int z) {
        return cps.provideChunk(x, z);
    }

    private void protectChunk(int x, int z, int ticks) {
        if (ticks > 0) protectUntilTick.put(chunkKey(x, z), tickCounter + ticks);
    }

    private boolean isProtected(int x, int z) {
        Long u = protectUntilTick.get(chunkKey(x, z));
        return u != null && u > tickCounter;
    }

    private void cleanupProtection() {
        if ((tickCounter & 31) == 0) protectUntilTick.entrySet().removeIf(e -> e.getValue() <= tickCounter);
    }

    private static long chunkKey(int x, int z) {
        return (((long) x) << 32) | (z & 0xffffffffL);
    }

    private boolean shouldNeverUnload(int x, int z) {
        return isProtected(x, z) || isForceLoadedByMods(x, z) || isInAnyPlayerView(x, z);
    }

    private boolean isForceLoadedByMods(int x, int z) {
        refreshForcedChunksMaybe();
        return forcedChunkKeys.contains(chunkKey(x, z));
    }

    private void refreshForcedChunksMaybe() {
        if (tickCounter - lastForcedRefreshTick < FORCED_REFRESH_INTERVAL_TICKS) return;
        lastForcedRefreshTick = tickCounter;
        forcedChunkKeys.clear();
        try {
            ImmutableSetMultimap<ChunkPos, ForgeChunkManager.Ticket> mm = ForgeChunkManager.getPersistentChunksFor(world);
            for (ChunkPos pos : mm.keySet()) forcedChunkKeys.add(chunkKey(pos.x, pos.z));
        } catch (Throwable ignored) {}
    }

    // Exclude follow-mode watchers: they sit near the head and are already protected by protectChunk.
    // Including them would block unloading of the entire player view distance around the generation head.
    private boolean isInAnyPlayerView(int x, int z) {
        int vd = server.getPlayerList().getViewDistance();
        for (EntityPlayerMP p : server.getPlayerList().getPlayers()) {
            if (FollowManager.isWatcher(p.getUniqueID())) continue;
            if (p.dimension == world.provider.getDimension()
                    && Math.abs(p.chunkCoordX - x) <= vd
                    && Math.abs(p.chunkCoordZ - z) <= vd) return true;
        }
        return false;
    }

    private static String memString() {
        Runtime rt = Runtime.getRuntime();
        return "mem=" + (rt.totalMemory() - rt.freeMemory()) / 1048576L + "MB/" + rt.maxMemory() / 1048576L + "MB";
    }

    private static String formatDuration(long s) {
        return (s >= 3600)
                ? String.format("%dh%dm%ds", s / 3600, (s % 3600) / 60, s % 60)
                : String.format("%dm%ds", s / 60, s % 60);
    }

    private void notify(String m) {
        if (initiator != null) initiator.sendMessage(new TextComponentString(m));
        JubitusChunksMod.LOGGER.info(m);
    }

    public void setSpiralStep(long s) {
        iterator.setSteps(s);
        calibrated = false;
        emaStepsPerSec = 0;
    }

    public void requestStopAndFlush() {
        saveState();
        try {
            ChunkProviderServer cps = world.getChunkProvider();
            cps.saveChunks(true);
            cps.flushToDisk();
            net.minecraft.world.storage.ThreadedFileIOBase.getThreadedIOInstance().waitForFinish();
        } catch (Throwable t) {
            JubitusChunksMod.LOGGER.warn("Flush on stop failed", t);
        }
    }

    public int getHeadChunkX() { return headChunkX; }
    public int getHeadChunkZ() { return headChunkZ; }
    public long getHeadStepIndex() { return headStepIndex; }
    public long getTotalSteps() { return totalSteps; }
    public int getHeadRadiusChunks() { return headRadiusChunks; }
    public long getGeneratedChunks() { return generatedChunks; }
    public long getSkippedExisting() { return skippedExisting; }
    public int getCenterChunkX() { return centerChunkX; }
    public int getCenterChunkZ() { return centerChunkZ; }

    private enum MemAction { NONE, PAUSE, TERMINATE }
}

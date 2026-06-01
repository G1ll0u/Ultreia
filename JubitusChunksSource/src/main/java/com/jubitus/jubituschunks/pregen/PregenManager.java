package com.jubitus.jubituschunks.pregen;

import com.jubitus.jubituschunks.config.JubitusChunksConfig;
import com.jubitus.jubituschunks.pregen.state.PregenState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PregenManager {

    private static final Map<Integer, PregenTask> TASKS = new ConcurrentHashMap<>();

    public static boolean start(MinecraftServer server, WorldServer world, BlockPos start, int radiusBlocks,
                                EntityPlayerMP initiator, boolean skipExisting) {
        int dim = world.provider.getDimension();
        if (TASKS.containsKey(dim)) return false;

        PregenTask task = new PregenTask(server, world, start, radiusBlocks, initiator,
                skipExisting,
                JubitusChunksConfig.GENERAL.verifyExistingChunks,
                JubitusChunksConfig.GENERAL.populateViaGenerator);
        TASKS.put(dim, task);
        return true;
    }

    /**
     * Resume a previously saved pregen, restoring the exact spiral shape from the saved state.
     * Pass savedRadiusSteps=0 and savedStrideChunks=0 to derive shape from current config instead.
     */
    public static boolean startResumable(MinecraftServer server, WorldServer world, BlockPos start, int radiusBlocks,
                                         EntityPlayerMP initiator, boolean skipExisting, boolean verifyExisting,
                                         boolean populateViaGenerator, int savedRadiusSteps, int savedStrideChunks,
                                         long stepIndex) {
        int dim = world.provider.getDimension();
        if (TASKS.containsKey(dim)) return false;

        PregenTask task = new PregenTask(server, world, start, radiusBlocks, initiator,
                skipExisting, verifyExisting, populateViaGenerator, savedRadiusSteps, savedStrideChunks);
        task.setSpiralStep(stepIndex);
        TASKS.put(dim, task);
        return true;
    }

    public static boolean pause(int dimension) {
        PregenTask t = TASKS.remove(dimension);
        if (t == null) return false;
        try { t.requestStopAndFlush(); } catch (Throwable ignored) {}
        return true;
    }

    public static boolean cancel(WorldServer world) {
        boolean wasRunning = pause(world.provider.getDimension());
        PregenState.delete(world);
        return wasRunning;
    }

    public static void pauseAllAndFlush() {
        for (Integer dim : new java.util.ArrayList<>(TASKS.keySet())) {
            pause(dim);
        }
    }

    public static void tick() {
        for (Map.Entry<Integer, PregenTask> e : TASKS.entrySet()) {
            if (e.getValue().tickStep()) {
                TASKS.remove(e.getKey());
            }
        }
    }

    @Nullable
    public static PregenTask getTask(int dim) {
        return TASKS.get(dim);
    }
}

package com.jubitus.millpathbuilder.gen;

import com.jubitus.millpathbuilder.MillPathBuilderMod;
import com.jubitus.millpathbuilder.config.MPBConfig;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

public final class MPBGenerationManager {

    private static final ConcurrentHashMap<Integer, MPBGenerationJob> JOBS = new ConcurrentHashMap<>();

    public static String status(World world) {
        if (world == null || world.provider == null) return "No world.";
        MPBGenerationJob job = JOBS.get(world.provider.getDimension());
        return job == null ? "Idle." : job.statusLine();
    }

    public static void cancel(World world) {
        if (world == null || world.provider == null) return;
        MPBGenerationJob job = JOBS.remove(world.provider.getDimension());
        if (job != null) {
            try {
                job.shutdown();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void start(WorldServer world, ICommandSender sender, boolean force, boolean dryRun) {
        int dim = world.provider.getDimension();
        if (JOBS.containsKey(dim)) {
            if (sender != null) sender.sendMessage(new TextComponentString("MillPathBuilder: already running. Use /millpathbuilder status or /millpathbuilder clear."));
            return;
        }
        MPBGenerationJob job = new MPBGenerationJob(world, sender, force, dryRun);
        JOBS.put(dim, job);
        if (sender != null) sender.sendMessage(new TextComponentString("MillPathBuilder: async generation started."));
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (JOBS.isEmpty()) return;

        long budgetNs = Math.max(1, MPBConfig.GENERAL.tickBudgetMs) * 1_000_000L;

        for (MPBGenerationJob job : JOBS.values()) {
            long start = System.nanoTime();
            job.tick(budgetNs);

            if (job.isDone()) {
                JOBS.remove(job.getDim());

                try {
                    job.shutdown();
                } catch (Throwable ignored) {
                }

                WeakReference<ICommandSender> wr = job.getSenderRef();
                ICommandSender sender = wr != null ? wr.get() : null;
                if (sender != null) sender.sendMessage(new TextComponentString("MillPathBuilder: generation completed."));
                MillPathBuilderMod.LOGGER.info("Async generation finished for dim={}", job.getDim());
            } else {
                long spent = System.nanoTime() - start;
                if (spent > budgetNs * 3L) {
                    MillPathBuilderMod.LOGGER.warn("Generation tick overran budget: {} ms", (spent / 1_000_000L));
                }
            }
        }
    }
}
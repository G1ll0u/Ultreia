package com.jubitus.ultreia.job;

import com.jubitus.ultreia.route.RoadType;
import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.UltreiaConfig;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.lang.ref.WeakReference;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GenerationManager {

    private static final ConcurrentHashMap<Integer, TickedJob> JOBS = new ConcurrentHashMap<>();

    // Pending auto-resumes: dim → (world, checkpoint, ticksRemaining)
    private static final ConcurrentHashMap<Integer, PendingResume> PENDING_RESUMES = new ConcurrentHashMap<>();

    private static final class PendingResume {
        final WorldServer world;
        final JobCheckpoint checkpoint;
        int remainingTicks;

        PendingResume(WorldServer world, JobCheckpoint checkpoint, int delay) {
            this.world = world;
            this.checkpoint = checkpoint;
            this.remainingTicks = delay;
        }
    }

    public static String status(World world) {
        if (world == null || world.provider == null) return "No world.";
        int dim = world.provider.getDimension();
        TickedJob job = JOBS.get(dim);
        if (job != null) return job.statusLine();
        PendingResume pr = PENDING_RESUMES.get(dim);
        if (pr != null) return "Resume pending in " + pr.remainingTicks + " ticks.";
        return "Idle.";
    }

    public static void cancel(World world) {
        if (world == null || world.provider == null) return;
        int dim = world.provider.getDimension();
        PENDING_RESUMES.remove(dim);
        TickedJob job = JOBS.remove(dim);
        if (job != null) {
            // User explicitly cancelled: clear checkpoint so next /mpb generate starts fresh.
            if (job instanceof VillageNetworkJob) {
                ((VillageNetworkJob) job).onJobComplete();
            }
            try {
                job.shutdown();
            } catch (Throwable ignored) {
            }
        }
    }

    public static void start(WorldServer world, ICommandSender sender, boolean force, boolean dryRun) {
        int dim = world.provider.getDimension();
        if (JOBS.containsKey(dim) || PENDING_RESUMES.containsKey(dim)) {
            if (sender != null) {
                sender.sendMessage(new TextComponentString("Ultreïa: already running in this dimension. Use /ultreia status or /ultreia cancel."));
            }
            return;
        }

        TickedJob job = new VillageNetworkJob(world, sender, force, dryRun);
        JOBS.put(dim, job);

        if (sender != null) {
            sender.sendMessage(new TextComponentString("Ultreïa: async generation started."));
        }
    }

    public static void startCustom(WorldServer world, ICommandSender sender, BlockPos start, BlockPos goal, boolean dryRun, RoadType roadType) {
        int dim = world.provider.getDimension();
        if (JOBS.containsKey(dim) || PENDING_RESUMES.containsKey(dim)) {
            if (sender != null) {
                sender.sendMessage(new TextComponentString("Ultreïa: already running in this dimension. Use /ultreia status or /ultreia cancel."));
            }
            return;
        }

        TickedJob job = new ManualPathJob(world, sender, start, goal, dryRun, roadType);
        JOBS.put(dim, job);

        if (sender != null) {
            sender.sendMessage(new TextComponentString("Ultreïa: custom path generation started."));
        }
    }

    // Queues an auto-resume after a short delay to ensure Millenaire has fully loaded.
    public static void queueResume(WorldServer world, JobCheckpoint checkpoint) {
        int dim = world.provider.getDimension();
        if (JOBS.containsKey(dim) || PENDING_RESUMES.containsKey(dim)) return;
        int delay = Math.max(20, UltreiaConfig.GENERAL.autoResumeDelayTicks);
        PENDING_RESUMES.put(dim, new PendingResume(world, checkpoint, delay));
        Ultreia.LOGGER.info("[Ultreia] Queued auto-resume for dim={} at edge {}/{} (starts in {} ticks)",
                dim, checkpoint.getEdgeIndex(), checkpoint.getEdges().size(), delay);
    }

    // Called from FMLServerStoppingEvent to persist latest progress for all active jobs.
    public static void onServerStopping() {
        for (TickedJob job : JOBS.values()) {
            if (job instanceof VillageNetworkJob) {
                ((VillageNetworkJob) job).onServerStopping();
            }
        }
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        if (!(event.getWorld() instanceof WorldServer)) return;
        if (!UltreiaConfig.GENERAL.autoResumeGeneration) return;

        WorldServer world = (WorldServer) event.getWorld();
        JobCheckpoint checkpoint = JobCheckpoint.get(world);
        if (!checkpoint.isActive() || checkpoint.getEdges().isEmpty()) return;

        queueResume(world, checkpoint);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;

        // Process pending resumes before ticking active jobs.
        if (!PENDING_RESUMES.isEmpty()) {
            Iterator<Map.Entry<Integer, PendingResume>> it = PENDING_RESUMES.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<Integer, PendingResume> entry = it.next();
                PendingResume pr = entry.getValue();
                if (--pr.remainingTicks <= 0) {
                    it.remove();
                    int dim = pr.world.provider.getDimension();
                    if (!JOBS.containsKey(dim)) {
                        try {
                            VillageNetworkJob job = new VillageNetworkJob(pr.world, pr.checkpoint);
                            JOBS.put(dim, job);
                            Ultreia.LOGGER.info("[Ultreia] Auto-resumed generation for dim={}", dim);
                        } catch (Throwable t) {
                            Ultreia.LOGGER.error("[Ultreia] Failed to resume generation for dim={}", dim, t);
                        }
                    }
                }
            }
        }

        if (JOBS.isEmpty()) return;

        // The budget is shared by every dimension's job, so two jobs never cost more than one.
        long budgetNs = Math.max(1, UltreiaConfig.GENERAL.tickBudgetMs) * 1_000_000L / JOBS.size();

        for (TickedJob job : JOBS.values()) {
            long start = System.nanoTime();

            try {
                job.tick(budgetNs);
            } catch (Throwable t) {
                JOBS.remove(job.getDim());

                // Checkpoint is NOT cleared on crash — the job can be resumed on next startup.
                try {
                    job.shutdown();
                } catch (Throwable ignored) {
                }

                WeakReference<ICommandSender> wr = job.getSenderRef();
                ICommandSender sender = wr != null ? wr.get() : null;
                if (sender != null) {
                    sender.sendMessage(new TextComponentString("Ultreïa: generation failed with an internal error. See log. The job will resume on next server start."));
                }

                Ultreia.LOGGER.error("Generation job crashed in dim=" + job.getDim(), t);
                continue;
            }

            if (job.isDone()) {
                JOBS.remove(job.getDim());

                // Normal completion: clear the checkpoint.
                if (job instanceof VillageNetworkJob) {
                    ((VillageNetworkJob) job).onJobComplete();
                }
                try {
                    job.shutdown();
                } catch (Throwable ignored) {
                }

                WeakReference<ICommandSender> wr = job.getSenderRef();
                ICommandSender sender = wr != null ? wr.get() : null;
                if (sender != null) {
                    sender.sendMessage(new TextComponentString(job.completionMessage()));
                }
                Ultreia.LOGGER.info("Async generation finished for dim={}", job.getDim());
            } else {
                long spent = System.nanoTime() - start;
                if (spent > budgetNs * 3L) {
                    Ultreia.LOGGER.warn("Generation tick overran budget: {} ms", (spent / 1_000_000L));
                }
            }
        }
    }
}

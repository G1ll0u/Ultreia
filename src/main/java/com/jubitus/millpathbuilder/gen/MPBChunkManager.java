package com.jubitus.millpathbuilder.gen;

import com.google.common.collect.ImmutableSetMultimap;
import com.jubitus.millpathbuilder.MillPathBuilderMod;
import com.jubitus.millpathbuilder.config.MPBConfig;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.server.management.PlayerChunkMapEntry;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.ForgeChunkManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

public final class MPBChunkManager {

    private final LongOpenHashSet touched = new LongOpenHashSet();
    private final int hardCap;

    private ForgeChunkManager.Ticket ticket;

    private static volatile Method PCM_GET_ENTRY;
    private static volatile Method PCME_GET_WATCHING;
    private static volatile Field PCME_PLAYERS_FALLBACK;

    public MPBChunkManager(int hardCap) {
        this.hardCap = Math.max(0, hardCap);
    }

    public boolean ensureLoaded(WorldServer world, int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        long k = key(cx, cz);

        ChunkProviderServer cps = (ChunkProviderServer) world.getChunkProvider();
        if (cps.getLoadedChunk(cx, cz) != null) {
            touched.add(k);
            return true;
        }

        if (hardCap > 0 && touched.size() >= hardCap) return false;

        if (MPBConfig.GENERAL.allowChunkLoading) {
            ensureTicket(world);
            if (ticket != null) {
                ChunkPos pos = new ChunkPos(cx, cz);
                try {
                    ForgeChunkManager.forceChunk(ticket, pos);
                } catch (Throwable ignored) {
                }
                // Important: forcing does not guarantee immediate load
                try {
                    cps.provideChunk(cx, cz);
                } catch (Throwable ignored) {
                }
                touched.add(k);
                return cps.getLoadedChunk(cx, cz) != null;
            }
        }

        try {
            cps.provideChunk(cx, cz);
        } catch (Throwable ignored) {
        }
        touched.add(k);
        return cps.getLoadedChunk(cx, cz) != null;
    }

    public void releaseAll(WorldServer world) {
        releaseAll(world, true);
    }

    public void releaseAll(WorldServer world, boolean saveBeforeUnload) {
        if (world == null || touched.isEmpty()) return;

        ChunkProviderServer cps = (ChunkProviderServer) world.getChunkProvider();

        // Unforce first so "persistent chunk" checks won't block unloading for our own ticket
        if (ticket != null) {
            for (long k : touched) {
                int cx = (int) (k >> 32);
                int cz = (int) k;
                try {
                    ForgeChunkManager.unforceChunk(ticket, new ChunkPos(cx, cz));
                } catch (Throwable ignored) {
                }
            }
        }

        if (saveBeforeUnload) {
            try {
                cps.saveChunks(true);
            } catch (Throwable ignored) {
            }
        }

        LongOpenHashSet keep = new LongOpenHashSet();

        for (long k : touched) {
            int cx = (int) (k >> 32);
            int cz = (int) k;

            ChunkPos pos = new ChunkPos(cx, cz);

            Chunk ch = cps.getLoadedChunk(cx, cz);
            if (ch == null) continue;

            if (!isSafeToUnload(world, pos)) {
                keep.add(k);
                continue;
            }

            try {
                cps.queueUnload(ch);
            } catch (Throwable ignored) {
            }
        }

        touched.clear();
        touched.addAll(keep);

        try {
            cps.tick();
        } catch (Throwable ignored) {
        }
    }

    public void close(WorldServer world) {
        if (world == null) return;
        try {
            releaseAll(world, true);
        } catch (Throwable ignored) {
        }
        if (ticket != null) {
            try {
                ForgeChunkManager.releaseTicket(ticket);
            } catch (Throwable ignored) {
            }
            ticket = null;
        }
        touched.clear();
    }

    public int touchedCount() {
        return touched.size();
    }

    private void ensureTicket(WorldServer world) {
        if (ticket != null) return;
        try {
            ticket = ForgeChunkManager.requestTicket(MillPathBuilderMod.INSTANCE, world, ForgeChunkManager.Type.NORMAL);
            if (ticket != null) {
                ticket.setChunkListDepth(Math.max(1, MPBConfig.GENERAL.maxForcedChunksPerJob));
            }
        } catch (Throwable t) {
            ticket = null;
        }
    }

    private boolean isSafeToUnload(WorldServer world, ChunkPos pos) {
        if (isPersistentByOthers(world, pos)) return false;
        return !isWatchedByPlayers(world, pos.x, pos.z);
    }

    private boolean isPersistentByOthers(WorldServer world, ChunkPos pos) {
        try {
            ImmutableSetMultimap<ChunkPos, ForgeChunkManager.Ticket> mm = ForgeChunkManager.getPersistentChunksFor(world);
            if (mm == null || !mm.containsKey(pos)) return false;

            if (ticket == null) return true;

            Collection<ForgeChunkManager.Ticket> ts = mm.get(pos);
            if (ts == null || ts.isEmpty()) return false;

            for (ForgeChunkManager.Ticket t : ts) {
                if (t != ticket) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isWatchedByPlayers(WorldServer world, int cx, int cz) {
        PlayerChunkMap pcm;
        try {
            pcm = world.getPlayerChunkMap();
        } catch (Throwable t) {
            return false;
        }
        if (pcm == null) return false;

        Object entry = getPlayerChunkEntry(pcm, cx, cz);
        if (entry == null) return false;

        try {
            Collection<?> watchers = getWatchingPlayers(entry);
            return watchers != null && !watchers.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object getPlayerChunkEntry(PlayerChunkMap pcm, int cx, int cz) {
        try {
            if (PCM_GET_ENTRY == null) {
                PCM_GET_ENTRY = pcm.getClass().getMethod("getEntry", int.class, int.class);
                PCM_GET_ENTRY.setAccessible(true);
            }
            return PCM_GET_ENTRY.invoke(pcm, cx, cz);
        } catch (Throwable ignored) {
        }

        try {
            Method m = pcm.getClass().getDeclaredMethod("getEntry", int.class, int.class);
            m.setAccessible(true);
            return m.invoke(pcm, cx, cz);
        } catch (Throwable ignored) {
        }

        return null;
    }

    private Collection<?> getWatchingPlayers(Object entry) throws Exception {
        if (entry instanceof PlayerChunkMapEntry) {
            PlayerChunkMapEntry e = (PlayerChunkMapEntry) entry;
            try {
                return e.getWatchingPlayers();
            } catch (Throwable ignored) {
            }
        }

        try {
            if (PCME_GET_WATCHING == null) {
                PCME_GET_WATCHING = entry.getClass().getMethod("getWatchingPlayers");
                PCME_GET_WATCHING.setAccessible(true);
            }
            Object res = PCME_GET_WATCHING.invoke(entry);
            return (res instanceof Collection) ? (Collection<?>) res : null;
        } catch (Throwable ignored) {
        }

        if (PCME_PLAYERS_FALLBACK == null) {
            for (Field f : entry.getClass().getDeclaredFields()) {
                if (Collection.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    PCME_PLAYERS_FALLBACK = f;
                    break;
                }
            }
        }
        if (PCME_PLAYERS_FALLBACK != null) {
            Object res = PCME_PLAYERS_FALLBACK.get(entry);
            return (res instanceof Collection) ? (Collection<?>) res : null;
        }

        return null;
    }

    private static long key(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }
}
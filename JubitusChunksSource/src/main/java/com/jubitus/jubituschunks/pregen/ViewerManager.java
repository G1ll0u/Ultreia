package com.jubitus.jubituschunks.pregen;

import com.jubitus.jubituschunks.net.NetworkHandler;
import com.jubitus.jubituschunks.net.msg.PacketViewUpdate;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ViewerManager {

    private static class Session {
        final UUID playerId;
        final int dim;
        final int radiusBlocks;
        int centerChunkX;
        int centerChunkZ;

        int tickCounter = 0;

        Session(UUID playerId, int dim, int radiusBlocks, int centerChunkX, int centerChunkZ) {
            this.playerId = playerId;
            this.dim = dim;
            this.radiusBlocks = radiusBlocks;
            this.centerChunkX = centerChunkX;
            this.centerChunkZ = centerChunkZ;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    public static void subscribe(EntityPlayerMP p, int radiusBlocks, int centerChunkX, int centerChunkZ) {
        SESSIONS.put(p.getUniqueID(), new Session(p.getUniqueID(), p.dimension, radiusBlocks, centerChunkX, centerChunkZ));
    }

    public static void unsubscribe(EntityPlayerMP p) {
        SESSIONS.remove(p.getUniqueID());
    }

    public static void tick(MinecraftServer server) {
        for (Session s : SESSIONS.values()) {
            EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(s.playerId);
            if (p == null) {
                SESSIONS.remove(s.playerId);
                continue;
            }
            if (p.dimension != s.dim) {
                SESSIONS.remove(s.playerId);
                continue;
            }

            s.tickCounter++;
            if (s.tickCounter < 20) continue; // update once per second
            s.tickCounter = 0;

            WorldServer world = server.getWorld(s.dim);
            if (world == null) continue;

            // If a pregen is running, use its center; otherwise keep player center updated
            PregenTask t = PregenManager.getTask(s.dim);
            if (t != null) {
                s.centerChunkX = t.getCenterChunkX();
                s.centerChunkZ = t.getCenterChunkZ();
            } else {
                s.centerChunkX = p.chunkCoordX;
                s.centerChunkZ = p.chunkCoordZ;
            }

            PacketViewUpdate pkt = PacketViewUpdate.build(server, world, p, s.radiusBlocks, s.centerChunkX, s.centerChunkZ);
            NetworkHandler.NET.sendTo(pkt, p);
        }
    }
}

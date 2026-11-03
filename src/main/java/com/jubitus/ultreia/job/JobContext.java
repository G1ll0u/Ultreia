package com.jubitus.ultreia.job;

import com.jubitus.ultreia.MillPresence;
import com.jubitus.ultreia.bridge.template.BridgeTemplateRegistry;
import com.jubitus.ultreia.chunk.ChunkManager;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.mill.MillBuildingZones;
import com.jubitus.ultreia.roads.RoadIndex;
import com.jubitus.ultreia.roads.RoadNetwork;
import com.jubitus.ultreia.terrain.ProtectedAreas;
import com.jubitus.ultreia.terrain.TerrainSampler;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

import java.lang.ref.WeakReference;

/** Everything a road job works with, built once per job: chunks, terrain, protected areas, placed roads. */
final class JobContext {

    // Radius (blocks) of the road-index scan around each endpoint before pathing.
    private static final int ROAD_SCAN_RADIUS = 36;

    final WorldServer world;
    final WeakReference<ICommandSender> senderRef;
    final ChunkManager chunkManager;
    final TerrainSampler sampler;
    final RoadIndex roadIndex;
    final RoadNetwork roadNetwork;
    final ProtectedAreas protectedAreas;

    JobContext(WorldServer world, ICommandSender sender) {
        this.world = world;
        this.senderRef = new WeakReference<>(sender);

        BridgeTemplateRegistry.invalidateTemplateCache();
        this.chunkManager = new ChunkManager(UltreiaConfig.GENERAL.maxForcedChunksPerJob);
        this.sampler = new TerrainSampler(world);
        this.sampler.setChunkManager(chunkManager);

        this.roadIndex = RoadIndex.get(world);
        this.roadIndex.attachChunkManager(chunkManager);
        this.sampler.setRoadIndex(roadIndex);
        this.roadNetwork = RoadNetwork.get(world);

        this.protectedAreas = new ProtectedAreas();
        if (MillPresence.isPresent()) MillBuildingZones.addMillenaireZones(world, protectedAreas);
        this.sampler.setProtectedAreas(protectedAreas);
    }

    /** Makes sure the road index knows the roads already placed around an endpoint. */
    void scanRoadsAround(BlockPos p) {
        roadIndex.ensureScannedRadius(world, p.getX(), p.getZ(), ROAD_SCAN_RADIUS);
    }

    /** Frees the terrain cache and the chunks loaded for the current edge. */
    void releaseEdge() {
        sampler.clearCache();
        chunkManager.releaseEdge(world);
    }

    void msg(String s) {
        ICommandSender sender = senderRef.get();
        if (sender != null) sender.sendMessage(new TextComponentString(s));
    }

    int dim() {
        return world.provider.getDimension();
    }
}

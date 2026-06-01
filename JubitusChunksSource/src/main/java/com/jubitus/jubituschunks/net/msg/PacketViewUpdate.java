package com.jubitus.jubituschunks.net.msg;

import com.jubitus.jubituschunks.client.ClientHooks;
import com.jubitus.jubituschunks.pregen.PregenManager;
import com.jubitus.jubituschunks.pregen.PregenTask;
import io.netty.buffer.ByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.fml.common.network.simpleimpl.*;
import net.minecraftforge.fml.relauncher.Side;

import java.util.BitSet;

public class PacketViewUpdate implements IMessage {

    // rendering grid info
    public int gridSize;            // e.g. 201
    public int originChunkX;        // top-left chunk X of grid
    public int originChunkZ;        // top-left chunk Z of grid
    public int scaleStepChunks;     // chunks per cell

    // bitsets length = gridSize*gridSize
    public byte[] generatedBits;
    public byte[] loadedBits;

    // head rectangle in CELL coordinates (inclusive)
    public int headMinCellX, headMaxCellX, headMinCellZ, headMaxCellZ;

    // optional stats
    public long stepIndex, totalSteps, generatedChunks, skippedExisting;

    public PacketViewUpdate() {}

    public static PacketViewUpdate build(MinecraftServer server, WorldServer world, net.minecraft.entity.player.EntityPlayerMP player,
                                         int radiusBlocks, int centerChunkX, int centerChunkZ) {

        PacketViewUpdate p = new PacketViewUpdate();

        int radiusChunks = (int) Math.ceil(radiusBlocks / 16.0);

        // fixed grid size (odd number so center is centered)
        p.gridSize = 201;

        // scale so the requested radius fits inside the grid
        int diameterChunks = radiusChunks * 2 + 1;
        p.scaleStepChunks = Math.max(1, (int) Math.ceil(diameterChunks / (double) p.gridSize));

        // grid covers gridSize*scaleStepChunks chunks
        int coveredDiameter = p.gridSize * p.scaleStepChunks;

        // top-left chunk of the grid
        p.originChunkX = centerChunkX - coveredDiameter / 2;
        p.originChunkZ = centerChunkZ - coveredDiameter / 2;

        BitSet gen = new BitSet(p.gridSize * p.gridSize);
        BitSet lod = new BitSet(p.gridSize * p.gridSize);

        ChunkProviderServer cps = world.getChunkProvider();

        // 1) Loaded: iterate actual loaded chunks and mark the cell they fall into
        for (Chunk c : cps.getLoadedChunks()) {
            int cellX = (c.x - p.originChunkX) / p.scaleStepChunks;
            int cellZ = (c.z - p.originChunkZ) / p.scaleStepChunks;
            if (cellX < 0 || cellZ < 0 || cellX >= p.gridSize || cellZ >= p.gridSize) continue;
            int idx = cellZ * p.gridSize + cellX;
            lod.set(idx);
        }

        // 2) Generated: sample one chunk per cell (fast + good enough for a debug viewer)
        for (int cz = 0; cz < p.gridSize; cz++) {
            for (int cx = 0; cx < p.gridSize; cx++) {
                int sampleChunkX = p.originChunkX + cx * p.scaleStepChunks;
                int sampleChunkZ = p.originChunkZ + cz * p.scaleStepChunks;

                if (cps.isChunkGeneratedAt(sampleChunkX, sampleChunkZ)) {
                    int idx = cz * p.gridSize + cx;
                    gen.set(idx);
                }
            }
        }

        p.generatedBits = gen.toByteArray();
        p.loadedBits = lod.toByteArray();

        // Head square (if task exists)
        PregenTask t = PregenManager.getTask(world.provider.getDimension());
        if (t != null) {
            int hx = t.getHeadChunkX();
            int hz = t.getHeadChunkZ();
            int hr = t.getHeadRadiusChunks();

            int minChunkX = hx - hr;
            int maxChunkX = hx + hr;
            int minChunkZ = hz - hr;
            int maxChunkZ = hz + hr;

            p.headMinCellX = clamp((minChunkX - p.originChunkX) / p.scaleStepChunks, 0, p.gridSize - 1);
            p.headMaxCellX = clamp((maxChunkX - p.originChunkX) / p.scaleStepChunks, 0, p.gridSize - 1);
            p.headMinCellZ = clamp((minChunkZ - p.originChunkZ) / p.scaleStepChunks, 0, p.gridSize - 1);
            p.headMaxCellZ = clamp((maxChunkZ - p.originChunkZ) / p.scaleStepChunks, 0, p.gridSize - 1);

            p.stepIndex = t.getHeadStepIndex();
            p.totalSteps = t.getTotalSteps();
            p.generatedChunks = t.getGeneratedChunks();
            p.skippedExisting = t.getSkippedExisting();
        } else {
            p.headMinCellX = p.headMaxCellX = p.headMinCellZ = p.headMaxCellZ = -1;
        }

        return p;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        gridSize = buf.readInt();
        originChunkX = buf.readInt();
        originChunkZ = buf.readInt();
        scaleStepChunks = buf.readInt();

        int genLen = buf.readInt();
        generatedBits = new byte[genLen];
        buf.readBytes(generatedBits);

        int lodLen = buf.readInt();
        loadedBits = new byte[lodLen];
        buf.readBytes(loadedBits);

        headMinCellX = buf.readInt();
        headMaxCellX = buf.readInt();
        headMinCellZ = buf.readInt();
        headMaxCellZ = buf.readInt();

        stepIndex = buf.readLong();
        totalSteps = buf.readLong();
        generatedChunks = buf.readLong();
        skippedExisting = buf.readLong();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(gridSize);
        buf.writeInt(originChunkX);
        buf.writeInt(originChunkZ);
        buf.writeInt(scaleStepChunks);

        buf.writeInt(generatedBits.length);
        buf.writeBytes(generatedBits);

        buf.writeInt(loadedBits.length);
        buf.writeBytes(loadedBits);

        buf.writeInt(headMinCellX);
        buf.writeInt(headMaxCellX);
        buf.writeInt(headMinCellZ);
        buf.writeInt(headMaxCellZ);

        buf.writeLong(stepIndex);
        buf.writeLong(totalSteps);
        buf.writeLong(generatedChunks);
        buf.writeLong(skippedExisting);
    }

    public static class Handler implements IMessageHandler<PacketViewUpdate, IMessage> {
        @Override
        public IMessage onMessage(PacketViewUpdate msg, MessageContext ctx) {
            net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> {
                ClientHooks.onViewUpdate(msg);
            });
            return null;
        }
    }

}

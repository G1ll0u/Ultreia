package com.jubitus.jubituschunks.net.msg;

import com.jubitus.jubituschunks.client.ClientHooks;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.*;
import net.minecraftforge.fml.relauncher.Side;

public class PacketViewOpen implements IMessage {
    public int radiusBlocks;

    public PacketViewOpen() {}
    public PacketViewOpen(int radiusBlocks) { this.radiusBlocks = radiusBlocks; }

    @Override public void fromBytes(ByteBuf buf) { radiusBlocks = buf.readInt(); }
    @Override public void toBytes(ByteBuf buf) { buf.writeInt(radiusBlocks); }

    public static class Handler implements IMessageHandler<PacketViewOpen, IMessage> {
        @Override
        public IMessage onMessage(PacketViewOpen msg, MessageContext ctx) {
            // Always hop to the main client thread
            net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> {
                ClientHooks.openChunkViewer(msg.radiusBlocks);
            });
            return null;
        }
    }
}

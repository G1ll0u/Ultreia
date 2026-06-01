package com.jubitus.jubituschunks.net.msg;

import com.jubitus.jubituschunks.pregen.ViewerManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.*;

public class PacketViewSubscribe implements IMessage {
    public int radiusBlocks;

    public PacketViewSubscribe() {}
    public PacketViewSubscribe(int radiusBlocks) { this.radiusBlocks = radiusBlocks; }

    @Override public void fromBytes(ByteBuf buf) { radiusBlocks = buf.readInt(); }
    @Override public void toBytes(ByteBuf buf) { buf.writeInt(radiusBlocks); }

    public static class Handler implements IMessageHandler<PacketViewSubscribe, IMessage> {
        @Override
        public IMessage onMessage(PacketViewSubscribe msg, MessageContext ctx) {
            EntityPlayerMP p = ctx.getServerHandler().player;
            p.getServer().addScheduledTask(() -> {
                // center chosen in ViewerManager.tick: task center if running, else player chunk
                ViewerManager.subscribe(p, msg.radiusBlocks, p.chunkCoordX, p.chunkCoordZ);
            });
            return null;
        }
    }
}

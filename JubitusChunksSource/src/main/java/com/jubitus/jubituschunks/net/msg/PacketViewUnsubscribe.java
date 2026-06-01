package com.jubitus.jubituschunks.net.msg;

import com.jubitus.jubituschunks.pregen.ViewerManager;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.simpleimpl.*;

public class PacketViewUnsubscribe implements IMessage {
    @Override public void fromBytes(ByteBuf buf) {}
    @Override public void toBytes(ByteBuf buf) {}

    public static class Handler implements IMessageHandler<PacketViewUnsubscribe, IMessage> {
        @Override
        public IMessage onMessage(PacketViewUnsubscribe msg, MessageContext ctx) {
            EntityPlayerMP p = ctx.getServerHandler().player;
            p.getServer().addScheduledTask(() -> ViewerManager.unsubscribe(p));
            return null;
        }
    }
}

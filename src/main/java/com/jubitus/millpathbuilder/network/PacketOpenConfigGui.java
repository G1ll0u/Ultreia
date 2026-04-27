package com.jubitus.millpathbuilder.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PacketOpenConfigGui implements IMessage {

    // Set by ClientProxy on client side only — null on dedicated server
    private static GuiOpener guiOpener;

    @FunctionalInterface
    public interface GuiOpener {
        void open(Map<String, String> values);
    }

    public static void setGuiOpener(GuiOpener opener) {
        guiOpener = opener;
    }

    private Map<String, String> values;

    public PacketOpenConfigGui() {}

    public PacketOpenConfigGui(Map<String, String> values) {
        this.values = values;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int n = buf.readInt();
        values = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            values.put(ByteBufUtils.readUTF8String(buf), ByteBufUtils.readUTF8String(buf));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(values.size());
        for (Map.Entry<String, String> e : values.entrySet()) {
            ByteBufUtils.writeUTF8String(buf, e.getKey());
            ByteBufUtils.writeUTF8String(buf, e.getValue());
        }
    }

    // No @SideOnly — this class is safe to load on both sides.
    // GUI-opening logic lives in the GuiOpener lambda set by ClientProxy.
    public static final class Handler implements IMessageHandler<PacketOpenConfigGui, IMessage> {
        @Override
        public IMessage onMessage(PacketOpenConfigGui msg, MessageContext ctx) {
            if (guiOpener != null) guiOpener.open(msg.values);
            return null;
        }
    }
}

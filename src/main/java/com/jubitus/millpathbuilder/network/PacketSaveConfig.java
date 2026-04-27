package com.jubitus.millpathbuilder.network;

import com.jubitus.millpathbuilder.config.ConfigEntry;
import com.jubitus.millpathbuilder.config.MPBConfigPersistence;
import com.jubitus.millpathbuilder.config.MPBConfigReflector;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PacketSaveConfig implements IMessage {

    private Map<String, String> values;

    public PacketSaveConfig() {}

    public PacketSaveConfig(Map<String, String> values) {
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

    public static final class Handler implements IMessageHandler<PacketSaveConfig, IMessage> {
        @Override
        public IMessage onMessage(PacketSaveConfig msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            player.getServer().addScheduledTask(() -> {
                List<ConfigEntry> entries = MPBConfigReflector.buildEntries();
                MPBConfigReflector.applyAll(entries, msg.values);
                MPBConfigPersistence.save(msg.values);
                player.sendMessage(new TextComponentString("§aMPB: config saved and applied."));
            });
            return null;
        }
    }
}

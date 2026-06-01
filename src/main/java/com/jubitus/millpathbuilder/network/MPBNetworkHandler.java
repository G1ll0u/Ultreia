package com.jubitus.millpathbuilder.network;

import com.jubitus.millpathbuilder.Tags;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public final class MPBNetworkHandler {

    public static final SimpleNetworkWrapper CHANNEL =
            NetworkRegistry.INSTANCE.newSimpleChannel(Tags.MOD_ID + ":cfg");

    public static void init() {
        CHANNEL.registerMessage(PacketOpenConfigGui.Handler.class,  PacketOpenConfigGui.class,  0, Side.CLIENT);
        CHANNEL.registerMessage(PacketSaveConfig.Handler.class,     PacketSaveConfig.class,     1, Side.SERVER);
        CHANNEL.registerMessage(PacketOpenMapGui.Handler.class,     PacketOpenMapGui.class,     2, Side.CLIENT);
        CHANNEL.registerMessage(PacketRequestPreview.Handler.class, PacketRequestPreview.class, 3, Side.SERVER);
        CHANNEL.registerMessage(PacketNetworkPreview.Handler.class, PacketNetworkPreview.class, 4, Side.CLIENT);
    }
}

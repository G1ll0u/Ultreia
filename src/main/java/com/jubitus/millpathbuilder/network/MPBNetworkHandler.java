package com.jubitus.millpathbuilder.network;

import com.jubitus.millpathbuilder.Tags;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public final class MPBNetworkHandler {

    public static final SimpleNetworkWrapper CHANNEL =
            NetworkRegistry.INSTANCE.newSimpleChannel(Tags.MOD_ID + ":cfg");

    public static void init() {
        // Both registrations happen here so both sides know the discriminators.
        // PacketOpenConfigGui.Handler has no @SideOnly — safe on server.
        // The actual GUI code is injected via PacketOpenConfigGui.setGuiOpener() from ClientProxy.
        CHANNEL.registerMessage(PacketOpenConfigGui.Handler.class, PacketOpenConfigGui.class, 0, Side.CLIENT);
        CHANNEL.registerMessage(PacketSaveConfig.Handler.class, PacketSaveConfig.class, 1, Side.SERVER);
    }
}

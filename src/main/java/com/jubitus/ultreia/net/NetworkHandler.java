package com.jubitus.ultreia.net;

import com.jubitus.ultreia.map.PacketNetworkPreview;
import com.jubitus.ultreia.map.PacketOpenMapGui;
import com.jubitus.ultreia.map.PacketRequestPreview;
import com.jubitus.jubiconfig.network.MsgOpenConfigGui;
import com.jubitus.jubiconfig.network.MsgSaveConfigGui;
import com.jubitus.ultreia.Tags;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public final class NetworkHandler {

    public static final SimpleNetworkWrapper CHANNEL =
            NetworkRegistry.INSTANCE.newSimpleChannel(Tags.MOD_ID + ":net");

    @SuppressWarnings("unchecked")
    public static void init() {
        boolean isClient = FMLCommonHandler.instance().getSide().isClient();
        // Use instance-based registration so MsgOpenConfigGui.Handler (which references GuiScreen)
        // is never loaded on the server side.
        CHANNEL.registerMessage(
                isClient ? new MsgOpenConfigGui.Handler() : new MsgOpenConfigGui.NoopHandler(),
                MsgOpenConfigGui.class, 0, Side.CLIENT);
        CHANNEL.registerMessage(MsgSaveConfigGui.Handler.class,  MsgSaveConfigGui.class,  1, Side.SERVER);
        // Map GUI packets
        CHANNEL.registerMessage(PacketOpenMapGui.Handler.class,     PacketOpenMapGui.class,     2, Side.CLIENT);
        CHANNEL.registerMessage(PacketRequestPreview.Handler.class, PacketRequestPreview.class, 3, Side.SERVER);
        CHANNEL.registerMessage(PacketNetworkPreview.Handler.class, PacketNetworkPreview.class, 4, Side.CLIENT);
    }
}

package com.jubitus.jubituschunks.net;

import com.jubitus.jubituschunks.Tags;
import com.jubitus.jubituschunks.net.msg.PacketViewOpen;
import com.jubitus.jubituschunks.net.msg.PacketViewSubscribe;
import com.jubitus.jubituschunks.net.msg.PacketViewUnsubscribe;
import com.jubitus.jubituschunks.net.msg.PacketViewUpdate;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

public class NetworkHandler {
    public static final SimpleNetworkWrapper NET =
            NetworkRegistry.INSTANCE.newSimpleChannel(Tags.MOD_ID);

    private static boolean inited = false;

    public static void init() {
        if (inited) return;
        inited = true;

        int id = 0;

        // clientbound
        NET.registerMessage(PacketViewOpen.Handler.class, PacketViewOpen.class, id++, Side.CLIENT);
        NET.registerMessage(PacketViewUpdate.Handler.class, PacketViewUpdate.class, id++, Side.CLIENT);

        // serverbound
        NET.registerMessage(PacketViewSubscribe.Handler.class, PacketViewSubscribe.class, id++, Side.SERVER);
        NET.registerMessage(PacketViewUnsubscribe.Handler.class, PacketViewUnsubscribe.class, id++, Side.SERVER);
    }
}

package com.jubitus.jubituschunks.pregen;

import com.jubitus.jubituschunks.pregen.fun.FollowManager;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class PregenTickHandler {

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();

        PregenManager.tick();
        ViewerManager.tick(server);
        FollowManager.tick(server); // <-- ADD THIS LINE
    }
}


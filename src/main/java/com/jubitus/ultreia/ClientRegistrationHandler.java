package com.jubitus.ultreia;

import com.jubitus.ultreia.block.UltreiaBlocks;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID, value = Side.CLIENT)
public final class ClientRegistrationHandler {

    @SubscribeEvent
    public static void onModelRegistry(ModelRegistryEvent event) {
        UltreiaBlocks.registerModels();
    }
}

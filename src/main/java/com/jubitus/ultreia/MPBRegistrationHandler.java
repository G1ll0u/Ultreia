package com.jubitus.ultreia;

import com.jubitus.ultreia.block.MPBBlocks;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class MPBRegistrationHandler {

    /** Mod id before the rename: worlds saved with it still hold these block ids. */
    private static final String OLD_MOD_ID = "millpathbuilder";

    @SubscribeEvent
    public static void registerBlocks(RegistryEvent.Register<Block> event) {
        MPBBlocks.registerBlocks(event.getRegistry());
    }

    @SubscribeEvent
    public static void registerItems(RegistryEvent.Register<Item> event) {
        MPBBlocks.registerItems(event.getRegistry());
    }

    @SubscribeEvent
    public static void remapOldBlocks(RegistryEvent.MissingMappings<Block> event) {
        for (RegistryEvent.MissingMappings.Mapping<Block> m : event.getAllMappings()) {
            if (!OLD_MOD_ID.equals(m.key.getNamespace())) continue;
            if ("path_block".equals(m.key.getPath())) m.remap(MPBBlocks.PATH_BLOCK);
            else if ("path_slab".equals(m.key.getPath())) m.remap(MPBBlocks.PATH_SLAB);
        }
    }

    @SubscribeEvent
    public static void remapOldItems(RegistryEvent.MissingMappings<Item> event) {
        for (RegistryEvent.MissingMappings.Mapping<Item> m : event.getAllMappings()) {
            if (!OLD_MOD_ID.equals(m.key.getNamespace())) continue;
            if ("path_block".equals(m.key.getPath())) m.remap(Item.getItemFromBlock(MPBBlocks.PATH_BLOCK));
            else if ("path_slab".equals(m.key.getPath())) m.remap(Item.getItemFromBlock(MPBBlocks.PATH_SLAB));
        }
    }
}

package com.jubitus.ultreia.block;

import com.jubitus.ultreia.Tags;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import net.minecraftforge.registries.IForgeRegistry;

public final class UltreiaBlocks {

    public static PathBlock PATH_BLOCK;
    public static PathSlabBlock PATH_SLAB;

    private UltreiaBlocks() {}

    public static void registerBlocks(IForgeRegistry<Block> registry) {
        registry.register(PATH_BLOCK = new PathBlock());
        registry.register(PATH_SLAB = new PathSlabBlock());
    }

    public static void registerItems(IForgeRegistry<Item> registry) {
        registry.register(new ItemBlock(PATH_BLOCK).setRegistryName(PATH_BLOCK.getRegistryName()));
        registry.register(new ItemBlock(PATH_SLAB).setRegistryName(PATH_SLAB.getRegistryName()));
    }

    @SideOnly(Side.CLIENT)
    public static void registerModels() {
        ModelLoader.setCustomModelResourceLocation(
                Item.getItemFromBlock(PATH_BLOCK), 0,
                new ModelResourceLocation(Tags.MOD_ID + ":path_block", "inventory"));
        ModelLoader.setCustomModelResourceLocation(
                Item.getItemFromBlock(PATH_SLAB), 0,
                new ModelResourceLocation(Tags.MOD_ID + ":path_slab", "half=bottom"));
    }
}

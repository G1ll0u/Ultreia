package com.jubitus.ultreia.block;

import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;

public class PathBlock extends Block {

    public PathBlock() {
        super(Material.GROUND);
        setRegistryName("ultreia", "path_block");
        setTranslationKey("ultreia.path_block");
        setHardness(0.8F);
        setResistance(1.0F);
        setSoundType(SoundType.GROUND);
        setCreativeTab(CreativeTabs.DECORATIONS);
    }
}

package com.jubitus.millpathbuilder.block;

import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;

public class MPBPathBlock extends Block {

    public MPBPathBlock() {
        super(Material.GROUND);
        setRegistryName("millpathbuilder", "path_block");
        setHardness(0.8F);
        setResistance(1.0F);
        setSoundType(SoundType.GROUND);
        setCreativeTab(CreativeTabs.DECORATIONS);
    }
}

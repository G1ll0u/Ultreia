package com.jubitus.millpathbuilder.gen;

import com.jubitus.millpathbuilder.gen.mill.MPBMillPathPalette;
import com.jubitus.millpathbuilder.gen.newp.MPBTreeCompat;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;

public final class MPBGround {

    private MPBGround() {}

    // MPBGround.java
    public static boolean isRoadReplaceableGround(IBlockState st) {
        if (st == null) return false;

        if (MPBTreeCompat.isDynamicTreesBlock(st)) return false;
        Block b = st.getBlock();
        if (b == Blocks.AIR) return false;

        if (MPBMillPathPalette.isMillPath(b)) return true;

        Material m = st.getMaterial();

        if (!m.isSolid()) return false;

        if (m == Material.WOOD || m == Material.LEAVES) return false;
        if (m == Material.GLASS) return false;
        if (m == Material.IRON || m == Material.ANVIL) return false;
        if (m == Material.REDSTONE_LIGHT) return false;

        if (b == Blocks.BEDROCK || b == Blocks.BARRIER) return false;
        if (b == Blocks.OBSIDIAN) return false;

        if (b == Blocks.GRASS || b == Blocks.DIRT || b == Blocks.GRAVEL || b == Blocks.SAND || b == Blocks.CLAY) return true;
        if (b == Blocks.STONE || b == Blocks.STONEBRICK || b == Blocks.COBBLESTONE) return true;
        if (b == Blocks.MYCELIUM) return true;
        if (b == Blocks.SNOW || b == Blocks.SNOW_LAYER) return true;
        if (b == Blocks.HARDENED_CLAY || b == Blocks.STAINED_HARDENED_CLAY) return true;

        if (m == Material.GROUND || m == Material.GRASS || m == Material.SAND || m == Material.CLAY || m == Material.SNOW) return true;
        if (m == Material.ROCK) return true;

        String name = (b.getRegistryName() != null) ? b.getRegistryName().toString().toLowerCase() : "";
        if (name.isEmpty()) return false;

        if (name.contains("grass") || name.contains("dirt") || name.contains("soil") || name.contains("loam")
                || name.contains("silt") || name.contains("sand") || name.contains("gravel") || name.contains("clay")
                || name.contains("stone") || name.contains("rock") || name.contains("overgrown")
                || name.contains("moss") || name.contains("podzol") || name.contains("mycel")) {
            return true;
        }

        return false;
    }
}
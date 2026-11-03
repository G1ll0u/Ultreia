package com.jubitus.ultreia.terrain;

import net.minecraft.block.BlockWall;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockSlab;
import com.jubitus.ultreia.mill.MillPathPalette;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;

public final class GroundRules {

    private GroundRules() {}

    // GroundRules.java
    public static boolean isRoadReplaceableGround(IBlockState st) {
        if (st == null) return false;

        // Rootydirt is plain dirt anchoring a DT tree — valid painting surface once the tree is cleared.
        if (TreeCompat.isDTRootyBlock(st)) return true;
        // Other DT structural blocks (branches, trunkshell, saplings) are never ground.
        if (TreeCompat.isDynamicTreesBlock(st)) return false;
        Block b = st.getBlock();
        if (b == Blocks.AIR) return false;

        // Never paint over farmland or tilled soil — would destroy crops.
        if (isFarmland(st)) return false;

        if (MillPathPalette.isMillPath(b)) return true;

        Material m = st.getMaterial();

        if (!m.isSolid()) return false;

        if (m == Material.WOOD || m == Material.LEAVES) return false;
        if (m == Material.GLASS) return false;
        if (m == Material.IRON || m == Material.ANVIL) return false;
        if (m == Material.REDSTONE_LIGHT) return false;

        if (b == Blocks.BEDROCK || b == Blocks.BARRIER) return false;
        if (b == Blocks.OBSIDIAN) return false;

        if (b == Blocks.GRASS || b == Blocks.DIRT || b == Blocks.GRAVEL || b == Blocks.SAND || b == Blocks.CLAY) return true;
        if (b == Blocks.STONE) return true;
        if (isBuiltStone(b)) return false;
        if (b == Blocks.MYCELIUM) return true;
        if (b == Blocks.SNOW || b == Blocks.SNOW_LAYER) return true;
        if (b == Blocks.HARDENED_CLAY || b == Blocks.STAINED_HARDENED_CLAY) return true;

        if (m == Material.GROUND || m == Material.GRASS || m == Material.SAND || m == Material.CLAY || m == Material.SNOW) return true;
        if (m == Material.ROCK) return true;

        String name = (b.getRegistryName() != null) ? b.getRegistryName().toString().toLowerCase() : "";
        if (name.isEmpty()) return false;

        // Also exclude modded farmland / tilled soil by name.
        if (name.contains("farmland") || name.contains("tilledsoil") || name.contains("tilled_soil")) return false;

        if (name.contains("grass") || name.contains("dirt") || name.contains("soil") || name.contains("loam")
                || name.contains("silt") || name.contains("sand") || name.contains("gravel") || name.contains("clay")
                || name.contains("stone") || name.contains("rock") || name.contains("overgrown")
                || name.contains("moss") || name.contains("podzol") || name.contains("mycel")) {
            return true;
        }

        return false;
    }

    /** Stone blocks that only appear in structures: never treated as natural ground. */
    private static boolean isBuiltStone(Block b) {
        if (b == Blocks.STONEBRICK || b == Blocks.COBBLESTONE || b == Blocks.MOSSY_COBBLESTONE) return true;
        if (b == Blocks.BRICK_BLOCK || b == Blocks.NETHER_BRICK) return true;
        return b instanceof BlockStairs || b instanceof BlockWall || b instanceof BlockSlab;
    }

    /**
     * Returns true if this block is farmland or a modded equivalent (tilled soil, etc.).
     * These blocks must never be painted over or replaced by road blocks.
     */
    public static boolean isFarmland(IBlockState st) {
        if (st == null) return false;
        Block b = st.getBlock();
        if (b == Blocks.FARMLAND) return true;
        ResourceLocation id = b.getRegistryName();
        if (id == null) return false;
        String name = id.toString().toLowerCase();
        return name.contains("farmland") || name.contains("tilledsoil") || name.contains("tilled_soil");
    }
}
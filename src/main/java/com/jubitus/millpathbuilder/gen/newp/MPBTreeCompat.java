package com.jubitus.millpathbuilder.gen.newp;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class MPBTreeCompat {

    private MPBTreeCompat() {}

    public static boolean isDynamicTreesBranch(IBlockState st) {
        if (st == null) return false;

        Block b = st.getBlock();
        if (b == null) return false;

        String cn = b.getClass().getName();
        if (cn != null && cn.contains("dynamictrees") && cn.contains("BlockBranch")) {
            return true;
        }

        ResourceLocation id = b.getRegistryName();
        if (id != null) {
            String s = id.toString().toLowerCase();
            if (s.contains("dynamictrees") && s.contains("branch")) {
                return true;
            }
        }

        return false;
    }

    public static boolean isDynamicTreesTrunkShell(IBlockState st) {
        if (st == null) return false;

        Block b = st.getBlock();
        if (b == null) return false;

        String cn = b.getClass().getName();
        if (cn != null && cn.contains("dynamictrees") && cn.contains("BlockTrunkShell")) {
            return true;
        }

        ResourceLocation id = b.getRegistryName();
        if (id != null) {
            String s = id.toString().toLowerCase();
            if (s.contains("dynamictrees") && s.contains("trunkshell")) {
                return true;
            }
        }

        return false;
    }

    public static boolean isDynamicTreesBlock(IBlockState st) {
        return isDynamicTreesBranch(st) || isDynamicTreesTrunkShell(st);
    }

    public static boolean isTreeWoodLike(World world, BlockPos pos, IBlockState st) {
        if (st == null) return false;

        if (isDynamicTreesBlock(st)) return true;

        Block b = st.getBlock();
        Material m = st.getMaterial();

        if (b.isWood(world, pos)) return true;
        if (m == Material.WOOD) return true;

        return false;
    }

    public static boolean isTreeFoliageLike(World world, BlockPos pos, IBlockState st) {
        if (st == null) return false;

        Block b = st.getBlock();
        Material m = st.getMaterial();

        if (b.isLeaves(st, world, pos)) return true;
        if (m == Material.LEAVES) return true;
        if (m == Material.PLANTS || m == Material.VINE) return true;

        ResourceLocation id = b.getRegistryName();
        if (id != null) {
            String s = id.toString().toLowerCase();
            if (s.contains("dynamictrees") && (s.contains("leaves") || s.contains("leaf"))) {
                return true;
            }
        }

        return false;
    }
}
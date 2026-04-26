package com.jubitus.millpathbuilder.gen;

import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.mill.MPBMillPathPalette;
import com.jubitus.millpathbuilder.gen.newp.MPBTreeCompat;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class MPBRoadColumns {

    private MPBRoadColumns() {}

    public static void clearRoadColumn(World world, BlockPos base, int up, boolean dryRun, boolean allowSolidNaturalCut) {
        if (dryRun) return;

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(base);

        for (int dy = 1; dy <= up; dy++) {
            p.setPos(base.getX(), base.getY() + dy, base.getZ());

            IBlockState st = world.getBlockState(p);
            if (st.getMaterial() == Material.AIR) continue;

            if (hasTile(world, p)) continue;
            if (isAlwaysKeep(st)) continue;

            if (wouldCauseWaterFlow(world, p)) continue;

            if (isSoftClearable(world, p, st)) {
                clearWithPairIfNeeded(world, p, st);
                continue;
            }

            if (allowSolidNaturalCut && isNaturalSolid(st)) {
                setAir(world, p);
            }
        }
    }

    public static void clearAtAndAbove(World world, BlockPos pos, int up, boolean dryRun, boolean allowSolidNaturalCut) {
        if (dryRun) return;

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(pos);
        for (int dy = 0; dy <= up; dy++) {
            p.setPos(pos.getX(), pos.getY() + dy, pos.getZ());

            IBlockState st = world.getBlockState(p);
            if (st.getMaterial() == Material.AIR) continue;

            if (hasTile(world, p)) continue;
            if (isAlwaysKeep(st)) continue;

            if (wouldCauseWaterFlow(world, p)) continue;

            if (isSoftClearable(world, p, st)) {
                clearWithPairIfNeeded(world, p, st);
                continue;
            }

            if (allowSolidNaturalCut && isNaturalSolid(st)) {
                setAir(world, p);
            }
        }
    }

    public static void removeMillPathNearby(World world, BlockPos center, int dyMin, int dyMax, boolean dryRun) {
        if (dryRun) return;

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = dyMin; dy <= dyMax; dy++) {
            p.setPos(center.getX(), center.getY() + dy, center.getZ());
            IBlockState st = world.getBlockState(p);
            if (st != null && MPBMillPathPalette.isMillPath(st.getBlock())) {
                if (wouldCauseWaterFlow(world, p)) continue;
                setAir(world, p);
            }
        }
    }

    public static boolean isSoftClearable(World world, BlockPos pos, IBlockState st) {
        Material m = st.getMaterial();
        Block b = st.getBlock();

        if (m == Material.AIR) return false;
        if (b == Blocks.SNOW_LAYER) return true;
        if (m.isReplaceable()) return true;
        if (b.isReplaceable(world, pos)) return true;
        if (MPBTreeCompat.isTreeFoliageLike(world, pos, st)) return true;

        if (MPBConfig.PAINTING.clearTrees && MPBConfig.PAINTING.clearTreeTrunks) {
            if (MPBTreeCompat.isTreeWoodLike(world, pos, st)) return true;
        }

        return false;
    }

    public static boolean isNaturalSolid(IBlockState st) {
        if (st == null) return false;

        if (MPBMillPathPalette.isMillPath(st.getBlock())) return false;

        if (MPBTreeCompat.isDynamicTreesBlock(st)) {
            return MPBConfig.PAINTING.clearTrees && MPBConfig.PAINTING.clearTreeTrunks;
        }

        Material m = st.getMaterial();
        if (!m.isSolid()) return false;
        if (m == Material.LEAVES) return false;
        if (m == Material.WOOD) return false;

        if (MPBGround.isRoadReplaceableGround(st)) return true;

        if (m == Material.ROCK || m == Material.GROUND || m == Material.GRASS || m == Material.SAND || m == Material.CLAY) return true;

        String name = (st.getBlock().getRegistryName() != null) ? st.getBlock().getRegistryName().toString() : "";
        if (name.contains("stone") || name.contains("rock") || name.contains("dirt") || name.contains("gravel") || name.contains("sand")) return true;

        return false;
    }

    private static void clearWithPairIfNeeded(World world, BlockPos pos, IBlockState st) {
        Block b = st.getBlock();

        if (b instanceof BlockDoublePlant) {
            BlockDoublePlant dp = (BlockDoublePlant) b;

            try {
                BlockDoublePlant.EnumBlockHalf half = st.getValue(BlockDoublePlant.HALF);
                if (half == BlockDoublePlant.EnumBlockHalf.UPPER) {
                    if (!wouldCauseWaterFlow(world, pos) && !wouldCauseWaterFlow(world, pos.down())) {
                        setAir(world, pos);
                        setAir(world, pos.down());
                    }
                    return;
                } else {
                    if (!wouldCauseWaterFlow(world, pos) && !wouldCauseWaterFlow(world, pos.up())) {
                        setAir(world, pos);
                        setAir(world, pos.up());
                    }
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        if (wouldCauseWaterFlow(world, pos)) return;
        setAir(world, pos);
    }

    private static void setAir(World world, BlockPos pos) {
        if (wouldCauseWaterFlow(world, pos)) return;
        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
    }

    private static boolean hasTile(World world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te != null;
    }

    private static boolean isAlwaysKeep(IBlockState st) {
        Block b = st.getBlock();
        return b == Blocks.BEDROCK || b == Blocks.BARRIER;
    }

    private static boolean wouldCauseWaterFlow(World world, BlockPos pos) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (EnumFacing f : EnumFacing.values()) {
            p.setPos(pos.getX() + f.getXOffset(), pos.getY() + f.getYOffset(), pos.getZ() + f.getZOffset());
            IBlockState n = world.getBlockState(p);
            if (n != null && n.getMaterial() == Material.WATER) return true;
        }
        return false;
    }
}
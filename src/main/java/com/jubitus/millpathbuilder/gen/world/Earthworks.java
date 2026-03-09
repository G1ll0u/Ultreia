package com.jubitus.millpathbuilder.gen.world;

import com.jubitus.millpathbuilder.gen.MPBGround;
import com.jubitus.millpathbuilder.gen.MPBRoadColumns;
import com.jubitus.millpathbuilder.gen.TerrainSampler;
import com.jubitus.millpathbuilder.gen.mill.MPBMillPathPalette;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
public final class Earthworks {

    private Earthworks() {}

    public static final class Settings {
        public final int roadHalfWidth;
        public final int shoulder;
        public final int maxGradePerBlock;
        public final int maxCut;
        public final int maxFill;

        public Settings(int roadHalfWidth, int shoulder, int maxGradePerBlock, int maxCut, int maxFill) {
            this.roadHalfWidth = roadHalfWidth;
            this.shoulder = shoulder;
            this.maxGradePerBlock = maxGradePerBlock;
            this.maxCut = maxCut;
            this.maxFill = maxFill;
        }
    }


    public static int gradeCell(WorldServer world, TerrainSampler sampler, int x, int z, int centerY, int dist, Settings s, boolean dryRun) {
        int targetY = centerY;

        TerrainSampler.Sample sm = sampler.sample(x, z);
        if (sm.isOcean || sm.isWater) return sm.surfaceY;

        int surfaceY = sm.surfaceY;
        int delta = targetY - surfaceY;

        if (delta > 0) {
            if (delta > s.maxFill) return surfaceY;
            fillUp(world, x, z, surfaceY, targetY, dryRun);
        } else if (delta < 0) {
            int cut = -delta;
            if (cut > s.maxCut) return surfaceY;
            boolean ok = cutDown(world, x, z, surfaceY, targetY, dryRun);
            if (!ok) return surfaceY;
        }

        if (!dryRun) {
            MPBRoadColumns.clearRoadColumn(world, new BlockPos(x, targetY, z), 2, false, true);
        }

        return targetY;
    }

    private static void fillUp(WorldServer world, int x, int z, int surfaceY, int targetY, boolean dryRun) {
        if (dryRun) return;

        for (int y = surfaceY + 1; y <= targetY; y++) {
            BlockPos p = new BlockPos(x, y, z);
            IBlockState st = world.getBlockState(p);
            Material m = st.getMaterial();

            if (m == Material.AIR) continue;
            if (m.isReplaceable() || m == Material.PLANTS || m == Material.VINE) continue;
            if (st.getBlock().isLeaves(st, world, p) || m == Material.LEAVES) continue;

            return;
        }

        for (int y = surfaceY + 1; y <= targetY; y++) {
            BlockPos p = new BlockPos(x, y, z);
            IBlockState st = world.getBlockState(p);
            if (st.getMaterial() != Material.AIR && !st.getMaterial().isReplaceable() && st.getMaterial() != Material.PLANTS && st.getMaterial() != Material.VINE) {
                return;
            }
            world.setBlockState(p, Blocks.DIRT.getDefaultState(), 2);
        }
    }

    private static boolean cutDown(WorldServer world, int x, int z, int surfaceY, int targetY, boolean dryRun) {
        if (dryRun) return true;

        for (int y = surfaceY; y > targetY; y--) {
            BlockPos p = new BlockPos(x, y, z);
            IBlockState st = world.getBlockState(p);
            Material m = st.getMaterial();

            if (m == Material.AIR) continue;
            if (m == Material.WATER) return false;

            if (world.getTileEntity(p) != null) return false;

            if (wouldCauseWaterFlow(world, p)) return false;

            float hardness;
            try {
                hardness = st.getBlockHardness(world, p);
            } catch (Throwable t) {
                hardness = 0.0f;
            }
            if (hardness < 0.0f) return false;
        }

        for (int y = surfaceY; y > targetY; y--) {
            BlockPos p = new BlockPos(x, y, z);
            IBlockState st = world.getBlockState(p);
            if (st.getMaterial() == Material.AIR) continue;
            world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
        }

        BlockPos top = new BlockPos(x, targetY, z);
        IBlockState topSt = world.getBlockState(top);
        Material tm = topSt.getMaterial();

        if (tm == Material.AIR || tm.isReplaceable() || tm == Material.PLANTS || tm == Material.VINE || topSt.getBlock().isLeaves(topSt, world, top)) {
            world.setBlockState(top, Blocks.DIRT.getDefaultState(), 2);
            return true;
        }

        if (!tm.isSolid()) {
            world.setBlockState(top, Blocks.DIRT.getDefaultState(), 2);
        }

        return true;
    }

    private static boolean wouldCauseWaterFlow(WorldServer world, BlockPos pos) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (EnumFacing f : EnumFacing.values()) {
            p.setPos(pos.getX() + f.getXOffset(), pos.getY() + f.getYOffset(), pos.getZ() + f.getZOffset());
            IBlockState n = world.getBlockState(p);
            if (n != null && n.getMaterial() == Material.WATER) return true;
        }
        return false;
    }
}
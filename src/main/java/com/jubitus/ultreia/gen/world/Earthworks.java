package com.jubitus.ultreia.gen.world;

import com.jubitus.ultreia.gen.MPBGround;
import com.jubitus.ultreia.gen.MPBRoadColumns;
import com.jubitus.ultreia.gen.TerrainSampler;
import com.jubitus.ultreia.gen.mill.MPBMillPathPalette;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

public final class Earthworks {

    private Earthworks() {}

    public static final class Settings {
        public final int roadHalfWidth;
        public final int shoulder;
        public final int maxCut;
        public final int maxFill;

        public Settings(int roadHalfWidth, int shoulder, int maxCut, int maxFill) {
            this.roadHalfWidth = roadHalfWidth;
            this.shoulder = shoulder;
            this.maxCut = maxCut;
            this.maxFill = maxFill;
        }
    }

    public static int gradeCell(WorldServer world, TerrainSampler sampler, int x, int z, int centerY, int dist, Settings s, boolean dryRun) {
        TerrainSampler.Sample sm = sampler.sample(x, z);
        if (sm == null) return centerY;
        if (sm.isOcean || sm.isWater) return sm.surfaceY;

        int naturalY = sm.surfaceY;
        int targetY = blendedTargetY(naturalY, centerY, dist, s);

        int delta = targetY - naturalY;
        if (delta > 0) {
            if (delta > s.maxFill) return naturalY;
            if (!fillUp(world, x, z, naturalY, targetY, dryRun)) return naturalY;
        } else if (delta < 0) {
            int cut = -delta;
            if (cut > s.maxCut) return naturalY;
            if (!cutDown(world, x, z, naturalY, targetY, dryRun)) return naturalY;
        }

        if (!dryRun) {
            MPBRoadColumns.clearRoadColumn(world, new BlockPos(x, targetY, z), 2, false, true);
        }

        return targetY;
    }

    private static int blendedTargetY(int naturalY, int centerY, int dist, Settings s) {
        int fullRoad = Math.max(0, s.roadHalfWidth);
        int shoulder = Math.max(0, s.shoulder);

        if (dist <= fullRoad) {
            return centerY;
        }

        if (shoulder <= 0 || dist >= fullRoad + shoulder) {
            return naturalY;
        }

        double t = (double) (dist - fullRoad) / (double) shoulder;
        double v = (1.0 - t) * (double) centerY + t * (double) naturalY;
        return (int) Math.round(v);
    }

    private static boolean fillUp(WorldServer world, int x, int z, int surfaceY, int targetY, boolean dryRun) {
        if (dryRun) return true;

        for (int y = surfaceY + 1; y <= targetY; y++) {
            BlockPos p = new BlockPos(x, y, z);
            IBlockState st = world.getBlockState(p);
            Material m = st.getMaterial();

            if (m == Material.AIR) continue;
            if (m.isReplaceable()) continue;
            if (m == Material.PLANTS || m == Material.VINE) continue;
            if (st.getBlock().isLeaves(st, world, p) || m == Material.LEAVES) continue;

            return false;
        }

        for (int y = surfaceY + 1; y <= targetY; y++) {
            BlockPos p = new BlockPos(x, y, z);
            world.setBlockState(p, Blocks.DIRT.getDefaultState(), 2);
        }

        return true;
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
            if (st.getMaterial() != Material.AIR) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
            }
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

    // -----------------------------------------------------------------------
    // Talus side-slope pass
    // -----------------------------------------------------------------------

    /**
     * Applies a sloped talus at one non-road cell (x, z).
     *
     * roadEdgeY:   surface Y of the nearest road cell
     * lateralDist: Euclidean distance from (x,z) to that road cell
     * slopeRatio:  lateral blocks per vertical block (1.0 = 45°, higher = gentler)
     */
    public static void applyTalusAt(WorldServer world, TerrainSampler sampler,
                                    int x, int z, int roadEdgeY,
                                    double lateralDist, double slopeRatio,
                                    int maxCut, int maxFill, boolean dryRun) {
        TerrainSampler.Sample sm = sampler.sample(x, z);
        if (sm == null || sm.isOcean || sm.isWater) return;

        int naturalY = sm.surfaceY;
        int delta = naturalY - roadEdgeY;
        if (delta == 0) return;

        int slopeRise = (int) Math.ceil(lateralDist / slopeRatio);

        if (delta > 0) {
            // CUT side: terrain is above road. Slope rises from road edge upward.
            int talusTargetY = roadEdgeY + slopeRise;
            if (talusTargetY >= naturalY) return;
            int cut = naturalY - talusTargetY;
            if (cut > maxCut) return;
            if (dryRun) return;
            // Clear snow, plants, and any non-solid above the natural surface first.
            // Using flag 3 (NOTIFY_NEIGHBORS | SEND_TO_CLIENT) breaks double-plant upper halves, etc.
            clearAboveSurface(world, x, z, naturalY);
            if (!talusCutDown(world, x, z, naturalY, talusTargetY)) return;
            restoreCutSurface(world, x, z, talusTargetY);
        } else {
            // FILL side: terrain is below road. Slope descends from road edge downward.
            int talusTargetY = roadEdgeY - slopeRise;
            if (talusTargetY <= naturalY) return;
            int fill = talusTargetY - naturalY;
            if (fill > maxFill) return;
            if (dryRun) return;
            if (!talusFillUp(world, x, z, naturalY, talusTargetY)) return;
            placeFillSurface(world, x, z, talusTargetY);
        }

        sampler.invalidateAround(x, z, 1);
    }

    /**
     * Clears non-solid blocks above the surface block (snow, plants, double-plants, leaves, vines).
     * Uses flag 3 so that block updates propagate — upper halves of double plants break naturally.
     */
    private static void clearAboveSurface(WorldServer world, int x, int z, int surfaceY) {
        for (int dy = 1; dy <= 4; dy++) {
            BlockPos p = new BlockPos(x, surfaceY + dy, z);
            IBlockState st = world.getBlockState(p);
            Material m = st.getMaterial();
            if (m == Material.AIR) break;
            if (m.isSolid() && m != Material.LEAVES && !st.getBlock().isLeaves(st, world, p)) break;
            // flag 3 = NOTIFY_NEIGHBORS | SEND_TO_CLIENT — propagates updates to upper double-plant halves etc.
            world.setBlockState(p, Blocks.AIR.getDefaultState(), 3);
        }
    }

    private static boolean talusCutDown(WorldServer world, int x, int z, int fromY, int toY) {
        for (int y = fromY; y > toY; y--) {
            BlockPos p = new BlockPos(x, y, z);
            IBlockState st = world.getBlockState(p);
            Material m = st.getMaterial();
            if (m == Material.AIR) continue;
            if (m == Material.WATER) return false;
            if (world.getTileEntity(p) != null) return false;
            if (wouldCauseWaterFlow(world, p)) return false;
            try {
                if (st.getBlockHardness(world, p) < 0.0f) return false;
            } catch (Throwable t) {
                // ignore
            }
        }
        for (int y = fromY; y > toY; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (world.getBlockState(p).getMaterial() != Material.AIR) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 3);
            }
        }
        return true;
    }

    private static boolean talusFillUp(WorldServer world, int x, int z, int fromY, int toY) {
        for (int y = fromY + 1; y <= toY; y++) {
            BlockPos p = new BlockPos(x, y, z);
            IBlockState st = world.getBlockState(p);
            Material m = st.getMaterial();
            if (m == Material.AIR || m.isReplaceable() || m == Material.PLANTS || m == Material.VINE
                    || st.getBlock().isLeaves(st, world, p) || m == Material.LEAVES) continue;
            return false;
        }
        for (int y = fromY + 1; y < toY; y++) {
            world.setBlockState(new BlockPos(x, y, z), Blocks.DIRT.getDefaultState(), 2);
        }
        return true;
    }

    private static void restoreCutSurface(WorldServer world, int x, int z, int y) {
        BlockPos pos = new BlockPos(x, y, z);
        IBlockState st = world.getBlockState(pos);
        Block b = st.getBlock();
        Material m = st.getMaterial();
        if (m == Material.ROCK) return; // stone/rock — leave natural
        if (b == Blocks.SAND || b == Blocks.GRAVEL) return; // natural sandy/gravel surface
        if (b == Blocks.DIRT || m == Material.AIR || m.isReplaceable()
                || m == Material.PLANTS || m == Material.VINE || b.isLeaves(st, world, pos)) {
            world.setBlockState(pos, Blocks.GRASS.getDefaultState(), 2);
        }
    }

    private static void placeFillSurface(WorldServer world, int x, int z, int y) {
        world.setBlockState(new BlockPos(x, y, z), Blocks.GRASS.getDefaultState(), 2);
    }
}

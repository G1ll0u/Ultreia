package com.jubitus.millpathbuilder.gen.bridge;

import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.MPBGround;
import com.jubitus.millpathbuilder.gen.TerrainSampler;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.*;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.List;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import net.minecraft.block.BlockPlanks;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import net.minecraft.block.BlockPlanks;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

public final class BridgeBuilder2 {

    private BridgeBuilder2() {}

    private static IBlockState slabBottom() {
        return Blocks.WOODEN_SLAB.getDefaultState()
                .withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.BOTTOM)
                .withProperty(net.minecraft.block.BlockWoodSlab.VARIANT, BlockPlanks.EnumType.OAK);
    }

    private static IBlockState slabTop() {
        return Blocks.WOODEN_SLAB.getDefaultState()
                .withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.TOP)
                .withProperty(net.minecraft.block.BlockWoodSlab.VARIANT, BlockPlanks.EnumType.OAK);
    }

    private static IBlockState supportState() {
        return Blocks.LOG.getDefaultState();
    }

    public static boolean build(WorldServer world, TerrainSampler sampler, BridgeSpan span, int roadWidth, boolean dryRun) {
        if (span == null || span.centerline == null || span.centerline.isEmpty()) return true;

        int width = Math.max(1, roadWidth);
        if ((width & 1) == 0) width++;
        int half = width / 2;

        int len = span.lengthBlocks();
        int maxLen = Math.max(8, MPBConfig.BRIDGES.maxBridgeLenBlocks);
        if (len > maxLen) return false;

        int supportSpacing = Math.max(2, MPBConfig.BRIDGES.supportSpacing);
        int maxSupportDepth = Math.max(8, MPBConfig.BRIDGES.maxSupportDepth);

        int[] deckHalfY = BridgeGeometry.buildDeckHalfProfile(span, sampler);
        LongOpenHashSet deckXZ = new LongOpenHashSet(Math.max(128, span.centerline.size() * 8));

        for (int i = 0; i < span.centerline.size(); i++) {
            BlockPos c = span.centerline.get(i);

            BridgeGeometry.Dir2 dir = BridgeGeometry.localDir(span.centerline, i);
            BridgeGeometry.Dir2 perp = dir.perp();

            BridgeGeometry.SlabPlacement sp = BridgeGeometry.slabPlacementForSurfaceHalfY(deckHalfY[i]);
            IBlockState deckSlab = sp.top ? slabTop() : slabBottom();

            if (!placeStripe(world, c.getX(), c.getZ(), perp, half, sp, deckSlab, dryRun, sampler, deckXZ)) {
                return false;
            }

            if (i > 0) {
                BlockPos p = span.centerline.get(i - 1);
                int stepX = c.getX() - p.getX();
                int stepZ = c.getZ() - p.getZ();

                if (stepX != 0 && stepZ != 0) {
                    if (!fillDiagonalGap(world, p.getX(), p.getZ(), c.getX(), c.getZ(), perp, half, sp, deckSlab, dryRun, sampler, deckXZ)) {
                        return false;
                    }
                }
            }

            if (!span.micro && (i % supportSpacing == 0)) {
                BlockPos s1 = supportPosAdjacentToDeckEdge(c.getX(), c.getZ(), perp, half, true, sp.blockY);
                BlockPos s2 = supportPosAdjacentToDeckEdge(c.getX(), c.getZ(), perp, half, false, sp.blockY);

                s1 = pushSupportOutsideDeck(deckXZ, s1, perp, true);
                s2 = pushSupportOutsideDeck(deckXZ, s2, perp, false);

                placeSupportColumn(world, s1.getX(), sp.blockY, s1.getZ(), maxSupportDepth, dryRun, sp.top);
                placeSupportColumn(world, s2.getX(), sp.blockY, s2.getZ(), maxSupportDepth, dryRun, sp.top);
            }
        }

        return true;
    }

    private static boolean placeStripe(WorldServer world,
                                       int cx,
                                       int cz,
                                       BridgeGeometry.Dir2 perp,
                                       int half,
                                       BridgeGeometry.SlabPlacement sp,
                                       IBlockState slab,
                                       boolean dryRun,
                                       TerrainSampler sampler,
                                       LongOpenHashSet deckXZ) {
        for (int o = -half; o <= half; o++) {
            int x = cx + perp.dx * o;
            int z = cz + perp.dz * o;
            if (!placeDeckCell(world, x, z, sp, slab, dryRun, sampler, deckXZ)) {
                return false;
            }
        }
        return true;
    }

    private static boolean fillDiagonalGap(WorldServer world,
                                           int px,
                                           int pz,
                                           int cx,
                                           int cz,
                                           BridgeGeometry.Dir2 perp,
                                           int half,
                                           BridgeGeometry.SlabPlacement sp,
                                           IBlockState slab,
                                           boolean dryRun,
                                           TerrainSampler sampler,
                                           LongOpenHashSet deckXZ) {
        int stepX = Integer.signum(cx - px);
        int stepZ = Integer.signum(cz - pz);

        for (int o = -half; o <= half; o++) {
            int ax = px + perp.dx * o;
            int az = pz + perp.dz * o;

            int bx = cx + perp.dx * o;
            int bz = cz + perp.dz * o;

            int c1x = ax + stepX;
            int c1z = az;
            if (isBetween(c1x, c1z, ax, az, bx, bz)) {
                if (!placeDeckCell(world, c1x, c1z, sp, slab, dryRun, sampler, deckXZ)) {
                    return false;
                }
            }

            int c2x = ax;
            int c2z = az + stepZ;
            if (isBetween(c2x, c2z, ax, az, bx, bz)) {
                if (!placeDeckCell(world, c2x, c2z, sp, slab, dryRun, sampler, deckXZ)) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean isBetween(int x, int z, int x0, int z0, int x1, int z1) {
        int minX = Math.min(x0, x1);
        int maxX = Math.max(x0, x1);
        int minZ = Math.min(z0, z1);
        int maxZ = Math.max(z0, z1);
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    private static boolean placeDeckCell(WorldServer world,
                                         int x,
                                         int z,
                                         BridgeGeometry.SlabPlacement sp,
                                         IBlockState slab,
                                         boolean dryRun,
                                         TerrainSampler sampler,
                                         LongOpenHashSet deckXZ) {
        long k = xzKey(x, z);
        deckXZ.add(k);

        if (dryRun) return true;

        BlockPos deckPos = new BlockPos(x, sp.blockY, z);
        IBlockState cur = world.getBlockState(deckPos);

        if (world.getTileEntity(deckPos) != null) return false;

        if (!canReplaceForBridgeDeck(world, deckPos, cur)) {
            return false;
        }

        clearBridgeDeckCell(world, deckPos);

        if (!world.setBlockState(deckPos, slab, 2)) {
            return false;
        }

        IBlockState placed = world.getBlockState(deckPos);
        if (placed == null || placed.getBlock() != slab.getBlock()) {
            return false;
        }

        clearBridgeWalkVolume(world, x, z, sp.blockY + 1, sp.blockY + 3);
        return true;
    }

    private static boolean canReplaceForBridgeDeck(WorldServer world, BlockPos pos, IBlockState st) {
        if (st == null) return true;

        Material m = st.getMaterial();
        Block b = st.getBlock();

        if (m == Material.AIR) return true;
        if (m == Material.WATER) return true;
        if (m == Material.PLANTS || m == Material.VINE) return true;
        if (m.isReplaceable()) return true;
        if (b == Blocks.WATERLILY) return true;
        if (b == Blocks.SNOW_LAYER) return true;
        if (b.isLeaves(st, world, pos) || m == Material.LEAVES) return true;

        if (MPBConfig.PAINTING.clearTrees && MPBConfig.PAINTING.clearTreeTrunks) {
            if (b.isWood(world, pos) || m == Material.WOOD) return true;
        }

        return MPBGround.isRoadReplaceableGround(st);
    }

    private static void clearBridgeDeckCell(WorldServer world, BlockPos pos) {
        IBlockState st = world.getBlockState(pos);
        if (st == null) return;

        Material m = st.getMaterial();
        Block b = st.getBlock();

        if (m == Material.AIR) return;
        if (world.getTileEntity(pos) != null) return;

        if (m == Material.WATER || b == Blocks.WATERLILY || m == Material.PLANTS || m == Material.VINE || m.isReplaceable()
                || b == Blocks.SNOW_LAYER || b.isLeaves(st, world, pos) || m == Material.LEAVES) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
            return;
        }

        if (MPBConfig.PAINTING.clearTrees && MPBConfig.PAINTING.clearTreeTrunks) {
            if (b.isWood(world, pos) || m == Material.WOOD) {
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
            }
        }
    }

    private static void clearBridgeWalkVolume(WorldServer world, int x, int z, int yFrom, int yTo) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int y = yFrom; y <= yTo; y++) {
            p.setPos(x, y, z);

            IBlockState st = world.getBlockState(p);
            if (st == null) continue;

            Material m = st.getMaterial();
            if (m == Material.AIR) continue;

            if (world.getTileEntity(p) != null) continue;

            boolean nearWater = isAdjacentToWater(world, p);

            if (m == Material.PLANTS || m == Material.VINE || m.isReplaceable() || st.getBlock() == Blocks.SNOW_LAYER) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                continue;
            }
            if (st.getBlock().isLeaves(st, world, p) || m == Material.LEAVES) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                continue;
            }

            if (MPBConfig.PAINTING.clearTrees && MPBConfig.PAINTING.clearTreeTrunks) {
                if (looksLikeTreeWood(world, p, st)) {
                    world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                    continue;
                }
            }

            if (!nearWater && isNaturalSolidForBridgeCut(st)) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
            }
        }
    }

    private static boolean isAdjacentToWater(WorldServer world, BlockPos pos) {
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (EnumFacing f : EnumFacing.values()) {
            q.setPos(pos.getX() + f.getXOffset(), pos.getY() + f.getYOffset(), pos.getZ() + f.getZOffset());
            IBlockState n = world.getBlockState(q);
            if (n != null && n.getMaterial() == Material.WATER) return true;
        }
        return false;
    }

    private static boolean isNaturalSolidForBridgeCut(IBlockState st) {
        Material m = st.getMaterial();
        if (!m.isSolid()) return false;
        if (m == Material.WOOD || m == Material.IRON || m == Material.GLASS) return false;
        if (m == Material.LEAVES) return false;
        if (st.getBlock() == Blocks.BEDROCK || st.getBlock() == Blocks.BARRIER) return false;
        return MPBGround.isRoadReplaceableGround(st)
                || m == Material.ROCK
                || m == Material.GROUND
                || m == Material.GRASS
                || m == Material.SAND
                || m == Material.CLAY;
    }

    private static long xzKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }


    private static boolean looksLikeTreeWood(WorldServer world, BlockPos pos, IBlockState st) {
        if (!(st.getBlock().isWood(world, pos) || st.getMaterial() == Material.WOOD)) return false;

        int leaves = 0;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    q.setPos(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    IBlockState st2 = world.getBlockState(q);
                    if (st2 == null) continue;
                    if (st2.getBlock().isLeaves(st2, world, q) || st2.getMaterial() == Material.LEAVES) {
                        leaves++;
                        if (leaves >= 3) return true;
                    }
                }
            }
        }
        return false;
    }

    private static BlockPos supportPosAdjacentToDeckEdge(int cx,
                                                         int cz,
                                                         BridgeGeometry.Dir2 perp,
                                                         int half,
                                                         boolean positiveSide,
                                                         int y) {
        int side = positiveSide ? 1 : -1;

        int ex = cx + perp.dx * (side * half);
        int ez = cz + perp.dz * (side * half);

        int sx = sign(perp.dx * side);
        int sz = sign(perp.dz * side);

        int cand1x = ex + sx;
        int cand1z = ez;
        int cand2x = ex;
        int cand2z = ez + sz;

        boolean diagonal = Math.abs(perp.dx) == Math.abs(perp.dz);

        int rx;
        int rz;
        if (!diagonal) {
            if (Math.abs(perp.dx) > Math.abs(perp.dz)) {
                rx = cand1x;
                rz = cand1z;
            } else {
                rx = cand2x;
                rz = cand2z;
            }
        } else {
            if (positiveSide) {
                rx = cand2x;
                rz = cand2z;
            } else {
                rx = cand1x;
                rz = cand1z;
            }
        }

        return new BlockPos(rx, y, rz);
    }

    private static int sign(int v) {
        return v < 0 ? -1 : (v > 0 ? 1 : 0);
    }

    private static void placeSupportColumn(WorldServer world,
                                           int x,
                                           int yDeckBlock,
                                           int z,
                                           int maxDepth,
                                           boolean dryRun,
                                           boolean deckIsTopSlab) {
        if (dryRun) return;

        int y = yDeckBlock - 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);

        int placedDown = 0;
        while (placedDown < maxDepth && y > 1) {
            IBlockState st = world.getBlockState(p);
            if (isSolidGroundStop(st)) break;
            world.setBlockState(p, supportState(), 2);
            y--;
            placedDown++;
            p.setPos(x, y, z);
        }

        int capTopY = yDeckBlock + (deckIsTopSlab ? 2 : 1);

        for (int yy = Math.max(2, y + 1); yy <= capTopY; yy++) {
            p.setPos(x, yy, z);

            IBlockState st = world.getBlockState(p);
            if (st != null && st.getBlock() == Blocks.WOODEN_SLAB) {
                continue;
            }
            world.setBlockState(p, supportState(), 2);
        }
    }

    private static boolean isSolidGroundStop(IBlockState st) {
        if (st == null) return true;
        Material m = st.getMaterial();
        return m.isSolid() && m != Material.WATER && m != Material.ICE;
    }

    private static boolean isInDeckStripe(int x, int z, int cx, int cz, BridgeGeometry.Dir2 perp, int half) {
        for (int o = -half; o <= half; o++) {
            int dx = perp.dx * o;
            int dz = perp.dz * o;
            if (x == cx + dx && z == cz + dz) return true;
        }
        return false;
    }

    private static BlockPos pushOutsideDeckStripe(BlockPos support,
                                                  int cx,
                                                  int cz,
                                                  BridgeGeometry.Dir2 perp,
                                                  int half) {
        int x = support.getX();
        int z = support.getZ();

        if (!isInDeckStripe(x, z, cx, cz, perp, half)) return support;

        int sx = sign(perp.dx);
        int sz = sign(perp.dz);

        if (Math.abs(perp.dx) == Math.abs(perp.dz)) {
            BlockPos a = new BlockPos(x + sx, support.getY(), z);
            if (!isInDeckStripe(a.getX(), a.getZ(), cx, cz, perp, half)) return a;

            BlockPos b = new BlockPos(x, support.getY(), z + sz);
            if (!isInDeckStripe(b.getX(), b.getZ(), cx, cz, perp, half)) return b;

            return new BlockPos(x + sx, support.getY(), z + sz);
        }

        if (Math.abs(perp.dx) > Math.abs(perp.dz)) {
            return new BlockPos(x + sx, support.getY(), z);
        }
        return new BlockPos(x, support.getY(), z + sz);
    }

    private static BlockPos pushSupportOutsideDeck(LongOpenHashSet deckXZ,
                                                   BlockPos pos,
                                                   BridgeGeometry.Dir2 perp,
                                                   boolean positiveSide) {
        int x = pos.getX();
        int z = pos.getZ();

        int side = positiveSide ? 1 : -1;

        int sx = sign(perp.dx * side);
        int sz = sign(perp.dz * side);

        boolean diagonal = Math.abs(perp.dx) == Math.abs(perp.dz);
        int stepX = 0;
        int stepZ = 0;

        if (!diagonal) {
            if (Math.abs(perp.dx) > Math.abs(perp.dz)) {
                stepX = sx;
                stepZ = 0;
            } else {
                stepX = 0;
                stepZ = sz;
            }
        } else {
            if (positiveSide) {
                stepX = 0;
                stepZ = sz;
            } else {
                stepX = sx;
                stepZ = 0;
            }
        }

        for (int t = 0; t < 5; t++) {
            if (!deckXZ.contains(xzKey(x, z))) break;
            x += stepX;
            z += stepZ;
        }

        return new BlockPos(x, pos.getY(), z);
    }
}
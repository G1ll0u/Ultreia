package com.jubitus.millpathbuilder.gen.bridge;

import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.MPBGround;
import com.jubitus.millpathbuilder.gen.TerrainSampler;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.BlockPlanks;
import net.minecraft.block.BlockSlab;
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

        int[] deckHalfY = buildDeckHalfProfile(span);
        LongOpenHashSet deckXZ = new LongOpenHashSet(Math.max(128, span.centerline.size() * 8));

        // We place deck cells using local segment direction to keep geometry stable.
        for (int i = 0; i < span.centerline.size(); i++) {
            BlockPos c = span.centerline.get(i);

            Dir2 dir = localDir(span.centerline, i);
            Dir2 perp = dir.perp();

            SlabPlacement sp = slabPlacementForSurfaceHalfY(deckHalfY[i]);
            IBlockState deckSlab = sp.top ? slabTop() : slabBottom();

            // Place main stripe
            placeStripe(world, c.getX(), c.getZ(), perp, half, sp, deckSlab, dryRun, sampler, deckXZ);

            // If diagonal step, fill missing corner cells WITHOUT widening:
            // We fill only the cells that lie between previous and current stripe for each offset.
            if (i > 0) {
                BlockPos p = span.centerline.get(i - 1);
                int stepX = c.getX() - p.getX();
                int stepZ = c.getZ() - p.getZ();

                if (stepX != 0 && stepZ != 0) {
                    fillDiagonalGap(world, p.getX(), p.getZ(), c.getX(), c.getZ(), perp, half, sp, deckSlab, dryRun, sampler, deckXZ);
                }
            }

            if (!span.micro && (i % supportSpacing == 0)) {
                BlockPos s1 = supportPosAdjacentToDeckEdge(c.getX(), c.getZ(), perp, half, true, sp.blockY);
                BlockPos s2 = supportPosAdjacentToDeckEdge(c.getX(), c.getZ(), perp, half, false, sp.blockY);

// Ensure supports never intersect any deck cell (including corner fills / neighbor stripes)
                s1 = pushSupportOutsideDeck(deckXZ, s1, perp, true);
                s2 = pushSupportOutsideDeck(deckXZ, s2, perp, false);

                placeSupportColumn(world, s1.getX(), sp.blockY, s1.getZ(), maxSupportDepth, dryRun, sp.top);
                placeSupportColumn(world, s2.getX(), sp.blockY, s2.getZ(), maxSupportDepth, dryRun, sp.top);
            }
        }

        return true;
    }

    private static void placeStripe(WorldServer world, int cx, int cz, Dir2 perp, int half, SlabPlacement sp,
                                    IBlockState slab, boolean dryRun, TerrainSampler sampler, LongOpenHashSet deckXZ) {
        for (int o = -half; o <= half; o++) {
            int x = cx + perp.dx * o;
            int z = cz + perp.dz * o;
            placeDeckCell(world, x, z, sp, slab, dryRun, sampler, deckXZ);
        }
    }

    private static void fillDiagonalGap(WorldServer world, int px, int pz, int cx, int cz, Dir2 perp, int half, SlabPlacement sp,
                                        IBlockState slab, boolean dryRun, TerrainSampler sampler, LongOpenHashSet deckXZ) {
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
                placeDeckCell(world, c1x, c1z, sp, slab, dryRun, sampler, deckXZ);
            }

            int c2x = ax;
            int c2z = az + stepZ;
            if (isBetween(c2x, c2z, ax, az, bx, bz)) {
                placeDeckCell(world, c2x, c2z, sp, slab, dryRun, sampler, deckXZ);
            }
        }
    }

    // -------------------------
    // Local direction
    // -------------------------

    private static Dir2 localDir(java.util.List<BlockPos> line, int idx) {
        if (line.size() <= 1) return new Dir2(1, 0);

        BlockPos prev = line.get(Math.max(0, idx - 1));
        BlockPos next = line.get(Math.min(line.size() - 1, idx + 1));

        int dx = Integer.signum(next.getX() - prev.getX());
        int dz = Integer.signum(next.getZ() - prev.getZ());

        if (dx == 0 && dz == 0) {
            // fallback: use immediate neighbor
            BlockPos cur = line.get(idx);
            if (idx > 0) {
                BlockPos p = line.get(idx - 1);
                dx = Integer.signum(cur.getX() - p.getX());
                dz = Integer.signum(cur.getZ() - p.getZ());
            } else if (idx + 1 < line.size()) {
                BlockPos n = line.get(idx + 1);
                dx = Integer.signum(n.getX() - cur.getX());
                dz = Integer.signum(n.getZ() - cur.getZ());
            }
        }

        if (dx == 0 && dz == 0) dx = 1;
        return new Dir2(dx, dz);
    }

    private static final class Dir2 {
        final int dx, dz;
        Dir2(int dx, int dz) { this.dx = dx; this.dz = dz; }
        Dir2 perp() { return new Dir2(-dz, dx); }
    }

    // -------------------------
    // Deck placement
    // -------------------------


    private static boolean isBetween(int x, int z, int x0, int z0, int x1, int z1) {
        int minX = Math.min(x0, x1);
        int maxX = Math.max(x0, x1);
        int minZ = Math.min(z0, z1);
        int maxZ = Math.max(z0, z1);
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    private static void placeDeckCell(WorldServer world, int x, int z, SlabPlacement sp, IBlockState slab,
                                      boolean dryRun, TerrainSampler sampler, LongOpenHashSet deckXZ) {
        long k = xzKey(x, z);
        deckXZ.add(k);

        if (dryRun) return;

        BlockPos deckPos = new BlockPos(x, sp.blockY, z);

        // If there's a tile entity exactly where we want the deck, abort placing here.
        // (Avoid breaking chests/TEs; keeps bridge from griefing villages.)
        if (world.getTileEntity(deckPos) != null) return;

        // Place slab
        world.setBlockState(deckPos, slab, 2);

        // Clear 3 blocks above the deck surface:
        // - for bottom slab, surface is y+0.5; clearing y+1..y+3 is correct
        // - for top slab, surface is y+1.0; clearing y+1..y+3 is also correct
        clearBridgeWalkVolume(world, x, z, sp.blockY + 1, sp.blockY + 3);
    }

    private static void clearBridgeWalkVolume(WorldServer world, int x, int z, int yFrom, int yTo) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int y = yFrom; y <= yTo; y++) {
            p.setPos(x, y, z);

            IBlockState st = world.getBlockState(p);
            if (st == null) continue;

            Material m = st.getMaterial();
            if (m == Material.AIR) continue;

            // Never destroy tiles
            if (world.getTileEntity(p) != null) continue;

            // Avoid unintentional water flood: if this block is adjacent to water, only soft-clear.
            boolean nearWater = isAdjacentToWater(world, p);

            // Always clear soft stuff
            if (m == Material.PLANTS || m == Material.VINE || m.isReplaceable() || st.getBlock() == Blocks.SNOW_LAYER) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                continue;
            }
            if (st.getBlock().isLeaves(st, world, p) || m == Material.LEAVES) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                continue;
            }

            // Clear tree trunks when they look like trees
            if (MPBConfig.PAINTING.clearTrees && MPBConfig.PAINTING.clearTreeTrunks) {
                if (looksLikeTreeWood(world, p, st)) {
                    world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                    continue;
                }
            }

            // Solid natural blocks: carve them out for walkability (unless we'd cause water issues)
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
        // Conservative: only carve common natural solids (dirt/stone/sand/gravel/clay etc).
        // Avoid cutting wood structures etc.
        Material m = st.getMaterial();
        if (!m.isSolid()) return false;
        if (m == Material.WOOD || m == Material.IRON || m == Material.GLASS) return false;
        if (m == Material.LEAVES) return false;
        if (st.getBlock() == Blocks.BEDROCK || st.getBlock() == Blocks.BARRIER) return false;
        return MPBGround.isRoadReplaceableGround(st) || m == Material.ROCK || m == Material.GROUND || m == Material.GRASS || m == Material.SAND || m == Material.CLAY;
    }

    private static long xzKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static void clearBridgeColumn(WorldServer world, BlockPos start, int up, TerrainSampler sampler) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(start);

        for (int dy = 0; dy < up; dy++) {
            p.setPos(start.getX(), start.getY() + dy, start.getZ());
            IBlockState st = world.getBlockState(p);
            if (st == null) continue;

            Material m = st.getMaterial();
            if (m == Material.AIR) continue;

            // Do not destroy tiles/manmade blocks
            if (world.getTileEntity(p) != null) continue;

            // Soft clear
            if (m == Material.PLANTS || m == Material.VINE || m.isReplaceable() || st.getBlock() == Blocks.SNOW_LAYER) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                continue;
            }
            if (st.getBlock().isLeaves(st, world, p) || m == Material.LEAVES) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                continue;
            }

            // Tree trunk clear (only when it looks like a tree)
            if (MPBConfig.PAINTING.clearTrees && MPBConfig.PAINTING.clearTreeTrunks) {
                if (looksLikeTreeWood(world, p, st)) {
                    world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                }
            }
        }
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

    // -------------------------
    // Height profile (0.5 steps)
    // -------------------------

    private static int[] buildDeckHalfProfile(BridgeSpan span) {
        int n = span.centerline.size();
        int[] ideal = new int[n];

        int a = span.startDeckSurfaceHalfY;
        int b = span.endDeckSurfaceHalfY;

        for (int i = 0; i < n; i++) {
            double t = (n <= 1) ? 0.0 : ((double) i / (double) (n - 1));
            ideal[i] = (int) Math.round((1.0 - t) * (double) a + t * (double) b);
        }

        int maxStepHalf = Math.max(0, MPBConfig.BRIDGES.maxDeckDeltaHalfPerBlock);
        if (maxStepHalf <= 0) {
            int flat = Math.max(a, b);
            int[] out = new int[n];
            for (int i = 0; i < n; i++) out[i] = flat;
            return out;
        }

        int[] out = new int[n];
        out[0] = ideal[0];

        for (int i = 1; i < n; i++) {
            int want = ideal[i];
            int prev = out[i - 1];
            int d = want - prev;
            if (d > maxStepHalf) want = prev + maxStepHalf;
            else if (d < -maxStepHalf) want = prev - maxStepHalf;
            out[i] = want;
        }

        for (int i = n - 2; i >= 0; i--) {
            int want = out[i];
            int next = out[i + 1];
            int d = want - next;
            if (d > maxStepHalf) want = next + maxStepHalf;
            else if (d < -maxStepHalf) want = next - maxStepHalf;
            out[i] = want;
        }

        return out;
    }

    private static final class SlabPlacement {
        final int blockY;
        final boolean top;
        SlabPlacement(int blockY, boolean top) { this.blockY = blockY; this.top = top; }
    }

    private static SlabPlacement slabPlacementForSurfaceHalfY(int surfaceHalfY) {
        if ((surfaceHalfY & 1) == 1) {
            int y = (surfaceHalfY - 1) / 2;
            return new SlabPlacement(y, false);
        } else {
            int y = (surfaceHalfY / 2) - 1;
            return new SlabPlacement(y, true);
        }
    }

    // -------------------------
    // Supports
    // -------------------------

    private static BlockPos supportPosAdjacentToDeckEdge(int cx, int cz, Dir2 perp, int half, boolean positiveSide, int y) {
        int side = positiveSide ? 1 : -1;

        int ex = cx + perp.dx * (side * half);
        int ez = cz + perp.dz * (side * half);

        int sx = sign(perp.dx * side);
        int sz = sign(perp.dz * side);

        int cand1x = ex + sx, cand1z = ez;
        int cand2x = ex,      cand2z = ez + sz;

        boolean diagonal = Math.abs(perp.dx) == Math.abs(perp.dz);

        int rx, rz;
        if (!diagonal) {
            if (Math.abs(perp.dx) > Math.abs(perp.dz)) { rx = cand1x; rz = cand1z; }
            else { rx = cand2x; rz = cand2z; }
        } else {
            // Stable tie-break => symmetry
            if (positiveSide) { rx = cand2x; rz = cand2z; }
            else { rx = cand1x; rz = cand1z; }
        }

        return new BlockPos(rx, y, rz);
    }

    private static int sign(int v) {
        return v < 0 ? -1 : (v > 0 ? 1 : 0);
    }
    private static void placeSupportColumn(WorldServer world, int x, int yDeckBlock, int z, int maxDepth, boolean dryRun, boolean deckIsTopSlab) {
        if (dryRun) return;

        // Find ground just below deck (or stop at first solid)
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

        // Now build the vertical pillar up through the deck height region and above it.
        int capTopY = yDeckBlock + (deckIsTopSlab ? 2 : 1);

        for (int yy = Math.max(2, y + 1); yy <= capTopY; yy++) {
            p.setPos(x, yy, z);

            // Don't overwrite the deck itself if it accidentally intersects (shouldn't happen now),
            // but still ensure the pillar is continuous otherwise.
            IBlockState st = world.getBlockState(p);
            if (st != null && (st.getBlock() == Blocks.WOODEN_SLAB)) {
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
    private static boolean isInDeckStripe(int x, int z, int cx, int cz, Dir2 perp, int half) {
        for (int o = -half; o <= half; o++) {
            int dx = perp.dx * o;
            int dz = perp.dz * o;
            if (x == cx + dx && z == cz + dz) return true;
        }
        return false;
    }

    private static BlockPos pushOutsideDeckStripe(BlockPos support, int cx, int cz, Dir2 perp, int half) {
        int x = support.getX();
        int z = support.getZ();

        if (!isInDeckStripe(x, z, cx, cz, perp, half)) return support;

        // Move one step further outward from deck edge (cardinal preference)
        int sx = sign(perp.dx);
        int sz = sign(perp.dz);

        // If diagonal perp, try both options and pick the one that exits the stripe
        if (Math.abs(perp.dx) == Math.abs(perp.dz)) {
            BlockPos a = new BlockPos(x + sx, support.getY(), z);
            if (!isInDeckStripe(a.getX(), a.getZ(), cx, cz, perp, half)) return a;
            BlockPos b = new BlockPos(x, support.getY(), z + sz);
            if (!isInDeckStripe(b.getX(), b.getZ(), cx, cz, perp, half)) return b;
            // fallback: two steps
            return new BlockPos(x + sx, support.getY(), z + sz);
        }

        // Non-diagonal: step along dominant perp axis
        if (Math.abs(perp.dx) > Math.abs(perp.dz)) return new BlockPos(x + sx, support.getY(), z);
        return new BlockPos(x, support.getY(), z + sz);
    }
    private static BlockPos pushSupportOutsideDeck(LongOpenHashSet deckXZ, BlockPos pos, Dir2 perp, boolean positiveSide) {
        int x = pos.getX();
        int z = pos.getZ();

        int side = positiveSide ? 1 : -1;

        // Outward step direction (cardinal preference)
        int sx = sign(perp.dx * side);
        int sz = sign(perp.dz * side);

        // If perp is diagonal, choose a stable cardinal axis per side
        boolean diagonal = Math.abs(perp.dx) == Math.abs(perp.dz);
        int stepX = 0, stepZ = 0;
        if (!diagonal) {
            if (Math.abs(perp.dx) > Math.abs(perp.dz)) { stepX = sx; stepZ = 0; }
            else { stepX = 0; stepZ = sz; }
        } else {
            // same tie-break as earlier => symmetry
            if (positiveSide) { stepX = 0; stepZ = sz; }
            else { stepX = sx; stepZ = 0; }
        }

        // Push at most a few steps to guarantee we exit deck footprint
        for (int t = 0; t < 5; t++) {
            if (!deckXZ.contains(xzKey(x, z))) break;
            x += stepX;
            z += stepZ;
        }

        return new BlockPos(x, pos.getY(), z);
    }


}
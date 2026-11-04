package com.jubitus.ultreia.terrain;

import com.jubitus.ultreia.mill.MillPathBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/** Built structures next to a terrain cell: tile entities, man-made blocks, building-like columns. */
final class BuildingGuard {

    private BuildingGuard() {}

    /** A tile entity (chest, furnace…) in the 3×3 block around the cell, up to 2 above; DynamicTrees ones excepted. */
    static boolean hasTileEntityNearby(WorldServer world, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos p = new BlockPos(x + dx, y + dy, z + dz);
                    if (world.getTileEntity(p) == null) continue;
                    // DT blocks (saplings, rootydirt) carry TEs for species metadata only —
                    // they are safe to clear and must not trigger path/paint blocking.
                    if (TreeCompat.isDynamicTreesBlock(world.getBlockState(p))) continue;
                    return true;
                }
            }
        }
        return false;
    }

    /** At least 3 man-made solid blocks at ground level or just above, in the 3×3 around the cell. */
    static boolean hasManmadeNearby(WorldServer world, int x, int y, int z) {
        int count = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                p.setPos(x + dx, y, z + dz);
                if (isManmadeSolid(world, world.getBlockState(p), p)) count++;
                p.setPos(x + dx, y + 1, z + dz);
                if (isManmadeSolid(world, world.getBlockState(p), p)) count++;
            }
        }
        return count >= 3;
    }

    private static boolean isManmadeSolid(WorldServer world, IBlockState st, BlockPos pos) {
        if (!st.getMaterial().isSolid()) return false;
        Block b = st.getBlock();
        if (b.isWood(world, pos)) return false;
        if (b.isLeaves(st, world, pos)) return false;
        // Mill/Ultreia path blocks are road infrastructure, not obstacles to paint around.
        if (MillPathBlocks.isMillPath(b)) return false;
        return !BlockRules.isRoadReplaceableGround(st);
    }

    /** A tile entity, or at least 3 built blocks, within radius r and from 1 below to 4 above the ground. */
    static boolean looksLikeBuildingColumn(WorldServer world, int x, int surfaceY, int z, int r) {
        int hits = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= 4; dy++) {
                    int yy = surfaceY + dy;
                    if (yy < 1 || yy > 255) continue;
                    p.setPos(x + dx, yy, z + dz);

                    if (!world.isBlockLoaded(p)) continue;

                    if (world.getTileEntity(p) != null) return true;

                    IBlockState st = world.getBlockState(p);
                    Material m = st.getMaterial();
                    if (!m.isSolid()) continue;
                    if (m == Material.LEAVES) continue;
                    if (m == Material.PLANTS || m == Material.VINE) continue;
                    if (m == Material.WATER) continue;

                    if (BlockRules.isRoadReplaceableGround(st)) continue;

                    // Ignore tree trunks (we can clear them); planks and fences count as building.
                    if (BlockRules.treesClearable() && BlockRules.isTreeWood(world, p, st)) continue;

                    hits++;
                    if (hits >= 3) return true;
                }
            }
        }
        return false;
    }
}

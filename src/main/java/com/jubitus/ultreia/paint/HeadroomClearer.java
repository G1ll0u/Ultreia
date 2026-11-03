package com.jubitus.ultreia.paint;

import com.jubitus.ultreia.terrain.BlockRules;
import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.mill.MillPathPalette;
import com.jubitus.ultreia.terrain.TreeCompat;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class HeadroomClearer {

    // When a DynamicTrees trunk with radius >= dtWholeTreeRemovalThreshold is encountered while clearing the...
    private static final boolean DT_REMOVE_WHOLE_TREE = true;
    // DynamicTrees branch radius at or above which the entire tree is removed when it intersects the road...
    private static final int DT_WHOLE_TREE_REMOVAL_THRESHOLD = 1;

    private HeadroomClearer() {}

    public static void clearRoadColumn(World world, BlockPos base, int up, boolean dryRun, boolean allowSolidNaturalCut) {
        if (dryRun) return;

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(base);

        for (int dy = 1; dy <= up; dy++) {
            p.setPos(base.getX(), base.getY() + dy, base.getZ());

            IBlockState st = world.getBlockState(p);
            if (st.getMaterial() == Material.AIR) continue;

            // DT saplings store species in a TileEntity but are safe to remove as vegetation.
            if (hasTile(world, p) && !TreeCompat.isDynamicTreesBlock(st)) continue;
            if (isAlwaysKeep(st)) continue;

            if (BlockRules.wouldCauseWaterFlow(world, p)) continue;

            if (BlockRules.isSoftClearable(world, p, st)) {
                clearWithPairIfNeeded(world, p, st);
                continue;
            }

            if (allowSolidNaturalCut && BlockRules.isNaturalSolid(st)) {
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

            // DT saplings store species in a TileEntity but are safe to remove as vegetation.
            if (hasTile(world, p) && !TreeCompat.isDynamicTreesBlock(st)) continue;
            if (isAlwaysKeep(st)) continue;

            if (BlockRules.wouldCauseWaterFlow(world, p)) continue;

            if (BlockRules.isSoftClearable(world, p, st)) {
                clearWithPairIfNeeded(world, p, st);
                continue;
            }

            if (allowSolidNaturalCut && BlockRules.isNaturalSolid(st)) {
                setAir(world, p);
            }
        }
    }

    /**
     * Scans upward from {@code base.up(startDy)} and removes tree blocks that block the road corridor.
     *
     * Behaviour:
     * - DT branch radius >= dtWholeTreeRemovalThreshold: removes the ENTIRE tree via BFS (rootydirt
     *   converted to dirt, branches removed, leaves decay naturally).
     * - DT branch radius < threshold or DT foliage: calls destroyBlock so DT's own physics can
     *   collapse orphaned sub-branches. The rest of the tree stays standing.
     * - Vanilla/non-DT wood: setBlockState to AIR.
     * - Stops at y=255, after 64 blocks, or at the first non-tree block.
     *
     * @return the rootydirt position (now plain dirt) if a whole DT tree was removed,
     *         so the caller can paint a road block there. Null otherwise.
     */
    @javax.annotation.Nullable
    public static BlockPos clearTreesAbove(World world, BlockPos base, int startDy, boolean dryRun) {
        if (dryRun) return null;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = startDy; dy <= startDy + 64; dy++) {
            int y = base.getY() + dy;
            if (y > 255) break;
            p.setPos(base.getX(), y, base.getZ());
            IBlockState st = world.getBlockState(p);
            if (st.getMaterial() == Material.AIR) break;
            boolean isWood = BlockRules.isTreeWood(world, p, st);
            boolean isFoliage = !isWood && BlockRules.isFoliage(world, p, st);
            if (!isWood && !isFoliage) break;
            if (hasTile(world, p)) continue;
            if (isAlwaysKeep(st)) break;
            if (BlockRules.wouldCauseWaterFlow(world, p)) continue;

            if (isWood && TreeCompat.isDynamicTreesBranch(st)) {
                int radius = TreeCompat.getDTBranchRadius(st);
                int threshold = DT_WHOLE_TREE_REMOVAL_THRESHOLD;
                if (DT_REMOVE_WHOLE_TREE && radius >= threshold) {
                    if (UltreiaConfig.DEBUG.logPainting) {
                        Ultreia.LOGGER.info(
                                "[Ultreia] DT whole-tree removal at ({},{},{}) radius={}",
                                p.getX(), p.getY(), p.getZ(), radius);
                    }
                    // Returns the rootydirt pos (now plain dirt) so the caller can paint road over it.
                    return TreeCompat.removeDTTreeCompletely(world, p.toImmutable());
                } else {
                    // Small branch → destroyBlock triggers DT cascade on orphaned sub-branches.
                    world.destroyBlock(p.toImmutable(), false);
                }
            } else if (isWood && TreeCompat.isDynamicTreesTrunkShell(st)) {
                if (DT_REMOVE_WHOLE_TREE) {
                    BlockPos seed = findAdjacentDTBranch(world, p.toImmutable());
                    return TreeCompat.removeDTTreeCompletely(world, seed);
                }
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 3);
            } else {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 3);
            }
        }
        return null;
    }

    /**
     * Finds a DT branch block adjacent to the given trunk-shell position.
     * Returns the shell position itself as fallback (BFS gracefully exits if no branch is adjacent).
     */
    private static BlockPos findAdjacentDTBranch(World world, BlockPos shellPos) {
        for (EnumFacing face : EnumFacing.values()) {
            BlockPos adj = shellPos.offset(face);
            IBlockState st = world.getBlockState(adj);
            if (TreeCompat.isDynamicTreesBranch(st)) return adj;
        }
        return shellPos;
    }

    private static void clearWithPairIfNeeded(World world, BlockPos pos, IBlockState st) {
        Block b = st.getBlock();

        if (b instanceof BlockDoublePlant) {
            BlockDoublePlant dp = (BlockDoublePlant) b;

            try {
                BlockDoublePlant.EnumBlockHalf half = st.getValue(BlockDoublePlant.HALF);
                if (half == BlockDoublePlant.EnumBlockHalf.UPPER) {
                    if (!BlockRules.wouldCauseWaterFlow(world, pos) && !BlockRules.wouldCauseWaterFlow(world, pos.down())) {
                        setAir(world, pos);
                        setAir(world, pos.down());
                    }
                    return;
                } else {
                    if (!BlockRules.wouldCauseWaterFlow(world, pos) && !BlockRules.wouldCauseWaterFlow(world, pos.up())) {
                        setAir(world, pos);
                        setAir(world, pos.up());
                    }
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        if (BlockRules.wouldCauseWaterFlow(world, pos)) return;
        setAir(world, pos);
    }

    private static void setAir(World world, BlockPos pos) {
        if (BlockRules.wouldCauseWaterFlow(world, pos)) return;
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
}
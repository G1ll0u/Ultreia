package com.jubitus.ultreia.terrain;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/** Tree trunks standing on or next to a terrain cell, measured as a trunk radius. */
public final class TreeProbe {

    /** Trunk radius reported for a vanilla or unknown tree. */
    public static final int VANILLA_TRUNK = 9;
    // DynamicTrees branch radius (1-8) from which a tree is considered 'large'.
    public static final int DT_LARGE_TREE_RADIUS_THRESHOLD = 3;
    // Horizontal scan radius (blocks) used to detect large DynamicTrees trunks near a path cell.
    private static final int DT_TREE_PROXIMITY_CHECK_RADIUS = 2;

    private TreeProbe() {}

    /**
     * Effective trunk radius at (x, surfaceY, z): a trunk 1–4 above the cell, a DT tree rooted in the cell, or a large
     * DT trunk within {@link #DT_TREE_PROXIMITY_CHECK_RADIUS} (so the cost covers the whole road width).
     * Returns 0 if none, 1–8 for a DT radius, {@link #VANILLA_TRUNK} for a vanilla/unknown trunk.
     */
    static int detectTrunkRadius(WorldServer world, int x, int surfaceY, int z) {
        BlockPos.MutableBlockPos tp = new BlockPos.MutableBlockPos();
        int best = 0;

        for (int dy = 1; dy <= 4 && best == 0; dy++) {
            int yy = surfaceY + dy;
            if (yy > 255) break;
            tp.setPos(x, yy, z);
            IBlockState tst = world.getBlockState(tp);
            int r = probeBlockTrunkRadius(world, tst, tp);
            // DT saplings (Tier-1) are not branches — treat as radius-1 obstacle so A* prefers routing around them.
            if (r == 0 && TreeCompat.isDTSapling(tst)) r = 1;
            if (r > best) best = r;
        }

        if (best == 0) {
            tp.setPos(x, surfaceY, z);
            if (TreeCompat.isDTRootyBlock(world.getBlockState(tp))) {
                // Trunk starts one block above the rootydirt
                tp.setPos(x, surfaceY + 1, z);
                int r = probeBlockTrunkRadius(world, world.getBlockState(tp), tp);
                best = (r > 0) ? r : 1;
            }
        }

        int proximityR = DT_TREE_PROXIMITY_CHECK_RADIUS;
        outer:
        for (int dx = -proximityR; dx <= proximityR; dx++) {
            for (int dz = -proximityR; dz <= proximityR; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (int dy = 1; dy <= 6; dy++) {
                    int yy = surfaceY + dy;
                    if (yy > 255) break;
                    tp.setPos(x + dx, yy, z + dz);
                    IBlockState tst = world.getBlockState(tp);
                    if (tst.getMaterial() == Material.AIR) continue;
                    if (TreeCompat.isDynamicTreesBranch(tst)) {
                        int r = TreeCompat.getDTBranchRadius(tst);
                        if (r >= DT_LARGE_TREE_RADIUS_THRESHOLD && r > best) {
                            best = r;
                            break outer;
                        }
                    }
                }
            }
        }

        return best;
    }

    /** Trunk radius of one block: 1–8 for DT branches, 4 for a DT trunk shell, {@link #VANILLA_TRUNK} for a log, 0 otherwise. */
    private static int probeBlockTrunkRadius(WorldServer world, IBlockState st, BlockPos pos) {
        if (TreeCompat.isDynamicTreesBranch(st)) {
            int r = TreeCompat.getDTBranchRadius(st);
            return (r > 0) ? r : 1;
        }
        if (TreeCompat.isDynamicTreesTrunkShell(st)) return 4;
        if (BlockRules.isTreeWood(world, pos, st)) return VANILLA_TRUNK;
        return 0;
    }
}

package com.jubitus.ultreia.terrain;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Where the ground is in a column: top-down scan that sees through air, plants, snow, foliage and clearable tree
 * trunks (rules in {@link BlockRules}), and stops on water, ice, lava or any other block.
 * The result is packed in an int: {@link #y} and {@link #isWater}.
 */
public final class ColumnSurface {

    /** Steps for a full-column scan. */
    public static final int FULL_SCAN = 256;

    private static final int Y_MASK = 0x1FF;
    private static final int WATER_BIT = 1 << 9;

    private ColumnSurface() {}

    /** Highest non-air block of the column (heightmap), at least 1. The chunk must be loaded. */
    public static int topY(World world, BlockPos.MutableBlockPos pos, int x, int z) {
        int y = world.getHeight(pos.setPos(x, 0, z)).getY() - 1;
        return Math.max(1, Math.min(255, y));
    }

    /** Full scan from the heightmap top. The chunk must be loaded. */
    public static int scan(World world, BlockPos.MutableBlockPos pos, int x, int z) {
        return scan(world, pos, x, z, topY(world, pos, x, z), FULL_SCAN);
    }

    /** Scans down from startY for at most maxSteps blocks; stops above Y=1. */
    public static int scan(World world, BlockPos.MutableBlockPos pos, int x, int z, int startY, int maxSteps) {
        boolean water = false;
        int y = startY;

        for (int i = 0; i < maxSteps && y > 1; i++, y--) {
            pos.setPos(x, y, z);
            IBlockState st = world.getBlockState(pos);
            if (st.getMaterial() == Material.AIR) continue;

            if (BlockRules.isWaterLike(st)) {
                water = true;
                break;
            }

            if (BlockRules.isWaterSurfaceDecoration(st)) {
                if (BlockRules.isWaterLike(world.getBlockState(pos.setPos(x, y - 1, z)))) {
                    water = true;
                    y--;
                    break;
                }
                continue;
            }

            if (BlockRules.isSoftClearable(world, pos, st)) continue;

            break;
        }

        if (y < 1) y = 1;
        return (y & Y_MASK) | (water ? WATER_BIT : 0);
    }

    public static int y(int packed) {
        return packed & Y_MASK;
    }

    public static boolean isWater(int packed) {
        return (packed & WATER_BIT) != 0;
    }
}

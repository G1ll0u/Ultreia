package com.jubitus.ultreia.terrain;

import com.jubitus.ultreia.block.UltreiaBlocks;
import com.jubitus.ultreia.mill.MillPathPalette;
import net.minecraft.block.Block;
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

    public static final int NO_PATH = Integer.MIN_VALUE;
    // Path blocks searched from this many blocks below to this many above the ground (or road block).
    private static final int PATH_BELOW = 3;
    private static final int PATH_ABOVE = 4;

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

    /**
     * Y of the highest path block within aroundY-3 .. aroundY+4 (aroundY = ground or road block), or {@link #NO_PATH}.
     * ultreiaOnly: only Ultreïa's own path blocks, not Millénaire's.
     */
    public static int findPathY(World world, BlockPos.MutableBlockPos pos, int x, int z, int aroundY, boolean ultreiaOnly) {
        int yMin = Math.max(1, aroundY - PATH_BELOW);
        int yMax = Math.min(255, aroundY + PATH_ABOVE);
        for (int y = yMax; y >= yMin; y--) {
            Block b = world.getBlockState(pos.setPos(x, y, z)).getBlock();
            boolean path = ultreiaOnly
                    ? b == UltreiaBlocks.PATH_BLOCK || b == UltreiaBlocks.PATH_SLAB
                    : MillPathPalette.isMillPath(b);
            if (path) return y;
        }
        return NO_PATH;
    }

    public static int y(int packed) {
        return packed & Y_MASK;
    }

    public static boolean isWater(int packed) {
        return (packed & WATER_BIT) != 0;
    }
}

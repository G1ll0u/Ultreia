package com.jubitus.ultreia.terrain;

import com.jubitus.ultreia.chunk.ChunkManager;
import com.jubitus.ultreia.config.UltreiaConfig;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;

/** Water around a terrain cell: small patches (puddles, marsh) and water next to a land cell. */
public final class WaterProbe {

    // Manhattan radius from any water/ice that roads must avoid when on land.
    public static final int NEAR_WATER_RADIUS = 1;
    // Radius (blocks) used to classify a water cell as 'small water patch'.
    private static final int SMALL_WATER_SCAN_RADIUS = 4;
    // If the number of nearby water cells in scan radius is <= this, it's considered 'small water'.
    private static final int SMALL_WATER_MAX_CELLS = 22;
    // Blocks scanned down a neighbouring column when looking for nearby water.
    private static final int NEAR_WATER_SCAN_DEPTH = 16;

    private WaterProbe() {}

    static boolean isSmallWaterPatch(WorldServer world, int x, int surfaceY, int z) {
        int r = SMALL_WATER_SCAN_RADIUS;
        int water = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                p.setPos(x + dx, surfaceY, z + dz);

                if (!world.isBlockLoaded(p)) continue;
                if (BlockRules.isWaterLike(world.getBlockState(p))) {
                    water++;
                    if (water > SMALL_WATER_MAX_CELLS) return false;
                }
            }
        }
        return water > 0;
    }

    /** Water within {@link #NEAR_WATER_RADIUS} (Manhattan) of a land cell; an unreadable neighbour counts as water. */
    static boolean isNearWater(WorldServer world, @Nullable ChunkManager chunkManager, int x, int z, int yGuess) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int d = 1; d <= NEAR_WATER_RADIUS; d++) {
            for (int dx = -d; dx <= d; dx++) {
                int dz = d - Math.abs(dx);

                if (dz == 0) {
                    if (hasWaterNearColumn(world, chunkManager, x + dx, z, yGuess, p)) return true;
                } else {
                    if (hasWaterNearColumn(world, chunkManager, x + dx, z + dz, yGuess, p)) return true;
                    if (hasWaterNearColumn(world, chunkManager, x + dx, z - dz, yGuess, p)) return true;
                }
            }
        }

        return false;
    }

    private static boolean hasWaterNearColumn(WorldServer world, @Nullable ChunkManager chunkManager,
                                              int x, int z, int yGuess, BlockPos.MutableBlockPos p) {
        p.setPos(x, 0, z);

        if (!world.isBlockLoaded(p)) {
            if (UltreiaConfig.GENERAL.allowChunkLoading && chunkManager != null) {
                if (!chunkManager.ensureLoaded(world, x, z)) return true;
            } else {
                return true;
            }
        }

        int start = Math.min(255, Math.max(yGuess + 2, ColumnSurface.topY(world, p, x, z)));
        if (ColumnSurface.isWater(ColumnSurface.scan(world, p, x, z, start, NEAR_WATER_SCAN_DEPTH))) return true;

        for (int dy = -2; dy <= 2; dy++) {
            int yy = yGuess + dy;
            if (yy < 1 || yy > 255) continue;
            p.setPos(x, yy, z);

            IBlockState st = world.getBlockState(p);
            if (BlockRules.isWaterLike(st)) return true;

            if (BlockRules.isWaterSurfaceDecoration(st)) {
                if (BlockRules.isWaterLike(world.getBlockState(p.down()))) return true;
            }
        }

        return false;
    }
}

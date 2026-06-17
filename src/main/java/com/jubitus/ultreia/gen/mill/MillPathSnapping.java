package com.jubitus.ultreia.gen.mill;

import com.jubitus.ultreia.gen.TerrainSampler;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

public final class MillPathSnapping {
    private MillPathSnapping() {}

    public static BlockPos findFirstMillPathAround(WorldServer world, TerrainSampler sampler, BlockPos center, int radius) {
        int cx = center.getX();
        int cz = center.getZ();

        // ring scan: r=0..radius, top/bottom edges then sides
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int r = 0; r <= radius; r++) {
            int minX = cx - r;
            int maxX = cx + r;
            int minZ = cz - r;
            int maxZ = cz + r;

            for (int x = minX; x <= maxX; x++) {
                BlockPos a = tryMillPathColumn(world, sampler, p, x, minZ);
                if (a != null) return a;
                if (maxZ != minZ) {
                    BlockPos b = tryMillPathColumn(world, sampler, p, x, maxZ);
                    if (b != null) return b;
                }
            }
            for (int z = minZ + 1; z <= maxZ - 1; z++) {
                BlockPos a = tryMillPathColumn(world, sampler, p, minX, z);
                if (a != null) return a;
                if (maxX != minX) {
                    BlockPos b = tryMillPathColumn(world, sampler, p, maxX, z);
                    if (b != null) return b;
                }
            }
        }
        return null;
    }

    private static BlockPos tryMillPathColumn(WorldServer world, TerrainSampler sampler, BlockPos.MutableBlockPos p, int x, int z) {
        TerrainSampler.Sample sm = sampler.sample(x, z);
        if (sm == null) return null;
        int y = sm.surfaceY;

        for (int dy = -2; dy <= 4; dy++) {
            int yy = y + dy;
            if (yy < 1 || yy > 255) continue;
            p.setPos(x, yy, z);
            IBlockState st = world.getBlockState(p);
            if (st != null && MPBMillPathPalette.isMillPath(st.getBlock())) {
                return new BlockPos(x, sm.surfaceY, z);
            }
        }
        return null;
    }
}
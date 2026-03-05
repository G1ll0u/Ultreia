package com.jubitus.millpathbuilder.gen.mill;

import com.jubitus.millpathbuilder.gen.TerrainSampler;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;

public final class MillVillageConnections {

    private MillVillageConnections() {}

    @Nullable
    public static BlockPos findGateEntrancePathAnchor(
            WorldServer world,
            TerrainSampler sampler,
            BlockPos gateChestPos,
            BlockPos approachFrom,
            int scanR
    ) {
        int gx = gateChestPos.getX();
        int gz = gateChestPos.getZ();

        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int dz = -scanR; dz <= scanR; dz++) {
            for (int dx = -scanR; dx <= scanR; dx++) {
                int x = gx + dx;
                int z = gz + dz;

                TerrainSampler.Sample sm = sampler.sample(x, z);
                int y = sm.surfaceY;

                boolean isPath = false;
                for (int dy = -2; dy <= 3; dy++) {
                    int yy = y + dy;
                    if (yy < 1 || yy > 255) continue;
                    p.setPos(x, yy, z);
                    IBlockState st = world.getBlockState(p);
                    if (st != null && MPBMillPathPalette.isMillPath(st.getBlock())) {
                        isPath = true;
                        break;
                    }
                }
                if (!isPath) continue;

                // Prefer points closer to approachFrom
                double d2 = approachFrom.distanceSq(new BlockPos(x, approachFrom.getY(), z));

                // Slight bias toward being "outside" the building: avoid hard zones if possible
                double bias = 0.0;
                // (We can't access protectedAreas here cleanly; keep simple)

                double score = d2 + bias;
                if (score < bestScore) {
                    bestScore = score;
                    best = new BlockPos(x, sm.surfaceY, z);
                }
            }
        }

        return best;
    }
}
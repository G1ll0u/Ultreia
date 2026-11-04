package com.jubitus.ultreia.paint;

import com.jubitus.ultreia.mill.MillPathBlocks;
import com.jubitus.ultreia.roads.RoadIndex;
import com.jubitus.ultreia.terrain.TerrainSampler;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;

/** Which kind of path block a road cell gets, from the ground around it, and what supports a slab. */
public final class PathPalette {

    public enum Kind { DIRT, GRAVEL, SANDSTONE, SLABS, OCHRE, SNOW }

    // Radius of the ground sampled around a cell to vote its kind.
    private static final int KIND_VOTE_RADIUS = 2;
    // Vote weight of the kind of the existing road next to the cell (keeps a joined road consistent).
    private static final int EXISTING_ROAD_KIND_WEIGHT = 10;
    private static final int EXISTING_ROAD_SEARCH_RADIUS = 2;

    private PathPalette() {}

    /** Kind voted by the ground within a small radius (closer cells weigh more), biased toward a nearby existing road. */
    static Kind pickKindAveraged(WorldServer world, TerrainSampler sampler, @Nullable RoadIndex roadIndex, int x, int z) {
        final int R = KIND_VOTE_RADIUS;

        int[] score = new int[Kind.values().length];

        for (int dz = -R; dz <= R; dz++) {
            for (int dx = -R; dx <= R; dx++) {
                int xx = x + dx;
                int zz = z + dz;

                TerrainSampler.Sample sm = sampler.sample(xx, zz);
                if (sm == null) continue;
                if (sm.isOcean || sm.isWater) continue;

                BlockPos top = new BlockPos(xx, sm.surfaceY, zz);
                IBlockState st = world.getBlockState(top);

                if (st != null && MillPathBlocks.isMillPath(st.getBlock())) {
                    IBlockState below = world.getBlockState(top.down());
                    if (below != null) st = below;
                }

                if (st == null) continue;

                Material m = st.getMaterial();
                if (m == Material.AIR) continue;
                if (m == Material.PLANTS || m == Material.VINE) continue;
                if (m.isReplaceable()) continue;

                Kind k = pickKind(st);

                int md = Math.abs(dx) + Math.abs(dz);
                int w = Math.max(0, (R + 2) - md);
                if (w > 0) score[k.ordinal()] += w;
            }
        }

        if (roadIndex != null) {
            BlockPos near = roadIndex.findNearestRoad(x, z, EXISTING_ROAD_SEARCH_RADIUS);
            if (near != null) {
                Kind ek = roadIndex.findExistingKindAt(world, sampler, near.getX(), near.getZ());
                if (ek != null) score[ek.ordinal()] += EXISTING_ROAD_KIND_WEIGHT;
            }
        }

        int bestIdx = -1;
        int best = -1;
        for (int i = 0; i < score.length; i++) {
            if (score[i] > best) {
                best = score[i];
                bestIdx = i;
            }
        }

        if (bestIdx >= 0 && best > 0) {
            return Kind.values()[bestIdx];
        }

        TerrainSampler.Sample sm0 = sampler.sample(x, z);
        return pickKind(world.getBlockState(new BlockPos(x, sm0.surfaceY, z)));
    }

    /** Kind matching one ground block. */
    static Kind pickKind(IBlockState ground) {
        Block b = ground.getBlock();

        if (b == Blocks.GRAVEL) return Kind.GRAVEL;
        if (b == Blocks.SAND || b == Blocks.SANDSTONE || b == Blocks.RED_SANDSTONE) return Kind.DIRT;
        if (b == Blocks.SNOW || b == Blocks.SNOW_LAYER) return Kind.SNOW;

        Material m = ground.getMaterial();
        if (m == Material.ROCK || b == Blocks.STONE || b == Blocks.HARDENED_CLAY) return Kind.GRAVEL;

        return Kind.DIRT;
    }

    /** Block placed under a bottom slab: matches the ground below, else the path kind. */
    static IBlockState supportBlock(World world, BlockPos supportPos, Kind kind) {
        IBlockState below = world.getBlockState(supportPos.down());
        Block b = below.getBlock();
        Material m = below.getMaterial();

        if (b == Blocks.SAND || m == Material.SAND) return Blocks.SAND.getDefaultState();
        if (b == Blocks.GRAVEL) return Blocks.GRAVEL.getDefaultState();
        if (b == Blocks.CLAY || m == Material.CLAY) return Blocks.CLAY.getDefaultState();
        if (m == Material.ROCK || b == Blocks.STONE) return Blocks.STONE.getDefaultState();

        if (kind == Kind.GRAVEL) return Blocks.GRAVEL.getDefaultState();
        if (kind == Kind.SANDSTONE) return Blocks.SAND.getDefaultState();
        if (kind == Kind.SNOW) return Blocks.SNOW.getDefaultState();
        if (kind == Kind.SLABS) return Blocks.STONE.getDefaultState();

        return Blocks.DIRT.getDefaultState();
    }
}

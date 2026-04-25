package com.jubitus.millpathbuilder.gen.mill;

import com.jubitus.millpathbuilder.gen.newp.MPBMillenairePathCompat;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.millenaire.common.block.MillBlocks;

public final class MPBMillPathPalette {

    private MPBMillPathPalette() {}

    public enum Kind { DIRT, GRAVEL, SANDSTONE, SLABS, OCHRE, SNOW }

    public static IBlockState full(Kind k) {
        IBlockState st;
        switch (k) {
            case GRAVEL:    st = MillBlocks.PATHGRAVEL.getDefaultState(); break;
            case SANDSTONE: st = MillBlocks.PATHSANDSTONE.getDefaultState(); break;
            case SLABS:     st = MillBlocks.PATHSLABS.getDefaultState(); break;
            case OCHRE:     st = MillBlocks.PATHOCHRESLABS.getDefaultState(); break;
            case SNOW:      st = MillBlocks.PATHSNOW.getDefaultState(); break;
            case DIRT:
            default:        st = MillBlocks.PATHDIRT.getDefaultState(); break;
        }
        return MPBMillenairePathCompat.stabilizeIfPossible(st);
    }

    public static IBlockState slabBottom(Kind k) {
        IBlockState st;
        switch (k) {
            case GRAVEL:
                st = MillBlocks.PATHGRAVEL_SLAB.getDefaultState().withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.BOTTOM);
                break;
            case SANDSTONE:
                st = MillBlocks.PATHDIRT_SLAB.getDefaultState().withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.BOTTOM);
                break;
            case SLABS:
                st = MillBlocks.PATHGRAVEL.getDefaultState().withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.BOTTOM);
                break;
            case SNOW:
                st = MillBlocks.PATHSNOW_SLAB.getDefaultState().withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.BOTTOM);
                break;
            case DIRT:
            default:
                st = MillBlocks.PATHDIRT_SLAB.getDefaultState().withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.BOTTOM);
                break;
        }
        return MPBMillenairePathCompat.stabilizeIfPossible(st);
    }

    public static boolean isMillPath(Block b) {
        return b == MillBlocks.PATHDIRT || b == MillBlocks.PATHGRAVEL || b == MillBlocks.PATHSANDSTONE
                || b == MillBlocks.PATHSLABS || b == MillBlocks.PATHOCHRESLABS || b == MillBlocks.PATHSNOW
                || b == MillBlocks.PATHDIRT_SLAB || b == MillBlocks.PATHGRAVEL_SLAB || b == MillBlocks.PATHSANDSTONE_SLAB
                || b == MillBlocks.PATHSLABS_SLAB || b == MillBlocks.PATHOCHRESLABS_SLAB || b == MillBlocks.PATHSNOW_SLAB;
    }

    public static Kind pickKind(World world, BlockPos pos, IBlockState ground) {
        Block b = ground.getBlock();

        if (b == Blocks.GRAVEL) return Kind.GRAVEL;
        if (b == Blocks.SAND || b == Blocks.SANDSTONE || b == Blocks.RED_SANDSTONE) return Kind.DIRT;
        if (b == Blocks.SNOW || b == Blocks.SNOW_LAYER) return Kind.SNOW;

        Material m = ground.getMaterial();
        if (m == Material.ROCK || b == Blocks.STONE || b == Blocks.HARDENED_CLAY) return Kind.GRAVEL;

        return Kind.DIRT;
    }
}
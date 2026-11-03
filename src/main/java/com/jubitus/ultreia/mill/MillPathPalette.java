package com.jubitus.ultreia.mill;

import com.jubitus.ultreia.MillPresence;
import com.jubitus.ultreia.block.UltreiaBlocks;
import com.jubitus.ultreia.block.PathSlabBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.millenaire.common.block.MillBlocks;

public final class MillPathPalette {

    private MillPathPalette() {}

    public enum Kind { DIRT, GRAVEL, SANDSTONE, SLABS, OCHRE, SNOW }

    public static IBlockState full(Kind k) {
        if (!MillPresence.isPresent()) {
            return UltreiaBlocks.PATH_BLOCK.getDefaultState();
        }
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
        return MillPathStability.stabilizeIfPossible(st);
    }

    public static IBlockState slabBottom(Kind k) {
        if (!MillPresence.isPresent()) {
            return UltreiaBlocks.PATH_SLAB.getDefaultState()
                    .withProperty(PathSlabBlock.HALF, BlockSlab.EnumBlockHalf.BOTTOM);
        }
        Block slab;
        switch (k) {
            case GRAVEL:    slab = MillBlocks.PATHGRAVEL_SLAB; break;
            case SANDSTONE: slab = MillBlocks.PATHSANDSTONE_SLAB; break;
            case SLABS:     slab = MillBlocks.PATHSLABS_SLAB; break;
            case OCHRE:     slab = MillBlocks.PATHOCHRESLABS_SLAB; break;
            case SNOW:      slab = MillBlocks.PATHSNOW_SLAB; break;
            case DIRT:
            default:        slab = MillBlocks.PATHDIRT_SLAB; break;
        }
        IBlockState st = slab.getDefaultState().withProperty(BlockSlab.HALF, BlockSlab.EnumBlockHalf.BOTTOM);
        return MillPathStability.stabilizeIfPossible(st);
    }

    public static boolean isMillPath(Block b) {
        if (b == UltreiaBlocks.PATH_BLOCK || b == UltreiaBlocks.PATH_SLAB) return true;
        if (!MillPresence.isPresent()) return false;
        return b == MillBlocks.PATHDIRT || b == MillBlocks.PATHGRAVEL || b == MillBlocks.PATHSANDSTONE
                || b == MillBlocks.PATHSLABS || b == MillBlocks.PATHOCHRESLABS || b == MillBlocks.PATHSNOW
                || b == MillBlocks.PATHDIRT_SLAB || b == MillBlocks.PATHGRAVEL_SLAB || b == MillBlocks.PATHSANDSTONE_SLAB
                || b == MillBlocks.PATHSLABS_SLAB || b == MillBlocks.PATHOCHRESLABS_SLAB || b == MillBlocks.PATHSNOW_SLAB;
    }

    /** Path kind of an existing path block, or null when the block is not a path. */
    public static Kind kindFromState(IBlockState st) {
        if (st == null) return null;
        Block b = st.getBlock();
        if (b == UltreiaBlocks.PATH_BLOCK || b == UltreiaBlocks.PATH_SLAB) return Kind.DIRT;
        if (!MillPresence.isPresent()) return null;
        if (b == MillBlocks.PATHDIRT || b == MillBlocks.PATHDIRT_SLAB) return Kind.DIRT;
        if (b == MillBlocks.PATHGRAVEL || b == MillBlocks.PATHGRAVEL_SLAB) return Kind.GRAVEL;
        if (b == MillBlocks.PATHSNOW || b == MillBlocks.PATHSNOW_SLAB) return Kind.SNOW;
        if (b == MillBlocks.PATHSANDSTONE || b == MillBlocks.PATHSANDSTONE_SLAB) return Kind.SANDSTONE;
        if (b == MillBlocks.PATHSLABS || b == MillBlocks.PATHSLABS_SLAB) return Kind.SLABS;
        if (b == MillBlocks.PATHOCHRESLABS || b == MillBlocks.PATHOCHRESLABS_SLAB) return Kind.OCHRE;
        return null;
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
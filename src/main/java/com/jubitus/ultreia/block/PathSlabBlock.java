package com.jubitus.ultreia.block;

import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyEnum;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;

public class PathSlabBlock extends Block {

    public static final PropertyEnum<BlockSlab.EnumBlockHalf> HALF = BlockSlab.HALF;

    private static final AxisAlignedBB AABB_BOTTOM = new AxisAlignedBB(0.0, 0.0, 0.0, 1.0, 0.5, 1.0);
    private static final AxisAlignedBB AABB_TOP    = new AxisAlignedBB(0.0, 0.5, 0.0, 1.0, 1.0, 1.0);

    public PathSlabBlock() {
        super(Material.GROUND);
        setRegistryName("ultreia", "path_slab");
        setTranslationKey("ultreia.path_slab");
        setHardness(0.8F);
        setResistance(1.0F);
        setSoundType(SoundType.GROUND);
        setCreativeTab(CreativeTabs.DECORATIONS);
        setDefaultState(blockState.getBaseState().withProperty(HALF, BlockSlab.EnumBlockHalf.BOTTOM));
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, HALF);
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return getDefaultState().withProperty(HALF,
                (meta & 8) != 0 ? BlockSlab.EnumBlockHalf.TOP : BlockSlab.EnumBlockHalf.BOTTOM);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(HALF) == BlockSlab.EnumBlockHalf.TOP ? 8 : 0;
    }

    @Override
    public IBlockState getStateForPlacement(World world, BlockPos pos, EnumFacing facing,
                                             float hitX, float hitY, float hitZ,
                                             int meta, EntityLivingBase placer, EnumHand hand) {
        if (facing == EnumFacing.DOWN) {
            return getDefaultState().withProperty(HALF, BlockSlab.EnumBlockHalf.TOP);
        }
        if (facing == EnumFacing.UP) {
            return getDefaultState().withProperty(HALF, BlockSlab.EnumBlockHalf.BOTTOM);
        }
        return getDefaultState().withProperty(HALF,
                hitY > 0.5 ? BlockSlab.EnumBlockHalf.TOP : BlockSlab.EnumBlockHalf.BOTTOM);
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        return state.getValue(HALF) == BlockSlab.EnumBlockHalf.BOTTOM ? AABB_BOTTOM : AABB_TOP;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean doesSideBlockRendering(IBlockState state, IBlockAccess world, BlockPos pos, EnumFacing face) {
        if (state.getValue(HALF) == BlockSlab.EnumBlockHalf.BOTTOM) {
            return face == EnumFacing.DOWN;
        } else {
            return face == EnumFacing.UP;
        }
    }

    @Override
    public int damageDropped(IBlockState state) {
        return getMetaFromState(state);
    }

    @Override
    public void getSubBlocks(CreativeTabs tab, net.minecraft.util.NonNullList<ItemStack> list) {
        list.add(new ItemStack(this, 1, 0));
    }
}

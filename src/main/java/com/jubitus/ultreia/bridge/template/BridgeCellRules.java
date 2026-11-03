package com.jubitus.ultreia.bridge.template;

import com.jubitus.ultreia.terrain.BlockRules;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemSlab;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/** Block-level rules shared by bridge placement and template import: replaceable cells, slab halves, slab gaps. */
final class BridgeCellRules {

    private BridgeCellRules() {}

    /** Clears the cell when a bridge block may replace what is there; false when it must be left alone. */
    static boolean prepareCell(WorldServer world, BlockPos pos, IBlockState st) {
        if (st == null) return true;

        Material m = st.getMaterial();
        Block b = st.getBlock();

        if (m == Material.AIR) return true;
        if (world.getTileEntity(pos) != null) return false;
        if (b == Blocks.BEDROCK || b == Blocks.BARRIER) return false;

        if (BlockRules.isBridgeReplaceable(world, pos, st)) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
            return true;
        }

        return false;
    }

    static IBlockState withSlabHalf(IBlockState base, boolean top) {
        Block b = base.getBlock();
        if (!(b instanceof BlockSlab)) return base;
        try {
            if (((BlockSlab) b).isDouble()) return base;
            return base.withProperty(BlockSlab.HALF, top ? BlockSlab.EnumBlockHalf.TOP : BlockSlab.EnumBlockHalf.BOTTOM);
        } catch (Throwable ignored) {
            return base;
        }
    }

    static boolean isSolidGroundStop(IBlockState st) {
        if (st == null) return true;
        Material m = st.getMaterial();
        return m.isSolid() && m != Material.WATER && m != Material.ICE;
    }

    // ---- gap fill between foundation/terrain and top-slab deck ----
    //
    // When sp.top=true the deck slab occupies only the upper half of sp.blockY, leaving a
    // 0.5-block air gap against any solid block directly below. We replace the top slab with
    // a filler block so the gap disappears without changing the walkable surface height.
    //
    // Priority: vanilla slab → matching full block (planks, stone…)
    //           modded slab  → double slab of the same mod/type (via ItemSlab reflection)

    static void fillTopSlabGap(WorldServer world, BlockPos deckPos, IBlockState deckBaseState) {
        IBlockState existing = world.getBlockState(deckPos);
        Block block = existing.getBlock();
        if (block != deckBaseState.getBlock()) return;
        if (!(block instanceof BlockSlab)) return;
        if (((BlockSlab) block).isDouble()) return;

        BlockSlab.EnumBlockHalf half;
        try {
            half = existing.getValue(BlockSlab.HALF);
        } catch (Exception e) {
            return;
        }
        if (half != BlockSlab.EnumBlockHalf.TOP) return;

        if (!world.getBlockState(deckPos.down()).getMaterial().isSolid()) return;

        IBlockState filler = fillerForSlab(block, existing);
        if (filler == null) return;

        world.setBlockState(deckPos, filler, 3);
    }

    // Vanilla slabs → full block; modded slabs → double slab of the same type.
    private static IBlockState fillerForSlab(Block slab, IBlockState slabState) {
        IBlockState full = vanillaFullBlock(slab, slabState);
        if (full != null) return full;
        return moddedDoubleSlab(slab, slabState);
    }

    // Static lookup for all vanilla slab → full-block pairings.
    // Metadata is masked with 0x7 to strip the top/bottom half bit.
    private static IBlockState vanillaFullBlock(Block slab, IBlockState slabState) {
        if (slab == Blocks.WOODEN_SLAB) {
            // wooden slab meta 0-5 matches planks meta 0-5 (oak/spruce/birch/jungle/acacia/dark_oak)
            return Blocks.PLANKS.getStateFromMeta(slab.getMetaFromState(slabState) & 0x7);
        }
        if (slab == Blocks.STONE_SLAB) {
            switch (slab.getMetaFromState(slabState) & 0x7) {
                case 0: return Blocks.STONE.getDefaultState();
                case 1: return Blocks.SANDSTONE.getDefaultState();
                case 2: return Blocks.PLANKS.getDefaultState();
                case 3: return Blocks.COBBLESTONE.getDefaultState();
                case 4: return Blocks.BRICK_BLOCK.getDefaultState();
                case 5: return Blocks.STONEBRICK.getDefaultState();
                case 6: return Blocks.NETHER_BRICK.getDefaultState();
                case 7: return Blocks.QUARTZ_BLOCK.getDefaultState();
            }
        }
        if (slab == Blocks.STONE_SLAB2) return Blocks.RED_SANDSTONE.getDefaultState();
        if (slab == Blocks.PURPUR_SLAB)  return Blocks.PURPUR_BLOCK.getDefaultState();
        return null;
    }

    // For modded slabs: finds the double-slab variant via ItemSlab field reflection.
    // Discovery is type-based (looks for BlockSlab fields), not name-based, so it works in
    // both the MCP-mapped dev environment and the obfuscated production environment.
    private static IBlockState moddedDoubleSlab(Block slab, IBlockState slabState) {
        Block doubleBlock = getDoubleSlabFor(slab);
        if (doubleBlock == null) return null;
        return buildDoubleSlabState(doubleBlock, slabState, (BlockSlab) slab);
    }

    private static final java.util.HashMap<Block, Block> DOUBLE_SLAB_CACHE = new java.util.HashMap<>();
    private static volatile boolean doubleSlabCacheReady = false;

    private static Block getDoubleSlabFor(Block single) {
        if (!doubleSlabCacheReady) buildDoubleSlabCache();
        return DOUBLE_SLAB_CACHE.get(single);
    }

    private static synchronized void buildDoubleSlabCache() {
        if (doubleSlabCacheReady) return;
        for (Item item : ForgeRegistries.ITEMS) {
            if (!(item instanceof ItemSlab)) continue;
            Block single = null;
            Block doubleBlock = null;
            for (java.lang.reflect.Field f : ItemSlab.class.getDeclaredFields()) {
                if (!BlockSlab.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(item);
                    if (!(val instanceof BlockSlab)) continue;
                    if (((BlockSlab) val).isDouble()) doubleBlock = (Block) val;
                    else single = (Block) val;
                } catch (Exception ignored) {}
            }
            if (single != null && doubleBlock != null) {
                DOUBLE_SLAB_CACHE.put(single, doubleBlock);
            }
        }
        doubleSlabCacheReady = true;
    }

    private static IBlockState buildDoubleSlabState(Block doubleBlock, IBlockState singleState, BlockSlab singleSlab) {
        try {
            IProperty<?> variantProp = singleSlab.getVariantProperty();
            IBlockState base = doubleBlock.getDefaultState();
            if (variantProp == null) return base;
            Comparable<?> val = singleState.getValue(variantProp);
            IProperty<?> doubleVariant = ((BlockSlab) doubleBlock).getVariantProperty();
            if (doubleVariant != null && doubleVariant.getName().equals(variantProp.getName())) {
                base = applyVariant(base, doubleVariant, val);
            }
            return base;
        } catch (Exception e) {
            return doubleBlock.getDefaultState();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> IBlockState applyVariant(IBlockState state, IProperty<T> prop, Comparable<?> raw) {
        try {
            return state.withProperty(prop, (T) raw);
        } catch (Exception e) {
            return state;
        }
    }
}

package com.jubitus.ultreia.terrain;

import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.mill.MillPathBlocks;
import net.minecraft.block.Block;
import net.minecraft.block.BlockHugeMushroom;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.BlockStairs;
import net.minecraft.block.BlockWall;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Locale;

/** What kind of block this is: natural ground, tree, plant, water, clearable. The only place these rules live. */
public final class BlockRules {

    private BlockRules() {}

    /** Roads and bridges may cut tree trunks (logs, DynamicTrees branches). */
    public static boolean treesClearable() {
        return UltreiaConfig.PAINTING.clearTrees;
    }

    // ----- Ground -----

    /** Natural ground or a path block: a road may be painted onto or into it. */
    public static boolean isRoadReplaceableGround(IBlockState st) {
        if (st == null) return false;

        // Rootydirt is plain dirt anchoring a DT tree — valid painting surface once the tree is cleared.
        if (TreeCompat.isDTRootyBlock(st)) return true;
        // Other DT structural blocks (branches, trunkshell, saplings) are never ground.
        if (TreeCompat.isDynamicTreesBlock(st)) return false;
        Block b = st.getBlock();
        if (b == Blocks.AIR) return false;

        // Never paint over farmland or tilled soil — would destroy crops.
        if (isFarmland(st)) return false;

        if (MillPathBlocks.isMillPath(b)) return true;

        Material m = st.getMaterial();

        if (!m.isSolid()) return false;

        if (m == Material.WOOD || m == Material.LEAVES) return false;
        if (m == Material.GLASS) return false;
        if (m == Material.IRON || m == Material.ANVIL) return false;
        if (m == Material.REDSTONE_LIGHT) return false;

        if (b == Blocks.BEDROCK || b == Blocks.BARRIER) return false;
        if (b == Blocks.OBSIDIAN) return false;

        if (b == Blocks.GRASS || b == Blocks.DIRT || b == Blocks.GRAVEL || b == Blocks.SAND || b == Blocks.CLAY) return true;
        if (b == Blocks.STONE) return true;
        if (isBuiltStone(b)) return false;
        if (b == Blocks.MYCELIUM) return true;
        if (b == Blocks.SNOW || b == Blocks.SNOW_LAYER) return true;
        if (b == Blocks.HARDENED_CLAY || b == Blocks.STAINED_HARDENED_CLAY) return true;

        if (m == Material.GROUND || m == Material.GRASS || m == Material.SAND || m == Material.CLAY || m == Material.SNOW) return true;
        if (m == Material.ROCK) return true;

        String name = (b.getRegistryName() != null) ? b.getRegistryName().toString().toLowerCase() : "";
        if (name.isEmpty()) return false;

        // Also exclude modded farmland / tilled soil by name.
        if (name.contains("farmland") || name.contains("tilledsoil") || name.contains("tilled_soil")) return false;

        if (name.contains("grass") || name.contains("dirt") || name.contains("soil") || name.contains("loam")
                || name.contains("silt") || name.contains("sand") || name.contains("gravel") || name.contains("clay")
                || name.contains("stone") || name.contains("rock") || name.contains("overgrown")
                || name.contains("moss") || name.contains("podzol") || name.contains("mycel")) {
            return true;
        }

        return false;
    }

    /** Natural ground, path blocks excluded. */
    public static boolean isNaturalGroundOnly(IBlockState st) {
        if (st == null) return false;
        if (MillPathBlocks.isMillPath(st.getBlock())) return false;
        return isRoadReplaceableGround(st);
    }

    /** Solid natural ground (or a clearable DT block) that may be dug out above a road. Path blocks never. */
    public static boolean isNaturalSolid(IBlockState st) {
        if (st == null) return false;

        if (MillPathBlocks.isMillPath(st.getBlock())) return false;

        if (TreeCompat.isDynamicTreesBlock(st)) return treesClearable();

        Material m = st.getMaterial();
        if (!m.isSolid()) return false;
        if (m == Material.LEAVES) return false;
        if (m == Material.WOOD) return false;

        return isRoadReplaceableGround(st);
    }

    /** Stone blocks that only appear in structures: never treated as natural ground. */
    private static boolean isBuiltStone(Block b) {
        if (b == Blocks.STONEBRICK || b == Blocks.COBBLESTONE || b == Blocks.MOSSY_COBBLESTONE) return true;
        if (b == Blocks.BRICK_BLOCK || b == Blocks.NETHER_BRICK) return true;
        return b instanceof BlockStairs || b instanceof BlockWall || b instanceof BlockSlab;
    }

    /** Farmland or a modded equivalent (tilled soil): never painted over or replaced. */
    public static boolean isFarmland(IBlockState st) {
        if (st == null) return false;
        Block b = st.getBlock();
        if (b == Blocks.FARMLAND) return true;
        ResourceLocation id = b.getRegistryName();
        if (id == null) return false;
        String name = id.toString().toLowerCase();
        return name.contains("farmland") || name.contains("tilledsoil") || name.contains("tilled_soil");
    }

    // ----- Trees and plants -----

    /** A tree trunk: logs, huge mushrooms and DynamicTrees branches. Planks, fences and doors share the wood material but are built. */
    public static boolean isTreeWood(World world, BlockPos pos, IBlockState st) {
        if (st == null) return false;

        if (TreeCompat.isDynamicTreesBranch(st) || TreeCompat.isDynamicTreesTrunkShell(st)) return true;

        Block b = st.getBlock();
        if (b.isWood(world, pos)) return true;
        if (b instanceof BlockHugeMushroom) return true;
        // Modded logs that do not extend BlockLog.
        if (st.getMaterial() == Material.WOOD) {
            ResourceLocation id = b.getRegistryName();
            return id != null && id.getPath().toLowerCase().contains("log");
        }

        return false;
    }

    /** Leaves, plants and vines. */
    public static boolean isFoliage(World world, BlockPos pos, IBlockState st) {
        if (st == null) return false;

        Block b = st.getBlock();
        Material m = st.getMaterial();

        if (b.isLeaves(st, world, pos)) return true;
        if (m == Material.LEAVES) return true;
        if (m == Material.PLANTS || m == Material.VINE) return true;

        ResourceLocation id = b.getRegistryName();
        if (id != null) {
            String s = id.toString().toLowerCase();
            if (s.contains("dynamictrees") && (s.contains("leaves") || s.contains("leaf"))) {
                return true;
            }
        }

        return false;
    }

    // ----- Water -----

    /** Water or ice. */
    public static boolean isWaterLike(IBlockState st) {
        if (st == null) return false;
        Material m = st.getMaterial();
        if (m == Material.WATER || m == Material.ICE) return true;
        Block b = st.getBlock();
        return b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.FROSTED_ICE;
    }

    /** Lily pads: the column below them is water. */
    public static boolean isWaterSurfaceDecoration(IBlockState st) {
        if (st == null) return false;

        Block b = st.getBlock();
        if (b == Blocks.WATERLILY) return true;

        Material m = st.getMaterial();
        if (m == Material.PLANTS || m == Material.VINE) {
            ResourceLocation id = b.getRegistryName();
            if (id != null) {
                String s = id.toString().toLowerCase(Locale.ROOT);
                return s.contains("lilypad") || s.contains("lily_pad") || s.contains("waterlily") || s.contains("water_lily");
            }
        }

        return false;
    }

    /** Removing a block here would let water flow in. */
    public static boolean wouldCauseWaterFlow(World world, BlockPos pos) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (EnumFacing f : EnumFacing.values()) {
            p.setPos(pos.getX() + f.getXOffset(), pos.getY() + f.getYOffset(), pos.getZ() + f.getZOffset());
            IBlockState n = world.getBlockState(p);
            if (n != null && n.getMaterial() == Material.WATER) return true;
        }
        return false;
    }

    // ----- Clearing -----

    // INVARIANT: lava is never clearable nor skipped (vanilla marks liquids replaceable).
    /** A non-air block a road or bridge may remove above its surface: plants, snow, foliage, tree trunks when allowed. */
    public static boolean isSoftClearable(World world, BlockPos pos, IBlockState st) {
        Material m = st.getMaterial();
        Block b = st.getBlock();

        if (m == Material.AIR) return false;
        if (m == Material.LAVA) return false;
        if (b == Blocks.SNOW_LAYER) return true;
        if (m.isReplaceable()) return true;
        if (b.isReplaceable(world, pos)) return true;
        if (isFoliage(world, pos, st)) return true;

        return treesClearable() && isTreeWood(world, pos, st);
    }

    public static boolean isClearableOrAir(World world, BlockPos pos, IBlockState st) {
        return st.getMaterial() == Material.AIR || isSoftClearable(world, pos, st);
    }

    /** Free room for a bridge deck; a wooden slab is accepted where a deck slab may sit on it. */
    public static boolean isBridgeClearable(World world, BlockPos pos, IBlockState st, boolean allowWoodenSlab) {
        if (isClearableOrAir(world, pos, st)) return true;
        return allowWoodenSlab && st.getBlock() == Blocks.WOODEN_SLAB;
    }

    /** A bridge may clear this block above its deck: anything clearable plus natural ground and path blocks. */
    public static boolean isBridgeWalkClearable(World world, BlockPos pos, IBlockState st) {
        return isClearableOrAir(world, pos, st) || isRoadReplaceableGround(st);
    }

    /** A bridge structure (deck, supports, imported template) may replace this block. */
    public static boolean isBridgeReplaceable(World world, BlockPos pos, IBlockState st) {
        if (st == null) return true;
        if (st.getMaterial() == Material.ICE) return true;
        return isBridgeWalkClearable(world, pos, st);
    }

    /** Loose cover earthworks may fill through or replace: air, plants, leaves, replaceable blocks. */
    public static boolean isLooseCover(World world, BlockPos pos, IBlockState st) {
        Material m = st.getMaterial();
        if (m == Material.AIR || m.isReplaceable()) return true;
        if (m == Material.PLANTS || m == Material.VINE || m == Material.LEAVES) return true;
        return st.getBlock().isLeaves(st, world, pos);
    }
}

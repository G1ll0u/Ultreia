package com.jubitus.millpathbuilder.gen.newp;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public final class MPBTreeCompat {

    private MPBTreeCompat() {}

    private static volatile Class<?> DT_BRANCH_CLASS;
    private static volatile Class<?> DT_TRUNK_SHELL_CLASS;
    private static volatile Class<?> DT_ROOTY_CLASS;
    private static volatile boolean DT_CHECKED = false;

    private static void initDTClasses() {
        if (DT_CHECKED) return;
        DT_CHECKED = true;
        try {
            DT_BRANCH_CLASS = Class.forName("com.ferreusveritas.dynamictrees.blocks.BlockBranch");
        } catch (Throwable ignored) {
        }
        try {
            DT_TRUNK_SHELL_CLASS = Class.forName("com.ferreusveritas.dynamictrees.blocks.BlockTrunkShell");
        } catch (Throwable ignored) {
        }
        try {
            DT_ROOTY_CLASS = Class.forName("com.ferreusveritas.dynamictrees.blocks.BlockRooty");
        } catch (Throwable ignored) {
        }
    }

    public static boolean isDynamicTreesBranch(IBlockState st) {
        if (st == null) return false;

        Block b = st.getBlock();
        if (b == null) return false;

        initDTClasses();

        if (DT_BRANCH_CLASS != null && DT_BRANCH_CLASS.isInstance(b)) return true;

        for (Class<?> c = b.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String cn = c.getName().toLowerCase();
            if (cn.contains("blockbranch") && (cn.contains("dynamictrees") || cn.contains("dynamictree"))) {
                return true;
            }
        }

        ResourceLocation id = b.getRegistryName();
        if (id != null) {
            String s = id.toString().toLowerCase();
            if (s.contains("branch") && (s.contains("dynamictrees") || s.contains("dynamictree"))) {
                return true;
            }
        }

        return false;
    }

    public static boolean isDynamicTreesTrunkShell(IBlockState st) {
        if (st == null) return false;

        Block b = st.getBlock();
        if (b == null) return false;

        initDTClasses();

        if (DT_TRUNK_SHELL_CLASS != null && DT_TRUNK_SHELL_CLASS.isInstance(b)) return true;

        for (Class<?> c = b.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String cn = c.getName().toLowerCase();
            if (cn.contains("trunkshell") && (cn.contains("dynamictrees") || cn.contains("dynamictree"))) {
                return true;
            }
        }

        ResourceLocation id = b.getRegistryName();
        if (id != null) {
            String s = id.toString().toLowerCase();
            if (s.contains("trunkshell") && (s.contains("dynamictrees") || s.contains("dynamictree"))) {
                return true;
            }
        }

        return false;
    }

    /** True for BlockBranch, BlockTrunkShell, or BlockRooty variants. */
    public static boolean isDynamicTreesBlock(IBlockState st) {
        return isDynamicTreesBranch(st) || isDynamicTreesTrunkShell(st) || isDTRootyBlock(st);
    }

    /**
     * True if this is a DynamicTrees BlockRooty variant (rootydirt, rootysand, rootydirtspecies, …).
     * These blocks anchor the tree in the ground and are placed at surfaceY.
     */
    public static boolean isDTRootyBlock(IBlockState st) {
        if (st == null) return false;

        Block b = st.getBlock();
        if (b == null) return false;

        initDTClasses();

        if (DT_ROOTY_CLASS != null && DT_ROOTY_CLASS.isInstance(b)) return true;

        for (Class<?> c = b.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String cn = c.getName().toLowerCase();
            if (cn.contains("blockrooty") && (cn.contains("dynamictrees") || cn.contains("dynamictree"))) {
                return true;
            }
        }

        ResourceLocation id = b.getRegistryName();
        if (id != null) {
            String s = id.toString().toLowerCase();
            if ((s.contains("dynamictrees") || s.contains("dynamictree"))
                    && (s.contains("rooty") || s.contains("rootydirt") || s.contains("rootysand"))) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns the DT branch radius (1-8) for DT branch blocks, 0 if not a DT branch.
     * Reads the block-state "radius" property without reflection on the value.
     */
    public static int getDTBranchRadius(IBlockState st) {
        if (!isDynamicTreesBranch(st)) return 0;

        try {
            for (IProperty<?> prop : st.getPropertyKeys()) {
                if ("radius".equals(prop.getName())) {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    Comparable<?> val = st.getValue((IProperty) prop);
                    if (val instanceof Integer) return (Integer) val;
                }
            }
        } catch (Throwable ignored) {
        }

        return 1; // DT branch but radius unknown → assume smallest
    }

    public static boolean isTreeWoodLike(World world, BlockPos pos, IBlockState st) {
        if (st == null) return false;

        if (isDynamicTreesBranch(st) || isDynamicTreesTrunkShell(st)) return true;

        Block b = st.getBlock();
        Material m = st.getMaterial();

        if (b.isWood(world, pos)) return true;
        if (m == Material.WOOD) return true;

        return false;
    }

    public static boolean isTreeFoliageLike(World world, BlockPos pos, IBlockState st) {
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

    /**
     * Removes an entire DynamicTrees tree by flood-filling from any branch block.
     *
     * Branch and TrunkShell blocks are removed immediately via setBlockState(AIR).
     * DT's breakBlock() is triggered for each, scheduling natural cascade rot for
     * anything missed. The rootydirt block directly below the lowest trunk is
     * converted to plain dirt so the tree cannot regrow.
     *
     * Leaves are intentionally NOT removed here — they will decay organically once
     * the supporting branches are gone, which looks far more natural than a
     * sudden cube of air.
     *
     * @param world        the server world
     * @param startBranch  any branch/trunkshell block belonging to the target tree
     * @return             the position of the rootydirt block that was converted to dirt,
     *                     or {@code null} if none was found. The caller should paint
     *                     a road block at this position so no bare dirt is left.
     */
    @javax.annotation.Nullable
    public static BlockPos removeDTTreeCompletely(World world, BlockPos startBranch) {
        if (world == null || startBranch == null) return null;

        // Sanity: ensure start is actually a DT structural block
        IBlockState startSt = world.getBlockState(startBranch);
        if (!isDynamicTreesBranch(startSt) && !isDynamicTreesTrunkShell(startSt)) return null;

        // BFS over all connected DT branch / trunk-shell blocks.
        LongOpenHashSet visited = new LongOpenHashSet(128);
        ArrayDeque<BlockPos> queue = new ArrayDeque<>(64);
        List<BlockPos> toRemove = new ArrayList<>(128);

        visited.add(startBranch.toLong());
        queue.add(startBranch);

        final int LIMIT = 600;

        while (!queue.isEmpty() && toRemove.size() < LIMIT) {
            BlockPos cur = queue.poll();
            IBlockState st = world.getBlockState(cur);

            boolean isStructural = isDynamicTreesBranch(st) || isDynamicTreesTrunkShell(st);
            if (!isStructural) continue;

            toRemove.add(cur);

            for (EnumFacing face : EnumFacing.values()) {
                BlockPos next = cur.offset(face);
                long key = next.toLong();
                if (visited.add(key)) {
                    queue.add(next);
                }
            }
        }

        if (toRemove.isEmpty()) return null;

        // Locate and neutralise the rootydirt block that anchors this tree.
        // It sits directly below the lowest branch we found.
        int minY = Integer.MAX_VALUE;
        BlockPos lowestBranch = toRemove.get(0);
        for (BlockPos p : toRemove) {
            if (p.getY() < minY) {
                minY = p.getY();
                lowestBranch = p;
            }
        }
        BlockPos rootPos = lowestBranch.down();
        IBlockState rootSt = world.getBlockState(rootPos);
        BlockPos paintableRootPos = null;
        if (isDTRootyBlock(rootSt)) {
            // Replace with plain dirt so the tree cannot regrow.
            // The caller is expected to then paint a road block here if applicable.
            world.setBlockState(rootPos, Blocks.DIRT.getDefaultState(), 3);
            paintableRootPos = rootPos.toImmutable();
        }

        // Remove all structural blocks (calls DT's breakBlock → schedules cascade cleanup).
        for (BlockPos pos : toRemove) {
            if (world.getBlockState(pos).getBlock() != Blocks.AIR) {
                world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
            }
        }

        return paintableRootPos;
    }
}

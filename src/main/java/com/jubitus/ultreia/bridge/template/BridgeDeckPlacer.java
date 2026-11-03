package com.jubitus.ultreia.bridge.template;

import com.jubitus.ultreia.bridge.BridgeClearanceCheck;
import com.jubitus.ultreia.bridge.BridgeGeometry;
import com.jubitus.ultreia.bridge.BridgeSpan;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.mill.MillPathPalette;
import com.jubitus.ultreia.terrain.BlockRules;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

/** Lays the walkable deck slabs of a span (deck width, half-block heights) and clears the room above them. */
final class BridgeDeckPlacer {

    private BridgeDeckPlacer() {}

    static void place(WorldServer world, BridgeSpan span, int[] deckHalfY, IBlockState deckBaseState) {
        final int n = span.centerline.size();
        BridgeGeometry.forEachFootprintCell(span.centerline, BridgeGeometry.DECK_WIDTH_BLOCKS / 2, (x, z, i, diagonalFill) -> {
            HalfY.SlabPlacement sp = HalfY.slabPlacement(deckHalfY[i]);
            IBlockState deckState = BridgeCellRules.withSlabHalf(deckBaseState, sp.top);
            // A diagonal corner of step 1 touches the start cell, so it counts as an end, like the end cells.
            boolean interior = diagonalFill ? (i > 1 && i < n - 1) : (i > 0 && i < n - 1);
            placeDeckCell(world, x, z, sp, deckState, interior);
        });
    }

    private static void placeDeckCell(WorldServer world,
                                      int x,
                                      int z,
                                      HalfY.SlabPlacement sp,
                                      IBlockState slab,
                                      boolean enforceUnderBridgeRoadClearance) {
        if (enforceUnderBridgeRoadClearance && hasRoadUnderDeckTooClose(world, x, z, sp.blockY)) {
            return;
        }

        BlockPos deckPos = new BlockPos(x, sp.blockY, z);
        if (world.getTileEntity(deckPos) != null) return;

        if (!BridgeCellRules.prepareCell(world, deckPos, world.getBlockState(deckPos))) return;

        world.setBlockState(deckPos, slab, 3);
        if (sp.top) {
            BridgeCellRules.fillTopSlabGap(world, deckPos, slab);
        }
        clearBridgeWalkVolume(world, x, z, sp.blockY + 1, sp.blockY + 3);
    }

    private static boolean hasRoadUnderDeckTooClose(WorldServer world, int x, int z, int deckBlockY) {
        int need = Math.max(0, BridgeClearanceCheck.UNDER_BRIDGE_ROAD_MIN_CLEARANCE_BLOCKS);
        if (need <= 0) return false;

        int minY = Math.max(1, deckBlockY - need - 4);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int y = deckBlockY - 1; y >= minY; y--) {
            p.setPos(x, y, z);
            IBlockState st = world.getBlockState(p);
            if (st == null) continue;
            if (!MillPathPalette.isMillPath(st.getBlock())) continue;

            int freeBlocks = deckBlockY - y - 1;
            return freeBlocks < need;
        }

        return false;
    }

    private static void clearBridgeWalkVolume(WorldServer world, int x, int z, int yFrom, int yTo) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int y = yFrom; y <= yTo; y++) {
            p.setPos(x, y, z);

            IBlockState st = world.getBlockState(p);
            if (st == null) continue;

            Material m = st.getMaterial();
            if (m == Material.AIR) continue;
            if (world.getTileEntity(p) != null) continue;

            if (BlockRules.isBridgeWalkClearable(world, p, st)) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
            }
        }
    }
}

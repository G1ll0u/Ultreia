package com.jubitus.ultreia.gen.bridge.template;

import com.jubitus.ultreia.config.MPBConfig;
import com.jubitus.ultreia.terrain.GroundRules;
import com.jubitus.ultreia.gen.bridge.BridgeGeometry;
import com.jubitus.ultreia.gen.mill.MPBMillPathPalette;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

public final class BridgeTemplateImporter {

    public static final class ImportResult {
        public final boolean ok;
        public final String message;
        public final int placedBlocks;

        private ImportResult(boolean ok, String message, int placedBlocks) {
            this.ok = ok;
            this.message = message;
            this.placedBlocks = placedBlocks;
        }

        public static ImportResult ok(String message, int placedBlocks) {
            return new ImportResult(true, message, placedBlocks);
        }

        public static ImportResult fail(String message) {
            return new ImportResult(false, message, 0);
        }
    }

    private BridgeTemplateImporter() {
    }

    public static ImportResult importUnderPlayer(WorldServer world, BlockPos playerPos, BridgeTemplate template) {
        if (world == null) return ImportResult.fail("World is null.");
        if (playerPos == null) return ImportResult.fail("Player position is null.");
        if (template == null) return ImportResult.fail("Template is null.");
        if (template.segmentLength <= 0) return ImportResult.fail("Template segment length is invalid.");
        if (template.deckSlab == null) return ImportResult.fail("Template deck slab is missing.");

        BridgeGeometry.Dir2 dir = canonicalDir(template);
        BridgeGeometry.Dir2 perp = dir.perp();

        int originX = playerPos.getX() - dir.dx * (template.segmentLength / 2);
        int originY = playerPos.getY() - 1;
        int originZ = playerPos.getZ() - dir.dz * (template.segmentLength / 2);

        int placed = 0;

        IBlockState deckBase = template.deckSlab.toState();
        if (!(deckBase.getBlock() instanceof BlockSlab)) {
            return ImportResult.fail("Template deck slab is not a slab block.");
        }

        IBlockState deckBottom = withSlabHalf(deckBase, false);

        for (int f = 0; f < template.segmentLength; f++) {
            for (int lateral = -1; lateral <= 1; lateral++) {
                BlockPos pos = localToWorld(originX, originY, originZ, dir, perp, f, lateral, 0);
                if (placeImportBlock(world, pos, deckBottom)) {
                    placed++;
                }
            }
        }

        if (template.blocks != null) {
            for (BridgeTemplate.BlockEntry b : template.blocks) {
                if (b == null || b.state == null) continue;

                BlockPos pos = localToWorld(originX, originY, originZ, dir, perp, b.forward, b.lateral, b.relY);
                IBlockState state = b.state.toState();

                if (placeImportBlock(world, pos, state)) {
                    placed++;
                }
            }
        }

        return ImportResult.ok("Bridge template imported: " + template.name + " placed=" + placed, placed);
    }

    private static BridgeGeometry.Dir2 canonicalDir(BridgeTemplate template) {
        String s = template.canonicalForward == null ? "south" : template.canonicalForward.trim().toLowerCase();

        if ("north".equals(s)) return new BridgeGeometry.Dir2(0, -1);
        if ("east".equals(s)) return new BridgeGeometry.Dir2(1, 0);
        if ("west".equals(s)) return new BridgeGeometry.Dir2(-1, 0);
        return new BridgeGeometry.Dir2(0, 1);
    }

    private static BlockPos localToWorld(int originX,
                                         int originY,
                                         int originZ,
                                         BridgeGeometry.Dir2 dir,
                                         BridgeGeometry.Dir2 perp,
                                         int forward,
                                         int lateral,
                                         int relY) {
        int x = originX + dir.dx * forward + perp.dx * lateral;
        int y = originY + relY;
        int z = originZ + dir.dz * forward + perp.dz * lateral;
        return new BlockPos(x, y, z);
    }

    private static IBlockState withSlabHalf(IBlockState base, boolean top) {
        Block b = base.getBlock();
        if (!(b instanceof BlockSlab)) return base;

        try {
            if (((BlockSlab) b).isDouble()) return base;
            return base.withProperty(BlockSlab.HALF, top ? BlockSlab.EnumBlockHalf.TOP : BlockSlab.EnumBlockHalf.BOTTOM);
        } catch (Throwable ignored) {
            return base;
        }
    }

    private static boolean placeImportBlock(WorldServer world, BlockPos pos, IBlockState state) {
        if (world == null || pos == null || state == null) return false;

        if (!prepareImportCell(world, pos)) return false;

        world.setBlockState(pos, state, 2);
        return true;
    }

    private static boolean prepareImportCell(WorldServer world, BlockPos pos) {
        IBlockState st = world.getBlockState(pos);
        if (st == null) return true;

        Material m = st.getMaterial();
        Block b = st.getBlock();

        if (m == Material.AIR) return true;
        if (world.getTileEntity(pos) != null) return false;
        if (b == Blocks.BEDROCK || b == Blocks.BARRIER) return false;

        if (isImportReplaceable(world, pos, st)) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
            return true;
        }

        return false;
    }

    private static boolean isImportReplaceable(WorldServer world, BlockPos pos, IBlockState st) {
        if (st == null) return true;

        Material m = st.getMaterial();
        Block b = st.getBlock();

        if (m == Material.AIR) return true;
        if (m == Material.PLANTS || m == Material.VINE) return true;
        if (m.isReplaceable()) return true;
        if (m == Material.WATER || m == Material.ICE) return true;
        if (b == Blocks.SNOW_LAYER || b == Blocks.WATERLILY) return true;
        if (b.isLeaves(st, world, pos) || m == Material.LEAVES) return true;
        if (MPBMillPathPalette.isMillPath(b)) return true;

        if (MPBConfig.PAINTING.clearTrees && MPBConfig.PAINTING.clearTreeTrunks) {
            if (b.isWood(world, pos) || m == Material.WOOD) return true;
        }

        return GroundRules.isRoadReplaceableGround(st);
    }
}
package com.jubitus.ultreia.gen.bridge.template;

import com.jubitus.ultreia.config.MPBConfig;
import com.jubitus.ultreia.gen.MPBGround;
import com.jubitus.ultreia.gen.TerrainSampler;
import com.jubitus.ultreia.gen.bridge.BridgeGeometry;
import com.jubitus.ultreia.gen.bridge.BridgeSpan;
import com.jubitus.ultreia.gen.bridge.RasterLine8;
import com.jubitus.ultreia.gen.bridge.SupercoverLine;
import com.jubitus.ultreia.gen.mill.MPBMillPathPalette;
import com.jubitus.ultreia.gen.util.MPBPos2D;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemSlab;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

public final class BridgeTemplateBridgeBuilder {

    private static final class SupportStart {
        final int y;
        final IBlockState state;

        SupportStart(int y, IBlockState state) {
            this.y = y;
            this.state = state;
        }
    }

    private BridgeTemplateBridgeBuilder() {
    }

    public static boolean build(WorldServer world,
                                TerrainSampler sampler,
                                BridgeSpan span,
                                BridgeTemplate template,
                                boolean dryRun) {
        if (span == null || span.centerline == null || span.centerline.isEmpty()) return true;
        if (template == null) return false;
        if (template.segmentLength < 1) return false;
        if (template.deckSlab == null) return false;
        BridgeTemplatePlacementContext placeCtx = BridgeTemplatePlacementContext.of(template, span);

        int len = span.lengthBlocks();
        int maxLen = Math.max(5, MPBConfig.BRIDGES.maxBridgeLenBlocks);
        if (len > maxLen) return false;

        int[] deckHalfY = BridgeGeometry.buildDeckHalfProfile(span);
        LongOpenHashSet deckXZ = new LongOpenHashSet(Math.max(128, span.centerline.size() * 8));

        IBlockState deckBaseState = template.deckSlab.toState();
        if (!(deckBaseState.getBlock() instanceof BlockSlab)) {
            return false;
        }

        for (int i = 0; i < span.centerline.size(); i++) {
            BlockPos c = span.centerline.get(i);

            BridgeGeometry.Dir2 dir = BridgeGeometry.localDir(span.centerline, i);
            BridgeGeometry.Dir2 perp = dir.perp();

            BridgeGeometry.SlabPlacement sp = BridgeGeometry.slabPlacementForSurfaceHalfY(deckHalfY[i]);
            IBlockState deckState = withSlabHalf(deckBaseState, sp.top);

            boolean interior = i > 0 && i < span.centerline.size() - 1;

            placeStripe(world, c.getX(), c.getZ(), perp, BridgeGeometry.DECK_WIDTH_BLOCKS / 2, sp, deckState, dryRun, sampler, deckXZ, interior);

            if (i > 0) {
                BlockPos p = span.centerline.get(i - 1);
                int stepX = c.getX() - p.getX();
                int stepZ = c.getZ() - p.getZ();

                if (stepX != 0 && stepZ != 0) {
                    boolean interiorDiag = i > 1 && i < span.centerline.size() - 1;
                    fillDiagonalGap(world, p.getX(), p.getZ(), c.getX(), c.getZ(), perp, BridgeGeometry.DECK_WIDTH_BLOCKS / 2, sp, deckState, dryRun, sampler, deckXZ, interiorDiag);
                }
            }
        }

        List<List<BridgeTemplate.BlockEntry>> blocksBySlice = bucketBlocks(template.blocks, template.segmentLength);
        List<java.util.Map<Long, BridgeTemplate.BlockEntry>> blockLookupBySlice = buildBlockLookup(blocksBySlice, template.segmentLength);
        List<List<BridgeTemplate.SupportSeed>> seedsBySlice = bucketSeeds(template.supportSeeds, template.segmentLength);

        LongOpenHashSet placedBlocks = new LongOpenHashSet();
        Long2ObjectOpenHashMap<SupportStart> supportStarts = new Long2ObjectOpenHashMap<SupportStart>();

        for (int i = 0; i < span.centerline.size(); i++) {
            int slice = Math.floorMod(i, template.segmentLength);

            BridgeGeometry.Dir2 dir = BridgeGeometry.localDir(span.centerline, i);
            BridgeGeometry.Dir2 perp = dir.perp();
            BridgeGeometry.SlabPlacement sp = BridgeGeometry.slabPlacementForSurfaceHalfY(deckHalfY[i]);

            List<BridgeTemplate.BlockEntry> sliceBlocks = blocksBySlice.get(slice);
            for (BridgeTemplate.BlockEntry b : sliceBlocks) {
                BlockPos wp = transform(span.centerline, deckHalfY, i, b.lateral, b.relY);

                IBlockState placeState = BridgeBlockStateRotation.rotateForPlacement(
                        b.state.toState(),
                        placeCtx
                );

                placeTemplateBlockOnce(world, wp, placeState, placedBlocks, dryRun);

                if (b.relY == -1 && sp.top) {
                    fillTopSlabGap(world, wp.up(), deckBaseState, dryRun);
                }

                if (i + 1 < span.centerline.size()) {
                    int nextSlice = (slice + 1) % template.segmentLength;
                    BridgeTemplate.BlockEntry nextLocal = blockLookupBySlice.get(nextSlice).get(localRibbonKey(b.lateral, b.relY));

                    if (nextLocal != null) {
                        IBlockState nextState = BridgeBlockStateRotation.rotateForPlacement(
                                nextLocal.state.toState(),
                                placeCtx
                        );

                        if (sameState(placeState, nextState)) {
                            BlockPos nextWp = transform(span.centerline, deckHalfY, i + 1, nextLocal.lateral, nextLocal.relY);

                            // Fill both diagonal corners only when the block is flanked on both
                            // lateral sides at the same relY (structural fill), not when it is a
                            // standalone 1-block-wide element like a barrier or railing (zigzag).
                            boolean flankedCurrent =
                                    blockLookupBySlice.get(slice).containsKey(localRibbonKey(b.lateral - 1, b.relY)) &&
                                    blockLookupBySlice.get(slice).containsKey(localRibbonKey(b.lateral + 1, b.relY));
                            boolean flankedNext =
                                    blockLookupBySlice.get(nextSlice).containsKey(localRibbonKey(nextLocal.lateral - 1, nextLocal.relY)) &&
                                    blockLookupBySlice.get(nextSlice).containsKey(localRibbonKey(nextLocal.lateral + 1, nextLocal.relY));
                            boolean fillBothCorners = flankedCurrent || flankedNext;

                            sweepDecorativeRibbon(world, wp, nextWp, placeState, placedBlocks, dryRun, fillBothCorners);
                        }
                    }
                }
            }

            // On diagonal bridges the perp vector is itself diagonal, so adjacent lateral
            // blocks within the same slice are diagonally adjacent in world space — fill both
            // corner cells so arches and piers have no checkerboard gaps.
            fillLateralDiagonalGaps(world, span.centerline, deckHalfY, i, sliceBlocks, placeCtx, placedBlocks, dryRun);

            List<BridgeTemplate.SupportSeed> sliceSeeds = seedsBySlice.get(slice);
            for (BridgeTemplate.SupportSeed s : sliceSeeds) {
                BlockPos wp = transform(span.centerline, deckHalfY, i, s.lateral, s.relY);
                long xz = MPBPos2D.xzKey(wp.getX(), wp.getZ());
                IBlockState state = BridgeBlockStateRotation.rotateForPlacement(
                        s.state.toState(),
                        placeCtx
                );

                SupportStart prev = supportStarts.get(xz);
                if (prev == null || wp.getY() < prev.y) {
                    supportStarts.put(xz, new SupportStart(wp.getY(), state));
                }
            }
        }

        int maxSupportDepth = template.maxSupportDepth != null
                ? Math.max(1, template.maxSupportDepth)
                : Math.max(8, MPBConfig.BRIDGES.maxSupportDepth);
        for (long xz : supportStarts.keySet()) {
            int x = MPBPos2D.xFromKey(xz);
            int z = MPBPos2D.zFromKey(xz);
            SupportStart ss = supportStarts.get(xz);
            extendSupport(world, x, ss.y, z, ss.state, maxSupportDepth, dryRun);
        }

        return true;
    }

    private static List<List<BridgeTemplate.BlockEntry>> bucketBlocks(List<BridgeTemplate.BlockEntry> in, int len) {
        List<List<BridgeTemplate.BlockEntry>> out = new ArrayList<List<BridgeTemplate.BlockEntry>>(len);
        for (int i = 0; i < len; i++) out.add(new ArrayList<BridgeTemplate.BlockEntry>());

        for (BridgeTemplate.BlockEntry b : in) {
            if (b.forward < 0 || b.forward >= len) continue;
            out.get(b.forward).add(b);
        }
        return out;
    }

    private static List<List<BridgeTemplate.SupportSeed>> bucketSeeds(List<BridgeTemplate.SupportSeed> in, int len) {
        List<List<BridgeTemplate.SupportSeed>> out = new ArrayList<List<BridgeTemplate.SupportSeed>>(len);
        for (int i = 0; i < len; i++) out.add(new ArrayList<BridgeTemplate.SupportSeed>());

        for (BridgeTemplate.SupportSeed s : in) {
            if (s.forward < 0 || s.forward >= len) continue;
            out.get(s.forward).add(s);
        }
        return out;
    }

    private static BlockPos transform(List<BlockPos> line,
                                      int[] deckHalfY,
                                      int bridgeIdx,
                                      int lateral,
                                      int relY) {
        BlockPos center = line.get(bridgeIdx);
        BridgeGeometry.Dir2 perp = BridgeGeometry.localDir(line, bridgeIdx).perp();
        BridgeGeometry.SlabPlacement sp = BridgeGeometry.slabPlacementForSurfaceHalfY(deckHalfY[bridgeIdx]);

        return new BlockPos(
                center.getX() + perp.dx * lateral,
                sp.blockY + relY,
                center.getZ() + perp.dz * lateral
        );
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

    private static void placeStripe(WorldServer world,
                                    int cx,
                                    int cz,
                                    BridgeGeometry.Dir2 perp,
                                    int half,
                                    BridgeGeometry.SlabPlacement sp,
                                    IBlockState slab,
                                    boolean dryRun,
                                    TerrainSampler sampler,
                                    LongOpenHashSet deckXZ,
                                    boolean enforceUnderBridgeRoadClearance) {
        for (int o = -half; o <= half; o++) {
            int x = cx + perp.dx * o;
            int z = cz + perp.dz * o;
            placeDeckCell(world, x, z, sp, slab, dryRun, sampler, deckXZ, enforceUnderBridgeRoadClearance);
        }
    }

    private static void fillDiagonalGap(WorldServer world,
                                        int px,
                                        int pz,
                                        int cx,
                                        int cz,
                                        BridgeGeometry.Dir2 perp,
                                        int half,
                                        BridgeGeometry.SlabPlacement sp,
                                        IBlockState slab,
                                        boolean dryRun,
                                        TerrainSampler sampler,
                                        LongOpenHashSet deckXZ,
                                        boolean enforceUnderBridgeRoadClearance) {
        int stepX = Integer.signum(cx - px);
        int stepZ = Integer.signum(cz - pz);

        for (int o = -half; o <= half; o++) {
            int ax = px + perp.dx * o;
            int az = pz + perp.dz * o;

            int bx = cx + perp.dx * o;
            int bz = cz + perp.dz * o;

            int c1x = ax + stepX;
            int c1z = az;
            if (isBetween(c1x, c1z, ax, az, bx, bz)) {
                placeDeckCell(world, c1x, c1z, sp, slab, dryRun, sampler, deckXZ, enforceUnderBridgeRoadClearance);
            }

            int c2x = ax;
            int c2z = az + stepZ;
            if (isBetween(c2x, c2z, ax, az, bx, bz)) {
                placeDeckCell(world, c2x, c2z, sp, slab, dryRun, sampler, deckXZ, enforceUnderBridgeRoadClearance);
            }
        }
    }

    private static boolean isBetween(int x, int z, int x0, int z0, int x1, int z1) {
        int minX = Math.min(x0, x1);
        int maxX = Math.max(x0, x1);
        int minZ = Math.min(z0, z1);
        int maxZ = Math.max(z0, z1);
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    private static void placeDeckCell(WorldServer world,
                                      int x,
                                      int z,
                                      BridgeGeometry.SlabPlacement sp,
                                      IBlockState slab,
                                      boolean dryRun,
                                      TerrainSampler sampler,
                                      LongOpenHashSet deckXZ,
                                      boolean enforceUnderBridgeRoadClearance) {
        long k = MPBPos2D.xzKey(x, z);
        deckXZ.add(k);

        if (dryRun) return;

        if (enforceUnderBridgeRoadClearance && hasRoadUnderDeckTooClose(world, x, z, sp.blockY)) {
            return;
        }

        BlockPos deckPos = new BlockPos(x, sp.blockY, z);
        if (world.getTileEntity(deckPos) != null) return;

        if (!prepareDeckCell(world, deckPos)) return;

        world.setBlockState(deckPos, slab, 3);
        if (sp.top) {
            fillTopSlabGap(world, deckPos, slab, false);
        }
        clearBridgeWalkVolume(world, x, z, sp.blockY + 1, sp.blockY + 3);
    }

    private static boolean prepareDeckCell(WorldServer world, BlockPos pos) {
        IBlockState st = world.getBlockState(pos);
        if (st == null) return true;
        if (world.getTileEntity(pos) != null) return false;
        if (isReplaceableForBridge(world, pos, st)) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
            return true;
        }
        return false;
    }

    private static void placeTemplateBlock(WorldServer world, BlockPos pos, IBlockState state, boolean dryRun) {
        if (dryRun) return;
        if (world.getTileEntity(pos) != null) return;

        IBlockState st = world.getBlockState(pos);
        if (!prepareTemplateCell(world, pos, st)) return;

        world.setBlockState(pos, state, 3);
    }

    private static boolean prepareTemplateCell(WorldServer world, BlockPos pos, IBlockState st) {
        if (st == null) return true;

        Material m = st.getMaterial();
        Block b = st.getBlock();

        if (m == Material.AIR) return true;
        if (world.getTileEntity(pos) != null) return false;
        if (b == Blocks.BEDROCK || b == Blocks.BARRIER) return false;

        if (isReplaceableForBridge(world, pos, st)) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
            return true;
        }

        return false;
    }

    private static boolean isReplaceableForBridge(World world, BlockPos pos, IBlockState st) {
        if (st == null) return true;

        Material m = st.getMaterial();
        Block b = st.getBlock();

        if (m == Material.AIR) return true;
        if (m == Material.WATER || m == Material.ICE) return true;
        if (m == Material.PLANTS || m == Material.VINE) return true;
        if (m.isReplaceable()) return true;
        if (b == Blocks.WATERLILY || b == Blocks.SNOW_LAYER) return true;
        if (b.isLeaves(st, world, pos) || m == Material.LEAVES) return true;
        if (MPBMillPathPalette.isMillPath(b)) return true;

        if (MPBConfig.PAINTING.clearTrees && MPBConfig.PAINTING.clearTreeTrunks) {
            if (b.isWood(world, pos) || m == Material.WOOD) return true;
        }

        return MPBGround.isRoadReplaceableGround(st);
    }

    private static void extendSupport(WorldServer world,
                                      int x,
                                      int y,
                                      int z,
                                      IBlockState state,
                                      int maxDepth,
                                      boolean dryRun) {
        if (dryRun) return;

        int sinkBlocks = Math.max(0, MPBConfig.BRIDGES.supportGroundSinkBlocks);
        int groundSinkLeft = sinkBlocks;

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int d = 1; d <= maxDepth + sinkBlocks; d++) {
            int yy = y - d;
            if (yy <= 1) break;

            p.setPos(x, yy, z);
            IBlockState st = world.getBlockState(p);

            if (world.getTileEntity(p) != null) break;

            // Tree/vegetation: replace with support and keep descending to actual ground
            if (isTreeBlock(world, p, st)) {
                world.setBlockState(p, state, 3);
                continue;
            }

            if (isSolidGroundStop(st)) {
                if (groundSinkLeft <= 0) break;
                Block b = st.getBlock();
                if (b == Blocks.BEDROCK || b == Blocks.BARRIER) break;
                groundSinkLeft--;
                world.setBlockState(p, state, 3);
                continue;
            }

            if (!prepareTemplateCell(world, p, st)) break;
            world.setBlockState(p, state, 3);
        }
    }

    private static boolean isSolidGroundStop(IBlockState st) {
        if (st == null) return true;
        Material m = st.getMaterial();
        return m.isSolid() && m != Material.WATER && m != Material.ICE;
    }

    private static boolean isTreeBlock(World world, BlockPos pos, IBlockState st) {
        if (st == null) return false;
        Block b = st.getBlock();
        Material m = st.getMaterial();
        if (b.isLeaves(st, world, pos) || m == Material.LEAVES) return true;
        if (b.isWood(world, pos) || m == Material.WOOD) return true;
        return false;
    }

    private static boolean hasRoadUnderDeckTooClose(WorldServer world, int x, int z, int deckBlockY) {
        int need = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.underBridgeRoadMinClearanceBlocks);
        if (need <= 0) return false;

        int minY = Math.max(1, deckBlockY - need - 4);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int y = deckBlockY - 1; y >= minY; y--) {
            p.setPos(x, y, z);
            IBlockState st = world.getBlockState(p);
            if (st == null) continue;
            if (!MPBMillPathPalette.isMillPath(st.getBlock())) continue;

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

            if (m == Material.PLANTS || m == Material.VINE || m.isReplaceable() || st.getBlock() == Blocks.SNOW_LAYER) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                continue;
            }

            if (st.getBlock().isLeaves(st, world, p) || m == Material.LEAVES) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                continue;
            }

            if (MPBConfig.PAINTING.clearTrees && MPBConfig.PAINTING.clearTreeTrunks) {
                if (st.getBlock().isWood(world, p) || m == Material.WOOD) {
                    world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
                    continue;
                }
            }

            if (MPBGround.isRoadReplaceableGround(st)) {
                world.setBlockState(p, Blocks.AIR.getDefaultState(), 2);
            }
        }
    }
    private static List<java.util.Map<Long, BridgeTemplate.BlockEntry>> buildBlockLookup(List<List<BridgeTemplate.BlockEntry>> bySlice,
                                                                                         int len) {
        List<java.util.Map<Long, BridgeTemplate.BlockEntry>> out = new ArrayList<java.util.Map<Long, BridgeTemplate.BlockEntry>>(len);

        for (int i = 0; i < len; i++) {
            java.util.Map<Long, BridgeTemplate.BlockEntry> map = new java.util.HashMap<Long, BridgeTemplate.BlockEntry>();
            List<BridgeTemplate.BlockEntry> slice = bySlice.get(i);

            for (BridgeTemplate.BlockEntry b : slice) {
                map.put(localRibbonKey(b.lateral, b.relY), b);
            }

            out.add(map);
        }

        return out;
    }
    private static long localRibbonKey(int lateral, int relY) {
        return (((long) lateral) << 32) ^ (relY & 0xffffffffL);
    }
    private static boolean sameState(IBlockState a, IBlockState b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.getBlock() != b.getBlock()) return false;

        try {
            return a == b || a.equals(b);
        } catch (Throwable ignored) {
            return false;
        }
    }
    private static void placeTemplateBlockOnce(WorldServer world,
                                               BlockPos pos,
                                               IBlockState state,
                                               LongOpenHashSet placedBlocks,
                                               boolean dryRun) {
        long key = pos.toLong();
        if (!placedBlocks.add(key)) return;
        placeTemplateBlock(world, pos, state, dryRun);
    }
    private static void sweepDecorativeRibbon(WorldServer world,
                                              BlockPos start,
                                              BlockPos end,
                                              IBlockState state,
                                              LongOpenHashSet placedBlocks,
                                              boolean dryRun,
                                              boolean fillBothCorners) {
        if (start == null || end == null || state == null) return;

        List<BlockPos> line = buildThinRibbonPath(start, end, fillBothCorners);

        if (line == null || line.isEmpty()) {
            placeTemplateBlockOnce(world, start, state, placedBlocks, dryRun);
            placeTemplateBlockOnce(world, end, state, placedBlocks, dryRun);
            return;
        }

        if (line.size() == 1) {
            placeTemplateBlockOnce(world, new BlockPos(line.get(0).getX(), start.getY(), line.get(0).getZ()), state, placedBlocks, dryRun);
            return;
        }

        for (int idx = 0; idx < line.size(); idx++) {
            BlockPos p = line.get(idx);
            double t = (double) idx / (double) (line.size() - 1);
            int y = (int) Math.round((1.0 - t) * (double) start.getY() + t * (double) end.getY());

            BlockPos wp = new BlockPos(p.getX(), y, p.getZ());
            placeTemplateBlockOnce(world, wp, state, placedBlocks, dryRun);
        }
    }
    private static List<BlockPos> buildThinRibbonPath(BlockPos start, BlockPos end, boolean fillBoth) {
        int dx = end.getX() - start.getX();
        int dz = end.getZ() - start.getZ();

        ArrayList<BlockPos> out = new ArrayList<BlockPos>(4);
        out.add(new BlockPos(start.getX(), 0, start.getZ()));

        if (dx == 0 && dz == 0) {
            return out;
        }

        if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
            if (fillBoth) {
                // Structural element (flanked on both sides): fill both corners for solid coverage.
                out.add(new BlockPos(start.getX() + dx, 0, start.getZ()));
                out.add(new BlockPos(start.getX(), 0, start.getZ() + dz));
            } else {
                // Thin decorative element (barrier, railing): single-corner zigzag stays 1 block wide.
                out.add(new BlockPos(start.getX() + dx, 0, start.getZ()));
            }
            out.add(new BlockPos(end.getX(), 0, end.getZ()));
            return dedupXZ(out);
        }

        List<BlockPos> thin = RasterLine8.raster(
                start.getX(), start.getZ(),
                end.getX(), end.getZ(),
                0
        );
        if (thin == null || thin.isEmpty()) {
            out.add(new BlockPos(end.getX(), 0, end.getZ()));
            return dedupXZ(out);
        }

        return dedupXZ(thin);
    }
    private static List<BlockPos> dedupXZ(List<BlockPos> in) {
        ArrayList<BlockPos> out = new ArrayList<BlockPos>(in.size());
        BlockPos last = null;

        for (BlockPos p : in) {
            if (p == null) continue;
            if (last == null || p.getX() != last.getX() || p.getZ() != last.getZ()) {
                out.add(p);
                last = p;
            }
        }

        return out;
    }

    // Fills diagonal gaps between adjacent lateral positions within the same template slice.
    // On a diagonal bridge the perp vector is itself diagonal, so lateral=L and lateral=L+1 land
    // at diagonally-adjacent world cells, leaving two corner cells.
    //
    // Rule: for the pair (ba, bb), we get corner c1 (step X toward bb) and c2 (step Z, keeps ba's X).
    //   - Don't fill c1 if bb is the outermost block in its direction (no block further beyond bb).
    //     Filling c1 would extend the outer edge outward in X.
    //   - Don't fill c2 if ba is the outermost block in its direction (no block further beyond ba).
    //     Filling c2 would extend the outer edge outward in Z.
    // This keeps outer walls/railings/roofs at their designed 1-block width while filling interior
    // arch and pier cross-sections solid.
    private static void fillLateralDiagonalGaps(WorldServer world,
                                                 List<BlockPos> centerline,
                                                 int[] deckHalfY,
                                                 int idx,
                                                 List<BridgeTemplate.BlockEntry> sliceBlocks,
                                                 BridgeTemplatePlacementContext placeCtx,
                                                 LongOpenHashSet placedBlocks,
                                                 boolean dryRun) {
        if (dryRun) return;
        BridgeGeometry.Dir2 dir = BridgeGeometry.localDir(centerline, idx);
        if (dir.dx == 0 || dir.dz == 0) return; // axis-aligned: no diagonal gap within a stripe

        java.util.Set<Long> present = new java.util.HashSet<>();
        for (BridgeTemplate.BlockEntry be : sliceBlocks) {
            present.add(lateralRelYKey(be.lateral, be.relY));
        }

        int n = sliceBlocks.size();
        for (int a = 0; a < n - 1; a++) {
            BridgeTemplate.BlockEntry ba = sliceBlocks.get(a);
            for (int b = a + 1; b < n; b++) {
                BridgeTemplate.BlockEntry bb = sliceBlocks.get(b);
                if (bb.relY != ba.relY || Math.abs(bb.lateral - ba.lateral) != 1) continue;

                BlockPos pa = transform(centerline, deckHalfY, idx, ba.lateral, ba.relY);
                BlockPos pb = transform(centerline, deckHalfY, idx, bb.lateral, bb.relY);

                int gdx = pb.getX() - pa.getX();
                int gdz = pb.getZ() - pa.getZ();
                if (Math.abs(gdx) != 1 || Math.abs(gdz) != 1) continue;

                int lateralDir = bb.lateral - ba.lateral; // +1 or -1, direction ba→bb

                // ba is outermost if no block exists further away from bb (in the -lateralDir direction)
                boolean baIsOutermost = !present.contains(lateralRelYKey(ba.lateral - lateralDir, ba.relY));
                // bb is outermost if no block exists further away from ba (in the +lateralDir direction)
                boolean bbIsOutermost = !present.contains(lateralRelYKey(bb.lateral + lateralDir, bb.relY));

                BridgeTemplate.BlockEntry inner = Math.abs(ba.lateral) <= Math.abs(bb.lateral) ? ba : bb;
                IBlockState fill = BridgeBlockStateRotation.rotateForPlacement(inner.state.toState(), placeCtx);

                // c1 steps pa in X toward pb — skip when bb is the outermost edge
                if (!bbIsOutermost) {
                    placeTemplateBlockOnce(world, new BlockPos(pa.getX() + gdx, pa.getY(), pa.getZ()), fill, placedBlocks, dryRun);
                }
                // c2 steps pa in Z toward pb, keeping pa's X — skip when ba is the outermost edge
                if (!baIsOutermost) {
                    placeTemplateBlockOnce(world, new BlockPos(pa.getX(), pa.getY(), pa.getZ() + gdz), fill, placedBlocks, dryRun);
                }
            }
        }
    }

    private static long lateralRelYKey(int lateral, int relY) {
        return (((long) lateral) << 32) | (relY & 0xffffffffL);
    }

    // ---- gap fill between foundation/terrain and top-slab deck ----
    //
    // When sp.top=true the deck slab occupies only the upper half of sp.blockY, leaving a
    // 0.5-block air gap against any solid block directly below. We replace the top slab with
    // a filler block so the gap disappears without changing the walkable surface height.
    //
    // Priority: vanilla slab → matching full block (planks, stone…)
    //           modded slab  → double slab of the same mod/type (via ItemSlab reflection)

    private static void fillTopSlabGap(WorldServer world, BlockPos deckPos, IBlockState deckBaseState, boolean dryRun) {
        if (dryRun) return;

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
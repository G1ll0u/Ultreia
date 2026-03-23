package com.jubitus.millpathbuilder.gen.bridge.template;

import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.MPBGround;
import com.jubitus.millpathbuilder.gen.TerrainSampler;
import com.jubitus.millpathbuilder.gen.bridge.BridgeGeometry;
import com.jubitus.millpathbuilder.gen.bridge.BridgeSpan;
import com.jubitus.millpathbuilder.gen.bridge.RasterLine8;
import com.jubitus.millpathbuilder.gen.bridge.SupercoverLine;
import com.jubitus.millpathbuilder.gen.mill.MPBMillPathPalette;
import com.jubitus.millpathbuilder.gen.util.MPBPos2D;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

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
        int maxLen = Math.max(8, MPBConfig.BRIDGES.maxBridgeLenBlocks);
        if (len > maxLen) return false;

        int[] deckHalfY = BridgeGeometry.buildDeckHalfProfile(span, sampler);
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

            placeStripe(world, c.getX(), c.getZ(), perp, 1, sp, deckState, dryRun, sampler, deckXZ, interior);

            if (i > 0) {
                BlockPos p = span.centerline.get(i - 1);
                int stepX = c.getX() - p.getX();
                int stepZ = c.getZ() - p.getZ();

                if (stepX != 0 && stepZ != 0) {
                    boolean interiorDiag = i > 1 && i < span.centerline.size() - 1;
                    fillDiagonalGap(world, p.getX(), p.getZ(), c.getX(), c.getZ(), perp, 1, sp, deckState, dryRun, sampler, deckXZ, interiorDiag);
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
                            BlockPos centerA = span.centerline.get(i);
                            BlockPos centerB = span.centerline.get(i + 1);

                            sweepDecorativeRibbon(world, wp, nextWp, centerA, centerB, placeState, placedBlocks, dryRun);
                        }
                    }
                }
            }

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

        int maxSupportDepth = Math.max(8, MPBConfig.BRIDGES.maxSupportDepth);
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

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int d = 1; d <= maxDepth; d++) {
            int yy = y - d;
            if (yy <= 1) break;

            p.setPos(x, yy, z);
            IBlockState st = world.getBlockState(p);

            if (isSolidGroundStop(st)) break;
            if (world.getTileEntity(p) != null) break;
            if (!prepareTemplateCell(world, p, st)) break;

            world.setBlockState(p, state, 3);
        }
    }

    private static boolean isSolidGroundStop(IBlockState st) {
        if (st == null) return true;
        Material m = st.getMaterial();
        return m.isSolid() && m != Material.WATER && m != Material.ICE;
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
                                              BlockPos centerA,
                                              BlockPos centerB,
                                              IBlockState state,
                                              LongOpenHashSet placedBlocks,
                                              boolean dryRun) {
        if (start == null || end == null || state == null) return;

        List<BlockPos> line = buildThinRibbonPath(start, end, centerA, centerB);

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
    private static List<BlockPos> buildThinRibbonPath(BlockPos start,
                                                      BlockPos end,
                                                      BlockPos centerA,
                                                      BlockPos centerB) {
        int dx = end.getX() - start.getX();
        int dz = end.getZ() - start.getZ();

        ArrayList<BlockPos> out = new ArrayList<BlockPos>(4);
        out.add(new BlockPos(start.getX(), 0, start.getZ()));

        if (dx == 0 && dz == 0) {
            return out;
        }

        if (Math.abs(dx) == 1 && Math.abs(dz) == 1) {
            BlockPos elbow = chooseInnerDiagonalElbow(start, end, centerA, centerB);
            if (elbow != null) {
                out.add(new BlockPos(elbow.getX(), 0, elbow.getZ()));
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


    private static BlockPos chooseInnerDiagonalElbow(BlockPos start,
                                                     BlockPos end,
                                                     BlockPos centerA,
                                                     BlockPos centerB) {
        BlockPos cand1 = new BlockPos(end.getX(), 0, start.getZ());
        BlockPos cand2 = new BlockPos(start.getX(), 0, end.getZ());

        double score1 = inwardScore(cand1, centerA, centerB);
        double score2 = inwardScore(cand2, centerA, centerB);

        if (score1 < score2) return cand1;
        if (score2 < score1) return cand2;

        double d1a = distSqXZ(cand1, centerA);
        double d1b = distSqXZ(cand1, centerB);
        double d2a = distSqXZ(cand2, centerA);
        double d2b = distSqXZ(cand2, centerB);

        double sum1 = d1a + d1b;
        double sum2 = d2a + d2b;

        if (sum1 <= sum2) return cand1;
        return cand2;
    }
    private static double inwardScore(BlockPos p, BlockPos centerA, BlockPos centerB) {
        if (p == null) return Double.POSITIVE_INFINITY;
        if (centerA == null && centerB == null) return 0.0;
        if (centerA == null) return distSqXZ(p, centerB);
        if (centerB == null) return distSqXZ(p, centerA);

        return pointToSegmentDistSqXZ(
                p.getX(), p.getZ(),
                centerA.getX(), centerA.getZ(),
                centerB.getX(), centerB.getZ()
        );
    }
    private static double distSqXZ(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - (long) b.getX();
        long dz = (long) a.getZ() - (long) b.getZ();
        return (double) dx * (double) dx + (double) dz * (double) dz;
    }
    private static double pointToSegmentDistSqXZ(int px, int pz,
                                                 int ax, int az,
                                                 int bx, int bz) {
        long dx = (long) bx - (long) ax;
        long dz = (long) bz - (long) az;
        long lenSq = dx * dx + dz * dz;

        if (lenSq <= 0L) {
            long ex = (long) px - (long) ax;
            long ez = (long) pz - (long) az;
            return (double) ex * (double) ex + (double) ez * (double) ez;
        }

        long apx = (long) px - (long) ax;
        long apz = (long) pz - (long) az;

        double t = (double) (apx * dx + apz * dz) / (double) lenSq;
        if (t < 0.0) t = 0.0;
        else if (t > 1.0) t = 1.0;

        double cx = (double) ax + t * (double) dx;
        double cz = (double) az + t * (double) dz;

        double ddx = (double) px - cx;
        double ddz = (double) pz - cz;
        return ddx * ddx + ddz * ddz;
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
}
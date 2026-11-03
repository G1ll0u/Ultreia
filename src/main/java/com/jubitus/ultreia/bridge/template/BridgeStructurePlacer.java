package com.jubitus.ultreia.bridge.template;

import com.jubitus.ultreia.bridge.BridgeGeometry;
import com.jubitus.ultreia.bridge.BridgeSpan;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.geom.LineRaster;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.terrain.BlockRules;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Repeats the template's slices along the span (walls, railings, arches) and grows its supports down to the ground. */
final class BridgeStructurePlacer {

    // Max vertical distance supports may extend down before giving up.
    private static final int MAX_SUPPORT_DEPTH = 128;
    // How many blocks a support embeds into the ground at its base (realistic abutment anchoring).
    private static final int SUPPORT_GROUND_SINK_BLOCKS = 1;

    private static final class SupportStart {
        final int y;
        final IBlockState state;

        SupportStart(int y, IBlockState state) {
            this.y = y;
            this.state = state;
        }
    }

    private BridgeStructurePlacer() {}

    static void place(WorldServer world, BridgeSpan span, BridgeTemplate template, int[] deckHalfY,
                      IBlockState deckBaseState) {
        BridgeTemplatePlacementContext placeCtx = BridgeTemplatePlacementContext.of(template, span);

        List<List<BridgeTemplate.BlockEntry>> blocksBySlice = bucketBlocks(template.blocks, template.segmentLength);
        List<Map<Long, BridgeTemplate.BlockEntry>> blockLookupBySlice = buildBlockLookup(blocksBySlice, template.segmentLength);
        List<List<BridgeTemplate.SupportSeed>> seedsBySlice = bucketSeeds(template.supportSeeds, template.segmentLength);

        LongOpenHashSet placedBlocks = new LongOpenHashSet();
        Long2ObjectOpenHashMap<SupportStart> supportStarts = new Long2ObjectOpenHashMap<SupportStart>();

        for (int i = 0; i < span.centerline.size(); i++) {
            int slice = Math.floorMod(i, template.segmentLength);

            HalfY.SlabPlacement sp = HalfY.slabPlacement(deckHalfY[i]);

            List<BridgeTemplate.BlockEntry> sliceBlocks = blocksBySlice.get(slice);
            for (BridgeTemplate.BlockEntry b : sliceBlocks) {
                BlockPos wp = BridgeLocalFrame.toWorld(span.centerline, deckHalfY, i, b.lateral, b.relY);

                IBlockState placeState = BridgeBlockStateRotation.rotateForPlacement(
                        b.state.toState(),
                        placeCtx
                );

                placeTemplateBlockOnce(world, wp, placeState, placedBlocks);

                if (b.relY == -1 && sp.top) {
                    BridgeCellRules.fillTopSlabGap(world, wp.up(), deckBaseState);
                }

                if (i + 1 < span.centerline.size()) {
                    int nextSlice = (slice + 1) % template.segmentLength;
                    BridgeTemplate.BlockEntry nextLocal = blockLookupBySlice.get(nextSlice).get(ribbonKey(b.lateral, b.relY));

                    if (nextLocal != null) {
                        IBlockState nextState = BridgeBlockStateRotation.rotateForPlacement(
                                nextLocal.state.toState(),
                                placeCtx
                        );

                        if (sameState(placeState, nextState)) {
                            BlockPos nextWp = BridgeLocalFrame.toWorld(span.centerline, deckHalfY, i + 1, nextLocal.lateral, nextLocal.relY);

                            // Fill both diagonal corners only when the block is flanked on both
                            // lateral sides at the same relY (structural fill), not when it is a
                            // standalone 1-block-wide element like a barrier or railing (zigzag).
                            boolean flankedCurrent =
                                    blockLookupBySlice.get(slice).containsKey(ribbonKey(b.lateral - 1, b.relY)) &&
                                    blockLookupBySlice.get(slice).containsKey(ribbonKey(b.lateral + 1, b.relY));
                            boolean flankedNext =
                                    blockLookupBySlice.get(nextSlice).containsKey(ribbonKey(nextLocal.lateral - 1, nextLocal.relY)) &&
                                    blockLookupBySlice.get(nextSlice).containsKey(ribbonKey(nextLocal.lateral + 1, nextLocal.relY));
                            boolean fillBothCorners = flankedCurrent || flankedNext;

                            sweepDecorativeRibbon(world, wp, nextWp, placeState, placedBlocks, fillBothCorners);
                        }
                    }
                }
            }

            // On diagonal bridges the perp vector is itself diagonal, so adjacent lateral
            // blocks within the same slice are diagonally adjacent in world space — fill both
            // corner cells so arches and piers have no checkerboard gaps.
            fillLateralDiagonalGaps(world, span.centerline, deckHalfY, i, sliceBlocks, placeCtx, placedBlocks);

            List<BridgeTemplate.SupportSeed> sliceSeeds = seedsBySlice.get(slice);
            for (BridgeTemplate.SupportSeed s : sliceSeeds) {
                BlockPos wp = BridgeLocalFrame.toWorld(span.centerline, deckHalfY, i, s.lateral, s.relY);
                long xz = Pos2D.xzKey(wp.getX(), wp.getZ());
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
                : Math.max(8, MAX_SUPPORT_DEPTH);
        for (long xz : supportStarts.keySet()) {
            int x = Pos2D.xFromKey(xz);
            int z = Pos2D.zFromKey(xz);
            SupportStart ss = supportStarts.get(xz);
            extendSupport(world, x, ss.y, z, ss.state, maxSupportDepth);
        }
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

    private static List<Map<Long, BridgeTemplate.BlockEntry>> buildBlockLookup(List<List<BridgeTemplate.BlockEntry>> bySlice,
                                                                               int len) {
        List<Map<Long, BridgeTemplate.BlockEntry>> out = new ArrayList<Map<Long, BridgeTemplate.BlockEntry>>(len);

        for (int i = 0; i < len; i++) {
            Map<Long, BridgeTemplate.BlockEntry> map = new HashMap<Long, BridgeTemplate.BlockEntry>();
            for (BridgeTemplate.BlockEntry b : bySlice.get(i)) {
                map.put(ribbonKey(b.lateral, b.relY), b);
            }
            out.add(map);
        }

        return out;
    }

    /** Key of a template cell within one slice. */
    private static long ribbonKey(int lateral, int relY) {
        return (((long) lateral) << 32) | (relY & 0xffffffffL);
    }

    private static boolean sameState(IBlockState a, IBlockState b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.getBlock() != b.getBlock()) return false;

        try {
            return a.equals(b);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void placeTemplateBlockOnce(WorldServer world,
                                               BlockPos pos,
                                               IBlockState state,
                                               LongOpenHashSet placedBlocks) {
        if (!placedBlocks.add(pos.toLong())) return;
        if (world.getTileEntity(pos) != null) return;
        if (!BridgeCellRules.prepareCell(world, pos, world.getBlockState(pos))) return;
        world.setBlockState(pos, state, 3);
    }

    private static void sweepDecorativeRibbon(WorldServer world,
                                              BlockPos start,
                                              BlockPos end,
                                              IBlockState state,
                                              LongOpenHashSet placedBlocks,
                                              boolean fillBothCorners) {
        if (start == null || end == null || state == null) return;

        List<BlockPos> line = buildThinRibbonPath(start, end, fillBothCorners);

        if (line == null || line.isEmpty()) {
            placeTemplateBlockOnce(world, start, state, placedBlocks);
            placeTemplateBlockOnce(world, end, state, placedBlocks);
            return;
        }

        if (line.size() == 1) {
            placeTemplateBlockOnce(world, new BlockPos(line.get(0).getX(), start.getY(), line.get(0).getZ()), state, placedBlocks);
            return;
        }

        for (int idx = 0; idx < line.size(); idx++) {
            BlockPos p = line.get(idx);
            double t = (double) idx / (double) (line.size() - 1);
            int y = (int) Math.round((1.0 - t) * (double) start.getY() + t * (double) end.getY());

            BlockPos wp = new BlockPos(p.getX(), y, p.getZ());
            placeTemplateBlockOnce(world, wp, state, placedBlocks);
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

        List<BlockPos> thin = LineRaster.eight(
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
                                                LongOpenHashSet placedBlocks) {
        BridgeGeometry.Dir2 dir = BridgeGeometry.localDir(centerline, idx);
        if (dir.dx == 0 || dir.dz == 0) return; // axis-aligned: no diagonal gap within a stripe

        Set<Long> present = new HashSet<>();
        for (BridgeTemplate.BlockEntry be : sliceBlocks) {
            present.add(ribbonKey(be.lateral, be.relY));
        }

        int n = sliceBlocks.size();
        for (int a = 0; a < n - 1; a++) {
            BridgeTemplate.BlockEntry ba = sliceBlocks.get(a);
            for (int b = a + 1; b < n; b++) {
                BridgeTemplate.BlockEntry bb = sliceBlocks.get(b);
                if (bb.relY != ba.relY || Math.abs(bb.lateral - ba.lateral) != 1) continue;

                BlockPos pa = BridgeLocalFrame.toWorld(centerline, deckHalfY, idx, ba.lateral, ba.relY);
                BlockPos pb = BridgeLocalFrame.toWorld(centerline, deckHalfY, idx, bb.lateral, bb.relY);

                int gdx = pb.getX() - pa.getX();
                int gdz = pb.getZ() - pa.getZ();
                if (Math.abs(gdx) != 1 || Math.abs(gdz) != 1) continue;

                int lateralDir = bb.lateral - ba.lateral; // +1 or -1, direction ba→bb

                // ba is outermost if no block exists further away from bb (in the -lateralDir direction)
                boolean baIsOutermost = !present.contains(ribbonKey(ba.lateral - lateralDir, ba.relY));
                // bb is outermost if no block exists further away from ba (in the +lateralDir direction)
                boolean bbIsOutermost = !present.contains(ribbonKey(bb.lateral + lateralDir, bb.relY));

                BridgeTemplate.BlockEntry inner = Math.abs(ba.lateral) <= Math.abs(bb.lateral) ? ba : bb;
                IBlockState fill = BridgeBlockStateRotation.rotateForPlacement(inner.state.toState(), placeCtx);

                // c1 steps pa in X toward pb — skip when bb is the outermost edge
                if (!bbIsOutermost) {
                    placeTemplateBlockOnce(world, new BlockPos(pa.getX() + gdx, pa.getY(), pa.getZ()), fill, placedBlocks);
                }
                // c2 steps pa in Z toward pb, keeping pa's X — skip when ba is the outermost edge
                if (!baIsOutermost) {
                    placeTemplateBlockOnce(world, new BlockPos(pa.getX(), pa.getY(), pa.getZ() + gdz), fill, placedBlocks);
                }
            }
        }
    }

    private static void extendSupport(WorldServer world,
                                      int x,
                                      int y,
                                      int z,
                                      IBlockState state,
                                      int maxDepth) {
        int sinkBlocks = Math.max(0, SUPPORT_GROUND_SINK_BLOCKS);
        int groundSinkLeft = sinkBlocks;

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int d = 1; d <= maxDepth + sinkBlocks; d++) {
            int yy = y - d;
            if (yy <= 1) break;

            p.setPos(x, yy, z);
            IBlockState st = world.getBlockState(p);

            if (world.getTileEntity(p) != null) break;

            // Tree/vegetation: replace with support and keep descending to actual ground
            if (BlockRules.isFoliage(world, p, st)
                    || (BlockRules.treesClearable() && BlockRules.isTreeWood(world, p, st))) {
                world.setBlockState(p, state, 3);
                continue;
            }

            if (BridgeCellRules.isSolidGroundStop(st)) {
                if (groundSinkLeft <= 0) break;
                Block b = st.getBlock();
                if (b == Blocks.BEDROCK || b == Blocks.BARRIER) break;
                groundSinkLeft--;
                world.setBlockState(p, state, 3);
                continue;
            }

            if (!BridgeCellRules.prepareCell(world, p, st)) break;
            world.setBlockState(p, state, 3);
        }
    }
}

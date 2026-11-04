package com.jubitus.ultreia.paint;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.bridge.BridgeSegmentPainter;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.mill.MillPathBlocks;
import com.jubitus.ultreia.roads.RoadIndex;
import com.jubitus.ultreia.terrain.BlockRules;
import com.jubitus.ultreia.terrain.ColumnSurface;
import com.jubitus.ultreia.terrain.ProtectedAreas;
import com.jubitus.ultreia.terrain.TerrainSampler;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Puts one road block in the world: grading around the centerline, headroom and tree clearing, support check,
 * path kind, slab or full block. Also paints the cells planned outside the sweep (elbow fills, merge connector,
 * forced leftovers) and keeps the per-edge paint diagnostics (DEBUG.logPainting).
 */
final class RoadCellPainter {

    // Shoulder width used to taper earthworks away from the road surface.
    private static final int EARTHWORKS_SHOULDER = 3;

    private final WorldServer world;
    private final TerrainSampler sampler;
    @Nullable private final ProtectedAreas protectedAreas;
    @Nullable private final RoadIndex roadIndex;
    private final BridgeSegmentPainter bridges;
    private final List<BlockPos> path;

    /** Half width of the road (blocks beside the centerline). */
    int roadHalfWidth;
    /** Footprint read for planned heights; set once the planner exists. */
    RoadFootprintPlanner footprint;

    /** Cells already handled by this edge (painted or skipped for good). */
    final LongOpenHashSet painted = new LongOpenHashSet();
    /** Road blocks this edge placed (also marked in the road index). */
    final LongOpenHashSet newlyPlaced = new LongOpenHashSet();
    // Paint diagnostics (DEBUG.logPainting): one summary line per edge.
    private final Long2IntOpenHashMap placedHalf = new Long2IntOpenHashMap();
    private int dbgForced, dbgForcedNoFit, dbgNoSupport, dbgDtRoot;
    private final StringBuilder dbgForcedNoFitAt = new StringBuilder(), dbgNoSupportAt = new StringBuilder(), dbgDtRootAt = new StringBuilder();

    RoadCellPainter(WorldServer world, TerrainSampler sampler, @Nullable ProtectedAreas protectedAreas,
                    @Nullable RoadIndex roadIndex, BridgeSegmentPainter bridges, List<BlockPos> path, int roadHalfWidth) {
        this.world = world;
        this.sampler = sampler;
        this.protectedAreas = protectedAreas;
        this.roadIndex = roadIndex;
        this.bridges = bridges;
        this.path = path;
        this.roadHalfWidth = roadHalfWidth;
    }

    /** One cell of the sweep around path index {@code idx}: painted when planned and paintable, once. */
    void paintSweepCell(int xx, int zz, int idx) {
        if (!footprint.shouldPaintCell(xx, zz)) {
            return;
        }

        long k = Pos2D.xzKey(xx, zz);
        if (!painted.add(k)) {
            return;
        }
        if (bridges.isBridgeCell(xx, zz)) {
            return;
        }

        TerrainSampler.Sample s = sampler.sample(xx, zz);
        if (s == null) {
            return;
        }
        if (sampler.forbiddenHard(s)) {
            return;
        }
        if (s.isOcean || s.isWater) {
            return;
        }
        if (sampler.forbidden(s, TerrainSampler.Mode.PAINTING)) {
            return;
        }
        if (protectedAreas != null && protectedAreas.contains(xx, zz)) {
            return;
        }

        paintSingle(xx, zz, s.surfaceY, idx);
    }

    void paintElbowFills(int centerIdx) {
        if (footprint.elbowFillList == null) return;
        for (int[] ef : footprint.elbowFillList) {
            long k = Pos2D.xzKey(ef[0], ef[1]);
            if (!painted.contains(k)) {
                paintSingle(ef[0], ef[1], ef[2], centerIdx);
                painted.add(k);
            }
        }
    }

    void paintMergeConnector(int centerIdx) {
        if (footprint.mergeConnectorList == null) return;
        for (int[] cell : footprint.mergeConnectorList) {
            long k = Pos2D.xzKey(cell[0], cell[1]);
            if (!painted.contains(k)) {
                if (cell.length > 3) {
                    footprint.plannedHalfByXZ.put(k, cell[3]);
                }
                paintSingle(cell[0], cell[1], cell[2], centerIdx);
                painted.add(k);
            }
        }
    }

    /**
     * Forced cells (junction disks, gap connectors, bridge exits) can lie outside the sweep radius.
     * Each takes the terrain height clamped to within half a block of every placed or planned
     * 4-neighbour, spreading outward; a cell with no neighbour or no such height stays unpainted.
     */
    void paintForcedLeftovers(int centerIdx) {
        LongArrayList pending = new LongArrayList();
        for (long k : footprint.forcePaint) {
            if (!painted.contains(k)) pending.add(k);
        }
        boolean progress = true;
        while (progress && !pending.isEmpty()) {
            progress = false;
            for (int pi = pending.size() - 1; pi >= 0; pi--) {
                long k = pending.getLong(pi);
                int x = Pos2D.xFromKey(k);
                int z = Pos2D.zFromKey(k);
                int half = walkableForcedHalf(x, z);
                if (half == Integer.MIN_VALUE) continue;
                dbgForced++;
                footprint.plannedHalfByXZ.put(k, half);
                paintSingle(x, z, sampler.sample(x, z).surfaceY, centerIdx);
                painted.add(k);
                pending.removeLong(pi);
                progress = true;
            }
        }
        dbgForcedNoFit += pending.size();
        for (int pi = 0; pi < pending.size(); pi++) {
            long k = pending.getLong(pi);
            dbgSample(dbgForcedNoFitAt, pi + 1, Pos2D.xFromKey(k),
                    HalfY.ofBlockTop(sampler.sample(Pos2D.xFromKey(k), Pos2D.zFromKey(k)).surfaceY),
                    Pos2D.zFromKey(k));
        }
    }

    // INVARIANT: a forced cell never steps more than half a block from a placed or planned 4-neighbour.
    private int walkableForcedHalf(int x, int z) {
        int lo = Integer.MIN_VALUE;
        int hi = Integer.MAX_VALUE;
        int[][] n4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : n4) {
            long nk = Pos2D.xzKey(x + d[0], z + d[1]);
            int h = placedHalf.containsKey(nk) ? placedHalf.get(nk) : footprint.plannedHalfByXZ.get(nk);
            if (h == Integer.MIN_VALUE) continue;
            lo = Math.max(lo, h - HalfY.MAX_ROAD_STEP_HALF_PER_BLOCK);
            hi = Math.min(hi, h + HalfY.MAX_ROAD_STEP_HALF_PER_BLOCK);
        }
        if (lo == Integer.MIN_VALUE || lo > hi) return Integer.MIN_VALUE;
        int terrain = HalfY.ofBlockTop(sampler.sample(x, z).surfaceY);
        return Math.max(lo, Math.min(hi, terrain));
    }

    private static void dbgSample(StringBuilder sb, int count, int x, int half, int z) {
        if (count <= 5) sb.append(" (").append(x).append(',').append(HalfY.walkBlockYFloor(half)).append(',').append(z).append(')');
    }

    /** One summary line per edge: suspicious side cells and steps above half a block between painted cells. */
    void logPaintSummary() {
        if (!UltreiaConfig.DEBUG.logPainting) return;
        int steps = 0;
        StringBuilder stepAt = new StringBuilder();
        for (Long2IntMap.Entry e : placedHalf.long2IntEntrySet()) {
            int x = Pos2D.xFromKey(e.getLongKey());
            int z = Pos2D.zFromKey(e.getLongKey());
            int h = e.getIntValue();
            int[][] n2 = {{1, 0}, {0, 1}};
            for (int[] d : n2) {
                long nk = Pos2D.xzKey(x + d[0], z + d[1]);
                if (!placedHalf.containsKey(nk)) continue;
                if (Math.abs(placedHalf.get(nk) - h) > 1) {
                    steps++;
                    dbgSample(stepAt, steps, x, Math.max(h, placedHalf.get(nk)), z);
                }
            }
        }
        Ultreia.LOGGER.info("[Ultreia paint] edge done: placed={} stepsOverHalf={}{} forced={} forcedNoWalkableHeight={}{}"
                        + " skippedNoSupport={}{} dtRootRepaints={}{}",
                placedHalf.size(), steps, stepAt, dbgForced, dbgForcedNoFit, dbgForcedNoFitAt,
                dbgNoSupport, dbgNoSupportAt, dbgDtRoot, dbgDtRootAt);
    }

    /** {@code centerIdx}: path index whose center the cell is graded around; outside the path, no earthworks. */
    private void paintSingle(int x, int z, int surfaceY, int centerIdx) {
        if (bridges.isBridgeCell(x, z)) return;
        // INVARIANT: no painting, clearing or earthworks inside protected areas or on Millénaire paths.
        if (protectedAreas != null && protectedAreas.contains(x, z)) return;
        // Never touch any column that belongs to an existing bridge volume (deck, supports, lateral blocks).
        if (roadIndex != null && roadIndex.isBridgeVolumeXZ(x, z)) return;
        if (roadIndex != null && roadIndex.isBridgeXZ(x, z)) return;

        long k = Pos2D.xzKey(x, z);
        int targetHalf = footprint.plannedHalfByXZ.get(k);
        if (targetHalf == Integer.MIN_VALUE) {
            targetHalf = HalfY.ofBlockTop(surfaceY);
        }

        HalfY.SlabPlacement sp = HalfY.slabPlacement(targetHalf);
        int supportTopY = HalfY.supportTopY(targetHalf);

        if (columnHasAnyMillPath(new BlockPos(x, sp.blockY, z))) {
            if (roadIndex != null) roadIndex.markRoad(x, z);
            return;
        }

        boolean earthworks = centerIdx >= 0 && centerIdx < path.size();

        boolean nearNoPaint = protectedAreas != null && protectedAreas.containsWithin(x, z, 1);
        if (nearNoPaint) {
            earthworks = false;
        }

        if (earthworks) {
            int rr = roadHalfWidth;
            int shoulder = Math.max(0, EARTHWORKS_SHOULDER);
            Earthworks.Settings st = new Earthworks.Settings(
                    rr,
                    shoulder,
                    Math.max(0, UltreiaConfig.PAINTING.earthworksMaxCut),
                    Math.max(0, UltreiaConfig.PAINTING.earthworksMaxFill)
            );

            int cx = path.get(centerIdx).getX();
            int cz = path.get(centerIdx).getZ();

            int dx0 = x - cx;
            int dz0 = z - cz;
            int dist = (int) Math.round(Math.sqrt((double) dx0 * (double) dx0 + (double) dz0 * (double) dz0));
            // A painted cell is road surface: graded to the road height; the talus pass shapes beyond it.
            dist = Math.min(dist, rr);

            Earthworks.gradeCell(world, sampler, x, z, supportTopY, dist, st);
            sampler.invalidateAround(x, z, 1);
        }

        BlockPos supportTop = new BlockPos(x, supportTopY, z);

        if (!nearNoPaint) {
            // Always allow clearing solid natural blocks above the road surface for proper headroom.
            HeadroomClearer.clearAtAndAbove(world, supportTop.up(), 2);
        }

        if (!hasSupportWithinOneBlock(supportTop)) {
            dbgNoSupport++;
            dbgSample(dbgNoSupportAt, dbgNoSupport, x, targetHalf, z);
            return;
        }

        PathPalette.Kind kind = PathPalette.pickKindAveraged(world, sampler, roadIndex, x, z);
        placeRoadBlockFromHalf(supportTop, kind, targetHalf, centerIdx);
    }

    private void placeRoadBlockFromHalf(BlockPos supportTop, PathPalette.Kind kind, int targetHalf, int centerIdx) {
        int x = supportTop.getX();
        int z = supportTop.getZ();

        HalfY.SlabPlacement sp = HalfY.slabPlacement(targetHalf);
        boolean useBottomSlab = HalfY.isBottomSlab(targetHalf);

        BlockPos placePos = new BlockPos(x, sp.blockY, z);

        if (useBottomSlab) {
            ensureSolidSupportUnderBottomSlab(placePos.down(), kind);
        }

        // Always allow clearing solid natural blocks when placing the road block and clearing headroom.
        HeadroomClearer.clearAtAndAbove(world, placePos, 2);

        IBlockState place = useBottomSlab
                ? MillPathBlocks.slabBottom(kind)
                : MillPathBlocks.full(kind);

        world.setBlockState(placePos, place, 2);
        placedHalf.put(Pos2D.xzKey(x, z), targetHalf);

        HeadroomClearer.clearRoadColumn(world, placePos, 3);

        if (BlockRules.treesClearable()) {
            BlockPos dtRootPos = HeadroomClearer.clearTreesAbove(world, placePos, 4);
            // If a whole DT tree was removed, paint a road block at the rootydirt position
            // (now plain dirt) so no bare earth patch is left where the tree stood.
            if (dtRootPos != null) {
                dbgDtRoot++;
                dbgSample(dbgDtRootAt, dbgDtRoot, dtRootPos.getX(), dtRootPos.getY() * 2, dtRootPos.getZ());
                paintSingle(dtRootPos.getX(), dtRootPos.getZ(), dtRootPos.getY(), centerIdx);
                sampler.invalidateAround(dtRootPos.getX(), dtRootPos.getZ(), 1);
            }
        }

        if (roadIndex != null) {
            roadIndex.markRoad(x, z);
            newlyPlaced.add(Pos2D.xzKey(x, z));
        }

        sampler.invalidateAround(x, z, 1);
    }

    // INVARIANT: a road block never floats: at most one block is filled under it, else the cell is skipped.
    private boolean hasSupportWithinOneBlock(BlockPos supportTop) {
        IBlockState ground = world.getBlockState(supportTop);
        if (BlockRules.isRoadReplaceableGround(ground)) return true;
        Material m = ground.getMaterial();
        if (m == Material.WATER) return true; // small streams are paved over
        if (m == Material.LAVA || !m.isReplaceable()) return false;
        return world.getBlockState(supportTop.down()).getMaterial().isSolid();
    }

    private void ensureSolidSupportUnderBottomSlab(BlockPos supportPos, PathPalette.Kind kind) {
        IBlockState st = world.getBlockState(supportPos);
        Material m = st.getMaterial();

        if (m.isSolid() && m != Material.LEAVES && m != Material.WOOD) return;
        if (world.getTileEntity(supportPos) != null) return;

        IBlockState support = PathPalette.supportBlock(world, supportPos, kind);
        world.setBlockState(supportPos, support, 3);
    }

    /** A Millénaire or Ultreïa path block already sits in this column, near this height. */
    boolean columnHasAnyMillPath(BlockPos roadBlock) {
        return ColumnSurface.findPathY(world, new BlockPos.MutableBlockPos(), roadBlock.getX(), roadBlock.getZ(),
                roadBlock.getY(), false) != ColumnSurface.NO_PATH;
    }
}

package com.jubitus.ultreia.paint;

import com.jubitus.ultreia.bridge.BridgeDeckProfile;
import com.jubitus.ultreia.terrain.ColumnSurface;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.geom.LineRaster;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.roads.RoadJoins;
import com.jubitus.ultreia.bridge.BridgeGeometry;
import com.jubitus.ultreia.bridge.BridgeSegmentPainter;
import com.jubitus.ultreia.bridge.BridgeSpan;
import com.jubitus.ultreia.terrain.BlockRules;
import com.jubitus.ultreia.terrain.ProtectedAreas;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.route.RoadType;
import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.bridge.template.BridgeBuildService;
import com.jubitus.ultreia.bridge.template.BridgeTemplate;
import com.jubitus.ultreia.bridge.template.BridgeTemplateRegistry;
import com.jubitus.ultreia.mill.MillPathBlocks;
import com.jubitus.ultreia.roads.RoadIndex;
import com.jubitus.ultreia.roads.RoadNetwork;
import com.jubitus.ultreia.roads.RoadSegment;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


import java.util.HashMap;
import java.util.Map;


public final class RoadPainter {

    // Shoulder width used to taper earthworks away from the road surface.
    private static final int EARTHWORKS_SHOULDER = 3;
    // Force-paint this many blocks after bridge exit to secure road continuation.
    static final int FORCE_PAINT_EXIT_BLOCKS = 2;


    public enum PaintResult { RUNNING, OK, FAILED }

    static final class ForcedBridgeRef {
        final BridgeSpan span;
        final int startIdx;
        final int endIdx;

        ForcedBridgeRef(BridgeSpan span, int startIdx, int endIdx) {
            this.span = span;
            this.startIdx = startIdx;
            this.endIdx = endIdx;
        }
    }

    public static final class Session {
        private int baseR;
        private final WorldServer world;
        private final TerrainSampler sampler;
        private final ProtectedAreas protectedAreas;

        private RoadType roadType = RoadType.VILLAGE_MAIN;

        private final List<BridgeSpan> forcedBridges;
        private final BridgeSegmentPainter bridges;
        private final RoadCenterline line;
        private final List<BlockPos> path;

        private final Map<Long, ForcedBridgeRef> forcedByStartKey = new HashMap<>();

        private boolean densifyDone;

        private int i = 0;
        private boolean paintingCell = false;
        private BlockPos curCenter = null;
        private int curDx = 0;
        private int curDz = 0;

        private boolean failed = false;
        private String failReason = "unknown";

        private boolean profilesReady = false;
        private boolean planBuilt = false;
        private boolean planPruned = false;

        private final it.unimi.dsi.fastutil.longs.LongOpenHashSet painted = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();

        private final RoadIndex roadIndex;
        private final it.unimi.dsi.fastutil.longs.LongOpenHashSet newlyPlaced = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        // Paint diagnostics (DEBUG.logPainting): one summary line per edge.
        private final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap placedHalf = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        private int dbgForced, dbgForcedNoFit, dbgNoSupport, dbgDtRoot;
        private final StringBuilder dbgForcedNoFitAt = new StringBuilder(), dbgNoSupportAt = new StringBuilder(), dbgDtRootAt = new StringBuilder();
        @javax.annotation.Nullable private RoadNetwork roadNetwork;
        @javax.annotation.Nullable private net.minecraft.util.math.BlockPos fromVillage;
        @javax.annotation.Nullable private net.minecraft.util.math.BlockPos toVillage;

        private boolean talusPassDone = false;

        private boolean elbowFillsPainted = false;
        private boolean forcedLeftoversPainted = false;
        // Talus work list, built once at the end of the edge and applied over several ticks.
        private long[] talusCells = null;
        private int talusCursor = 0;
        private it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap talusDist;
        private it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap talusNearestRoad;
        private it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap roadSurfaceY;
        private double talusSlopeRatio;
        private int talusMaxCut, talusMaxFill;

        private final RoadMergePlanner merge;

        private boolean mergeConnectorPainted = false;

        private it.unimi.dsi.fastutil.longs.LongSet reusedBridgeXZ = it.unimi.dsi.fastutil.longs.LongSets.EMPTY_SET;

        private BridgeSpan pendingBridge = null;
        private int pendingBridgeEndIdx = -1;

        private final RoadFootprintPlanner footprint;

        Session(WorldServer world,
                List<BlockPos> coarsePath,
                List<BridgeSpan> forcedBridges,
                TerrainSampler sampler,
                ProtectedAreas protectedAreas,
                RoadIndex roadIndex) {
            this.world = world;
            this.sampler = sampler;
            this.protectedAreas = protectedAreas;
            this.roadIndex = roadIndex;
            this.forcedBridges = forcedBridges == null ? Collections.<BridgeSpan>emptyList() : forcedBridges;
            this.bridges = new BridgeSegmentPainter(world, sampler, protectedAreas, roadIndex, this.forcedBridges);

            this.baseR = roadType.roadWidthBlocks() / 2;

            this.line = new RoadCenterline(coarsePath == null ? Collections.<BlockPos>emptyList() : coarsePath, sampler);
            this.path = line.points;
            this.densifyDone = path.isEmpty();
            this.merge = new RoadMergePlanner(world, sampler, protectedAreas, roadIndex, path, newlyPlaced);
            this.footprint = new RoadFootprintPlanner(world, sampler, protectedAreas, roadIndex, bridges, line, merge, newlyPlaced);
        }

        public PaintResult advance(long deadlineNs) {
            if (failed) return PaintResult.FAILED;

            while (System.nanoTime() < deadlineNs) {

                if (!densifyDone) {
                    if (!line.densifyStep(deadlineNs)) return PaintResult.RUNNING;
                    densifyDone = true;
                    if (line.failReason() != null) {
                        failed = true;
                        failReason = line.failReason();
                    }

                    indexForcedBridges();
                    bridges.buildFootprint();

                    merge.detect(roadType, baseR, roadNetwork, toVillage, reusedBridgeXZ, forcedByStartKey.values());
                    continue;
                }

                if (!profilesReady) {
                    profilesReady = true;
                    if (!line.buildProfiles(forcedBridges, baseR)) {
                        failed = true;
                        failReason = line.failReason();
                        return PaintResult.FAILED;
                    }
                    continue;
                }

                if (!planBuilt) {
                    if (!footprint.buildPlanStep(deadlineNs, baseR)) return PaintResult.RUNNING;
                    planBuilt = true;
                    if (baseR == 0) {
                        footprint.addDiagonalElbowFills();
                    }
                    continue;
                }

                if (!planPruned) {
                    footprint.pruneAndReinforce(baseR);
                    planPruned = true;
                    continue;
                }

                if (merge.active && i > merge.hitIndex) {
                    if (pendingBridge != null) finishPendingBridge(" at end of edge", false);
                    if (!mergeConnectorPainted) {
                        mergeConnectorPainted = true;
                        paintMergeConnectorDirect();
                    }
                    if (!elbowFillsPainted) {
                        elbowFillsPainted = true;
                        paintElbowFillsDirect();
                    }
                    if (!forcedLeftoversPainted) {
                        forcedLeftoversPainted = true;
                        paintForcedLeftovers();
                    }
                    if (!talusPassDone) {
                        if (!talusStep(deadlineNs)) return PaintResult.RUNNING;
                        talusPassDone = true;
                    }
                    logPaintSummary();
                    registerRoadSegment();
                    return PaintResult.OK;
                }

                if (i >= path.size()) {
                    if (pendingBridge != null) finishPendingBridge(" at final path end", false);
                    if (!elbowFillsPainted) {
                        elbowFillsPainted = true;
                        paintElbowFillsDirect();
                    }
                    if (!forcedLeftoversPainted) {
                        forcedLeftoversPainted = true;
                        paintForcedLeftovers();
                    }
                    if (!talusPassDone) {
                        if (!talusStep(deadlineNs)) return PaintResult.RUNNING;
                        talusPassDone = true;
                    }
                    logPaintSummary();
                    registerRoadSegment();
                    return PaintResult.OK;
                }

                if (paintingCell) {
                    if (!paintCells(deadlineNs)) return PaintResult.RUNNING;
                    paintingCell = false;
                    curCenter = null;
                    i++;
                    continue;
                }

                BlockPos p = path.get(i);

                if (pendingBridge != null) {
                    if (i >= pendingBridgeEndIdx) {
                        finishPendingBridge("", true);
                        i++;
                        continue;
                    }
                }

                ForcedBridgeRef forced = forcedByStartKey.get(Pos2D.xzKey(p.getX(), p.getZ()));
                if (forced != null && forced.startIdx == i) {
                    pendingBridge = forced.span;
                    pendingBridgeEndIdx = forced.endIdx;
                    i++;
                    continue;
                }

                beginPaintSweep(p);
            }

            return PaintResult.RUNNING;
        }


        public String failureReason() {
            return failReason;
        }

        /** Must be called before advance(): deck centerline xz keys of built bridges the path crosses. */
        public void setReusedBridges(it.unimi.dsi.fastutil.longs.LongSet deckXZ) {
            this.reusedBridgeXZ = deckXZ;
        }

        public void setRoadNetwork(@javax.annotation.Nullable RoadNetwork network,
                                   @javax.annotation.Nullable net.minecraft.util.math.BlockPos from,
                                   @javax.annotation.Nullable net.minecraft.util.math.BlockPos to) {
            this.roadNetwork = network;
            this.fromVillage = from;
            this.toVillage = to;
        }

        /**
         * Must be called before advance(). Adjusts road width and earthworks behaviour
         * based on the road importance type.
         */
        public void setRoadType(RoadType type) {
            this.roadType = (type != null) ? type : RoadType.VILLAGE_MAIN;
            this.baseR = this.roadType.roadWidthBlocks() / 2;
        }

        private int paintedWidthBlocks() {
            return baseR * 2 + 1;
        }

        private void indexForcedBridges() {
            forcedByStartKey.clear();
            for (BridgeSpan span : forcedBridges) {
                if (span == null) continue;
                int s = line.indexOf(span.start.getX(), span.start.getZ(), 0);
                int e = line.indexOf(span.end.getX(), span.end.getZ(), Math.max(0, s));
                if (s < 0 || e < 0 || e <= s) {
                    Ultreia.LOGGER.warn("Skipping forced bridge span: unresolved indices start=({}, {}) end=({}, {}) s={} e={}",
                            span.start.getX(), span.start.getZ(),
                            span.end.getX(), span.end.getZ(),
                            s, e);
                    continue;
                }
                forcedByStartKey.put(Pos2D.xzKey(span.start.getX(), span.start.getZ()), new ForcedBridgeRef(span, s, e));
            }
        }

        private int centerXAt(int idx) {
            return path.get(idx).getX();
        }

        private int centerZAt(int idx) {
            return path.get(idx).getZ();
        }

        private void paintElbowFillsDirect() {
            if (footprint.elbowFillList == null) return;
            for (int[] ef : footprint.elbowFillList) {
                long k = Pos2D.xzKey(ef[0], ef[1]);
                if (!painted.contains(k)) {
                    paintSingle(ef[0], ef[1], ef[2], i);
                    painted.add(k);
                }
            }
        }

        /**
         * Forced cells (junction disks, gap connectors, bridge exits) can lie outside the sweep radius.
         * Each takes the terrain height clamped to within half a block of every placed or planned
         * 4-neighbour, spreading outward; a cell with no neighbour or no such height stays unpainted.
         */
        private void paintForcedLeftovers() {
            it.unimi.dsi.fastutil.longs.LongArrayList pending = new it.unimi.dsi.fastutil.longs.LongArrayList();
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
                    paintSingle(x, z, sampler.sample(x, z).surfaceY, i);
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
        private void logPaintSummary() {
            if (!UltreiaConfig.DEBUG.logPainting) return;
            int steps = 0;
            StringBuilder stepAt = new StringBuilder();
            for (it.unimi.dsi.fastutil.longs.Long2IntMap.Entry e : placedHalf.long2IntEntrySet()) {
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

        private void paintMergeConnectorDirect() {
            if (footprint.mergeConnectorList == null) return;
            for (int[] cell : footprint.mergeConnectorList) {
                long k = Pos2D.xzKey(cell[0], cell[1]);
                if (!painted.contains(k)) {
                    if (cell.length > 3) {
                        footprint.plannedHalfByXZ.put(k, cell[3]);
                    }
                    paintSingle(cell[0], cell[1], cell[2], i);
                    painted.add(k);
                }
            }
        }



        private boolean paintCells(long deadlineNs) {
            while (System.nanoTime() < deadlineNs) {
                if (curCenter == null) return true;

                int idx = i;
                int localR = baseR;

                if (curDx > localR) {
                    return true;
                }

                int localDx = curDx;
                int localDz = curDz;

                advancePaintCursor(localR);

                int cx = centerXAt(idx);
                int cz = centerZAt(idx);

                int xx = cx + localDx;
                int zz = cz + localDz;

                if (!footprint.shouldPaintCell(xx, zz)) {
                    continue;
                }

                long k = Pos2D.xzKey(xx, zz);
                if (!painted.add(k)) {
                    continue;
                }
                if (bridges.isBridgeCell(xx, zz)) {
                    continue;
                }

                TerrainSampler.Sample s = sampler.sample(xx, zz);
                if (s == null) {
                    continue;
                }
                if (sampler.forbiddenHard(s)) {
                    continue;
                }
                if (s.isOcean || s.isWater) {
                    continue;
                }
                if (sampler.forbidden(s, TerrainSampler.Mode.PAINTING)) {
                    continue;
                }
                if (protectedAreas != null && protectedAreas.contains(xx, zz)) {
                    continue;
                }

                paintSingle(xx, zz, s.surfaceY, idx);
            }

            return false;
        }



        private void beginPaintSweep(BlockPos center) {
            this.curCenter = center;

            int localR = baseR;
            this.curDx = -localR;
            this.curDz = -localR;
            this.paintingCell = true;
        }

        private void advancePaintCursor(int localR) {
            curDz++;
            if (curDz > localR) {
                curDz = -localR;
                curDx++;
            }
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
                int rr = baseR;
                int shoulder = Math.max(0, EARTHWORKS_SHOULDER);
                Earthworks.Settings st = new Earthworks.Settings(
                        rr,
                        shoulder,
                        Math.max(0, UltreiaConfig.PAINTING.earthworksMaxCut),
                        Math.max(0, UltreiaConfig.PAINTING.earthworksMaxFill)
                );

                int cx = centerXAt(centerIdx);
                int cz = centerZAt(centerIdx);

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
                net.minecraft.util.math.BlockPos dtRootPos =
                        HeadroomClearer.clearTreesAbove(world, placePos, 4);
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

        private boolean columnHasAnyMillPath(BlockPos roadBlock) {
            return ColumnSurface.findPathY(world, new BlockPos.MutableBlockPos(), roadBlock.getX(), roadBlock.getZ(),
                    roadBlock.getY(), false) != ColumnSurface.NO_PATH;
        }

        private double effectiveSlopeRatio() {
            boolean isLoneTrail = roadType == RoadType.LONE_TRAIL;
            double ltr = UltreiaConfig.PAINTING.earthworksSlopeRatioLoneTrail;
            if (isLoneTrail && ltr > 0.0) return ltr;
            return UltreiaConfig.PAINTING.earthworksSlopeRatio;
        }

        /**
         * Post-painting pass: extends sloped talus outside the road surface.
         * For each newly-placed road cell, BFS outward to talusRadius and cut/fill
         * terrain to a natural slope rather than leaving vertical walls.
         */
        /** Builds the talus work list once; returns false when there is nothing to do. */
        private boolean prepareTalus() {
            if (newlyPlaced.isEmpty()) return false;

            double slopeRatio = effectiveSlopeRatio();
            int maxCut = Math.max(0, UltreiaConfig.PAINTING.earthworksMaxCut);
            int maxFill = Math.max(0, UltreiaConfig.PAINTING.earthworksMaxFill);
            if (slopeRatio <= 0.0 || (maxCut == 0 && maxFill == 0)) return false;

            int talusRadius = (int) Math.ceil(Math.max(maxCut, maxFill) * slopeRatio) + 1;
            if (talusRadius <= 0) return false;

            // Build road surface Y lookup for newly placed cells.
            roadSurfaceY = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap(newlyPlaced.size());
            roadSurfaceY.defaultReturnValue(Integer.MIN_VALUE);

            for (it.unimi.dsi.fastutil.longs.LongIterator rit = newlyPlaced.iterator(); rit.hasNext(); ) {
                long k = rit.nextLong();
                int rx = Pos2D.xFromKey(k);
                int rz = Pos2D.zFromKey(k);
                TerrainSampler.Sample s = sampler.sample(rx, rz);
                if (s != null) roadSurfaceY.put(k, s.surfaceY);
            }

            // BFS: propagate distance and nearest-road-cell from all newly placed cells.
            talusDist = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
            it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap distMap = talusDist;
            distMap.defaultReturnValue(-1);
            talusNearestRoad = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
            it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap nearestRoad = talusNearestRoad;
            nearestRoad.defaultReturnValue(Long.MIN_VALUE);

            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>(newlyPlaced.size() * 4);

            for (it.unimi.dsi.fastutil.longs.LongIterator rit = newlyPlaced.iterator(); rit.hasNext(); ) {
                long k = rit.nextLong();
                distMap.put(k, 0);
                nearestRoad.put(k, k);
                queue.add(k);
            }

            while (!queue.isEmpty()) {
                long k = queue.poll();
                int d = distMap.get(k);
                if (d >= talusRadius) continue;
                long nr = nearestRoad.get(k);
                int cx = Pos2D.xFromKey(k);
                int cz = Pos2D.zFromKey(k);
                long[] neighbors = {
                    Pos2D.xzKey(cx + 1, cz), Pos2D.xzKey(cx - 1, cz),
                    Pos2D.xzKey(cx, cz + 1), Pos2D.xzKey(cx, cz - 1)
                };
                for (long nk : neighbors) {
                    if (distMap.containsKey(nk)) continue;
                    distMap.put(nk, d + 1);
                    nearestRoad.put(nk, nr);
                    queue.add(nk);
                }
            }

            talusSlopeRatio = slopeRatio;
            talusMaxCut = maxCut;
            talusMaxFill = maxFill;
            talusCells = distMap.keySet().toLongArray();
            talusCursor = 0;
            return true;
        }

        /** Applies the talus within the tick budget; returns true when finished. */
        private boolean talusStep(long deadlineNs) {
            if (talusCells == null) {
                if (!prepareTalus()) return true;
            }
            it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap distMap = talusDist;
            it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap nearestRoad = talusNearestRoad;
            double slopeRatio = talusSlopeRatio;
            int maxCut = talusMaxCut;
            int maxFill = talusMaxFill;
            while (talusCursor < talusCells.length && System.nanoTime() < deadlineNs) {
                long k = talusCells[talusCursor++];
                int d = distMap.get(k);
                if (d <= 0) continue;                // road cell itself
                if (newlyPlaced.contains(k)) continue;

                int tx = Pos2D.xFromKey(k);
                int tz = Pos2D.zFromKey(k);

                if (protectedAreas != null && protectedAreas.contains(tx, tz)) continue;
                if (roadIndex != null && roadIndex.isRoadXZ(tx, tz)) continue;
                if (roadIndex != null && roadIndex.isBridgeVolumeXZ(tx, tz)) continue;

                long nr = nearestRoad.get(k);
                if (nr == Long.MIN_VALUE) continue;
                int roadY = roadSurfaceY.get(nr);
                if (roadY == Integer.MIN_VALUE) continue;
                if (columnHasAnyMillPath(new BlockPos(tx, roadY, tz))) continue;

                int rx = Pos2D.xFromKey(nr);
                int rz = Pos2D.zFromKey(nr);
                double lateralDist = Math.sqrt((double)(tx - rx) * (tx - rx) + (double)(tz - rz) * (tz - rz));

                Earthworks.applyTalusAt(world, sampler, tx, tz, roadY,
                        lateralDist, slopeRatio, maxCut, maxFill);
            }

            if (talusCursor < talusCells.length) return false;
            if (UltreiaConfig.DEBUG.logPainting) {
                Ultreia.LOGGER.info("[Ultreia] Talus pass: {} road cells, {} talus cells, slopeRatio={}",
                        newlyPlaced.size(), talusCells.length, slopeRatio);
            }
            return true;
        }


        private void registerRoadSegment() {
            if (roadNetwork == null || path.isEmpty()) return;
            roadNetwork.addRoadSegment(path, roadIndex, fromVillage, toVillage, roadType, paintedWidthBlocks());
        }

        /** Builds the bridge waiting in {@code pendingBridge}; past its exit, forces the first road cells when asked. */
        private void finishPendingBridge(String where, boolean reinforceExit) {
            BridgeSpan span = pendingBridge;
            if (!bridges.paint(span, roadNetwork, fromVillage, toVillage, roadType)) {
                Ultreia.LOGGER.warn("Bridge build degraded{} start=({}, {}) end=({}, {}) len={}", where,
                        span.start.getX(), span.start.getZ(),
                        span.end.getX(), span.end.getZ(),
                        span.lengthBlocks());
            }
            if (reinforceExit) {
                footprint.addBridgeExitCells(bridges.exitCells(span, FORCE_PAINT_EXIT_BLOCKS));
            }
            pendingBridge = null;
            pendingBridgeEndIdx = -1;
        }
    }

    private RoadPainter() {}

    public static Session start(WorldServer world,
                                List<BlockPos> coarsePath,
                                List<BridgeSpan> forcedBridges,
                                TerrainSampler sampler,
                                ProtectedAreas protectedAreas,
                                RoadIndex roadIndex) {
        return new Session(world, coarsePath, forcedBridges, sampler, protectedAreas, roadIndex);
    }

}
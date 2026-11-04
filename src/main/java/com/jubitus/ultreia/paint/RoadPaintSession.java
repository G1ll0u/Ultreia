package com.jubitus.ultreia.paint;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.bridge.BridgeSegmentPainter;
import com.jubitus.ultreia.bridge.BridgeSpan;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.roads.RoadIndex;
import com.jubitus.ultreia.roads.RoadNetwork;
import com.jubitus.ultreia.route.RoadType;
import com.jubitus.ultreia.terrain.ProtectedAreas;
import com.jubitus.ultreia.terrain.TerrainSampler;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Paints one found path into the world within the tick budget, step by step: centerline, merge decisions,
 * height profile, cell plan, then a sweep along the centerline that paints cells and builds the edge's bridges,
 * then the cells outside the sweep, the talus, and the road's registration in the network.
 */
public final class RoadPaintSession {

    // Force-paint this many blocks after bridge exit to secure road continuation.
    static final int FORCE_PAINT_EXIT_BLOCKS = 2;

    public enum PaintResult { RUNNING, OK, FAILED }

    /** A bridge of this edge and the centerline indices of its two ends. */
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

    private int baseR;
    private RoadType roadType = RoadType.VILLAGE_MAIN;

    private final List<BridgeSpan> forcedBridges;
    private final BridgeSegmentPainter bridges;
    private final RoadCenterline line;
    private final List<BlockPos> path;
    private final RoadCellPainter cells;
    private final RoadMergePlanner merge;
    private final RoadFootprintPlanner footprint;
    private final WorldServer world;
    private final TerrainSampler sampler;
    @Nullable private final ProtectedAreas protectedAreas;
    @Nullable private final RoadIndex roadIndex;
    @Nullable private TalusPass talus;

    private final Map<Long, ForcedBridgeRef> forcedByStartKey = new HashMap<>();

    @Nullable private RoadNetwork roadNetwork;
    @Nullable private BlockPos fromVillage;
    @Nullable private BlockPos toVillage;
    private LongSet followedXZ = LongSets.EMPTY_SET;

    private boolean densifyDone;
    private boolean profilesReady = false;
    private boolean planBuilt = false;
    private boolean planPruned = false;
    private boolean mergeConnectorPainted = false;
    private boolean elbowFillsPainted = false;
    private boolean forcedLeftoversPainted = false;
    private boolean talusPassDone = false;

    // Sweep cursor: centerline index i, offset (curDx, curDz) inside its square of half width baseR.
    private int i = 0;
    private boolean paintingCell = false;
    private BlockPos curCenter = null;
    private int curDx = 0;
    private int curDz = 0;

    private BridgeSpan pendingBridge = null;
    private int pendingBridgeEndIdx = -1;

    private boolean failed = false;
    private String failReason = "unknown";

    public RoadPaintSession(WorldServer world,
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
        this.cells = new RoadCellPainter(world, sampler, protectedAreas, roadIndex, bridges, path, baseR);
        this.merge = new RoadMergePlanner(world, sampler, protectedAreas, roadIndex, path, cells.newlyPlaced);
        this.footprint = new RoadFootprintPlanner(world, sampler, protectedAreas, roadIndex, bridges, line, merge, cells.newlyPlaced);
        this.cells.footprint = footprint;
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

                merge.detect(roadType, baseR, roadNetwork, toVillage, followedXZ, forcedByStartKey.values());
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
                    cells.paintMergeConnector(i);
                }
                return finishEdge(deadlineNs);
            }

            if (i >= path.size()) {
                if (pendingBridge != null) finishPendingBridge(" at final path end", false);
                return finishEdge(deadlineNs);
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

    /** End of the sweep: cells outside it, then the talus (over several ticks), then the summary and registration. */
    private PaintResult finishEdge(long deadlineNs) {
        if (!elbowFillsPainted) {
            elbowFillsPainted = true;
            cells.paintElbowFills(i);
        }
        if (!forcedLeftoversPainted) {
            forcedLeftoversPainted = true;
            cells.paintForcedLeftovers(i);
        }
        if (!talusPassDone) {
            if (talus == null) {
                talus = new TalusPass(world, sampler, protectedAreas, roadIndex, cells.newlyPlaced, cells, roadType);
            }
            if (!talus.step(deadlineNs)) return PaintResult.RUNNING;
            talusPassDone = true;
        }
        cells.logPaintSummary();
        registerRoadSegment();
        return PaintResult.OK;
    }

    public String failureReason() {
        return failReason;
    }

    /** Must be called before advance(): centerline xz keys the path follows on existing roads (nothing painted). */
    public void setFollowedRoads(LongSet followedXZ) {
        this.followedXZ = followedXZ;
    }

    public void setRoadNetwork(@Nullable RoadNetwork network, @Nullable BlockPos from, @Nullable BlockPos to) {
        this.roadNetwork = network;
        this.fromVillage = from;
        this.toVillage = to;
    }

    /** Must be called before advance(): road width follows the road type. */
    public void setRoadType(RoadType type) {
        this.roadType = (type != null) ? type : RoadType.VILLAGE_MAIN;
        this.baseR = this.roadType.roadWidthBlocks() / 2;
        this.cells.roadHalfWidth = baseR;
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

    /** Sweeps the square around centerline index i until the deadline; true when the square is done. */
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

            int xx = path.get(idx).getX() + localDx;
            int zz = path.get(idx).getZ() + localDz;

            cells.paintSweepCell(xx, zz, idx);
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

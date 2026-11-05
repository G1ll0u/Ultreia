package com.jubitus.ultreia.bridge;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.roads.RoadNetwork;
import com.jubitus.ultreia.route.RoadType;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.terrain.TerrainView;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

import static com.jubitus.ultreia.route.GridMoves.NEIGHBOR_DIRS;
import static com.jubitus.ultreia.route.GridMoves.nearestDirIndex;
import static com.jubitus.ultreia.route.GridMoves.normalizedDot;
import static com.jubitus.ultreia.route.GridMoves.snap;
import static com.jubitus.ultreia.route.GridMoves.turnCost;

/** The new-bridge moves A* may take from a node: spans that feasibility accepts. */
public final class BridgeMoveGenerator {

    // Bridge if the terrain drops at least this many blocks below entry/exit baseline.
    private static final int MIN_VALLEY_DEPTH = 6;
    // Max accepted bridge candidates per expanded A* node.
    private static final int MAX_BRIDGE_CANDIDATES_PER_NODE = 4;
    // Minimum number of normal path blocks between two bridges.
    public static final int MIN_DISTANCE_BETWEEN_BRIDGES_BLOCKS = 28;
    // Safety rail only, not the main alignment mechanism: rejects bridge directions that would require a...
    private static final boolean REQUIRE_ALIGNED_APPROACH = true;
    // Minimum normalized dot product between incoming direction and bridge direction (safety rail, see...
    private static final double MIN_APPROACH_DOT = -0.3;

    /** The cells A* may visit (corridor, bounds, radius). */
    public interface SearchArea {
        boolean contains(int x, int z);
    }

    public static final class BridgeMove {
        public final int nx;
        public final int nz;
        public final int ndir;
        public final double cost;
        /** Kept apart from {@code cost}: the lone-trail bridge multiplier must not scale it. */
        public final double turnCost;
        public final BridgeSpan span;

        BridgeMove(int nx, int nz, int ndir, double cost, double turnCost, BridgeSpan span) {
            this.nx = nx;
            this.nz = nz;
            this.ndir = ndir;
            this.cost = cost;
            this.turnCost = turnCost;
            this.span = span;
        }
    }

    private final TerrainView sampler;
    private final int step;
    private final SearchArea area;
    private final Consumer<String> debug;

    /** {@code debug} must be capped by the caller (never per block or per tick). */
    public BridgeMoveGenerator(TerrainView sampler, int step, SearchArea area, Consumer<String> debug) {
        this.sampler = sampler;
        this.step = step;
        this.area = area;
        this.debug = debug;
    }

    /** New bridges are only probed near water or a valley edge (avoids loading chunks everywhere). */
    public boolean isNearWaterOrValleyEdge(int x, int z, TerrainSampler.Sample cur) {
        if (cur.nearWaterLike) return true;
        if (cur.smallWater) return true;

        int valleyDrop = UltreiaConfig.VALLEY_BRIDGING.enabled
                ? Math.max(2, MIN_VALLEY_DEPTH)
                : Integer.MAX_VALUE;

        for (int[] d : NEIGHBOR_DIRS) {
            int nx = x + d[0] * step;
            int nz = z + d[1] * step;
            TerrainSampler.Sample ns = sampler.sampleIfLoaded(nx, nz);
            if (ns == null) continue;
            if (ns.isWater && !ns.isOcean) return true;
            if (!ns.isWater && !ns.isOcean && (cur.surfaceY - ns.surfaceY) >= valleyDrop) return true;
        }
        return false;
    }

    /** {@code inDx/inDz}: incoming direction, used only when {@code hasIncoming}. */
    public List<BridgeMove> newBridgeMoves(int x, int z, int curDir, int inDx, int inDz, boolean hasIncoming,
                                           @Nullable TerrainSampler.Sample cur, RoadType roadType) {
        ArrayList<BridgeMove> out = new ArrayList<BridgeMove>();

        if (cur == null) return out;
        if (cur.isWater || cur.isOcean) return out;
        if (sampler.isProtectedNoPaint(x, z)) return out;

        final int maxCandidates = Math.max(1, MAX_BRIDGE_CANDIDATES_PER_NODE);

        ArrayList<BridgeDirScore> dirs = new ArrayList<BridgeDirScore>();

        for (int[] dir : NEIGHBOR_DIRS) {
            if (REQUIRE_ALIGNED_APPROACH && hasIncoming) {
                double dot = normalizedDot(inDx, inDz, dir[0], dir[1]);
                if (dot < MIN_APPROACH_DOT) continue;
            }

            QuickBridgeRayInfo info = scanBridgeRayQuick(x, z, cur.surfaceY, dir[0], dir[1]);
            if (!info.canLaunch) continue;

            double dirScore = 0.0;

            if (info.waterCrossing) dirScore += 80.0;
            if (info.valleyCrossing) dirScore -= 20.0;

            dirScore += info.firstObstacleDist * 7.0;
            dirScore += info.landingDist * 2.0;

            if (hasIncoming) {
                dirScore -= normalizedDot(inDx, inDz, dir[0], dir[1]) * 18.0;
            }

            dirs.add(new BridgeDirScore(dir[0], dir[1], dirScore, info));
        }

        if (dirs.isEmpty()) return out;

        Collections.sort(dirs, new Comparator<BridgeDirScore>() {
            @Override
            public int compare(BridgeDirScore a, BridgeDirScore b) {
                return Double.compare(a.score, b.score);
            }
        });

        int dirLimit = Math.min(8, dirs.size());
        for (int i = 0; i < dirLimit; i++) {
            BridgeDirScore d = dirs.get(i);
            List<BridgeMove> moves = buildBridgeMovesFromQuickInfo(x, z, cur, curDir, d.dx, d.dz, d.info, roadType);

            for (BridgeMove mv : moves) {
                out.add(mv);
                if (out.size() >= maxCandidates) break;
            }

            if (out.size() >= maxCandidates) break;
        }

        if (out.size() > 1) {
            Collections.sort(out, new Comparator<BridgeMove>() {
                @Override
                public int compare(BridgeMove a, BridgeMove b) {
                    return Double.compare(a.cost + a.turnCost, b.cost + b.turnCost);
                }
            });

            while (out.size() > maxCandidates) {
                out.remove(out.size() - 1);
            }
        }

        return out;
    }

    private QuickBridgeRayInfo scanBridgeRayQuick(int x, int z, int baselineY, int dirX, int dirZ) {
        final int minLen = Math.max(step * 2, BridgeFeasibility.MIN_BRIDGE_LEN_BLOCKS);
        final int maxLen = Math.max(minLen, BridgeFeasibility.MAX_PROBE_LEN_BLOCKS);

        final int configuredApproach = Math.max(1, BridgeFeasibility.BRIDGE_ROAD_APPROACH_MAX_BLOCKS);
        final int maxLaunchSearch = Math.min(
                maxLen,
                Math.max(16, Math.min(48, minLen + configuredApproach * 2))
        );

        final int minWaterRun = Math.max(1, BridgeClearanceCheck.MIN_WATER_RUN_BLOCKS);
        final int minGapRun = Math.max(1, BridgeClearanceCheck.MIN_MEANINGFUL_GAP_RUN);
        final int maxLeadOut = Math.max(2, BridgeClearanceCheck.MAX_WATER_BRIDGE_LEAD_OUT_BLOCKS + 2);

        int waterRun = 0;
        int gapRun = 0;

        boolean obstacleSeen = false;
        boolean waterCrossing = false;
        boolean valleyCrossing = false;

        int firstObstacleDist = -1;
        int dryAfterObstacle = 0;

        int lastNX = Integer.MIN_VALUE;
        int lastNZ = Integer.MIN_VALUE;

        for (int len = step; len <= maxLen; len += step) {
            int nx = snap(x + dirX * len, step);
            int nz = snap(z + dirZ * len, step);

            if (nx == x && nz == z) continue;
            if (nx == lastNX && nz == lastNZ) continue;

            lastNX = nx;
            lastNZ = nz;

            if (!area.contains(nx, nz)) break;

            // Before finding any obstacle: don't load new chunks — stop the probe.
            // After finding water/valley: allow loading to locate the bridge landing.
            TerrainSampler.Sample s = obstacleSeen
                    ? sampler.sample(nx, nz)
                    : sampler.sampleIfLoaded(nx, nz);
            if (s == null) break;
            if (sampler.isProtectedNoPaint(nx, nz)) break;

            boolean water = s.isWater && !s.isOcean;
            boolean land = !s.isWater && !s.isOcean;
            boolean valley = land && isQuickValleyGap(baselineY, s);

            if (water) {
                if (!obstacleSeen) firstObstacleDist = len;
                obstacleSeen = true;
                waterCrossing = true;
                waterRun++;
                gapRun = 0;
                dryAfterObstacle = 0;
                continue;
            }

            if (valley) {
                if (!obstacleSeen) firstObstacleDist = len;
                obstacleSeen = true;
                valleyCrossing = true;
                gapRun++;
                waterRun = 0;
                dryAfterObstacle = 0;
                continue;
            }

            if (!land) {
                if (obstacleSeen) {
                    dryAfterObstacle++;
                    if (dryAfterObstacle * step > maxLeadOut) break;
                }
                continue;
            }

            if (sampler.forbidden(s, TerrainSampler.Mode.PATHFINDING)) {
                if (obstacleSeen) {
                    dryAfterObstacle++;
                    if (dryAfterObstacle * step > maxLeadOut) break;
                }
                continue;
            }

            if (!obstacleSeen) {
                if (len > maxLaunchSearch) {
                    break;
                }
                continue;
            }

            // Runs count samples, one every step blocks: n samples may span up to (n + 1) * step - 1 blocks.
            // Optimistic on purpose, BridgeFeasibility measures the real run.
            boolean goodWater = waterCrossing && (waterRun + 1) * step - 1 >= minWaterRun;
            boolean goodValley = valleyCrossing && (gapRun + 1) * step - 1 >= minGapRun;

            if ((goodWater || goodValley) && len >= minLen) {
                return new QuickBridgeRayInfo(
                        true,
                        goodWater,
                        goodValley,
                        firstObstacleDist < 0 ? len : firstObstacleDist,
                        len
                );
            }

            dryAfterObstacle++;
            if (dryAfterObstacle * step > maxLeadOut) {
                break;
            }
        }

        return new QuickBridgeRayInfo(false, false, false, -1, -1);
    }

    private static boolean isQuickValleyGap(int baselineY, TerrainSampler.Sample s) {
        if (s == null) return false;
        if (s.isWater || s.isOcean) return false;
        if (!UltreiaConfig.VALLEY_BRIDGING.enabled) return false;

        int drop = baselineY - s.surfaceY;
        return drop >= Math.max(2, MIN_VALLEY_DEPTH);
    }

    private List<BridgeMove> buildBridgeMovesFromQuickInfo(int x,
                                                           int z,
                                                           TerrainSampler.Sample cur,
                                                           int curDir,
                                                           int dirX,
                                                           int dirZ,
                                                           QuickBridgeRayInfo info,
                                                           RoadType roadType) {
        ArrayList<BridgeMove> out = new ArrayList<BridgeMove>();
        if (info == null || !info.canLaunch) return out;

        int bridgeDir = nearestDirIndex(dirX, dirZ);

        int[] landingOffsets = new int[] {0, step, -step};

        for (int off : landingOffsets) {
            int probeDist = info.landingDist + off;
            if (probeDist <= 0) continue;

            int nx = snap(x + dirX * probeDist, step);
            int nz = snap(z + dirZ * probeDist, step);

            if (nx == x && nz == z) continue;

            if (!area.contains(nx, nz)) {
                debug.accept("reject landing outside corridor start=(" + x + "," + z + ") dir=(" + dirX + "," + dirZ + ")"
                        + " probeDist=" + probeDist + " landing=(" + nx + "," + nz + ")");
                continue;
            }

            TerrainSampler.Sample ns = sampler.sample(nx, nz);
            if (ns == null) {
                debug.accept("reject landing null sample start=(" + x + "," + z + ") landing=(" + nx + "," + nz + ")");
                continue;
            }
            if (ns.isWater || ns.isOcean) {
                debug.accept("reject landing still water start=(" + x + "," + z + ") landing=(" + nx + "," + nz + ")"
                        + " water=" + ns.isWater + " ocean=" + ns.isOcean);
                continue;
            }
            if (sampler.forbidden(ns, TerrainSampler.Mode.PATHFINDING)) {
                debug.accept("reject landing forbidden start=(" + x + "," + z + ") landing=(" + nx + "," + nz + ")"
                        + " reason=" + sampler.describeForbidden(ns, TerrainSampler.Mode.PATHFINDING));
                continue;
            }
            if (sampler.isProtectedNoPaint(nx, nz)) {
                debug.accept("reject landing protected start=(" + x + "," + z + ") landing=(" + nx + "," + nz + ")");
                continue;
            }

            BlockPos startPos = new BlockPos(x, cur.surfaceY, z);
            BlockPos endPos = new BlockPos(nx, ns.surfaceY, nz);

            BridgeFeasibility.Result feasibility = BridgeFeasibility.tryCreate(sampler, startPos, endPos);
            BridgeSpan span = feasibility.span;

            if (span == null) {
                debug.accept("reject feasibility start=(" + x + "," + z + ") end=(" + nx + "," + nz + ") reason="
                        + feasibility.reason);
                continue;
            }

            double cost = BridgeMoveCost.newSpan(sampler, span, roadType, info.waterCrossing, info.valleyCrossing);

            double turn = turnCost(curDir, bridgeDir, step, UltreiaConfig.PATHFINDING.turnPenalty);

            debug.accept("accept span start=(" + x + "," + cur.surfaceY + "," + z + ")"
                    + " end=(" + nx + "," + ns.surfaceY + "," + nz + ")"
                    + " len=" + span.lengthBlocks()
                    + " cost=" + cost
                    + " waterCrossing=" + info.waterCrossing
                    + " valleyCrossing=" + info.valleyCrossing);

            out.add(new BridgeMove(nx, nz, bridgeDir, cost, turn, span));
        }

        if (out.size() > 1) {
            Collections.sort(out, new Comparator<BridgeMove>() {
                @Override
                public int compare(BridgeMove a, BridgeMove b) {
                    return Double.compare(a.cost + a.turnCost, b.cost + b.turnCost);
                }
            });

            while (out.size() > 2) {
                out.remove(out.size() - 1);
            }
        }

        return out;
    }

    private static final class QuickBridgeRayInfo {
        final boolean canLaunch;
        final boolean waterCrossing;
        final boolean valleyCrossing;
        final int firstObstacleDist;
        final int landingDist;

        QuickBridgeRayInfo(boolean canLaunch,
                           boolean waterCrossing,
                           boolean valleyCrossing,
                           int firstObstacleDist,
                           int landingDist) {
            this.canLaunch = canLaunch;
            this.waterCrossing = waterCrossing;
            this.valleyCrossing = valleyCrossing;
            this.firstObstacleDist = firstObstacleDist;
            this.landingDist = landingDist;
        }
    }

    private static final class BridgeDirScore {
        final int dx;
        final int dz;
        final double score;
        final QuickBridgeRayInfo info;

        BridgeDirScore(int dx, int dz, double score, QuickBridgeRayInfo info) {
            this.dx = dx;
            this.dz = dz;
            this.score = score;
            this.info = info;
        }
    }
}

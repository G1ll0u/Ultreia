package com.jubitus.ultreia.route;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.terrain.ProtectedAreas;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.terrain.TerrainView;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.List;

import static com.jubitus.ultreia.route.GridMoves.GRID_STEP;
import static com.jubitus.ultreia.route.GridMoves.isOpenCell;
import static com.jubitus.ultreia.route.TerrainMoveCost.HAIRPIN_CONTACT_WIDTHS;
import static com.jubitus.ultreia.route.TerrainMoveCost.HAIRPIN_MIN_PATH_WIDTHS;

/** Debug output of one A* search: rejection counters, failure summary, progress log, capped bridge log, cell description. */
public final class AStarDiagnostics {

    private static final int BRIDGE_DEBUG_LIMIT = 16;
    private static final long PROGRESS_LOG_INTERVAL_NS = 10_000_000_000L;
    private static final int ROAD_NEAR_RADIUS = 16;
    private static final int DESCRIBED_RINGS = 3;
    private static final int HAIRPIN_LOG_LIMIT = 5;

    private int skipOcean = 0, skipBlacklist = 0, skipBlocked = 0, skipSlope = 0, skipBridge = 0, skipHairpin = 0;
    private long lastProgressLogNs = 0;
    private int bridgeDebugPrinted = 0;
    private int bridgeDebugSuppressed = 0;

    void countForbidden(TerrainView sampler, TerrainSampler.Sample s) {
        TerrainSampler.ForbiddenReason r = sampler.forbiddenReason(s, TerrainSampler.Mode.PATHFINDING);
        if (r == TerrainSampler.ForbiddenReason.OCEAN) skipOcean++;
        else if (r == TerrainSampler.ForbiddenReason.BLACKLIST) skipBlacklist++;
        else if (r == TerrainSampler.ForbiddenReason.WATER) skipBridge++;
        else skipBlocked++;
    }

    void countWaterMove() {
        skipBridge++;
    }

    void countTooSteep() {
        skipSlope++;
    }

    void countSteepHairpin() {
        skipHairpin++;
    }

    String failureSummary(String reason, SearchState st, boolean detailed) {
        if (!detailed) return reason;
        return reason
                + " it=" + st.iterations
                + " closed=" + st.closed.size()
                + " open=" + st.open.size()
                + " rejects{ocean=" + skipOcean
                + ", blacklisted=" + skipBlacklist
                + ", blocked=" + skipBlocked
                + ", slope=" + skipSlope
                + ", bridge=" + skipBridge
                + ", hairpin=" + skipHairpin + "}";
    }

    /** Periodic log line so the user can see A* is still alive. */
    void logProgress(long nowNs, long startNs, SearchState st, double bestHeuristic) {
        if (!UltreiaConfig.DEBUG.logPathfinding || nowNs - lastProgressLogNs <= PROGRESS_LOG_INTERVAL_NS) return;
        lastProgressLogNs = nowNs;
        long elapsedSec = (nowNs - startNs) / 1_000_000_000L;
        Ultreia.LOGGER.info(
                "[Ultreia A*] still running: it={} closed={} open={} best_dist={} elapsed={}s",
                st.iterations, st.closed.size(), st.open.size(),
                Double.isFinite(bestHeuristic) ? (int) bestHeuristic : -1,
                elapsedSec);
    }

    void bridge(String msg) {
        if (!UltreiaConfig.DEBUG.logBridges) return;

        if (bridgeDebugPrinted < BRIDGE_DEBUG_LIMIT) {
            Ultreia.LOGGER.info("[Ultreia bridge-debug] {}", msg);
            bridgeDebugPrinted++;
        } else {
            bridgeDebugSuppressed++;
        }
    }

    /** End of search: the bridge-reuse summary (null when no built bridge was near) and the capped-lines count. */
    void flushBridge(@Nullable String reuseSummary, int reusedPathsRejected) {
        if (!UltreiaConfig.DEBUG.logBridges) return;
        if (reuseSummary != null) {
            Ultreia.LOGGER.info("[Ultreia bridge-debug] {}, found paths through it rejected by the whole-path check: {}",
                    reuseSummary, reusedPathsRejected);
        }
        if (bridgeDebugSuppressed > 0) {
            Ultreia.LOGGER.info("[Ultreia bridge-debug] suppressed {} additional bridge debug lines", bridgeDebugSuppressed);
            bridgeDebugSuppressed = 0;
        }
    }

    /** One-line description of a requested endpoint, on the base grid step. */
    public static String describeEndpoint(String label, TerrainView sampler, @Nullable ProtectedAreas protectedAreas,
                                          @Nullable BlockPos pos) {
        if (pos == null) return label + "=null";
        return describeCell(label, sampler, protectedAreas, pos.getX(), pos.getZ(), GRID_STEP);
    }

    /** One-line description of a cell: terrain, protection, roads, and open cells on the rings around it. */
    static String describeCell(String label, TerrainView sampler, @Nullable ProtectedAreas protectedAreas,
                               int x, int z, int step) {
        TerrainSampler.Sample s = sampler.sample(x, z);
        if (s == null) return label + "=(" + x + "," + z + ") sample=null";

        BlockPos near = sampler.findNearestExistingRoad(x, z, ROAD_NEAR_RADIUS);
        int roadNear = near == null ? -1 : Math.abs(near.getX() - x) + Math.abs(near.getZ() - z);

        StringBuilder free = new StringBuilder(" free{");
        StringBuilder blocked = new StringBuilder(" blocked{");
        for (int ring = 1; ring <= DESCRIBED_RINGS; ring++) {
            int f = countOpenOnRing(x, z, step, sampler, ring);
            String sep = ring == 1 ? "" : ",";
            free.append(sep).append('r').append(ring).append('=').append(f);
            blocked.append(sep).append('r').append(ring).append('=').append(8 * ring - f);
        }

        return label + "=(" + x + "," + z + ")"
                + " surfY=" + s.surfaceY
                + " water=" + s.isWater
                + " ocean=" + s.isOcean
                + " blockedPath=" + s.blockedPath
                + " blockedPaint=" + s.blockedPaint
                + " forbidden=" + sampler.forbiddenReason(s, TerrainSampler.Mode.PATHFINDING)
                + " noPaint=" + sampler.isProtectedNoPaint(x, z)
                + " protectPenalty=" + (protectedAreas != null ? protectedAreas.penaltyAt(x, z) : 0)
                + " road=" + sampler.isExistingRoadXZ(x, z)
                + " roadNear=" + roadNear
                + free.append('}')
                + blocked.append('}');
    }

    private static int countOpenOnRing(int x, int z, int step, TerrainView sampler, int ring) {
        int free = 0;
        for (int dz = -ring; dz <= ring; dz++) {
            for (int dx = -ring; dx <= ring; dx++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                if (isOpenCell(x + dx * step, z + dz * step, sampler)) free++;
            }
        }
        return free;
    }

    /**
     * Logs the hairpins of a found path: places where the path comes back beside itself within a road width,
     * with the height gap between the two parts (the painter cannot make a steep gap walkable). One summary per path.
     */
    static void logHairpins(List<BlockPos> path, RoadType roadType) {
        if (!UltreiaConfig.DEBUG.logPathfinding || path.size() < 3) return;

        int width = roadType.roadWidthBlocks();
        double contactDist = width * HAIRPIN_CONTACT_WIDTHS;
        double minPathGap = width * HAIRPIN_MIN_PATH_WIDTHS;

        double[] along = new double[path.size()];
        for (int i = 1; i < path.size(); i++) {
            along[i] = along[i - 1] + Math.sqrt(distSqXZ(path.get(i - 1), path.get(i)));
        }

        int hairpins = 0;
        int steep = 0;
        int lastContactI = Integer.MIN_VALUE;
        // Worst contact of the hairpin being grouped: index pair, XZ distance, height gap.
        int bestI = -1, bestJ = -1;
        double bestDist = 0;
        int bestDy = -1;
        StringBuilder lines = new StringBuilder();

        for (int i = 0; i < path.size(); i++) {
            for (int j = i + 1; j < path.size(); j++) {
                if (along[j] - along[i] < minPathGap) continue;
                double d = Math.sqrt(distSqXZ(path.get(i), path.get(j)));
                if (d > contactDist) continue;

                int dy = Math.abs(path.get(i).getY() - path.get(j).getY());
                if (i > lastContactI + 1 && bestI >= 0) {
                    if (appendHairpin(lines, hairpins, path, bestI, bestJ, bestDist, bestDy)) steep++;
                    hairpins++;
                    bestI = -1;
                }
                lastContactI = i;
                if (bestI < 0 || dy > bestDy) {
                    bestI = i;
                    bestJ = j;
                    bestDist = d;
                    bestDy = dy;
                }
            }
        }
        if (bestI >= 0) {
            if (appendHairpin(lines, hairpins, path, bestI, bestJ, bestDist, bestDy)) steep++;
            hairpins++;
        }

        Ultreia.LOGGER.info("[Ultreia A*] hairpins: {} ({} with a height gap over half a block per block of gap), road width {}, turnPenalty={} slopePenalty={}{}",
                hairpins, steep, width, UltreiaConfig.PATHFINDING.turnPenalty, UltreiaConfig.PATHFINDING.slopePenalty, lines);
    }

    /** Appends one hairpin line (up to the log limit); true when its height gap is not walkable across the gap. */
    private static boolean appendHairpin(StringBuilder lines, int index, List<BlockPos> path,
                                         int i, int j, double dist, int dy) {
        boolean steepGap = TerrainMoveCost.isSteepHairpinGap(dy, dist);
        if (index < HAIRPIN_LOG_LIMIT) {
            BlockPos a = path.get(i);
            BlockPos b = path.get(j);
            lines.append("\n  hairpin (").append(a.getX()).append(',').append(a.getY()).append(',').append(a.getZ())
                    .append(") / (").append(b.getX()).append(',').append(b.getY()).append(',').append(b.getZ())
                    .append(") xz=").append(Math.round(dist * 10.0) / 10.0)
                    .append(" dy=").append(dy)
                    .append(" pathPoints=").append(j - i)
                    .append(steepGap ? " STEEP" : "");
        }
        return steepGap;
    }

    private static double distSqXZ(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }
}

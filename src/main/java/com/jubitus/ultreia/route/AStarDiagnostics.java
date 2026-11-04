package com.jubitus.ultreia.route;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.terrain.ProtectedAreas;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.terrain.TerrainView;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

import static com.jubitus.ultreia.route.GridMoves.GRID_STEP;
import static com.jubitus.ultreia.route.GridMoves.isOpenCell;

/** Debug output of one A* search: rejection counters, failure summary, progress log, capped bridge log, cell description. */
public final class AStarDiagnostics {

    private static final int BRIDGE_DEBUG_LIMIT = 16;
    private static final long PROGRESS_LOG_INTERVAL_NS = 10_000_000_000L;
    private static final int ROAD_NEAR_RADIUS = 16;
    private static final int DESCRIBED_RINGS = 3;

    private int skipOcean = 0, skipBlacklist = 0, skipBlocked = 0, skipSlope = 0, skipBridge = 0;
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
                + ", bridge=" + skipBridge + "}";
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

    void flushBridge() {
        if (!UltreiaConfig.DEBUG.logBridges) return;
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
}

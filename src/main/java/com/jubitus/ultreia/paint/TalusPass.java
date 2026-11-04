package com.jubitus.ultreia.paint;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.roads.RoadIndex;
import com.jubitus.ultreia.route.RoadType;
import com.jubitus.ultreia.terrain.ProtectedAreas;
import com.jubitus.ultreia.terrain.TerrainSampler;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayDeque;

/**
 * Post-painting pass: extends sloped talus outside the road surface. From every road block the edge placed,
 * a BFS outward to the talus radius cuts or fills the terrain to a natural slope instead of vertical walls.
 * Built once at the end of the edge, then applied within the tick budget.
 */
final class TalusPass {

    private final WorldServer world;
    private final TerrainSampler sampler;
    @Nullable private final ProtectedAreas protectedAreas;
    @Nullable private final RoadIndex roadIndex;
    private final LongSet newlyPlaced;
    private final RoadCellPainter cells;
    private final RoadType roadType;

    // Talus work list, built once at the end of the edge and applied over several ticks.
    private long[] talusCells = null;
    private int talusCursor = 0;
    private Long2IntOpenHashMap talusDist;
    private Long2LongOpenHashMap talusNearestRoad;
    private Long2IntOpenHashMap roadSurfaceY;
    private double talusSlopeRatio;
    private int talusMaxCut, talusMaxFill;

    TalusPass(WorldServer world, TerrainSampler sampler, @Nullable ProtectedAreas protectedAreas,
              @Nullable RoadIndex roadIndex, LongSet newlyPlaced, RoadCellPainter cells, RoadType roadType) {
        this.world = world;
        this.sampler = sampler;
        this.protectedAreas = protectedAreas;
        this.roadIndex = roadIndex;
        this.newlyPlaced = newlyPlaced;
        this.cells = cells;
        this.roadType = roadType;
    }

    private double effectiveSlopeRatio() {
        boolean isLoneTrail = roadType == RoadType.LONE_TRAIL;
        double ltr = UltreiaConfig.PAINTING.earthworksSlopeRatioLoneTrail;
        if (isLoneTrail && ltr > 0.0) return ltr;
        return UltreiaConfig.PAINTING.earthworksSlopeRatio;
    }

    /** Builds the talus work list once; returns false when there is nothing to do. */
    private boolean prepare() {
        if (newlyPlaced.isEmpty()) return false;

        double slopeRatio = effectiveSlopeRatio();
        int maxCut = Math.max(0, UltreiaConfig.PAINTING.earthworksMaxCut);
        int maxFill = Math.max(0, UltreiaConfig.PAINTING.earthworksMaxFill);
        if (slopeRatio <= 0.0 || (maxCut == 0 && maxFill == 0)) return false;

        int talusRadius = (int) Math.ceil(Math.max(maxCut, maxFill) * slopeRatio) + 1;
        if (talusRadius <= 0) return false;

        // Build road surface Y lookup for newly placed cells.
        roadSurfaceY = new Long2IntOpenHashMap(newlyPlaced.size());
        roadSurfaceY.defaultReturnValue(Integer.MIN_VALUE);

        for (LongIterator rit = newlyPlaced.iterator(); rit.hasNext(); ) {
            long k = rit.nextLong();
            int rx = Pos2D.xFromKey(k);
            int rz = Pos2D.zFromKey(k);
            TerrainSampler.Sample s = sampler.sample(rx, rz);
            if (s != null) roadSurfaceY.put(k, s.surfaceY);
        }

        // BFS: propagate distance and nearest-road-cell from all newly placed cells.
        talusDist = new Long2IntOpenHashMap();
        Long2IntOpenHashMap distMap = talusDist;
        distMap.defaultReturnValue(-1);
        talusNearestRoad = new Long2LongOpenHashMap();
        Long2LongOpenHashMap nearestRoad = talusNearestRoad;
        nearestRoad.defaultReturnValue(Long.MIN_VALUE);

        ArrayDeque<Long> queue = new ArrayDeque<>(newlyPlaced.size() * 4);

        for (LongIterator rit = newlyPlaced.iterator(); rit.hasNext(); ) {
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
    boolean step(long deadlineNs) {
        if (talusCells == null) {
            if (!prepare()) return true;
        }
        Long2IntOpenHashMap distMap = talusDist;
        Long2LongOpenHashMap nearestRoad = talusNearestRoad;
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
            if (cells.columnHasAnyMillPath(new BlockPos(tx, roadY, tz))) continue;

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
}

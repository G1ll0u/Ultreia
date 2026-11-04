package com.jubitus.ultreia.paint;

import com.jubitus.ultreia.bridge.BridgeSpan;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.geom.LineRaster;
import com.jubitus.ultreia.terrain.TerrainSampler;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The block-by-block centerline of one road: the coarse A* path rasterised within the tick budget, its walkable
 * height profile (half-blocks) and the edge variation that trims one side of the road on stretches.
 */
final class RoadCenterline {

    // Maximum number of blocks trimmed from one edge of the road to create organic width variation.
    static final int ROAD_EDGE_VARIATION = 1;
    // Average number of road blocks between edge-variation changes.
    private static final int ROAD_EDGE_VARIATION_PERIOD = 8;

    /** Dense centerline, ground height from the sampler. */
    final List<BlockPos> points = new ArrayList<>();
    /** Road surface height per point (half-blocks); null until {@link #buildProfiles}. */
    int[] centerHalf;
    /** Edge trim per point: positive trims the left side, negative the right; null until {@link #buildProfiles}. */
    int[] edgeNoise;

    private final List<BlockPos> coarse;
    private final TerrainSampler sampler;
    private int densifyIndex = 1;
    private String failReason;

    RoadCenterline(List<BlockPos> coarse, TerrainSampler sampler) {
        this.coarse = coarse;
        this.sampler = sampler;
        if (!coarse.isEmpty()) {
            BlockPos a = coarse.get(0);
            TerrainSampler.Sample sm = sampler.sample(a.getX(), a.getZ());
            points.add(new BlockPos(a.getX(), sm.surfaceY, a.getZ()));
        }
    }

    /** Why densifying or the profile failed, or null. */
    String failReason() {
        return failReason;
    }

    /** Rasterises coarse segments until the deadline; true when done (or failed, see {@link #failReason}). */
    boolean densifyStep(long deadlineNs) {
        while (densifyIndex < coarse.size() && System.nanoTime() < deadlineNs) {
            BlockPos a = coarse.get(densifyIndex - 1);
            BlockPos b = coarse.get(densifyIndex);

            List<BlockPos> line;
            try {
                line = LineRaster.forBridges(a.getX(), a.getZ(), b.getX(), b.getZ(), 0);
            } catch (Throwable t) {
                failReason = "densify failed on segment (" + a.getX() + "," + a.getZ() + ") -> (" + b.getX() + "," + b.getZ() + "): " + t.getMessage();
                return true;
            }

            if (line == null || line.isEmpty()) {
                densifyIndex++;
                continue;
            }

            // Always finish the segment: densifyIndex only advances per whole segment.
            for (int k = 1; k < line.size(); k++) {
                BlockPos p = line.get(k);
                TerrainSampler.Sample sm = sampler.sample(p.getX(), p.getZ());
                points.add(new BlockPos(p.getX(), sm.surfaceY, p.getZ()));
            }

            densifyIndex++;
        }
        return densifyIndex >= coarse.size();
    }

    /** Height profile (bridges fixed at deck height) and edge variation; false when no walkable profile exists. */
    boolean buildProfiles(List<BridgeSpan> forcedBridges, int baseR) {
        int n = points.size();
        edgeNoise = new int[n];

        try {
            centerHalf = RoadHeightProfile.build(points, forcedBridges, sampler);
        } catch (IllegalStateException ex) {
            failReason = "No feasible final road profile with max 0.5-block steps: " + ex.getMessage();
            centerHalf = new int[n];
            return false;
        } catch (Throwable t) {
            failReason = "Road profile generation failed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            centerHalf = new int[n];
            return false;
        }

        buildEdgeVariationProfile(baseR);
        return true;
    }

    /** Planned surface height at a point, or its ground height before the profile exists. */
    int halfAt(int idx) {
        return centerHalf != null && idx < centerHalf.length
                ? centerHalf[idx]
                : HalfY.ofBlockTop(points.get(idx).getY());
    }

    /** First index at or after {@code fromIdx} with this XZ, or -1. */
    int indexOf(int x, int z, int fromIdx) {
        for (int idx = Math.max(0, fromIdx); idx < points.size(); idx++) {
            BlockPos p = points.get(idx);
            if (p.getX() == x && p.getZ() == z) return idx;
        }
        return -1;
    }

    private void buildEdgeVariationProfile(int baseR) {
        int n = edgeNoise.length;
        if (n == 0) return;

        int maxOffset = ROAD_EDGE_VARIATION;
        if (maxOffset <= 0 || baseR <= 0) return;

        // Clamp maxOffset so we never reduce the road to 0-wide.
        maxOffset = Math.min(maxOffset, baseR);

        int period = Math.max(2, ROAD_EDGE_VARIATION_PERIOD);

        BlockPos start = points.get(0);
        Random rng = new Random(
                (long) start.getX() * 73856093L ^ (long) start.getZ() * 19349663L);

        // For a 3-wide road (baseR == 1) one side must always be trimmed: never allow current == 0.
        boolean forceNonZero = (baseR == 1 && maxOffset >= 1);
        int current = forceNonZero ? (rng.nextBoolean() ? 1 : -1) : 0;
        // Stagger the first change so different paths don't all change at the same step.
        int stepsUntilChange = period / 2 + rng.nextInt(Math.max(1, period));

        for (int idx = 0; idx < n; idx++) {
            // Keep endpoints at 0 for clean terminal connections.
            if (idx == 0 || idx == n - 1) {
                edgeNoise[idx] = 0;
                continue;
            }
            if (stepsUntilChange <= 0) {
                int delta = rng.nextInt(3) - 1; // -1, 0, +1
                current = Math.max(-maxOffset, Math.min(maxOffset, current + delta));
                // On a 3-wide road, skip 0 — flip to the same sign the delta was heading.
                if (forceNonZero && current == 0) current = (delta >= 0) ? 1 : -1;
                stepsUntilChange = period / 2 + rng.nextInt(Math.max(1, period));
            }
            edgeNoise[idx] = current;
            stepsUntilChange--;
        }
    }
}

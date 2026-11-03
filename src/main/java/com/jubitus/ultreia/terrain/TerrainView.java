package com.jubitus.ultreia.terrain;

import com.jubitus.ultreia.terrain.TerrainSampler.ForbiddenReason;
import com.jubitus.ultreia.terrain.TerrainSampler.Mode;
import com.jubitus.ultreia.terrain.TerrainSampler.Sample;
import com.jubitus.ultreia.bridge.BridgeSite;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/** Read-only terrain questions asked by A* and route/bridge planning; lets tests run them on synthetic terrain. */
public interface TerrainView {

    Sample sample(int x, int z);

    @Nullable
    Sample sampleIfLoaded(int x, int z);

    int sampleLightIfLoaded(int x, int z);

    boolean forbidden(Sample s, Mode mode);

    ForbiddenReason forbiddenReason(Sample s, Mode mode);

    String describeForbidden(Sample s, Mode mode);

    double extraCost(Sample s, Mode mode);

    boolean isProtectedNoPaint(int x, int z);

    boolean isExistingRoadXZ(int x, int z);

    @Nullable
    BlockPos findNearestExistingRoad(int x, int z, int radius);

    int nearestRoadDistManhattan(int x, int z, int maxRadius, int scanStep);

    boolean isNaturalLandEndpoint(int x, int z);

    /** World reads of bridge feasibility at this terrain. */
    BridgeSite bridgeSite();
}

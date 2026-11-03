package com.jubitus.ultreia.testutil;

import com.jubitus.ultreia.terrain.TerrainSampler;

/**
 * Terrain defined by formulas instead of a world. Only sampling is replaced: the real
 * forbidden/cost rules of {@link TerrainSampler} still apply (no protected areas, no road index).
 */
public abstract class SyntheticTerrain extends TerrainSampler {

    protected SyntheticTerrain() {
        super(null);
    }

    protected abstract int height(int x, int z);

    protected boolean water(int x, int z) { return false; }

    protected boolean blocked(int x, int z) { return false; }

    @Override
    public Sample sample(int x, int z) {
        boolean w = water(x, z);
        boolean b = blocked(x, z);
        BlockedDetail d = b ? BlockedDetail.NON_WALKABLE_GROUND : BlockedDetail.NONE;
        return new Sample(x, z, height(x, z), w, false, false, b, b, false, false,
                false, 0, false, 0, d, d, null);
    }

    @Override
    public Sample sampleIfLoaded(int x, int z) {
        return sample(x, z);
    }

    @Override
    public int sampleLightIfLoaded(int x, int z) {
        Sample s = sample(x, z);
        return packLight(s.surfaceY, s.isWater, s.isOcean, s.surfaceHardnessCategory);
    }

    @Override
    public boolean isNaturalLandEndpoint(int x, int z) {
        Sample s = sample(x, z);
        return !s.isWater && !s.blockedPath;
    }
}

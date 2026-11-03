package com.jubitus.ultreia.testutil;

import com.jubitus.ultreia.bridge.BridgeSite;
import com.jubitus.ultreia.bridge.BridgeSpan;
import com.jubitus.ultreia.bridge.template.BridgeTemplate;
import com.jubitus.ultreia.terrain.TerrainSampler;
import net.minecraft.util.math.BlockPos;

/**
 * Terrain defined by formulas instead of a world. Only sampling is replaced: the real
 * forbidden/cost rules of {@link TerrainSampler} still apply (no protected areas, no road index).
 * Bridge sites are open: natural ground, nothing above the deck, no settlement, no template.
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

    private static final BridgeSite OPEN_SITE = new BridgeSite() {
        @Override public boolean isNaturalGroundOnly(int x, int y, int z) { return true; }
        @Override public boolean isRoadGround(int x, int y, int z) { return true; }
        @Override public boolean isBridgeClearable(int x, int y, int z, boolean allowWoodenSlab) { return true; }
        @Override public boolean isWalkVolumePassable(int x, int z, int yFrom, int yTo, boolean allowWoodenSlab) { return true; }
        @Override public boolean isNearVillage(BlockPos pos, int radius) { return false; }
        @Override public boolean isNearAnySettlement(BlockPos pos, int radius) { return false; }
        @Override public BridgeTemplate selectTemplate(BridgeSpan span) { return null; }
    };

    @Override
    public BridgeSite bridgeSite() {
        return OPEN_SITE;
    }

    @Override
    public boolean isNaturalLandEndpoint(int x, int z) {
        Sample s = sample(x, z);
        return !s.isWater && !s.blockedPath;
    }
}

package com.jubitus.ultreia.gen.bridge;

import com.jubitus.ultreia.gen.bridge.template.BridgeTemplate;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.List;

public final class BridgeSpan {
    public final BlockPos start;
    public final BlockPos end;
    public final List<BlockPos> centerline; // rasterised points from start to end (inclusive)

    // Deck walking surface in half-block units, see BridgeGeometry.naturalSurfaceHalfY.
    public final int startDeckSurfaceHalfY;
    public final int endDeckSurfaceHalfY;

    /** True when water cells dominate the interior (water > 1/3 total AND water >= dry-gap cells). */
    public final boolean waterDominant;
    /** True when the bridge has at least minWaterRunBlocks consecutive interior water cells. */
    public final boolean hasWaterCrossing;

    /** Template the bridge will be built with; null when no template matched. */
    @Nullable public final BridgeTemplate template;
    // INVARIANT: every deck profile of this span (checks, costs, painting, network) uses these two values.
    public final int crownHalf;
    public final int crownMinLen;

    public BridgeSpan(BlockPos start, BlockPos end, List<BlockPos> centerline,
                      int startDeckSurfaceHalfY, int endDeckSurfaceHalfY,
                      boolean waterDominant, boolean hasWaterCrossing,
                      @Nullable BridgeTemplate template, int crownHalf, int crownMinLen) {
        this.start = start;
        this.end = end;
        this.centerline = centerline;
        this.startDeckSurfaceHalfY = startDeckSurfaceHalfY;
        this.endDeckSurfaceHalfY = endDeckSurfaceHalfY;
        this.waterDominant = waterDominant;
        this.hasWaterCrossing = hasWaterCrossing;
        this.template = template;
        this.crownHalf = crownHalf;
        this.crownMinLen = crownMinLen;
    }

    public BridgeSpan withTemplate(@Nullable BridgeTemplate t) {
        return new BridgeSpan(start, end, centerline, startDeckSurfaceHalfY, endDeckSurfaceHalfY,
                waterDominant, hasWaterCrossing, t, crownHalf, crownMinLen);
    }

    public int lengthBlocks() {
        return Math.max(0, centerline.size() - 1);
    }
}

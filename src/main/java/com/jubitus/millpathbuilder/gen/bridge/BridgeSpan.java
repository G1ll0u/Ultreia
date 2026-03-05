package com.jubitus.millpathbuilder.gen.bridge;

import net.minecraft.util.math.BlockPos;

import java.util.List;

import net.minecraft.util.math.BlockPos;
import java.util.List;

public final class BridgeSpan {
    public final BlockPos start;
    public final BlockPos end;
    public final List<BlockPos> centerline; // supercover points from start to end (inclusive)

    // Deck walking surface in HALF-block units (y*2). Example: surfaceY + 1.5 => halfY = (surfaceY+1)*2 + 1
    public final int startDeckSurfaceHalfY;
    public final int endDeckSurfaceHalfY;

    public final boolean micro;

    public BridgeSpan(BlockPos start, BlockPos end, List<BlockPos> centerline,
                      int startDeckSurfaceHalfY, int endDeckSurfaceHalfY, boolean micro) {
        this.start = start;
        this.end = end;
        this.centerline = centerline;
        this.startDeckSurfaceHalfY = startDeckSurfaceHalfY;
        this.endDeckSurfaceHalfY = endDeckSurfaceHalfY;
        this.micro = micro;
    }

    public int lengthBlocks() {
        return Math.max(0, centerline.size() - 1);
    }
}
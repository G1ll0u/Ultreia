package com.jubitus.ultreia.route;

import com.jubitus.ultreia.terrain.TerrainView;
import com.jubitus.ultreia.paint.RoadHeightProfile;

import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.bridge.BridgeSpan;
import com.jubitus.ultreia.geom.RasterLine8;
import com.jubitus.ultreia.geom.SupercoverLine;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class PathProfileCheck {

    private PathProfileCheck() {}

    public static boolean isFullPathFeasible(List<BlockPos> coarsePath,
                                             List<BridgeSpan> bridges,
                                             TerrainView sampler,
                                             RoadType roadType) {
        if (coarsePath == null || coarsePath.isEmpty() || sampler == null) {
            return false;
        }

        List<BlockPos> dense = densify(coarsePath, sampler);
        if (dense.isEmpty()) {
            return false;
        }

        try {
            int[] half = RoadHeightProfile.build(dense, bridges, sampler, roadType);
            if (half == null || half.length != dense.size()) {
                return false;
            }

            int maxStepHalf = Math.max(1, UltreiaConfig.PAINTING.maxRoadStepHalfPerBlock);
            for (int i = 1; i < half.length; i++) {
                if (Math.abs(half[i] - half[i - 1]) > maxStepHalf) {
                    return false;
                }
            }

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static List<BlockPos> densify(List<BlockPos> coarse, TerrainView sampler) {
        if (coarse == null || coarse.isEmpty()) {
            return Collections.emptyList();
        }

        ArrayList<BlockPos> out = new ArrayList<BlockPos>();
        BlockPos first = coarse.get(0);
        TerrainSampler.Sample fs = sampler.sample(first.getX(), first.getZ());
        if (fs == null) {
            return Collections.emptyList();
        }
        out.add(new BlockPos(first.getX(), fs.surfaceY, first.getZ()));

        for (int i = 1; i < coarse.size(); i++) {
            BlockPos a = coarse.get(i - 1);
            BlockPos b = coarse.get(i);

            List<BlockPos> line = UltreiaConfig.BRIDGE_GEOMETRY.useRaster8ForBridges
                    ? RasterLine8.raster(a.getX(), a.getZ(), b.getX(), b.getZ(), 0)
                    : SupercoverLine.raster(a.getX(), a.getZ(), b.getX(), b.getZ(), 0);

            if (line == null || line.isEmpty()) {
                continue;
            }

            for (int k = 1; k < line.size(); k++) {
                BlockPos p = line.get(k);
                TerrainSampler.Sample s = sampler.sample(p.getX(), p.getZ());
                if (s == null) {
                    return Collections.emptyList();
                }
                out.add(new BlockPos(p.getX(), s.surfaceY, p.getZ()));
            }
        }

        return out;
    }
}
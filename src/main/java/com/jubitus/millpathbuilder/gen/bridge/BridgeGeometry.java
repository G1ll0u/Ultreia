package com.jubitus.millpathbuilder.gen.bridge;

import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.TerrainSampler;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public final class BridgeGeometry {

    private BridgeGeometry() {}

    public static final class Dir2 {
        public final int dx;
        public final int dz;

        public Dir2(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        public Dir2 perp() {
            return new Dir2(-dz, dx);
        }
    }

    public static final class SlabPlacement {
        public final int blockY;
        public final boolean top;

        public SlabPlacement(int blockY, boolean top) {
            this.blockY = blockY;
            this.top = top;
        }
    }

    public static Dir2 localDir(List<BlockPos> line, int idx) {
        if (line == null || line.isEmpty()) return new Dir2(1, 0);
        if (line.size() == 1) return new Dir2(1, 0);

        BlockPos prev = line.get(Math.max(0, idx - 1));
        BlockPos next = line.get(Math.min(line.size() - 1, idx + 1));

        int dx = Integer.signum(next.getX() - prev.getX());
        int dz = Integer.signum(next.getZ() - prev.getZ());

        if (dx == 0 && dz == 0) {
            BlockPos cur = line.get(idx);

            if (idx > 0) {
                BlockPos p = line.get(idx - 1);
                dx = Integer.signum(cur.getX() - p.getX());
                dz = Integer.signum(cur.getZ() - p.getZ());
            } else if (idx + 1 < line.size()) {
                BlockPos n = line.get(idx + 1);
                dx = Integer.signum(n.getX() - cur.getX());
                dz = Integer.signum(n.getZ() - cur.getZ());
            }
        }

        if (dx == 0 && dz == 0) {
            dx = 1;
        }

        return new Dir2(dx, dz);
    }

    public static SlabPlacement slabPlacementForSurfaceHalfY(int surfaceHalfY) {
        if ((surfaceHalfY & 1) == 1) {
            int y = (surfaceHalfY - 1) / 2;
            return new SlabPlacement(y, false);
        } else {
            int y = (surfaceHalfY / 2) - 1;
            return new SlabPlacement(y, true);
        }
    }

    public static int surfaceYFromHalf(int surfaceHalfY) {
        return (surfaceHalfY - 1) / 2;
    }

    public static int[] buildDeckHalfProfile(int startHalf, int endHalf, List<BlockPos> line, TerrainSampler sampler) {
        int n = line.size();
        int[] ideal = new int[n];

        for (int i = 0; i < n; i++) {
            double t = (n <= 1) ? 0.0 : ((double) i / (double) (n - 1));
            ideal[i] = (int) Math.round((1.0 - t) * (double) startHalf + t * (double) endHalf);
        }

        int len = Math.max(0, n - 1);
        int crown = 0;

        if (len >= Math.max(2, MPBConfig.BRIDGES.bridgeCrownMinLenBlocks)) {
            crown = Math.max(0, MPBConfig.BRIDGES.bridgeCrownHalfBlocks);

            if (MPBConfig.BRIDGES.extraWaterBridgeCrown && hasMeaningfulWaterInterior(line, sampler)) {
                crown += 1;
            }
        }

        if (crown > 0 && n >= 3) {
            for (int i = 1; i < n - 1; i++) {
                double t = (double) i / (double) (n - 1);
                double arch = 1.0 - Math.abs(2.0 * t - 1.0);
                ideal[i] += (int) Math.round((double) crown * arch);
            }
        }

        int maxStepHalf = Math.max(0, MPBConfig.BRIDGES.maxDeckDeltaHalfPerBlock);
        if (maxStepHalf <= 0) {
            return ideal;
        }

        int[] out = new int[n];
        out[0] = ideal[0];

        for (int i = 1; i < n; i++) {
            int want = ideal[i];
            int prev = out[i - 1];
            int d = want - prev;

            if (d > maxStepHalf) want = prev + maxStepHalf;
            else if (d < -maxStepHalf) want = prev - maxStepHalf;

            out[i] = want;
        }

        for (int i = n - 2; i >= 0; i--) {
            int want = out[i];
            int next = out[i + 1];
            int d = want - next;

            if (d > maxStepHalf) want = next + maxStepHalf;
            else if (d < -maxStepHalf) want = next - maxStepHalf;

            out[i] = want;
        }

        return out;
    }

    public static int[] buildDeckHalfProfile(BridgeSpan span, TerrainSampler sampler) {
        return buildDeckHalfProfile(
                span.startDeckSurfaceHalfY,
                span.endDeckSurfaceHalfY,
                span.centerline,
                sampler
        );
    }

    public static boolean hasMeaningfulWaterInterior(List<BlockPos> line, TerrainSampler sampler) {
        if (line == null || line.size() < 3 || sampler == null) return false;

        int run = 0;
        int best = 0;

        for (int i = 1; i < line.size() - 1; i++) {
            BlockPos p = line.get(i);
            TerrainSampler.Sample s = sampler.sample(p.getX(), p.getZ());

            boolean water = s != null && s.isWater && !s.isOcean;
            if (water) {
                run++;
                if (run > best) best = run;
            } else {
                run = 0;
            }
        }

        return best >= Math.max(2, MPBConfig.BRIDGE_AWARE_PATHFINDING.minWaterRunBlocks);
    }
}
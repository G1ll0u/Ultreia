package com.jubitus.ultreia.gen.bridge;

import com.jubitus.ultreia.config.MPBConfig;
import com.jubitus.ultreia.terrain.TerrainSampler;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.List;

public final class BridgeGeometry {

    private BridgeGeometry() {}

    /** Walkable deck width of every bridge, in blocks (odd); independent of roadWidth. */
    public static final int DECK_WIDTH_BLOCKS = 3;

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

        // Sample direction from an interior index, never the very first/last point. A bridge
        // centerline is one straight raster line, but its raw first/last step can land on a
        // single Bresenham-phase artifact (e.g. a diagonal step) that isn't representative of the
        // line's real heading — using it as-is rotates the endpoint's wall/lateral perpendicular
        // away from the approach road's true direction, so the entrance walls end up offset from
        // where the road actually arrives. Borrowing the direction from one step in (a symmetric,
        // interior neighbor window) gives the steady-state staircase direction instead.
        int sampleIdx = clampInterior(idx, line.size());

        BlockPos prev = line.get(Math.max(0, sampleIdx - 1));
        BlockPos next = line.get(Math.min(line.size() - 1, sampleIdx + 1));

        int dx = Integer.signum(next.getX() - prev.getX());
        int dz = Integer.signum(next.getZ() - prev.getZ());

        if (dx == 0 && dz == 0) {
            BlockPos cur = line.get(sampleIdx);

            if (sampleIdx > 0) {
                BlockPos p = line.get(sampleIdx - 1);
                dx = Integer.signum(cur.getX() - p.getX());
                dz = Integer.signum(cur.getZ() - p.getZ());
            } else if (sampleIdx + 1 < line.size()) {
                BlockPos n = line.get(sampleIdx + 1);
                dx = Integer.signum(n.getX() - cur.getX());
                dz = Integer.signum(n.getZ() - cur.getZ());
            }
        }

        if (dx == 0 && dz == 0) {
            dx = 1;
        }

        return new Dir2(dx, dz);
    }

    private static int clampInterior(int idx, int size) {
        if (size <= 2) return idx; // no interior point to borrow from on a 1-2 point line
        if (idx <= 0) return 1;
        if (idx >= size - 1) return size - 2;
        return idx;
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

    public static int naturalSurfaceHalfY(int surfaceY) {
        return surfaceY * 2 + 2;
    }

    public static int walkSurfaceBlockYFloor(int surfaceHalfY) {
        return surfaceHalfY / 2;
    }

    /** The deck profile of a span, in half-blocks, one value per centerline cell. */
    public static int[] buildDeckHalfProfile(BridgeSpan span) {
        if (span == null) return new int[0];
        return computeDeckProfile(span.startDeckSurfaceHalfY, span.endDeckSurfaceHalfY, span.centerline,
                span.crownHalf, span.crownMinLen);
    }

    /** Deck profile for a candidate that is not a span yet (feasibility). */
    public static int[] buildDeckHalfProfile(int startHalf, int endHalf, List<BlockPos> line, int crown, int crownMinLen) {
        return computeDeckProfile(startHalf, endHalf, line, crown, crownMinLen);
    }

    /** Crown height in half-blocks: the template's value, else the global one, plus the water bonus. */
    public static int effectiveCrownHalf(@Nullable Integer templateCrown, List<BlockPos> line, TerrainSampler sampler) {
        int crown = templateCrown != null ? Math.max(0, templateCrown)
                : Math.max(0, MPBConfig.BRIDGES.bridgeCrownHalfBlocks);
        if (crown > 0 && MPBConfig.BRIDGES.extraWaterBridgeCrown && hasMeaningfulWaterInterior(line, sampler)) {
            crown += 1;
        }
        return crown;
    }

    public static int effectiveCrownMinLen(@Nullable Integer templateMinLen) {
        return templateMinLen != null ? Math.max(2, templateMinLen)
                : Math.max(2, MPBConfig.BRIDGES.bridgeCrownMinLenBlocks);
    }

    // Returns the max allowed endpoint height delta (in half-blocks) for a given bridge length.
    public static int maxBridgeDeltaHalfForLen(int lenBlocks) {
        double slopeTan = Math.tan(Math.toRadians(Math.max(0.5, MPBConfig.BRIDGES.maxBridgeSlopeDegrees)));
        return (int) Math.round(lenBlocks * slopeTan * 2.0);
    }

    private static int[] computeDeckProfile(int startHalf, int endHalf, List<BlockPos> line,
                                            int crown, int crownMinLen) {
        int n = line.size();
        if (n == 0) return new int[0];
        if (n == 1) return new int[]{startHalf};

        int maxStepHalf = Math.max(1, MPBConfig.BRIDGES.maxDeckDeltaHalfPerBlock);
        int len = n - 1;

        // Cap crown so the sine arch slope never exceeds the step budget at the endpoints.
        // Sine arch slope near t=0 or t=1 is approximately crown*π/len (in half-blocks per step).
        // Combined with the linear baseline slope |endHalf-startHalf|/len, both must fit in maxStepHalf.
        int effectiveCrown = 0;
        if (crown > 0 && len >= Math.max(2, crownMinLen) && n >= 3) {
            double budget = (double) maxStepHalf * len - Math.abs(endHalf - startHalf);
            if (budget > 0.0) effectiveCrown = Math.min(crown, (int) Math.floor(budget / Math.PI));
        }

        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            double t = (double) i / (double) len;
            double base = (1.0 - t) * startHalf + t * endHalf;
            double arch = effectiveCrown > 0 ? effectiveCrown * Math.sin(Math.PI * t) : 0.0;
            out[i] = (int) Math.round(base + arch);
        }
        // Lock endpoints exactly — rounding must not drift them.
        out[0] = startHalf;
        out[n - 1] = endHalf;

        // Interior-only clamping: endpoints stay pinned so road-bridge junctions are always exact.
        if (n >= 3) {
            for (int i = 1; i < n - 1; i++) {
                int d = out[i] - out[i - 1];
                if (d > maxStepHalf) out[i] = out[i - 1] + maxStepHalf;
                else if (d < -maxStepHalf) out[i] = out[i - 1] - maxStepHalf;
            }
            for (int i = n - 2; i >= 1; i--) {
                int d = out[i] - out[i + 1];
                if (d > maxStepHalf) out[i] = out[i + 1] + maxStepHalf;
                else if (d < -maxStepHalf) out[i] = out[i + 1] - maxStepHalf;
            }
        }
        out[0] = startHalf;
        out[n - 1] = endHalf;

        return out;
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
package com.jubitus.ultreia.bridge;

import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.terrain.TerrainView;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.List;

/** Deck heights of a bridge, in half-blocks: straight line between both ends plus a sine crown. */
public final class BridgeDeckProfile {

    // Maximum slope angle (degrees) between bridge start and end deck heights.
    private static final double MAX_BRIDGE_SLOPE_DEGREES = 10.0;
    // Extra lift applied near the middle of bridges, in HALF blocks.
    private static final int BRIDGE_CROWN_HALF_BLOCKS = 4;
    // Only apply crown lift on bridges at least this long.
    private static final int BRIDGE_CROWN_MIN_LEN_BLOCKS = 8;
    // If true, water bridges get +1 extra HALF block of crown compared to land bridges.
    private static final boolean EXTRA_WATER_BRIDGE_CROWN = true;

    private BridgeDeckProfile() {}

    /** The deck profile of a span, one value per centerline cell. */
    public static int[] of(BridgeSpan span) {
        if (span == null) return new int[0];
        return of(span.startDeckSurfaceHalfY, span.endDeckSurfaceHalfY, span.centerline,
                span.crownHalf, span.crownMinLen);
    }

    /** Deck profile for a candidate that is not a span yet (feasibility). */
    public static int[] of(int startHalf, int endHalf, List<BlockPos> line, int crown, int crownMinLen) {
        int n = line.size();
        if (n == 0) return new int[0];
        if (n == 1) return new int[]{startHalf};

        int maxStepHalf = Math.max(1, HalfY.MAX_DECK_DELTA_HALF_PER_BLOCK);
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

    /** Crown height in half-blocks: the template's value, else the global one, plus the water bonus. */
    public static int crownHalf(@Nullable Integer templateCrown, List<BlockPos> line, TerrainView sampler) {
        int crown = templateCrown != null ? Math.max(0, templateCrown)
                : Math.max(0, BRIDGE_CROWN_HALF_BLOCKS);
        if (crown > 0 && EXTRA_WATER_BRIDGE_CROWN && hasMeaningfulWaterInterior(line, sampler)) {
            crown += 1;
        }
        return crown;
    }

    public static int crownMinLen(@Nullable Integer templateMinLen) {
        return templateMinLen != null ? Math.max(2, templateMinLen)
                : Math.max(2, BRIDGE_CROWN_MIN_LEN_BLOCKS);
    }

    /** Max height difference between both deck ends, in half-blocks, for a bridge of this length. */
    public static int maxEndDeltaHalf(int lenBlocks) {
        double slopeTan = Math.tan(Math.toRadians(Math.max(0.5, MAX_BRIDGE_SLOPE_DEGREES)));
        return (int) Math.round(lenBlocks * slopeTan * 2.0);
    }

    private static boolean hasMeaningfulWaterInterior(List<BlockPos> line, TerrainView sampler) {
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

        return best >= Math.max(2, BridgeClearanceCheck.MIN_WATER_RUN_BLOCKS);
    }
}

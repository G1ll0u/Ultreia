package com.jubitus.ultreia.job;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.chunk.ChunkManager;
import net.minecraft.util.math.BlockPos;

/** Server-thread time spent on one edge, split by step, for the logPerformance debug line. */
final class EdgePerf {

    // GATES and ROAD_SCAN are parts of the edge preparation; PREP keeps the rest (mostly chunk preloading).
    enum Step { PREP, GATES, ROAD_SCAN, SEARCH, PAINT, RELEASE, SAVE }

    private static final int STEPS = Step.values().length;

    private final long[] stepNs = new long[STEPS];
    // Steps of the tick in progress, and of the edge's worst tick so far.
    private final long[] tickStepNs = new long[STEPS];
    private final long[] worstTickStepNs = new long[STEPS];
    private long edgeStartNs;
    private long worstTickNs;
    private int ticks;

    void startEdge() {
        java.util.Arrays.fill(stepNs, 0L);
        java.util.Arrays.fill(worstTickStepNs, 0L);
        edgeStartNs = System.nanoTime();
        worstTickNs = 0;
        ticks = 0;
    }

    void add(Step step, long sinceNs) {
        long ns = System.nanoTime() - sinceNs;
        stepNs[step.ordinal()] += ns;
        tickStepNs[step.ordinal()] += ns;
    }

    /** A part of {@code parent} timed on its own: counted in {@code part}, taken out of the parent's total. */
    void addPart(Step parent, Step part, long sinceNs) {
        long ns = System.nanoTime() - sinceNs;
        stepNs[part.ordinal()] += ns;
        tickStepNs[part.ordinal()] += ns;
        stepNs[parent.ordinal()] -= ns;
        tickStepNs[parent.ordinal()] -= ns;
    }

    void addTick(long spentNs) {
        ticks++;
        if (spentNs > worstTickNs) {
            worstTickNs = spentNs;
            System.arraycopy(tickStepNs, 0, worstTickStepNs, 0, STEPS);
        }
        java.util.Arrays.fill(tickStepNs, 0L);
    }

    void log(BlockPos start, BlockPos goal, ChunkManager chunks) {
        Ultreia.LOGGER.info("[Ultreia] Perf edge ({},{})->({},{}): wall {} s over {} ticks, worst tick {} ms ({})"
                        + " | prep {} ms, gates {} ms, road_scan {} ms, search {} ms, paint {} ms, release {} ms,"
                        + " save {} ms | {}",
                start.getX(), start.getZ(), goal.getX(), goal.getZ(),
                String.format("%.1f", (System.nanoTime() - edgeStartNs) / 1e9), ticks, ms(worstTickNs),
                worstTickSteps(),
                ms(stepNs[0]), ms(stepNs[1]), ms(stepNs[2]), ms(stepNs[3]), ms(stepNs[4]), ms(stepNs[5]),
                ms(stepNs[6]),
                chunks.takePerfSummary());
    }

    /** Steps of the worst tick that took at least 1 ms, plus the time no step accounts for. */
    private String worstTickSteps() {
        StringBuilder sb = new StringBuilder();
        long counted = 0;
        for (Step s : Step.values()) {
            long ns = worstTickStepNs[s.ordinal()];
            counted += ns;
            if (ms(ns) < 1) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(s.name().toLowerCase()).append(' ').append(ms(ns));
        }
        long other = ms(worstTickNs - counted);
        if (other >= 1) sb.append(sb.length() > 0 ? ", " : "").append("other ").append(other);
        return sb.toString();
    }

    static long ms(long ns) {
        return ns / 1_000_000L;
    }
}

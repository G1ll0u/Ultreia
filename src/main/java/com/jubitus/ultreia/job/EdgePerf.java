package com.jubitus.ultreia.job;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.chunk.ChunkManager;
import net.minecraft.util.math.BlockPos;

/** Server-thread time spent on one edge, split by step, for the logPerformance debug line. */
final class EdgePerf {

    enum Step { PREP, SEARCH, PAINT, RELEASE, SAVE }

    private final long[] stepNs = new long[Step.values().length];
    private long edgeStartNs;
    private long worstTickNs;
    private int ticks;

    void startEdge() {
        java.util.Arrays.fill(stepNs, 0L);
        edgeStartNs = System.nanoTime();
        worstTickNs = 0;
        ticks = 0;
    }

    void add(Step step, long sinceNs) {
        stepNs[step.ordinal()] += System.nanoTime() - sinceNs;
    }

    void addTick(long spentNs) {
        ticks++;
        if (spentNs > worstTickNs) worstTickNs = spentNs;
    }

    void log(BlockPos start, BlockPos goal, ChunkManager chunks) {
        Ultreia.LOGGER.info("[Ultreia] Perf edge ({},{})->({},{}): wall {} s over {} ticks, worst tick {} ms"
                        + " | prep {} ms, search {} ms, paint {} ms, release {} ms, save {} ms | {}",
                start.getX(), start.getZ(), goal.getX(), goal.getZ(),
                String.format("%.1f", (System.nanoTime() - edgeStartNs) / 1e9), ticks, ms(worstTickNs),
                ms(stepNs[0]), ms(stepNs[1]), ms(stepNs[2]), ms(stepNs[3]), ms(stepNs[4]),
                chunks.takePerfSummary());
    }

    static long ms(long ns) {
        return ns / 1_000_000L;
    }
}

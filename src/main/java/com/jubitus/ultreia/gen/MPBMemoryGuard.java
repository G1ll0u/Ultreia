package com.jubitus.ultreia.gen;

public final class MPBMemoryGuard {

    private MPBMemoryGuard() {}

    public static boolean isNearOOM(int thresholdPercent) {
        if (thresholdPercent <= 0) return false;
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        if (max <= 0) return false;
        long used = rt.totalMemory() - rt.freeMemory();
        return used * 100L / max >= thresholdPercent;
    }

    public static int heapUsedPercent() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        if (max <= 0) return 0;
        long used = rt.totalMemory() - rt.freeMemory();
        return (int) (used * 100L / max);
    }
}

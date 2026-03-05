package com.jubitus.millpathbuilder.gen;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.List;

public final class MPBProtectedAreas {

    public static final class Zone {
        public final int minX, maxX, minZ, maxZ;
        public final int penalty;
        public final boolean noPaint; // hard forbid for painting/path endpoints

        public Zone(int minX, int maxX, int minZ, int maxZ, int penalty, boolean noPaint) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.penalty = penalty;
            this.noPaint = noPaint;
        }

        public boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private final Long2ObjectOpenHashMap<List<Zone>> byChunk = new Long2ObjectOpenHashMap<>();

    public void addPenaltySquare(int cx, int cz, int radius, int penalty) {
        addRect(cx - radius, cx + radius, cz - radius, cz + radius, penalty, false);
    }

    public void addNoPaintSquare(int cx, int cz, int radius, int penalty) {
        addRect(cx - radius, cx + radius, cz - radius, cz + radius, penalty, true);
    }

    public void addNoPaintRect(int minX, int maxX, int minZ, int maxZ, int penalty) {
        addRect(minX, maxX, minZ, maxZ, penalty, true);
    }

    public void addRect(int minX, int maxX, int minZ, int maxZ, int penalty, boolean noPaint) {
        Zone z = new Zone(minX, maxX, minZ, maxZ, penalty, noPaint);

        int cminX = minX >> 4;
        int cmaxX = maxX >> 4;
        int cminZ = minZ >> 4;
        int cmaxZ = maxZ >> 4;

        for (int cxk = cminX; cxk <= cmaxX; cxk++) {
            for (int czk = cminZ; czk <= cmaxZ; czk++) {
                long k = chunkKey(cxk, czk);
                List<Zone> list = byChunk.get(k);
                if (list == null) {
                    list = new ArrayList<>(4);
                    byChunk.put(k, list);
                }
                list.add(z);
            }
        }
    }

    // Keep existing call sites working: contains() means "noPaint" only.
    public boolean contains(int x, int z) {
        List<Zone> list = byChunk.get(chunkKey(x >> 4, z >> 4));
        if (list == null) return false;
        for (Zone zz : list) {
            if (zz.noPaint && zz.contains(x, z)) return true;
        }
        return false;
    }

    public int penaltyAt(int x, int z) {
        List<Zone> list = byChunk.get(chunkKey(x >> 4, z >> 4));
        if (list == null) return 0;
        int best = 0;
        for (Zone zz : list) {
            if (zz.penalty > 0 && zz.contains(x, z)) best = Math.max(best, zz.penalty);
        }
        return best;
    }

    private static long chunkKey(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }
}
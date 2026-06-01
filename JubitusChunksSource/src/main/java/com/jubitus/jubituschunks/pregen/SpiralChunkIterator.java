package com.jubitus.jubituschunks.pregen;

import java.util.NoSuchElementException;

public class SpiralChunkIterator {

    public static class ChunkCoord {
        public final int x;
        public final int z;
        public ChunkCoord(int x, int z) { this.x = x; this.z = z; }
    }

    private final int cx, cz;
    private final int r;          // Rayon en étapes
    private final int stride;     // Saut de chunks
    private final long max;
    private long steps = 0;

    public SpiralChunkIterator(int centerX, int centerZ, int radiusSteps, int strideChunks) {
        this.cx = centerX;
        this.cz = centerZ;
        this.r = radiusSteps;
        this.stride = Math.max(1, strideChunks);

        // Nombre total de points dans un carré de (2r + 1) de côté
        long d = (long) (r * 2 + 1);
        this.max = d * d;
    }

    public long getSteps() { return steps; }
    public long getMaxSteps() { return max; }
    public int getRadiusSteps() { return r; }
    public int getStrideChunks() { return stride; }

    public boolean hasNext() {
        return steps < max;
    }

    /**
     * Calcule les coordonnées X,Z pour n'importe quel index 'n' de la spirale.
     * Formule mathématique pure (Stateless).
     */
    public ChunkCoord getCoordAt(long n) {
        if (n <= 0) return new ChunkCoord(cx, cz);

        // 1. Trouver dans quelle "couche" (ring) se trouve n
        long k = (long) Math.ceil((Math.sqrt(n + 1.0) - 1.0) / 2.0);
        long t = 2 * k + 1; // Taille du côté du carré pour cette couche
        long m = t * t - 1; // Index maximum de cette couche
        long side = 2 * k;  // Longueur d'un côté de cette couche

        long offset = m - n;
        int x, z;

        if (offset < side) {
            // Bas (vers la gauche)
            x = (int) (k - offset);
            z = (int) (-k);
        } else if (offset < 2 * side) {
            // Gauche (vers le haut)
            x = (int) (-k);
            z = (int) (-k + (offset - side));
        } else if (offset < 3 * side) {
            // Haut (vers la droite)
            x = (int) (-k + (offset - 2 * side));
            z = (int) k;
        } else {
            // Droite (vers le bas)
            x = (int) k;
            z = (int) (k - (offset - 3 * side));
        }

        return new ChunkCoord(cx + x * stride, cz + z * stride);
    }

    public ChunkCoord peek() {
        if (!hasNext()) throw new NoSuchElementException();
        return getCoordAt(steps);
    }

    public void advance() {
        if (!hasNext()) throw new NoSuchElementException();
        steps++;
    }

    public void setSteps(long stepIndex) {
        this.steps = Math.min(stepIndex, max);
    }
}
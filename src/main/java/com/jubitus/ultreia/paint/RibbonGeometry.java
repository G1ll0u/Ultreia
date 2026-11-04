package com.jubitus.ultreia.paint;

/** Cell-versus-ribbon and cell-versus-disk tests of the road footprint, in block coordinates (cell centers at +0.5). */
final class RibbonGeometry {

    private RibbonGeometry() {}

    static double segmentParam01(double px, double pz,
                                  double ax, double az,
                                  double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lenSq = dx * dx + dz * dz;

        if (lenSq <= 1.0e-9) return 0.0;

        double t = ((px - ax) * dx + (pz - az) * dz) / lenSq;
        if (t < 0.0) return 0.0;
        if (t > 1.0) return 1.0;
        return t;
    }

    static double pointToSegmentDistanceSqClamped(double px, double pz,
                                                   double ax, double az,
                                                   double bx, double bz) {
        double t = segmentParam01(px, pz, ax, az, bx, bz);
        double cx = ax + (bx - ax) * t;
        double cz = az + (bz - az) * t;

        double dx = px - cx;
        double dz = pz - cz;
        return dx * dx + dz * dz;
    }

    static boolean cellIntersectsRibbon(int x,
                                         int z,
                                         double ax,
                                         double az,
                                         double bx,
                                         double bz,
                                         double halfWidth) {
        double nearestX = x + 0.5;
        double nearestZ = z + 0.5;
        double bestDistSq = Double.POSITIVE_INFINITY;

        for (int sx = 0; sx <= 1; sx++) {
            double px = x + sx;
            for (int sz = 0; sz <= 1; sz++) {
                double pz = z + sz;
                double d2 = pointToSegmentDistanceSqClamped(px, pz, ax, az, bx, bz);
                if (d2 < bestDistSq) {
                    bestDistSq = d2;
                    nearestX = px;
                    nearestZ = pz;
                }
            }
        }

        double centerD2 = pointToSegmentDistanceSqClamped(x + 0.5, z + 0.5, ax, az, bx, bz);
        if (centerD2 < bestDistSq) {
            bestDistSq = centerD2;
            nearestX = x + 0.5;
            nearestZ = z + 0.5;
        }

        if (bestDistSq <= halfWidth * halfWidth) {
            return true;
        }

        double segDx = bx - ax;
        double segDz = bz - az;
        double segLen = Math.sqrt(segDx * segDx + segDz * segDz);
        if (segLen <= 1.0e-9) {
            return false;
        }

        double nx = -segDz / segLen;
        double nz = segDx / segLen;

        double support = 0.5 * Math.abs(nx) + 0.5 * Math.abs(nz);

        double t = segmentParam01(nearestX, nearestZ, ax, az, bx, bz);
        double cx = ax + (bx - ax) * t;
        double cz = az + (bz - az) * t;

        double perp = Math.abs((nearestX - cx) * nx + (nearestZ - cz) * nz);
        return perp <= halfWidth + support;
    }

    static boolean cellIntersectsDisk(int x,
                                       int z,
                                       double cx,
                                       double cz,
                                       double radius) {
        double nearestX = Math.max((double) x, Math.min(cx, (double) x + 1.0));
        double nearestZ = Math.max((double) z, Math.min(cz, (double) z + 1.0));

        double dx = nearestX - cx;
        double dz = nearestZ - cz;
        return dx * dx + dz * dz <= radius * radius;
    }
}

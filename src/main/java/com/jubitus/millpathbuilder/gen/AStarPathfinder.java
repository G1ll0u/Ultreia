package com.jubitus.millpathbuilder.gen;

import com.jubitus.millpathbuilder.config.MPBConfig;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.*;

import it.unimi.dsi.fastutil.longs.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.*;

public final class AStarPathfinder {

    public enum StepResult { RUNNING, FOUND, FAILED }

    private static final class Node {
        final int x, z;
        final double f;

        Node(int x, int z, double f) {
            this.x = x;
            this.z = z;
            this.f = f;
        }
    }

    public static final class Session {
        private int minX, maxX, minZ, maxZ;
        private int sx, sz;
        private int maxRadSq;

        private final WorldServer world;
        private final TerrainSampler sampler;
        private final int step;
        private int gx, gz;
        private long goalK;

        private final Long2DoubleOpenHashMap gScore = new Long2DoubleOpenHashMap();
        private final Long2LongOpenHashMap cameFrom = new Long2LongOpenHashMap();
        private final PriorityQueue<Node> open = new PriorityQueue<>(new Comparator<Node>() {
            @Override
            public int compare(Node a, Node b) {
                return Double.compare(a.f, b.f);
            }
        });
        private final LongOpenHashSet closed = new LongOpenHashSet();

        private int it = 0;
        private int maxIt;

        private boolean failed = false;
        private String failReason = "unknown";

        private long foundKey = Long.MIN_VALUE;
        private boolean reconstructing = false;

        private final ArrayList<long[]> rev = new ArrayList<>();
        private long recCur = Long.MIN_VALUE;
        private int recGuard = 0;

        private List<BlockPos> path = Collections.emptyList();

        private int skipOcean = 0, skipBlacklist = 0, skipBlocked = 0, skipSlope = 0;

        private int corridorHalfWidth;
        private long ax, az, bx, bz;
        private long segDx, segDz;
        private long segLenSq;

        Session(WorldServer world, BlockPos start, BlockPos goal, TerrainSampler sampler) {
            this.world = world;
            this.sampler = sampler;

            this.step = Math.max(2, MPBConfig.PATHFINDING.gridStep);

            int sx0 = snap(start.getX(), step);
            int sz0 = snap(start.getZ(), step);
            int gx0 = snap(goal.getX(), step);
            int gz0 = snap(goal.getZ(), step);

            int[] sFix = nearestValidGrid(sx0, sz0, step, sampler);
            int[] gFix = nearestValidGrid(gx0, gz0, step, sampler);

            if (sFix == null) {
                failed = true;
                failReason = "start forbidden (no valid grid point near snapped start)";
                return;
            }
            if (gFix == null) {
                failed = true;
                failReason = "goal forbidden (no valid grid point near snapped goal)";
                return;
            }

            this.sx = sFix[0];
            this.sz = sFix[1];
            this.gx = gFix[0];
            this.gz = gFix[1];

            this.ax = this.sx;
            this.az = this.sz;
            this.bx = this.gx;
            this.bz = this.gz;

            this.segDx = bx - ax;
            this.segDz = bz - az;
            this.segLenSq = segDx * segDx + segDz * segDz;

// corridor width: tighter than searchMargin to keep A* directed
            int m = Math.max(96, MPBConfig.PATHFINDING.searchMargin / 2);
            this.corridorHalfWidth = m;

            int marginBase = Math.max(64, MPBConfig.PATHFINDING.searchMargin);

            TerrainSampler.Sample ss = sampler.sample(this.sx, this.sz);
            TerrainSampler.Sample gs = sampler.sample(this.gx, this.gz);
            int dyGoal = Math.abs(gs.surfaceY - ss.surfaceY);

            int margin = marginBase + Math.min(2048, dyGoal * 6);

            this.minX = Math.min(sx, gx) - margin;
            this.maxX = Math.max(sx, gx) + margin;
            this.minZ = Math.min(sz, gz) - margin;
            this.maxZ = Math.max(sz, gz) + margin;

            int mr = Math.max(0, MPBConfig.PATHFINDING.maxSearchRadius);
            this.maxRadSq = (mr > 0) ? (mr * mr) : -1;

            long startK = key(sx, sz);
            this.goalK = key(gx, gz);

            this.maxIt = MPBConfig.PATHFINDING.maxIterations;

            gScore.defaultReturnValue(Double.POSITIVE_INFINITY);
            cameFrom.defaultReturnValue(Long.MIN_VALUE);


            if (sampler.forbidden(ss, TerrainSampler.Mode.PATHFINDING)) {
                failed = true;
                failReason = "start forbidden: " + sampler.describeForbidden(ss, TerrainSampler.Mode.PATHFINDING);
                return;
            }
            if (sampler.forbidden(gs, TerrainSampler.Mode.PATHFINDING)) {
                failed = true;
                failReason = "goal forbidden: " + sampler.describeForbidden(gs, TerrainSampler.Mode.PATHFINDING);
                return;
            }

            gScore.put(startK, 0.0);
            open.add(new Node(sx, sz, heuristic(sx, sz, gx, gz)));
        }

        public StepResult advance(long deadlineNs) {
            if (failed) return StepResult.FAILED;
            if (!reconstructing) {
                while (!open.isEmpty() && it < maxIt && System.nanoTime() < deadlineNs) {
                    it++;
                    Node cur = open.poll();
                    long ck = key(cur.x, cur.z);

                    if (!closed.add(ck)) continue;

                    if (ck == goalK || distSq(cur.x, cur.z, gx, gz) <= (double) step * (double) step) {
                        foundKey = ck;
                        reconstructing = true;
                        recCur = ck;
                        recGuard = 0;
                        break;
                    }

                    TerrainSampler.Sample cs = sampler.sample(cur.x, cur.z);
                    if (sampler.forbidden(cs, TerrainSampler.Mode.PATHFINDING)) {
                        bumpForbidden(cs);
                        continue;
                    }

                    for (int[] d : neighbors(step)) {
                        int nx = cur.x + d[0];
                        int nz = cur.z + d[1];
                        long nk = key(nx, nz);

                        if (!inCorridor(nx, nz)) continue;
                        if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;

                        if (maxRadSq > 0) {
                            long dx0 = (long) nx - (long) sx;
                            long dz0 = (long) nz - (long) sz;
                            if ((dx0 * dx0 + dz0 * dz0) > (long) maxRadSq) continue;
                        }

                        if (closed.contains(nk)) continue;

                        TerrainSampler.Sample ns = sampler.sample(nx, nz);
                        if (sampler.forbidden(ns, TerrainSampler.Mode.PATHFINDING)) {
                            bumpForbidden(ns);
                            continue;
                        }

                        int dy = Math.abs(ns.surfaceY - cs.surfaceY);
                        int maxDy = Math.max(1, MPBConfig.PATHFINDING.maxYDeltaPerStep);

// Hard rule: never accept a segment that would require jumping
                        if (!segmentIsWalkable(cur.x, cur.z, nx, nz)) {
                            skipSlope++;
                            continue;
                        }

// Still keep the basic delta constraint for stability
                        if (dy > Math.max(2, maxDy)) {
                            skipSlope++;
                            continue;
                        }

                        double base = Math.sqrt((double) d[0] * (double) d[0] + (double) d[1] * (double) d[1]);

                        double slopeCost = (double) dy * MPBConfig.PATHFINDING.slopePenalty;

// extra penalty for "earthworks-needed" steps
                        if (MPBConfig.PATHFINDING.allowEarthworks && dy > maxDy) {
                            double extra = (double) (dy - maxDy);
                            slopeCost += extra * extra * 60.0; // strong deterrent but not impossible
                        }

                        double tentative = gScore.get(ck) + base + slopeCost + sampler.extraCost(ns, TerrainSampler.Mode.PATHFINDING);

                        if (tentative < gScore.get(nk)) {
                            cameFrom.put(nk, ck);
                            gScore.put(nk, tentative);
                            double f = tentative + heuristic(nx, nz, gx, gz);
                            open.add(new Node(nx, nz, f));
                        }
                    }
                }

                if (!reconstructing) {
                    if (open.isEmpty()) {
                        failed = true;
                        failReason = "open exhausted";
                        return StepResult.FAILED;
                    }
                    if (it >= maxIt) {
                        failed = true;
                        failReason = "max iterations reached";
                        return StepResult.FAILED;
                    }
                    return StepResult.RUNNING;
                }
            }

            while (reconstructing && System.nanoTime() < deadlineNs) {
                if (recCur == Long.MIN_VALUE) break;

                int x = (int) (recCur >> 32);
                int z = (int) recCur;
                TerrainSampler.Sample s = sampler.sample(x, z);
                rev.add(new long[]{x, s.surfaceY, z});

                long prev = cameFrom.get(recCur);
                recCur = prev == Long.MIN_VALUE ? Long.MIN_VALUE : prev;

                recGuard++;
                if (recGuard > 400_000) {
                    failed = true;
                    failReason = "reconstruct guard hit (cycle?)";
                    reconstructing = false;
                    return StepResult.FAILED;
                }

                if (recCur == Long.MIN_VALUE) {
                    reconstructing = false;
                    Collections.reverse(rev);

                    ArrayList<BlockPos> out = new ArrayList<>(rev.size());
                    for (long[] p : rev) out.add(new BlockPos((int) p[0], (int) p[1], (int) p[2]));
                    this.path = out;
                    return StepResult.FOUND;
                }
            }

            return StepResult.RUNNING;
        }
        private static int[] nearestValidGrid(int x0, int z0, int step, TerrainSampler sampler) {
            int maxR = step * 2;

            int bestX = 0, bestZ = 0;
            double best = Double.POSITIVE_INFINITY;
            boolean found = false;

            for (int dz = -maxR; dz <= maxR; dz += step) {
                for (int dx = -maxR; dx <= maxR; dx += step) {
                    int x = x0 + dx;
                    int z = z0 + dz;

                    TerrainSampler.Sample s = sampler.sample(x, z);
                    if (sampler.forbidden(s, TerrainSampler.Mode.PATHFINDING)) continue;
                    if (s.isOcean || s.isWater) continue;

                    double d2 = (double) dx * (double) dx + (double) dz * (double) dz;
                    if (d2 < best) {
                        best = d2;
                        bestX = x;
                        bestZ = z;
                        found = true;
                    }
                }
            }

            return found ? new int[]{bestX, bestZ} : null;
        }
        public List<BlockPos> getPath() {
            return path;
        }

        public String failureSummary(boolean detailed) {
            if (!detailed) return failReason;
            return failReason
                    + " it=" + it
                    + " closed=" + closed.size()
                    + " open=" + open.size()
                    + " rejects{ocean=" + skipOcean
                    + ", blacklisted=" + skipBlacklist
                    + ", blocked=" + skipBlocked
                    + ", slope=" + skipSlope + "}";
        }

        private int maxStepDeltaAlongSegment(int x0, int z0, int x1, int z1) {
            int dx = x1 - x0;
            int dz = z1 - z0;

            int steps = Math.max(Math.abs(dx), Math.abs(dz));
            if (steps <= 1) return 0;

            double ix = (double) dx / (double) steps;
            double iz = (double) dz / (double) steps;

            int prevY = sampler.sample(x0, z0).surfaceY;
            int worst = 0;

            for (int i = 1; i <= steps; i++) {
                int xx = x0 + (int) Math.round(ix * i);
                int zz = z0 + (int) Math.round(iz * i);
                int yy = sampler.sample(xx, zz).surfaceY;

                int d = Math.abs(yy - prevY);
                if (d > worst) worst = d;
                int maxDy = Math.max(1, MPBConfig.PATHFINDING.maxYDeltaPerStep);
                if (MPBConfig.PATHFINDING.allowEarthworks) maxDy = Math.max(maxDy, 2);

// Early out only when clearly too steep
                if (worst > (maxDy + 1)) return worst;
                prevY = yy;
            }
            return worst;
        }

        private boolean segmentIsWalkable(int x0, int z0, int x1, int z1) {
            int worst = maxStepDeltaAlongSegment(x0, z0, x1, z1);

            int maxDy = Math.max(1, MPBConfig.PATHFINDING.maxYDeltaPerStep);

            // If earthworks are allowed, we can tolerate a bit more roughness; painter will smooth it.
            if (MPBConfig.PATHFINDING.allowEarthworks) {
                maxDy = Math.max(maxDy, 2);
            }

            // Worst is measured per "micro step" inside the segment, so allow up to maxDy.
            return worst <= maxDy;
        }
        private void bumpForbidden(TerrainSampler.Sample s) {
            TerrainSampler.ForbiddenReason r = sampler.forbiddenReason(s, TerrainSampler.Mode.PATHFINDING);
            if (r == TerrainSampler.ForbiddenReason.OCEAN) skipOcean++;
            else if (r == TerrainSampler.ForbiddenReason.BLACKLIST) skipBlacklist++;
            else if (r == TerrainSampler.ForbiddenReason.BLOCKED) skipBlocked++;
            else skipBlocked++;
        }
        private boolean inCorridor(int x, int z) {
            if (segLenSq <= 0) return true;

            long px = x;
            long pz = z;

            long apx = px - ax;
            long apz = pz - az;

            double t = (double)(apx * segDx + apz * segDz) / (double)segLenSq;
            if (t < 0.0) t = 0.0;
            else if (t > 1.0) t = 1.0;

            double cx = (double)ax + t * (double)segDx;
            double cz = (double)az + t * (double)segDz;

            double dx = (double)px - cx;
            double dz = (double)pz - cz;

            double d2 = dx * dx + dz * dz;
            double r = (double)corridorHalfWidth;

            return d2 <= r * r;
        }
    }

    public Session startSession(WorldServer world, BlockPos start, BlockPos goal, TerrainSampler sampler) {
        return new Session(world, start, goal, sampler);
    }

    private static int snap(int v, int step) {
        return Math.floorDiv(v, step) * step;
    }

    private static double heuristic(int x, int z, int gx, int gz) {
        return Math.sqrt(distSq(x, z, gx, gz));
    }

    private static double distSq(int x1, int z1, int x2, int z2) {
        long dx = (long) x1 - (long) x2;
        long dz = (long) z1 - (long) z2;
        return (double) dx * (double) dx + (double) dz * (double) dz;
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static List<int[]> neighbors(int step) {
        return Arrays.asList(
                new int[]{ step, 0}, new int[]{-step, 0}, new int[]{0, step}, new int[]{0, -step},
                new int[]{ step, step}, new int[]{ step, -step}, new int[]{-step, step}, new int[]{-step, -step}
        );
    }

}
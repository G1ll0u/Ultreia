package com.jubitus.millpathbuilder.gen;

import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.bridge.BridgeAwarePathResult;
import com.jubitus.millpathbuilder.gen.bridge.BridgeFeasibility;
import com.jubitus.millpathbuilder.gen.bridge.BridgeSpan;
import com.jubitus.millpathbuilder.gen.bridge.SupercoverLine;
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

import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

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

    private static final class BridgeMove {
        final int nx;
        final int nz;
        final double cost;
        final BridgeSpan span;

        BridgeMove(int nx, int nz, double cost, BridgeSpan span) {
            this.nx = nx;
            this.nz = nz;
            this.cost = cost;
            this.span = span;
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
        private final Long2ObjectOpenHashMap<BridgeSpan> cameBridge = new Long2ObjectOpenHashMap<>();
        private final Long2IntOpenHashMap distSinceBridge = new Long2IntOpenHashMap();

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

        private final ArrayList<Long> revKeys = new ArrayList<>();
        private long recCur = Long.MIN_VALUE;
        private int recGuard = 0;

        private BridgeAwarePathResult result = new BridgeAwarePathResult(Collections.<BlockPos>emptyList(), Collections.<BridgeSpan>emptyList());

        private int skipOcean = 0, skipBlacklist = 0, skipBlocked = 0, skipSlope = 0, skipBridge = 0;

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
            distSinceBridge.defaultReturnValue(Integer.MAX_VALUE);

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
            distSinceBridge.put(startK, Integer.MAX_VALUE / 4);
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

                    expandNormalMoves(cur, ck, cs);
                    expandBridgeMoves(cur, ck);
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

                revKeys.add(recCur);

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
                    Collections.reverse(revKeys);
                    buildResultFromReconstruction();
                    return StepResult.FOUND;
                }
            }

            return StepResult.RUNNING;
        }

        private void expandNormalMoves(Node cur, long ck, TerrainSampler.Sample cs) {
            for (int[] d : neighbors(step)) {
                int nx = cur.x + d[0];
                int nz = cur.z + d[1];
                long nk = key(nx, nz);

                if (!baseNodeChecks(nx, nz, nk)) continue;

                TerrainSampler.Sample ns = sampler.sample(nx, nz);
                if (sampler.forbidden(ns, TerrainSampler.Mode.PATHFINDING)) {
                    bumpForbidden(ns);
                    continue;
                }

                int dy = Math.abs(ns.surfaceY - cs.surfaceY);
                int maxDy = Math.max(1, MPBConfig.PATHFINDING.maxYDeltaPerStep);

                SegmentWalkability sw = segmentWalkability(cur.x, cur.z, nx, nz);
                if (sw.crossesWater) {
                    skipBridge++;
                    continue;
                }
                if (!sw.walkable) {
                    skipSlope++;
                    continue;
                }

                if (dy > Math.max(2, maxDy)) {
                    skipSlope++;
                    continue;
                }

                double base = Math.sqrt((double) d[0] * (double) d[0] + (double) d[1] * (double) d[1]);
                double slopeCost = (double) dy * MPBConfig.PATHFINDING.slopePenalty;

                if (MPBConfig.PATHFINDING.allowEarthworks && dy > maxDy) {
                    double extra = (double) (dy - maxDy);
                    slopeCost += extra * extra * 60.0;
                }

                double tentative = gScore.get(ck) + base + slopeCost + sampler.extraCost(ns, TerrainSampler.Mode.PATHFINDING);

                if (tentative < gScore.get(nk)) {
                    cameFrom.put(nk, ck);
                    cameBridge.remove(nk);
                    gScore.put(nk, tentative);

                    int prevDist = distSinceBridge.get(ck);
                    int nextDist = (prevDist >= Integer.MAX_VALUE / 8) ? prevDist : Math.min(1000000, prevDist + (int) Math.round(base));
                    distSinceBridge.put(nk, nextDist);

                    double f = tentative + heuristic(nx, nz, gx, gz);
                    open.add(new Node(nx, nz, f));
                }
            }
        }

        private void expandBridgeMoves(Node cur, long ck) {
            if (!MPBConfig.BRIDGE_AWARE_PATHFINDING.enabled) return;
            if (!MPBConfig.BRIDGES.enabled) return;

            int minSpacing = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.minDistanceBetweenBridgesBlocks);
            int distFromLastBridge = distSinceBridge.get(ck);
            if (distFromLastBridge < minSpacing) return;

            List<BridgeMove> moves = enumerateBridgeMoves(cur.x, cur.z, ck);
            for (BridgeMove mv : moves) {
                long nk = key(mv.nx, mv.nz);
                if (!baseNodeChecks(mv.nx, mv.nz, nk)) continue;
                if (closed.contains(nk)) continue;

                TerrainSampler.Sample ns = sampler.sample(mv.nx, mv.nz);
                if (sampler.forbidden(ns, TerrainSampler.Mode.PATHFINDING)) {
                    bumpForbidden(ns);
                    continue;
                }

                double tentative = gScore.get(ck) + mv.cost + sampler.extraCost(ns, TerrainSampler.Mode.PATHFINDING);
                if (tentative < gScore.get(nk)) {
                    cameFrom.put(nk, ck);
                    cameBridge.put(nk, mv.span);
                    gScore.put(nk, tentative);
                    distSinceBridge.put(nk, 0);

                    double f = tentative + heuristic(mv.nx, mv.nz, gx, gz);
                    open.add(new Node(mv.nx, mv.nz, f));
                }
            }
        }

        private List<BridgeMove> enumerateBridgeMoves(int x, int z, long ck) {
            ArrayList<BridgeMove> out = new ArrayList<BridgeMove>();

            TerrainSampler.Sample cur = sampler.sample(x, z);
            if (cur == null) return out;
            if (cur.isWater || cur.isOcean) return out;
            if (sampler.isProtectedNoPaint(x, z)) return out;

            final int minLen = Math.max(step * 2, MPBConfig.BRIDGE_AWARE_PATHFINDING.minBridgeLenBlocks);
            final int maxLen = Math.max(minLen, MPBConfig.BRIDGE_AWARE_PATHFINDING.maxProbeLenBlocks);
            final int lenStep = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.probeLenStepBlocks);
            final int maxCandidates = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.maxBridgeCandidatesPerNode);

            int accepted = 0;

            int[][] dirs = bridgeDirections();
            long parentK = cameFrom.get(ck);

            int inDx = 0;
            int inDz = 0;
            boolean hasIncoming = false;

            if (parentK != Long.MIN_VALUE) {
                int px = (int) (parentK >> 32);
                int pz = (int) (long) parentK;
                inDx = Integer.signum(x - px);
                inDz = Integer.signum(z - pz);
                hasIncoming = (inDx != 0 || inDz != 0);
            }

            for (int[] dir : dirs) {
                if (accepted >= maxCandidates) break;

                if (MPBConfig.BRIDGE_AWARE_PATHFINDING.requireAlignedApproach && hasIncoming) {
                    double dot = normalizedDot(inDx, inDz, dir[0], dir[1]);
                    if (dot < MPBConfig.BRIDGE_AWARE_PATHFINDING.minApproachDot) continue;
                }

                for (int len = minLen; len <= maxLen; len += lenStep) {
                    int nx = x + dir[0] * len;
                    int nz = z + dir[1] * len;

                    nx = snap(nx, step);
                    nz = snap(nz, step);

                    if (nx == x && nz == z) continue;

                    if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) continue;
                    if (!inCorridor(nx, nz)) continue;

                    TerrainSampler.Sample ns = sampler.sample(nx, nz);
                    if (ns == null) continue;
                    if (ns.isWater || ns.isOcean) continue;
                    if (sampler.forbidden(ns, TerrainSampler.Mode.PATHFINDING)) continue;

                    BridgeSpan span = BridgeFeasibility.tryCreate(
                            world,
                            sampler,
                            new BlockPos(x, cur.surfaceY, z),
                            new BlockPos(nx, ns.surfaceY, nz),
                            false
                    );

                    if (span == null) {
                        continue;
                    }

                    double geometric = bridgeSpanCost(span);
                    out.add(new BridgeMove(nx, nz, geometric, span));
                    accepted++;
                    break;
                }
            }

            return out;
        }

        private double bridgeSpanCost(BridgeSpan span) {
            int len = Math.max(1, span.lengthBlocks());
            double euclid = Math.sqrt(distSq(span.start.getX(), span.start.getZ(), span.end.getX(), span.end.getZ()));

            double fixed = Math.max(0.0, MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeFixedCost);
            double mult = Math.max(1.0, MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeLengthCostMultiplier);
            double valleyBonus = Math.max(0.0, MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeValleyPreferenceBonusPerBlock);

            int midpointIdx = span.centerline.size() / 2;
            BlockPos mid = span.centerline.get(midpointIdx);
            TerrainSampler.Sample sm = sampler.sample(mid.getX(), mid.getZ());

            int baseline = Math.max(
                    sampler.sample(span.start.getX(), span.start.getZ()).surfaceY,
                    sampler.sample(span.end.getX(), span.end.getZ()).surfaceY
            );
            int depth = Math.max(0, baseline - sm.surfaceY);

            return fixed + euclid * mult - ((double) depth * valleyBonus);
        }

        private boolean baseNodeChecks(int nx, int nz, long nk) {
            if (!inCorridor(nx, nz)) return false;
            if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) return false;

            if (maxRadSq > 0) {
                long dx0 = (long) nx - (long) sx;
                long dz0 = (long) nz - (long) sz;
                if ((dx0 * dx0 + dz0 * dz0) > (long) maxRadSq) return false;
            }

            return !closed.contains(nk);
        }

        private void buildResultFromReconstruction() {
            ArrayList<BlockPos> out = new ArrayList<BlockPos>();
            ArrayList<BridgeSpan> spans = new ArrayList<BridgeSpan>();

            if (revKeys.isEmpty()) {
                result = new BridgeAwarePathResult(Collections.<BlockPos>emptyList(), Collections.<BridgeSpan>emptyList());
                return;
            }

            long first = revKeys.get(0);
            int fx = (int) (first >> 32);
            int fz = (int) (long) first;
            TerrainSampler.Sample fs = sampler.sample(fx, fz);
            out.add(new BlockPos(fx, fs.surfaceY, fz));

            for (int i = 1; i < revKeys.size(); i++) {
                long curK = revKeys.get(i);
                int cx = (int) (curK >> 32);
                int cz = (int) (long) curK;

                BridgeSpan span = cameBridge.get(curK);
                if (span != null) {
                    spans.add(span);

                    for (int j = 1; j < span.centerline.size(); j++) {
                        BlockPos p = span.centerline.get(j);
                        TerrainSampler.Sample s = sampler.sample(p.getX(), p.getZ());
                        if (out.isEmpty() || out.get(out.size() - 1).getX() != p.getX() || out.get(out.size() - 1).getZ() != p.getZ()) {
                            out.add(new BlockPos(p.getX(), s.surfaceY, p.getZ()));
                        }
                    }
                } else {
                    TerrainSampler.Sample s = sampler.sample(cx, cz);
                    if (out.isEmpty() || out.get(out.size() - 1).getX() != cx || out.get(out.size() - 1).getZ() != cz) {
                        out.add(new BlockPos(cx, s.surfaceY, cz));
                    }
                }
            }

            result = new BridgeAwarePathResult(out, spans);
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
            return result.getPath();
        }

        public BridgeAwarePathResult getResult() {
            return result;
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
                    + ", slope=" + skipSlope
                    + ", bridge=" + skipBridge + "}";
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

                if (worst > (maxDy + 1)) return worst;
                prevY = yy;
            }
            return worst;
        }

        private static final class SegmentWalkability {
            final boolean walkable;
            final boolean crossesWater;

            SegmentWalkability(boolean walkable, boolean crossesWater) {
                this.walkable = walkable;
                this.crossesWater = crossesWater;
            }
        }

        private SegmentWalkability segmentWalkability(int x0, int z0, int x1, int z1) {
            if (segmentCrossesWater(x0, z0, x1, z1)) {
                return new SegmentWalkability(false, true);
            }

            int worst = maxStepDeltaAlongSegment(x0, z0, x1, z1);

            int maxDy = Math.max(1, MPBConfig.PATHFINDING.maxYDeltaPerStep);
            if (MPBConfig.PATHFINDING.allowEarthworks) {
                maxDy = Math.max(maxDy, 2);
            }

            return new SegmentWalkability(worst <= maxDy, false);
        }

        private boolean segmentCrossesWater(int x0, int z0, int x1, int z1) {
            List<BlockPos> line = SupercoverLine.raster(x0, z0, x1, z1, 0);
            if (line == null || line.isEmpty()) return false;

            for (int i = 1; i < line.size() - 1; i++) {
                BlockPos p = line.get(i);
                TerrainSampler.Sample s = sampler.sample(p.getX(), p.getZ());
                if (s == null) return true;
                if (s.isOcean || s.isWater) return true;
            }

            return false;
        }

        private void bumpForbidden(TerrainSampler.Sample s) {
            TerrainSampler.ForbiddenReason r = sampler.forbiddenReason(s, TerrainSampler.Mode.PATHFINDING);
            if (r == TerrainSampler.ForbiddenReason.OCEAN) skipOcean++;
            else if (r == TerrainSampler.ForbiddenReason.BLACKLIST) skipBlacklist++;
            else if (r == TerrainSampler.ForbiddenReason.WATER) skipBridge++;
            else if (r == TerrainSampler.ForbiddenReason.BLOCKED) skipBlocked++;
            else skipBlocked++;
        }

        private boolean inCorridor(int x, int z) {
            if (segLenSq <= 0) return true;

            long px = x;
            long pz = z;

            long apx = px - ax;
            long apz = pz - az;

            double t = (double) (apx * segDx + apz * segDz) / (double) segLenSq;
            if (t < 0.0) t = 0.0;
            else if (t > 1.0) t = 1.0;

            double cx = (double) ax + t * (double) segDx;
            double cz = (double) az + t * (double) segDz;

            double dx = (double) px - cx;
            double dz = (double) pz - cz;

            double d2 = dx * dx + dz * dz;
            double r = (double) corridorHalfWidth;

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

    private static int[][] bridgeDirections() {
        return new int[][]{
                { 1, 0}, {-1, 0}, {0, 1}, {0, -1},
                { 1, 1}, { 1,-1}, {-1, 1}, {-1,-1},
                { 2, 1}, { 2,-1}, {-2, 1}, {-2,-1},
                { 1, 2}, { 1,-2}, {-1, 2}, {-1,-2}
        };
    }

    private static double normalizedDot(int ax, int az, int bx, int bz) {
        double al = Math.sqrt((double) ax * (double) ax + (double) az * (double) az);
        double bl = Math.sqrt((double) bx * (double) bx + (double) bz * (double) bz);
        if (al <= 0.0 || bl <= 0.0) return 1.0;
        return ((double) ax * (double) bx + (double) az * (double) bz) / (al * bl);
    }
}
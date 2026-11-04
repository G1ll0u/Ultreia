package com.jubitus.ultreia.route;

import com.jubitus.ultreia.bridge.BridgeMoveGenerator;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.geom.LineRaster;
import com.jubitus.ultreia.paint.RoadHeightSolver;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.terrain.TerrainView;
import net.minecraft.util.math.BlockPos;

import java.util.List;

import static com.jubitus.ultreia.route.GridMoves.NEIGHBOR_DIRS;
import static com.jubitus.ultreia.route.GridMoves.scaleUpToStep;

/**
 * Terrain cost of one A* grid move: grade, roughness, predicted cut and fill of the move itself,
 * and the cutting it commits to over the next steps. The per-cell cost lives in {@link TerrainCost}.
 */
public final class TerrainMoveCost {

    // Max vertical delta allowed between neighbor grid nodes.
    public static final int MAX_YDELTA_PER_STEP = 2;
    // Extra cost per unit of local terrain roughness along a segment.
    private static final double SURFACE_ROUGHNESS_PENALTY = 3.0;
    // Hard cap for cumulative roughness along one neighbor segment.
    private static final int MAX_SEGMENT_ROUGHNESS = 4;
    // Minimum total rise within lookahead before a straight uphill is considered 'direct climb'.
    private static final int UPHILL_LOOKAHEAD_MIN_RISE = 3;
    // Predicted maximum cut depth pathfinding treats as normal terrain shaping on one move.
    private static final int PREDICTED_MAX_CUT_DEPTH = 4;
    // Penalty per block of predicted cut depth when a move cuts into terrain.
    private static final double PREDICTED_CUT_PENALTY_PER_BLOCK = 50.0;
    // Penalty per block of fill required along a segment.
    private static final double PREDICTED_FILL_PENALTY_PER_BLOCK = 120.0;
    // Lateral scan radius used to detect enclosed mountain cutting.
    private static final int ENCLOSED_CUT_SCAN_RADIUS = 3;
    // Minimum wall height on both sides for a cell to count as enclosed cutting.
    private static final int ENCLOSED_WALL_MIN_HEIGHT = 2;
    // Penalty added when a move enters enclosed cutting on both sides (trench/saw-through-ridge).
    private static final double ENCLOSED_CUT_PENALTY = 150.0;
    // Extra penalty per additional enclosed cell in a sustained enclosed cut run.
    private static final double SUSTAINED_ENCLOSED_CUT_PENALTY = 100.0;
    // Penalty per block of the lower wall in an enclosed cut cell of the move itself.
    private static final double ENCLOSED_WALL_PENALTY_PER_BLOCK = 8.0;
    // Forward lookahead used to discourage direct enclosed mountain ascent.
    private static final int MOUNTAIN_ASCENT_LOOKAHEAD = 4;
    // Penalty for continuing into a sustained enclosed uphill push.
    private static final double MOUNTAIN_ASCENT_PENALTY = 50.0;
    // Penalty per block of sustained rise or drop ahead while the look-ahead is enclosed.
    private static final double VERTICAL_COMMIT_PENALTY_PER_BLOCK = 8.0;
    // Cut cost multiplier applied when the terrain being cut is hard rock (stone, granite, etc.).
    private static final double HARD_ROCK_CUT_MULTIPLIER = 1.5;
    // Light-sample hardness category from which the surface counts as hard rock (2 = stone).
    private static final int HARD_ROCK_CATEGORY = 2;
    // Look-ahead weights: cut cost, enclosed entry, per enclosed cell, sustained run, per block of wall.
    private static final double LOOKAHEAD_CUT_WEIGHT = 0.75;
    private static final double LOOKAHEAD_ENCLOSED_WEIGHT = 0.5;
    private static final double LOOKAHEAD_ENCLOSED_CELL_PENALTY = 26.0;
    private static final double LOOKAHEAD_SUSTAINED_WEIGHT = 0.6;
    private static final double LOOKAHEAD_WALL_PENALTY_PER_BLOCK = 10.0;
    // Cost per squared block of look-ahead cut deeper than PREDICTED_MAX_CUT_DEPTH.
    private static final double LOOKAHEAD_OVER_CUT_PENALTY = 120.0;
    // Look-ahead cut cost factor with terrain on one side only; rises linearly to 1 when fully enclosed.
    private static final double OPEN_HILLSIDE_CUT_FACTOR = 0.3;
    // Cost per squared block of height step beyond the grade limit.
    private static final double OVER_GRADE_PENALTY = 30.0;
    // Slack over the roughness and cut-depth limits before a move is rejected.
    private static final int ROUGHNESS_SLACK = 2;
    private static final int CUT_DEPTH_SLACK = 1;
    // A move with an enclosed run this long that also cuts beyond the limit is rejected.
    private static final int SEVERE_ENCLOSED_MIN_RUN = 2;
    // Extra earthworks tolerance (blocks) added at pathfinding time only, on top of earthworksMaxCut/Fill.
    private static final int PATHFINDING_EARTHWORKS_TOLERANCE = 4;

    enum Verdict { OK, WATER, TOO_STEEP }

    /** Cost parts of one move, kept apart so A* adds them in its fixed order. */
    static final class MoveCost {
        static final MoveCost WATER = new MoveCost(Verdict.WATER, 0.0, 0.0, 0.0, 0.0);
        static final MoveCost TOO_STEEP = new MoveCost(Verdict.TOO_STEEP, 0.0, 0.0, 0.0, 0.0);

        final Verdict verdict;
        final double slope;
        final double roughness;
        final double cut;
        final double ahead;

        MoveCost(Verdict verdict, double slope, double roughness, double cut, double ahead) {
            this.verdict = verdict;
            this.slope = slope;
            this.roughness = roughness;
            this.cut = cut;
            this.ahead = ahead;
        }
    }

    private final TerrainView sampler;
    private final int step;
    private final BridgeMoveGenerator.SearchArea area;

    TerrainMoveCost(TerrainView sampler, int step, BridgeMoveGenerator.SearchArea area) {
        this.sampler = sampler;
        this.step = step;
        this.area = area;
    }

    /** Cost of the grid move from (x, z) in direction {@code dirIdx} to (nx, nz), or why it is refused. */
    MoveCost evaluate(int x, int z, TerrainSampler.Sample cs, int dirIdx, int nx, int nz, TerrainSampler.Sample ns,
                      RoadType roadType) {
        SegmentWalkability sw = segmentWalkability(x, z, nx, nz, roadType);
        if (sw.crossesWater) return MoveCost.WATER;
        if (!sw.walkable) return MoveCost.TOO_STEEP;

        boolean earthworks = roadType.allowsEarthworks();
        // The grade limit stays the same whatever the step size.
        int effectiveMaxDy = scaleUpToStep(MAX_YDELTA_PER_STEP, step);
        // LONE_TRAIL uses a higher slope multiplier to make the trail wind around hills.
        double slopeMultiplier = (roadType == RoadType.LONE_TRAIL)
                ? Math.max(1.0, UltreiaConfig.ROAD_HIERARCHY.loneTrailSlopePenaltyMultiplier)
                : 1.0;

        int dy = Math.abs(ns.surfaceY - cs.surfaceY);

        double slopeCost = (double) Math.min(dy, effectiveMaxDy) * UltreiaConfig.PATHFINDING.slopePenalty * slopeMultiplier;
        double roughCost = (double) sw.roughness * SURFACE_ROUGHNESS_PENALTY;
        double aheadCost = mountainTraversePenalty(x, z, dirIdx, cs.surfaceY, ns.surfaceY);

        if (dy > effectiveMaxDy) {
            if (!earthworks) return MoveCost.TOO_STEEP;
            double extra = (double) (dy - effectiveMaxDy);
            slopeCost += extra * extra * OVER_GRADE_PENALTY;
        }

        return new MoveCost(Verdict.OK, slopeCost, roughCost, sw.terrainPenalty, aheadCost);
    }

    private static boolean isEnclosed(int[] walls) {
        return walls[0] >= ENCLOSED_WALL_MIN_HEIGHT && walls[1] >= ENCLOSED_WALL_MIN_HEIGHT;
    }

    private double mountainTraversePenalty(int x, int z, int dirIdx, int curY, int nextY) {
        int lookahead = MOUNTAIN_ASCENT_LOOKAHEAD;
        int[] dir = NEIGHBOR_DIRS[dirIdx];

        int stepDy = nextY - curY;

        int cx = x;
        int cz = z;

        int maxPredictedCut = 0;
        int sumPredictedCut = 0;

        int enclosedAhead = 0;
        int currentEnclosedRun = 0;
        int longestEnclosedRun = 0;
        int maxWallMin = 0;

        int maxRiseAhead = 0;
        int maxDropAhead = 0;
        int maxHardnessCat = 0;

        for (int i = 1; i <= lookahead; i++) {
            cx += dir[0] * step;
            cz += dir[1] * step;

            if (!area.contains(cx, cz)) break;

            // Light sample: surfaceY, water/ocean, and hardness category.
            int lv = sampler.sampleLightIfLoaded(cx, cz);
            if (lv == TerrainSampler.LIGHT_NOT_LOADED) break;
            boolean sWater = TerrainSampler.lightIsWater(lv);
            boolean sOcean = TerrainSampler.lightIsOcean(lv);
            if (sWater || sOcean) break;
            int sSurfaceY = TerrainSampler.lightSurfaceY(lv);
            int hardnessCat = TerrainSampler.lightHardness(lv);
            if (hardnessCat > maxHardnessCat) maxHardnessCat = hardnessCat;

            int projectedRoadY = nextY + stepDy * (i - 1);

            int riseAhead = sSurfaceY - nextY;
            int dropAhead = nextY - sSurfaceY;
            if (riseAhead > maxRiseAhead) maxRiseAhead = riseAhead;
            if (dropAhead > maxDropAhead) maxDropAhead = dropAhead;

            int predictedCut = Math.max(0, sSurfaceY - projectedRoadY);
            if (predictedCut > maxPredictedCut) maxPredictedCut = predictedCut;
            sumPredictedCut += predictedCut;

            // Only scan lateral walls when the road actually cuts into terrain.
            if (predictedCut > 0) {
                int[] walls = lateralWallHeights(cx, cz, projectedRoadY, dir[0], dir[1]);
                int wallMin = Math.min(walls[0], walls[1]);
                if (wallMin > maxWallMin) maxWallMin = wallMin;

                boolean enclosed = isEnclosed(walls);
                if (enclosed) {
                    enclosedAhead++;
                    currentEnclosedRun++;
                    if (currentEnclosedRun > longestEnclosedRun) longestEnclosedRun = currentEnclosedRun;
                } else {
                    currentEnclosedRun = 0;
                }
            } else {
                currentEnclosedRun = 0;
            }
        }

        if (sumPredictedCut <= 0 && enclosedAhead <= 0) {
            return 0.0;
        }

        double penalty = 0.0;

        // Hard rock (stone, granite, etc.) multiplies cut cost — soft terrain (dirt, snow) stays at 1x.
        double hardnessMult = (maxHardnessCat >= HARD_ROCK_CATEGORY) ? HARD_ROCK_CUT_MULTIPLIER : 1.0;

        // Open hillside cuts (terrain only on one side = natural embankment/talus) look realistic
        // and are much cheaper than enclosed trench cuts (terrain on both sides = saw-effect through a ridge).
        double enclosedFraction = (enclosedAhead > 0)
                ? Math.min(1.0, (double) enclosedAhead / (double) lookahead)
                : 0.0;
        double openHillsideFactor = OPEN_HILLSIDE_CUT_FACTOR + (1.0 - OPEN_HILLSIDE_CUT_FACTOR) * enclosedFraction;
        penalty += (double) sumPredictedCut * PREDICTED_CUT_PENALTY_PER_BLOCK * LOOKAHEAD_CUT_WEIGHT * hardnessMult * openHillsideFactor;

        if (maxPredictedCut > PREDICTED_MAX_CUT_DEPTH) {
            double extra = (double) (maxPredictedCut - PREDICTED_MAX_CUT_DEPTH);
            penalty += extra * extra * LOOKAHEAD_OVER_CUT_PENALTY;
        }

        if (enclosedAhead > 0) {
            penalty += ENCLOSED_CUT_PENALTY * LOOKAHEAD_ENCLOSED_WEIGHT;
            penalty += (double) enclosedAhead * LOOKAHEAD_ENCLOSED_CELL_PENALTY;
            penalty += (double) Math.max(0, longestEnclosedRun - 1)
                    * SUSTAINED_ENCLOSED_CUT_PENALTY * LOOKAHEAD_SUSTAINED_WEIGHT;
            penalty += (double) maxWallMin * LOOKAHEAD_WALL_PENALTY_PER_BLOCK;
        }

        boolean sustainedDirectAscent = nextY > curY && maxRiseAhead >= UPHILL_LOOKAHEAD_MIN_RISE;
        boolean sustainedDirectDescent = nextY < curY && maxDropAhead >= UPHILL_LOOKAHEAD_MIN_RISE;

        if ((sustainedDirectAscent || sustainedDirectDescent) && enclosedAhead > 0) {
            penalty += MOUNTAIN_ASCENT_PENALTY;

            int verticalCommit = Math.max(maxRiseAhead, maxDropAhead);
            penalty += (double) verticalCommit * VERTICAL_COMMIT_PENALTY_PER_BLOCK;
        }

        return penalty;
    }

    private int[] lateralWallHeights(int x, int z, int roadY, int dirDx, int dirDz) {
        double len = Math.sqrt((double) dirDx * (double) dirDx + (double) dirDz * (double) dirDz);
        if (len <= 0.0) return new int[]{0, 0};

        double px = -((double) dirDz) / len;
        double pz = ((double) dirDx) / len;

        int leftWall = 0;
        int rightWall = 0;

        for (int off = 1; off <= ENCLOSED_CUT_SCAN_RADIUS; off++) {
            int lx = (int) Math.round((double) x + px * (double) off);
            int lz = (int) Math.round((double) z + pz * (double) off);
            int lv = sampler.sampleLightIfLoaded(lx, lz);
            if (lv != TerrainSampler.LIGHT_NOT_LOADED
                    && !TerrainSampler.lightIsWater(lv)
                    && !TerrainSampler.lightIsOcean(lv)) {
                int sy = TerrainSampler.lightSurfaceY(lv);
                leftWall = Math.max(leftWall, sy - roadY);
            }

            int rx = (int) Math.round((double) x - px * (double) off);
            int rz = (int) Math.round((double) z - pz * (double) off);
            int rv = sampler.sampleLightIfLoaded(rx, rz);
            if (rv != TerrainSampler.LIGHT_NOT_LOADED
                    && !TerrainSampler.lightIsWater(rv)
                    && !TerrainSampler.lightIsOcean(rv)) {
                int sy = TerrainSampler.lightSurfaceY(rv);
                rightWall = Math.max(rightWall, sy - roadY);
            }
        }

        return new int[]{leftWall, rightWall};
    }

    private double segmentParam01(double px, double pz,
                                  double segStartX, double segStartZ,
                                  double segEndX, double segEndZ) {
        double dx = segEndX - segStartX;
        double dz = segEndZ - segStartZ;
        double lenSq = dx * dx + dz * dz;

        if (lenSq <= 1.0e-9) return 0.0;

        double t = ((px - segStartX) * dx + (pz - segStartZ) * dz) / lenSq;
        if (t < 0.0) return 0.0;
        if (t > 1.0) return 1.0;
        return t;
    }

    private static final class SegmentWalkability {
        static final SegmentWalkability TRIVIAL = new SegmentWalkability(true, false, 0, 0.0);
        static final SegmentWalkability WATER = new SegmentWalkability(false, true, Integer.MAX_VALUE, 0.0);

        final boolean walkable;
        final boolean crossesWater;
        final int roughness;
        final double terrainPenalty;

        SegmentWalkability(boolean walkable, boolean crossesWater, int roughness, double terrainPenalty) {
            this.walkable = walkable;
            this.crossesWater = crossesWater;
            this.roughness = roughness;
            this.terrainPenalty = terrainPenalty;
        }
    }

    private SegmentWalkability segmentWalkability(int x0, int z0, int x1, int z1, RoadType roadType) {
        List<BlockPos> line = LineRaster.supercover(x0, z0, x1, z1, 0);
        if (line == null || line.size() < 2) {
            return SegmentWalkability.TRIVIAL;
        }

        TerrainSampler.Sample first = sampler.sample(line.get(0).getX(), line.get(0).getZ());
        TerrainSampler.Sample last = sampler.sample(line.get(line.size() - 1).getX(), line.get(line.size() - 1).getZ());
        if (first == null || last == null) {
            return SegmentWalkability.WATER;
        }

        int prevY = first.surfaceY;
        int roughness = 0;

        double terrainPenalty = 0.0;
        int maxPredictedCut = 0;
        int currentEnclosedRun = 0;
        int longestEnclosedRun = 0;

        double segStartX = (double) x0 + 0.5;
        double segStartZ = (double) z0 + 0.5;
        double segEndX = (double) x1 + 0.5;
        double segEndZ = (double) z1 + 0.5;

        for (int i = 1; i < line.size(); i++) {
            BlockPos p = line.get(i);

            // Interior raster points only need surfaceY/water — use light sample to avoid
            // polluting the full cache with off-grid positions.
            int pSurfaceY;
            boolean pIsWater;
            boolean pIsOcean;

            boolean isInterior = i < line.size() - 1;
            if (isInterior) {
                int lv = sampler.sampleLightIfLoaded(p.getX(), p.getZ());
                if (lv == TerrainSampler.LIGHT_NOT_LOADED) {
                    // Chunk not loaded yet — fall back to full sample (will load chunk).
                    TerrainSampler.Sample fs = sampler.sample(p.getX(), p.getZ());
                    if (fs == null) return SegmentWalkability.WATER;
                    pSurfaceY = fs.surfaceY;
                    pIsWater  = fs.isWater;
                    pIsOcean  = fs.isOcean;
                } else {
                    pSurfaceY = TerrainSampler.lightSurfaceY(lv);
                    pIsWater  = TerrainSampler.lightIsWater(lv);
                    pIsOcean  = TerrainSampler.lightIsOcean(lv);
                }
            } else {
                // Last point — use full sample (= grid node, already cached).
                TerrainSampler.Sample s = sampler.sample(p.getX(), p.getZ());
                if (s == null) return SegmentWalkability.WATER;
                pSurfaceY = s.surfaceY;
                pIsWater  = s.isWater;
                pIsOcean  = s.isOcean;
            }

            if (pIsOcean || pIsWater) {
                return SegmentWalkability.WATER;
            }

            int d = Math.abs(pSurfaceY - prevY);
            if (d > 1) roughness += (d - 1);
            prevY = pSurfaceY;

            if (isInterior) {
                double ppx = (double) p.getX() + 0.5;
                double ppz = (double) p.getZ() + 0.5;
                double t = segmentParam01(ppx, ppz, segStartX, segStartZ, segEndX, segEndZ);

                int roadY = (int) Math.round((1.0 - t) * (double) first.surfaceY + t * (double) last.surfaceY);
                int predictedCut = Math.max(0, pSurfaceY - roadY);
                int predictedFill = Math.max(0, roadY - pSurfaceY);

                if (predictedCut > maxPredictedCut) maxPredictedCut = predictedCut;

                if (predictedFill > 0) {
                    terrainPenalty += (double) predictedFill * PREDICTED_FILL_PENALTY_PER_BLOCK;
                }

                if (predictedCut > 0) {
                    terrainPenalty += (double) predictedCut * PREDICTED_CUT_PENALTY_PER_BLOCK;

                    // Only probe lateral walls when terrain actually needs cutting.
                    int[] walls = lateralWallHeights(p.getX(), p.getZ(), roadY, x1 - x0, z1 - z0);

                    boolean enclosed = isEnclosed(walls);
                    if (enclosed) {
                        int wallMin = Math.min(walls[0], walls[1]);
                        currentEnclosedRun++;
                        if (currentEnclosedRun > longestEnclosedRun) longestEnclosedRun = currentEnclosedRun;
                        terrainPenalty += ENCLOSED_CUT_PENALTY;
                        terrainPenalty += (double) wallMin * ENCLOSED_WALL_PENALTY_PER_BLOCK;
                    } else {
                        currentEnclosedRun = 0;
                    }
                } else {
                    currentEnclosedRun = 0;
                }
            }
        }

        if (longestEnclosedRun > 1) {
            terrainPenalty += (double) (longestEnclosedRun - 1) * SUSTAINED_ENCLOSED_CUT_PENALTY;
        }

        // Extra tolerance applied only during pathfinding — lets A* find routes through steeper terrain
        // without changing what the painter actually does. Painter always uses its own earthworks limits.
        int pfTolerance = PATHFINDING_EARTHWORKS_TOLERANCE;

        // More raster points on a larger step: the roughness budget grows with it.
        int scaledMaxRough = scaleUpToStep(MAX_SEGMENT_ROUGHNESS, step);

        boolean severeEnclosedCut = longestEnclosedRun >= SEVERE_ENCLOSED_MIN_RUN
                && maxPredictedCut > PREDICTED_MAX_CUT_DEPTH + pfTolerance;

        boolean coarseTerrainOk = roughness <= (scaledMaxRough + ROUGHNESS_SLACK)
                && maxPredictedCut <= (PREDICTED_MAX_CUT_DEPTH + CUT_DEPTH_SLACK + pfTolerance)
                && !severeEnclosedCut;

        if (!coarseTerrainOk) {
            return new SegmentWalkability(false, false, roughness, terrainPenalty);
        }

        int startHalf = HalfY.ofBlockTop(first.surfaceY);
        int endHalf = HalfY.ofBlockTop(last.surfaceY);

        boolean earthworks = roadType.allowsEarthworks();
        int cutHalf = earthworks ? Math.max(0, UltreiaConfig.PAINTING.earthworksMaxCut + pfTolerance) * 2 : 0;
        int fillHalf = earthworks ? Math.max(0, UltreiaConfig.PAINTING.earthworksMaxFill + pfTolerance) * 2 : 0;

        boolean gradeFeasible = RoadHeightSolver.isLineFeasible(
                line,
                sampler,
                startHalf,
                endHalf,
                earthworks,
                cutHalf,
                fillHalf,
                HalfY.MAX_ROAD_STEP_HALF_PER_BLOCK
        );

        return new SegmentWalkability(gradeFeasible, false, roughness, terrainPenalty);
    }
}

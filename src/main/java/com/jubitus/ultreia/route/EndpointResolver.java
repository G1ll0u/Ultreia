package com.jubitus.ultreia.route;

import com.jubitus.ultreia.terrain.ColumnSurface;
import com.jubitus.ultreia.terrain.ProtectedAreas;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.mill.MillPathBlocks;
import com.jubitus.ultreia.roads.RoadIndex;
import com.jubitus.ultreia.mill.Villages;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class EndpointResolver {

    // Radius around each gate building's anchor to scan for Millénaire path blocks.
    private static final int GATE_MILL_PATH_SEARCH_RADIUS = 120;

    private EndpointResolver() {}

    /**
     * Resolves the best approach point to connect a village or lone building.
     * Also populates a {@link VillageGateField} if the node is a village with gates,
     * so A* can terminate at any valid gate rather than a single pre-selected point.
     *
     * Village priority:
     * 1. Outermost Mill-path floor block at a gate facing the other village (usage-weighted).
     * 2. Outermost Mill-path block generally (outside noPaint zones).
     * 3. Approach point just outside building noPaint zones.
     * 4. Walk outward until free of building obstacles.
     *
     * @param gateFieldOut if non-null, filled with all valid gate positions for A* multi-termination
     * @param gateScan     the node's finished gate scan, or null to scan now (village nodes only)
     */
    public static BlockPos resolveEndpointAnchor(
            WorldServer world,
            BlockPos nodeCenter,
            BlockPos otherNodeCenter,
            TerrainSampler sampler,
            @Nullable ProtectedAreas protectedAreas,
            @Nullable VillageGateTracker gateTracker,
            @Nullable VillageGateField[] gateFieldOut,
            @Nullable GateScan gateScan
    ) {
        Villages.Settlement vi = Villages.at(world, nodeCenter);
        boolean isVillage = (vi != null && vi.isVillage);

        int protectR = Math.max(0, Villages.CENTER_PROTECT_RADIUS);

        if (isVillage) {
            // Gate candidates with Mill-path entries: the job's finished scan, else one run now.
            if (gateScan == null) {
                gateScan = new GateScan(world, sampler, nodeCenter, otherNodeCenter, gateTracker);
                gateScan.advance(Long.MAX_VALUE);
            }
            List<GateCandidate> candidates = gateScan.candidates;

            // Build VillageGateField from all candidates so A* can terminate at any gate.
            if (gateFieldOut != null && !candidates.isEmpty()) {
                int n = candidates.size();
                int[] fxs = new int[n], fzs = new int[n], fys = new int[n];
                for (int i = 0; i < n; i++) {
                    fxs[i] = candidates.get(i).pos.getX();
                    fzs[i] = candidates.get(i).pos.getZ();
                    fys[i] = candidates.get(i).pos.getY();
                }
                gateFieldOut[0] = new VillageGateField(fxs, fzs, fys, n);
            }

            // Best candidate (highest score) = primary endpoint for the A* goal.
            if (!candidates.isEmpty()) {
                BlockPos best = candidates.get(0).pos;
                if (UltreiaConfig.DEBUG.logPathfinding) {
                    Ultreia.LOGGER.debug(
                            "[Ultreia] gate selected village={},{} other={},{} total_candidates={} -> ({},{},{})",
                            nodeCenter.getX(), nodeCenter.getZ(),
                            otherNodeCenter.getX(), otherNodeCenter.getZ(),
                            candidates.size(), best.getX(), best.getY(), best.getZ());
                }
                return best;
            }

            // Rule 2: outermost Mill-path block facing other village, outside noPaint zones.
            BlockPos millEntry = findOutermostMillPathToward(world, sampler, nodeCenter, otherNodeCenter, 120, protectedAreas);
            if (millEntry != null) return millEntry;

            // Rule 3: approach point just outside building noPaint zones
            BlockPos approx = stepToward(nodeCenter, otherNodeCenter, Math.max(8, protectR + 6));
            BlockPos anchor = findAnchor(world, approx, sampler, 120);
            if (protectedAreas == null || !protectedAreas.contains(anchor.getX(), anchor.getZ())) {
                return anchor;
            }

            // Rule 4: walk outward until free
            return walkOutUntilFree(world, anchor, otherNodeCenter, sampler, protectedAreas);
        }

        // Lone building: bypass from outside protected zone
        BlockPos bypass = stepToward(nodeCenter, otherNodeCenter, protectR + 6);
        return walkOutUntilFree(world, bypass, otherNodeCenter, sampler, protectedAreas);
    }

    /**
     * Adds the chunks (chunk xz keys) the gate search of {@link #resolveEndpointAnchor} may read for this node,
     * so the job can load them over several ticks first. Nothing for a lone building or a village without gates.
     */
    public static void addGateSearchChunks(WorldServer world, BlockPos nodeCenter, BlockPos otherNodeCenter,
                                           it.unimi.dsi.fastutil.longs.LongSet out) {
        Villages.Settlement vi = Villages.at(world, nodeCenter);
        if (vi == null || !vi.isVillage) return;
        double ddx = otherNodeCenter.getX() - nodeCenter.getX();
        double ddz = otherNodeCenter.getZ() - nodeCenter.getZ();
        double dlen = Math.sqrt(ddx * ddx + ddz * ddz);
        if (dlen < 1.0) return;
        double ux = ddx / dlen, uz = ddz / dlen;
        int r = GATE_MILL_PATH_SEARCH_RADIUS;
        for (BlockPos gate : Villages.gates(world, nodeCenter)) {
            for (int cx = (gate.getX() - r) >> 4; cx <= (gate.getX() + r) >> 4; cx++) {
                for (int cz = (gate.getZ() - r) >> 4; cz <= (gate.getZ() + r) >> 4; cz++) {
                    // The search skips columns behind the village centre; a chunk is needed when one corner is ahead.
                    double x0 = (cx << 4) - nodeCenter.getX(), z0 = (cz << 4) - nodeCenter.getZ();
                    double ahead = Math.max(x0 * ux, (x0 + 15) * ux) + Math.max(z0 * uz, (z0 + 15) * uz);
                    if (ahead > 0.0) out.add(com.jubitus.ultreia.geom.Pos2D.xzKey(cx, cz));
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Gate candidate collection
    // -------------------------------------------------------------------------

    private static final class GateCandidate {
        final BlockPos pos;
        final double score;

        GateCandidate(BlockPos pos, double score) {
            this.pos = pos;
            this.score = score;
        }
    }

    /**
     * The gate search of one village toward another: for each gate, the Mill-path entry farthest toward the other
     * village, scored by gate alignment minus a usage penalty so less-used gates are preferred. Resumable across
     * ticks; {@link #resolveEndpointAnchor} takes the finished scan.
     */
    public static final class GateScan {
        // Columns read between two deadline checks.
        private static final int DEADLINE_CHECK_EVERY = 256;

        private final WorldServer world;
        private final TerrainSampler sampler;
        private final BlockPos villageCenter;
        private final BlockPos otherNodeCenter;
        @Nullable private final VillageGateTracker tracker;
        private final List<BlockPos> gates;
        private final double ux, uz, dlen;
        private final List<GateCandidate> candidates = new ArrayList<>();
        @Nullable private int[] offsets;
        private int gateIdx = 0;
        private int offsetIdx = 0;
        private boolean done;

        public GateScan(WorldServer world, TerrainSampler sampler, BlockPos villageCenter, BlockPos otherNodeCenter,
                        @Nullable VillageGateTracker tracker) {
            this.world = world;
            this.sampler = sampler;
            this.villageCenter = villageCenter;
            this.otherNodeCenter = otherNodeCenter;
            this.tracker = tracker;
            this.gates = Villages.gates(world, villageCenter);
            double ddx = otherNodeCenter.getX() - villageCenter.getX();
            double ddz = otherNodeCenter.getZ() - villageCenter.getZ();
            this.dlen = Math.sqrt(ddx * ddx + ddz * ddz);
            this.ux = dlen < 1.0 ? 0.0 : ddx / dlen;
            this.uz = dlen < 1.0 ? 0.0 : ddz / dlen;
            this.done = gates.isEmpty() || dlen < 1.0;
        }

        /** Scans until the deadline; true once every gate is scanned. */
        public boolean advance(long deadlineNs) {
            if (done) return true;
            if (offsets == null) offsets = farthestFirstOffsets(GATE_MILL_PATH_SEARCH_RADIUS, ux, uz);

            while (gateIdx < gates.size()) {
                BlockPos gate = gates.get(gateIdx);
                int cgx = gate.getX(), cgz = gate.getZ();
                // Within one gate only proj varies in the score, so the first match in farthest-first
                // order is the best one (same result as scoring every column, without reading them all).
                BlockPos found = null;
                double foundProj = 0.0;
                while (offsetIdx < offsets.length) {
                    if (offsetIdx % DEADLINE_CHECK_EVERY == 0 && System.nanoTime() >= deadlineNs) return false;
                    int packed = offsets[offsetIdx++];
                    int tx = cgx + offsetX(packed), tz = cgz + offsetZ(packed);

                    double dxOurs = tx - villageCenter.getX(), dzOurs = tz - villageCenter.getZ();
                    double dxOther = tx - otherNodeCenter.getX(), dzOther = tz - otherNodeCenter.getZ();
                    double distOurs = Math.sqrt(dxOurs * dxOurs + dzOurs * dzOurs);
                    double distOther = Math.sqrt(dxOther * dxOther + dzOther * dzOther);
                    if (distOther < distOurs * 0.7) continue; // wrong-village guard

                    double proj = dxOurs * ux + dzOurs * uz;
                    if (proj <= 0.0) continue; // behind village center

                    Integer millY = millPathOnLand(world, sampler, tx, tz);
                    if (millY == null) continue;

                    found = new BlockPos(tx, millY, tz);
                    foundProj = proj;
                    break;
                }

                if (found != null) {
                    double gx = cgx - villageCenter.getX();
                    double gz = cgz - villageCenter.getZ();
                    double gl = Math.sqrt(gx * gx + gz * gz);
                    double gateAlignment = (gl > 0.5) ? ((gx / gl) * ux + (gz / gl) * uz) : 0.0;
                    double usagePenalty = (tracker != null) ? tracker.usagePenalty(cgx, cgz) : 0.0;
                    candidates.add(new GateCandidate(found, gateAlignment * 0.4 * dlen + foundProj * 0.6 - usagePenalty));
                }
                gateIdx++;
                offsetIdx = 0;
            }

            candidates.sort((a, b) -> Double.compare(b.score, a.score));
            done = true;
            return true;
        }
    }

    /**
     * Returns the Millénaire path block with the highest projection toward the other village
     * within maxRadius of the village center, outside noPaint zones.
     * Returns the BlockPos with the actual Mill-path Y (not terrain surface Y).
     */
    @Nullable
    private static BlockPos findOutermostMillPathToward(
            WorldServer world,
            TerrainSampler sampler,
            BlockPos center,
            BlockPos other,
            int maxRadius,
            @Nullable ProtectedAreas protectedAreas
    ) {
        int cx = center.getX(), cz = center.getZ();
        double ddx = other.getX() - cx;
        double ddz = other.getZ() - cz;
        double dlen = Math.sqrt(ddx * ddx + ddz * ddz);
        if (dlen < 1.0) return null;
        double ux = ddx / dlen, uz = ddz / dlen;

        // Farthest-first: the first match has the largest projection (ties keep the old scan order).
        for (int packed : farthestFirstOffsets(maxRadius, ux, uz)) {
            int ox = offsetX(packed), oz = offsetZ(packed);
            double proj = ox * ux + oz * uz;
            if (proj <= 0.0) break; // sorted: everything after is behind the centre

            int r2 = ox * ox + oz * oz;
            if (r2 < 36 || r2 > maxRadius * maxRadius) continue;

            int tx = cx + ox, tz = cz + oz;
            if (protectedAreas != null && protectedAreas.contains(tx, tz)) continue;

            Integer millY = millPathOnLand(world, sampler, tx, tz);
            if (millY == null) continue;

            return new BlockPos(tx, millY, tz);
        }
        return null;
    }

    /**
     * Offsets of the square of the given radius, ordered by projection on (ux, uz), largest first.
     * Stable sort over the old x-then-z scan order, so equal projections keep their former priority.
     */
    private static int[] farthestFirstOffsets(int radius, double ux, double uz) {
        int side = 2 * radius + 1;
        int n = side * side;
        Integer[] idx = new Integer[n];
        double[] proj = new double[n];
        int i = 0;
        for (int ox = -radius; ox <= radius; ox++) {
            for (int oz = -radius; oz <= radius; oz++) {
                idx[i] = i;
                proj[i] = ox * ux + oz * uz;
                i++;
            }
        }
        java.util.Arrays.sort(idx, (a, b) -> Double.compare(proj[b], proj[a])); // stable for objects
        int[] packed = new int[n];
        for (int k = 0; k < n; k++) {
            int ox = idx[k] / side - radius;
            int oz = idx[k] % side - radius;
            packed[k] = (ox << 16) | (oz & 0xFFFF);
        }
        return packed;
    }

    private static int offsetX(int packed) { return packed >> 16; }

    private static int offsetZ(int packed) { return (short) packed; }


    /**
     * Y of a Millénaire path block at (x, z) when the column is land (not ocean, not water), or null.
     * The full sample (trees, buildings, water nearby) is read only where the surface scan finds a path block:
     * gate searches cover thousands of columns and almost none hold one.
     */
    @Nullable
    private static Integer millPathOnLand(WorldServer world, TerrainSampler sampler, int x, int z) {
        int light = sampler.sampleLightIfLoaded(x, z);
        if (light != TerrainSampler.LIGHT_NOT_LOADED) {
            // Same surface scan as the full sample, so a miss here is a miss there.
            if (TerrainSampler.lightIsWater(light)) return null;
            if (findMillPathY(world, x, z, TerrainSampler.lightSurfaceY(light)) == null) return null;
        }
        TerrainSampler.Sample sm = sampler.sample(x, z);
        if (sm == null || sm.isOcean || sm.isWater) return null;
        Integer y = findMillPathY(world, x, z, sm.surfaceY);
        // Ultreïa roads share the path blocks: a village entry is a Millénaire path, never one of our roads or trails.
        if (y != null && RoadIndex.get(world).isRoadXZ(x, z)) return null;
        return y;
    }

    /** Y of a Millénaire/Ultreïa path block near the ground at (x, z), or null. */
    @Nullable
    private static Integer findMillPathY(WorldServer world, int x, int z, int surfaceY) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, 0, z);
        // A chunk the chunk manager refused must not be loaded here behind its back.
        if (!world.isBlockLoaded(p)) return null;
        int y = ColumnSurface.findPathY(world, p, x, z, surfaceY, false);
        return y == ColumnSurface.NO_PATH ? null : y;
    }

    /** Steps from {@code from} toward {@code to} by exactly {@code dist} blocks. */
    private static BlockPos stepToward(BlockPos from, BlockPos to, int dist) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0) return from.add(dist, 0, 0);
        int sx = (int) Math.round(dx / len * dist);
        int sz = (int) Math.round(dz / len * dist);
        return from.add(sx, 0, sz);
    }

    /**
     * Walks outward from {@code start} toward {@code toward} in 4-block steps until
     * clear of any noPaint zone, then returns a stable anchor at that position.
     */
    private static BlockPos walkOutUntilFree(
            WorldServer world,
            BlockPos start,
            BlockPos toward,
            TerrainSampler sampler,
            @Nullable ProtectedAreas protectedAreas
    ) {
        if (protectedAreas == null) {
            return findAnchor(world, start, sampler, 24);
        }
        int dx = Integer.signum(toward.getX() - start.getX());
        int dz = Integer.signum(toward.getZ() - start.getZ());
        if (dx == 0 && dz == 0) dx = 1;

        BlockPos cur = start;
        for (int i = 0; i < 20; i++) {
            BlockPos a = findAnchor(world, cur, sampler, 16);
            if (!protectedAreas.contains(a.getX(), a.getZ())) return a;
            cur = cur.add(dx * 4, 0, dz * 4);
        }
        TerrainSampler.Sample sm = sampler.sample(cur.getX(), cur.getZ());
        return new BlockPos(cur.getX(), sm != null ? sm.surfaceY : start.getY(), cur.getZ());
    }


    /** Nearest open land cell (not forbidden, not water) within maxR of raw, ring by ring; raw's surface if none. */
    public static BlockPos findAnchor(WorldServer world, BlockPos raw, TerrainSampler sampler, int maxR) {
        int cx = raw.getX();
        int cz = raw.getZ();

        for (int r = 0; r <= maxR; r++) {
            for (int dx = -r; dx <= r; dx++) {
                BlockPos p1 = tryAnchorAt(cx + dx, cz - r, sampler);
                if (p1 != null) return p1;
                BlockPos p2 = tryAnchorAt(cx + dx, cz + r, sampler);
                if (p2 != null) return p2;
            }
            for (int dz = -r + 1; dz <= r - 1; dz++) {
                BlockPos p1 = tryAnchorAt(cx - r, cz + dz, sampler);
                if (p1 != null) return p1;
                BlockPos p2 = tryAnchorAt(cx + r, cz + dz, sampler);
                if (p2 != null) return p2;
            }
        }

        TerrainSampler.Sample s = sampler.sample(cx, cz);
        return new BlockPos(cx, s.surfaceY, cz);
    }

    @Nullable
    private static BlockPos tryAnchorAt(int x, int z, TerrainSampler sampler) {
        TerrainSampler.Sample s = sampler.sample(x, z);
        if (sampler.forbidden(s, TerrainSampler.Mode.PATHFINDING)) return null;
        if (s.isOcean || s.isWater) return null;
        return new BlockPos(x, s.surfaceY, z);
    }
}

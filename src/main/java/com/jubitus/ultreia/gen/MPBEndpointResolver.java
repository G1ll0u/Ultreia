package com.jubitus.ultreia.gen;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.MPBConfig;
import com.jubitus.ultreia.gen.mill.MPBMillPathPalette;
import com.jubitus.ultreia.gen.mill.MillVillageIndex;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class MPBEndpointResolver {

    private MPBEndpointResolver() {}

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
     */
    public static BlockPos resolveEndpointAnchor(
            WorldServer world,
            BlockPos nodeCenter,
            BlockPos otherNodeCenter,
            TerrainSampler sampler,
            @Nullable MPBProtectedAreas protectedAreas,
            @Nullable VillageGateTracker gateTracker,
            @Nullable VillageGateField[] gateFieldOut
    ) {
        MillVillageIndex.VillageInfo vi = MillVillageIndex.findExact(world, nodeCenter);
        boolean isVillage = (vi != null && vi.isVillage);

        int protectR = isVillage
                ? Math.max(0, MPBConfig.GENERAL.villageNoPaintRadius)
                : Math.max(0, MPBConfig.GENERAL.loneBuildingNoPaintRadius);

        if (isVillage) {
            // Collect all gate candidates with Mill-path entries.
            List<GateCandidate> candidates = collectGateCandidates(
                    world, sampler, nodeCenter, otherNodeCenter,
                    MPBConfig.GENERAL.gateSearchRadius, MPBConfig.GENERAL.gateMillPathSearchRadius,
                    gateTracker);

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
                if (MPBConfig.GENERAL.printFailureBreakdown) {
                    Ultreia.LOGGER.debug(
                            "[MPB] gate selected village={},{} other={},{} total_candidates={} -> ({},{},{})",
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
            BlockPos anchor = MPBGenerationJob.findAnchor(world, approx, sampler, 120);
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

    /** Backward-compatible overload (no gate tracker, no field output). */
    public static BlockPos resolveEndpointAnchor(
            WorldServer world,
            BlockPos nodeCenter,
            BlockPos otherNodeCenter,
            TerrainSampler sampler,
            @Nullable MPBProtectedAreas protectedAreas
    ) {
        return resolveEndpointAnchor(world, nodeCenter, otherNodeCenter, sampler, protectedAreas, null, null);
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
     * Collects all gate Mill-path entries, scored by alignment with the direction to the other
     * village minus a usage penalty so less-used gates are preferred.
     * Returns candidates sorted best-first.
     */
    private static List<GateCandidate> collectGateCandidates(
            WorldServer world,
            TerrainSampler sampler,
            BlockPos villageCenter,
            BlockPos otherNodeCenter,
            int gateSearchRadius,
            int gateLocalRadius,
            @Nullable VillageGateTracker tracker
    ) {
        List<MillVillageIndex.BuildingInfo> gates =
                MillVillageIndex.getVillageGateInfos(world, villageCenter, gateSearchRadius, 64);
        if (gates.isEmpty()) return java.util.Collections.emptyList();

        double ddx = otherNodeCenter.getX() - villageCenter.getX();
        double ddz = otherNodeCenter.getZ() - villageCenter.getZ();
        double dlen = Math.sqrt(ddx * ddx + ddz * ddz);
        if (dlen < 1.0) return java.util.Collections.emptyList();
        double ux = ddx / dlen, uz = ddz / dlen;

        // Per-gate: score its outward direction alignment, then search Mill-path blocks nearby.
        // Keep the best (highest scoring) Mill-path block per gate as its representative entry.
        List<GateCandidate> result = new ArrayList<>();

        for (MillVillageIndex.BuildingInfo bi : gates) {
            double gx = bi.chestPos.getX() - villageCenter.getX();
            double gz = bi.chestPos.getZ() - villageCenter.getZ();
            double gl = Math.sqrt(gx * gx + gz * gz);
            double gateAlignment = (gl > 0.5) ? ((gx / gl) * ux + (gz / gl) * uz) : 0.0;

            double usagePenalty = (tracker != null)
                    ? tracker.usagePenalty(bi.chestPos.getX(), bi.chestPos.getZ())
                    : 0.0;

            BlockPos bestForGate = null;
            double bestForGateScore = Double.NEGATIVE_INFINITY;

            int cgx = bi.chestPos.getX(), cgz = bi.chestPos.getZ();
            for (int ox = -gateLocalRadius; ox <= gateLocalRadius; ox++) {
                for (int oz = -gateLocalRadius; oz <= gateLocalRadius; oz++) {
                    int tx = cgx + ox, tz = cgz + oz;

                    TerrainSampler.Sample sm = sampler.sample(tx, tz);
                    if (sm == null || sm.isOcean || sm.isWater) continue;

                    Integer millY = findMillPathY(world, tx, tz, sm.surfaceY);
                    if (millY == null) continue;

                    double dxOurs = tx - villageCenter.getX(), dzOurs = tz - villageCenter.getZ();
                    double dxOther = tx - otherNodeCenter.getX(), dzOther = tz - otherNodeCenter.getZ();
                    double distOurs = Math.sqrt(dxOurs * dxOurs + dzOurs * dzOurs);
                    double distOther = Math.sqrt(dxOther * dxOther + dzOther * dzOther);
                    if (distOther < distOurs * 0.7) continue; // wrong-village guard

                    double proj = dxOurs * ux + dzOurs * uz;
                    if (proj <= 0.0) continue; // behind village center

                    double score = gateAlignment * 0.4 * dlen + proj * 0.6 - usagePenalty;
                    if (score > bestForGateScore) {
                        bestForGateScore = score;
                        bestForGate = new BlockPos(tx, millY, tz);
                    }
                }
            }

            if (bestForGate != null) {
                result.add(new GateCandidate(bestForGate, bestForGateScore));
            }
        }

        result.sort((a, b) -> Double.compare(b.score, a.score));
        return result;
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
            @Nullable MPBProtectedAreas protectedAreas
    ) {
        int cx = center.getX(), cz = center.getZ();
        double ddx = other.getX() - cx;
        double ddz = other.getZ() - cz;
        double dlen = Math.sqrt(ddx * ddx + ddz * ddz);
        if (dlen < 1.0) return null;
        double ux = ddx / dlen, uz = ddz / dlen;

        BlockPos best = null;
        double bestProj = Double.NEGATIVE_INFINITY;

        for (int ox = -maxRadius; ox <= maxRadius; ox++) {
            for (int oz = -maxRadius; oz <= maxRadius; oz++) {
                double proj = ox * ux + oz * uz;
                if (proj <= 0.0) continue;

                int r2 = ox * ox + oz * oz;
                if (r2 < 36 || r2 > maxRadius * maxRadius) continue;

                if (proj <= bestProj) continue;

                int tx = cx + ox, tz = cz + oz;
                if (protectedAreas != null && protectedAreas.contains(tx, tz)) continue;

                TerrainSampler.Sample sm = sampler.sample(tx, tz);
                if (sm == null || sm.isOcean || sm.isWater) continue;

                Integer millY = findMillPathY(world, tx, tz, sm.surfaceY);
                if (millY == null) continue;

                bestProj = proj;
                best = new BlockPos(tx, millY, tz);
            }
        }

        return best;
    }

    /**
     * Scans the block column at (x, z) for the lowest Millénaire/MPB path block.
     * Searches from Y=4 up to surfaceY+5 to catch paths buried below the terrain surface.
     * Returns the Y coordinate of the found block, or null if none exists.
     */
    @Nullable
    private static Integer findMillPathY(WorldServer world, int x, int z, int surfaceY) {
        int maxY = Math.min(255, surfaceY + 5);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, 0, z);
        // A chunk the chunk manager refused must not be loaded here behind its back.
        if (!world.isBlockLoaded(p)) return null;
        for (int y = 4; y <= maxY; y++) {
            p.setPos(x, y, z);
            IBlockState st = world.getBlockState(p);
            if (st != null && MPBMillPathPalette.isMillPath(st.getBlock())) return y;
        }
        return null;
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
            @Nullable MPBProtectedAreas protectedAreas
    ) {
        if (protectedAreas == null) {
            return MPBGenerationJob.findAnchor(world, start, sampler, 24);
        }
        int dx = Integer.signum(toward.getX() - start.getX());
        int dz = Integer.signum(toward.getZ() - start.getZ());
        if (dx == 0 && dz == 0) dx = 1;

        BlockPos cur = start;
        for (int i = 0; i < 20; i++) {
            BlockPos a = MPBGenerationJob.findAnchor(world, cur, sampler, 16);
            if (!protectedAreas.contains(a.getX(), a.getZ())) return a;
            cur = cur.add(dx * 4, 0, dz * 4);
        }
        TerrainSampler.Sample sm = sampler.sample(cur.getX(), cur.getZ());
        return new BlockPos(cur.getX(), sm != null ? sm.surfaceY : start.getY(), cur.getZ());
    }

    public static String debugResolveEndpointAnchor(
            WorldServer world,
            BlockPos nodeCenter,
            BlockPos otherNodeCenter,
            TerrainSampler sampler,
            @Nullable MPBProtectedAreas protectedAreas
    ) {
        BlockPos resolved = resolveEndpointAnchor(world, nodeCenter, otherNodeCenter, sampler, protectedAreas);
        return "node=(" + nodeCenter.getX() + "," + nodeCenter.getZ() + ")"
                + " other=(" + otherNodeCenter.getX() + "," + otherNodeCenter.getZ() + ")"
                + " resolved=(" + resolved.getX() + "," + resolved.getY() + "," + resolved.getZ() + ")";
    }
}

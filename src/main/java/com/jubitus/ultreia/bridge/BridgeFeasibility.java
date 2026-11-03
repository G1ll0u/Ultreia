package com.jubitus.ultreia.bridge;

import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.geom.LineRaster;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.terrain.TerrainView;
import com.jubitus.ultreia.bridge.template.BridgeTemplate;

import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.terrain.TerrainSampler;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;


/** May a bridge exist between these two points: endpoints, settlements, length, deck slope, then clearance. */
public final class BridgeFeasibility {

    // Minimum bridge length in blocks for A* bridge moves.
    public static final int MIN_BRIDGE_LEN_BLOCKS = 8;
    // Maximum bridge probe length in blocks for A* bridge moves.
    public static final int MAX_PROBE_LEN_BLOCKS = 128;
    // No bridge start/end within this radius of a village.
    private static final int ENDPOINT_VILLAGE_RADIUS = 10;
    // No bridge start/end within this radius of a lone building.
    private static final int ENDPOINT_LONE_BUILDING_RADIUS = 10;
    // Bridge centerline must not pass within this radius of a village.
    private static final int CROSS_VILLAGE_RADIUS = 10;
    // Bridge centerline must not pass within this radius of a lone building.
    private static final int CROSS_LONE_BUILDING_RADIUS = 10;
    // When snapping to an existing road, keep at least this many land blocks before merging into it.
    public static final int BRIDGE_ROAD_APPROACH_MIN_BLOCKS = 8;
    // Maximum search radius around a snapped road to find a small natural land approach before the road.
    public static final int BRIDGE_ROAD_APPROACH_MAX_BLOCKS = 8;

    private BridgeFeasibility() {}

    /** A bridge span, or the reason none may exist here. */
    public static final class Result {
        @Nullable public final BridgeSpan span;
        public final String reason;

        private Result(@Nullable BridgeSpan span, String reason) {
            this.span = span;
            this.reason = reason;
        }
    }

    private static Result ok(BridgeSpan span) {
        return new Result(span, "ok");
    }

    private static Result fail(String reason) {
        return new Result(null, reason);
    }

    public static Result tryCreate(TerrainView sampler, BlockPos start, BlockPos end) {
        BridgeSite site = sampler.bridgeSite();
        // The template depends on the span (length, water), its crown changes the deck: check with the
        // global crown, pick the template, then check again with the deck that will really be built.
        Result first = evaluate(site, sampler, start, end, null);
        if (first.span == null) return first;
        BridgeTemplate template = site.selectTemplate(first.span);
        if (template == null) return first;
        int crown = BridgeDeckProfile.crownHalf(template.bridgeCrownHalfBlocks, first.span.centerline, sampler);
        int crownMin = BridgeDeckProfile.crownMinLen(template.bridgeCrownMinLenBlocks);
        if (crown == first.span.crownHalf && crownMin == first.span.crownMinLen) return ok(first.span.withTemplate(template));
        return evaluate(site, sampler, start, end, template);
    }

    private static Result evaluate(BridgeSite site,
                                   TerrainView sampler,
                                   BlockPos start,
                                   BlockPos end,
                                   @Nullable BridgeTemplate template) {
        if (!UltreiaConfig.BRIDGES.enabled) return fail("bridges disabled");
        if (start == null || end == null) return fail("start/end null");
        if (sameXZ(start, end)) return fail("same xz");

        TerrainSampler.Sample startSample = sampler.sample(start.getX(), start.getZ());
        TerrainSampler.Sample endSample = sampler.sample(end.getX(), end.getZ());
        if (startSample == null || endSample == null) return fail("start/end sample null");

        if (!isValidBridgeEndpoint(site, sampler, startSample)) {
            return fail("invalid start endpoint at (" + startSample.x + "," + startSample.z + ")"
                    + " water=" + startSample.isWater
                    + " ocean=" + startSample.isOcean
                    + " forbidden=" + sampler.describeForbidden(startSample, TerrainSampler.Mode.PATHFINDING));
        }
        if (!isValidBridgeEndpoint(site, sampler, endSample)) {
            return fail("invalid end endpoint at (" + endSample.x + "," + endSample.z + ")"
                    + " water=" + endSample.isWater
                    + " ocean=" + endSample.isOcean
                    + " forbidden=" + sampler.describeForbidden(endSample, TerrainSampler.Mode.PATHFINDING));
        }

        if (isTooNearSettlement(site, start, true)) return fail("start too near settlement");
        if (isTooNearSettlement(site, end, true)) return fail("end too near settlement");

        List<BlockPos> rawLine = LineRaster.forBridges(start.getX(), start.getZ(), end.getX(), end.getZ(), 0);

        if (rawLine == null || rawLine.size() < 2) return fail("raw bridge line too short");

        TrimmedBridgeCandidate trimmed = trimBridgeEndBeforeTargetRoad(sampler, start, end, rawLine);
        if (trimmed == null) return fail("trimBridgeEndBeforeTargetRoad failed");

        end = trimmed.end;
        List<BlockPos> line = trimmed.line;

        startSample = sampler.sample(start.getX(), start.getZ());
        endSample = sampler.sample(end.getX(), end.getZ());
        if (startSample == null || endSample == null) return fail("trimmed start/end sample null");

        if (!isValidBridgeEndpoint(site, sampler, startSample)) {
            return fail("trimmed start endpoint invalid at (" + startSample.x + "," + startSample.z + ")");
        }
        if (!isValidBridgeEndpoint(site, sampler, endSample)) {
            return fail("trimmed end endpoint invalid at (" + endSample.x + "," + endSample.z + ")");
        }

        int len = Math.max(0, line.size() - 1);
        int minLen = Math.max(2, MIN_BRIDGE_LEN_BLOCKS);
        int maxLen = Math.min(
                Math.max(minLen, UltreiaConfig.BRIDGES.maxBridgeLenBlocks),
                Math.max(minLen, MAX_PROBE_LEN_BLOCKS)
        );
        if (len < minLen || len > maxLen) {
            return fail("bridge length out of range len=" + len + " min=" + minLen + " max=" + maxLen);
        }

        int startHalf = HalfY.ofBlockTop(startSample.surfaceY);
        int endHalf = HalfY.ofBlockTop(endSample.surfaceY);

        int maxDeltaHalf = BridgeDeckProfile.maxEndDeltaHalf(len);
        if (Math.abs(endHalf - startHalf) > maxDeltaHalf) {
            return fail("endpoint delta too high startHalf=" + startHalf + " endHalf=" + endHalf + " max=" + maxDeltaHalf);
        }

        int crownHalf = BridgeDeckProfile.crownHalf(
                template != null ? template.bridgeCrownHalfBlocks : null, line, sampler);
        int crownMinLen = BridgeDeckProfile.crownMinLen(
                template != null ? template.bridgeCrownMinLenBlocks : null);
        int[] deckHalf = BridgeDeckProfile.of(startHalf, endHalf, line, crownHalf, crownMinLen);

        BridgeClearanceCheck.Result clearance = BridgeClearanceCheck.check(site, sampler, line, deckHalf);
        if (clearance.reason != null) return fail(clearance.reason);

        if (!BridgeApproachCheck.exitCorridorIsBuildable(site, sampler, start, end)) {
            return fail("exitCorridorIsBuildable failed");
        }

        return ok(new BridgeSpan(start, end, line, startHalf, endHalf, clearance.waterDominant, clearance.waterCrossing,
                template, crownHalf, crownMinLen));
    }

    private static final class TrimmedBridgeCandidate {
        final BlockPos end;
        final List<BlockPos> line;

        TrimmedBridgeCandidate(BlockPos end, List<BlockPos> line) {
            this.end = end;
            this.line = line;
        }
    }

    @Nullable
    private static TrimmedBridgeCandidate trimBridgeEndBeforeTargetRoad(TerrainView sampler,
                                                                        BlockPos start,
                                                                        BlockPos end,
                                                                        List<BlockPos> rawLine) {
        if (rawLine == null || rawLine.size() < 2) return null;

        int roadSearch = Math.max(1, BRIDGE_ROAD_APPROACH_MAX_BLOCKS);
        int minApproach = Math.max(1, BRIDGE_ROAD_APPROACH_MIN_BLOCKS);

        BlockPos targetRoad = sampler.findNearestExistingRoad(end.getX(), end.getZ(), roadSearch);
        if (targetRoad == null) {
            return new TrimmedBridgeCandidate(end, rawLine);
        }

        int roadHitIdx = -1;
        double roadHitDistSq = Double.MAX_VALUE;

        int scanFrom = Math.max(1, rawLine.size() - 1 - (roadSearch + minApproach + 4));

        for (int i = scanFrom; i < rawLine.size() - 1; i++) {
            BlockPos p = rawLine.get(i);

            if (!sampler.isExistingRoadXZ(p.getX(), p.getZ())) {
                continue;
            }

            double d2 = Pos2D.distSq(p.getX(), p.getZ(), targetRoad.getX(), targetRoad.getZ());
            if (d2 < roadHitDistSq) {
                roadHitDistSq = d2;
                roadHitIdx = i;
            }
        }

        if (roadHitIdx < 0) {
            return new TrimmedBridgeCandidate(end, rawLine);
        }

        int minBridgeLen = Math.max(2, MIN_BRIDGE_LEN_BLOCKS);
        int searchStart = Math.max(1, roadHitIdx - Math.max(roadSearch + minApproach + 4, 8));
        int searchEnd = Math.max(searchStart, roadHitIdx - minApproach);

        int bestIdx = -1;
        double bestScore = Double.MAX_VALUE;

        for (int i = searchEnd; i >= searchStart; i--) {
            BlockPos p = rawLine.get(i);

            if (i < minBridgeLen) continue;
            if (sampler.isExistingRoadXZ(p.getX(), p.getZ())) continue;
            if (!sampler.isNaturalLandEndpoint(p.getX(), p.getZ())) continue;

            double d2Road = Pos2D.distSq(p.getX(), p.getZ(), targetRoad.getX(), targetRoad.getZ());
            if (d2Road < minApproach * minApproach) continue;

            TerrainSampler.Sample s = sampler.sample(p.getX(), p.getZ());
            if (s == null) continue;

            double score = d2Road + (roadHitIdx - i) * 6;
            if (score < bestScore) {
                bestScore = score;
                bestIdx = i;
            }
        }

        if (bestIdx < 0) {
            return new TrimmedBridgeCandidate(end, rawLine);
        }

        List<BlockPos> trimmedLine = new ArrayList<BlockPos>(bestIdx + 1);
        for (int i = 0; i <= bestIdx; i++) {
            BlockPos p = rawLine.get(i);
            TerrainSampler.Sample s = sampler.sample(p.getX(), p.getZ());
            if (s == null) return null;
            trimmedLine.add(new BlockPos(p.getX(), s.surfaceY, p.getZ()));
        }

        BlockPos newEnd2D = rawLine.get(bestIdx);
        TerrainSampler.Sample newEndSample = sampler.sample(newEnd2D.getX(), newEnd2D.getZ());
        if (newEndSample == null) return null;

        BlockPos newEnd = new BlockPos(newEnd2D.getX(), newEndSample.surfaceY, newEnd2D.getZ());
        return new TrimmedBridgeCandidate(newEnd, trimmedLine);
    }

    private static boolean isValidBridgeEndpoint(BridgeSite site,
                                                 TerrainView sampler,
                                                 TerrainSampler.Sample sample) {
        if (sample.isWater || sample.isOcean) return false;
        if (sampler.forbidden(sample, TerrainSampler.Mode.PATHFINDING)) return false;
        if (sampler.isProtectedNoPaint(sample.x, sample.z)) return false;
        if (sampler.isExistingRoadXZ(sample.x, sample.z)) return false;

        int y = sample.surfaceY;
        if (!site.isNaturalGroundOnly(sample.x, y, sample.z)) return false;
        return site.isBridgeClearable(sample.x, y + 1, sample.z, true)
                && site.isBridgeClearable(sample.x, y + 2, sample.z, true);
    }

    static boolean isTooNearSettlement(BridgeSite site, BlockPos pos, boolean endpoint) {
        int villageRadius = endpoint
                ? Math.max(0, ENDPOINT_VILLAGE_RADIUS)
                : Math.max(0, CROSS_VILLAGE_RADIUS);

        int loneRadius = endpoint
                ? Math.max(0, ENDPOINT_LONE_BUILDING_RADIUS)
                : Math.max(0, CROSS_LONE_BUILDING_RADIUS);

        if (villageRadius > 0 && site.isNearVillage(pos, villageRadius)) return true;
        if (loneRadius > 0 && site.isNearAnySettlement(pos, loneRadius)) return true;

        return false;
    }

    private static boolean sameXZ(BlockPos a, BlockPos b) {
        return a.getX() == b.getX() && a.getZ() == b.getZ();
    }

}

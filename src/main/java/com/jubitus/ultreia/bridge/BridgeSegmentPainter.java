package com.jubitus.ultreia.bridge;

import com.jubitus.ultreia.bridge.template.BridgeBuildService;
import com.jubitus.ultreia.bridge.template.BridgeTemplate;
import com.jubitus.ultreia.bridge.template.BridgeTemplateRegistry;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.geom.HalfY;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.roads.RoadIndex;
import com.jubitus.ultreia.roads.RoadNetwork;
import com.jubitus.ultreia.route.RoadType;
import com.jubitus.ultreia.terrain.ProtectedAreas;
import com.jubitus.ultreia.terrain.TerrainSampler;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The bridge side of painting one edge: building its spans, and the cells road painting must leave alone. */
public final class BridgeSegmentPainter {

    private interface CellSink {
        void accept(int x, int z);
    }

    private final WorldServer world;
    private final TerrainSampler sampler;
    @Nullable private final ProtectedAreas protectedAreas;
    @Nullable private final RoadIndex roadIndex;
    private final List<BridgeSpan> spans;
    private final boolean dryRun;
    private final LongOpenHashSet footprint = new LongOpenHashSet();

    public BridgeSegmentPainter(WorldServer world, TerrainSampler sampler, @Nullable ProtectedAreas protectedAreas,
                                @Nullable RoadIndex roadIndex, @Nullable List<BridgeSpan> spans, boolean dryRun) {
        this.world = world;
        this.sampler = sampler;
        this.protectedAreas = protectedAreas;
        this.roadIndex = roadIndex;
        this.spans = spans == null ? Collections.<BridgeSpan>emptyList() : spans;
        this.dryRun = dryRun;
    }

    // INVARIANT: called before any isBridgeCell query (centerlines are only known through the footprint).
    public void buildFootprint() {
        footprint.clear();
        int lateral = lateralHalfWidth();
        for (BridgeSpan span : spans) {
            if (span == null || span.centerline == null || span.centerline.isEmpty()) continue;
            forEachFootprintCell(span, lateral, (x, z) -> footprint.add(Pos2D.xzKey(x, z)));
        }
    }

    /** A cell of this edge's bridges (deck and lateral template width): road painting stays off it. */
    public boolean isBridgeCell(int x, int z) {
        return footprint.contains(Pos2D.xzKey(x, z));
    }

    /** Within the template's lateral half width of an already-built bridge deck. */
    public boolean isNearExistingBridgeDeck(int x, int z) {
        if (roadIndex == null) return false;
        int half = lateralHalfWidth();
        for (int dx = -half; dx <= half; dx++) {
            for (int dz = -half; dz <= half; dz++) {
                if (roadIndex.isBridgeXZ(x + dx, z + dz)) return true;
            }
        }
        return false;
    }

    /** Builds the span (or adopts the bridge already there) and registers it; false when the build degraded. */
    public boolean paint(BridgeSpan span, @Nullable RoadNetwork roadNetwork, @Nullable BlockPos fromVillage,
                         @Nullable BlockPos toVillage, RoadType roadType) {
        if (span == null) return true;
        if (!UltreiaConfig.BRIDGES.enabled) return false;

        // Skip building if the bridge already exists (another path built it first).
        if (isBridgeAlreadyAt(span)) {
            if (!dryRun) {
                if (roadNetwork != null) {
                    int[] deckHalf = BridgeDeckProfile.of(span);
                    roadNetwork.addBridgeSegment(span, deckHalf, roadIndex, fromVillage, toVillage, roadType);
                }
                if (roadIndex != null) markVolumeInIndex(span, roadIndex);
            }
            return true;
        }

        if (protectedAreas != null) {
            if (protectedAreas.contains(span.start.getX(), span.start.getZ())) return false;
            if (protectedAreas.contains(span.end.getX(), span.end.getZ())) return false;
        }

        boolean built = BridgeBuildService.build(world, sampler, span, dryRun);

        if (built && !dryRun && (roadIndex != null || roadNetwork != null)) {
            int[] deckHalf = BridgeDeckProfile.of(span);
            if (roadNetwork != null) {
                roadNetwork.addBridgeSegment(span, deckHalf, roadIndex, fromVillage, toVillage, roadType);
            } else if (roadIndex != null) {
                // roadNetwork absent: at least mark bridge decks in the index
                for (int i = 0; i < span.centerline.size(); i++) {
                    BlockPos c = span.centerline.get(i);
                    int deckY = HalfY.surfaceBlockY(
                            (deckHalf != null && i < deckHalf.length) ? deckHalf[i] : c.getY() * 2);
                    roadIndex.markBridgeDeck(c.getX(), deckY, c.getZ());
                }
            }
            if (roadIndex != null) {
                markVolumeInIndex(span, roadIndex);
            }
        }

        return built;
    }

    /**
     * Road cells to force-paint straight past the bridge exit, stepping half a block per cell from the deck
     * toward the ground. Each entry is {x, z, half, n} with n the 1-based distance from the exit.
     */
    public List<int[]> exitCells(BridgeSpan span, int blocks) {
        List<int[]> out = new ArrayList<>();
        if (span == null || blocks <= 0) return out;
        if (span.centerline == null || span.centerline.size() < 2) return out;

        int[] exitStep = exitStep(span);
        int dx = exitStep[0];
        int dz = exitStep[1];

        if (dx == 0 && dz == 0) return out;

        int[] deckHalf = BridgeDeckProfile.of(span);
        int exitHalf = deckHalf[deckHalf.length - 1];

        int maxHalfStep = Math.max(1, HalfY.MAX_ROAD_STEP_HALF_PER_BLOCK);

        int x = span.end.getX();
        int z = span.end.getZ();
        int half = exitHalf;

        for (int i = 1; i <= blocks; i++) {
            x += dx;
            z += dz;

            if (isBridgeCell(x, z)) continue;
            if (protectedAreas != null && protectedAreas.contains(x, z)) continue;

            TerrainSampler.Sample s = sampler.sample(x, z);
            if (s == null) continue;
            if (sampler.forbiddenHard(s)) continue;
            if (s.isOcean || s.isWater) continue;
            if (sampler.forbidden(s, TerrainSampler.Mode.PAINTING)) continue;

            int naturalHalf = HalfY.ofBlockTop(s.surfaceY);
            half = stepHalfToward(half, naturalHalf, maxHalfStep);

            out.add(new int[]{x, z, half, i});
        }
        return out;
    }

    private boolean isBridgeAlreadyAt(BridgeSpan span) {
        if (roadIndex == null) return false;
        if (span.centerline == null || span.centerline.isEmpty()) return false;
        BlockPos mid = span.centerline.get(span.centerline.size() / 2);
        return roadIndex.isBridgeXZ(mid.getX(), mid.getZ());
    }

    private void markVolumeInIndex(BridgeSpan span, RoadIndex idx) {
        // Only mark volume for water-crossing bridges. Valley bridges are high enough
        // above the valley floor that ground-level paths cannot interact with them.
        if (!span.hasWaterCrossing) return;
        forEachFootprintCell(span, lateralHalfWidth(), idx::markBridgeVolume);
    }

    /** Lateral stripe of every centerline cell, plus the corner cells of diagonal steps so there are no holes. */
    private static void forEachFootprintCell(BridgeSpan span, int half, CellSink sink) {
        for (int i = 0; i < span.centerline.size(); i++) {
            BlockPos c = span.centerline.get(i);
            BridgeGeometry.Dir2 perp = BridgeGeometry.localDir(span.centerline, i).perp();
            for (int o = -half; o <= half; o++) {
                sink.accept(c.getX() + perp.dx * o, c.getZ() + perp.dz * o);
            }
            if (i > 0) {
                BlockPos p = span.centerline.get(i - 1);
                int stepX = Integer.signum(c.getX() - p.getX());
                int stepZ = Integer.signum(c.getZ() - p.getZ());
                if (stepX != 0 && stepZ != 0) {
                    for (int o = -half; o <= half; o++) {
                        sink.accept(p.getX() + stepX + perp.dx * o, p.getZ() + perp.dz * o);
                        sink.accept(p.getX() + perp.dx * o, p.getZ() + stepZ + perp.dz * o);
                    }
                }
            }
        }
    }

    private static int lateralHalfWidth() {
        int half = 1;

        BridgeTemplate active = BridgeTemplateRegistry.getDefaultTemplate();
        if (active != null) {
            int tHalf = Math.max(Math.abs(active.minLateral), Math.abs(active.maxLateral));
            if (tHalf > half) half = tHalf;
        }

        return half;
    }

    private static int[] exitStep(BridgeSpan span) {
        for (int i = span.centerline.size() - 1; i > 0; i--) {
            BlockPos a = span.centerline.get(i - 1);
            BlockPos b = span.centerline.get(i);

            int dx = Integer.signum(b.getX() - a.getX());
            int dz = Integer.signum(b.getZ() - a.getZ());

            if (dx != 0 || dz != 0) {
                return new int[]{dx, dz};
            }
        }

        return new int[]{0, 0};
    }

    private static int stepHalfToward(int curHalf, int targetHalf, int maxHalfStep) {
        int d = targetHalf - curHalf;
        if (d > maxHalfStep) return curHalf + maxHalfStep;
        if (d < -maxHalfStep) return curHalf - maxHalfStep;
        return targetHalf;
    }
}

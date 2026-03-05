package com.jubitus.millpathbuilder.gen.bridge;

import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.MPBGround;
import com.jubitus.millpathbuilder.gen.MPBProtectedAreas;
import com.jubitus.millpathbuilder.gen.MPBRoadColumns;
import com.jubitus.millpathbuilder.gen.TerrainSampler;
import com.jubitus.millpathbuilder.gen.mill.MPBMillPathPalette;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.List;

public final class MPBBridgeFeasibility {

    private MPBBridgeFeasibility() {}

    public enum FailReason {
        DISABLED,
        LENGTH,
        ENDPOINT_FORBIDDEN,
        OCEAN,
        PROTECTED,
        RIDGE_WALL,
        NOT_ENOUGH_VOID_OR_WATER,
        TUNNEL_INTERSECTION,
        TILE_ENTITY,
        BAD_LANDING,
        TOO_CLOSE_TO_OTHER_BRIDGE
    }

    public static final class Result {
        public final boolean ok;
        public final FailReason reason;
        @Nullable public final BridgeSpan span;

        private Result(boolean ok, FailReason reason, @Nullable BridgeSpan span) {
            this.ok = ok;
            this.reason = reason;
            this.span = span;
        }

        public static Result ok(BridgeSpan span) { return new Result(true, null, span); }
        public static Result no(FailReason r) { return new Result(false, r, null); }
    }

    /**
     * Validate that a bridge between (sx,sz) and (ex,ez) is buildable and desirable.
     * The returned BridgeSpan is ready for BridgeBuilder2.build().
     */
    public static Result check(WorldServer world,
                               TerrainSampler sampler,
                               int sx, int sz,
                               int ex, int ez,
                               @Nullable MPBProtectedAreas protectedAreas,
                               @Nullable LongOpenHashSet recentBridgeCellsXZ // optional anti-overlap
    ) {
        if (!MPBConfig.PATHFINDING_BRIDGES.enabled) return Result.no(FailReason.DISABLED);
        if (!MPBConfig.BRIDGES.enabled) return Result.no(FailReason.DISABLED);

        TerrainSampler.Sample ss = sampler.sample(sx, sz);
        TerrainSampler.Sample es = sampler.sample(ex, ez);
        if (ss == null || es == null) return Result.no(FailReason.ENDPOINT_FORBIDDEN);

        if (sampler.forbidden(ss, TerrainSampler.Mode.PATHFINDING)) return Result.no(FailReason.ENDPOINT_FORBIDDEN);
        if (sampler.forbidden(es, TerrainSampler.Mode.PATHFINDING)) return Result.no(FailReason.ENDPOINT_FORBIDDEN);

        if (ss.isOcean || es.isOcean) return Result.no(FailReason.OCEAN);
        if (ss.isWater || es.isWater) return Result.no(FailReason.ENDPOINT_FORBIDDEN);

        // Protected endpoints hard forbid (same rule as painter)
        if (protectedAreas != null) {
            int pad = Math.max(0, MPBConfig.PATHFINDING_BRIDGES.protectedPadding);
            if (containsProtected(protectedAreas, sx, sz, pad)) return Result.no(FailReason.PROTECTED);
            if (containsProtected(protectedAreas, ex, ez, pad)) return Result.no(FailReason.PROTECTED);
        }

        List<BlockPos> line = MPBConfig.BRIDGE_GEOMETRY.useRaster8ForBridges
                ? RasterLine8.raster(sx, sz, ex, ez, 0)
                : SupercoverLine.raster(sx, sz, ex, ez, 0);

        int len = Math.max(0, line.size() - 1);
        int minLen = Math.max(2, MPBConfig.PATHFINDING_BRIDGES.minJumpLenBlocks);
        int maxLen = Math.max(minLen, MPBConfig.PATHFINDING_BRIDGES.maxJumpLenBlocks);
        if (len < minLen || len > maxLen) return Result.no(FailReason.LENGTH);

        // Avoid ocean along line
        for (BlockPos p : line) {
            TerrainSampler.Sample sp = sampler.sample(p.getX(), p.getZ());
            if (sp == null) continue;
            if (sp.isOcean) return Result.no(FailReason.OCEAN);
        }

        // Protected crossing
        if (protectedAreas != null && MPBConfig.PATHFINDING_BRIDGES.avoidProtectedZonesOnBridges) {
            int pad = Math.max(0, MPBConfig.PATHFINDING_BRIDGES.protectedPadding);
            for (BlockPos p : line) {
                if (containsProtected(protectedAreas, p.getX(), p.getZ(), pad)) {
                    return Result.no(FailReason.PROTECTED);
                }
            }
        }

        // Baseline + ridge/wall check + void/water run
        int baseline = Math.max(ss.surfaceY, es.surfaceY);
        int ridgeRise = Math.max(0, MPBConfig.PATHFINDING_BRIDGES.ridgeRiseBlocks);
        int maxAllowedY = baseline + ridgeRise;

        int minVoidDepth = Math.max(2, MPBConfig.PATHFINDING_BRIDGES.minVoidDepthBlocks);
        int minVoidRun = Math.max(0, MPBConfig.PATHFINDING_BRIDGES.minVoidRunBlocks);
        int minWaterRun = Math.max(0, MPBConfig.PATHFINDING_BRIDGES.minWaterRunBlocks);

        int voidRun = 0, voidRunMax = 0;
        int waterRun = 0, waterRunMax = 0;

        for (BlockPos p : line) {
            TerrainSampler.Sample sp = sampler.sample(p.getX(), p.getZ());
            if (sp == null) continue;

            if (sp.surfaceY > maxAllowedY) return Result.no(FailReason.RIDGE_WALL);

            int drop = baseline - sp.surfaceY;
            boolean deepVoid = drop >= minVoidDepth;
            boolean water = sp.isWater && !sp.isOcean;

            if (deepVoid) { voidRun++; voidRunMax = Math.max(voidRunMax, voidRun); }
            else voidRun = 0;

            if (water) { waterRun++; waterRunMax = Math.max(waterRunMax, waterRun); }
            else waterRun = 0;
        }

        if (voidRunMax < minVoidRun && waterRunMax < minWaterRun) {
            return Result.no(FailReason.NOT_ENOUGH_VOID_OR_WATER);
        }

        // Deck halfY endpoints: allow +/-0.5 block mismatch max
        // Use same convention as BridgePlanner: surfaceY*2 + 3 (puts walking on slabs naturally)
        int startHalf = ss.surfaceY * 2 + 3;
        int endHalf = es.surfaceY * 2 + 3;

        int halfDiff = Math.abs(endHalf - startHalf);
        if (halfDiff > Math.max(1, MPBConfig.PATHFINDING_BRIDGES.maxEndHalfDelta)) {
            return Result.no(FailReason.ENDPOINT_FORBIDDEN);
        }

        // Build smooth deck half profile (similar to BridgeBuilder2)
        int[] deckHalf = buildDeckHalfProfile(line.size(), startHalf, endHalf, Math.max(0, MPBConfig.BRIDGES.maxDeckDeltaHalfPerBlock));

        // Anti overlap: reject if too close to recent bridge cells (optional)
        if (recentBridgeCellsXZ != null) {
            int dist = Math.max(0, MPBConfig.PATHFINDING_BRIDGES.minDistanceBetweenBridgeSegments);
            if (dist > 0) {
                for (BlockPos p : line) {
                    if (nearAny(recentBridgeCellsXZ, p.getX(), p.getZ(), dist)) {
                        return Result.no(FailReason.TOO_CLOSE_TO_OTHER_BRIDGE);
                    }
                }
            }
        }

        // Tunnel / intersection check: deck must stay above terrain, and deck volume must not hit solid blocks (except soft clearable).
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int i = 0; i < line.size(); i++) {
            BlockPos p = line.get(i);
            int deckSurfaceHalf = deckHalf[i];
            SlabPlacement sp = slabPlacementForSurfaceHalfY(deckSurfaceHalf);

            // Deck block position
            int deckBlockY = sp.blockY;
            int x = p.getX();
            int z = p.getZ();

            TerrainSampler.Sample sm = sampler.sample(x, z);
            if (sm == null) continue;

            // Must be in air above terrain (avoid "bridge into mountain")
            // deck block is at y=blockY; slab occupies that block space
            if (sm.surfaceY >= deckBlockY) return Result.no(FailReason.TUNNEL_INTERSECTION);

            // No tiles at deck or in walk volume
            mp.setPos(x, deckBlockY, z);
            if (hasTile(world, mp)) return Result.no(FailReason.TILE_ENTITY);

            // Walk volume clearance; allow cutting soft + tree wood if configured (same spirit as BridgeBuilder2)
            if (!walkVolumeClearable(world, x, z, deckBlockY + 1, deckBlockY + 3)) {
                return Result.no(FailReason.TUNNEL_INTERSECTION);
            }
        }

        // Landing safety: ensure end column is not tree/air weirdness; accept natural ground or existing millpath
        if (!landingLooksSafe(world, sampler, ex, ez)) return Result.no(FailReason.BAD_LANDING);
        if (!landingLooksSafe(world, sampler, sx, sz)) return Result.no(FailReason.BAD_LANDING);

        // micro flag: optional
        boolean micro = false;
        if (MPBConfig.BRIDGES.microBridges) {
            int microMax = Math.max(2, MPBConfig.BRIDGES.microBridgeMaxLen);
            if (len <= microMax) micro = true;
        }

        BridgeSpan span = new BridgeSpan(
                new BlockPos(sx, ss.surfaceY, sz),
                new BlockPos(ex, es.surfaceY, ez),
                line,
                startHalf,
                endHalf,
                micro
        );

        return Result.ok(span);
    }

    private static boolean landingLooksSafe(WorldServer world, TerrainSampler sampler, int x, int z) {
        TerrainSampler.Sample s = sampler.sample(x, z);
        if (s == null) return false;
        if (s.isOcean || s.isWater) return false;

        BlockPos top = new BlockPos(x, s.surfaceY, z);
        IBlockState st = world.getBlockState(top);
        if (st == null) return false;

        if (MPBMillPathPalette.isMillPath(st.getBlock())) return true;
        if (!MPBGround.isRoadReplaceableGround(st) && !st.getMaterial().isSolid()) return false;

        // basic headroom
        BlockPos up1 = top.up();
        BlockPos up2 = top.up(2);
        if (!isClearableOrAir(world, up1)) return false;
        if (!isClearableOrAir(world, up2)) return false;

        return true;
    }

    private static boolean isClearableOrAir(WorldServer world, BlockPos pos) {
        IBlockState st = world.getBlockState(pos);
        Material m = st.getMaterial();
        if (m == Material.AIR) return true;

        if (MPBRoadColumns.isSoftClearable(world, pos, st)) return true;

        if (MPBConfig.PAINTING.clearTrees && MPBConfig.PAINTING.clearTreeTrunks) {
            if (st.getBlock().isWood(world, pos) || m == Material.WOOD) return true;
        }
        return false;
    }

    private static boolean walkVolumeClearable(WorldServer world, int x, int z, int yFrom, int yTo) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = yFrom; y <= yTo; y++) {
            p.setPos(x, y, z);
            IBlockState st = world.getBlockState(p);
            Material m = st.getMaterial();
            if (m == Material.AIR) continue;
            if (hasTile(world, p)) return false;

            if (MPBRoadColumns.isSoftClearable(world, p, st)) continue;
            if (st.getBlock().isLeaves(st, world, p) || m == Material.LEAVES) continue;

            if (MPBConfig.PAINTING.clearTrees && MPBConfig.PAINTING.clearTreeTrunks) {
                if (st.getBlock().isWood(world, p) || m == Material.WOOD) continue;
            }

            // Hard solid in walk volume -> reject (prevents bridges through cliffs/buildings)
            if (m.isSolid() && st.getBlock() != Blocks.WATER && st.getMaterial() != Material.WATER) return false;
        }
        return true;
    }

    private static boolean hasTile(WorldServer world, BlockPos pos) {
        TileEntity te = world.getTileEntity(pos);
        return te != null;
    }

    private static boolean containsProtected(MPBProtectedAreas areas, int x, int z, int pad) {
        if (areas.contains(x, z)) return true;
        if (pad <= 0) return false;
        for (int dx = -pad; dx <= pad; dx++) {
            for (int dz = -pad; dz <= pad; dz++) {
                if (areas.contains(x + dx, z + dz)) return true;
            }
        }
        return false;
    }

    private static boolean nearAny(LongOpenHashSet set, int x, int z, int r) {
        // small radius scan; r is usually <= 24
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                long k = xzKey(x + dx, z + dz);
                if (set.contains(k)) return true;
            }
        }
        return false;
    }

    private static long xzKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static int[] buildDeckHalfProfile(int n, int startHalf, int endHalf, int maxStepHalf) {
        if (n <= 0) return new int[0];
        int[] ideal = new int[n];
        for (int i = 0; i < n; i++) {
            double t = (n <= 1) ? 0.0 : ((double) i / (double) (n - 1));
            ideal[i] = (int) Math.round((1.0 - t) * (double) startHalf + t * (double) endHalf);
        }
        if (maxStepHalf <= 0) {
            int flat = Math.max(startHalf, endHalf);
            int[] out = new int[n];
            for (int i = 0; i < n; i++) out[i] = flat;
            return out;
        }

        int[] out = new int[n];
        out[0] = ideal[0];
        for (int i = 1; i < n; i++) {
            int want = ideal[i];
            int prev = out[i - 1];
            int d = want - prev;
            if (d > maxStepHalf) want = prev + maxStepHalf;
            else if (d < -maxStepHalf) want = prev - maxStepHalf;
            out[i] = want;
        }
        for (int i = n - 2; i >= 0; i--) {
            int want = out[i];
            int next = out[i + 1];
            int d = want - next;
            if (d > maxStepHalf) want = next + maxStepHalf;
            else if (d < -maxStepHalf) want = next - maxStepHalf;
            out[i] = want;
        }
        return out;
    }

    private static final class SlabPlacement {
        final int blockY;
        final boolean top;
        SlabPlacement(int blockY, boolean top) { this.blockY = blockY; this.top = top; }
    }

    private static SlabPlacement slabPlacementForSurfaceHalfY(int surfaceHalfY) {
        if ((surfaceHalfY & 1) == 1) {
            int y = (surfaceHalfY - 1) / 2;
            return new SlabPlacement(y, false);
        } else {
            int y = (surfaceHalfY / 2) - 1;
            return new SlabPlacement(y, true);
        }
    }
}
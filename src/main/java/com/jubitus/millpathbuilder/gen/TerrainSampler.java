package com.jubitus.millpathbuilder.gen;

import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.mill.MPBMillPathPalette;
import com.jubitus.millpathbuilder.gen.newp.MPBTreeCompat;
import com.jubitus.millpathbuilder.gen.world.MPBRoadIndexData;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.BiomeDictionary;

import java.util.*;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

public class TerrainSampler {

    public enum Mode { PATHFINDING, PAINTING }
    public enum ForbiddenReason { NONE, OCEAN, BLACKLIST, WATER, WATER_BUFFER, BLOCKED }

    private static final int MAX_SAMPLE_CACHE  = 500_000;
    private static final int MAX_LIGHT_CACHE   = 400_000;

    // Light-sample packed int: bits 0-8 = surfaceY (0-255+), bit 9 = isWater, bit 10 = isOcean.
    // Integer.MIN_VALUE = not loaded / not in cache.
    public static final int LIGHT_NOT_LOADED  = Integer.MIN_VALUE;
    public static final int LIGHT_SURFACE_MASK = 0x1FF;
    public static final int LIGHT_WATER_BIT   = 1 << 9;
    public static final int LIGHT_OCEAN_BIT   = 1 << 10;

    public enum BlockedDetail {
        NONE,
        NO_PAINT_ZONE,
        WATER,
        NON_WALKABLE_GROUND,
        BAD_HEADROOM,
        MANMADE_NEARBY,
        TILE_NEARBY,
        NON_PAINTABLE_GROUND,
        BUILDING_GUARD
    }

    public static final class Sample {
        public final int x, z;
        public final int surfaceY;
        public final boolean isWater;
        public final boolean isOcean;
        public final boolean isBlacklisted;
        public final boolean blockedPath;
        public final boolean blockedPaint;
        public final Biome biome;
        public final boolean smallWater;
        public final boolean nearWaterLike;
        /** True when a tree trunk/branch is present at surfaceY+1 or surfaceY+2. */
        public final boolean hasTreeTrunk;

        public final BlockedDetail blockedPathDetail;
        public final BlockedDetail blockedPaintDetail;

        public Sample(int x, int z, int surfaceY, boolean isWater, boolean isOcean, boolean isBlacklisted,
                      boolean blockedPath, boolean blockedPaint, boolean smallWater, boolean nearWaterLike,
                      boolean hasTreeTrunk,
                      BlockedDetail blockedPathDetail, BlockedDetail blockedPaintDetail, Biome biome) {
            this.x = x;
            this.z = z;
            this.surfaceY = surfaceY;
            this.isWater = isWater;
            this.isOcean = isOcean;
            this.isBlacklisted = isBlacklisted;
            this.blockedPath = blockedPath;
            this.blockedPaint = blockedPaint;
            this.smallWater = smallWater;
            this.nearWaterLike = nearWaterLike;
            this.hasTreeTrunk = hasTreeTrunk;
            this.blockedPathDetail = blockedPathDetail;
            this.blockedPaintDetail = blockedPaintDetail;
            this.biome = biome;
        }
    }

    private final WorldServer world;
    private final Long2ObjectOpenHashMap<Sample> cache = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap lightCache = new Long2IntOpenHashMap();
    private final Set<ResourceLocation> blacklist = new HashSet<>();
    private MPBProtectedAreas protectedAreas;

    private MPBChunkManager chunkManager;
    private MPBRoadIndexData roadIndex;

    private final Long2IntOpenHashMap roadDistCache = new Long2IntOpenHashMap();
    {
        roadDistCache.defaultReturnValue(Integer.MIN_VALUE);
    }

    public TerrainSampler(WorldServer world) {
        this.world = world;
        lightCache.defaultReturnValue(LIGHT_NOT_LOADED);
        for (String s : MPBConfig.GENERAL.biomeBlacklist) {
            if (s == null || s.trim().isEmpty()) continue;
            blacklist.add(new ResourceLocation(s.trim()));
        }
    }

    public void setProtectedAreas(MPBProtectedAreas areas) {
        this.protectedAreas = areas;
    }

    public void setChunkManager(MPBChunkManager mgr) {
        this.chunkManager = mgr;
    }

    public void setRoadIndex(MPBRoadIndexData idx) {
        this.roadIndex = idx;
        roadDistCache.clear();
    }

    public void clearCache() {
        cache.clear();
        lightCache.clear();
        roadDistCache.clear();
    }

    public void invalidate(int x, int z) {
        long k = key(x, z);
        cache.remove(k);
        lightCache.remove(k);
        roadDistCache.remove(k);
    }

    public void invalidateAround(int x, int z, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                long k = key(x + dx, z + dz);
                cache.remove(k);
                lightCache.remove(k);
                roadDistCache.remove(k);
            }
        }
    }

    public Sample sample(int x, int z) {
        long k = key(x, z);
        Sample cached = cache.get(k);
        if (cached != null) return cached;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, 0, z);

        if (!world.isBlockLoaded(pos)) {
            if (MPBConfig.GENERAL.allowChunkLoading && chunkManager != null) {
                if (!chunkManager.ensureLoaded(world, x, z)) {
                    Sample out = new Sample(x, z, 1, false, false, false, true, true, false, false, false,
                            BlockedDetail.NON_WALKABLE_GROUND, BlockedDetail.NON_PAINTABLE_GROUND, world.getBiome(pos));
                    return cachePut(k, out);
                }
            } else {
                Sample out = new Sample(x, z, 1, false, false, false, true, true, false, false, false,
                        BlockedDetail.NON_WALKABLE_GROUND, BlockedDetail.NON_PAINTABLE_GROUND, world.getBiome(pos));
                return cachePut(k, out);
            }
        }

        Biome biome = world.getBiome(pos);
        ResourceLocation biomeId = biome.getRegistryName();
        boolean isBlacklisted = biomeId != null && blacklist.contains(biomeId);

        boolean isOcean = false;
        if (MPBConfig.GENERAL.forbidOcean) {
            isOcean = BiomeDictionary.hasType(biome, BiomeDictionary.Type.OCEAN)
                    || (biomeId != null && biomeId.getPath().contains("ocean"));
        }

        int topY = world.getHeight(pos).getY() - 1;
        if (topY < 1) topY = 1;

        boolean isWater = false;
        int y = topY;

        for (int i = 0; i < 256 && y > 1; i++, y--) {
            pos.setPos(x, y, z);
            IBlockState st = world.getBlockState(pos);
            Material m = st.getMaterial();

            if (m == Material.AIR) continue;

            if (isWaterLike(st)) {
                isWater = true;
                break;
            }

            if (isWaterSurfaceDecoration(st)) {
                BlockPos below = pos.down();
                IBlockState belowSt = world.getBlockState(below);
                if (isWaterLike(belowSt)) {
                    isWater = true;
                    y = below.getY();
                    break;
                }
                continue;
            }

            if (isSkippableTop(st, pos)) continue;

            break;
        }

        if (y < 1) y = 1;
        int surfaceY = y;

        boolean inNoPaint = isInBuildingNoPaintZone(x, z);
        boolean hasMillPathCol = (!isWater) && columnHasMillPath(x, surfaceY, z);

        boolean blockedPath = false;
        boolean blockedPaint = false;
        BlockedDetail blockedPathDetail = BlockedDetail.NONE;
        BlockedDetail blockedPaintDetail = BlockedDetail.NONE;

        if (!isWater) {
            if (inNoPaint) {
                blockedPaint = true;
                blockedPaintDetail = BlockedDetail.NO_PAINT_ZONE;

                if (MPBConfig.BUILDINGS.enabled && MPBConfig.BUILDINGS.hardBlockInPathfinding) {
                    if (MPBConfig.BUILDINGS.allowExistingRoadThroughBuildings && hasMillPathCol) {
                        blockedPath = false;
                        blockedPathDetail = BlockedDetail.NONE;
                    } else {
                        blockedPath = true;
                        blockedPathDetail = BlockedDetail.NO_PAINT_ZONE;
                    }
                } else {
                    blockedPath = !hasMillPathCol;
                    if (blockedPath) blockedPathDetail = BlockedDetail.NO_PAINT_ZONE;
                }
            } else {
                BlockPos.MutableBlockPos pos2 = new BlockPos.MutableBlockPos(x, surfaceY, z);
                IBlockState ground = world.getBlockState(pos2);

                // Mill path blocks are valid painting surfaces (we paint onto/alongside them).
                boolean paintable = isNaturalGround(ground) || MPBMillPathPalette.isMillPath(ground.getBlock());
                boolean walkable = ground.getMaterial().isSolid() && ground.getMaterial() != Material.LEAVES;

                BlockPos.MutableBlockPos up1 = new BlockPos.MutableBlockPos(x, surfaceY + 1, z);
                BlockPos.MutableBlockPos up2 = new BlockPos.MutableBlockPos(x, surfaceY + 2, z);

                boolean headroomPath = isClearableOrAir(world.getBlockState(up1), up1, Mode.PATHFINDING)
                        && isClearableOrAir(world.getBlockState(up2), up2, Mode.PATHFINDING);
                boolean headroomPaint = isClearableOrAir(world.getBlockState(up1), up1, Mode.PAINTING)
                        && isClearableOrAir(world.getBlockState(up2), up2, Mode.PAINTING);

                blockedPath = !walkable || !headroomPath;
                if (!walkable) blockedPathDetail = BlockedDetail.NON_WALKABLE_GROUND;
                else if (!headroomPath) blockedPathDetail = BlockedDetail.BAD_HEADROOM;

                boolean manmade = hasManmadeNearby(x, surfaceY, z);
                boolean tile = hasTileEntityNearby(x, surfaceY, z);

                blockedPaint = !paintable || !headroomPaint || manmade || tile;
                if (!paintable) blockedPaintDetail = BlockedDetail.NON_PAINTABLE_GROUND;
                else if (!headroomPaint) blockedPaintDetail = BlockedDetail.BAD_HEADROOM;
                else if (manmade) blockedPaintDetail = BlockedDetail.MANMADE_NEARBY;
                else if (tile) blockedPaintDetail = BlockedDetail.TILE_NEARBY;

                if (!blockedPaint && MPBConfig.BUILDINGS.enabled && MPBConfig.BUILDINGS.extraPaintingGuard) {
                    int rr = Math.max(1, MPBConfig.BUILDINGS.extraPaintingGuardRadius);
                    int nearR = Math.max(2, MPBConfig.BUILDINGS.footprintPadding + 2);
                    if (isNearBuildingNoPaintZone(x, z, nearR)) {
                        if (looksLikeBuildingColumn(x, surfaceY, z, rr)) {
                            blockedPaint = true;
                            blockedPaintDetail = BlockedDetail.BUILDING_GUARD;
                        }
                    }
                }
            }
        }

        boolean smallWater = false;
        if (isWater && !isOcean) {
            smallWater = isSmallWaterPatch(x, surfaceY, z);
        }

        boolean nearWaterLike = false;
        if (!isWater && MPBConfig.WATER_AVOID.enabled) {
            int r = Math.max(1, MPBConfig.WATER_AVOID.bufferRadius);
            nearWaterLike = hasWaterLikeInManhattanRadius(x, z, surfaceY, r);
        }

        boolean hasTreeTrunk = false;
        if (!isWater && MPBConfig.PAINTING.clearTrees) {
            BlockPos.MutableBlockPos tp = new BlockPos.MutableBlockPos();
            for (int dy = 1; dy <= 2; dy++) {
                tp.setPos(x, surfaceY + dy, z);
                IBlockState tst = world.getBlockState(tp);
                if (tst != null && (MPBTreeCompat.isTreeWoodLike(world, tp, tst) || isLikelyTreeWood(tst, tp))) {
                    hasTreeTrunk = true;
                    break;
                }
            }
        }

        Sample out = new Sample(x, z, surfaceY, isWater, isOcean, isBlacklisted,
                blockedPath, blockedPaint, smallWater, nearWaterLike, hasTreeTrunk,
                blockedPathDetail, blockedPaintDetail, biome);
        return cachePut(k, out);
    }

    public boolean forbidden(Sample s, Mode mode) {
        if (MPBConfig.GENERAL.forbidOcean && s.isOcean) return true;
        if (MPBConfig.GENERAL.hardBlockBlacklistedBiomes && s.isBlacklisted) return true;

        if (mode == Mode.PATHFINDING && s.isWater) return true;

        return mode == Mode.PAINTING ? s.blockedPaint : s.blockedPath;
    }

    public ForbiddenReason forbiddenReason(Sample s, Mode mode) {
        if (MPBConfig.GENERAL.forbidOcean && s.isOcean) return ForbiddenReason.OCEAN;
        if (MPBConfig.GENERAL.hardBlockBlacklistedBiomes && s.isBlacklisted) return ForbiddenReason.BLACKLIST;
        if (mode == Mode.PATHFINDING && s.isWater) return ForbiddenReason.WATER;

        if (mode == Mode.PAINTING ? s.blockedPaint : s.blockedPath) return ForbiddenReason.BLOCKED;
        return ForbiddenReason.NONE;
    }

    public String describeForbidden(Sample s, Mode mode) {
        ForbiddenReason r = forbiddenReason(s, mode);
        if (r == ForbiddenReason.OCEAN) return "ocean biome";
        if (r == ForbiddenReason.BLACKLIST) return "blacklisted biome";
        if (r == ForbiddenReason.WATER) return "water requires explicit bridge";
        if (r == ForbiddenReason.WATER_BUFFER) return "near water buffer";
        if (r == ForbiddenReason.BLOCKED) {
            BlockedDetail d = mode == Mode.PAINTING ? s.blockedPaintDetail : s.blockedPathDetail;
            return "blocked:" + d;
        }
        return "none";
    }

    public boolean forbiddenHard(Sample s) {
        if (MPBConfig.GENERAL.forbidOcean && s.isOcean) return true;
        return MPBConfig.GENERAL.hardBlockBlacklistedBiomes && s.isBlacklisted;
    }

    public boolean isProtectedNoPaint(int x, int z) {
        return protectedAreas != null && protectedAreas.contains(x, z);
    }

    public boolean isExistingRoadXZ(int x, int z) {
        return roadIndex != null && roadIndex.isRoadXZ(x, z);
    }

    @Nullable
    public Sample sampleIfLoaded(int x, int z) {
        long k = key(x, z);
        Sample cached = cache.get(k);
        if (cached != null) return cached;
        ChunkProviderServer cps = (ChunkProviderServer) world.getChunkProvider();
        if (cps.getLoadedChunk(x >> 4, z >> 4) == null) return null;
        return sample(x, z);
    }

    /**
     * Lightweight surface check: returns a packed int (LIGHT_* constants) or LIGHT_NOT_LOADED.
     * Only computes surfaceY, isWater, isOcean — skips all paint-blocking checks.
     * Use this for lateral wall probes and lookahead penalties to avoid polluting the full cache.
     */
    public int sampleLightIfLoaded(int x, int z) {
        long k = key(x, z);

        // Promote from full cache if available (free).
        Sample full = cache.get(k);
        if (full != null) {
            return (full.isOcean ? LIGHT_OCEAN_BIT : 0)
                    | (full.isWater ? LIGHT_WATER_BIT : 0)
                    | (full.surfaceY & LIGHT_SURFACE_MASK);
        }

        // Check light cache.
        int lv = lightCache.get(k);
        if (lv != LIGHT_NOT_LOADED) return lv;

        // Chunk must already be loaded — never force-load for lateral probes.
        ChunkProviderServer cps = (ChunkProviderServer) world.getChunkProvider();
        if (cps.getLoadedChunk(x >> 4, z >> 4) == null) return LIGHT_NOT_LOADED;

        // Quick surface scan: top-down, skip air/replaceable/snow/foliage — no paint sub-checks.
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, 0, z);
        int topY = world.getHeight(pos).getY() - 1;
        if (topY < 1) topY = 1;

        boolean isWater = false;
        int y = topY;

        for (int i = 0; i < 256 && y > 1; i++, y--) {
            pos.setPos(x, y, z);
            IBlockState st = world.getBlockState(pos);
            Material m = st.getMaterial();
            if (m == Material.AIR) continue;
            if (isWaterLike(st)) { isWater = true; break; }
            if (isWaterSurfaceDecoration(st)) {
                IBlockState below = world.getBlockState(pos.down());
                if (isWaterLike(below)) { isWater = true; y = y - 1; break; }
                continue;
            }
            if (isSkippableTop(st, pos)) continue;
            break;
        }
        if (y < 1) y = 1;

        boolean isOcean = false;
        if (MPBConfig.GENERAL.forbidOcean && isWater) {
            Biome biome = world.getBiome(pos.setPos(x, 0, z));
            ResourceLocation biomeId = biome.getRegistryName();
            isOcean = BiomeDictionary.hasType(biome, BiomeDictionary.Type.OCEAN)
                    || (biomeId != null && biomeId.getPath().contains("ocean"));
        }

        int packed = (isOcean ? LIGHT_OCEAN_BIT : 0) | (isWater ? LIGHT_WATER_BIT : 0) | (y & LIGHT_SURFACE_MASK);
        if (lightCache.size() >= MAX_LIGHT_CACHE) lightCache.clear();
        lightCache.put(k, packed);
        return packed;
    }

    @Nullable
    public BlockPos findNearestExistingRoad(int x, int z, int radius) {
        if (roadIndex == null) return null;
        return roadIndex.findNearestRoad(x, z, Math.max(0, radius));
    }

    public boolean isNaturalLandEndpoint(int x, int z) {
        Sample s = sample(x, z);
        if (s == null) return false;
        if (s.isWater || s.isOcean) return false;
        if (forbidden(s, Mode.PATHFINDING)) return false;
        if (isProtectedNoPaint(x, z)) return false;

        BlockPos top = new BlockPos(x, s.surfaceY, z);
        IBlockState st = world.getBlockState(top);
        return MPBGround.isRoadReplaceableGround(st) || MPBMillPathPalette.isMillPath(st.getBlock());
    }

    private Sample cachePut(long k, Sample out) {
        if (cache.size() >= MAX_SAMPLE_CACHE) {
            cache.clear();
            lightCache.clear();
            roadDistCache.clear();
        }
        cache.put(k, out);
        return out;
    }

    public double extraCost(Sample s, Mode mode) {
        double cost = 0.0;
        if (s.isWater && !s.isOcean) {
            cost += MPBConfig.PATHFINDING.waterPenalty;
            if (s.smallWater) cost += MPBConfig.PAINTING.smallWaterPenalty;
        }
        if (s.isBlacklisted && !MPBConfig.GENERAL.hardBlockBlacklistedBiomes) cost += MPBConfig.PATHFINDING.blacklistedBiomePenalty;

        if (mode == Mode.PATHFINDING && MPBConfig.BUILDINGS.enabled && MPBConfig.BUILDINGS.addPenaltyRing && protectedAreas != null) {
            int ring = Math.max(0, MPBConfig.BUILDINGS.penaltyRingRadius);
            if (ring > 0 && !protectedAreas.contains(s.x, s.z)) {
                if (isNearBuildingNoPaintZone(s.x, s.z, ring)) {
                    cost += Math.max(0.0, MPBConfig.BUILDINGS.penaltyRingCost);
                }
            }
        }

        if (mode == Mode.PATHFINDING && MPBConfig.PATHFINDING.avoidProtectedZones && protectedAreas != null) {
            cost += (double) protectedAreas.penaltyAt(s.x, s.z);
        }

        if (mode == Mode.PATHFINDING && MPBConfig.PATHFINDING.avoidParallelToExistingRoads && roadIndex != null) {
            int maxR = Math.max(1, MPBConfig.PATHFINDING.parallelRoadAvoidRadius);
            int step = Math.max(1, MPBConfig.PATHFINDING.parallelRoadAvoidScanStep);

            long kk = key(s.x, s.z);
            int dist = roadDistCache.get(kk);

            if (dist == Integer.MIN_VALUE) {
                dist = roadIndex.approxNearestRoadDistManhattan(s.x, s.z, maxR, step);
                roadDistCache.put(kk, dist);
            }

            if (dist > 1 && dist != Integer.MAX_VALUE) {
                double t = (double) (maxR - dist + 1);
                if (t > 0.0) {
                    double base = MPBConfig.PATHFINDING.parallelRoadPenalty;
                    double p = MPBConfig.PATHFINDING.parallelRoadPenaltyPower;
                    if (p < 1.0) p = 1.0;
                    cost += base * Math.pow(t, p);
                }
            }
        }
        if (MPBConfig.WATER_AVOID.enabled && !s.isWater && s.nearWaterLike) {
            int r = Math.max(1, MPBConfig.WATER_AVOID.bufferRadius);
            cost += 35.0 * (double) r;
        }
        if (mode == Mode.PATHFINDING && s.hasTreeTrunk && MPBConfig.PAINTING.clearTrees) {
            cost += MPBConfig.PATHFINDING.treePenalty;
        }
        return cost;
    }

    private boolean hasTileEntityNearby(int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos p = new BlockPos(x + dx, y + dy, z + dz);
                    if (world.getTileEntity(p) != null) return true;
                }
            }
        }
        return false;
    }

    private boolean hasManmadeNearby(int x, int y, int z) {
        int count = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                p.setPos(x + dx, y, z + dz);
                if (isManmadeSolid(world.getBlockState(p), p)) count++;
                p.setPos(x + dx, y + 1, z + dz);
                if (isManmadeSolid(world.getBlockState(p), p)) count++;
            }
        }
        return count >= 3;
    }

    private boolean isManmadeSolid(IBlockState st, BlockPos pos) {
        if (!st.getMaterial().isSolid()) return false;
        Block b = st.getBlock();
        if (b.isWood(world, pos)) return false;
        if (b.isLeaves(st, world, pos)) return false;
        // Mill/MPB path blocks are road infrastructure, not obstacles to paint around.
        if (MPBMillPathPalette.isMillPath(b)) return false;
        return !isNaturalGround(st);
    }

    private boolean isSkippableTop(IBlockState st, BlockPos pos) {
        Material m = st.getMaterial();
        Block b = st.getBlock();

        if (MPBTreeCompat.isTreeFoliageLike(world, pos, st)) return true;
        if (m.isReplaceable()) return true;
        if (b.isReplaceable(world, pos)) return true;
        if (b == Blocks.SNOW_LAYER) return true;
        if (isWaterSurfaceDecoration(st)) return true;

        if (MPBConfig.PAINTING.clearTrees && MPBConfig.PAINTING.clearTreeTrunks) {
            if (MPBTreeCompat.isTreeWoodLike(world, pos, st)) return true;
            if (isLikelyTreeWood(st, pos)) return true;
        }

        return false;
    }

    private static boolean isWaterSurfaceDecoration(IBlockState st) {
        if (st == null) return false;

        Block b = st.getBlock();
        if (b == Blocks.WATERLILY) return true;

        Material m = st.getMaterial();
        if (m == Material.PLANTS || m == Material.VINE) {
            ResourceLocation id = b.getRegistryName();
            if (id != null) {
                String s = id.toString().toLowerCase(Locale.ROOT);
                if (s.contains("lilypad") || s.contains("lily_pad") || s.contains("waterlily") || s.contains("water_lily")) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isLikelyTreeWood(IBlockState st, BlockPos pos) {
        Block b = st.getBlock();
        if (!(b.isWood(world, pos) || st.getMaterial() == Material.WOOD)) return false;

        int leaves = 0;
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    q.setPos(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    IBlockState st2 = world.getBlockState(q);
                    if (st2.getBlock().isLeaves(st2, world, q) || st2.getMaterial() == Material.LEAVES) {
                        leaves++;
                        if (leaves >= 3) return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isSmallWaterPatch(int x, int surfaceY, int z) {
        int r = Math.max(1, MPBConfig.PAINTING.smallWaterScanRadius);
        int maxCells = Math.max(0, MPBConfig.PAINTING.smallWaterMaxCells);

        int water = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                p.setPos(x + dx, surfaceY, z + dz);

                if (!world.isBlockLoaded(p)) continue;
                IBlockState st = world.getBlockState(p);
                if (isWaterLike(st)) {
                    water++;
                    if (water > maxCells) return false;
                }
            }
        }
        return water > 0 && water <= maxCells;
    }

    private boolean isClearableOrAir(IBlockState st, BlockPos pos, Mode mode) {
        Material m = st.getMaterial();
        Block b = st.getBlock();
        if (m == Material.AIR) return true;

        if (MPBTreeCompat.isTreeFoliageLike(world, pos, st)) return true;
        if (m.isReplaceable()) return true;
        if (b.isReplaceable(world, pos)) return true;
        if (b == Blocks.SNOW_LAYER) return true;

        if (mode == Mode.PATHFINDING && MPBConfig.PAINTING.clearTrees && MPBConfig.PAINTING.clearTreeTrunks) {
            if (MPBTreeCompat.isTreeWoodLike(world, pos, st)) return true;
        }

        return false;
    }

    private boolean columnHasMillPath(int x, int surfaceY, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = -2; dy <= 4; dy++) {
            int yy = surfaceY + dy;
            if (yy < 1 || yy > 255) continue;
            p.setPos(x, yy, z);
            IBlockState st = world.getBlockState(p);
            if (st != null && MPBMillPathPalette.isMillPath(st.getBlock())) return true;
        }
        return false;
    }

    private boolean hasWaterLikeInManhattanRadius(int x, int z, int yGuess, int r) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int d = 1; d <= r; d++) {
            for (int dx = -d; dx <= d; dx++) {
                int dz = d - Math.abs(dx);

                if (dz == 0) {
                    if (hasWaterLikeNearColumn(x + dx, z, yGuess, p)) return true;
                } else {
                    if (hasWaterLikeNearColumn(x + dx, z + dz, yGuess, p)) return true;
                    if (hasWaterLikeNearColumn(x + dx, z - dz, yGuess, p)) return true;
                }
            }
        }

        return false;
    }

    private boolean hasWaterLikeNearColumn(int x, int z, int yGuess, BlockPos.MutableBlockPos p) {
        p.setPos(x, 0, z);

        if (!world.isBlockLoaded(p)) {
            if (MPBConfig.GENERAL.allowChunkLoading && chunkManager != null) {
                if (!chunkManager.ensureLoaded(world, x, z)) return true;
            } else {
                return true;
            }
        }

        int topY = world.getHeight(p).getY() - 1;
        if (topY < 1) topY = 1;

        int start = Math.min(255, Math.max(1, Math.max(yGuess + 2, topY)));
        int y = start;

        for (int i = 0; i < 16 && y > 1; i++, y--) {
            p.setPos(x, y, z);
            IBlockState st = world.getBlockState(p);
            Material m = st.getMaterial();

            if (m == Material.AIR) continue;
            if (isWaterLike(st)) return true;

            if (isWaterSurfaceDecoration(st)) {
                BlockPos below = p.down();
                if (isWaterLike(world.getBlockState(below))) return true;
                continue;
            }

            if (isSkippableTop(st, p)) continue;

            break;
        }

        for (int dy = -2; dy <= 2; dy++) {
            int yy = yGuess + dy;
            if (yy < 1 || yy > 255) continue;
            p.setPos(x, yy, z);

            IBlockState st = world.getBlockState(p);
            if (isWaterLike(st)) return true;

            if (isWaterSurfaceDecoration(st)) {
                BlockPos below = p.down();
                if (isWaterLike(world.getBlockState(below))) return true;
            }
        }

        return false;
    }

    private static boolean isNaturalGround(IBlockState st) {
        return MPBGround.isRoadReplaceableGround(st);
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static boolean isIceBlock(IBlockState st) {
        if (st == null) return false;
        Block b = st.getBlock();
        return b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.FROSTED_ICE || st.getMaterial() == Material.ICE;
    }

    private static boolean isWaterLike(IBlockState st) {
        if (st == null) return false;
        Material m = st.getMaterial();
        return m == Material.WATER || isIceBlock(st);
    }
    private boolean isInBuildingNoPaintZone(int x, int z) {
        return protectedAreas != null && protectedAreas.contains(x, z);
    }

    private boolean isNearBuildingNoPaintZone(int x, int z, int r) {
        if (protectedAreas == null) return false;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (protectedAreas.contains(x + dx, z + dz)) return true;
            }
        }
        return false;
    }

    private boolean looksLikeBuildingColumn(int x, int surfaceY, int z, int r) {
        // Minimal but robust: tiles/manmade solids in a small neighborhood.
        // Used only as a last-resort painting guard (optional).
        int hits = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= 4; dy++) {
                    int yy = surfaceY + dy;
                    if (yy < 1 || yy > 255) continue;
                    p.setPos(x + dx, yy, z + dz);

                    if (!world.isBlockLoaded(p)) continue;

                    if (world.getTileEntity(p) != null) return true;

                    IBlockState st = world.getBlockState(p);
                    if (st == null) continue;

                    Material m = st.getMaterial();
                    if (!m.isSolid()) continue;
                    if (m == Material.LEAVES) continue;
                    if (m == Material.PLANTS || m == Material.VINE) continue;
                    if (m == Material.WATER) continue;

                    if (MPBGround.isRoadReplaceableGround(st)) continue;
                    if (MPBMillPathPalette.isMillPath(st.getBlock())) continue;

                    // Ignore natural trees (we can clear them).
                    if (MPBConfig.PAINTING.clearTrees && MPBConfig.PAINTING.clearTreeTrunks) {
                        if (isLikelyTreeWood(st, p) || st.getBlock().isWood(world, p) || st.getMaterial() == Material.WOOD) {
                            continue;
                        }
                    }

                    hits++;
                    if (hits >= 3) return true;
                }
            }
        }
        return false;
    }
}
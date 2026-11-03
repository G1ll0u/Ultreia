package com.jubitus.ultreia.terrain;

import com.jubitus.ultreia.chunk.ChunkManager;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.roads.RoadIndex;
import com.jubitus.ultreia.route.TerrainCost;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.BiomeDictionary;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;

/** Terrain samples for A* and painting (surface, water, blocked reasons), cached per column. */
public class TerrainSampler implements TerrainView {

    // Building guard: a paintable cell this close to a no-paint zone is refused if it looks built (radius rr).
    private static final int BUILDING_GUARD_NEAR_RADIUS = 3;
    private static final int BUILDING_GUARD_RADIUS = 1;

    public enum Mode { PATHFINDING, PAINTING }
    public enum ForbiddenReason { NONE, OCEAN, BLACKLIST, WATER, BLOCKED }

    private static final int MAX_SAMPLE_CACHE  = 500_000;
    private static final int MAX_LIGHT_CACHE   = 400_000;

    // Light-sample packed int: bits 0-8 = surfaceY (0-255+), bit 9 = isWater, bit 10 = isOcean,
    // bits 11-12 = surface hardness category (0=soft/dirt, 1=medium/sandstone, 2=hard/stone, 3=veryhard).
    // Integer.MIN_VALUE = not loaded / not in cache.
    public static final int LIGHT_NOT_LOADED    = Integer.MIN_VALUE;
    public static final int LIGHT_SURFACE_MASK  = 0x1FF;
    public static final int LIGHT_WATER_BIT     = 1 << 9;
    public static final int LIGHT_OCEAN_BIT     = 1 << 10;
    public static final int LIGHT_HARDNESS_SHIFT = 11;
    public static final int LIGHT_HARDNESS_MASK  = 0x3;

    public static int packLight(int surfaceY, boolean water, boolean ocean, int hardnessCategory) {
        return (ocean ? LIGHT_OCEAN_BIT : 0) | (water ? LIGHT_WATER_BIT : 0)
                | (surfaceY & LIGHT_SURFACE_MASK) | (hardnessCategory << LIGHT_HARDNESS_SHIFT);
    }

    public static int lightSurfaceY(int light) { return light & LIGHT_SURFACE_MASK; }

    public static boolean lightIsWater(int light) { return (light & LIGHT_WATER_BIT) != 0; }

    public static boolean lightIsOcean(int light) { return (light & LIGHT_OCEAN_BIT) != 0; }

    public static int lightHardness(int light) { return (light >> LIGHT_HARDNESS_SHIFT) & LIGHT_HARDNESS_MASK; }

    public enum BlockedDetail {
        NONE,
        NO_PAINT_ZONE,
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
        /**
         * True when a tree trunk/branch is present near this cell.
         * For DynamicTrees, this includes trunks within dtTreeProximityCheckRadius blocks.
         */
        public final boolean hasTreeTrunk;
        /**
         * DynamicTrees branch radius of the largest trunk found near this cell (1-8).
         * 0 = no DT trunk detected. 9 = vanilla/unknown tree.
         * Combined with hasTreeTrunk to scale the A* avoidance penalty.
         */
        public final int trunkRadius;
        /** True when the surface block at this cell is farmland (vanilla or modded). */
        public final boolean hasFarmland;
        /** Surface block hardness category: 0=soft (dirt/snow), 1=medium (sandstone), 2=hard (stone), 3=veryhard. */
        public final int surfaceHardnessCategory;

        public final BlockedDetail blockedPathDetail;
        public final BlockedDetail blockedPaintDetail;

        public Sample(int x, int z, int surfaceY, boolean isWater, boolean isOcean, boolean isBlacklisted,
                      boolean blockedPath, boolean blockedPaint, boolean smallWater, boolean nearWaterLike,
                      boolean hasTreeTrunk, int trunkRadius, boolean hasFarmland, int surfaceHardnessCategory,
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
            this.trunkRadius = trunkRadius;
            this.hasFarmland = hasFarmland;
            this.surfaceHardnessCategory = surfaceHardnessCategory;
            this.blockedPathDetail = blockedPathDetail;
            this.blockedPaintDetail = blockedPaintDetail;
            this.biome = biome;
        }
    }

    private final WorldServer world;
    private final Long2ObjectOpenHashMap<Sample> cache = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap lightCache = new Long2IntOpenHashMap();
    private final Set<ResourceLocation> blacklist = new HashSet<>();
    private ProtectedAreas protectedAreas;

    private ChunkManager chunkManager;
    private RoadIndex roadIndex;

    private final Long2IntOpenHashMap roadDistCache = new Long2IntOpenHashMap();
    {
        roadDistCache.defaultReturnValue(Integer.MIN_VALUE);
    }

    public TerrainSampler(WorldServer world) {
        this.world = world;
        lightCache.defaultReturnValue(LIGHT_NOT_LOADED);
        for (String s : UltreiaConfig.GENERAL.biomeBlacklist) {
            if (s == null || s.trim().isEmpty()) continue;
            blacklist.add(new ResourceLocation(s.trim()));
        }
    }

    public void setProtectedAreas(ProtectedAreas areas) {
        this.protectedAreas = areas;
    }

    public void setChunkManager(ChunkManager mgr) {
        this.chunkManager = mgr;
    }

    public void setRoadIndex(RoadIndex idx) {
        this.roadIndex = idx;
        roadDistCache.clear();
    }

    @Nullable
    public ProtectedAreas protectedAreas() {
        return protectedAreas;
    }

    @Nullable
    public RoadIndex roadIndex() {
        return roadIndex;
    }

    /** Cached manhattan distance to nearest road, or Integer.MAX_VALUE if none within maxRadius. */
    public int nearestRoadDistManhattan(int x, int z, int maxRadius, int scanStep) {
        if (roadIndex == null) return Integer.MAX_VALUE;
        long k = Pos2D.xzKey(x, z);
        int cached = roadDistCache.get(k);
        if (cached != Integer.MIN_VALUE) return cached;
        int dist = roadIndex.approxNearestRoadDistManhattan(x, z, maxRadius, scanStep);
        roadDistCache.put(k, dist);
        return dist;
    }

    public void clearCache() {
        cache.clear();
        lightCache.clear();
        roadDistCache.clear();
    }

    public void invalidateAround(int x, int z, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                long k = Pos2D.xzKey(x + dx, z + dz);
                cache.remove(k);
                lightCache.remove(k);
                roadDistCache.remove(k);
            }
        }
    }

    public Sample sample(int x, int z) {
        long k = Pos2D.xzKey(x, z);
        Sample cached = cache.get(k);
        if (cached != null) return cached;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, 0, z);

        if (!world.isBlockLoaded(pos)) {
            if (UltreiaConfig.GENERAL.allowChunkLoading && chunkManager != null) {
                if (!chunkManager.ensureLoaded(world, x, z)) {
                    Sample out = new Sample(x, z, 1, false, false, false, true, true, false, false, false, 0, false, 0,
                            BlockedDetail.NON_WALKABLE_GROUND, BlockedDetail.NON_PAINTABLE_GROUND, world.getBiome(pos));
                    return cachePut(k, out);
                }
            } else {
                Sample out = new Sample(x, z, 1, false, false, false, true, true, false, false, false, 0, false, 0,
                        BlockedDetail.NON_WALKABLE_GROUND, BlockedDetail.NON_PAINTABLE_GROUND, world.getBiome(pos));
                return cachePut(k, out);
            }
        }

        Biome biome = world.getBiome(pos);
        ResourceLocation biomeId = biome.getRegistryName();
        boolean isBlacklisted = biomeId != null && blacklist.contains(biomeId);

        boolean isOcean = false;
        if (UltreiaConfig.GENERAL.forbidOcean) {
            isOcean = BiomeDictionary.hasType(biome, BiomeDictionary.Type.OCEAN)
                    || (biomeId != null && biomeId.getPath().contains("ocean"));
        }

        int column = ColumnSurface.scan(world, pos, x, z);
        boolean isWater = ColumnSurface.isWater(column);
        int surfaceY = ColumnSurface.y(column);

        boolean inNoPaint = isProtectedNoPaint(x, z);
        boolean hasMillPathCol = (!isWater) && ColumnSurface.findPathY(world, new BlockPos.MutableBlockPos(), x, z, surfaceY, false) != ColumnSurface.NO_PATH;

        boolean blockedPath = false;
        boolean blockedPaint = false;
        BlockedDetail blockedPathDetail = BlockedDetail.NONE;
        BlockedDetail blockedPaintDetail = BlockedDetail.NONE;

        if (!isWater) {
            if (inNoPaint) {
                blockedPaint = true;
                blockedPaintDetail = BlockedDetail.NO_PAINT_ZONE;

                // An existing path through a footprint stays usable.
                blockedPath = !hasMillPathCol;
                if (blockedPath) blockedPathDetail = BlockedDetail.NO_PAINT_ZONE;
            } else {
                BlockPos.MutableBlockPos pos2 = new BlockPos.MutableBlockPos(x, surfaceY, z);
                IBlockState ground = world.getBlockState(pos2);

                // Mill path blocks are valid painting surfaces (we paint onto/alongside them).
                boolean paintable = BlockRules.isRoadReplaceableGround(ground);
                boolean walkable = ground.getMaterial().isSolid() && ground.getMaterial() != Material.LEAVES;

                BlockPos.MutableBlockPos up1 = new BlockPos.MutableBlockPos(x, surfaceY + 1, z);
                BlockPos.MutableBlockPos up2 = new BlockPos.MutableBlockPos(x, surfaceY + 2, z);

                boolean headroom = BlockRules.isClearableOrAir(world, up1, world.getBlockState(up1))
                        && BlockRules.isClearableOrAir(world, up2, world.getBlockState(up2));

                boolean manmade = BuildingGuard.hasManmadeNearby(world, x, surfaceY, z);
                boolean tile = BuildingGuard.hasTileEntityNearby(world, x, surfaceY, z);

                blockedPath = !walkable || !headroom || tile;
                if (!walkable) blockedPathDetail = BlockedDetail.NON_WALKABLE_GROUND;
                else if (!headroom) blockedPathDetail = BlockedDetail.BAD_HEADROOM;
                else if (tile) blockedPathDetail = BlockedDetail.TILE_NEARBY;

                blockedPaint = !paintable || !headroom || manmade || tile;
                if (!paintable) blockedPaintDetail = BlockedDetail.NON_PAINTABLE_GROUND;
                else if (!headroom) blockedPaintDetail = BlockedDetail.BAD_HEADROOM;
                else if (manmade) blockedPaintDetail = BlockedDetail.MANMADE_NEARBY;
                else if (tile) blockedPaintDetail = BlockedDetail.TILE_NEARBY;

                if (!blockedPaint && protectedAreas != null
                        && protectedAreas.containsWithin(x, z, BUILDING_GUARD_NEAR_RADIUS)
                        && BuildingGuard.looksLikeBuildingColumn(world, x, surfaceY, z, BUILDING_GUARD_RADIUS)) {
                    blockedPaint = true;
                    blockedPaintDetail = BlockedDetail.BUILDING_GUARD;
                }
            }
        }

        boolean smallWater = false;
        if (isWater && !isOcean) {
            smallWater = WaterProbe.isSmallWaterPatch(world, x, surfaceY, z);
        }

        boolean nearWaterLike = false;
        if (!isWater) {
            nearWaterLike = WaterProbe.isNearWater(world, chunkManager, x, z, surfaceY);
        }

        boolean hasTreeTrunk = false;
        int trunkRadius = 0;
        if (!isWater && UltreiaConfig.PAINTING.clearTrees) {
            trunkRadius = TreeProbe.detectTrunkRadius(world, x, surfaceY, z);
            hasTreeTrunk = trunkRadius > 0;
        }

        // Farmland detection: check the surface block (crops are skipped by the surface scan,
        // so surfaceY lands on the farmland itself).
        boolean hasFarmland = false;
        int surfaceHardnessCategory = 0;
        if (!isWater) {
            BlockPos.MutableBlockPos fp = new BlockPos.MutableBlockPos(x, surfaceY, z);
            IBlockState fst = world.getBlockState(fp);
            hasFarmland = BlockRules.isFarmland(fst);
            try {
                float h = fst.getBlockHardness(world, fp);
                surfaceHardnessCategory = hardnessCategoryFromFloat(h);
            } catch (Throwable ignored) {}
        }

        Sample out = new Sample(x, z, surfaceY, isWater, isOcean, isBlacklisted,
                blockedPath, blockedPaint, smallWater, nearWaterLike, hasTreeTrunk, trunkRadius, hasFarmland,
                surfaceHardnessCategory, blockedPathDetail, blockedPaintDetail, biome);
        return cachePut(k, out);
    }

    public boolean forbidden(Sample s, Mode mode) {
        if (UltreiaConfig.GENERAL.forbidOcean && s.isOcean) return true;
        if (UltreiaConfig.GENERAL.hardBlockBlacklistedBiomes && s.isBlacklisted) return true;

        if (mode == Mode.PATHFINDING && s.isWater) return true;

        return mode == Mode.PAINTING ? s.blockedPaint : s.blockedPath;
    }

    public ForbiddenReason forbiddenReason(Sample s, Mode mode) {
        if (UltreiaConfig.GENERAL.forbidOcean && s.isOcean) return ForbiddenReason.OCEAN;
        if (UltreiaConfig.GENERAL.hardBlockBlacklistedBiomes && s.isBlacklisted) return ForbiddenReason.BLACKLIST;
        if (mode == Mode.PATHFINDING && s.isWater) return ForbiddenReason.WATER;

        if (mode == Mode.PAINTING ? s.blockedPaint : s.blockedPath) return ForbiddenReason.BLOCKED;
        return ForbiddenReason.NONE;
    }

    public String describeForbidden(Sample s, Mode mode) {
        ForbiddenReason r = forbiddenReason(s, mode);
        if (r == ForbiddenReason.OCEAN) return "ocean biome";
        if (r == ForbiddenReason.BLACKLIST) return "blacklisted biome";
        if (r == ForbiddenReason.WATER) return "water requires explicit bridge";
        if (r == ForbiddenReason.BLOCKED) {
            BlockedDetail d = mode == Mode.PAINTING ? s.blockedPaintDetail : s.blockedPathDetail;
            return "blocked:" + d;
        }
        return "none";
    }

    public boolean forbiddenHard(Sample s) {
        if (UltreiaConfig.GENERAL.forbidOcean && s.isOcean) return true;
        return UltreiaConfig.GENERAL.hardBlockBlacklistedBiomes && s.isBlacklisted;
    }

    public boolean isProtectedNoPaint(int x, int z) {
        return protectedAreas != null && protectedAreas.contains(x, z);
    }

    public boolean isExistingRoadXZ(int x, int z) {
        return roadIndex != null && roadIndex.isRoadXZ(x, z);
    }

    @Nullable
    public Sample sampleIfLoaded(int x, int z) {
        long k = Pos2D.xzKey(x, z);
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
        long k = Pos2D.xzKey(x, z);

        // Promote from full cache if available (free).
        Sample full = cache.get(k);
        if (full != null) {
            return packLight(full.surfaceY, full.isWater, full.isOcean, full.surfaceHardnessCategory);
        }

        // Check light cache.
        int lv = lightCache.get(k);
        if (lv != LIGHT_NOT_LOADED) return lv;

        // Chunk must already be loaded — never force-load for lateral probes.
        ChunkProviderServer cps = (ChunkProviderServer) world.getChunkProvider();
        if (cps.getLoadedChunk(x >> 4, z >> 4) == null) return LIGHT_NOT_LOADED;

        // Surface only: no paint sub-checks.
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, 0, z);
        int column = ColumnSurface.scan(world, pos, x, z);
        boolean isWater = ColumnSurface.isWater(column);
        int y = ColumnSurface.y(column);

        boolean isOcean = false;
        if (UltreiaConfig.GENERAL.forbidOcean && isWater) {
            Biome biome = world.getBiome(pos.setPos(x, 0, z));
            ResourceLocation biomeId = biome.getRegistryName();
            isOcean = BiomeDictionary.hasType(biome, BiomeDictionary.Type.OCEAN)
                    || (biomeId != null && biomeId.getPath().contains("ocean"));
        }

        int hardnessCat = 0;
        if (!isWater) {
            try {
                IBlockState surfSt = world.getBlockState(pos.setPos(x, y, z));
                float h = surfSt.getBlockHardness(world, pos);
                hardnessCat = hardnessCategoryFromFloat(h);
            } catch (Throwable ignored) {}
        }

        int packed = packLight(y, isWater, isOcean, hardnessCat);
        if (lightCache.size() >= MAX_LIGHT_CACHE) lightCache.clear();
        lightCache.put(k, packed);
        return packed;
    }

    private static int hardnessCategoryFromFloat(float h) {
        if (h < 0) return 3;     // unbreakable
        if (h <= 0.6f) return 0; // soft: dirt, grass, sand, snow, gravel, clay
        if (h <= 1.1f) return 1; // medium: sandstone, etc.
        if (h <= 2.5f) return 2; // hard: stone, cobblestone, granite
        return 3;                // very hard: obsidian, modded hard blocks
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
        return BlockRules.isRoadReplaceableGround(st);
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
        return TerrainCost.extraCost(this, s, mode);
    }
}

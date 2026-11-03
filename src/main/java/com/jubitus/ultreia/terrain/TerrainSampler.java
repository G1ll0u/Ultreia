package com.jubitus.ultreia.terrain;

import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.chunk.ChunkManager;
import com.jubitus.ultreia.config.UltreiaConfig;
import com.jubitus.ultreia.mill.MillPathPalette;
import com.jubitus.ultreia.roads.RoadIndex;
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

public class TerrainSampler implements TerrainView {

    // If true, roads will avoid hugging rivers/lakes when not bridging.
    private static final boolean WATER_AVOID_ENABLED = true;
    // Manhattan radius from any water/ice that roads must avoid when on land.
    private static final int BUFFER_RADIUS = 1;
    // Additional cost if stepping on water (non-ocean).
    private static final double WATER_PENALTY = 24.0;
    // Extra cost when a tree trunk/branch is in the headroom above a grid cell.
    private static final double TREE_PENALTY = 15.0;
    // Extra pathfinding cost when a cell contains farmland (or modded equivalent).
    private static final double FARMLAND_PENALTY = 4000.0;
    // DynamicTrees branch radius (1-8) above which a tree is considered 'large'.
    private static final int DT_LARGE_TREE_RADIUS_THRESHOLD = 3;
    // Multiplier applied to treePenalty when a large DynamicTrees trunk is nearby.
    private static final double DT_LARGE_TREE_PENALTY_MULTIPLIER = 6.0;
    // Horizontal scan radius (blocks) used to detect DynamicTrees trunks near a path cell.
    private static final int DT_TREE_PROXIMITY_CHECK_RADIUS = 2;
    // Additional cost if stepping in a blacklisted biome when hardBlockBlacklistedBiomes=false.
    private static final double BLACKLISTED_BIOME_PENALTY = 5000.0;
    // If true, pathfinding tries to avoid protected zones (village/lone building footprints).
    private static final boolean AVOID_PROTECTED_ZONES = true;
    // Extra penalty for crossing small water patches (puddles/marsh).
    private static final double SMALL_WATER_PENALTY = 260.0;
    // Radius (blocks) used to classify a water cell as 'small water patch'.
    private static final int SMALL_WATER_SCAN_RADIUS = 4;
    // If the number of nearby water cells in scan radius is <= this, it's considered 'small water'.
    private static final int SMALL_WATER_MAX_CELLS = 22;
    private static final boolean BUILDINGS_ENABLED = true;
    private static final int FOOTPRINT_PADDING = 1;
    private static final boolean ADD_PENALTY_RING = true;
    private static final int PENALTY_RING_RADIUS = 1;
    private static final double PENALTY_RING_COST = 15000.0;
    private static final boolean ALLOW_EXISTING_ROAD_THROUGH_BUILDINGS = true;
    private static final boolean EXTRA_PAINTING_GUARD = true;
    private static final int EXTRA_PAINTING_GUARD_RADIUS = 1;
    // Cost added when entering an XZ column that belongs to an existing bridge volume (deck + supports + lateral...
    private static final double EXISTING_BRIDGE_VOLUME_AVOIDANCE_COST = 700.0;

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

        int topY = world.getHeight(pos).getY() - 1;
        if (topY < 1) topY = 1;

        boolean isWater = false;
        int y = topY;

        for (int i = 0; i < 256 && y > 1; i++, y--) {
            pos.setPos(x, y, z);
            IBlockState st = world.getBlockState(pos);
            Material m = st.getMaterial();

            if (m == Material.AIR) continue;

            if (BlockRules.isWaterLike(st)) {
                isWater = true;
                break;
            }

            if (BlockRules.isWaterSurfaceDecoration(st)) {
                BlockPos below = pos.down();
                IBlockState belowSt = world.getBlockState(below);
                if (BlockRules.isWaterLike(belowSt)) {
                    isWater = true;
                    y = below.getY();
                    break;
                }
                continue;
            }

            if (BlockRules.isSoftClearable(world, pos, st)) continue;

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

                if (BUILDINGS_ENABLED && UltreiaConfig.BUILDINGS.hardBlockInPathfinding) {
                    if (ALLOW_EXISTING_ROAD_THROUGH_BUILDINGS && hasMillPathCol) {
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
                boolean paintable = BlockRules.isRoadReplaceableGround(ground);
                boolean walkable = ground.getMaterial().isSolid() && ground.getMaterial() != Material.LEAVES;

                BlockPos.MutableBlockPos up1 = new BlockPos.MutableBlockPos(x, surfaceY + 1, z);
                BlockPos.MutableBlockPos up2 = new BlockPos.MutableBlockPos(x, surfaceY + 2, z);

                boolean headroom = BlockRules.isClearableOrAir(world, up1, world.getBlockState(up1))
                        && BlockRules.isClearableOrAir(world, up2, world.getBlockState(up2));
                boolean headroomPath = headroom;
                boolean headroomPaint = headroom;

                boolean manmade = hasManmadeNearby(x, surfaceY, z);
                boolean tile = hasTileEntityNearby(x, surfaceY, z);

                blockedPath = !walkable || !headroomPath || tile;
                if (!walkable) blockedPathDetail = BlockedDetail.NON_WALKABLE_GROUND;
                else if (!headroomPath) blockedPathDetail = BlockedDetail.BAD_HEADROOM;
                else if (tile) blockedPathDetail = BlockedDetail.TILE_NEARBY;

                blockedPaint = !paintable || !headroomPaint || manmade || tile;
                if (!paintable) blockedPaintDetail = BlockedDetail.NON_PAINTABLE_GROUND;
                else if (!headroomPaint) blockedPaintDetail = BlockedDetail.BAD_HEADROOM;
                else if (manmade) blockedPaintDetail = BlockedDetail.MANMADE_NEARBY;
                else if (tile) blockedPaintDetail = BlockedDetail.TILE_NEARBY;

                if (!blockedPaint && BUILDINGS_ENABLED && EXTRA_PAINTING_GUARD) {
                    int rr = Math.max(1, EXTRA_PAINTING_GUARD_RADIUS);
                    int nearR = Math.max(2, FOOTPRINT_PADDING + 2);
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
        if (!isWater && WATER_AVOID_ENABLED) {
            int r = Math.max(1, BUFFER_RADIUS);
            nearWaterLike = hasWaterLikeInManhattanRadius(x, z, surfaceY, r);
        }

        boolean hasTreeTrunk = false;
        int trunkRadius = 0;
        if (!isWater && UltreiaConfig.PAINTING.clearTrees) {
            trunkRadius = detectTrunkRadius(x, surfaceY, z);
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
            if (BlockRules.isWaterLike(st)) { isWater = true; break; }
            if (BlockRules.isWaterSurfaceDecoration(st)) {
                IBlockState below = world.getBlockState(pos.down());
                if (BlockRules.isWaterLike(below)) { isWater = true; y = y - 1; break; }
                continue;
            }
            if (BlockRules.isSoftClearable(world, pos, st)) continue;
            break;
        }
        if (y < 1) y = 1;

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
                // pos still points to (x, y, z) = the surface block
                IBlockState surfSt = world.getBlockState(pos);
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
        double cost = 0.0;
        if (s.isWater && !s.isOcean) {
            cost += WATER_PENALTY;
            if (s.smallWater) cost += SMALL_WATER_PENALTY;
        }
        if (s.isBlacklisted && !UltreiaConfig.GENERAL.hardBlockBlacklistedBiomes) cost += BLACKLISTED_BIOME_PENALTY;

        if (mode == Mode.PATHFINDING && BUILDINGS_ENABLED && ADD_PENALTY_RING && protectedAreas != null) {
            int ring = Math.max(0, PENALTY_RING_RADIUS);
            if (ring > 0 && !protectedAreas.contains(s.x, s.z)) {
                if (isNearBuildingNoPaintZone(s.x, s.z, ring)) {
                    cost += Math.max(0.0, PENALTY_RING_COST);
                }
            }
        }

        if (mode == Mode.PATHFINDING && AVOID_PROTECTED_ZONES && protectedAreas != null) {
            cost += (double) protectedAreas.penaltyAt(s.x, s.z);
        }

        if (mode == Mode.PATHFINDING && roadIndex != null && roadIndex.isBridgeVolumeXZ(s.x, s.z)) {
            cost += Math.max(0.0, EXISTING_BRIDGE_VOLUME_AVOIDANCE_COST);
        }

        if (mode == Mode.PATHFINDING && roadIndex != null) {
            boolean onRoad = roadIndex.isRoadXZ(s.x, s.z);
            if (onRoad && UltreiaConfig.PATHFINDING.preferExistingRoads) {
                cost -= Math.max(0.0, UltreiaConfig.PATHFINDING.existingRoadBonus);
            } else if (!onRoad && UltreiaConfig.PATHFINDING.avoidParallelToExistingRoads) {
                int maxR = Math.max(1, UltreiaConfig.PATHFINDING.parallelRoadAvoidRadius);
                int step = Math.max(1, UltreiaConfig.PATHFINDING.parallelRoadAvoidScanStep);

                long kk = Pos2D.xzKey(s.x, s.z);
                int dist = roadDistCache.get(kk);

                if (dist == Integer.MIN_VALUE) {
                    dist = roadIndex.approxNearestRoadDistManhattan(s.x, s.z, maxR, step);
                    roadDistCache.put(kk, dist);
                }

                if (dist > 1 && dist != Integer.MAX_VALUE) {
                    double t = (double) (maxR - dist + 1);
                    if (t > 0.0) {
                        double base = UltreiaConfig.PATHFINDING.parallelRoadPenalty;
                        double p = UltreiaConfig.PATHFINDING.parallelRoadPenaltyPower;
                        if (p < 1.0) p = 1.0;
                        cost += base * Math.pow(t, p);
                    }
                }
            }
        }
        if (WATER_AVOID_ENABLED && !s.isWater && s.nearWaterLike) {
            int r = Math.max(1, BUFFER_RADIUS);
            cost += 35.0 * (double) r;
        }
        if (mode == Mode.PATHFINDING && s.hasTreeTrunk && UltreiaConfig.PAINTING.clearTrees) {
            double mult = treePenaltyMultiplier(s.trunkRadius);
            cost += TREE_PENALTY * mult;
        }

        if (mode == Mode.PATHFINDING && s.hasFarmland) {
            cost += FARMLAND_PENALTY;
        }

        return cost;
    }

    private boolean hasTileEntityNearby(int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos p = new BlockPos(x + dx, y + dy, z + dz);
                    if (world.getTileEntity(p) == null) continue;
                    // DT blocks (saplings, rootydirt) carry TEs for species metadata only —
                    // they are safe to clear and must not trigger path/paint blocking.
                    IBlockState st = world.getBlockState(p);
                    if (TreeCompat.isDynamicTreesBlock(st)) continue;
                    return true;
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
        // Mill/Ultreia path blocks are road infrastructure, not obstacles to paint around.
        if (MillPathPalette.isMillPath(b)) return false;
        return !BlockRules.isRoadReplaceableGround(st);
    }

    /**
     * Detects the "effective trunk radius" at (x, surfaceY, z).
     *
     * Steps:
     * 1. Check directly above (surfaceY+1 to +4) for DT branches or vanilla trunks.
     * 2. If rootydirt is AT surfaceY, read the trunk radius from surfaceY+1.
     * 3. Proximity scan: within dtTreeProximityCheckRadius blocks check for large DT trunks.
     *    This ensures the cost is applied even when the path is NEXT TO a thick trunk,
     *    covering the full road width.
     *
     * Returns 0 if no trunk found, 1-8 for DT radius, 9 for vanilla/unknown trunk.
     */
    private int detectTrunkRadius(int x, int surfaceY, int z) {
        BlockPos.MutableBlockPos tp = new BlockPos.MutableBlockPos();
        int best = 0;

        // --- Direct column scan above this cell ---
        for (int dy = 1; dy <= 4 && best == 0; dy++) {
            int yy = surfaceY + dy;
            if (yy > 255) break;
            tp.setPos(x, yy, z);
            IBlockState tst = world.getBlockState(tp);
            if (tst == null) continue;
            int r = probeBlockTrunkRadius(tst, tp);
            // DT saplings (Tier-1) are not branches — treat as radius-1 obstacle so A* prefers routing around them.
            if (r == 0 && TreeCompat.isDTSapling(tst)) r = 1;
            if (r > best) best = r;
        }

        // --- Check if rootydirt is at the surface (tree planted exactly here) ---
        if (best == 0) {
            tp.setPos(x, surfaceY, z);
            IBlockState surfSt = world.getBlockState(tp);
            if (TreeCompat.isDTRootyBlock(surfSt)) {
                // Trunk starts one block above the rootydirt
                tp.setPos(x, surfaceY + 1, z);
                IBlockState trunkSt = world.getBlockState(tp);
                int r = probeBlockTrunkRadius(trunkSt, tp);
                best = (r > 0) ? r : 1;
            }
        }

        // --- Proximity scan for large DT trunks nearby ---
        // If a thick trunk is within the road footprint, apply the penalty to this centerline cell.
        int proximityR = DT_TREE_PROXIMITY_CHECK_RADIUS;
        if (proximityR > 0) {
            int largeThreshold = DT_LARGE_TREE_RADIUS_THRESHOLD;
            outer:
            for (int dx = -proximityR; dx <= proximityR; dx++) {
                for (int dz = -proximityR; dz <= proximityR; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    int nx = x + dx;
                    int nz = z + dz;
                    for (int dy = 1; dy <= 6; dy++) {
                        int yy = surfaceY + dy;
                        if (yy > 255) break;
                        tp.setPos(nx, yy, nz);
                        IBlockState tst = world.getBlockState(tp);
                        if (tst == null || tst.getMaterial() == net.minecraft.block.material.Material.AIR) continue;
                        if (TreeCompat.isDynamicTreesBranch(tst)) {
                            int r = TreeCompat.getDTBranchRadius(tst);
                            if (r >= largeThreshold && r > best) {
                                best = r;
                                break outer; // Found a large trunk nearby — no need to keep scanning
                            }
                        }
                    }
                }
            }
        }

        return best;
    }

    /** Returns the trunk radius for a single block: 1-8 for DT branches, 9 for vanilla wood, 0 otherwise. */
    private int probeBlockTrunkRadius(IBlockState st, BlockPos pos) {
        if (st == null) return 0;
        if (TreeCompat.isDynamicTreesBranch(st)) {
            int r = TreeCompat.getDTBranchRadius(st);
            return (r > 0) ? r : 1;
        }
        if (TreeCompat.isDynamicTreesTrunkShell(st)) return 4; // Trunk shell = thick trunk
        if (BlockRules.isTreeWood(world, pos, st)) {
            return 9; // Vanilla/unknown tree
        }
        return 0;
    }

    /**
     * Returns the A* cost multiplier for a tree based on its trunk radius.
     * DT large trees (radius >= threshold) get a configurable multiplier.
     * Vanilla trees (radius 9) get base 1.0 — the treePenalty is already calibrated for them.
     */
    private static double treePenaltyMultiplier(int radius) {
        if (radius <= 0) return 1.0;
        if (radius == 9) return 1.0; // vanilla tree
        int threshold = DT_LARGE_TREE_RADIUS_THRESHOLD;
        if (radius >= threshold) {
            return DT_LARGE_TREE_PENALTY_MULTIPLIER;
        }
        return 1.0;
    }

    private boolean isSmallWaterPatch(int x, int surfaceY, int z) {
        int r = Math.max(1, SMALL_WATER_SCAN_RADIUS);
        int maxCells = Math.max(0, SMALL_WATER_MAX_CELLS);

        int water = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                p.setPos(x + dx, surfaceY, z + dz);

                if (!world.isBlockLoaded(p)) continue;
                IBlockState st = world.getBlockState(p);
                if (BlockRules.isWaterLike(st)) {
                    water++;
                    if (water > maxCells) return false;
                }
            }
        }
        return water > 0 && water <= maxCells;
    }

    private boolean columnHasMillPath(int x, int surfaceY, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = -2; dy <= 4; dy++) {
            int yy = surfaceY + dy;
            if (yy < 1 || yy > 255) continue;
            p.setPos(x, yy, z);
            IBlockState st = world.getBlockState(p);
            if (st != null && MillPathPalette.isMillPath(st.getBlock())) return true;
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
            if (UltreiaConfig.GENERAL.allowChunkLoading && chunkManager != null) {
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
            if (BlockRules.isWaterLike(st)) return true;

            if (BlockRules.isWaterSurfaceDecoration(st)) {
                BlockPos below = p.down();
                if (BlockRules.isWaterLike(world.getBlockState(below))) return true;
                continue;
            }

            if (BlockRules.isSoftClearable(world, p, st)) continue;

            break;
        }

        for (int dy = -2; dy <= 2; dy++) {
            int yy = yGuess + dy;
            if (yy < 1 || yy > 255) continue;
            p.setPos(x, yy, z);

            IBlockState st = world.getBlockState(p);
            if (BlockRules.isWaterLike(st)) return true;

            if (BlockRules.isWaterSurfaceDecoration(st)) {
                BlockPos below = p.down();
                if (BlockRules.isWaterLike(world.getBlockState(below))) return true;
            }
        }

        return false;
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

                    if (BlockRules.isRoadReplaceableGround(st)) continue;

                    // Ignore tree trunks (we can clear them); planks and fences count as building.
                    if (BlockRules.treesClearable() && BlockRules.isTreeWood(world, p, st)) continue;

                    hits++;
                    if (hits >= 3) return true;
                }
            }
        }
        return false;
    }
}
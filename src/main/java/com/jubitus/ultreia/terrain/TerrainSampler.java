package com.jubitus.ultreia.terrain;

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

public class TerrainSampler {

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
        long k = key(x, z);
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

                if (UltreiaConfig.BUILDINGS.enabled && UltreiaConfig.BUILDINGS.hardBlockInPathfinding) {
                    if (UltreiaConfig.BUILDINGS.allowExistingRoadThroughBuildings && hasMillPathCol) {
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
                boolean paintable = isNaturalGround(ground) || MillPathPalette.isMillPath(ground.getBlock());
                boolean walkable = ground.getMaterial().isSolid() && ground.getMaterial() != Material.LEAVES;

                BlockPos.MutableBlockPos up1 = new BlockPos.MutableBlockPos(x, surfaceY + 1, z);
                BlockPos.MutableBlockPos up2 = new BlockPos.MutableBlockPos(x, surfaceY + 2, z);

                boolean headroomPath = isClearableOrAir(world.getBlockState(up1), up1, Mode.PATHFINDING)
                        && isClearableOrAir(world.getBlockState(up2), up2, Mode.PATHFINDING);
                boolean headroomPaint = isClearableOrAir(world.getBlockState(up1), up1, Mode.PAINTING)
                        && isClearableOrAir(world.getBlockState(up2), up2, Mode.PAINTING);

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

                if (!blockedPaint && UltreiaConfig.BUILDINGS.enabled && UltreiaConfig.BUILDINGS.extraPaintingGuard) {
                    int rr = Math.max(1, UltreiaConfig.BUILDINGS.extraPaintingGuardRadius);
                    int nearR = Math.max(2, UltreiaConfig.BUILDINGS.footprintPadding + 2);
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
        if (!isWater && UltreiaConfig.WATER_AVOID.enabled) {
            int r = Math.max(1, UltreiaConfig.WATER_AVOID.bufferRadius);
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
            hasFarmland = GroundRules.isFarmland(fst);
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
                    | (full.surfaceY & LIGHT_SURFACE_MASK)
                    | (full.surfaceHardnessCategory << LIGHT_HARDNESS_SHIFT);
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

        int packed = (isOcean ? LIGHT_OCEAN_BIT : 0) | (isWater ? LIGHT_WATER_BIT : 0) | (y & LIGHT_SURFACE_MASK)
                | (hardnessCat << LIGHT_HARDNESS_SHIFT);
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
        return GroundRules.isRoadReplaceableGround(st) || MillPathPalette.isMillPath(st.getBlock());
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
            cost += UltreiaConfig.PATHFINDING.waterPenalty;
            if (s.smallWater) cost += UltreiaConfig.PAINTING.smallWaterPenalty;
        }
        if (s.isBlacklisted && !UltreiaConfig.GENERAL.hardBlockBlacklistedBiomes) cost += UltreiaConfig.PATHFINDING.blacklistedBiomePenalty;

        if (mode == Mode.PATHFINDING && UltreiaConfig.BUILDINGS.enabled && UltreiaConfig.BUILDINGS.addPenaltyRing && protectedAreas != null) {
            int ring = Math.max(0, UltreiaConfig.BUILDINGS.penaltyRingRadius);
            if (ring > 0 && !protectedAreas.contains(s.x, s.z)) {
                if (isNearBuildingNoPaintZone(s.x, s.z, ring)) {
                    cost += Math.max(0.0, UltreiaConfig.BUILDINGS.penaltyRingCost);
                }
            }
        }

        if (mode == Mode.PATHFINDING && UltreiaConfig.PATHFINDING.avoidProtectedZones && protectedAreas != null) {
            cost += (double) protectedAreas.penaltyAt(s.x, s.z);
        }

        if (mode == Mode.PATHFINDING && roadIndex != null && roadIndex.isBridgeVolumeXZ(s.x, s.z)) {
            cost += Math.max(0.0, UltreiaConfig.BRIDGE_AWARE_PATHFINDING.existingBridgeVolumeAvoidanceCost);
        }

        if (mode == Mode.PATHFINDING && roadIndex != null) {
            boolean onRoad = roadIndex.isRoadXZ(s.x, s.z);
            if (onRoad && UltreiaConfig.PATHFINDING.preferExistingRoads) {
                cost -= Math.max(0.0, UltreiaConfig.PATHFINDING.existingRoadBonus);
            } else if (!onRoad && UltreiaConfig.PATHFINDING.avoidParallelToExistingRoads) {
                int maxR = Math.max(1, UltreiaConfig.PATHFINDING.parallelRoadAvoidRadius);
                int step = Math.max(1, UltreiaConfig.PATHFINDING.parallelRoadAvoidScanStep);

                long kk = key(s.x, s.z);
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
        if (UltreiaConfig.WATER_AVOID.enabled && !s.isWater && s.nearWaterLike) {
            int r = Math.max(1, UltreiaConfig.WATER_AVOID.bufferRadius);
            cost += 35.0 * (double) r;
        }
        if (mode == Mode.PATHFINDING && s.hasTreeTrunk && UltreiaConfig.PAINTING.clearTrees) {
            double mult = treePenaltyMultiplier(s.trunkRadius);
            cost += UltreiaConfig.PATHFINDING.treePenalty * mult;
        }

        if (mode == Mode.PATHFINDING && s.hasFarmland) {
            cost += UltreiaConfig.PATHFINDING.farmlandPenalty;
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
        return !isNaturalGround(st);
    }

    private boolean isSkippableTop(IBlockState st, BlockPos pos) {
        Material m = st.getMaterial();
        Block b = st.getBlock();
        // Lava is "replaceable" in vanilla: stop on it so the column reads as lava, not as the pool floor.
        if (m == Material.LAVA) return false;

        if (TreeCompat.isTreeFoliageLike(world, pos, st)) return true;
        if (m.isReplaceable()) return true;
        if (b.isReplaceable(world, pos)) return true;
        if (b == Blocks.SNOW_LAYER) return true;
        if (isWaterSurfaceDecoration(st)) return true;

        if (UltreiaConfig.PAINTING.clearTrees && UltreiaConfig.PAINTING.clearTreeTrunks) {
            if (TreeCompat.isTreeWoodLike(world, pos, st)) return true;
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
        int proximityR = UltreiaConfig.PATHFINDING.dtTreeProximityCheckRadius;
        if (proximityR > 0) {
            int largeThreshold = UltreiaConfig.PATHFINDING.dtLargeTreeRadiusThreshold;
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
        if (isLikelyTreeWood(st, pos) || st.getBlock().isWood(world, pos) || st.getMaterial() == Material.WOOD) {
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
        int threshold = UltreiaConfig.PATHFINDING.dtLargeTreeRadiusThreshold;
        if (radius >= threshold) {
            return UltreiaConfig.PATHFINDING.dtLargeTreePenaltyMultiplier;
        }
        return 1.0;
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
        int r = Math.max(1, UltreiaConfig.PAINTING.smallWaterScanRadius);
        int maxCells = Math.max(0, UltreiaConfig.PAINTING.smallWaterMaxCells);

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
        if (m == Material.LAVA) return false;

        if (TreeCompat.isTreeFoliageLike(world, pos, st)) return true;
        if (m.isReplaceable()) return true;
        if (b.isReplaceable(world, pos)) return true;
        if (b == Blocks.SNOW_LAYER) return true;

        if (UltreiaConfig.PAINTING.clearTrees && UltreiaConfig.PAINTING.clearTreeTrunks) {
            if (TreeCompat.isTreeWoodLike(world, pos, st)) return true;
            if (isLikelyTreeWood(st, pos)) return true;
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
        return GroundRules.isRoadReplaceableGround(st);
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

                    if (GroundRules.isRoadReplaceableGround(st)) continue;
                    if (MillPathPalette.isMillPath(st.getBlock())) continue;

                    // Ignore natural trees (we can clear them).
                    if (UltreiaConfig.PAINTING.clearTrees && UltreiaConfig.PAINTING.clearTreeTrunks) {
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
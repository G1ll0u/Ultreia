package com.jubitus.millpathbuilder.gen;

import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.mill.MPBMillPathPalette;
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
import net.minecraftforge.common.BiomeDictionary;

import java.util.HashSet;
import java.util.Set;
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

import java.util.HashSet;
import java.util.Set;

public final class TerrainSampler {

    public enum Mode { PATHFINDING, PAINTING }
    public enum ForbiddenReason { NONE, OCEAN, BLACKLIST, WATER_BUFFER, BLOCKED }

    private static final int MAX_SAMPLE_CACHE = 200_000;

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

        public Sample(int x, int z, int surfaceY, boolean isWater, boolean isOcean, boolean isBlacklisted,
                      boolean blockedPath, boolean blockedPaint, boolean smallWater, boolean nearWaterLike, Biome biome) {
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
            this.biome = biome;
        }
    }

    private final WorldServer world;
    private final Long2ObjectOpenHashMap<Sample> cache = new Long2ObjectOpenHashMap<>();
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
        roadDistCache.clear();
    }

    public void invalidate(int x, int z) {
        cache.remove(key(x, z));
        roadDistCache.remove(key(x, z));
    }

    public void invalidateAround(int x, int z, int r) {
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                long k = key(x + dx, z + dz);
                cache.remove(k);
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
                    Sample out = new Sample(x, z, 1, false, false, false, true, true, false, false, world.getBiome(pos));
                    return cachePut(k, out);
                }
            } else {
                Sample out = new Sample(x, z, 1, false, false, false, true, true, false, false, world.getBiome(pos));
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

            if (isSkippableTop(st, pos)) continue;

            break;
        }

        if (y < 1) y = 1;
        int surfaceY = y;

        boolean inNoPaint = isInBuildingNoPaintZone(x, z);
        boolean hasMillPathCol = (!isWater) && columnHasMillPath(x, surfaceY, z);

        boolean blockedPath = false;
        boolean blockedPaint = false;

        if (!isWater) {
            if (inNoPaint) {
                blockedPaint = true;

                if (MPBConfig.BUILDINGS.enabled && MPBConfig.BUILDINGS.hardBlockInPathfinding) {
                    // Never walk through building footprints unless explicitly allowed via existing Millenaire road.
                    if (MPBConfig.BUILDINGS.allowExistingRoadThroughBuildings && hasMillPathCol) {
                        blockedPath = false;
                    } else {
                        blockedPath = true;
                    }
                } else {
                    // Legacy behavior.
                    blockedPath = !hasMillPathCol;
                }
            } else {
                BlockPos.MutableBlockPos pos2 = new BlockPos.MutableBlockPos(x, surfaceY, z);
                IBlockState ground = world.getBlockState(pos2);

                boolean paintable = isNaturalGround(ground);
                boolean walkable = ground.getMaterial().isSolid() && ground.getMaterial() != Material.LEAVES;

                BlockPos.MutableBlockPos up1 = new BlockPos.MutableBlockPos(x, surfaceY + 1, z);
                BlockPos.MutableBlockPos up2 = new BlockPos.MutableBlockPos(x, surfaceY + 2, z);

                boolean headroomPath = isClearableOrAir(world.getBlockState(up1), up1, Mode.PATHFINDING)
                        && isClearableOrAir(world.getBlockState(up2), up2, Mode.PATHFINDING);
                boolean headroomPaint = isClearableOrAir(world.getBlockState(up1), up1, Mode.PAINTING)
                        && isClearableOrAir(world.getBlockState(up2), up2, Mode.PAINTING);

                blockedPath = !walkable || !headroomPath;

                boolean manmade = hasManmadeNearby(x, surfaceY, z);
                boolean tile = hasTileEntityNearby(x, surfaceY, z);

                blockedPaint = !paintable || !headroomPaint || manmade || tile;

                // Extra building-like guard (optional).
                if (!blockedPaint && MPBConfig.BUILDINGS.enabled && MPBConfig.BUILDINGS.extraPaintingGuard) {
                    int rr = Math.max(1, MPBConfig.BUILDINGS.extraPaintingGuardRadius);

                    // Only guard near actual building no-paint zones, to avoid blocking forest roads.
                    int nearR = Math.max(2, MPBConfig.BUILDINGS.footprintPadding + 2);
                    if (isNearBuildingNoPaintZone(x, z, nearR)) {
                        if (looksLikeBuildingColumn(x, surfaceY, z, rr)) {
                            blockedPaint = true;
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

        Sample out = new Sample(x, z, surfaceY, isWater, isOcean, isBlacklisted, blockedPath, blockedPaint, smallWater, nearWaterLike, biome);
        return cachePut(k, out);
    }

    public boolean forbidden(Sample s, Mode mode) {
        if (MPBConfig.GENERAL.forbidOcean && s.isOcean) return true;
        if (MPBConfig.GENERAL.hardBlockBlacklistedBiomes && s.isBlacklisted) return true;

        return mode == Mode.PAINTING ? s.blockedPaint : s.blockedPath;
    }

    public ForbiddenReason forbiddenReason(Sample s, Mode mode) {
        if (MPBConfig.GENERAL.forbidOcean && s.isOcean) return ForbiddenReason.OCEAN;
        if (MPBConfig.GENERAL.hardBlockBlacklistedBiomes && s.isBlacklisted) return ForbiddenReason.BLACKLIST;


        if (mode == Mode.PAINTING ? s.blockedPaint : s.blockedPath) return ForbiddenReason.BLOCKED;
        return ForbiddenReason.NONE;
    }

    public String describeForbidden(Sample s, Mode mode) {
        ForbiddenReason r = forbiddenReason(s, mode);
        if (r == ForbiddenReason.OCEAN) return "ocean biome";
        if (r == ForbiddenReason.BLACKLIST) return "blacklisted biome";
        if (r == ForbiddenReason.WATER_BUFFER) return "near water buffer";
        if (r == ForbiddenReason.BLOCKED) {
            return mode == Mode.PAINTING ? "blocked (paint rules/manmade/tile)" : "blocked (walk/headroom)";
        }
        return "none";
    }

    public boolean forbiddenHard(Sample s) {
        if (MPBConfig.GENERAL.forbidOcean && s.isOcean) return true;
        return MPBConfig.GENERAL.hardBlockBlacklistedBiomes && s.isBlacklisted;
    }

    private Sample cachePut(long k, Sample out) {
        if (cache.size() >= MAX_SAMPLE_CACHE) {
            cache.clear();
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
        return !isNaturalGround(st);
    }

    private boolean isSkippableTop(IBlockState st, BlockPos pos) {
        Material m = st.getMaterial();
        Block b = st.getBlock();

        if (b.isLeaves(st, world, pos)) return true;
        if (m == Material.PLANTS || m == Material.VINE) return true;
        if (m.isReplaceable()) return true;
        if (b == Blocks.SNOW_LAYER) return true;

        if (MPBConfig.PAINTING.clearTrees && MPBConfig.PAINTING.clearTreeTrunks && isLikelyTreeWood(st, pos)) return true;

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
        if (m == Material.AIR) return true;

        Block b = st.getBlock();

        if (b.isLeaves(st, world, pos)) return true;
        if (m == Material.PLANTS || m == Material.VINE) return true;
        if (m.isReplaceable()) return true;
        if (b == Blocks.SNOW_LAYER) return true;

        if (mode == Mode.PATHFINDING && MPBConfig.PAINTING.clearTrees && MPBConfig.PAINTING.clearTreeTrunks) {
            if (b.isWood(world, pos) || m == Material.WOOD) return true;
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
            if (isSkippableTop(st, p)) continue;

            break;
        }

        for (int dy = -2; dy <= 2; dy++) {
            int yy = yGuess + dy;
            if (yy < 1 || yy > 255) continue;
            p.setPos(x, yy, z);
            if (isWaterLike(world.getBlockState(p))) return true;
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
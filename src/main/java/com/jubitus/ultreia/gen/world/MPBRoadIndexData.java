package com.jubitus.ultreia.gen.world;

import com.jubitus.ultreia.MillPresence;
import com.jubitus.ultreia.block.MPBBlocks;
import com.jubitus.ultreia.gen.MPBChunkManager;
import com.jubitus.ultreia.gen.TerrainSampler;
import com.jubitus.ultreia.gen.mill.MPBMillPathPalette;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

import javax.annotation.Nullable;

public class MPBRoadIndexData extends WorldSavedData {

    private static final String NAME = "ultreia_roadindex";
    private static final int WORDS = 4; // 4 * 64 = 256 bits per chunk

    // Per-chunk data: road presence + bridge deck presence + bridge volume + Y per XZ column
    private static final class ChunkData {
        final long[] road = new long[WORDS];
        long[] bridge = null;   // lazy: only allocated for chunks with bridge decks
        long[] volume = null;   // lazy: full bridge footprint (deck + supports + lateral extent)
        byte[] yArr = null;     // lazy: Y per XZ index (stored as signed byte, read with &0xFF)
    }

    private final Long2ObjectOpenHashMap<ChunkData> chunkData = new Long2ObjectOpenHashMap<>();

    private transient final LongOpenHashSet scannedChunks = new LongOpenHashSet();
    private transient MPBChunkManager chunkManager;

    public MPBRoadIndexData() {
        super(NAME);
    }

    public MPBRoadIndexData(String name) {
        super(name);
    }

    public static MPBRoadIndexData get(WorldServer world) {
        MapStorage storage = world.getPerWorldStorage();
        MPBRoadIndexData data = (MPBRoadIndexData) storage.getOrLoadData(MPBRoadIndexData.class, NAME);
        if (data == null) {
            data = new MPBRoadIndexData();
            storage.setData(NAME, data);
        }
        data.initTransient();
        return data;
    }

    private void initTransient() {
        // scannedChunks is created in field init
    }

    public void attachChunkManager(@Nullable MPBChunkManager mgr) {
        this.chunkManager = mgr;
    }

    // --- Road presence ---

    public boolean isRoadXZ(int x, int z) {
        ChunkData cd = chunkData.get(chunkKey(x >> 4, z >> 4));
        if (cd == null) return false;
        int idx = bitIndex(x, z);
        return (cd.road[idx >>> 6] & (1L << (idx & 63))) != 0L;
    }

    public void markRoad(int x, int z) {
        ChunkData cd = getOrCreateChunk(x >> 4, z >> 4);
        int idx = bitIndex(x, z);
        int w = idx >>> 6;
        int b = idx & 63;
        long before = cd.road[w];
        long after = before | (1L << b);
        if (after != before) {
            cd.road[w] = after;
            markDirty();
        }
    }

    /** Mark road with a known Y coordinate (more precise than scanning later). */
    public void markRoad(int x, int y, int z) {
        markRoad(x, z);
        storeY(x, y, z);
    }

    // --- Bridge volume (full footprint: deck + supports + lateral extent) ---

    /** Mark an XZ column as part of a bridge volume (persisted). */
    public void markBridgeVolume(int x, int z) {
        ChunkData cd = getOrCreateChunk(x >> 4, z >> 4);
        int idx = bitIndex(x, z);
        int w = idx >>> 6;
        int b = idx & 63;
        if (cd.volume == null) cd.volume = new long[WORDS];
        long before = cd.volume[w];
        long after = before | (1L << b);
        if (after != before) {
            cd.volume[w] = after;
            markDirty();
        }
    }

    public boolean isBridgeVolumeXZ(int x, int z) {
        ChunkData cd = chunkData.get(chunkKey(x >> 4, z >> 4));
        if (cd == null || cd.volume == null) return false;
        int idx = bitIndex(x, z);
        return (cd.volume[idx >>> 6] & (1L << (idx & 63))) != 0L;
    }

    // --- Bridge deck ---

    public boolean isBridgeXZ(int x, int z) {
        ChunkData cd = chunkData.get(chunkKey(x >> 4, z >> 4));
        if (cd == null || cd.bridge == null) return false;
        int idx = bitIndex(x, z);
        return (cd.bridge[idx >>> 6] & (1L << (idx & 63))) != 0L;
    }

    /** Mark an XZ column as both road and bridge deck, storing its Y coordinate. */
    public void markBridgeDeck(int x, int y, int z) {
        ChunkData cd = getOrCreateChunk(x >> 4, z >> 4);
        int idx = bitIndex(x, z);
        int w = idx >>> 6;
        int b = idx & 63;

        boolean changed = false;

        long roadBefore = cd.road[w];
        long roadAfter = roadBefore | (1L << b);
        if (roadAfter != roadBefore) { cd.road[w] = roadAfter; changed = true; }

        if (cd.bridge == null) cd.bridge = new long[WORDS];
        long bridgeBefore = cd.bridge[w];
        long bridgeAfter = bridgeBefore | (1L << b);
        if (bridgeAfter != bridgeBefore) { cd.bridge[w] = bridgeAfter; changed = true; }

        storeY(x, y, z);

        if (changed) markDirty();
    }

    // --- Y lookup ---

    /** Returns the stored Y for this XZ, or -1 if not set. */
    public int getRoadY(int x, int z) {
        ChunkData cd = chunkData.get(chunkKey(x >> 4, z >> 4));
        if (cd == null || cd.yArr == null) return -1;
        int raw = cd.yArr[bitIndex(x, z)] & 0xFF;
        return raw == 0 ? -1 : (raw - 1); // 0 = unset; stored as (Y+1) so Y=0 is valid
    }

    private void storeY(int x, int y, int z) {
        if (y < 0 || y > 254) return; // Y+1 must fit in byte 1..255
        ChunkData cd = getOrCreateChunk(x >> 4, z >> 4);
        if (cd.yArr == null) cd.yArr = new byte[256];
        cd.yArr[bitIndex(x, z)] = (byte) (y + 1);
    }

    // --- Nearest road / bridge ---

    @Nullable
    public BlockPos findNearestRoad(int x, int z, int maxRadius) {
        if (maxRadius < 0) return null;
        if (isRoadXZ(x, z)) return new BlockPos(x, 0, z);

        for (int r = 1; r <= maxRadius; r++) {
            int minX = x - r, maxX = x + r, minZ = z - r, maxZ = z + r;
            for (int xx = minX; xx <= maxX; xx++) {
                if (isRoadXZ(xx, minZ)) return new BlockPos(xx, 0, minZ);
                if (isRoadXZ(xx, maxZ)) return new BlockPos(xx, 0, maxZ);
            }
            for (int zz = minZ + 1; zz <= maxZ - 1; zz++) {
                if (isRoadXZ(minX, zz)) return new BlockPos(minX, 0, zz);
                if (isRoadXZ(maxX, zz)) return new BlockPos(maxX, 0, zz);
            }
        }
        return null;
    }

    @Nullable
    public BlockPos findNearestBridge(int x, int z, int maxRadius) {
        if (maxRadius < 0) return null;
        if (isBridgeXZ(x, z)) return new BlockPos(x, Math.max(0, getRoadY(x, z)), z);

        for (int r = 1; r <= maxRadius; r++) {
            int minX = x - r, maxX = x + r, minZ = z - r, maxZ = z + r;
            for (int xx = minX; xx <= maxX; xx++) {
                if (isBridgeXZ(xx, minZ)) return new BlockPos(xx, Math.max(0, getRoadY(xx, minZ)), minZ);
                if (isBridgeXZ(xx, maxZ)) return new BlockPos(xx, Math.max(0, getRoadY(xx, maxZ)), maxZ);
            }
            for (int zz = minZ + 1; zz <= maxZ - 1; zz++) {
                if (isBridgeXZ(minX, zz)) return new BlockPos(minX, Math.max(0, getRoadY(minX, zz)), zz);
                if (isBridgeXZ(maxX, zz)) return new BlockPos(maxX, Math.max(0, getRoadY(maxX, zz)), zz);
            }
        }
        return null;
    }

    // --- Chunk scanning ---

    public void ensureScannedRadius(WorldServer world, int x, int z, int radius) {
        int cminX = (x - radius) >> 4, cmaxX = (x + radius) >> 4;
        int cminZ = (z - radius) >> 4, cmaxZ = (z + radius) >> 4;

        for (int cx = cminX; cx <= cmaxX; cx++) {
            for (int cz = cminZ; cz <= cmaxZ; cz++) {
                long ck = chunkKey(cx, cz);
                if (!scannedChunks.contains(ck) && scanChunk(world, cx, cz)) scannedChunks.add(ck);
            }
        }
    }

    /** Returns false when the chunk could not be read; it is then scanned again on a later request. */
    private boolean scanChunk(WorldServer world, int cx, int cz) {
        int wx0 = cx << 4, wz0 = cz << 4;

        boolean loaded = chunkManager != null
                ? chunkManager.ensureLoaded(world, wx0, wz0)
                : world.isBlockLoaded(new BlockPos(wx0, 0, wz0));
        if (!loaded) return false;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = wx0 + lx, z = wz0 + lz;

                int y = world.getHeight(pos.setPos(x, 0, z)).getY() - 1;
                if (y < 1) y = 1;
                if (y > 255) y = 255;

                int surfaceY = findSurfaceYForScan(world, x, y, z, pos);

                for (int dy = -2; dy <= 2; dy++) {
                    int yy = surfaceY + dy;
                    if (yy < 1 || yy > 255) continue;
                    pos.setPos(x, yy, z);
                    IBlockState st = world.getBlockState(pos);
                    Block b = st.getBlock();
                    // Only index MPB's own blocks — Millénaire village paths share the same
                    // palette but must NOT be treated as MPB-generated roads.
                    if (b == MPBBlocks.PATH_BLOCK || b == MPBBlocks.PATH_SLAB) {
                        markRoad(x, yy, z);
                        break;
                    }
                }
            }
        }
        return true;
    }

    private int findSurfaceYForScan(World world, int x, int startY, int z, BlockPos.MutableBlockPos pos) {
        int y = startY;
        for (int i = 0; i < 256 && y > 1; i++, y--) {
            pos.setPos(x, y, z);
            IBlockState st = world.getBlockState(pos);
            if (st.getMaterial().isReplaceable()) continue;
            Block b = st.getBlock();
            if (st.getMaterial().isLiquid()) return y;
            if (b == net.minecraft.init.Blocks.SNOW_LAYER) continue;
            if (st.getMaterial() == net.minecraft.block.material.Material.PLANTS) continue;
            if (st.getMaterial() == net.minecraft.block.material.Material.VINE) continue;
            if (st.getMaterial() == net.minecraft.block.material.Material.LEAVES) continue;
            if (st.getMaterial() == net.minecraft.block.material.Material.WOOD) continue;
            try {
                if (b.isLeaves(st, world, pos)) continue;
                if (b.isWood(world, pos)) continue;
            } catch (Throwable ignored) {}
            return y;
        }
        return Math.max(1, y);
    }

    // --- Kind lookup (unchanged) ---

    @Nullable
    public MPBMillPathPalette.Kind findExistingKindAt(WorldServer world, TerrainSampler sampler, int x, int z) {
        TerrainSampler.Sample sm = sampler.sample(x, z);
        int y = sm.surfaceY;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = -2; dy <= 3; dy++) {
            int yy = y + dy;
            if (yy < 1 || yy > 255) continue;
            p.setPos(x, yy, z);
            IBlockState st = world.getBlockState(p);
            MPBMillPathPalette.Kind k = MPBMillPathPalette.kindFromState(st);
            if (k != null) return k;
        }
        return null;
    }


    // --- Approx nearest road dist (unchanged) ---

    public int approxNearestRoadDistManhattan(int x, int z, int maxRadius, int step) {
        if (maxRadius <= 0) return Integer.MAX_VALUE;
        if (step < 1) step = 1;
        if (isRoadXZ(x, z)) return 0;

        for (int r = 1; r <= maxRadius; r += step) {
            int minX = x - r, maxX = x + r, minZ = z - r, maxZ = z + r;
            for (int xx = minX; xx <= maxX; xx += step) {
                if (isRoadXZ(xx, minZ) || isRoadXZ(xx, maxZ)) return r;
            }
            for (int zz = minZ; zz <= maxZ; zz += step) {
                if (isRoadXZ(minX, zz) || isRoadXZ(maxX, zz)) return r;
            }
        }
        return Integer.MAX_VALUE;
    }

    // --- NBT serialization ---

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        chunkData.clear();

        // New format: "chunks2" with "r", optional "b", optional "y" per chunk
        if (nbt.hasKey("chunks2")) {
            NBTTagList list = nbt.getTagList("chunks2", 10);
            for (int i = 0; i < list.tagCount(); i++) {
                NBTTagCompound e = list.getCompoundTagAt(i);
                long k = e.getLong("k");

                NBTTagList rl = e.getTagList("r", 4);
                if (rl == null || rl.tagCount() != WORDS) continue;
                long[] road = new long[WORDS];
                for (int j = 0; j < WORDS; j++) road[j] = ((net.minecraft.nbt.NBTTagLong) rl.get(j)).getLong();

                boolean anyRoad = false;
                for (long v : road) if (v != 0L) { anyRoad = true; break; }
                if (!anyRoad) continue;

                ChunkData cd = new ChunkData();
                System.arraycopy(road, 0, cd.road, 0, WORDS);

                if (e.hasKey("b")) {
                    NBTTagList bl = e.getTagList("b", 4);
                    if (bl != null && bl.tagCount() == WORDS) {
                        cd.bridge = new long[WORDS];
                        for (int j = 0; j < WORDS; j++) cd.bridge[j] = ((net.minecraft.nbt.NBTTagLong) bl.get(j)).getLong();
                    }
                }

                if (e.hasKey("v")) {
                    NBTTagList vl = e.getTagList("v", 4);
                    if (vl != null && vl.tagCount() == WORDS) {
                        cd.volume = new long[WORDS];
                        for (int j = 0; j < WORDS; j++) cd.volume[j] = ((net.minecraft.nbt.NBTTagLong) vl.get(j)).getLong();
                    }
                }

                if (e.hasKey("y")) {
                    NBTTagByteArray ya = (NBTTagByteArray) e.getTag("y");
                    if (ya != null && ya.getByteArray().length == 256) {
                        cd.yArr = ya.getByteArray().clone();
                    }
                }

                chunkData.put(k, cd);
            }
            return;
        }

        // Legacy format: "chunks" with "m" (road bitmask only, no bridge/Y)
        NBTTagList list = nbt.getTagList("chunks", 10);
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound e = list.getCompoundTagAt(i);
            long k = e.getLong("k");

            NBTTagList ml = e.getTagList("m", 4);
            if (ml == null || ml.tagCount() != WORDS) continue;

            ChunkData cd = new ChunkData();
            for (int j = 0; j < WORDS; j++) cd.road[j] = ((net.minecraft.nbt.NBTTagLong) ml.get(j)).getLong();

            boolean any = false;
            for (long v : cd.road) if (v != 0L) { any = true; break; }
            if (!any) continue;

            chunkData.put(k, cd);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();

        for (long k : chunkData.keySet()) {
            ChunkData cd = chunkData.get(k);
            if (cd == null) continue;

            boolean anyRoad = false;
            for (long v : cd.road) if (v != 0L) { anyRoad = true; break; }
            if (!anyRoad) continue;

            NBTTagCompound e = new NBTTagCompound();
            e.setLong("k", k);

            NBTTagList rl = new NBTTagList();
            for (int i = 0; i < WORDS; i++) rl.appendTag(new net.minecraft.nbt.NBTTagLong(cd.road[i]));
            e.setTag("r", rl);

            if (cd.bridge != null) {
                boolean anyBridge = false;
                for (long v : cd.bridge) if (v != 0L) { anyBridge = true; break; }
                if (anyBridge) {
                    NBTTagList bl = new NBTTagList();
                    for (int i = 0; i < WORDS; i++) bl.appendTag(new net.minecraft.nbt.NBTTagLong(cd.bridge[i]));
                    e.setTag("b", bl);
                }
            }

            if (cd.volume != null) {
                boolean anyVolume = false;
                for (long v : cd.volume) if (v != 0L) { anyVolume = true; break; }
                if (anyVolume) {
                    NBTTagList vl = new NBTTagList();
                    for (int i = 0; i < WORDS; i++) vl.appendTag(new net.minecraft.nbt.NBTTagLong(cd.volume[i]));
                    e.setTag("v", vl);
                }
            }

            if (cd.yArr != null) {
                e.setTag("y", new NBTTagByteArray(cd.yArr.clone()));
            }

            list.appendTag(e);
        }

        nbt.setTag("chunks2", list);
        return nbt;
    }

    // --- Helpers ---

    private ChunkData getOrCreateChunk(int cx, int cz) {
        long k = chunkKey(cx, cz);
        ChunkData cd = chunkData.get(k);
        if (cd == null) {
            cd = new ChunkData();
            chunkData.put(k, cd);
        }
        return cd;
    }

    private static int bitIndex(int x, int z) {
        return ((z & 15) << 4) | (x & 15);
    }

    private static long chunkKey(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }
}

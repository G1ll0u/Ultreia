package com.jubitus.millpathbuilder.gen.world;

import com.jubitus.millpathbuilder.gen.MPBChunkManager;
import com.jubitus.millpathbuilder.gen.TerrainSampler;
import com.jubitus.millpathbuilder.gen.mill.MPBMillPathPalette;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;
import org.millenaire.common.block.MillBlocks;

import javax.annotation.Nullable;

public class MPBRoadIndexData extends WorldSavedData {

    private static final String NAME = "millpathbuilder_roadindex";
    private static final int WORDS = 4; // 4 * 64 = 256 bits per chunk

    private final Long2ObjectOpenHashMap<long[]> masksByChunk = new Long2ObjectOpenHashMap<>();

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
        // nothing else; scannedChunks is created in field init
    }

    public void attachChunkManager(@Nullable MPBChunkManager mgr) {
        this.chunkManager = mgr;
    }

    public boolean isRoadXZ(int x, int z) {
        long ck = chunkKey(x >> 4, z >> 4);
        long[] m = masksByChunk.get(ck);
        if (m == null) return false;
        int idx = bitIndex(x, z);
        int w = idx >>> 6;
        int b = idx & 63;
        return (m[w] & (1L << b)) != 0L;
    }

    public void markRoad(int x, int z) {
        long ck = chunkKey(x >> 4, z >> 4);
        long[] m = masksByChunk.get(ck);
        if (m == null) {
            m = new long[WORDS];
            masksByChunk.put(ck, m);
        }
        int idx = bitIndex(x, z);
        int w = idx >>> 6;
        int b = idx & 63;
        long before = m[w];
        long after = before | (1L << b);
        if (after != before) {
            m[w] = after;
            markDirty();
        }
    }


    @Nullable
    public BlockPos findNearestRoad(int x, int z, int maxRadius) {
        if (maxRadius < 0) return null;
        if (isRoadXZ(x, z)) return new BlockPos(x, 0, z);

        for (int r = 1; r <= maxRadius; r++) {
            int minX = x - r;
            int maxX = x + r;
            int minZ = z - r;
            int maxZ = z + r;

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

    public void ensureScannedRadius(WorldServer world, int x, int z, int radius) {
        int minX = x - radius;
        int maxX = x + radius;
        int minZ = z - radius;
        int maxZ = z + radius;

        int cminX = minX >> 4;
        int cmaxX = maxX >> 4;
        int cminZ = minZ >> 4;
        int cmaxZ = maxZ >> 4;

        for (int cx = cminX; cx <= cmaxX; cx++) {
            for (int cz = cminZ; cz <= cmaxZ; cz++) {
                long ck = chunkKey(cx, cz);
                if (scannedChunks.add(ck)) {
                    scanChunk(world, cx, cz);
                }
            }
        }
    }

    private void scanChunk(WorldServer world, int cx, int cz) {
        int wx0 = cx << 4;
        int wz0 = cz << 4;

        if (chunkManager != null) {
            chunkManager.ensureLoaded(world, wx0, wz0);
        } else {
            BlockPos p = new BlockPos(wx0, 0, wz0);
            if (!world.isBlockLoaded(p)) return;
        }

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int x = wx0 + lx;
                int z = wz0 + lz;

                int y = world.getHeight(pos.setPos(x, 0, z)).getY() - 1;
                if (y < 1) y = 1;
                if (y > 255) y = 255;

                int surfaceY = findSurfaceYForScan(world, x, y, z, pos);

                boolean found = false;
                for (int dy = -2; dy <= 2; dy++) {
                    int yy = surfaceY + dy;
                    if (yy < 1 || yy > 255) continue;
                    pos.setPos(x, yy, z);
                    IBlockState st = world.getBlockState(pos);
                    Block b = st.getBlock();
                    if (MPBMillPathPalette.isMillPath(b)) {
                        found = true;
                        break;
                    }
                }

                if (found) {
                    markRoad(x, z);
                }
            }
        }
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
            } catch (Throwable ignored) {
            }
            return y;
        }
        return Math.max(1, y);
    }

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
            MPBMillPathPalette.Kind k = kindFromState(st);
            if (k != null) return k;
        }
        return null;
    }

    @Nullable
    public static MPBMillPathPalette.Kind kindFromState(IBlockState st) {
        if (st == null) return null;
        Block b = st.getBlock();

        if (b == MillBlocks.PATHDIRT || b == MillBlocks.PATHDIRT_SLAB) return MPBMillPathPalette.Kind.DIRT;
        if (b == MillBlocks.PATHGRAVEL || b == MillBlocks.PATHGRAVEL_SLAB) return MPBMillPathPalette.Kind.GRAVEL;
        if (b == MillBlocks.PATHSNOW || b == MillBlocks.PATHSNOW_SLAB) return MPBMillPathPalette.Kind.SNOW;

        if (b == MillBlocks.PATHSANDSTONE || b == MillBlocks.PATHSANDSTONE_SLAB) return MPBMillPathPalette.Kind.SANDSTONE;
        if (b == MillBlocks.PATHSLABS || b == MillBlocks.PATHSLABS_SLAB) return MPBMillPathPalette.Kind.SLABS;
        if (b == MillBlocks.PATHOCHRESLABS || b == MillBlocks.PATHOCHRESLABS_SLAB) return MPBMillPathPalette.Kind.OCHRE;

        return null;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        masksByChunk.clear();

        NBTTagList list = nbt.getTagList("chunks", 10); // list of compounds
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound e = list.getCompoundTagAt(i);
            long k = e.getLong("k");

            NBTTagList ml = e.getTagList("m", 4); // list of long tags
            if (ml == null || ml.tagCount() != WORDS) continue;

            long[] m = new long[WORDS];
            for (int j = 0; j < WORDS; j++) {
                m[j] = ((net.minecraft.nbt.NBTTagLong) ml.get(j)).getLong();
            }

            masksByChunk.put(k, m);
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        NBTTagList list = new NBTTagList();

        for (long k : masksByChunk.keySet()) {
            long[] m = masksByChunk.get(k);
            if (m == null) continue;

            boolean any = false;
            for (int i = 0; i < WORDS; i++) {
                if (m[i] != 0L) { any = true; break; }
            }
            if (!any) continue;

            NBTTagCompound e = new NBTTagCompound();
            e.setLong("k", k);

            NBTTagList ml = new NBTTagList();
            for (int i = 0; i < WORDS; i++) {
                ml.appendTag(new net.minecraft.nbt.NBTTagLong(m[i]));
            }
            e.setTag("m", ml);

            list.appendTag(e);
        }

        nbt.setTag("chunks", list);
        return nbt;
    }

    private static int bitIndex(int x, int z) {
        int lx = x & 15;
        int lz = z & 15;
        return (lz << 4) | lx;
    }

    private static long chunkKey(int cx, int cz) {
        return (((long) cx) << 32) ^ (cz & 0xffffffffL);
    }
    public int approxNearestRoadDistManhattan(int x, int z, int maxRadius, int step) {
        if (maxRadius <= 0) return Integer.MAX_VALUE;
        if (step < 1) step = 1;

        if (isRoadXZ(x, z)) return 0;

        for (int r = 1; r <= maxRadius; r += step) {
            int minX = x - r;
            int maxX = x + r;
            int minZ = z - r;
            int maxZ = z + r;

            // top/bottom edges
            for (int xx = minX; xx <= maxX; xx += step) {
                if (isRoadXZ(xx, minZ) || isRoadXZ(xx, maxZ)) return r;
            }
            // left/right edges
            for (int zz = minZ; zz <= maxZ; zz += step) {
                if (isRoadXZ(minX, zz) || isRoadXZ(maxX, zz)) return r;
            }
        }

        return Integer.MAX_VALUE;
    }
}
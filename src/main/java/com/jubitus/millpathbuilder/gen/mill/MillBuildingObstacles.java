package com.jubitus.millpathbuilder.gen.mill;

import com.jubitus.millpathbuilder.gen.MPBGround;
import com.jubitus.millpathbuilder.gen.MPBProtectedAreas;
import com.jubitus.millpathbuilder.gen.TerrainSampler;
import com.jubitus.millpathbuilder.integration.MillIntegration;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.world.MillWorldData;

import java.util.List;

public final class MillBuildingObstacles {

    private MillBuildingObstacles() {}

    public static void addAllBuildingsAsNoPaint(
            WorldServer world,
            TerrainSampler sampler,
            MPBProtectedAreas out,
            int villageScanXZ,
            int villageScanY,
            int perBuildingMaxScanR,
            int margin,
            int penalty
    ) {
        MillWorldData wd = MillIntegration.ensureLoaded(world);
        if (wd == null) return;

        // Villages: iterate their centers, then list all buildings around
        if (wd.villagesList != null && wd.villagesList.pos != null) {
            for (Point vp : wd.villagesList.pos) {
                if (vp == null) continue;
                BlockPos center = new BlockPos(vp.getiX(), vp.getiY(), vp.getiZ());
                List<MillVillageIndex.BuildingInfo> buildings =
                        MillVillageIndex.listBuildingsAroundVillageCached(world, center, villageScanXZ, villageScanY);
                for (MillVillageIndex.BuildingInfo bi : buildings) {
                    addOneBuildingFootprint(world, sampler, out, bi.chestPos, perBuildingMaxScanR, margin, penalty);
                }
            }
        }

        // Lone buildings: treat the building itself as one footprint
        if (wd.loneBuildingsList != null && wd.loneBuildingsList.pos != null) {
            for (Point lp : wd.loneBuildingsList.pos) {
                if (lp == null) continue;
                BlockPos p = new BlockPos(lp.getiX(), lp.getiY(), lp.getiZ());
                addOneBuildingFootprint(world, sampler, out, p, perBuildingMaxScanR, margin, penalty);
            }
        }
    }

    private static void addOneBuildingFootprint(
            WorldServer world,
            TerrainSampler sampler,
            MPBProtectedAreas out,
            BlockPos chestPos,
            int maxR,
            int margin,
            int penalty
    ) {
        int sx = chestPos.getX();
        int sz = chestPos.getZ();

        // Find a seed cell that looks like building (chest may be outside)
        int[] seed = findSeedManmadeCell(world, sampler, sx, sz, 6);
        if (seed == null) return;

        int seedX = seed[0];
        int seedZ = seed[1];

        // Flood fill in XZ on "manmade" surface columns excluding Mill path blocks
        IntArrayQueue qx = new IntArrayQueue();
        IntArrayQueue qz = new IntArrayQueue();
        LongOpenHashSet visited = new LongOpenHashSet();

        qx.enqueue(seedX);
        qz.enqueue(seedZ);
        visited.add(xzKey(seedX, seedZ));

        int minX = seedX, maxXb = seedX, minZ = seedZ, maxZb = seedZ;
        int cells = 0;

        while (!qx.isEmpty()) {
            int x = qx.dequeueInt();
            int z = qz.dequeueInt();

            if (Math.abs(x - seedX) > maxR || Math.abs(z - seedZ) > maxR) continue;

            if (!isBuildingColumn(world, sampler, x, z)) continue;

            cells++;
            if (x < minX) minX = x;
            if (x > maxXb) maxXb = x;
            if (z < minZ) minZ = z;
            if (z > maxZb) maxZb = z;

            // 4-neighbors
            pushIfNew(qx, qz, visited, x + 1, z);
            pushIfNew(qx, qz, visited, x - 1, z);
            pushIfNew(qx, qz, visited, x, z + 1);
            pushIfNew(qx, qz, visited, x, z - 1);
        }

        if (cells < 12) return; // too small -> ignore noise

        minX -= Math.max(0, margin);
        maxXb += Math.max(0, margin);
        minZ -= Math.max(0, margin);
        maxZb += Math.max(0, margin);

        out.addNoPaintRect(minX, maxXb, minZ, maxZb, penalty);
    }

    private static void pushIfNew(IntArrayQueue qx, IntArrayQueue qz, LongOpenHashSet vis, int x, int z) {
        long k = xzKey(x, z);
        if (vis.add(k)) {
            qx.enqueue(x);
            qz.enqueue(z);
        }
    }

    private static int[] findSeedManmadeCell(WorldServer world, TerrainSampler sampler, int x0, int z0, int r) {
        if (isBuildingColumn(world, sampler, x0, z0)) return new int[]{x0, z0};
        for (int rr = 1; rr <= r; rr++) {
            for (int dx = -rr; dx <= rr; dx++) {
                int x1 = x0 + dx;
                int z1 = z0 - rr;
                if (isBuildingColumn(world, sampler, x1, z1)) return new int[]{x1, z1};
                z1 = z0 + rr;
                if (isBuildingColumn(world, sampler, x1, z1)) return new int[]{x1, z1};
            }
            for (int dz = -rr + 1; dz <= rr - 1; dz++) {
                int z1 = z0 + dz;
                int x1 = x0 - rr;
                if (isBuildingColumn(world, sampler, x1, z1)) return new int[]{x1, z1};
                x1 = x0 + rr;
                if (isBuildingColumn(world, sampler, x1, z1)) return new int[]{x1, z1};
            }
        }
        return null;
    }

    private static boolean isBuildingColumn(WorldServer world, TerrainSampler sampler, int x, int z) {
        TerrainSampler.Sample sm = sampler.sample(x, z);
        if (sm == null) return false;
        if (sm.isOcean) return false;

        // If this is a Millenaire path column -> not building obstacle (we want to connect to paths)
        if (columnHasMillPath(world, sm.surfaceY, x, z)) return false;

        // Detect solid non-natural blocks / tile entities in the near-surface column
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = -1; dy <= 5; dy++) {
            int yy = sm.surfaceY + dy;
            if (yy < 1 || yy > 255) continue;
            p.setPos(x, yy, z);
            IBlockState st = world.getBlockState(p);
            if (st == null) continue;

            if (world.getTileEntity(p) != null) return true;

            Material m = st.getMaterial();
            if (!m.isSolid()) continue;
            if (m == Material.LEAVES) continue;
            if (m == Material.PLANTS || m == Material.VINE) continue;
            if (m == Material.WATER) continue;

            // Natural ground is not building
            if (MPBGround.isRoadReplaceableGround(st)) continue;

            // Anything else solid counts as building
            return true;
        }
        return false;
    }

    private static boolean columnHasMillPath(WorldServer world, int surfaceY, int x, int z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = -2; dy <= 3; dy++) {
            int yy = surfaceY + dy;
            if (yy < 1 || yy > 255) continue;
            p.setPos(x, yy, z);
            IBlockState st = world.getBlockState(p);
            if (st != null && MPBMillPathPalette.isMillPath(st.getBlock())) return true;
        }
        return false;
    }

    private static long xzKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    // Minimal primitive int queue (fastutil also has IntArrayFIFOQueue but keeping it standalone)
    private static final class IntArrayQueue {
        private int[] a = new int[256];
        private int head = 0, tail = 0;

        boolean isEmpty() { return head == tail; }

        void enqueue(int v) {
            if (((tail + 1) & (a.length - 1)) == head && (a.length & (a.length - 1)) == 0) {
                growPow2();
            } else if (tail + 1 == a.length && head == 0) {
                growLinear();
            } else if (tail == a.length) {
                tail = 0;
            }
            a[tail++] = v;
        }

        int dequeueInt() {
            int v = a[head++];
            if (head == a.length) head = 0;
            return v;
        }

        private void growPow2() {
            int n = a.length;
            int[] b = new int[n << 1];
            int i = 0;
            while (!isEmpty()) b[i++] = dequeueInt();
            a = b;
            head = 0;
            tail = i;
        }

        private void growLinear() {
            int n = a.length;
            int[] b = new int[n << 1];
            System.arraycopy(a, 0, b, 0, n);
            a = b;
        }
    }
}
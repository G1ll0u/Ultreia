package com.jubitus.millpathbuilder.gen;

import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.mill.MPBMillPathPalette;
import com.jubitus.millpathbuilder.gen.mill.MillVillageIndex;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.List;

public final class MPBEndpointResolver {

    private MPBEndpointResolver() {}

    /**
     * Resolves the best approach point to connect a village or lone building.
     *
     * Village priority:
     * 1. Outermost Mill-path floor block at a gate facing the other village (ignores building noPaint).
     * 2. Outermost Mill-path block generally (outside noPaint zones).
     * 3. Approach point just outside building noPaint zones.
     * 4. Walk outward until free of building obstacles.
     */
    public static BlockPos resolveEndpointAnchor(
            WorldServer world,
            BlockPos nodeCenter,
            BlockPos otherNodeCenter,
            TerrainSampler sampler,
            @Nullable MPBProtectedAreas protectedAreas
    ) {
        MillVillageIndex.VillageInfo vi = MillVillageIndex.findExact(world, nodeCenter);
        boolean isVillage = (vi != null && vi.isVillage);

        int protectR = isVillage
                ? Math.max(0, MPBConfig.GENERAL.villageNoPaintRadius)
                : Math.max(0, MPBConfig.GENERAL.loneBuildingNoPaintRadius);

        if (isVillage) {
            // Rule 1: connect to the outermost Mill-path floor block at a gate facing the other village.
            // Gate Mill-path blocks are searched ignoring noPaint zones (gate floor IS Mill path by design).
            // The Y coordinate is the actual path floor, not the terrain surface.
            BlockPos gateEntry = findGateMillPathEntry(world, sampler, nodeCenter, otherNodeCenter, 120, 12);
            if (gateEntry != null) return gateEntry;

            // Rule 2: outermost Mill-path block facing other village, outside noPaint zones.
            // Also uses actual Mill-path Y (not terrain surface Y).
            BlockPos millEntry = findOutermostMillPathToward(world, sampler, nodeCenter, otherNodeCenter, 120, protectedAreas);
            if (millEntry != null) return millEntry;

            // Rule 3: approach point just outside building noPaint zones
            BlockPos approx = stepToward(nodeCenter, otherNodeCenter, Math.max(8, protectR + 6));
            BlockPos anchor = MPBGenerationJob.findAnchor(world, approx, sampler, 120);
            if (protectedAreas == null || !protectedAreas.contains(anchor.getX(), anchor.getZ())) {
                return anchor;
            }

            // Rule 4: walk outward until free
            return walkOutUntilFree(world, anchor, otherNodeCenter, sampler, protectedAreas);
        }

        // Lone building: bypass from outside protected zone
        BlockPos bypass = stepToward(nodeCenter, otherNodeCenter, protectR + 6);
        return walkOutUntilFree(world, bypass, otherNodeCenter, sampler, protectedAreas);
    }

    /**
     * Finds the best Mill-path floor block at a village gate, on the side facing the other village.
     * Ignores noPaint zones so gates inside building footprints are found correctly.
     * Uses full-column Y scan so the real path elevation is returned (not terrain surface Y).
     *
     * @param gateSearchRadius radius around village center to search for gate buildings
     * @param gateLocalRadius  radius around each gate's anchor to scan for Mill-path blocks
     */
    @Nullable
    private static BlockPos findGateMillPathEntry(
            WorldServer world,
            TerrainSampler sampler,
            BlockPos villageCenter,
            BlockPos otherNodeCenter,
            int gateSearchRadius,
            int gateLocalRadius
    ) {
        List<BlockPos> gates = MillVillageIndex.getVillageGates(world, villageCenter, gateSearchRadius, 64);
        if (gates.isEmpty()) return null;

        double ddx = otherNodeCenter.getX() - villageCenter.getX();
        double ddz = otherNodeCenter.getZ() - villageCenter.getZ();
        double dlen = Math.sqrt(ddx * ddx + ddz * ddz);
        if (dlen < 1.0) return null;
        double ux = ddx / dlen, uz = ddz / dlen;

        BlockPos best = null;
        double bestProj = 0.0; // only accept blocks projecting toward the other village

        for (BlockPos gatePos : gates) {
            int gx = gatePos.getX(), gz = gatePos.getZ();

            for (int ox = -gateLocalRadius; ox <= gateLocalRadius; ox++) {
                for (int oz = -gateLocalRadius; oz <= gateLocalRadius; oz++) {
                    int tx = gx + ox, tz = gz + oz;

                    // Projection from village center toward other village
                    double proj = (tx - villageCenter.getX()) * ux + (tz - villageCenter.getZ()) * uz;
                    if (proj <= bestProj) continue;

                    // Load chunk via sampler side-effect
                    TerrainSampler.Sample sm = sampler.sample(tx, tz);
                    if (sm == null || sm.isOcean || sm.isWater) continue;

                    Integer millY = findMillPathY(world, tx, tz, sm.surfaceY);
                    if (millY == null) continue;

                    bestProj = proj;
                    best = new BlockPos(tx, millY, tz);
                }
            }
        }

        return best;
    }

    /**
     * Returns the Millénaire path block with the highest projection toward the other village
     * within maxRadius of the village center, outside noPaint zones.
     * Returns the BlockPos with the actual Mill-path Y (not terrain surface Y).
     */
    @Nullable
    private static BlockPos findOutermostMillPathToward(
            WorldServer world,
            TerrainSampler sampler,
            BlockPos center,
            BlockPos other,
            int maxRadius,
            @Nullable MPBProtectedAreas protectedAreas
    ) {
        int cx = center.getX(), cz = center.getZ();
        double ddx = other.getX() - cx;
        double ddz = other.getZ() - cz;
        double dlen = Math.sqrt(ddx * ddx + ddz * ddz);
        if (dlen < 1.0) return null;
        double ux = ddx / dlen, uz = ddz / dlen;

        BlockPos best = null;
        double bestProj = Double.NEGATIVE_INFINITY;

        for (int ox = -maxRadius; ox <= maxRadius; ox++) {
            for (int oz = -maxRadius; oz <= maxRadius; oz++) {
                double proj = ox * ux + oz * uz;
                if (proj <= 0.0) continue;

                int r2 = ox * ox + oz * oz;
                if (r2 < 36 || r2 > maxRadius * maxRadius) continue;

                if (proj <= bestProj) continue;

                int tx = cx + ox, tz = cz + oz;
                if (protectedAreas != null && protectedAreas.contains(tx, tz)) continue;

                TerrainSampler.Sample sm = sampler.sample(tx, tz);
                if (sm == null || sm.isOcean || sm.isWater) continue;

                Integer millY = findMillPathY(world, tx, tz, sm.surfaceY);
                if (millY == null) continue;

                bestProj = proj;
                best = new BlockPos(tx, millY, tz);
            }
        }

        return best;
    }

    /**
     * Scans the block column at (x, z) for the lowest Millénaire/MPB path block.
     * Searches from Y=4 up to surfaceY+5 to catch paths buried below the terrain surface.
     * Returns the Y coordinate of the found block, or null if none exists.
     */
    @Nullable
    private static Integer findMillPathY(WorldServer world, int x, int z, int surfaceY) {
        int maxY = Math.min(255, surfaceY + 5);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int y = 4; y <= maxY; y++) {
            p.setPos(x, y, z);
            IBlockState st = world.getBlockState(p);
            if (st != null && MPBMillPathPalette.isMillPath(st.getBlock())) return y;
        }
        return null;
    }

    /** Steps from {@code from} toward {@code to} by exactly {@code dist} blocks. */
    private static BlockPos stepToward(BlockPos from, BlockPos to, int dist) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0) return from.add(dist, 0, 0);
        int sx = (int) Math.round(dx / len * dist);
        int sz = (int) Math.round(dz / len * dist);
        return from.add(sx, 0, sz);
    }

    /**
     * Walks outward from {@code start} toward {@code toward} in 4-block steps until
     * clear of any noPaint zone, then returns a stable anchor at that position.
     */
    private static BlockPos walkOutUntilFree(
            WorldServer world,
            BlockPos start,
            BlockPos toward,
            TerrainSampler sampler,
            @Nullable MPBProtectedAreas protectedAreas
    ) {
        if (protectedAreas == null) {
            return MPBGenerationJob.findAnchor(world, start, sampler, 24);
        }
        int dx = Integer.signum(toward.getX() - start.getX());
        int dz = Integer.signum(toward.getZ() - start.getZ());
        if (dx == 0 && dz == 0) dx = 1;

        BlockPos cur = start;
        for (int i = 0; i < 20; i++) {
            BlockPos a = MPBGenerationJob.findAnchor(world, cur, sampler, 16);
            if (!protectedAreas.contains(a.getX(), a.getZ())) return a;
            cur = cur.add(dx * 4, 0, dz * 4);
        }
        TerrainSampler.Sample sm = sampler.sample(cur.getX(), cur.getZ());
        return new BlockPos(cur.getX(), sm != null ? sm.surfaceY : start.getY(), cur.getZ());
    }

    public static String debugResolveEndpointAnchor(
            WorldServer world,
            BlockPos nodeCenter,
            BlockPos otherNodeCenter,
            TerrainSampler sampler,
            @Nullable MPBProtectedAreas protectedAreas
    ) {
        BlockPos resolved = resolveEndpointAnchor(world, nodeCenter, otherNodeCenter, sampler, protectedAreas);
        return "node=(" + nodeCenter.getX() + "," + nodeCenter.getZ() + ")"
                + " other=(" + otherNodeCenter.getX() + "," + otherNodeCenter.getZ() + ")"
                + " resolved=(" + resolved.getX() + "," + resolved.getY() + "," + resolved.getZ() + ")";
    }
}

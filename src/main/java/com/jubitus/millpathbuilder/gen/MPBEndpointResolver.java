package com.jubitus.millpathbuilder.gen;

import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.mill.MillGateRouting;
import com.jubitus.millpathbuilder.gen.mill.MillPathSnapping;
import com.jubitus.millpathbuilder.gen.mill.MillVillageConnections;
import com.jubitus.millpathbuilder.gen.mill.MillVillageIndex;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;

public final class MPBEndpointResolver {

    private MPBEndpointResolver() {}

    public static BlockPos resolveEndpointAnchor(
            WorldServer world,
            BlockPos nodeCenter,
            BlockPos otherNodeCenter,
            TerrainSampler sampler,
            @Nullable MPBProtectedAreas protectedAreas
    ) {
        MillVillageIndex.VillageInfo vi = MillVillageIndex.findExact(world, nodeCenter);

        int protectR = (vi != null && vi.isVillage)
                ? Math.max(0, MPBConfig.GENERAL.villageNoPaintRadius)
                : Math.max(0, MPBConfig.GENERAL.loneBuildingNoPaintRadius);

        if (vi != null && vi.isVillage) {
            BlockPos gate = MillGateRouting.pickGateFacing(world, vi.center, otherNodeCenter);

            if (gate != null && gateLooksOnApproachSide(vi.center, otherNodeCenter, gate)) {
                // 1) Join the first existing Millenaire path near the gate (usually under the arch)
                BlockPos snap = MillPathSnapping.findFirstMillPathAround(world, sampler, gate, 18);
                if (snap != null) return snap;

                // 2) Fallback: stop near the gate like before
                return MPBGenerationJob.findAnchor(world, gate, sampler, 24);
            }

            // No gate on approach side: keep your existing behavior (approach point near village edge)
            BlockPos approx = approachPoint(vi.center, otherNodeCenter, Math.max(10, protectR / 2));
            return MPBGenerationJob.findAnchor(world, approx, sampler, 32);
        }

        // Lone buildings: always bypass, otherwise painter will cut inside radius
        BlockPos bypass = approachPoint(nodeCenter, otherNodeCenter, protectR + 6);
        return findOutsideProtected(world, bypass, otherNodeCenter, sampler, protectedAreas, protectR);
    }

    private static boolean gateLooksOnApproachSide(BlockPos center, BlockPos other, BlockPos gate) {
        long gx = (long) gate.getX() - (long) center.getX();
        long gz = (long) gate.getZ() - (long) center.getZ();
        long ox = (long) other.getX() - (long) center.getX();
        long oz = (long) other.getZ() - (long) center.getZ();
        long dot = gx * ox + gz * oz;
        return dot > 0;
    }

    private static BlockPos approachPoint(BlockPos center, BlockPos other, int dist) {
        MillGateRouting.Side s = MillGateRouting.sideToward(center, other);
        int dx = 0, dz = 0;
        switch (s) {
            case EAST:  dx = 1; break;
            case WEST:  dx = -1; break;
            case SOUTH: dz = 1; break;
            case NORTH: dz = -1; break;
        }
        if (dx == 0 && dz == 0) dx = 1;
        return center.add(dx * dist, 0, dz * dist);
    }

    private static BlockPos findOutsideProtected(
            WorldServer world,
            BlockPos approx,
            BlockPos other,
            TerrainSampler sampler,
            @Nullable MPBProtectedAreas protectedAreas,
            int protectR
    ) {
        MillGateRouting.Side s = MillGateRouting.sideToward(approx, other);
        int dx = 0, dz = 0;
        switch (s) {
            case EAST:  dx = 1; break;
            case WEST:  dx = -1; break;
            case SOUTH: dz = 1; break;
            case NORTH: dz = -1; break;
        }
        if (dx == 0 && dz == 0) dx = 1;

        BlockPos cur = approx;
        for (int i = 0; i < 16; i++) {
            BlockPos a = MPBGenerationJob.findAnchor(world, cur, sampler, 24);
            if (protectedAreas == null || !protectedAreas.contains(a.getX(), a.getZ())) return a;
            cur = cur.add(dx * Math.max(4, protectR / 4), 0, dz * Math.max(4, protectR / 4));
        }

        TerrainSampler.Sample sm = sampler.sample(cur.getX(), cur.getZ());
        return new BlockPos(cur.getX(), sm.surfaceY, cur.getZ());
    }
}
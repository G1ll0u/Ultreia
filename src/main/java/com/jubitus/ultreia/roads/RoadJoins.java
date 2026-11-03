package com.jubitus.ultreia.roads;

import com.jubitus.ultreia.bridge.BridgeGeometry;
import com.jubitus.ultreia.config.MPBConfig;
import com.jubitus.ultreia.terrain.ProtectedAreas;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.mill.MillPathPalette;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.List;

public final class RoadJoins {

    private RoadJoins() {}

    @Nullable
    public static BlockPos resolveSnappedApproachAnchor(WorldServer world,
                                                        TerrainSampler sampler,
                                                        @Nullable RoadIndex roadIndex,
                                                        BlockPos originalAnchor,
                                                        BlockPos otherAnchor,
                                                        @Nullable BlockPos snappedRoad,
                                                        @Nullable ProtectedAreas protectedAreas) {
        if (roadIndex == null || snappedRoad == null) return null;

        int minBlocks = Math.max(1, MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeRoadApproachMinBlocks);
        int maxBlocks = Math.max(minBlocks, MPBConfig.BRIDGE_AWARE_PATHFINDING.bridgeRoadApproachMaxBlocks);
        int maxDeltaHalf = Math.max(0, MPBConfig.BRIDGE_AWARE_PATHFINDING.maxRoadJoinDeltaHalfBlocks);

        return findNaturalApproachNearRoad(
                world,
                sampler,
                roadIndex,
                originalAnchor,
                otherAnchor,
                snappedRoad,
                protectedAreas,
                minBlocks,
                maxBlocks,
                maxDeltaHalf
        );
    }

    @Nullable
    private static BlockPos findNaturalApproachNearRoad(WorldServer world,
                                                        TerrainSampler sampler,
                                                        RoadIndex roadIndex,
                                                        BlockPos sourceAnchor,
                                                        BlockPos otherAnchor,
                                                        BlockPos roadPos,
                                                        @Nullable ProtectedAreas protectedAreas,
                                                        int minBlocks,
                                                        int maxBlocks,
                                                        int maxDeltaHalf) {
        Integer roadHalf = findRoadSurfaceHalfY(world, sampler, roadPos.getX(), roadPos.getZ());
        if (roadHalf == null) return null;

        int prefDist = Math.max(minBlocks, Math.min(maxBlocks, minBlocks + 2));

        int vx = sourceAnchor.getX() - roadPos.getX();
        int vz = sourceAnchor.getZ() - roadPos.getZ();

        if (vx == 0 && vz == 0 && otherAnchor != null) {
            vx = roadPos.getX() - otherAnchor.getX();
            vz = roadPos.getZ() - otherAnchor.getZ();
        }
        if (vx == 0 && vz == 0) {
            vx = 1;
        }

        BlockPos best = null;
        double bestScore = Double.POSITIVE_INFINITY;

        for (int r = minBlocks; r <= maxBlocks; r++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;

                    int x = roadPos.getX() + dx;
                    int z = roadPos.getZ() + dz;

                    if (roadIndex.isRoadXZ(x, z)) continue;
                    if (protectedAreas != null && protectedAreas.contains(x, z)) continue;
                    if (!sampler.isNaturalLandEndpoint(x, z)) continue;

                    TerrainSampler.Sample s = sampler.sample(x, z);
                    if (s == null) continue;

                    int groundHalf = naturalSurfaceHalf(s.surfaceY);
                    int deltaHalf = Math.abs(groundHalf - roadHalf);
                    if (deltaHalf > maxDeltaHalf) continue;

                    int sideDot = dx * vx + dz * vz;
                    if (sideDot <= 0) continue;

                    double dist = Math.sqrt((double) dx * (double) dx + (double) dz * (double) dz);
                    double distPenalty = Math.abs(dist - (double) prefDist) * 8.0;
                    double linePenalty = pointToSegmentDistanceSq(
                            x, z,
                            sourceAnchor.getX(), sourceAnchor.getZ(),
                            roadPos.getX(), roadPos.getZ()
                    );
                    double deltaPenalty = (double) deltaHalf * 12.0;

                    double score = distPenalty + linePenalty * 0.35 + deltaPenalty;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new BlockPos(x, s.surfaceY, z);
                    }
                }
            }
        }

        return best;
    }

    public static boolean canMergeCleanlyToRoad(WorldServer world,
                                                TerrainSampler sampler,
                                                @Nullable RoadIndex roadIndex,
                                                int pathX,
                                                int pathY,
                                                int pathZ,
                                                int roadX,
                                                int roadZ,
                                                int maxDeltaHalf) {
        if (roadIndex == null) return false;
        if (!roadIndex.isRoadXZ(roadX, roadZ)) return false;

        Integer roadHalf = findRoadSurfaceHalfY(world, sampler, roadX, roadZ);
        if (roadHalf == null) return false;

        int pathHalf = naturalSurfaceHalf(pathY);
        return Math.abs(pathHalf - roadHalf) <= Math.max(0, maxDeltaHalf);
    }

    @Nullable
    public static Integer findRoadSurfaceHalfY(WorldServer world, TerrainSampler sampler, int x, int z) {
        TerrainSampler.Sample sm = sampler.sample(x, z);
        if (sm == null) return null;

        int yMin = Math.max(1, sm.surfaceY - 3);
        int yMax = Math.min(255, sm.surfaceY + 4);

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int y = yMax; y >= yMin; y--) {
            p.setPos(x, y, z);
            IBlockState st = world.getBlockState(p);
            if (st == null) continue;

            Block b = st.getBlock();
            if (!MillPathPalette.isMillPath(b)) continue;

            // Path slabs (ours and Millénaire's) carry the slab HALF property without extending BlockSlab.
            if (st.getPropertyKeys().contains(BlockSlab.HALF)) {
                return st.getValue(BlockSlab.HALF) == BlockSlab.EnumBlockHalf.TOP ? y * 2 + 2 : y * 2 + 1;
            }

            return y * 2 + 2;
        }

        return null;
    }

    private static int naturalSurfaceHalf(int surfaceY) {
        return BridgeGeometry.naturalSurfaceHalfY(surfaceY);
    }

    private static double pointToSegmentDistanceSq(int px, int pz, int ax, int az, int bx, int bz) {
        long dx = (long) bx - (long) ax;
        long dz = (long) bz - (long) az;
        long lenSq = dx * dx + dz * dz;
        if (lenSq <= 0L) {
            long ex = (long) px - (long) ax;
            long ez = (long) pz - (long) az;
            return (double) ex * (double) ex + (double) ez * (double) ez;
        }

        long apx = (long) px - (long) ax;
        long apz = (long) pz - (long) az;

        double t = (double) (apx * dx + apz * dz) / (double) lenSq;
        if (t < 0.0) t = 0.0;
        else if (t > 1.0) t = 1.0;

        double cx = (double) ax + t * (double) dx;
        double cz = (double) az + t * (double) dz;

        double ddx = (double) px - cx;
        double ddz = (double) pz - cz;
        return ddx * ddx + ddz * ddz;
    }
}
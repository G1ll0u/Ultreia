package com.jubitus.millpathbuilder.gen.mill;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class MillGateRouting {

    private MillGateRouting() {}

    public enum Side { NORTH, SOUTH, WEST, EAST }

    @Nullable
    public static BlockPos pickGateFacing(World world, BlockPos villageCenter, BlockPos targetCenter) {
        List<BlockPos> gates = MillVillageIndex.getVillageGates(world, villageCenter, 96, 64);
        if (gates == null || gates.isEmpty()) return null;

        Side side = sideToward(villageCenter, targetCenter);

        BlockPos best = null;
        int bestScore = Integer.MIN_VALUE;

        int cx = villageCenter.getX();
        int cz = villageCenter.getZ();

        for (BlockPos g : gates) {
            int dx = g.getX() - cx;
            int dz = g.getZ() - cz;

            int score;
            switch (side) {
                case EAST:  score = dx; break;         // want max dx
                case WEST:  score = -dx; break;        // want min dx
                case SOUTH: score = dz; break;         // want max dz
                case NORTH: score = -dz; break;        // want min dz
                default: score = 0;
            }

            if (score > bestScore) {
                bestScore = score;
                best = g;
            }
        }

        if (best != null) return best;

        return nearestGate(world, villageCenter, targetCenter);
    }

    @Nullable
    public static BlockPos nearestGate(World world, BlockPos villageCenter, BlockPos from) {
        List<BlockPos> gates = MillVillageIndex.getVillageGates(world, villageCenter, 96, 64);
        if (gates == null || gates.isEmpty()) return null;

        BlockPos best = null;
        double bestD2 = Double.MAX_VALUE;

        for (BlockPos g : gates) {
            double d2 = g.distanceSq(from);
            if (d2 < bestD2) {
                bestD2 = d2;
                best = g;
            }
        }
        return best;
    }

    public static Side sideToward(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? Side.EAST : Side.WEST;
        } else {
            return dz >= 0 ? Side.SOUTH : Side.NORTH;
        }
    }

    public static List<BlockPos> allVillageGatesOrFallback(World world, BlockPos villageCenter) {
        List<BlockPos> gates = MillVillageIndex.getVillageGates(world, villageCenter, 96, 64);
        if (gates == null || gates.isEmpty()) {
            gates = new ArrayList<>();
            gates.add(villageCenter);
        }
        return gates;
    }
}
package com.jubitus.ultreia.bridge.template;

import com.jubitus.ultreia.bridge.BridgeGeometry;
import com.jubitus.ultreia.geom.HalfY;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/** Template coordinates (forward slice, lateral, relY) and template directions ("north"…) to world terms. */
public final class BridgeLocalFrame {

    private BridgeLocalFrame() {}

    /** A template direction name; unknown or missing names read as "south". */
    public static EnumFacing facingFromName(String name) {
        String s = name == null ? "south" : name.trim().toLowerCase();
        if ("north".equals(s)) return EnumFacing.NORTH;
        if ("west".equals(s)) return EnumFacing.WEST;
        if ("east".equals(s)) return EnumFacing.EAST;
        return EnumFacing.SOUTH;
    }

    public static String nameOf(EnumFacing facing) {
        if (facing == null) return "south";
        switch (facing) {
            case NORTH: return "north";
            case WEST: return "west";
            case EAST: return "east";
            default: return "south";
        }
    }

    public static BridgeGeometry.Dir2 dirOf(EnumFacing facing) {
        return new BridgeGeometry.Dir2(facing.getXOffset(), facing.getZOffset());
    }

    /** The horizontal facing closest to (dx, dz); ties go to north/south. */
    public static EnumFacing snapFromDir(int dx, int dz) {
        if (dx == 0 && dz == 0) return EnumFacing.SOUTH;

        if (Math.abs(dx) > Math.abs(dz)) {
            return dx >= 0 ? EnumFacing.EAST : EnumFacing.WEST;
        }
        if (Math.abs(dz) > Math.abs(dx)) {
            return dz >= 0 ? EnumFacing.SOUTH : EnumFacing.NORTH;
        }

        if (dz != 0) {
            return dz >= 0 ? EnumFacing.SOUTH : EnumFacing.NORTH;
        }
        return dx >= 0 ? EnumFacing.EAST : EnumFacing.WEST;
    }

    /** World position of a template cell placed at centerline index {@code bridgeIdx}. */
    static BlockPos toWorld(List<BlockPos> centerline, int[] deckHalfY, int bridgeIdx, int lateral, int relY) {
        BlockPos center = centerline.get(bridgeIdx);
        BridgeGeometry.Dir2 perp = BridgeGeometry.localDir(centerline, bridgeIdx).perp();
        HalfY.SlabPlacement sp = HalfY.slabPlacement(deckHalfY[bridgeIdx]);

        return new BlockPos(
                center.getX() + perp.dx * lateral,
                sp.blockY + relY,
                center.getZ() + perp.dz * lateral
        );
    }
}

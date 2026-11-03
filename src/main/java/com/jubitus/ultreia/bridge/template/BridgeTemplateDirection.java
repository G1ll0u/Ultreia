package com.jubitus.ultreia.bridge.template;

import net.minecraft.util.EnumFacing;

public final class BridgeTemplateDirection {

    private BridgeTemplateDirection() {
    }

    public static String normalizeName(String name) {
        if (name == null) return "south";
        String s = name.trim().toLowerCase();
        if ("north".equals(s) || "south".equals(s) || "west".equals(s) || "east".equals(s)) {
            return s;
        }
        return "south";
    }

    public static EnumFacing fromName(String name) {
        String s = normalizeName(name);
        if ("north".equals(s)) return EnumFacing.NORTH;
        if ("south".equals(s)) return EnumFacing.SOUTH;
        if ("west".equals(s)) return EnumFacing.WEST;
        return EnumFacing.EAST;
    }

    public static String toName(EnumFacing facing) {
        if (facing == null) return "south";
        switch (facing) {
            case NORTH: return "north";
            case SOUTH: return "south";
            case WEST: return "west";
            case EAST: return "east";
            default: return "south";
        }
    }

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
}
package com.jubitus.ultreia.gen.newp;

import net.minecraft.block.properties.IProperty;
import net.minecraft.block.properties.PropertyBool;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class MPBMillenairePathCompat {

    private MPBMillenairePathCompat() {}

    public static IBlockState stabilizeIfPossible(IBlockState state) {
        if (state == null) return null;

        for (IProperty<?> prop : state.getPropertyKeys()) {
            if (!(prop instanceof PropertyBool)) continue;
            if (!"stable".equals(prop.getName())) continue;

            @SuppressWarnings("unchecked")
            PropertyBool stable = (PropertyBool) prop;

            try {
                Boolean cur = state.getValue(stable);
                if (Boolean.TRUE.equals(cur)) return state;
                return state.withProperty(stable, true);
            } catch (Throwable ignored) {
                return state;
            }
        }

        return state;
    }

    public static boolean isStablePath(IBlockState state) {
        if (state == null) return false;

        for (IProperty<?> prop : state.getPropertyKeys()) {
            if (!(prop instanceof PropertyBool)) continue;
            if (!"stable".equals(prop.getName())) continue;

            @SuppressWarnings("unchecked")
            PropertyBool stable = (PropertyBool) prop;

            try {
                return Boolean.TRUE.equals(state.getValue(stable));
            } catch (Throwable ignored) {
                return false;
            }
        }

        return false;
    }

    public static boolean hasStablePathInColumn(World world, BlockPos center, int dyMin, int dyMax) {
        if (world == null || center == null) return false;

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = dyMin; dy <= dyMax; dy++) {
            p.setPos(center.getX(), center.getY() + dy, center.getZ());
            IBlockState st = world.getBlockState(p);
            if (isStablePath(st)) {
                return true;
            }
        }

        return false;
    }
}
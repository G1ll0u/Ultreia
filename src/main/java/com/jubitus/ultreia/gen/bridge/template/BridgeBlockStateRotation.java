package com.jubitus.ultreia.gen.bridge.template;

import net.minecraft.block.Block;
import net.minecraft.block.BlockRailBase;
import net.minecraft.block.BlockRotatedPillar;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Rotation;

import java.util.Collection;

public final class BridgeBlockStateRotation {

    private BridgeBlockStateRotation() {
    }

    public static IBlockState rotateForPlacement(IBlockState state, BridgeTemplatePlacementContext ctx) {
        if (state == null) return null;
        if (ctx == null || ctx.rotation == null || ctx.rotation == Rotation.NONE) return state;

        Block block = state.getBlock();

        if (block instanceof BlockRailBase) {
            return state;
        }

        IBlockState rotated = state;

        try {
            IBlockState vanilla = rotated.withRotation(ctx.rotation);
            if (vanilla != null) {
                rotated = vanilla;
            }
        } catch (Throwable ignored) {
            try {
                IBlockState vanilla = block.withRotation(rotated, ctx.rotation);
                if (vanilla != null) {
                    rotated = vanilla;
                }
            } catch (Throwable ignored2) {
            }
        }

        rotated = rotateAxisIfPresent(rotated, ctx.rotation);
        return rotated;
    }

    private static IBlockState rotateAxisIfPresent(IBlockState state, Rotation rotation) {
        if (rotation == Rotation.NONE) return state;

        Block block = state.getBlock();

        if (block instanceof BlockRotatedPillar) {
            try {
                EnumFacing.Axis axis = state.getValue(BlockRotatedPillar.AXIS);
                EnumFacing.Axis rotatedAxis = rotateAxis(axis, rotation);
                if (rotatedAxis != axis) {
                    return state.withProperty(BlockRotatedPillar.AXIS, rotatedAxis);
                }
            } catch (Throwable ignored) {
            }
        }

        Collection<IProperty<?>> props = state.getProperties().keySet();
        for (IProperty<?> prop : props) {
            if (prop == null) continue;
            if (!"axis".equals(prop.getName())) continue;

            Comparable<?> raw = state.getValue(prop);
            if (!(raw instanceof EnumFacing.Axis)) continue;

            EnumFacing.Axis axis = (EnumFacing.Axis) raw;
            EnumFacing.Axis rotatedAxis = rotateAxis(axis, rotation);
            if (rotatedAxis == axis) return state;

            try {
                @SuppressWarnings("unchecked")
                IProperty<EnumFacing.Axis> axisProp = (IProperty<EnumFacing.Axis>) prop;
                return state.withProperty(axisProp, rotatedAxis);
            } catch (Throwable ignored) {
                return state;
            }
        }

        return state;
    }

    private static EnumFacing.Axis rotateAxis(EnumFacing.Axis axis, Rotation rotation) {
        if (axis == null) return null;
        if (rotation == Rotation.NONE || rotation == Rotation.CLOCKWISE_180) return axis;

        if (axis == EnumFacing.Axis.X) return EnumFacing.Axis.Z;
        if (axis == EnumFacing.Axis.Z) return EnumFacing.Axis.X;
        return axis;
    }
}
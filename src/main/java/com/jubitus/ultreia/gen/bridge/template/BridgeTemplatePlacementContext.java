package com.jubitus.ultreia.gen.bridge.template;

import com.jubitus.ultreia.gen.bridge.BridgeSpan;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Rotation;

public final class BridgeTemplatePlacementContext {

    public final EnumFacing templateForward;
    public final EnumFacing targetForward;
    public final Rotation rotation;

    public BridgeTemplatePlacementContext(EnumFacing templateForward,
                                          EnumFacing targetForward,
                                          Rotation rotation) {
        this.templateForward = templateForward;
        this.targetForward = targetForward;
        this.rotation = rotation;
    }

    public static BridgeTemplatePlacementContext of(BridgeTemplate template, BridgeSpan span) {
        EnumFacing templateForward = BridgeTemplateDirection.fromName(
                template != null ? template.canonicalForward : "south"
        );

        int dx = 0;
        int dz = 0;
        if (span != null && span.start != null && span.end != null) {
            dx = span.end.getX() - span.start.getX();
            dz = span.end.getZ() - span.start.getZ();
        }

        EnumFacing targetForward = BridgeTemplateDirection.snapFromDir(dx, dz);
        Rotation rotation = computeRotation(templateForward, targetForward);

        return new BridgeTemplatePlacementContext(templateForward, targetForward, rotation);
    }

    private static Rotation computeRotation(EnumFacing from, EnumFacing to) {
        if (from == null || to == null) return Rotation.NONE;
        if (from == to) return Rotation.NONE;

        if (from.rotateY() == to) return Rotation.CLOCKWISE_90;
        if (from.rotateYCCW() == to) return Rotation.COUNTERCLOCKWISE_90;
        if (from.getOpposite() == to) return Rotation.CLOCKWISE_180;

        return Rotation.NONE;
    }
}
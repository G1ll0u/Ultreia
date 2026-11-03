package com.jubitus.ultreia.bridge;

import com.jubitus.ultreia.bridge.template.BridgeTemplate;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;

/** The world reads of bridge feasibility (blocks, settlements, template); lets tests run it on synthetic terrain. */
public interface BridgeSite {

    /** Natural ground (not a path block) at this block. */
    boolean isNaturalGroundOnly(int x, int y, int z);

    /** Natural ground or a path block at this block. */
    boolean isRoadGround(int x, int y, int z);

    /** This block may be cleared for a bridge deck. */
    boolean isBridgeClearable(int x, int y, int z, boolean allowWoodenSlab);

    /** Every block from yFrom to yTo is air or clearable, with no tile entity. */
    boolean isWalkVolumePassable(int x, int z, int yFrom, int yTo, boolean allowWoodenSlab);

    boolean isNearVillage(BlockPos pos, int radius);

    /** Near a village or a lone building. */
    boolean isNearAnySettlement(BlockPos pos, int radius);

    @Nullable
    BridgeTemplate selectTemplate(BridgeSpan span);
}

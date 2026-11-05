package com.jubitus.ultreia.bridge;

import com.jubitus.ultreia.bridge.template.BridgeTemplate;
import com.jubitus.ultreia.bridge.template.BridgeTemplateSelector;
import com.jubitus.ultreia.mill.Villages;
import com.jubitus.ultreia.terrain.BlockRules;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;

/** Bridge feasibility reads on the real world. */
public final class WorldBridgeSite implements BridgeSite {

    private final WorldServer world;
    private final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

    public WorldBridgeSite(WorldServer world) {
        this.world = world;
    }

    @Override
    public boolean isNaturalGroundOnly(int x, int y, int z) {
        return BlockRules.isNaturalGroundOnly(world, p.setPos(x, y, z), world.getBlockState(p));
    }

    @Override
    public boolean isRoadGround(int x, int y, int z) {
        return BlockRules.isRoadReplaceableGround(world, p.setPos(x, y, z), world.getBlockState(p));
    }

    @Override
    public boolean isBridgeClearable(int x, int y, int z, boolean allowWoodenSlab) {
        IBlockState st = world.getBlockState(p.setPos(x, y, z));
        return BlockRules.isBridgeClearable(world, p, st, allowWoodenSlab);
    }

    @Override
    public boolean isWalkVolumePassable(int x, int z, int yFrom, int yTo, boolean allowWoodenSlab) {
        for (int y = yFrom; y <= yTo; y++) {
            p.setPos(x, y, z);
            IBlockState st = world.getBlockState(p);
            if (st.getMaterial() == Material.AIR) continue;
            if (world.getTileEntity(p) != null) return false;
            if (!BlockRules.isBridgeClearable(world, p, st, allowWoodenSlab)) return false;
        }
        return true;
    }

    @Override
    public boolean isNearVillage(BlockPos pos, int radius) {
        return Villages.isNearVillage(world, pos, radius);
    }

    @Override
    public boolean isNearAnySettlement(BlockPos pos, int radius) {
        return Villages.isNearAnySettlement(world, pos, radius);
    }

    @Nullable
    @Override
    public BridgeTemplate selectTemplate(BridgeSpan span) {
        return BridgeTemplateSelector.select(world, span);
    }
}

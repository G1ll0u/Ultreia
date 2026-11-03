package com.jubitus.ultreia.bridge.template;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.ResourceLocation;

public final class BlockStateRef {

    public String block;
    public int meta;

    public BlockStateRef() {
    }

    public BlockStateRef(String block, int meta) {
        this.block = block;
        this.meta = meta;
    }

    public static BlockStateRef of(IBlockState state) {
        if (state == null) {
            return new BlockStateRef("minecraft:air", 0);
        }

        Block b = state.getBlock();
        ResourceLocation id = b.getRegistryName();
        String name = id != null ? id.toString() : "minecraft:air";
        int meta = 0;

        try {
            meta = b.getMetaFromState(state);
        } catch (Throwable ignored) {
        }

        return new BlockStateRef(name, meta);
    }

    public IBlockState toState() {
        try {
            Block b = (Block) Block.REGISTRY.getObject(new ResourceLocation(block));
            if (b == null) {
                return Blocks.AIR.getDefaultState();
            }
            return b.getStateFromMeta(meta);
        } catch (Throwable ignored) {
            return Blocks.AIR.getDefaultState();
        }
    }

    public boolean matches(IBlockState state) {
        if (state == null) return false;
        Block b = state.getBlock();
        ResourceLocation id = b.getRegistryName();
        if (id == null) return false;
        if (!id.toString().equals(this.block)) return false;

        int otherMeta = 0;
        try {
            otherMeta = b.getMetaFromState(state);
        } catch (Throwable ignored) {
        }
        return otherMeta == this.meta;
    }

    @Override
    public String toString() {
        return block + "@" + meta;
    }
}
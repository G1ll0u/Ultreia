package com.jubitus.ultreia.job;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ManualPathSelection {

    public static final class PlayerState {
        public int dimension;
        @Nullable public BlockPos pos1;
        @Nullable public BlockPos pos2;

        private PlayerState(int dimension) {
            this.dimension = dimension;
        }
    }

    private static final ConcurrentHashMap<UUID, PlayerState> STATES = new ConcurrentHashMap<>();

    private ManualPathSelection() {}

    public static PlayerState get(EntityPlayerMP player) {
        UUID id = player.getUniqueID();
        PlayerState state = STATES.get(id);
        if (state == null) {
            state = new PlayerState(player.dimension);
            STATES.put(id, state);
        }
        return state;
    }

    public static PlayerState getForDimension(EntityPlayerMP player, int dimension) {
        PlayerState state = get(player);
        if (state.dimension != dimension) {
            state.dimension = dimension;
            state.pos1 = null;
            state.pos2 = null;
        }
        return state;
    }

    public static void setPos1(EntityPlayerMP player, int dimension, BlockPos pos) {
        PlayerState state = getForDimension(player, dimension);
        state.pos1 = pos;
    }

    public static void setPos2(EntityPlayerMP player, int dimension, BlockPos pos) {
        PlayerState state = getForDimension(player, dimension);
        state.pos2 = pos;
    }

    public static void reset(EntityPlayerMP player, int dimension) {
        PlayerState state = getForDimension(player, dimension);
        state.pos1 = null;
        state.pos2 = null;
    }
}
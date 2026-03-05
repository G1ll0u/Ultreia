package com.jubitus.millpathbuilder.gen.world;

import net.minecraft.world.World;
import net.minecraftforge.common.ForgeChunkManager;

import java.util.List;

public final class MPBChunkLoadingCallback implements ForgeChunkManager.LoadingCallback {

    @Override
    public void ticketsLoaded(List<ForgeChunkManager.Ticket> tickets, World world) {
        // We do not use persistent tickets; release anything that got restored.
        if (tickets == null) return;
        for (ForgeChunkManager.Ticket t : tickets) {
            try {
                ForgeChunkManager.releaseTicket(t);
            } catch (Throwable ignored) {
            }
        }
    }
}
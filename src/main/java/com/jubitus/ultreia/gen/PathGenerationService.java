package com.jubitus.ultreia.gen;

import net.minecraft.command.ICommandSender;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

public final class PathGenerationService {

    private PathGenerationService() {}

    public static void generate(WorldServer world, ICommandSender sender, boolean force, boolean dryRun) {
        MPBGenerationManager.start(world, sender, force, dryRun);
    }
    public static void generateCustom(WorldServer world, ICommandSender sender, BlockPos start, BlockPos goal, boolean dryRun, RoadType roadType) {
        MPBGenerationManager.startCustom(world, sender, start, goal, dryRun, roadType);
    }
}
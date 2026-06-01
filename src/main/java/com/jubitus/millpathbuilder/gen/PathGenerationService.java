package com.jubitus.millpathbuilder.gen;

import com.jubitus.millpathbuilder.MillPathBuilderMod;
import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.integration.MillIntegration;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

import java.util.List;

import net.minecraft.command.ICommandSender;
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
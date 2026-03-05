package com.jubitus.millpathbuilder.command;

import com.jubitus.millpathbuilder.gen.MPBGenerationManager;
import com.jubitus.millpathbuilder.gen.MPBWorldData;
import com.jubitus.millpathbuilder.gen.PathGenerationService;
import com.jubitus.millpathbuilder.integration.MillIntegration;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class CommandMillPathBuilder extends CommandBase {

    @Override
    public String getName() {
        return "millpathbuilder";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/millpathbuilder <generate|status|clear> [--force] [--dryrun]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        WorldServer world = server.getWorld(0);
        if (sender.getEntityWorld() instanceof WorldServer) {
            world = (WorldServer) sender.getEntityWorld();
        }

        if (args.length == 0) {
            sender.sendMessage(new TextComponentString(getUsage(sender)));
            return;
        }

        String sub = args[0].toLowerCase();
        Set<String> flags = new HashSet<>(Arrays.asList(args));

        switch (sub) {
            case "status": {
                MillIntegration.logNodeSummary(world);
                MPBWorldData data = MPBWorldData.get(world);
                sender.sendMessage(new TextComponentString("Nodes: " + MillIntegration.getAllNodePositions(world).size()));
                sender.sendMessage(new TextComponentString("Built edges: " + data.getBuiltEdgeCount()));
                sender.sendMessage(new TextComponentString("Job: " + MPBGenerationManager.status(world)));
                return;
            }
            case "clear": {
                MPBGenerationManager.cancel(world);
                MPBWorldData data = MPBWorldData.get(world);
                data.clear();
                data.markDirty();
                sender.sendMessage(new TextComponentString("MillPathBuilder data cleared and job cancelled for this dimension."));
                return;
            }
            case "generate": {
                boolean force = flags.contains("--force");
                boolean dryRun = flags.contains("--dryrun");

                sender.sendMessage(new TextComponentString("MillPathBuilder: generation queued (force=" + force + ", dryrun=" + dryRun + ")"));
                PathGenerationService.generate(world, sender, force, dryRun);
                return;
            }
            default:
                sender.sendMessage(new TextComponentString(getUsage(sender)));
        }
    }
}
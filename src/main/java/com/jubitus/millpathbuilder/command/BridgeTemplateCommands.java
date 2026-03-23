package com.jubitus.millpathbuilder.command;

import com.jubitus.millpathbuilder.gen.bridge.template.*;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

public final class BridgeTemplateCommands {

    private BridgeTemplateCommands() {
    }

    public static void handle(WorldServer world, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            sender.sendMessage(new TextComponentString(usage()));
            return;
        }

        String sub = args[1].toLowerCase();

        if ("create".equals(sub)) {
            if (args.length < 3) {
                throw new CommandException("Missing bridge template name.");
            }

            EntityPlayerMP player = requirePlayer(sender);
            String name = args[2];

            BridgeTemplateAnalyzer.CreateResult result = BridgeTemplateAnalyzer.createNearestTemplate(world, player.getPosition(), name);
            if (!result.ok || result.template == null) {
                throw new CommandException(result.message);
            }

            try {
                BridgeTemplateRegistry.saveTemplate(result.template);
            } catch (Throwable t) {
                throw new CommandException("Failed to save bridge template: " + t.getMessage());
            }

            sender.sendMessage(new TextComponentString(result.message));
            sender.sendMessage(new TextComponentString("Saved to: " + BridgeTemplateRegistry.getTemplateFile(result.template.name).getAbsolutePath()));
            return;
        }

        if ("set".equals(sub)) {
            if (args.length < 3) {
                throw new CommandException("Missing bridge template name.");
            }

            String name = args[2];
            if (!BridgeTemplateRegistry.setActiveTemplate(name)) {
                throw new CommandException("Bridge template not found: " + BridgeTemplateRegistry.sanitizeName(name));
            }

            sender.sendMessage(new TextComponentString("Active bridge template set to: " + BridgeTemplateRegistry.sanitizeName(name)));
            return;
        }

        if ("export".equals(sub)) {
            EntityPlayerMP player = requirePlayer(sender);

            BridgeDebugExporter.ExportResult result = BridgeDebugExporter.exportNearestBridge(world, player.getPosition());
            if (!result.ok || result.file == null) {
                throw new CommandException(result.message);
            }

            sender.sendMessage(new TextComponentString(result.message));
            sender.sendMessage(new TextComponentString("Saved to: " + result.file.getAbsolutePath()));
            return;
        }
        if ("import".equals(sub)) {
            if (args.length < 3) {
                throw new CommandException("Missing bridge template name.");
            }

            EntityPlayerMP player = requirePlayer(sender);
            String name = args[2];

            BridgeTemplate template = BridgeTemplateRegistry.loadTemplate(name);
            if (template == null) {
                throw new CommandException("Bridge template not found: " + BridgeTemplateRegistry.sanitizeName(name));
            }

            BridgeTemplateImporter.ImportResult result = BridgeTemplateImporter.importUnderPlayer(
                    world,
                    player.getPosition(),
                    template
            );

            if (!result.ok) {
                throw new CommandException(result.message);
            }

            sender.sendMessage(new TextComponentString(result.message));
            return;
        }

        sender.sendMessage(new TextComponentString(usage()));
    }

    private static EntityPlayerMP requirePlayer(ICommandSender sender) throws CommandException {
        if (!(sender.getCommandSenderEntity() instanceof EntityPlayerMP)) {
            throw new CommandException("This bridge command must be run by a player.");
        }
        return (EntityPlayerMP) sender.getCommandSenderEntity();
    }

    private static String usage() {
        return "/millpathbuilder bridge <create <name>|set <name>|export|import <name>>";
    }
}
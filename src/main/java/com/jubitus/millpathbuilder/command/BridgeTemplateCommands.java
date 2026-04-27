package com.jubitus.millpathbuilder.command;

import com.jubitus.millpathbuilder.Tags;
import com.jubitus.millpathbuilder.gen.bridge.template.*;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;

public final class BridgeTemplateCommands {

    private BridgeTemplateCommands() {}

    public static void handle(WorldServer world, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            sender.sendMessage(new TextComponentString(usage()));
            return;
        }

        String sub = args[1].toLowerCase();

        if ("create".equals(sub)) {
            if (args.length < 3) throw new CommandException("Missing bridge template name.");

            EntityPlayerMP player = requirePlayer(sender);
            String name = args[2];

            BridgeTemplateAnalyzer.CreateResult result = BridgeTemplateAnalyzer.createNearestTemplate(world, player.getPosition(), name);
            if (!result.ok || result.template == null) throw new CommandException(result.message);

            try {
                BridgeTemplateRegistry.saveTemplate(result.template);
            } catch (Throwable t) {
                throw new CommandException("Failed to save bridge template: " + t.getMessage());
            }

            BridgeTemplateRegistry.invalidateTemplateCache();
            sender.sendMessage(new TextComponentString(result.message));
            sender.sendMessage(new TextComponentString("Saved to: " + BridgeTemplateRegistry.getTemplateFile(result.template.name).getAbsolutePath()));
            return;
        }

        if ("set".equals(sub)) {
            if (args.length < 3) throw new CommandException("Missing bridge template name.");

            String name = args[2];
            if (!BridgeTemplateRegistry.setDefaultTemplate(name)) {
                throw new CommandException("Bridge template not found: " + BridgeTemplateRegistry.sanitizeName(name));
            }

            sender.sendMessage(new TextComponentString("Default bridge template set to: " + BridgeTemplateRegistry.sanitizeName(name)));
            return;
        }

        if ("export".equals(sub)) {
            EntityPlayerMP player = requirePlayer(sender);

            BridgeDebugExporter.ExportResult result = BridgeDebugExporter.exportNearestBridge(world, player.getPosition());
            if (!result.ok || result.file == null) throw new CommandException(result.message);

            sender.sendMessage(new TextComponentString(result.message));
            sender.sendMessage(new TextComponentString("Saved to: " + result.file.getAbsolutePath()));
            return;
        }

        if ("import".equals(sub)) {
            if (args.length < 3) throw new CommandException("Missing bridge template name.");

            EntityPlayerMP player = requirePlayer(sender);
            String name = args[2];

            BridgeTemplate template = BridgeTemplateRegistry.loadTemplate(name);
            if (template == null) {
                throw new CommandException("Bridge template not found: " + BridgeTemplateRegistry.sanitizeName(name));
            }

            BridgeTemplateImporter.ImportResult result = BridgeTemplateImporter.importUnderPlayer(world, player.getPosition(), template);
            if (!result.ok) throw new CommandException(result.message);

            sender.sendMessage(new TextComponentString(result.message));
            return;
        }

        if ("reloadconfig".equals(sub)) {
            ConfigManager.sync(Tags.MOD_ID, Config.Type.INSTANCE);
            BridgeTemplateRegistry.invalidateTemplateCache();
            sender.sendMessage(new TextComponentString("Bridge templates reloaded from disk."));
            return;
        }

        if ("validate".equals(sub)) {
            boolean hasIssues = false;
            for (BridgeTemplate t : BridgeTemplateRegistry.loadAllTemplates()) {
                BridgeTemplateValidator.ValidationResult result = BridgeTemplateValidator.validate(t);
                if (result.hasErrors()) {
                    for (String e : result.errors) {
                        sender.sendMessage(new TextComponentString("[ERROR] " + t.name + ": " + e));
                    }
                    hasIssues = true;
                }
                if (result.hasWarnings()) {
                    for (String w : result.warnings) {
                        sender.sendMessage(new TextComponentString("[WARN]  " + t.name + ": " + w));
                    }
                    hasIssues = true;
                }
            }
            if (!hasIssues) {
                sender.sendMessage(new TextComponentString("All bridge templates are valid."));
            }
            return;
        }

        if ("list".equals(sub)) {
            java.util.List<String> names = BridgeTemplateRegistry.listTemplateNames();
            if (names.isEmpty()) {
                sender.sendMessage(new TextComponentString("No bridge templates found."));
            } else {
                sender.sendMessage(new TextComponentString("Bridge templates: " + String.join(", ", names)));
            }
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
        return "/millpathbuilder bridge <create <name>|set <name>|export|import <name>|validate|list|reloadconfig>";
    }
}

package com.jubitus.ultreia.command;

import com.jubitus.ultreia.bridge.template.BridgeDebugExporter;
import com.jubitus.ultreia.bridge.template.BridgeTemplate;
import com.jubitus.ultreia.bridge.template.BridgeTemplateAnalyzer;
import com.jubitus.ultreia.bridge.template.BridgeTemplateImporter;
import com.jubitus.ultreia.bridge.template.BridgeTemplateRegistry;
import com.jubitus.ultreia.bridge.template.BridgeTemplateValidator;
import com.jubitus.jubiconfig.JubiConfig;

import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static com.jubitus.ultreia.command.CommandUltreia.requirePlayer;
import static com.jubitus.ultreia.command.CommandUltreia.say;

/** /ultreia bridge: capture, update, export and import bridge templates, set the default, validate, list, reload. */
final class BridgeTemplateCommands {

    static final List<String> SUBS = Arrays.asList(
            "create", "update", "set", "export", "import", "validate", "list", "reloadconfig");

    private BridgeTemplateCommands() {}

    static void handle(WorldServer world, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 2) {
            throw new WrongUsageException("ultreia.command.bridge.usage");
        }

        String sub = args[1].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "create": {
                String name = requireName(args);
                captureTemplate(world, sender, requirePlayer(sender, "bridge create"), name, null);
                return;
            }

            case "update": {
                String name = requireName(args);
                EntityPlayerMP player = requirePlayer(sender, "bridge update");
                BridgeTemplate existing = BridgeTemplateRegistry.loadTemplate(name);
                if (existing == null) {
                    throw new CommandException("ultreia.command.bridge.not_found", BridgeTemplateRegistry.sanitizeName(name));
                }
                captureTemplate(world, sender, player, name, existing);
                return;
            }

            case "set": {
                String name = requireName(args);
                if (!BridgeTemplateRegistry.setDefaultTemplate(name)) {
                    throw new CommandException("ultreia.command.bridge.not_found", BridgeTemplateRegistry.sanitizeName(name));
                }
                say(sender, "ultreia.command.bridge.default_set", BridgeTemplateRegistry.sanitizeName(name));
                return;
            }

            case "export": {
                EntityPlayerMP player = requirePlayer(sender, "bridge export");
                BridgeDebugExporter.ExportResult result = BridgeDebugExporter.exportNearestBridge(world, player.getPosition());
                if (!result.ok || result.file == null) throw new CommandException(result.message);

                sender.sendMessage(new TextComponentString(result.message));
                say(sender, "ultreia.command.bridge.saved", result.file.getAbsolutePath());
                return;
            }

            case "import": {
                String name = requireName(args);
                EntityPlayerMP player = requirePlayer(sender, "bridge import");
                BridgeTemplate template = BridgeTemplateRegistry.loadTemplate(name);
                if (template == null) {
                    throw new CommandException("ultreia.command.bridge.not_found", BridgeTemplateRegistry.sanitizeName(name));
                }

                BridgeTemplateImporter.ImportResult result = BridgeTemplateImporter.importUnderPlayer(world, player.getPosition(), template);
                if (!result.ok) throw new CommandException(result.message);

                sender.sendMessage(new TextComponentString(result.message));
                return;
            }

            case "reloadconfig":
                JubiConfig.applyOnStartup();
                BridgeTemplateRegistry.invalidateTemplateCache();
                say(sender, "ultreia.command.bridge.reloaded");
                return;

            case "validate": {
                boolean hasIssues = false;
                for (BridgeTemplate t : BridgeTemplateRegistry.loadAllTemplates()) {
                    BridgeTemplateValidator.ValidationResult result = BridgeTemplateValidator.validate(t);
                    for (String e : result.errors) {
                        say(sender, "ultreia.command.bridge.error", t.name, e);
                        hasIssues = true;
                    }
                    for (String w : result.warnings) {
                        say(sender, "ultreia.command.bridge.warn", t.name, w);
                        hasIssues = true;
                    }
                }
                if (!hasIssues) {
                    say(sender, "ultreia.command.bridge.all_valid");
                }
                return;
            }

            case "list": {
                List<String> names = BridgeTemplateRegistry.listTemplateNames();
                if (names.isEmpty()) {
                    say(sender, "ultreia.command.bridge.none");
                } else {
                    say(sender, "ultreia.command.bridge.list", String.join(", ", names));
                }
                return;
            }

            default:
                throw new WrongUsageException("ultreia.command.bridge.usage");
        }
    }

    private static String requireName(String[] args) throws CommandException {
        if (args.length < 3) throw new CommandException("ultreia.command.bridge.missing_name");
        return args[2];
    }

    /**
     * Captures the bridge nearest the player as template {@code name} and saves it. When {@code existing} is given
     * (update), its spawn conditions and per-template overrides are kept.
     */
    private static void captureTemplate(WorldServer world, ICommandSender sender, EntityPlayerMP player, String name,
                                        @Nullable BridgeTemplate existing) throws CommandException {
        BridgeTemplateAnalyzer.CreateResult result = BridgeTemplateAnalyzer.createNearestTemplate(world, player.getPosition(), name);
        if (!result.ok || result.template == null) throw new CommandException(result.message);

        BridgeTemplate template = result.template;
        if (existing != null) {
            template.biomes = existing.biomes;
            template.biomeTemperatureCategories = existing.biomeTemperatureCategories;
            template.biomeDictionaryTypes = existing.biomeDictionaryTypes;
            template.waterOnly = existing.waterOnly;
            template.minLength = existing.minLength;
            template.maxLength = existing.maxLength;
            template.bridgeCrownHalfBlocks = existing.bridgeCrownHalfBlocks;
            template.bridgeCrownMinLenBlocks = existing.bridgeCrownMinLenBlocks;
            template.maxSupportDepth = existing.maxSupportDepth;
        }

        try {
            BridgeTemplateRegistry.saveTemplate(template);
        } catch (Throwable t) {
            throw new CommandException("ultreia.command.bridge.save_failed", t.getMessage());
        }

        BridgeTemplateRegistry.invalidateTemplateCache();
        sender.sendMessage(new TextComponentString(result.message));
        say(sender, existing != null ? "ultreia.command.bridge.updated" : "ultreia.command.bridge.saved",
                BridgeTemplateRegistry.getTemplateFile(template.name).getAbsolutePath());
    }
}

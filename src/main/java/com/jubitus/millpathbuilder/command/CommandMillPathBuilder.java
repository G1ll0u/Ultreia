package com.jubitus.millpathbuilder.command;

import com.jubitus.millpathbuilder.Tags;
import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.*;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import com.jubitus.millpathbuilder.gen.bridge.template.BridgeTemplateRegistry;
import com.jubitus.millpathbuilder.gen.util.MPBPos2D;
import com.jubitus.millpathbuilder.integration.MillIntegration;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.*;

public final class CommandMillPathBuilder extends CommandBase {

    private static final List<String> SUBS = Arrays.asList(
            "pos1",
            "pos2",
            "generate",
            "generatecustom",
            "status",
            "reset",
            "cancel",
            "reloadconfig"
    );

    @Override
    public String getName() {
        return "millpathbuilder";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/millpathbuilder <pos1|pos2|generate|generatecustom|status|reset|cancel>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("mpb");
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length <= 0) {
            throw new WrongUsageException(getUsage(sender));
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        WorldServer world = sender.getEntityWorld() instanceof WorldServer
                ? (WorldServer) sender.getEntityWorld()
                : server.getWorld(0);

        EntityPlayerMP player = sender instanceof EntityPlayerMP ? (EntityPlayerMP) sender : null;
        int dim = world.provider.getDimension();

        if ("bridge".equals(sub)) {
            BridgeTemplateCommands.handle(world, sender, args);
            return;
        }

        if ("pos1".equals(sub)) {
            if (player == null) {
                sender.sendMessage(new TextComponentString("MillPathBuilder: pos1 can only be used by a player."));
                return;
            }

            BlockPos pos = resolveCommandPos(world, player, args);
            MPBManualPathState.setPos1(player, dim, pos);
            sender.sendMessage(new TextComponentString("MillPathBuilder: pos1 = " + formatPos(pos) + " in dim " + dim));
            return;
        }

        if ("pos2".equals(sub)) {
            if (player == null) {
                sender.sendMessage(new TextComponentString("MillPathBuilder: pos2 can only be used by a player."));
                return;
            }

            BlockPos pos = resolveCommandPos(world, player, args);
            MPBManualPathState.setPos2(player, dim, pos);
            sender.sendMessage(new TextComponentString("MillPathBuilder: pos2 = " + formatPos(pos) + " in dim " + dim));
            return;
        }

        if ("generate".equals(sub)) {
            PathGenerationService.generate(world, sender, false, false);
            return;
        }

        if ("generatecustom".equals(sub)) {
            if (player == null) {
                sender.sendMessage(new TextComponentString("MillPathBuilder: generatecustom can only be used by a player."));
                return;
            }

            MPBManualPathState.PlayerState state = MPBManualPathState.getForDimension(player, dim);

            if (state.pos1 == null) {
                sender.sendMessage(new TextComponentString("MillPathBuilder: pos1 is not set. Use /millpathbuilder pos1"));
                return;
            }
            if (state.pos2 == null) {
                sender.sendMessage(new TextComponentString("MillPathBuilder: pos2 is not set. Use /millpathbuilder pos2"));
                return;
            }
            if (MPBPos2D.sameXZ(state.pos1, state.pos2)) {
                sender.sendMessage(new TextComponentString("MillPathBuilder: pos1 and pos2 are the same."));
                return;
            }

            PathGenerationService.generateCustom(world, sender, state.pos1, state.pos2, false);
            return;
        }

        if ("status".equals(sub)) {
            if (player != null) {
                MPBManualPathState.PlayerState state = MPBManualPathState.getForDimension(player, dim);

                String pos1 = state.pos1 == null ? "<unset>" : formatPos(state.pos1);
                String pos2 = state.pos2 == null ? "<unset>" : formatPos(state.pos2);

                sender.sendMessage(new TextComponentString("MillPathBuilder: dim=" + dim + " pos1=" + pos1 + " pos2=" + pos2));
            } else {
                sender.sendMessage(new TextComponentString("MillPathBuilder: dim=" + dim + " console sender"));
            }

            sender.sendMessage(new TextComponentString("MillPathBuilder job: " + MPBGenerationManager.status(world)));
            return;
        }

        if ("reset".equals(sub)) {
            if (player == null) {
                sender.sendMessage(new TextComponentString("MillPathBuilder: reset can only be used by a player."));
                return;
            }

            MPBManualPathState.reset(player, dim);
            sender.sendMessage(new TextComponentString("MillPathBuilder: pos1 and pos2 cleared for dim " + dim));
            return;
        }

        if ("cancel".equals(sub)) {
            MPBGenerationManager.cancel(world);
            sender.sendMessage(new TextComponentString("MillPathBuilder: cancelled active generation in dim " + dim));
            return;
        }

        if ("reloadconfig".equals(sub)) {
            ConfigManager.sync(Tags.MOD_ID, Config.Type.INSTANCE);
            sender.sendMessage(new TextComponentString("MillPathBuilder: config reloaded from disk."));
            return;
        }

        throw new WrongUsageException(getUsage(sender));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBS);
        }
        if (args.length >= 2 && "bridge".equals(args[0].toLowerCase(Locale.ROOT))) {
            if (args.length == 2) {
                return getListOfStringsMatchingLastWord(args, Arrays.asList("create", "set", "export", "import"));
            }

            if (args.length == 3) {
                String sub = args[1].toLowerCase(Locale.ROOT);
                if ("set".equals(sub) || "import".equals(sub)) {
                    return getListOfStringsMatchingLastWord(args, BridgeTemplateRegistry.listTemplateNames());
                }
            }

            return Collections.emptyList();
        }

        if (args.length >= 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("pos1".equals(sub) || "pos2".equals(sub)) {
                BlockPos pos = sender.getPosition();

                if (args.length == 2) {
                    return getListOfStringsMatchingLastWord(args, Collections.singletonList(Integer.toString(pos.getX())));
                }

                if (args.length == 3) {
                    return getListOfStringsMatchingLastWord(args, Collections.singletonList(Integer.toString(pos.getZ())));
                }
            }
        }

        return Collections.emptyList();
    }

    private static BlockPos resolveCommandPos(WorldServer world, EntityPlayerMP player, String[] args) throws CommandException {
        if (args.length == 1) {
            return resolveAnchor(world, player.getPosition().getX(), player.getPosition().getZ());
        }

        if (args.length == 3) {
            int x = parseInt(args[1]);
            int z = parseInt(args[2]);
            return resolveAnchor(world, x, z);
        }

        throw new WrongUsageException("/millpathbuilder " + args[0] + " [x z]");
    }

    private static BlockPos resolveAnchor(WorldServer world, int x, int z) {
        TerrainSampler sampler = new TerrainSampler(world);
        try {
            return MPBGenerationJob.findAnchor(world, new BlockPos(x, 0, z), sampler, 16);
        } finally {
            sampler.clearCache();
        }
    }

    private static String formatPos(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}
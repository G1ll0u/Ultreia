package com.jubitus.ultreia.command;

import com.jubitus.ultreia.job.GenerationManager;
import com.jubitus.ultreia.job.ManualPathSelection;
import com.jubitus.ultreia.job.VillageNetworkJob;
import com.jubitus.jubiconfig.network.MsgOpenConfigGui;
import com.jubitus.ultreia.gen.*;
import com.jubitus.ultreia.gen.bridge.template.BridgeTemplateRegistry;
import com.jubitus.ultreia.gen.util.MPBPos2D;
import com.jubitus.ultreia.gen.world.MPBRoadNetwork;
import com.jubitus.ultreia.gen.world.RoadSegment;
import com.jubitus.ultreia.gen.world.RoadWaypoint;
import com.jubitus.ultreia.integration.MillIntegration;
import com.jubitus.ultreia.network.MPBNetworkHandler;
import com.jubitus.ultreia.network.PacketOpenMapGui;
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

public final class CommandUltreia extends CommandBase {

    private static final List<String> SUBS = Arrays.asList(
            "pos1",
            "pos2",
            "generate",
            "generatecustom",
            "config",
            "gui",
            "status",
            "reset",
            "cancel",
            "bridge"
    );

    @Override
    public String getName() {
        return "ultreia";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/ultreia <pos1|pos2|generate|generatecustom|status|reset|cancel" +
               "/ultreia bridge <create <name>|export|import <name>|validate|list|reloadconfig>";
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
                sender.sendMessage(new TextComponentString("Ultreïa: pos1 can only be used by a player."));
                return;
            }

            BlockPos pos = resolveCommandPos(world, player, args);
            ManualPathSelection.setPos1(player, dim, pos);
            sender.sendMessage(new TextComponentString("Ultreïa: pos1 = " + formatPos(pos) + " in dim " + dim));
            return;
        }

        if ("pos2".equals(sub)) {
            if (player == null) {
                sender.sendMessage(new TextComponentString("Ultreïa: pos2 can only be used by a player."));
                return;
            }

            BlockPos pos = resolveCommandPos(world, player, args);
            ManualPathSelection.setPos2(player, dim, pos);
            sender.sendMessage(new TextComponentString("Ultreïa: pos2 = " + formatPos(pos) + " in dim " + dim));
            return;
        }

        if ("generate".equals(sub)) {
            GenerationManager.start(world, sender, false, false);
            return;
        }

        if ("generatecustom".equals(sub)) {
            if (player == null) {
                sender.sendMessage(new TextComponentString("Ultreïa: generatecustom can only be used by a player."));
                return;
            }

            ManualPathSelection.PlayerState state = ManualPathSelection.getForDimension(player, dim);

            if (state.pos1 == null) {
                sender.sendMessage(new TextComponentString("Ultreïa: pos1 is not set. Use /ultreia pos1"));
                return;
            }
            if (state.pos2 == null) {
                sender.sendMessage(new TextComponentString("Ultreïa: pos2 is not set. Use /ultreia pos2"));
                return;
            }
            if (MPBPos2D.sameXZ(state.pos1, state.pos2)) {
                sender.sendMessage(new TextComponentString("Ultreïa: pos1 and pos2 are the same."));
                return;
            }

            RoadType roadType = RoadType.VILLAGE_MAIN;
            if (args.length >= 2) {
                String typeArg = args[1].toUpperCase(Locale.ROOT);
                if ("LONE_TRAIL".equals(typeArg)) {
                    roadType = RoadType.LONE_TRAIL;
                } else if (!"VILLAGE_MAIN".equals(typeArg)) {
                    sender.sendMessage(new TextComponentString("Ultreïa: unknown road type '" + args[1] + "'. Use VILLAGE_MAIN or LONE_TRAIL."));
                    return;
                }
            }

            GenerationManager.startCustom(world, sender, state.pos1, state.pos2, false, roadType);
            return;
        }

        if ("gui".equals(sub)) {
            if (player == null) {
                sender.sendMessage(new TextComponentString("Ultreïa: gui can only be used by a player."));
                return;
            }
            int guiRadius = 2000;
            if (args.length >= 2) {
                try {
                    guiRadius = Math.max(1, Math.min(8000, parseInt(args[1])));
                } catch (CommandException ignored) {
                    sender.sendMessage(new TextComponentString("Ultreïa: invalid radius — using 2000."));
                }
            }
            BlockPos ppos = player.getPosition();
            int px = ppos.getX(), pz = ppos.getZ();
            MPBRoadNetwork network = MPBRoadNetwork.get(world);
            List<RoadSegment> nearby = network.getSegmentsNear(px, pz, guiRadius);
            List<PacketOpenMapGui.SegmentData> segData = new ArrayList<>();
            for (RoadSegment seg : nearby) {
                int fromX = seg.fromVillage != null ? seg.fromVillage.getX() : PacketOpenMapGui.ABSENT;
                int fromZ = seg.fromVillage != null ? seg.fromVillage.getZ() : PacketOpenMapGui.ABSENT;
                int toX   = seg.toVillage   != null ? seg.toVillage.getX()   : PacketOpenMapGui.ABSENT;
                int toZ   = seg.toVillage   != null ? seg.toVillage.getZ()   : PacketOpenMapGui.ABSENT;
                int wc = seg.waypoints.size();
                int[] wp = new int[wc * 3];
                int wi = 0;
                for (RoadWaypoint wpt : seg.waypoints) {
                    wp[wi++] = wpt.x; wp[wi++] = wpt.y; wp[wi++] = wpt.z;
                }
                segData.add(new PacketOpenMapGui.SegmentData(seg.id, seg.isBridge, fromX, fromZ, toX, toZ, wp));
            }
            MPBNetworkHandler.CHANNEL.sendTo(new PacketOpenMapGui(px, pz, guiRadius, segData), player);
            sender.sendMessage(new TextComponentString(
                    "§7[MPB] Opening map GUI — " + nearby.size() + " segment(s) in radius " + guiRadius + "..."));
            return;
        }

        if ("config".equals(sub)) {
            if (player == null) {
                sender.sendMessage(new TextComponentString("Ultreïa: config GUI can only be opened by a player."));
                return;
            }
            MsgOpenConfigGui.open(player, false);
            return;
        }

        if ("status".equals(sub)) {
            if (player != null) {
                ManualPathSelection.PlayerState state = ManualPathSelection.getForDimension(player, dim);

                String pos1 = state.pos1 == null ? "<unset>" : formatPos(state.pos1);
                String pos2 = state.pos2 == null ? "<unset>" : formatPos(state.pos2);

                sender.sendMessage(new TextComponentString("Ultreïa: dim=" + dim + " pos1=" + pos1 + " pos2=" + pos2));
            } else {
                sender.sendMessage(new TextComponentString("Ultreïa: dim=" + dim + " console sender"));
            }

            sender.sendMessage(new TextComponentString("Ultreïa job: " + GenerationManager.status(world)));
            return;
        }

        if ("reset".equals(sub)) {
            if (player == null) {
                sender.sendMessage(new TextComponentString("Ultreïa: reset can only be used by a player."));
                return;
            }

            ManualPathSelection.reset(player, dim);
            sender.sendMessage(new TextComponentString("Ultreïa: pos1 and pos2 cleared for dim " + dim));
            return;
        }

        if ("cancel".equals(sub)) {
            GenerationManager.cancel(world);
            sender.sendMessage(new TextComponentString("Ultreïa: cancelled active generation in dim " + dim));
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
                return getListOfStringsMatchingLastWord(args, Arrays.asList(
                        "create", "update", "set", "export", "import", "validate", "list", "reloadconfig"));
            }

            if (args.length == 3) {
                String sub = args[1].toLowerCase(Locale.ROOT);
                if ("set".equals(sub) || "import".equals(sub) || "update".equals(sub)) {
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

            if ("generatecustom".equals(sub) && args.length == 2) {
                return getListOfStringsMatchingLastWord(args, Arrays.asList("VILLAGE_MAIN", "LONE_TRAIL"));
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

        throw new WrongUsageException("/ultreia " + args[0] + " [x z]");
    }

    private static BlockPos resolveAnchor(WorldServer world, int x, int z) {
        TerrainSampler sampler = new TerrainSampler(world);
        try {
            return VillageNetworkJob.findAnchor(world, new BlockPos(x, 0, z), sampler, 16);
        } finally {
            sampler.clearCache();
        }
    }

    private static String formatPos(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}
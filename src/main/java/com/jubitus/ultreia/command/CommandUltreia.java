package com.jubitus.ultreia.command;

import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.route.RoadType;
import com.jubitus.ultreia.job.GenerationManager;
import com.jubitus.ultreia.job.ManualPathSelection;
import com.jubitus.ultreia.route.EndpointResolver;
import com.jubitus.jubiconfig.network.MsgOpenConfigGui;
import com.jubitus.ultreia.bridge.template.BridgeTemplateRegistry;
import com.jubitus.ultreia.geom.Pos2D;
import com.jubitus.ultreia.roads.RoadNetwork;
import com.jubitus.ultreia.roads.RoadSegment;
import com.jubitus.ultreia.roads.RoadWaypoint;
import com.jubitus.ultreia.net.NetworkHandler;
import com.jubitus.ultreia.map.PacketOpenMapGui;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.WorldServer;

import javax.annotation.Nullable;
import java.util.*;

/** /ultreia: manual path selection, generation, map, config, status, and the bridge template sub-commands. */
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

    private static final int GUI_DEFAULT_RADIUS = 2000;
    private static final int GUI_MAX_RADIUS = 8000;

    @Override
    public String getName() {
        return "ultreia";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "ultreia.command.usage";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("mpb");
    }

    /** The sender as a player, else a "player only" error naming the sub-command. */
    static EntityPlayerMP requirePlayer(ICommandSender sender, String sub) throws CommandException {
        if (!(sender.getCommandSenderEntity() instanceof EntityPlayerMP)) {
            throw new CommandException("ultreia.command.player_only", sub);
        }
        return (EntityPlayerMP) sender.getCommandSenderEntity();
    }

    static void say(ICommandSender sender, String key, Object... args) {
        sender.sendMessage(new TextComponentTranslation(key, args));
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

        int dim = world.provider.getDimension();

        switch (sub) {
            case "bridge":
                BridgeTemplateCommands.handle(world, sender, args);
                return;

            case "pos1":
            case "pos2": {
                EntityPlayerMP player = requirePlayer(sender, sub);
                BlockPos pos = resolveCommandPos(world, player, args);
                if ("pos1".equals(sub)) ManualPathSelection.setPos1(player, dim, pos);
                else ManualPathSelection.setPos2(player, dim, pos);
                say(sender, "ultreia.command.pos_set", sub, formatPos(pos), dim);
                return;
            }

            case "generate":
                GenerationManager.start(world, sender);
                return;

            case "generatecustom": {
                EntityPlayerMP player = requirePlayer(sender, sub);
                ManualPathSelection.PlayerState state = ManualPathSelection.getForDimension(player, dim);

                if (state.pos1 == null) {
                    say(sender, "ultreia.command.pos_unset", "pos1");
                    return;
                }
                if (state.pos2 == null) {
                    say(sender, "ultreia.command.pos_unset", "pos2");
                    return;
                }
                if (Pos2D.sameXZ(state.pos1, state.pos2)) {
                    say(sender, "ultreia.command.same_pos");
                    return;
                }

                RoadType roadType = RoadType.VILLAGE_MAIN;
                if (args.length >= 2) {
                    String typeArg = args[1].toUpperCase(Locale.ROOT);
                    if ("LONE_TRAIL".equals(typeArg)) {
                        roadType = RoadType.LONE_TRAIL;
                    } else if (!"VILLAGE_MAIN".equals(typeArg)) {
                        say(sender, "ultreia.command.unknown_road_type", args[1]);
                        return;
                    }
                }

                GenerationManager.startCustom(world, sender, state.pos1, state.pos2, roadType);
                return;
            }

            case "gui": {
                EntityPlayerMP player = requirePlayer(sender, sub);
                int guiRadius = GUI_DEFAULT_RADIUS;
                if (args.length >= 2) {
                    try {
                        guiRadius = Math.max(1, Math.min(GUI_MAX_RADIUS, parseInt(args[1])));
                    } catch (CommandException ignored) {
                        say(sender, "ultreia.command.gui.bad_radius", GUI_DEFAULT_RADIUS);
                    }
                }
                openMap(world, player, guiRadius);
                return;
            }

            case "config":
                MsgOpenConfigGui.open(requirePlayer(sender, sub), false);
                return;

            case "status": {
                if (sender.getCommandSenderEntity() instanceof EntityPlayerMP) {
                    ManualPathSelection.PlayerState state =
                            ManualPathSelection.getForDimension((EntityPlayerMP) sender.getCommandSenderEntity(), dim);
                    say(sender, "ultreia.command.status.player", dim, formatPosOrUnset(state.pos1), formatPosOrUnset(state.pos2));
                } else {
                    say(sender, "ultreia.command.status.console", dim);
                }
                say(sender, "ultreia.command.status.job", GenerationManager.status(world));
                return;
            }

            case "reset":
                ManualPathSelection.reset(requirePlayer(sender, sub), dim);
                say(sender, "ultreia.command.reset", dim);
                return;

            case "cancel":
                GenerationManager.cancel(world);
                say(sender, "ultreia.command.cancel", dim);
                return;

            default:
                throw new WrongUsageException(getUsage(sender));
        }
    }

    /** Sends the built roads and bridges within {@code radius} of the player and opens the map screen. */
    private static void openMap(WorldServer world, EntityPlayerMP player, int radius) {
        BlockPos ppos = player.getPosition();
        int px = ppos.getX(), pz = ppos.getZ();
        List<RoadSegment> nearby = RoadNetwork.get(world).getSegmentsNear(px, pz, radius);
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
        NetworkHandler.CHANNEL.sendTo(new PacketOpenMapGui(px, pz, radius, segData), player);
        say(player, "ultreia.command.gui.opening", nearby.size(), radius);
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos targetPos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, SUBS);
        }
        if (args.length >= 2 && "bridge".equals(args[0].toLowerCase(Locale.ROOT))) {
            if (args.length == 2) {
                return getListOfStringsMatchingLastWord(args, BridgeTemplateCommands.SUBS);
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

        throw new WrongUsageException("ultreia.command.usage");
    }

    private static BlockPos resolveAnchor(WorldServer world, int x, int z) {
        TerrainSampler sampler = new TerrainSampler(world);
        try {
            return EndpointResolver.findAnchor(world, new BlockPos(x, 0, z), sampler, 16);
        } finally {
            sampler.clearCache();
        }
    }

    private static String formatPos(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }

    private static Object formatPosOrUnset(@Nullable BlockPos pos) {
        return pos == null ? new TextComponentTranslation("ultreia.command.status.unset") : formatPos(pos);
    }
}

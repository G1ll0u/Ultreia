package com.jubitus.jubituschunks.commands;

import com.jubitus.jubituschunks.JubitusChunksMod;
import com.jubitus.jubituschunks.config.JubitusChunksConfig;
import com.jubitus.jubituschunks.net.NetworkHandler;
import com.jubitus.jubituschunks.net.msg.PacketViewOpen;
import com.jubitus.jubituschunks.pregen.PregenManager;
import com.jubitus.jubituschunks.pregen.fun.FollowManager;
import com.jubitus.jubituschunks.pregen.state.PregenState;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;

public class CommandJubitusChunks extends CommandBase {

    @Override
    public String getName() { return "jubituschunks"; }

    @Override
    public int getRequiredPermissionLevel() { return 2; }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/jubituschunks <radiusBlocks> [x z] [skipExisting|force] | pause | cancel | resume [stepIndex [x z]] | view <radiusBlocks>";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) throw new WrongUsageException(getUsage(sender));

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "pause":  handlePause(server, sender, args); return;
            case "cancel": handleCancel(server, sender, args); return;
            case "resume": handleResume(server, sender, args); return;
            case "view":   handleView(server, sender, args); return;
            case "follow": handleFollow(server, sender, args); return;
            default:       handleStart(server, sender, args);
        }
    }

    private void handleStart(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        // Usage: <radiusBlocks> [x z] [skipExisting|force]
        if (args.length < 1 || args.length > 4) throw new WrongUsageException(getUsage(sender));

        int radiusBlocks = parseInt(args[0], 16, 50_000_000);
        boolean skipExisting = JubitusChunksConfig.GENERAL.skipAlreadyGeneratedChunks;

        // Optional trailing flag (last arg when length is 2 or 4)
        if (args.length == 2 || args.length == 4) {
            String flag = args[args.length - 1].toLowerCase();
            if (flag.equals("skipexisting") || flag.equals("skip")) {
                skipExisting = true;
            } else if (flag.equals("force") || flag.equals("noskip")) {
                skipExisting = false;
            } else {
                throw new WrongUsageException(getUsage(sender));
            }
        }

        WorldServer world;
        BlockPos start;

        if (args.length <= 2) {
            EntityPlayerMP player = sender.getCommandSenderEntity() instanceof EntityPlayerMP
                    ? (EntityPlayerMP) sender.getCommandSenderEntity() : null;
            if (player != null) {
                world = player.getServerWorld();
                start = player.getPosition();
            } else {
                world = worldForSender(server, sender);
                start = world.getSpawnPoint();
            }
        } else {
            world = worldForSender(server, sender);
            int x = parseInt(args[1]);
            int z = parseInt(args[2]);
            start = new BlockPos(x, world.getSeaLevel(), z);
        }

        EntityPlayerMP initiator = sender.getCommandSenderEntity() instanceof EntityPlayerMP
                ? (EntityPlayerMP) sender.getCommandSenderEntity() : null;

        if (!PregenManager.start(server, world, start, radiusBlocks, initiator, skipExisting)) {
            send(sender, "A pregen is already running here. Use /jubituschunks pause first.");
            return;
        }

        String msg = "Started pregeneration: radius=" + radiusBlocks + " blocks, start=(" + start.getX() + "," + start.getZ()
                + "), dim=" + world.provider.getDimension() + ", mode=" + (skipExisting ? "skipExisting" : "force");
        send(sender, msg);
        JubitusChunksMod.LOGGER.info(msg);
    }

    private void handlePause(MinecraftServer server, ICommandSender sender, String[] args) {
        WorldServer world = worldForSender(server, sender);
        boolean paused = PregenManager.pause(world.provider.getDimension());
        send(sender, paused
                ? "Pregen paused (state saved). Use /jubituschunks resume to continue."
                : "No pregen running here.");
    }

    private void handleCancel(MinecraftServer server, ICommandSender sender, String[] args) {
        WorldServer world = worldForSender(server, sender);
        boolean cancelled = PregenManager.cancel(world);
        send(sender, cancelled
                ? "Pregen cancelled (saved state deleted)."
                : "No pregen running here.");
    }

    private void handleResume(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        // Usage: resume [stepIndex [x z]]
        WorldServer world = worldForSender(server, sender);
        PregenState st = PregenState.load(world);
        if (st == null) {
            send(sender, "No saved pregen state for this dimension.");
            return;
        }

        int cx = st.centerChunkX;
        int cz = st.centerChunkZ;
        long maxSteps = st.radiusSteps > 0 ? (long) (2 * st.radiusSteps + 1) * (2 * st.radiusSteps + 1) : Long.MAX_VALUE;
        long stepIndex = Math.max(0, Math.min(st.spiralSteps, maxSteps));

        if (args.length >= 2) stepIndex = parseLong(args[1], 0, maxSteps);
        if (args.length >= 4) {
            cx = parseInt(args[2]) >> 4;
            cz = parseInt(args[3]) >> 4;
        }

        BlockPos start = new BlockPos((cx << 4) + 8, world.getSeaLevel(), (cz << 4) + 8);
        int radiusBlocks = st.chunkRadius * 16;

        EntityPlayerMP initiator = sender.getCommandSenderEntity() instanceof EntityPlayerMP
                ? (EntityPlayerMP) sender.getCommandSenderEntity() : null;

        boolean started = PregenManager.startResumable(server, world, start, radiusBlocks, initiator,
                st.skipExisting, st.verifyExisting, st.populateViaGenerator,
                st.radiusSteps, st.strideChunks, stepIndex);

        if (started) {
            send(sender, "Resumed pregen at step=" + stepIndex + "/" + maxSteps
                    + " center=(" + cx + "," + cz + ") radiusBlocks=" + radiusBlocks);
        } else {
            send(sender, "A pregen is already running here. Use /jubituschunks pause first.");
        }
    }

    private void handleView(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (!(sender.getCommandSenderEntity() instanceof EntityPlayerMP)) {
            send(sender, "This command must be run by a player.");
            return;
        }
        if (args.length != 2) throw new WrongUsageException("/jubituschunks view <radiusBlocks>");

        int radiusBlocks = parseInt(args[1], 16, 50_000_000);
        EntityPlayerMP p = (EntityPlayerMP) sender.getCommandSenderEntity();
        NetworkHandler.NET.sendTo(new PacketViewOpen(radiusBlocks), p);
        send(sender, "Opened chunk viewer (radiusBlocks=" + radiusBlocks + ").");
    }

    private void handleFollow(MinecraftServer server, ICommandSender sender, String[] args) {
        if (!(sender.getCommandSenderEntity() instanceof EntityPlayerMP)) {
            send(sender, "This command must be run by a player.");
            return;
        }
        EntityPlayerMP p = (EntityPlayerMP) sender.getCommandSenderEntity();
        if (PregenManager.getTask(p.dimension) == null) {
            send(sender, "No pregen task running in this dimension.");
            return;
        }
        boolean enabled = FollowManager.toggle(p);
        send(sender, enabled ? "Follow enabled: teleporting to pregen head." : "Follow disabled.");
    }

    private static void send(ICommandSender sender, String msg) {
        sender.sendMessage(new TextComponentString(msg));
    }

    private static WorldServer worldForSender(MinecraftServer server, ICommandSender sender) {
        if (sender.getEntityWorld() instanceof WorldServer) return (WorldServer) sender.getEntityWorld();
        return server.getWorld(0);
    }
}

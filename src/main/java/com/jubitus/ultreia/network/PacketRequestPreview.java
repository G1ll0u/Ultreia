package com.jubitus.ultreia.network;

import com.jubitus.ultreia.planner.NetworkPlanner;
import com.jubitus.ultreia.planner.VillageClassifier;
import com.jubitus.ultreia.mill.MillVillageIndex;
import com.jubitus.ultreia.mill.MillWorldDataRegistry;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Sent by the client when the "Preview Network" button is clicked. No payload. */
public final class PacketRequestPreview implements IMessage {

    public PacketRequestPreview() {}

    @Override public void fromBytes(ByteBuf buf) {}
    @Override public void toBytes(ByteBuf buf) {}

    public static final class Handler implements IMessageHandler<PacketRequestPreview, IMessage> {
        @Override
        public IMessage onMessage(PacketRequestPreview msg, MessageContext ctx) {
            EntityPlayerMP player = ctx.getServerHandler().player;
            WorldServer world = player.getServerWorld();
            // Same permission as the command that opens the map: building the network is expensive.
            if (!player.canUseCommand(2, "ultreia")) return null;

            world.addScheduledTask(() -> {
                List<BlockPos> nodes = MillWorldDataRegistry.getAllNodePositions(world);

                NetworkPlanner.NetworkResult result = NetworkPlanner.build(world, nodes);

                List<PacketNetworkPreview.NodeData> nodeData = buildNodeData(world, nodes, result.villageSizes);
                List<PacketNetworkPreview.EdgeData> edgeData = buildEdgeData(world, nodes, result.edges);

                MPBNetworkHandler.CHANNEL.sendTo(new PacketNetworkPreview(nodeData, edgeData), player);
            });

            return null;
        }

        private static List<PacketNetworkPreview.NodeData> buildNodeData(
                WorldServer world,
                List<BlockPos> nodes,
                Map<BlockPos, VillageClassifier.VillageSize> sizes) {

            List<PacketNetworkPreview.NodeData> out = new ArrayList<>(nodes.size());
            for (BlockPos pos : nodes) {
                MillVillageIndex.VillageInfo vi = MillVillageIndex.findExact(world, pos);
                boolean isVillage = vi == null || vi.isVillage;
                String name       = vi != null ? vi.name : (pos.getX() + "/" + pos.getZ());
                VillageClassifier.VillageSize size =
                        isVillage ? sizes.getOrDefault(pos, VillageClassifier.VillageSize.SMALL)
                                  : null;
                int population = isVillage ? VillageClassifier.getPopulation(world, pos) : 0;

                byte typeByte;
                if (!isVillage) {
                    typeByte = PacketNetworkPreview.TYPE_LONE;
                } else if (size == VillageClassifier.VillageSize.HUB) {
                    typeByte = PacketNetworkPreview.TYPE_HUB;
                } else if (size == VillageClassifier.VillageSize.MEDIUM) {
                    typeByte = PacketNetworkPreview.TYPE_MEDIUM;
                } else {
                    typeByte = PacketNetworkPreview.TYPE_SMALL;
                }

                out.add(new PacketNetworkPreview.NodeData(pos.getX(), pos.getZ(), typeByte, population, name));
            }
            return out;
        }

        private static List<PacketNetworkPreview.EdgeData> buildEdgeData(
                WorldServer world,
                List<BlockPos> nodes,
                List<NetworkPlanner.Edge> edges) {

            List<PacketNetworkPreview.EdgeData> out = new ArrayList<>(edges.size());
            for (NetworkPlanner.Edge e : edges) {
                out.add(new PacketNetworkPreview.EdgeData(
                        e.a.getX(), e.a.getZ(), e.b.getX(), e.b.getZ(), e.isLoneTrail));
            }
            return out;
        }
    }
}

package com.jubitus.millpathbuilder.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

/** Server → client: logical road network preview (nodes + straight-line edges). */
public final class PacketNetworkPreview implements IMessage {

    // Node type constants
    public static final byte TYPE_SMALL  = 0;
    public static final byte TYPE_MEDIUM = 1;
    public static final byte TYPE_HUB    = 2;
    public static final byte TYPE_LONE   = 3;

    // -------------------------------------------------------------------------
    // Data types
    // -------------------------------------------------------------------------

    public static final class NodeData {
        public final int x, z;
        public final byte type;        // TYPE_* constant
        public final int population;
        public final String name;

        public NodeData(int x, int z, byte type, int population, String name) {
            this.x = x; this.z = z; this.type = type;
            this.population = population;
            this.name = name != null ? name : "";
        }
    }

    public static final class EdgeData {
        public final int x1, z1, x2, z2;
        public final boolean isLoneTrail;

        public EdgeData(int x1, int z1, int x2, int z2, boolean isLoneTrail) {
            this.x1 = x1; this.z1 = z1; this.x2 = x2; this.z2 = z2;
            this.isLoneTrail = isLoneTrail;
        }
    }

    // -------------------------------------------------------------------------
    // Handler callback
    // -------------------------------------------------------------------------

    @FunctionalInterface
    public interface PreviewHandler {
        void onPreview(List<NodeData> nodes, List<EdgeData> edges);
    }

    private static PreviewHandler handler;
    public static void setHandler(PreviewHandler h) { handler = h; }

    // -------------------------------------------------------------------------
    // Packet payload
    // -------------------------------------------------------------------------

    private List<NodeData> nodes = new ArrayList<>();
    private List<EdgeData> edges = new ArrayList<>();

    public PacketNetworkPreview() {}

    public PacketNetworkPreview(List<NodeData> nodes, List<EdgeData> edges) {
        this.nodes = nodes;
        this.edges = edges;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(nodes.size());
        for (NodeData n : nodes) {
            buf.writeInt(n.x);
            buf.writeInt(n.z);
            buf.writeByte(n.type);
            buf.writeInt(n.population);
            ByteBufUtils.writeUTF8String(buf, n.name);
        }
        buf.writeInt(edges.size());
        for (EdgeData e : edges) {
            buf.writeInt(e.x1); buf.writeInt(e.z1);
            buf.writeInt(e.x2); buf.writeInt(e.z2);
            buf.writeBoolean(e.isLoneTrail);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int nc = buf.readInt();
        nodes = new ArrayList<>(nc);
        for (int i = 0; i < nc; i++) {
            int x   = buf.readInt();
            int z   = buf.readInt();
            byte t  = buf.readByte();
            int pop = buf.readInt();
            String name = ByteBufUtils.readUTF8String(buf);
            nodes.add(new NodeData(x, z, t, pop, name));
        }
        int ec = buf.readInt();
        edges = new ArrayList<>(ec);
        for (int i = 0; i < ec; i++) {
            edges.add(new EdgeData(
                    buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt(),
                    buf.readBoolean()));
        }
    }

    public static final class Handler implements IMessageHandler<PacketNetworkPreview, IMessage> {
        @Override
        public IMessage onMessage(PacketNetworkPreview msg, MessageContext ctx) {
            if (handler != null) handler.onPreview(msg.nodes, msg.edges);
            return null;
        }
    }
}

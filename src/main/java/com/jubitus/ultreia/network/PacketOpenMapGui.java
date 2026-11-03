package com.jubitus.ultreia.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

import java.util.ArrayList;
import java.util.List;

public final class PacketOpenMapGui implements IMessage {

    /** Sentinel for absent village coordinates. */
    public static final int ABSENT = Integer.MIN_VALUE;

    public static final class SegmentData {
        public final int id;
        public final boolean isBridge;
        public final int fromX, fromZ; // ABSENT if not set
        public final int toX, toZ;
        // flat: x0,y0,z0, x1,y1,z1, ...
        public final int[] waypoints;

        public SegmentData(int id, boolean isBridge,
                           int fromX, int fromZ, int toX, int toZ,
                           int[] waypoints) {
            this.id = id; this.isBridge = isBridge;
            this.fromX = fromX; this.fromZ = fromZ;
            this.toX = toX; this.toZ = toZ;
            this.waypoints = waypoints;
        }

        public int waypointCount()    { return waypoints.length / 3; }
        public int wx(int i)          { return waypoints[i * 3]; }
        public int wy(int i)          { return waypoints[i * 3 + 1]; }
        public int wz(int i)          { return waypoints[i * 3 + 2]; }
    }

    private static GuiOpener guiOpener;

    @FunctionalInterface
    public interface GuiOpener {
        void open(int cx, int cz, int radius, List<SegmentData> segments);
    }

    public static void setGuiOpener(GuiOpener opener) { guiOpener = opener; }

    private int cx, cz, radius;
    private List<SegmentData> segments = new ArrayList<>();

    public PacketOpenMapGui() {}

    public PacketOpenMapGui(int cx, int cz, int radius, List<SegmentData> segs) {
        this.cx = cx; this.cz = cz; this.radius = radius;
        this.segments = segs;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        cx = buf.readInt(); cz = buf.readInt(); radius = buf.readInt();
        int n = buf.readInt();
        segments = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int id     = buf.readInt();
            boolean br = buf.readBoolean();
            int fx = buf.readInt(), fz = buf.readInt();
            int tx = buf.readInt(), tz = buf.readInt();
            int wc = buf.readInt();
            int[] wp = new int[wc * 3];
            for (int j = 0; j < wp.length; j++) wp[j] = buf.readInt();
            segments.add(new SegmentData(id, br, fx, fz, tx, tz, wp));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(cx); buf.writeInt(cz); buf.writeInt(radius);
        buf.writeInt(segments.size());
        for (SegmentData sd : segments) {
            buf.writeInt(sd.id);
            buf.writeBoolean(sd.isBridge);
            buf.writeInt(sd.fromX); buf.writeInt(sd.fromZ);
            buf.writeInt(sd.toX);  buf.writeInt(sd.toZ);
            buf.writeInt(sd.waypointCount());
            for (int v : sd.waypoints) buf.writeInt(v);
        }
    }

    public static final class Handler implements IMessageHandler<PacketOpenMapGui, IMessage> {
        @Override
        public IMessage onMessage(PacketOpenMapGui msg, MessageContext ctx) {
            if (guiOpener != null) guiOpener.open(msg.cx, msg.cz, msg.radius, msg.segments);
            return null;
        }
    }
}

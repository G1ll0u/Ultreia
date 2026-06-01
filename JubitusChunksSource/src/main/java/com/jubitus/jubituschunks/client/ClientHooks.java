package com.jubitus.jubituschunks.client;

import com.jubitus.jubituschunks.client.gui.GuiChunkViewer;
import com.jubitus.jubituschunks.net.NetworkHandler;
import com.jubitus.jubituschunks.net.msg.PacketViewSubscribe;
import com.jubitus.jubituschunks.net.msg.PacketViewUnsubscribe;
import com.jubitus.jubituschunks.net.msg.PacketViewUpdate;
import net.minecraft.client.Minecraft;

public class ClientHooks {

    private static GuiChunkViewer CURRENT;

    public static void openChunkViewer(int radiusBlocks) {
        Minecraft mc = Minecraft.getMinecraft();
        CURRENT = new GuiChunkViewer(radiusBlocks);
        mc.displayGuiScreen(CURRENT);

        // tell server to start streaming updates
        NetworkHandler.NET.sendToServer(new PacketViewSubscribe(radiusBlocks));
    }

    public static void onViewUpdate(PacketViewUpdate msg) {
        if (CURRENT != null) {
            CURRENT.accept(msg);
        }
    }

    public static void onViewerClosed() {
        NetworkHandler.NET.sendToServer(new PacketViewUnsubscribe());
        CURRENT = null;
    }
}

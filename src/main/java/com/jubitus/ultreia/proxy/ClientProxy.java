package com.jubitus.ultreia.proxy;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.block.MPBBlocks;
import com.jubitus.ultreia.gui.GuiMPBMap;
import com.jubitus.ultreia.network.PacketNetworkPreview;
import com.jubitus.ultreia.network.PacketOpenMapGui;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class ClientProxy implements IProxy {

    @Override
    public void registerClientPackets() {
        PacketOpenMapGui.setGuiOpener((cx, cz, radius, segments) -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) return;
            mc.addScheduledTask(() -> {
                try {
                    mc.displayGuiScreen(new GuiMPBMap(cx, cz, radius, segments));
                } catch (Exception e) {
                    Ultreia.LOGGER.error("[MPBMap] Failed to open map GUI on client", e);
                }
            });
        });

        PacketNetworkPreview.setHandler((nodes, edges) -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) return;
            mc.addScheduledTask(() -> {
                if (mc.currentScreen instanceof GuiMPBMap) {
                    ((GuiMPBMap) mc.currentScreen).setPreviewData(nodes, edges);
                }
            });
        });
    }
}

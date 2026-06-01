package com.jubitus.millpathbuilder.proxy;

import com.jubitus.millpathbuilder.MillPathBuilderMod;
import com.jubitus.millpathbuilder.block.MPBBlocks;
import com.jubitus.millpathbuilder.gui.GuiMPBConfig;
import com.jubitus.millpathbuilder.gui.GuiMPBMap;
import com.jubitus.millpathbuilder.network.PacketNetworkPreview;
import com.jubitus.millpathbuilder.network.PacketOpenConfigGui;
import com.jubitus.millpathbuilder.network.PacketOpenMapGui;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class ClientProxy implements IProxy {

    @Override
    public void registerBlockModels() {
        MPBBlocks.registerModels();
    }

    @Override
    public void registerClientPackets() {
        // All references to Minecraft/GuiMPBConfig live inside this @SideOnly class — never loaded on server.
        PacketOpenConfigGui.setGuiOpener(values -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                MillPathBuilderMod.LOGGER.error("[MPBConfig] Minecraft instance is null — cannot open config GUI");
                return;
            }
            mc.addScheduledTask(() -> {
                try {
                    mc.displayGuiScreen(new GuiMPBConfig(values));
                } catch (Exception e) {
                    MillPathBuilderMod.LOGGER.error("[MPBConfig] Failed to open config GUI on client", e);
                }
            });
        });

        PacketOpenMapGui.setGuiOpener((cx, cz, radius, segments) -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) return;
            mc.addScheduledTask(() -> {
                try {
                    mc.displayGuiScreen(new GuiMPBMap(cx, cz, radius, segments));
                } catch (Exception e) {
                    MillPathBuilderMod.LOGGER.error("[MPBMap] Failed to open map GUI on client", e);
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

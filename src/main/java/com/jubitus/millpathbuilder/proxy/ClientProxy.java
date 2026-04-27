package com.jubitus.millpathbuilder.proxy;

import com.jubitus.millpathbuilder.MillPathBuilderMod;
import com.jubitus.millpathbuilder.gui.GuiMPBConfig;
import com.jubitus.millpathbuilder.network.PacketOpenConfigGui;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

@SideOnly(Side.CLIENT)
public final class ClientProxy implements IProxy {

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
    }
}

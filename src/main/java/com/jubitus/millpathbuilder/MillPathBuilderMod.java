package com.jubitus.millpathbuilder;

import com.jubitus.jubiconfig.JubiConfig;
import com.jubitus.millpathbuilder.command.CommandMillPathBuilder;
import com.jubitus.millpathbuilder.config.MPBConfig;
import com.jubitus.millpathbuilder.gen.MPBGenerationManager;
import com.jubitus.millpathbuilder.gen.world.MPBChunkLoadingCallback;
import com.jubitus.millpathbuilder.network.MPBNetworkHandler;
import com.jubitus.millpathbuilder.player.MPBSpeedBoostHandler;
import com.jubitus.millpathbuilder.proxy.IProxy;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

@Mod(
        modid = Tags.MOD_ID,
        name = Tags.MOD_NAME,
        version = Tags.VERSION,
        acceptableRemoteVersions = "*",
        dependencies = "after:millenaire",
        guiFactory = "com.jubitus.millpathbuilder.gui.MPBGuiFactory"
)
public class MillPathBuilderMod {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    @Mod.Instance(Tags.MOD_ID)
    public static MillPathBuilderMod INSTANCE;

    @SidedProxy(
            clientSide = "com.jubitus.millpathbuilder.proxy.ClientProxy",
            serverSide = "com.jubitus.millpathbuilder.proxy.ServerProxy"
    )
    public static IProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ForgeChunkManager.setForcedChunkLoadingCallback(MillPathBuilderMod.INSTANCE, new MPBChunkLoadingCallback());
        MinecraftForge.EVENT_BUS.register(new MPBGenerationManager());
        MinecraftForge.EVENT_BUS.register(new MPBSpeedBoostHandler());

        File configDir = new File(event.getModConfigurationDirectory(), "millpathbuilder");
        JubiConfig.register(
                Tags.MOD_ID, Tags.MOD_NAME, "mpbconfig",
                MPBConfig.class, MPBNetworkHandler.CHANNEL,
                () -> {}, // no additional post-save actions needed
                configDir, 1
        );
        JubiConfig.applyOnStartup(Tags.MOD_ID);
        JubiConfig.loadHistory(Tags.MOD_ID);

        MPBNetworkHandler.init();
        proxy.registerClientPackets();
        LOGGER.info("MillPathBuilder loaded.");
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandMillPathBuilder());
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        MPBGenerationManager.onServerStopping();
    }
}

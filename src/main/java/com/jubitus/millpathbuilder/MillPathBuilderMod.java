package com.jubitus.millpathbuilder;

import com.jubitus.millpathbuilder.command.CommandMillPathBuilder;
import com.jubitus.millpathbuilder.config.MPBConfigPersistence;
import com.jubitus.millpathbuilder.gen.MPBGenerationManager;
import com.jubitus.millpathbuilder.gen.world.MPBChunkLoadingCallback;
import com.jubitus.millpathbuilder.network.MPBNetworkHandler;
import com.jubitus.millpathbuilder.proxy.IProxy;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
        modid = Tags.MOD_ID,
        name = Tags.MOD_NAME,
        version = Tags.VERSION,
        acceptableRemoteVersions = "*"
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
        MPBNetworkHandler.init();
        proxy.registerClientPackets();
        LOGGER.info("MillPathBuilder loaded.");
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandMillPathBuilder());
        MPBConfigPersistence.applyOnStartup();
    }
}

package com.jubitus.ultreia;

import com.jubitus.jubiconfig.JubiConfig;
import com.jubitus.ultreia.command.CommandUltreia;
import com.jubitus.ultreia.config.MPBConfig;
import com.jubitus.ultreia.bridge.template.BridgeDefaultTemplates;
import com.jubitus.ultreia.job.GenerationManager;
import com.jubitus.ultreia.chunk.ChunkLoadingCallback;
import com.jubitus.ultreia.network.MPBNetworkHandler;
import com.jubitus.ultreia.player.MPBSpeedBoostHandler;
import com.jubitus.ultreia.proxy.IProxy;
import net.minecraftforge.common.ForgeChunkManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import com.jubitus.ultreia.integration.MillIntegration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

@Mod(
        modid = Tags.MOD_ID,
        name = Tags.MOD_NAME,
        version = Tags.VERSION,
        acceptableRemoteVersions = "*",
        dependencies = "after:millenaire",
        guiFactory = "com.jubitus.ultreia.shadow.jubiconfig.factory.ModGuiFactory"
)
public class Ultreia {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    @Mod.Instance(Tags.MOD_ID)
    public static Ultreia INSTANCE;

    @SidedProxy(
            clientSide = "com.jubitus.ultreia.proxy.ClientProxy",
            serverSide = "com.jubitus.ultreia.proxy.ServerProxy"
    )
    public static IProxy proxy;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ForgeChunkManager.setForcedChunkLoadingCallback(Ultreia.INSTANCE, new ChunkLoadingCallback());
        MinecraftForge.EVENT_BUS.register(new GenerationManager());
        MinecraftForge.EVENT_BUS.register(new MPBSpeedBoostHandler());

        File configDir = new File(event.getModConfigurationDirectory(), "ultreia");
        JubiConfig.builder(Tags.MOD_ID, Tags.MOD_NAME, MPBConfig.class, MPBNetworkHandler.CHANNEL, configDir)
                .command("ultreia config")
                .resetVersion(1) // config was restructured for this release, force a one-time reset
                .register();

        MPBNetworkHandler.init();
        proxy.registerClientPackets();
        LOGGER.info("Ultreïa loaded.");
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        BridgeDefaultTemplates.deployIfAbsent();
        event.registerServerCommand(new CommandUltreia());
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        GenerationManager.onServerStopping();
        if (MillPresence.isPresent()) MillIntegration.clear();
    }
}

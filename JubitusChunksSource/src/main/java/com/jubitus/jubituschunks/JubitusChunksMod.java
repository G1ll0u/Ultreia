package com.jubitus.jubituschunks;

import com.jubitus.jubituschunks.commands.CommandJubitusChunks;
import com.jubitus.jubituschunks.config.JubitusChunksConfig;
import com.jubitus.jubituschunks.net.NetworkHandler;
import com.jubitus.jubituschunks.pregen.PregenManager;
import com.jubitus.jubituschunks.pregen.PregenTickHandler;
import com.jubitus.jubituschunks.pregen.state.PregenState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartedEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Tags.MOD_ID, name = Tags.MOD_NAME, version = Tags.VERSION)
public class JubitusChunksMod {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new PregenTickHandler());
        NetworkHandler.init();
    }

    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandJubitusChunks());
    }

    @Mod.EventHandler
    public void onServerStarted(FMLServerStartedEvent event) {
        if (!JubitusChunksConfig.GENERAL.autoResumePregenOnStartup) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        for (WorldServer world : server.worlds) {
            if (world == null) continue;
            PregenState st = PregenState.load(world);
            if (st == null) continue;
            if (PregenManager.getTask(world.provider.getDimension()) != null) continue;

            BlockPos start = new BlockPos(st.centerChunkX << 4, 64, st.centerChunkZ << 4);
            int radiusBlocks = st.chunkRadius * 16;

            boolean ok = PregenManager.startResumable(server, world, start, radiusBlocks, null,
                    st.skipExisting, st.verifyExisting, st.populateViaGenerator,
                    st.radiusSteps, st.strideChunks, st.spiralSteps);

            if (ok) {
                LOGGER.info("Auto-resumed pregen for dim {} at ({},{}) step={}/{}", st.dim,
                        st.centerChunkX, st.centerChunkZ, st.spiralSteps,
                        (long) (2 * st.radiusSteps + 1) * (2 * st.radiusSteps + 1));
            } else {
                LOGGER.warn("Auto-resume failed for dim {} (task already running?)", st.dim);
            }
        }
    }

    @Mod.EventHandler
    public void onServerStopping(FMLServerStoppingEvent event) {
        PregenManager.pauseAllAndFlush();
    }
}

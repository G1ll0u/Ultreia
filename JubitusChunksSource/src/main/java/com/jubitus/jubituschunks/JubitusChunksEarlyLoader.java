package com.jubitus.jubituschunks;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import zone.rong.mixinbooter.IEarlyMixinLoader;

import java.util.Collections;
import java.util.List;

/**
 * Cleanroom coremod entrypoint (loaded very early).
 * Loads mixins before Minecraft classes like PathNavigateGround are defined.
 */
@IFMLLoadingPlugin.Name("JubitusChunksEarlyLoader")
@IFMLLoadingPlugin.MCVersion("1.12.2")
public class JubitusChunksEarlyLoader implements IEarlyMixinLoader {
    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("mixins.jubituschunks.json");
    }
}


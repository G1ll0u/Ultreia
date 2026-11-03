package com.jubitus.ultreia;

import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.Collections;
import java.util.List;

public class UltreiaLateLoader implements ILateMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        return Collections.singletonList("mixins.ultreia.json");
    }

    @Override
    public boolean shouldMixinConfigQueue(String mixinConfig) {
        // INVARIANT: resource lookup, never Class.forName (it would load the mixin target early).
        return getClass().getClassLoader()
                .getResource("org/millenaire/common/world/MillWorldData.class") != null;
    }
}

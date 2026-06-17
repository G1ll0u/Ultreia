package com.jubitus.ultreia;

import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.Collections;
import java.util.List;

public class UltreiaLateLoader implements ILateMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        System.out.println("[Ultreïa] ILateMixinLoader called! Queuing Ultreïa.millmix.json");
        return Collections.singletonList("mixins.ultreia.json");
    }
}
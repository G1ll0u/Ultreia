package com.jubitus.millpathbuilder;

import zone.rong.mixinbooter.ILateMixinLoader;

import java.util.Collections;
import java.util.List;

public class MillPathBuilderLateLoader implements ILateMixinLoader {

    @Override
    public List<String> getMixinConfigs() {
        System.out.println("[MillPathBuilder] ILateMixinLoader called! Queuing MillPathBuilder.millmix.json");
        return Collections.singletonList("mixins.millpathbuilder.json");
    }
}
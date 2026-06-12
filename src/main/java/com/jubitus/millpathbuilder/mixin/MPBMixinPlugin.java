package com.jubitus.millpathbuilder.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public class MPBMixinPlugin implements IMixinConfigPlugin {

    private boolean millePresent;

    @Override
    public void onLoad(String mixinPackage) {
        // Use resource lookup instead of Class.forName() to avoid loading the class.
        // Class.forName() would trigger MixinTargetAlreadyLoadedException for any other
        // mod (e.g. jubitussounds) that also mixins into MillWorldData.
        millePresent = getClass().getClassLoader()
                .getResource("org/millenaire/common/world/MillWorldData.class") != null;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return millePresent;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass,
                         String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass,
                          String mixinClassName, IMixinInfo mixinInfo) {}
}

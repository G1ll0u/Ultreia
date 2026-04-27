package com.jubitus.millpathbuilder.gen.bridge.template;

import com.jubitus.millpathbuilder.gen.TerrainSampler;
import com.jubitus.millpathbuilder.gen.bridge.BridgeSpan;
import net.minecraft.world.WorldServer;

public final class BridgeBuildService {

    private BridgeBuildService() {}

    public static boolean build(WorldServer world,
                                TerrainSampler sampler,
                                BridgeSpan span,
                                int roadWidth,
                                boolean dryRun) {
        BridgeTemplate template = BridgeTemplateSelector.select(world, span);
        return BridgeTemplateBridgeBuilder.build(world, sampler, span, template, dryRun);
    }
}

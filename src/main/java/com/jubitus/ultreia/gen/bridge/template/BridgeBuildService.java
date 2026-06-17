package com.jubitus.ultreia.gen.bridge.template;

import com.jubitus.ultreia.gen.TerrainSampler;
import com.jubitus.ultreia.gen.bridge.BridgeSpan;
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

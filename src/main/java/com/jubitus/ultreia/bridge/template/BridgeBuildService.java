package com.jubitus.ultreia.bridge.template;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.MPBConfig;
import com.jubitus.ultreia.terrain.TerrainSampler;
import com.jubitus.ultreia.bridge.BridgeSpan;
import net.minecraft.world.WorldServer;

public final class BridgeBuildService {

    private BridgeBuildService() {}

    public static boolean build(WorldServer world,
                                TerrainSampler sampler,
                                BridgeSpan span,
                                boolean dryRun) {
        BridgeTemplate template = span.template;
        if (template == null) {
            Ultreia.LOGGER.warn("[MPB] No bridge template matched and default '{}' not found — bridge skipped.",
                    MPBConfig.BRIDGES.defaultTemplateName);
            return false;
        }
        if (MPBConfig.DEBUG.logBridgeTemplateSelection) {
            Ultreia.LOGGER.info("[MPB] Bridge ({} blocks, water={}, waterDominant={}): template '{}'",
                    span.lengthBlocks(), span.hasWaterCrossing, span.waterDominant, template.name);
        }
        return BridgeTemplateBridgeBuilder.build(world, sampler, span, template, dryRun);
    }
}

package com.jubitus.ultreia.bridge.template;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.bridge.BridgeDeckProfile;
import com.jubitus.ultreia.bridge.BridgeSpan;
import com.jubitus.ultreia.config.UltreiaConfig;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.state.IBlockState;
import net.minecraft.world.WorldServer;

/** Builds one bridge span in the world from its template: deck first, then the template structure. */
public final class BridgeBuildService {

    private BridgeBuildService() {}

    public static boolean build(WorldServer world, BridgeSpan span) {
        BridgeTemplate template = span.template;
        if (template == null) {
            Ultreia.LOGGER.warn("[Ultreia] No bridge template matched and default '{}' not found — bridge skipped.",
                    UltreiaConfig.BRIDGES.defaultTemplateName);
            return false;
        }
        if (UltreiaConfig.DEBUG.logBridges) {
            Ultreia.LOGGER.info("[Ultreia] Bridge ({} blocks, water={}, waterDominant={}): template '{}'",
                    span.lengthBlocks(), span.hasWaterCrossing, span.waterDominant, template.name);
        }

        if (span.centerline == null || span.centerline.isEmpty()) return true;
        if (template.segmentLength < 1) return false;
        if (template.deckSlab == null) return false;

        int len = span.lengthBlocks();
        int maxLen = Math.max(5, UltreiaConfig.BRIDGES.maxBridgeLenBlocks);
        if (len > maxLen) return false;

        int[] deckHalfY = BridgeDeckProfile.of(span);

        IBlockState deckBaseState = template.deckSlab.toState();
        if (!(deckBaseState.getBlock() instanceof BlockSlab)) {
            return false;
        }

        BridgeDeckPlacer.place(world, span, deckHalfY, deckBaseState);
        BridgeStructurePlacer.place(world, span, template, deckHalfY, deckBaseState);
        return true;
    }
}

package com.jubitus.ultreia.gen.bridge.template;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.MPBConfig;
import com.jubitus.ultreia.bridge.BridgeSpan;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.Locale;
import java.util.Set;

public final class BridgeTemplateSelector {

    private BridgeTemplateSelector() {}

    // Templates are validated once per loaded instance (a reload creates new instances), so errors are logged once.
    private static final Map<BridgeTemplate, BridgeTemplateValidator.ValidationResult> VALIDATED = new WeakHashMap<>();

    private static BridgeTemplateValidator.ValidationResult validateOnce(BridgeTemplate t) {
        BridgeTemplateValidator.ValidationResult result = VALIDATED.get(t);
        if (result != null) return result;
        result = BridgeTemplateValidator.validate(t);
        for (String err : result.errors) {
            Ultreia.LOGGER.error("[MPB] Bridge template '{}' error: {}", t.name, err);
        }
        for (String w : result.warnings) {
            Ultreia.LOGGER.warn("[MPB] Bridge template '{}': {}", t.name, w);
        }
        VALIDATED.put(t, result);
        return result;
    }

    /** Called for every bridge candidate of the A*: no logging here. */
    @Nullable
    public static BridgeTemplate select(WorldServer world, BridgeSpan span) {
        List<BridgeTemplate> all = BridgeTemplateRegistry.loadAllTemplates();
        String defaultName = BridgeTemplateRegistry.sanitizeName(MPBConfig.BRIDGES.defaultTemplateName);

        Biome biome = sampleBiome(world, span);

        BridgeTemplate defaultTemplate = null;
        List<BridgeTemplate> candidates = new ArrayList<BridgeTemplate>();

        for (BridgeTemplate t : all) {
            BridgeTemplateValidator.ValidationResult result = validateOnce(t);

            if (result.hasErrors()) {
                // Still keep a critically-invalid default so the path is never silently blocked
                if (t.name != null && t.name.equals(defaultName)) {
                    defaultTemplate = t;
                }
                continue;
            }

            if (t.name != null && t.name.equals(defaultName)) {
                defaultTemplate = t;
            } else if (matchesConditions(t, span, biome)) {
                candidates.add(t);
            }
        }

        if (candidates.isEmpty()) return defaultTemplate;

        // Most specific conditions win; stable sort by name as tiebreaker
        BridgeTemplate best = candidates.get(0);
        for (int i = 1; i < candidates.size(); i++) {
            BridgeTemplate c = candidates.get(i);
            int cs = score(c);
            int bs = score(best);
            if (cs > bs || (cs == bs && c.name.compareTo(best.name) < 0)) {
                best = c;
            }
        }
        return best;
    }

    private static boolean matchesConditions(BridgeTemplate t, BridgeSpan span, @Nullable Biome biome) {
        int len = span.lengthBlocks();
        if (t.minLength != null && t.minLength > 0 && len < t.minLength) return false;
        if (t.maxLength != null && t.maxLength > 0 && len > t.maxLength) return false;

        String wo = t.waterOnly != null ? t.waterOnly.toUpperCase(Locale.ROOT) : "BOTH";
        if ("YES".equals(wo) && !span.hasWaterCrossing) return false;
        if ("NO".equals(wo) && span.hasWaterCrossing) return false;

        if (biome != null) {
            if (t.biomes != null && !t.biomes.isEmpty() && !matchesBiome(biome, t.biomes)) return false;
            if (t.biomeTemperatureCategories != null && !t.biomeTemperatureCategories.isEmpty()
                    && !matchesTempCategory(biome, t.biomeTemperatureCategories)) return false;
            if (t.biomeDictionaryTypes != null && !t.biomeDictionaryTypes.isEmpty()
                    && !matchesBiomeDictTypes(biome, t.biomeDictionaryTypes)) return false;
        }

        return true;
    }

    private static boolean matchesBiome(Biome biome, List<String> names) {
        ResourceLocation biomeRL = biome.getRegistryName();
        if (biomeRL == null) return false;

        for (String name : names) {
            if (name == null || name.isEmpty()) continue;

            if (name.contains(":")) {
                // comparaison complète modid:path
                if (biomeRL.toString().equalsIgnoreCase(name)) return true;
            } else {
                String lower = name.toLowerCase(Locale.ROOT);

                // match sur le path seul
                if (biomeRL.getPath().equalsIgnoreCase(lower)) return true;

                // fallback : interpréter comme minecraft:name
                try {
                    Biome found = ForgeRegistries.BIOMES.getValue(new ResourceLocation("minecraft", lower));
                    if (found == biome) return true;
                } catch (Exception ignored) {}
            }
        }
        return false;
    }

    private static boolean matchesTempCategory(Biome biome, List<String> cats) {
        Biome.TempCategory cat = biome.getTempCategory();
        if (cat == null) return false;
        String catName = cat.name();
        for (String c : cats) {
            if (c != null && c.equalsIgnoreCase(catName)) return true;
        }
        return false;
    }

    private static boolean matchesBiomeDictTypes(Biome biome, List<String> typeNames) {
        Set<BiomeDictionary.Type> biomeTypes = BiomeDictionary.getTypes(biome);
        for (String typeName : typeNames) {
            if (typeName == null) continue;
            for (BiomeDictionary.Type t : biomeTypes) {
                if (t.getName().equalsIgnoreCase(typeName)) return true;
            }
        }
        return false;
    }

    /** Higher score = more conditions specified = higher priority. */
    private static int score(BridgeTemplate t) {
        int s = 0;
        if (t.biomes != null && !t.biomes.isEmpty()) s++;
        if (t.biomeTemperatureCategories != null && !t.biomeTemperatureCategories.isEmpty()) s++;
        if (t.biomeDictionaryTypes != null && !t.biomeDictionaryTypes.isEmpty()) s++;
        if (t.waterOnly != null && !"BOTH".equalsIgnoreCase(t.waterOnly)) s++;
        if (t.minLength != null && t.minLength > 0) s++;
        if (t.maxLength != null && t.maxLength > 0) s++;
        return s;
    }

    @Nullable
    private static Biome sampleBiome(WorldServer world, BridgeSpan span) {
        if (span.centerline == null || span.centerline.isEmpty()) return null;
        int midIdx = span.centerline.size() / 2;
        return world.getBiome(span.centerline.get(midIdx));
    }
}

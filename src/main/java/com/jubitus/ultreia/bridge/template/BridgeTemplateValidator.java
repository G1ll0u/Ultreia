package com.jubitus.ultreia.bridge.template;

import net.minecraft.util.ResourceLocation;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class BridgeTemplateValidator {

    private static final List<String> VALID_WATER_ONLY =
            Arrays.asList("BOTH", "YES", "NO");
    private static final List<String> VALID_TEMP_CATEGORIES =
            Arrays.asList("OCEAN", "COLD", "MEDIUM", "WARM");

    private BridgeTemplateValidator() {}

    public static ValidationResult validate(BridgeTemplate t) {
        List<String> errors = new ArrayList<String>();
        List<String> warnings = new ArrayList<String>();

        if (t == null) {
            errors.add("template is null");
            return new ValidationResult(errors, warnings);
        }

        if (t.name == null || t.name.isEmpty()) errors.add("name is missing");
        if (t.deckSlab == null) errors.add("deckSlab is missing");
        if (t.segmentLength < 1) errors.add("segmentLength must be >= 1");
        if (t.blocks == null) errors.add("blocks list is null");

        if (t.waterOnly != null && !VALID_WATER_ONLY.contains(t.waterOnly.toUpperCase(Locale.ROOT))) {
            errors.add("waterOnly '" + t.waterOnly + "' is invalid — must be one of: BOTH, YES, NO");
        }

        if (t.biomeTemperatureCategories != null) {
            for (String cat : t.biomeTemperatureCategories) {
                if (cat == null) continue;
                if (!VALID_TEMP_CATEGORIES.contains(cat.toUpperCase(Locale.ROOT))) {
                    errors.add("biomeTemperatureCategory '" + cat + "' is invalid — must be one of: "
                            + VALID_TEMP_CATEGORIES);
                }
            }
        }

        if (t.biomes != null) {
            for (String biomeName : t.biomes) {
                if (biomeName == null || biomeName.isEmpty()) continue;
                if (!isBiomeKnown(biomeName)) {
                    warnings.add("biome '" + biomeName + "' not found in registry (may be unloaded or modded)");
                }
            }
        }

        if (t.minLength != null && t.maxLength != null && t.minLength > 0 && t.maxLength > 0
                && t.minLength > t.maxLength) {
            errors.add("minLength (" + t.minLength + ") > maxLength (" + t.maxLength + ")");
        }

        return new ValidationResult(errors, warnings);
    }

    private static boolean isBiomeKnown(String name) {
        if (name.contains(":")) {
            try {
                return ForgeRegistries.BIOMES.getValue(new ResourceLocation(name)) != null;
            } catch (Exception ignored) {
                return false;
            }
        }

        String lower = name.toLowerCase(Locale.ROOT);
        for (Biome b : ForgeRegistries.BIOMES.getValuesCollection()) {
            if (b == null) continue;
            ResourceLocation rl = b.getRegistryName();
            if (rl != null) {
                if (rl.getPath().equalsIgnoreCase(lower)) return true;
                if (rl.toString().equalsIgnoreCase(name)) return true;
            }
        }
        return false;
    }

    public static final class ValidationResult {
        public final List<String> errors;
        public final List<String> warnings;

        public ValidationResult(List<String> errors, List<String> warnings) {
            this.errors = errors;
            this.warnings = warnings;
        }

        public boolean hasErrors() { return !errors.isEmpty(); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }
}

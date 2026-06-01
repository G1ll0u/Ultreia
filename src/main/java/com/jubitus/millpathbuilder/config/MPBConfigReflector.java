package com.jubitus.millpathbuilder.config;

import net.minecraftforge.common.config.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

public final class MPBConfigReflector {

    public static List<ConfigEntry> buildEntries() {
        List<ConfigEntry> result = new ArrayList<>();
        for (Field catField : MPBConfig.class.getDeclaredFields()) {
            int mods = catField.getModifiers();
            if (!Modifier.isStatic(mods) || !Modifier.isPublic(mods)) continue;
            catField.setAccessible(true);
            Object catValue;
            try { catValue = catField.get(null); } catch (Exception e) { continue; }
            if (catValue == null) continue;
            Class<?> catClass = catValue.getClass();
            if (catClass.getEnclosingClass() != MPBConfig.class) continue;

            String category = catField.getName();
            for (Field f : catClass.getDeclaredFields()) {
                if (!Modifier.isPublic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object owner = Modifier.isStatic(f.getModifiers()) ? null : catValue;

                String comment = extractComment(f);
                Class<?> type = f.getType();
                double rMin = Double.NEGATIVE_INFINITY, rMax = Double.POSITIVE_INFINITY;
                boolean hasRange = false;

                Config.RangeInt ri = f.getAnnotation(Config.RangeInt.class);
                if (ri != null) { rMin = ri.min(); rMax = ri.max(); hasRange = true; }
                Config.RangeDouble rd = f.getAnnotation(Config.RangeDouble.class);
                if (rd != null) { rMin = rd.min(); rMax = rd.max(); hasRange = true; }

                String value = readFieldAsString(f, owner);
                if (value == null) continue;

                result.add(new ConfigEntry(category, f.getName(), comment, type,
                        rMin, rMax, hasRange, f, owner, value));
            }
        }
        return result;
    }

    static String extractComment(Field f) {
        Config.Comment c = f.getAnnotation(Config.Comment.class);
        if (c == null) return "";
        return String.join(" ", c.value());
    }

    static String readFieldAsString(Field f, Object owner) {
        try {
            Object val = f.get(owner);
            if (val instanceof String[]) return String.join(",", (String[]) val);
            return val == null ? "" : String.valueOf(val);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean applyValue(ConfigEntry entry, String raw) {
        try {
            Object parsed = parse(raw.trim(), entry.type);
            if (parsed == null) return false;
            entry.field.set(entry.owner, parsed);
            entry.currentValue = raw.trim();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static Object parse(String raw, Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(raw);
        if (type == int.class || type == Integer.class) return Integer.parseInt(raw);
        if (type == double.class || type == Double.class) return Double.parseDouble(raw);
        if (type == float.class || type == Float.class) return Float.parseFloat(raw);
        if (type == long.class || type == Long.class) return Long.parseLong(raw);
        if (type == String.class) return raw;
        if (type == String[].class) {
            if (raw.isEmpty()) return new String[0];
            String[] parts = raw.split(",");
            for (int i = 0; i < parts.length; i++) parts[i] = parts[i].trim();
            return parts;
        }
        return null;
    }

    public static Map<String, String> toMap(List<ConfigEntry> entries) {
        Map<String, String> map = new LinkedHashMap<>();
        for (ConfigEntry e : entries) map.put(e.path, e.currentValue);
        return map;
    }

    public static void applyAll(List<ConfigEntry> entries, Map<String, String> values) {
        for (ConfigEntry e : entries) {
            String v = values.get(e.path);
            if (v != null) applyValue(e, v);
        }
    }
}

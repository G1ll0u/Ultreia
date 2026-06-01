package com.jubitus.millpathbuilder.config;

import com.jubitus.millpathbuilder.MillPathBuilderMod;
import net.minecraftforge.fml.common.Loader;

import java.io.*;
import java.util.*;

public final class MPBConfigPersistence {

    private static File getOverrideFile() {
        return new File(Loader.instance().getConfigDir(), "millpathbuilder/mpb_overrides.properties");
    }

    public static void save(Map<String, String> values) {
        File f = getOverrideFile();
        f.getParentFile().mkdirs();
        Properties props = new Properties();
        props.putAll(values);
        try (Writer w = new FileWriter(f)) {
            props.store(w, "MPB live config overrides — edited via GUI");
        } catch (IOException e) {
            MillPathBuilderMod.LOGGER.error("MPBConfigPersistence: failed to save overrides", e);
        }
    }

    public static Map<String, String> load() {
        File f = getOverrideFile();
        if (!f.exists()) return Collections.emptyMap();
        Properties props = new Properties();
        try (Reader r = new FileReader(f)) {
            props.load(r);
        } catch (IOException e) {
            MillPathBuilderMod.LOGGER.error("MPBConfigPersistence: failed to load overrides", e);
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) map.put(key, props.getProperty(key));
        return map;
    }

    public static void applyOnStartup() {
        Map<String, String> saved = load();
        if (saved.isEmpty()) return;
        List<ConfigEntry> entries = MPBConfigReflector.buildEntries();
        MPBConfigReflector.applyAll(entries, saved);
        MillPathBuilderMod.LOGGER.info("MPBConfigPersistence: applied {} GUI overrides.", saved.size());
    }
}

package com.jubitus.millpathbuilder.gen.bridge.template;

import com.jubitus.millpathbuilder.MillPathBuilderMod;
import com.jubitus.millpathbuilder.Tags;
import com.jubitus.millpathbuilder.config.MPBConfig;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.common.Loader;

import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class BridgeTemplateRegistry {

    private static volatile List<BridgeTemplate> cachedTemplates = null;

    private BridgeTemplateRegistry() {}

    public static File getBridgeDir() {
        return new File(Loader.instance().getConfigDir(), "millpathbuilder/bridges");
    }

    public static File getDebugDir() {
        return new File(getBridgeDir(), "debug");
    }

    public static File getTemplateFile(String rawName) {
        return new File(getBridgeDir(), sanitizeName(rawName) + ".json");
    }

    public static String sanitizeName(String rawName) {
        if (rawName == null) return "";
        String s = rawName.trim().toLowerCase();
        s = s.replaceAll("[^a-z0-9_\\-]", "_");
        while (s.contains("__")) s = s.replace("__", "_");
        return s;
    }

    public static void saveTemplate(BridgeTemplate template) throws IOException {
        if (template == null) throw new IOException("template is null");
        template.name = sanitizeName(template.name);
        BridgeTemplateIO.save(getTemplateFile(template.name), template);
        invalidateTemplateCache();
    }

    public static void invalidateTemplateCache() {
        cachedTemplates = null;
    }

    /** Returns all templates from disk, using an in-memory cache. */
    public static List<BridgeTemplate> loadAllTemplates() {
        if (cachedTemplates != null) return cachedTemplates;

        List<BridgeTemplate> out = new ArrayList<BridgeTemplate>();
        for (String name : listTemplateNames()) {
            BridgeTemplate t = loadTemplate(name);
            if (t != null) out.add(t);
        }
        cachedTemplates = Collections.unmodifiableList(out);
        return cachedTemplates;
    }

    @Nullable
    public static BridgeTemplate loadTemplate(String rawName) {
        String name = sanitizeName(rawName);
        if (name.isEmpty()) return null;

        File file = getTemplateFile(name);
        if (!file.isFile()) return null;

        try {
            BridgeTemplate t = BridgeTemplateIO.load(file);
            if (t == null) return null;
            t.name = sanitizeName(t.name != null ? t.name : name);
            return t;
        } catch (Throwable t) {
            MillPathBuilderMod.LOGGER.error("Failed to load bridge template '{}': {}", name, t.getMessage());
            return null;
        }
    }

    @Nullable
    public static BridgeTemplate getDefaultTemplate() {
        String name = MPBConfig.BRIDGES.defaultTemplateName;
        if (name == null || name.trim().isEmpty()) return null;
        return loadTemplate(name);
    }

    public static boolean setDefaultTemplate(String rawName) {
        String name = sanitizeName(rawName);
        if (name.isEmpty()) return false;
        if (!getTemplateFile(name).isFile()) return false;

        MPBConfig.BRIDGES.defaultTemplateName = name;
        ConfigManager.sync(Tags.MOD_ID, Config.Type.INSTANCE);
        return true;
    }

    public static List<String> listTemplateNames() {
        File dir = getBridgeDir();
        if (!dir.isDirectory()) return Collections.emptyList();

        String[] files = dir.list();
        if (files == null || files.length == 0) return Collections.emptyList();

        List<String> out = new ArrayList<String>();
        Arrays.sort(files);
        for (String s : files) {
            if (s == null || !s.endsWith(".json")) continue;
            if ("debug".equalsIgnoreCase(s)) continue;
            out.add(s.substring(0, s.length() - 5));
        }
        return out;
    }
}

package com.jubitus.millpathbuilder.gen.bridge.template;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class BridgeTemplateIO {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private BridgeTemplateIO() {
    }

    public static BridgeTemplate load(File file) throws IOException {
        Reader reader = null;
        try {
            reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8);
            return GSON.fromJson(reader, BridgeTemplate.class);
        } finally {
            if (reader != null) reader.close();
        }
    }

    public static void save(File file, BridgeTemplate template) throws IOException {
        if (template == null) throw new IOException("template is null");
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create directory: " + parent.getAbsolutePath());
        }

        template.formatVersion = BridgeTemplate.FORMAT_VERSION;

        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
            GSON.toJson(template, writer);
        } finally {
            if (writer != null) writer.close();
        }
    }

    public static void saveJson(File file, Object obj) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create directory: " + parent.getAbsolutePath());
        }

        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
            GSON.toJson(obj, writer);
        } finally {
            if (writer != null) writer.close();
        }
    }
}
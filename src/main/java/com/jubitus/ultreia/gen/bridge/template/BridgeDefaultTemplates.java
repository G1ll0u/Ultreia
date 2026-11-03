package com.jubitus.ultreia.gen.bridge.template;

import com.jubitus.ultreia.Ultreia;

import java.io.*;

public final class BridgeDefaultTemplates {

    private static final String[] NAMES = {
        "default", "default_big",
        "birch",
        "jungle",
        "oak", "oak_big",
        "redsand",
        "sand",
        "spruce", "spruce_big",
        "stone", "stone_big"
    };

    private BridgeDefaultTemplates() {}

    /** Copies each bundled default template to the config dir if the file does not already exist. */
    public static void deployIfAbsent() {
        for (String name : NAMES) {
            File dest = BridgeTemplateRegistry.getTemplateFile(name);
            if (dest.exists()) continue;

            String resource = "/assets/ultreia/default_templates/" + name + ".json";
            try {
                copyResource(resource, dest);
                Ultreia.LOGGER.info("Deployed default bridge template: {}", name);
            } catch (IOException e) {
                Ultreia.LOGGER.warn("Failed to deploy default bridge template '{}': {}", name, e.getMessage());
            }
        }
    }

    private static void copyResource(String resourcePath, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Could not create directory: " + parent.getAbsolutePath());
        }
        InputStream in = BridgeDefaultTemplates.class.getResourceAsStream(resourcePath);
        if (in == null) throw new IOException("Bundled resource not found: " + resourcePath);
        try {
            OutputStream out = new FileOutputStream(dest);
            try {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } finally {
                out.close();
            }
        } finally {
            in.close();
        }
    }
}

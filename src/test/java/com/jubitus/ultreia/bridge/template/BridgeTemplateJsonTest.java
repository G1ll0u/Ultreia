package com.jubitus.ultreia.bridge.template;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Template JSON format must not change: every bundled template survives load → save with all its data. */
class BridgeTemplateJsonTest {

    private static final Path BUNDLED = new File("src/main/resources/assets/ultreia/default_templates").toPath();

    @Test
    void bundledTemplatesRoundTrip(@TempDir Path tmp) throws IOException {
        File[] files = BUNDLED.toFile().listFiles((d, n) -> n.endsWith(".json"));
        assertNotNull(files);
        assertTrue(files.length > 0, "no bundled templates found");

        for (File f : files) {
            BridgeTemplate t = BridgeTemplateIO.load(f);
            assertNotNull(t, f.getName());

            File once = tmp.resolve("once_" + f.getName()).toFile();
            BridgeTemplateIO.save(once, t);
            File twice = tmp.resolve("twice_" + f.getName()).toFile();
            BridgeTemplateIO.save(twice, BridgeTemplateIO.load(once));

            assertEquals(read(once), read(twice), "save is not stable: " + f.getName());
            assertContains(parse(f), parse(once), f.getName());
        }
    }

    /** Every value present in the original file is present and equal after a save (nulls may be added). */
    private static void assertContains(JsonElement original, JsonElement saved, String where) {
        if (original.isJsonObject()) {
            assertTrue(saved.isJsonObject(), where);
            JsonObject so = saved.getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : original.getAsJsonObject().entrySet()) {
                if (e.getValue().isJsonNull()) continue;
                assertTrue(so.has(e.getKey()), "key lost: " + where + "." + e.getKey());
                assertContains(e.getValue(), so.get(e.getKey()), where + "." + e.getKey());
            }
        } else if (original.isJsonArray()) {
            assertTrue(saved.isJsonArray(), where);
            assertEquals(original.getAsJsonArray().size(), saved.getAsJsonArray().size(), where);
            for (int i = 0; i < original.getAsJsonArray().size(); i++) {
                assertContains(original.getAsJsonArray().get(i), saved.getAsJsonArray().get(i), where + "[" + i + "]");
            }
        } else if (original.isJsonPrimitive() && original.getAsJsonPrimitive().isNumber()) {
            assertEquals(original.getAsDouble(), saved.getAsDouble(), 0.0, where);
        } else {
            assertEquals(original, saved, where);
        }
    }

    private static JsonElement parse(File f) throws IOException {
        return new JsonParser().parse(read(f));
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}

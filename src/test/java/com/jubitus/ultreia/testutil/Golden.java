package com.jubitus.ultreia.testutil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Snapshot comparison against src/test/resources/golden/. A snapshot is rewritten only when the
 * environment variable ULTREIA_UPDATE_GOLDEN=1 is set, and only for a change the user approved.
 */
public final class Golden {

    private static final Path DIR = Paths.get("src", "test", "resources", "golden");

    private Golden() {}

    public static void check(String name, String actual) throws IOException {
        Path file = DIR.resolve(name + ".txt");
        if ("1".equals(System.getenv("ULTREIA_UPDATE_GOLDEN"))) {
            Files.createDirectories(DIR);
            Files.write(file, actual.getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (!Files.exists(file)) {
            fail("No golden snapshot " + file + " (run once with ULTREIA_UPDATE_GOLDEN=1)");
        }
        String expected = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertEquals(expected, actual, "Golden snapshot changed: " + name);
    }
}

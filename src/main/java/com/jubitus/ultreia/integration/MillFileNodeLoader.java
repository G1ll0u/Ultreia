package com.jubitus.ultreia.integration;

import com.jubitus.ultreia.Ultreia;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.SaveHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class MillFileNodeLoader {

    private MillFileNodeLoader() {}

    public static List<BlockPos> loadNodesFromFiles(World world, boolean includeLoneBuildings) {
        File worldDir = getWorldDir(world);
        if (worldDir == null) return new ArrayList<>();

        File millDir = new File(worldDir, "millenaire");
        List<BlockPos> out = new ArrayList<>();

        File villages = new File(millDir, "villages.txt");
        out.addAll(readListFile(villages));

        if (includeLoneBuildings) {
            File lone = new File(millDir, "lonebuildings.txt");
            out.addAll(readListFile(lone));
        }

        return out;
    }

    private static List<BlockPos> readListFile(File file) {
        List<BlockPos> out = new ArrayList<>();
        if (file == null || !file.exists()) return out;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // Format: name;x/y/z;type;culture;generatedFor
                String[] parts = line.split(";");
                if (parts.length < 2) continue;

                String[] xyz = parts[1].split("/");
                if (xyz.length < 3) continue;

                int x = Integer.parseInt(xyz[0]);
                int y = Integer.parseInt(xyz[1]);
                int z = Integer.parseInt(xyz[2]);

                out.add(new BlockPos(x, y, z));
            }
        } catch (Exception e) {
            Ultreia.LOGGER.warn("Failed reading Millenaire file: {}", file.getAbsolutePath(), e);
        }

        return out;
    }

    private static File getWorldDir(World world) {
        try {
            // In 1.12.2 this is commonly a SaveHandler
            if (world.getSaveHandler() instanceof SaveHandler) {
                return ((SaveHandler) world.getSaveHandler()).getWorldDirectory();
            }
        } catch (Exception ignored) {}
        return null;
    }
}
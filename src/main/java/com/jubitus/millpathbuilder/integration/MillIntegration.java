package com.jubitus.millpathbuilder.integration;

import com.jubitus.millpathbuilder.MillPathBuilderMod;
import com.jubitus.millpathbuilder.config.MPBConfig;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.world.MillWorldData;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public final class MillIntegration {

    private static final Map<Integer, MillWorldData> BY_DIM = new ConcurrentHashMap<>();
    private static final Set<Integer> LOAD_TRIED_DIM = ConcurrentHashMap.newKeySet();

    private MillIntegration() {}

    public static void register(MillWorldData data) {
        if (data == null || data.world == null) return;
        int dim = data.world.provider.getDimension();
        BY_DIM.put(dim, data);
    }

    public static MillWorldData get(World world) {
        if (world == null || world.provider == null) return null;
        return BY_DIM.get(world.provider.getDimension());
    }

    /**
     * Ensures MillWorldData has loaded villages/lonebuildings lists from disk.
     * Safe to call multiple times; will only attempt once per dimension.
     */
    public static MillWorldData ensureLoaded(World world) {
        MillWorldData data = get(world);
        if (data == null) return null;

        if (world.isRemote) return data;

        int dim = world.provider.getDimension();

        // Only attempt once per dimension to avoid repeated disk reads
        if (LOAD_TRIED_DIM.contains(dim)) return data;
        LOAD_TRIED_DIM.add(dim);

        try {
            int v = (data.villagesList != null) ? data.villagesList.pos.size() : 0;
            int l = (data.loneBuildingsList != null) ? data.loneBuildingsList.pos.size() : 0;

            if (v == 0 && l == 0) {
                MillPathBuilderMod.LOGGER.info("MillWorldData lists are empty -> calling loadData() once for dim={}", dim);
                data.loadData();

                int v2 = (data.villagesList != null) ? data.villagesList.pos.size() : 0;
                int l2 = (data.loneBuildingsList != null) ? data.loneBuildingsList.pos.size() : 0;

                MillPathBuilderMod.LOGGER.info("After loadData(): villages={}, loneBuildings={}", v2, l2);
            }
        } catch (Throwable t) {
            MillPathBuilderMod.LOGGER.error("Failed to call MillWorldData.loadData()", t);
        }

        return data;
    }

    public static List<Point> getAllNodePoints(World world) {
        MillWorldData data = ensureLoaded(world);
        if (data == null) return Collections.emptyList();

        List<Point> pts = new ArrayList<>();
        pts.addAll(data.villagesList.pos);

        if (MPBConfig.GENERAL.includeLoneBuildings) {
            pts.addAll(data.loneBuildingsList.pos);
        }

        LinkedHashMap<String, Point> uniq = new LinkedHashMap<>();
        for (Point p : pts) {
            String k = p.getiX() + "," + p.getiY() + "," + p.getiZ();
            uniq.put(k, p);
        }
        return new ArrayList<>(uniq.values());
    }

    public static List<BlockPos> getAllNodePositions(World world) {
        List<BlockPos> out = new ArrayList<>();

        MillWorldData data = get(world);
        if (data != null) {
            // runtime lists
            for (Point p : data.villagesList.pos) {
                out.add(new BlockPos(p.getiX(), p.getiY(), p.getiZ()));
            }
            if (MPBConfig.GENERAL.includeLoneBuildings) {
                for (Point p : data.loneBuildingsList.pos) {
                    out.add(new BlockPos(p.getiX(), p.getiY(), p.getiZ()));
                }
            }
        }

        // Fallback: if runtime is empty, read files directly
        if (out.isEmpty()) {
            List<BlockPos> fromFiles = MillFileNodeLoader.loadNodesFromFiles(world, MPBConfig.GENERAL.includeLoneBuildings);
            out.addAll(fromFiles);
            MillPathBuilderMod.LOGGER.info("Millenaire nodes loaded from files: {}", out.size());
        }

        // De-dup
        LinkedHashMap<String, BlockPos> uniq = new LinkedHashMap<>();
        for (BlockPos p : out) {
            uniq.put(p.getX() + "," + p.getZ(), new BlockPos(p.getX(), p.getY(), p.getZ()));
        }
        return new ArrayList<>(uniq.values());
    }

    public static void logNodeSummary(World world) {
        MillWorldData data = get(world);
        if (data == null) {
            MillPathBuilderMod.LOGGER.warn("MillWorldData not found for this world/dimension.");
            return;
        }
        MillPathBuilderMod.LOGGER.info("Millenaire nodes (runtime): villages={}, loneBuildings={}, includeLoneBuildings={}",
                data.villagesList.pos.size(),
                data.loneBuildingsList.pos.size(),
                MPBConfig.GENERAL.includeLoneBuildings);
    }
}
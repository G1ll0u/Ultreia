package com.jubitus.ultreia.mill;

import com.jubitus.ultreia.Ultreia;
import com.jubitus.ultreia.config.UltreiaConfig;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.millenaire.common.entity.MillVillager;
import org.millenaire.common.forge.Mill;
import org.millenaire.common.utilities.MillCommonUtilities;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Millénaire settlements (villages and lone buildings) from its world data. Only {@link Villages} calls this. */
final class MillSettlements {

    private static final Set<Integer> LOAD_TRIED_DIM = ConcurrentHashMap.newKeySet();

    private MillSettlements() {}

    /**
     * Millénaire's world data: Millénaire's own accessor first, else the instance captured when it was created.
     * The first time per dimension, empty village lists are reloaded from disk once.
     */
    @Nullable
    static MillWorldData wd(World world) {
        MillWorldData data = null;
        try {
            WorldServer ws = (world instanceof WorldServer)
                    ? (WorldServer) world
                    : world.getMinecraftServer().getWorld(world.provider.getDimension());
            if (ws != null) data = Mill.getMillWorld(ws);
        } catch (Throwable ignored) {
        }
        if (data == null) data = MillWorldDataRegistry.get(world);
        if (data != null && !world.isRemote) loadOnceIfEmpty(data, world.provider.getDimension());
        return data;
    }

    private static void loadOnceIfEmpty(MillWorldData data, int dim) {
        if (!LOAD_TRIED_DIM.add(dim)) return;
        try {
            int v = (data.villagesList != null) ? data.villagesList.pos.size() : 0;
            int l = (data.loneBuildingsList != null) ? data.loneBuildingsList.pos.size() : 0;
            if (v == 0 && l == 0) {
                Ultreia.LOGGER.info("MillWorldData lists are empty -> calling loadData() once for dim={}", dim);
                data.loadData();
                int v2 = (data.villagesList != null) ? data.villagesList.pos.size() : 0;
                int l2 = (data.loneBuildingsList != null) ? data.loneBuildingsList.pos.size() : 0;
                Ultreia.LOGGER.info("After loadData(): villages={}, loneBuildings={}", v2, l2);
            }
        } catch (Throwable t) {
            Ultreia.LOGGER.error("Failed to call MillWorldData.loadData()", t);
        }
    }

    /** Forgets the previous world, so the next world opened in the same session loads its own. */
    static void clear() {
        MillWorldDataRegistry.clear();
        LOAD_TRIED_DIM.clear();
    }

    /** Village and (if configured) lone-building centres, one per XZ; read from Millénaire's files when it has none. */
    static List<BlockPos> nodePositions(World world) {
        List<BlockPos> out = new ArrayList<>();

        MillWorldData data = wd(world);
        if (data != null) {
            for (Point p : data.villagesList.pos) {
                out.add(new BlockPos(p.getiX(), p.getiY(), p.getiZ()));
            }
            if (UltreiaConfig.GENERAL.includeLoneBuildings) {
                for (Point p : data.loneBuildingsList.pos) {
                    out.add(new BlockPos(p.getiX(), p.getiY(), p.getiZ()));
                }
            }
        }

        if (out.isEmpty()) {
            List<BlockPos> fromFiles = MillFileNodeLoader.loadNodesFromFiles(world, UltreiaConfig.GENERAL.includeLoneBuildings);
            out.addAll(fromFiles);
            Ultreia.LOGGER.info("Millenaire nodes loaded from files: {}", out.size());
        }

        LinkedHashMap<String, BlockPos> uniq = new LinkedHashMap<>();
        for (BlockPos p : out) {
            uniq.put(p.getX() + "," + p.getZ(), new BlockPos(p.getX(), p.getY(), p.getZ()));
        }
        return new ArrayList<>(uniq.values());
    }

    static void logNodeSummary(World world) {
        MillWorldData data = wd(world);
        if (data == null) {
            Ultreia.LOGGER.warn("MillWorldData not found for this world/dimension.");
            return;
        }
        Ultreia.LOGGER.info("Millenaire nodes (runtime): villages={}, loneBuildings={}, includeLoneBuildings={}",
                data.villagesList.pos.size(),
                data.loneBuildingsList.pos.size(),
                UltreiaConfig.GENERAL.includeLoneBuildings);
    }

    /** The settlement whose saved centre is exactly this position. */
    @Nullable
    static Villages.Settlement findExact(World world, BlockPos pos) {
        MillWorldData wd = wd(world);
        if (wd == null) return null;

        if (wd.villagesList != null && wd.villagesList.pos != null) {
            for (int i = 0; i < wd.villagesList.pos.size(); i++) {
                Point p = wd.villagesList.pos.get(i);
                if (p == null || !equalsBlockPos(p, pos)) continue;
                Building th = wd.getBuilding(p);
                if (th != null) return villageOf(wd, th);
                // Town hall not loaded: fall back to the list data.
                String name = safeString(wd.villagesList.names, i, pos.getX() + "/" + pos.getY() + "/" + pos.getZ());
                return new Villages.Settlement(pos, true, name);
            }
        }

        if (wd.loneBuildingsList != null && wd.loneBuildingsList.pos != null) {
            for (int i = 0; i < wd.loneBuildingsList.pos.size(); i++) {
                Point p = wd.loneBuildingsList.pos.get(i);
                if (p == null || !equalsBlockPos(p, pos)) continue;
                String name = safeString(wd.loneBuildingsList.names, i, pos.getX() + "/" + pos.getY() + "/" + pos.getZ());
                return new Villages.Settlement(pos, false, name);
            }
        }
        return null;
    }

    /** The exact settlement at {@code approx}, else the nearest village (3D), else the nearest settlement (XZ). */
    @Nullable
    static BlockPos resolveCenter(World world, BlockPos approx, int maxRadius) {
        Villages.Settlement s = findExact(world, approx);
        if (s == null) s = findNearestVillage3D(world, approx, maxRadius);
        if (s == null) s = findNearest2D(world, approx, maxRadius);
        return s == null ? null : s.center;
    }

    @Nullable
    private static Villages.Settlement findNearestVillage3D(World world, BlockPos origin, int maxRadius) {
        MillWorldData wd = wd(world);
        if (wd == null || wd.villagesList == null || wd.villagesList.pos == null) return null;

        long maxSq = (long) maxRadius * (long) maxRadius;
        Point op = new Point(origin.getX(), origin.getY(), origin.getZ());
        Point bestP = null;
        long bestD = Long.MAX_VALUE;
        for (Point p : wd.villagesList.pos) {
            if (p == null) continue;
            long d = (long) op.distanceToSquared(p);
            if (d <= maxSq && d < bestD) {
                bestD = d;
                bestP = p;
            }
        }
        if (bestP == null) return null;
        Building th = wd.getBuilding(bestP);
        return th != null ? villageOf(wd, th) : null;
    }

    @Nullable
    private static Villages.Settlement findNearest2D(World world, BlockPos pos, int maxRadius) {
        MillWorldData wd = wd(world);
        if (wd == null) return null;

        long best = Long.MAX_VALUE;
        Villages.Settlement bestS = null;
        long maxR2 = (long) maxRadius * (long) maxRadius;

        if (wd.villagesList != null && wd.villagesList.pos != null) {
            for (int i = 0; i < wd.villagesList.pos.size(); i++) {
                Point p = wd.villagesList.pos.get(i);
                if (p == null) continue;
                long d2 = dist2XZ(pos, p);
                if (d2 <= maxR2 && d2 < best) {
                    best = d2;
                    bestS = new Villages.Settlement(toBlockPos(p), true, safeString(wd.villagesList.names, i, ""));
                }
            }
        }
        if (wd.loneBuildingsList != null && wd.loneBuildingsList.pos != null) {
            for (int i = 0; i < wd.loneBuildingsList.pos.size(); i++) {
                Point p = wd.loneBuildingsList.pos.get(i);
                if (p == null) continue;
                long d2 = dist2XZ(pos, p);
                if (d2 <= maxR2 && d2 < best) {
                    best = d2;
                    bestS = new Villages.Settlement(toBlockPos(p), false, safeString(wd.loneBuildingsList.names, i, ""));
                }
            }
        }
        return bestS;
    }

    /** True if a village town hall lies within {@code radiusBlocks} (3D, then XZ: Millénaire's Y can be far off). */
    static boolean isNearVillage(World world, BlockPos pos, int radiusBlocks) {
        MillWorldData wd = wd(world);
        if (wd == null || wd.villagesList == null || wd.villagesList.pos == null) return false;

        final long r2 = (long) radiusBlocks * (long) radiusBlocks;
        for (Point p : wd.villagesList.pos) {
            long dx = p.getiX() - pos.getX();
            long dy = p.getiY() - pos.getY();
            long dz = p.getiZ() - pos.getZ();
            if (dx * dx + dy * dy + dz * dz <= r2) return true;
        }
        for (Point p : wd.villagesList.pos) {
            long dx = p.getiX() - pos.getX();
            long dz = p.getiZ() - pos.getZ();
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    /** True if a village town hall or a lone building lies within {@code radiusBlocks} (3D). */
    static boolean isNearAnySettlement(World world, BlockPos pos, int radiusBlocks) {
        MillWorldData wd = wd(world);
        if (wd == null) return false;

        final long r2 = (long) radiusBlocks * (long) radiusBlocks;
        if (wd.villagesList != null && wd.villagesList.pos != null) {
            for (Point p : wd.villagesList.pos) {
                long dx = p.getiX() - pos.getX();
                long dy = p.getiY() - pos.getY();
                long dz = p.getiZ() - pos.getZ();
                if (dx * dx + dy * dy + dz * dz <= r2) return true;
            }
        }
        if (wd.loneBuildingsList != null && wd.loneBuildingsList.pos != null) {
            for (Point p : wd.loneBuildingsList.pos) {
                long dx = p.getiX() - pos.getX();
                long dy = p.getiY() - pos.getY();
                long dz = p.getiZ() - pos.getZ();
                if (dx * dx + dy * dy + dz * dz <= r2) return true;
            }
        }
        return false;
    }

    /** Villagers whose town hall is the one at this centre, loaded or not. */
    static int population(World world, BlockPos villageCenter) {
        MillWorldData wd = wd(world);
        if (wd == null) return 0;
        Building th = wd.getBuilding(new Point(villageCenter.getX(), villageCenter.getY(), villageCenter.getZ()));
        if (th == null) return 0;

        int count = 0;
        for (MillVillager v : wd.getAllKnownVillagers()) {
            if (v != null && v.getTownHall() == th) count++;
        }
        return count;
    }

    private static Villages.Settlement villageOf(MillWorldData wd, Building th) {
        Integer idx = wd.villagesList.rankByPos.get(th.getPos());
        String name = th.toString();
        if (idx != null) {
            MillCommonUtilities.VillageList vl = wd.villagesList;
            name = safeString(vl.names, idx, name);
        }
        return new Villages.Settlement(toBlockPos(th.getPos()), true, name);
    }

    private static boolean equalsBlockPos(Point p, BlockPos bp) {
        return p.getiX() == bp.getX() && p.getiY() == bp.getY() && p.getiZ() == bp.getZ();
    }

    private static BlockPos toBlockPos(Point p) {
        return new BlockPos(p.getiX(), p.getiY(), p.getiZ());
    }

    private static String safeString(List<String> list, int idx, String def) {
        return (list != null && idx >= 0 && idx < list.size() && list.get(idx) != null) ? list.get(idx) : def;
    }

    private static long dist2XZ(BlockPos a, Point p) {
        long dx = a.getX() - p.getiX();
        long dz = a.getZ() - p.getiZ();
        return dx * dx + dz * dz;
    }
}

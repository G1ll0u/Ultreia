package com.jubitus.ultreia.gen.mill;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import org.millenaire.common.entity.MillVillager;
import org.millenaire.common.forge.Mill;
import org.millenaire.common.utilities.MillCommonUtilities;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.village.BuildingLocation;
import org.millenaire.common.world.MillWorldData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class MillVillageIndex {

    private static final int CACHE_MAX_ENTRIES = 256;
    private static final long CACHE_TTL_TICKS = 20L * 60L * 2L; // 2 minutes (20 ticks/sec)
    private static final int CLEANUP_EVERY_N_CALLS = 200;
    private static final AtomicInteger CACHE_CALLS = new AtomicInteger();


    private static final ConcurrentHashMap<BuildingsCacheKey, BuildingsCacheEntry> BUILDINGS_CACHE =
            new ConcurrentHashMap<>();

    private MillVillageIndex() {
    }

    private static long nowTick(World world) {
        // Prefer world time to avoid System.currentTimeMillis in game loop
        return world.getTotalWorldTime();
    }

    private static void maybeCleanupCache(long nowTick) {
        int n = CACHE_CALLS.incrementAndGet();
        if (n % CLEANUP_EVERY_N_CALLS != 0) return;

        // 1) Remove expired
        for (Map.Entry<BuildingsCacheKey, BuildingsCacheEntry> e : BUILDINGS_CACHE.entrySet()) {
            BuildingsCacheEntry ce = e.getValue();
            if (ce == null || !ce.valid(nowTick)) {
                BUILDINGS_CACHE.remove(e.getKey(), ce);
            }
        }

        // 2) If still too big, drop least-recently-accessed
        int size = BUILDINGS_CACHE.size();
        if (size <= CACHE_MAX_ENTRIES) return;

        int toRemove = size - CACHE_MAX_ENTRIES;
        for (int i = 0; i < toRemove; i++) {
            BuildingsCacheKey worstKey = null;
            long worstAccess = Long.MAX_VALUE;

            for (Map.Entry<BuildingsCacheKey, BuildingsCacheEntry> e : BUILDINGS_CACHE.entrySet()) {
                BuildingsCacheEntry ce = e.getValue();
                if (ce == null) continue;
                long acc = ce.lastAccessTick;
                if (acc < worstAccess) {
                    worstAccess = acc;
                    worstKey = e.getKey();
                }
            }
            if (worstKey == null) break;
            BUILDINGS_CACHE.remove(worstKey);
        }
    }

    /**
     * All known village centers for this world/dimension.
     */
    public static List<BlockPos> getAllVillageCenters(World world) {
        MillWorldData wd = getWD(world);
        if (wd == null || wd.villagesList == null || wd.villagesList.pos == null) {
            return java.util.Collections.emptyList();
        }
        List<BlockPos> out = new ArrayList<>(wd.villagesList.pos.size());
        for (Point p : wd.villagesList.pos) out.add(toBlockPos(p));
        return out;
    }

    @Nullable
    public static MillWorldData getWD(World world) {
        try {
            final WorldServer ws = (world instanceof WorldServer)
                    ? (WorldServer) world
                    : world.getMinecraftServer().getWorld(world.provider.getDimension());
            if (ws == null) return null;
            return Mill.getMillWorld(ws);
        } catch (Throwable t) {
            return null;
        }
    }
    // Tune these:


    // ---------- Public API ----------

    /**
     * Find the VillageInfo whose TH is exactly at that BlockPos (Y ignored unless it differs).
     */

    private static BlockPos toBlockPos(Point p) {
        return new BlockPos(p.getiX(), p.getiY(), p.getiZ());
    }

    /**
     * Best-effort name: exact → nearest3D(128) → nearest2D(160) → "x/y/z".
     */
    public static String nameForApprox(World world, BlockPos approxTH) {
        VillageInfo vi = findExact(world, approxTH);
        if (vi == null) vi = findNearest3D(world, approxTH, 128);
        if (vi == null) vi = findNearest2D(world, approxTH, 160);
        if (vi != null) return vi.name;
        return approxTH.getX() + "/" + approxTH.getY() + "/" + approxTH.getZ();
    }

    /**
     * 3D nearest Town Hall within radius (blocks).
     */
    @Nullable
    public static VillageInfo findNearest3D(World world, BlockPos origin, int maxRadius) {
        MillWorldData wd = getWD(world);
        if (wd == null) return null;
        if (wd.villagesList == null || wd.villagesList.pos == null) return null;

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
        return (th != null) ? buildInfo(wd, th) : null;
    }

    // ---------- Helpers ----------
    @Nullable
    public static String villageNameFor(World world, BlockPos approxTH) {
        MillWorldData wd = getWD(world);
        if (wd == null || wd.villagesList == null) return null;

        // 1) Try exact match first
        for (int i = 0; i < wd.villagesList.pos.size(); i++) {
            Point p = wd.villagesList.pos.get(i);
            if (p.getiX() == approxTH.getX() && p.getiY() == approxTH.getY() && p.getiZ() == approxTH.getZ()) {
                Object name = wd.villagesList.names.get(i);
                return name != null ? name.toString() : null;
            }
        }

        // 2) Fall back to nearest 2D within a tolerance (Y can differ a lot)
        final int maxRadius = 160; // tune if needed
        final long maxSq = (long) maxRadius * (long) maxRadius;
        int ox = approxTH.getX(), oz = approxTH.getZ();
        int bestIdx = -1;
        long bestD = Long.MAX_VALUE;

        for (int i = 0; i < wd.villagesList.pos.size(); i++) {
            Point p = wd.villagesList.pos.get(i);
            long dx = p.getiX() - ox, dz = p.getiZ() - oz;
            long d = dx * dx + dz * dz;
            if (d <= maxSq && d < bestD) {
                bestD = d;
                bestIdx = i;
            }
        }

        if (bestIdx >= 0) {
            Object name = wd.villagesList.names.get(bestIdx);
            return name != null ? name.toString() : null;
        }

        return null;
    }

    public static boolean isLoneBuildingStop(World world, BlockPos center) {
        VillageInfo vi = findExact(world, center);
        return vi != null && !vi.isVillage;
    }

    /**
     * Exact classification by matching the saved centers.
     */
    @Nullable
    public static VillageInfo findExact(World world, BlockPos pos) {
        MillWorldData wd = getWD(world);
        if (wd == null) return null;

        // --- villages: prefer full Building info if we can ---
        if (wd.villagesList != null && wd.villagesList.pos != null) {
            for (int i = 0; i < wd.villagesList.pos.size(); i++) {
                Point p = wd.villagesList.pos.get(i);
                if (p == null) continue;
                if (equalsBlockPos(p, pos)) {
                    // Try to use the actual Town Hall building first
                    Building th = wd.getBuilding(p);
                    if (th != null) {
                        // This uses Building.toString() as a fallback name,
                        // and also fills type/culture properly
                        return buildInfo(wd, th);
                    }

                    // Building not loaded, fall back to the list data
                    String name = safeString(
                            wd.villagesList.names,
                            i,
                            pos.getX() + "/" + pos.getY() + "/" + pos.getZ()
                    );
                    String type = safeString(wd.villagesList.types, i, "");
                    String culture = safeString(wd.villagesList.cultures, i, "");
                    return new VillageInfo(pos, true, name, type, culture);
                }
            }
        }

        // --- lone buildings: still honour exact matches there too ---
        if (wd.loneBuildingsList != null && wd.loneBuildingsList.pos != null) {
            for (int i = 0; i < wd.loneBuildingsList.pos.size(); i++) {
                Point p = wd.loneBuildingsList.pos.get(i);
                if (p == null) continue;
                if (equalsBlockPos(p, pos)) {
                    String name = safeString(
                            wd.loneBuildingsList.names,
                            i,
                            pos.getX() + "/" + pos.getY() + "/" + pos.getZ()
                    );
                    String type = safeString(wd.loneBuildingsList.types, i, "");
                    String culture = safeString(wd.loneBuildingsList.cultures, i, "");
                    return new VillageInfo(pos, false, name, type, culture);
                }
            }
        }

        return null;
    }

    private static boolean equalsBlockPos(Point p, BlockPos bp) {
        return p.getiX() == bp.getX() && p.getiY() == bp.getY() && p.getiZ() == bp.getZ();
    }

    private static VillageInfo buildInfo(MillWorldData wd, Building th) {
        Integer idx = wd.villagesList.rankByPos.get(th.getPos());
        String name = th.toString();
        String culture = "";
        String typeKey = "";
        if (idx != null) {
            MillCommonUtilities.VillageList vl = wd.villagesList;
            name = safeString(vl.names, idx, name);
            culture = safeString(vl.cultures, idx, "");
            typeKey = safeString(vl.types, idx, "");
        }
        Point p = th.getPos();
        return new VillageInfo(
                new BlockPos(p.getiX(), p.getiY(), p.getiZ()),
                true,           // this is a village (Town Hall), not a lone building
                name,
                typeKey,        // <— ctor expects typeKey before cultureKey
                culture
        );
    }

    // helper
    private static String safeString(List<String> list, int idx, String def) {
        return (idx >= 0 && idx < list.size() && list.get(idx) != null) ? list.get(idx) : def;
    }

    /**
     * True if the position is within {@code radiusBlocks} of any **village** Town Hall.
     * Lone buildings are ignored.
     */
    public static boolean isNearVillage(World world, BlockPos pos, int radiusBlocks) {
        MillWorldData wd = getWD(world);
        if (wd == null || wd.villagesList == null || wd.villagesList.pos == null) return false;

        final long r2 = (long) radiusBlocks * (long) radiusBlocks;

        // 1) 3D check against saved Town Hall positions
        for (Point p : wd.villagesList.pos) {
            long dx = p.getiX() - pos.getX();
            long dy = p.getiY() - pos.getY();
            long dz = p.getiZ() - pos.getZ();
            if (dx * dx + dy * dy + dz * dz <= r2) return true;
        }

        // 2) 2D fallback (Y in Millénaire data can differ a lot)
        for (Point p : wd.villagesList.pos) {
            long dx = p.getiX() - pos.getX();
            long dz = p.getiZ() - pos.getZ();
            if (dx * dx + dz * dz <= r2) return true;
        }
        return false;
    }

    /**
     * Variant that treats **either** a village TH or a lone building as “near”.
     * Use if you want travellers to also appear near lone buildings.
     */
    public static boolean isNearAnySettlement(World world, BlockPos pos, int radiusBlocks) {
        MillWorldData wd = getWD(world);
        if (wd == null) return false;

        final long r2 = (long) radiusBlocks * (long) radiusBlocks;

        // Villages (3D)
        if (wd.villagesList != null && wd.villagesList.pos != null) {
            for (Point p : wd.villagesList.pos) {
                long dx = p.getiX() - pos.getX();
                long dy = p.getiY() - pos.getY();
                long dz = p.getiZ() - pos.getZ();
                if (dx * dx + dy * dy + dz * dz <= r2) return true;
            }
        }
        // Lone buildings (3D)
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

    public static boolean hasVillageData(World world) {
        MillWorldData wd = getWD(world);
        return wd != null && wd.villagesList != null && wd.villagesList.pos != null && !wd.villagesList.pos.isEmpty();
    }

    public static List<BlockPos> getBuildingCenters(World world, BlockPos approxTH, int radiusXZ, int radiusY) {
        List<BuildingInfo> infos = listBuildingsAroundVillage(world, approxTH, radiusXZ, radiusY);
        List<BlockPos> out = new ArrayList<>(infos.size());
        for (BuildingInfo bi : infos) out.add(bi.chestPos);
        return out;
    }

    /**
     * Brute-force scan around a village center to find Millénaire buildings and
     * return lightweight {@link BuildingInfo} for each.
     *
     * @param world    MC world
     * @param approxTH approximate Town Hall position (village center),
     *                 can be the result of findExact/findNearest2D/3D, etc.
     * @param radiusXZ horizontal radius (blocks) to scan
     * @param radiusY  vertical radius (blocks up/down from TH Y) to scan
     */
//    public static java.util.List<BuildingInfo> listBuildingsAroundVillage(
//            World world,
//            BlockPos approxTH,
//            int radiusXZ,
//            int radiusY) {
//
//        MillWorldData wd = getWD(world);
//        if (wd == null) {
//            return java.util.Collections.emptyList();
//        }
//
//        // Try to lock to a “real” TH position (in case approxTH is slightly off)
//        VillageInfo vi = findExact(world, approxTH);
//        if (vi == null) vi = findNearest3D(world, approxTH, radiusXZ);
//        if (vi == null) vi = findNearest2D(world, approxTH, radiusXZ);
//
//        if (vi == null) {
//            // No village found around that center; nothing to scan.
//            return java.util.Collections.emptyList();
//        }
//
//        BlockPos center = vi.center;
//        int cx = center.getX();
//        int cy = center.getY();
//        int cz = center.getZ();
//
//        java.util.List<BuildingInfo> result = new ArrayList<>();
//        java.util.Set<Building> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
//
//        // Brute-force scan; reasonable radius keeps it fine performance-wise
//        for (int dx = -radiusXZ; dx <= radiusXZ; dx++) {
//            for (int dz = -radiusXZ; dz <= radiusXZ; dz++) {
//                for (int dy = -radiusY; dy <= radiusY; dy++) {
//                    Point p = new Point(cx + dx, cy + dy, cz + dz);
//                    Building silva = wd.getBuilding(p);
//                    if (silva == null) continue;
//                    if (!seen.add(silva)) continue; // multiple chest blocks → same Building
//
//                    result.add(buildBuildingInfo(silva));
//                }
//            }
//        }
//
//        return result;
//    }
    public static List<BuildingInfo> listBuildingsAroundVillageCached(
            World world,
            BlockPos approxTH,
            int radiusXZ,
            int radiusY
    ) {
        MillWorldData wd = getWD(world);
        if (wd == null) return java.util.Collections.emptyList();

        // Lock to a real village TH position
        VillageInfo vi = findExact(world, approxTH);
        if (vi == null) vi = findNearest3D(world, approxTH, radiusXZ);
        if (vi == null) vi = findNearest2D(world, approxTH, radiusXZ);
        if (vi == null) return java.util.Collections.emptyList();

        BlockPos center = vi.center;
        int dim = world.provider.getDimension();

        long now = nowTick(world);
        BuildingsCacheKey key = new BuildingsCacheKey(dim, center, radiusXZ, radiusY);

        BuildingsCacheEntry cached = BUILDINGS_CACHE.get(key);
        if (cached != null && cached.valid(now)) {
            cached.lastAccessTick = now;
            maybeCleanupCache(now);
            return cached.list;
        }

        // Compute fresh
        List<BuildingInfo> fresh = listBuildingsAroundVillage(world, center, radiusXZ, radiusY);

        // IMPORTANT: store immutable copy so callers can’t mutate the cache
        List<BuildingInfo> frozen =
                java.util.Collections.unmodifiableList(new ArrayList<>(fresh));

        BuildingsCacheEntry entry = new BuildingsCacheEntry(
                frozen,
                now + CACHE_TTL_TICKS,
                now
        );
        BUILDINGS_CACHE.put(key, entry);

        maybeCleanupCache(now);
        return frozen;
    }

    public static List<BuildingInfo> listBuildingsAroundVillage(
            World world,
            BlockPos approxTH,
            int radiusXZ,
            int radiusY) {

        MillWorldData wd = getWD(world);
        if (wd == null) {
            return java.util.Collections.emptyList();
        }

        // Try to lock to a “real” TH position (in case approxTH is slightly off)
        VillageInfo vi = findExact(world, approxTH);
        if (vi == null) vi = findNearest3D(world, approxTH, radiusXZ);
        if (vi == null) vi = findNearest2D(world, approxTH, radiusXZ);

        if (vi == null) {
            // No village found around that center; nothing to scan.
            return java.util.Collections.emptyList();
        }

        BlockPos center = vi.center;
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();

        List<BuildingInfo> result = new ArrayList<>();
        java.util.Set<Building> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

        // Brute-force scan; reasonable radius keeps it fine performance-wise
        for (int dx = -radiusXZ; dx <= radiusXZ; dx++) {
            for (int dz = -radiusXZ; dz <= radiusXZ; dz++) {
                for (int dy = -radiusY; dy <= radiusY; dy++) {
                    Point p = new Point(cx + dx, cy + dy, cz + dz);
                    Building b = wd.getBuilding(p);
                    if (b == null) continue;
                    if (!seen.add(b)) continue; // multiple chest blocks → same Building

                    result.add(buildBuildingInfo(b));
                }
            }
        }

        return result;
    }

    private static BuildingInfo buildBuildingInfo(Building b) {
        // Name used in logs/GUIs
        String displayName = b.toString();

        BuildingLocation loc = b.location; // public field in decompiled code
        String planKey = "";
        String cultureKey = "";

        if (loc != null) {
            if (loc.planKey != null) {
                planKey = loc.planKey;
            }
            if (loc.culture != null && loc.culture.key != null) {
                cultureKey = loc.culture.key;
            }
        }

        // Fallback: if no chestPos set, use building main pos
        Point chest = (loc != null && loc.chestPos != null) ? loc.chestPos : b.getPos();
        BlockPos chestPos = new BlockPos(chest.getiX(), chest.getiY(), chest.getiZ());

        List<String> tags = new ArrayList<>();

        try {
            java.util.Set<String> millTags = b.getTags(); // this is a Set<String>
            if (millTags != null) {
                tags.addAll(millTags); // List <- Set, fine
            }
        } catch (Throwable t) {
            // swallow or log, up to you
        }


        boolean isTownhall = false;
        try {
            isTownhall = b.isTownhall; // public field in Millénaire
        } catch (Throwable ignored) {
        }

        BlockPos townHallCenter = null;
        try {
            org.millenaire.common.utilities.Point thp = b.getTownHallPos();
            if (thp != null) townHallCenter = new BlockPos(thp.getiX(), thp.getiY(), thp.getiZ());
        } catch (Throwable ignored) {}

        return new BuildingInfo(
                chestPos,
                isTownhall,
                planKey,
                cultureKey,
                displayName,
                tags,
                loc,
                townHallCenter
        );
    }

    public static List<BuildingInfo> getBuildingsWithTag(World world, BlockPos approxTH,
                                                         int radiusXZ, int radiusY,
                                                         String tag) {
        List<BuildingInfo> all = listBuildingsAroundVillageCached(world, approxTH, radiusXZ, radiusY);
        List<BuildingInfo> out = new ArrayList<>();
        for (BuildingInfo bi : all) {
            if (bi.tags.contains(tag)) out.add(bi);
        }
        return out;
    }

    static boolean isGatePlanKey(String planKey) {
        if (planKey == null) return false;
        return planKey.contains("borderpost_gate")
            || planKey.contains("full_wall_gateway")
            || planKey.contains("full_wall_slope_right")
            || planKey.contains("full_wall_slope_left");
    }

    /**
     * Returns BuildingInfo for all gate-type buildings belonging to the village at approxTH.
     * Gate types: borderpost_gate, full_wall_gateway, full_wall_slope_right, full_wall_slope_left.
     * Buildings whose townHallCenter is too far from the village center are filtered out to
     * prevent gates from neighbouring villages being included.
     */
    public static List<BuildingInfo> getVillageGateInfos(World world, BlockPos approxTH, int radiusXZ, int radiusY) {
        List<BuildingInfo> all = listBuildingsAroundVillageCached(world, approxTH, radiusXZ, radiusY);

        VillageInfo vi = findExact(world, approxTH);
        if (vi == null) vi = findNearest3D(world, approxTH, radiusXZ);
        if (vi == null) vi = findNearest2D(world, approxTH, radiusXZ);
        BlockPos center = (vi != null) ? vi.center : approxTH;
        List<BuildingInfo> out = new ArrayList<>();
        for (BuildingInfo bi : all) {
            if (!isGatePlanKey(bi.planKey)) continue;
            if (bi.townHallCenter != null) {
                long dx = bi.townHallCenter.getX() - center.getX();
                long dz = bi.townHallCenter.getZ() - center.getZ();
                if (dx * dx + dz * dz > 256L * 256L) continue; // belongs to a different village
            }
            out.add(bi);
        }
        return out;
    }

    public static List<BlockPos> getVillageGates(World world,
                                                 BlockPos approxTH,
                                                 int radiusXZ,
                                                 int radiusY) {
        List<BuildingInfo> infos = getVillageGateInfos(world, approxTH, radiusXZ, radiusY);
        List<BlockPos> out = new ArrayList<>(infos.size());
        for (BuildingInfo bi : infos) out.add(bi.chestPos);
        return out;
    }


    /**
     * Nearest (XZ) within a max radius; returns which list it came from.
     */
    @Nullable
    public static VillageInfo findNearest2D(World world, BlockPos pos, int maxRadius) {
        MillWorldData wd = getWD(world);
        if (wd == null) return null;

        long best = Long.MAX_VALUE;
        VillageInfo bestVI = null;
        long maxR2 = (long) maxRadius * (long) maxRadius;

        // mill
        if (wd.villagesList != null && wd.villagesList.pos != null) {
            for (int i = 0; i < wd.villagesList.pos.size(); i++) {
                Point p = wd.villagesList.pos.get(i);
                if (p == null) continue;
                long d2 = dist2XZ(pos, p);
                if (d2 <= maxR2 && d2 < best) {
                    best = d2;
                    bestVI = new VillageInfo(
                            new BlockPos(p.getiX(), p.getiY(), p.getiZ()),
                            true,
                            safeString(wd.villagesList.names, i, ""),
                            safeString(wd.villagesList.types, i, ""),
                            safeString(wd.villagesList.cultures, i, "")
                    );
                }
            }
        }

        // lone buildings
        if (wd.loneBuildingsList != null && wd.loneBuildingsList.pos != null) {
            for (int i = 0; i < wd.loneBuildingsList.pos.size(); i++) {
                Point p = wd.loneBuildingsList.pos.get(i);
                if (p == null) continue;
                long d2 = dist2XZ(pos, p);
                if (d2 <= maxR2 && d2 < best) {
                    best = d2;
                    bestVI = new VillageInfo(
                            new BlockPos(p.getiX(), p.getiY(), p.getiZ()),
                            false,
                            safeString(wd.loneBuildingsList.names, i, ""),
                            safeString(wd.loneBuildingsList.types, i, ""),
                            safeString(wd.loneBuildingsList.cultures, i, "")
                    );
                }
            }
        }

        return bestVI;
    }

    public static int countVillagersInVillage(World world, BlockPos villageCenter) {
        MillWorldData wd = getWD(world);
        if (wd == null) return 0;

        // Find the Town Hall building for that center
        Building th = getTownHallBuilding(wd, villageCenter);
        if (th == null) return 0;

        int count = 0;
        for (MillVillager v : wd.getAllKnownVillagers()) {
            if (v == null) continue;
            Building vTh = v.getTownHall();
            if (vTh == th) { // same Building instance
                count++;
            }
        }
        return count;
    }

    private static long dist2XZ(BlockPos a, Point p) {
        long dx = a.getX() - p.getiX();
        long dz = a.getZ() - p.getiZ();
        return dx * dx + dz * dz;
    }

    @Nullable
    public static Building getTownHallBuilding(MillWorldData wd, BlockPos center) {
        if (wd == null) return null;
        Point p = new Point(center.getX(), center.getY(), center.getZ());
        return wd.getBuilding(p);
    }


    /**
     * Same as above but takes an already-resolved MillWorldData.
     */
    @Nullable
    public static Building findAnyTownHallNear(MillWorldData wd, BlockPos center, int radiusXZ) {
        if (wd == null || wd.villagesList == null || wd.villagesList.pos == null) {
            return null;
        }

        int cx = center.getX();
        int cz = center.getZ();
        int r2 = radiusXZ * radiusXZ;

        for (Point p : wd.villagesList.pos) {
            if (p == null) continue;

            int dx = p.getiX() - cx;
            int dz = p.getiZ() - cz;
            if (dx * dx + dz * dz > r2) continue;

            Building b = wd.getBuilding(p);
            if (b != null && b.isTownhall) {
                return b;
            }
        }
        return null;
    }


    private static final class BuildingsCacheKey {
        final int dim;
        final int x, y, z;
        final int rxz, ry;

        BuildingsCacheKey(int dim, BlockPos center, int rxz, int ry) {
            this.dim = dim;
            this.x = center.getX();
            this.y = center.getY();
            this.z = center.getZ();
            this.rxz = rxz;
            this.ry = ry;
        }

        @Override
        public int hashCode() {
            int h = dim;
            h = 31 * h + x;
            h = 31 * h + y;
            h = 31 * h + z;
            h = 31 * h + rxz;
            h = 31 * h + ry;
            return h;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof BuildingsCacheKey)) return false;
            BuildingsCacheKey k = (BuildingsCacheKey) o;
            return dim == k.dim && x == k.x && y == k.y && z == k.z && rxz == k.rxz && ry == k.ry;
        }
    }

    private static final class BuildingsCacheEntry {
        final List<BuildingInfo> list; // store immutable
        final long expiresAtTick;
        volatile long lastAccessTick;

        BuildingsCacheEntry(List<BuildingInfo> list, long expiresAtTick, long nowTick) {
            this.list = list;
            this.expiresAtTick = expiresAtTick;
            this.lastAccessTick = nowTick;
        }

        boolean valid(long nowTick) {
            return nowTick <= expiresAtTick;
        }
    }

    /**
     * Lightweight view of a village (Town Hall).
     */
    public static final class VillageInfo {
        public final BlockPos center;
        public final boolean isVillage;     // true = village, false = lone building
        public final String name;
        public final String typeKey;
        public final String cultureKey;

        VillageInfo(BlockPos c, boolean v, String n, String t, String cu) {
            this.center = c;
            this.isVillage = v;
            this.name = n;
            this.typeKey = t;
            this.cultureKey = cu;
        }
    }

    public static final class BuildingInfo {
        /**
         * Chest position / internal anchor of the building.
         */
        public final BlockPos chestPos;
        /**
         * True if this building is the Town Hall of a village.
         */
        public final boolean isTownhall;
        /**
         * Millénaire internal plan key, e.g. "norman_farm", etc.
         */
        public final String planKey;
        /**
         * Culture key, e.g. "norman", "indian", etc.
         */
        public final String cultureKey;
        /**
         * Display name (same thing TH uses in tooltips / debug).
         */
        public final String displayName;
        /**
         * All tags on this building (e.g. “shop”, “pujas”, “sacrifices”…).
         */
        public final List<String> tags;
        /**
         * Raw Millénaire location object, if you need min/max bounds, etc.
         */
        public final BuildingLocation location;
        /**
         * Position of the Town Hall this building belongs to. Used to disambiguate
         * buildings from neighbouring villages when search radii overlap.
         */
        @javax.annotation.Nullable
        public final BlockPos townHallCenter;

        public BuildingInfo(BlockPos chestPos,
                            boolean isTownhall,
                            String planKey,
                            String cultureKey,
                            String displayName,
                            List<String> tags,
                            @Nullable BuildingLocation location) {
            this(chestPos, isTownhall, planKey, cultureKey, displayName, tags, location, null);
        }

        public BuildingInfo(BlockPos chestPos,
                            boolean isTownhall,
                            String planKey,
                            String cultureKey,
                            String displayName,
                            List<String> tags,
                            @Nullable BuildingLocation location,
                            @Nullable BlockPos townHallCenter) {
            this.chestPos = chestPos;
            this.isTownhall = isTownhall;
            this.planKey = planKey;
            this.cultureKey = cultureKey;
            this.displayName = displayName;
            this.tags = tags;
            this.location = location;
            this.townHallCenter = townHallCenter;
        }

        @Override
        public String toString() {
            return "BuildingInfo{" +
                    "chestPos=" + chestPos +
                    ", isTownhall=" + isTownhall +
                    ", planKey='" + planKey + '\'' +
                    ", cultureKey='" + cultureKey + '\'' +
                    ", displayName='" + displayName + '\'' +
                    ", tags=" + tags +
                    '}';
        }
    }
    /**
     * Returns the *loaded* MillVillager entities that belong to the given village.
     *
     * @param world        World (server side preferred)
     * @param approxCenter Approx Town Hall center (BlockPos) - can be slightly off
     * @param maxRadius    Radius (blocks) used to "snap" approxCenter to a real TH
     */
    public static List<MillVillager> getLoadedVillagersForVillage(
            World world,
            BlockPos approxCenter,
            int maxRadius
    ) {
        MillWorldData wd = getWD(world);
        if (wd == null) return Collections.emptyList();

        // Snap approxCenter to a real village center:
        VillageInfo vi = findExact(world, approxCenter);
        if (vi == null) vi = findNearest3D(world, approxCenter, maxRadius);
        if (vi == null) vi = findNearest2D(world, approxCenter, maxRadius);

        if (vi == null || !vi.isVillage) {
            return Collections.emptyList(); // not found or it's a lone building
        }

        // Try to resolve the actual Town Hall Building instance:
        Building th = getTownHallBuilding(wd, vi.center);

        // If TH isn't loaded, we can still match by coordinates against villagers' TH.
        // (Loaded villagers usually have a non-null getTownHall(), but be defensive.)
        final int thX = vi.center.getX();
        final int thY = vi.center.getY();
        final int thZ = vi.center.getZ();

        List<MillVillager> out = new ArrayList<>();

        for (MillVillager v : wd.getAllKnownVillagers()) {
            if (v == null) continue;
            if (v.isDead) continue; // 1.12 field

            Building vTh = v.getTownHall();
            if (vTh == null) continue;

            // Best case: identity match (fast + correct when buildings are loaded)
            if (th != null) {
                if (vTh == th) {
                    out.add(v);
                }
                continue;
            }

            // Fallback: compare TH coordinates
            Point p = vTh.getPos();
            if (p != null && p.getiX() == thX && p.getiY() == thY && p.getiZ() == thZ) {
                out.add(v);
            }
        }

        return out;
    }

    /**
     * Convenience: pick a random loaded villager from a given village.
     */
    @Nullable
    public static MillVillager getRandomLoadedVillagerForVillage(
            World world,
            BlockPos approxCenter,
            int maxRadius,
            java.util.Random rand
    ) {
        List<MillVillager> list = getLoadedVillagersForVillage(world, approxCenter, maxRadius);
        if (list.isEmpty()) return null;
        return list.get(rand.nextInt(list.size()));
    }

}
package com.jubitus.ultreia.mill;

import com.jubitus.ultreia.terrain.ProtectedAreas;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.millenaire.common.buildingplan.BuildingPlan;
import org.millenaire.common.utilities.Point;
import org.millenaire.common.village.Building;
import org.millenaire.common.village.BuildingLocation;
import org.millenaire.common.world.MillWorldData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Millénaire buildings from its world data (no block scan): protected footprints and village gates. Only {@link Villages} calls this. */
final class MillBuildings {

    // Soft-penalty radius (blocks) around every village and lone-building centre.
    static final int SETTLEMENT_CENTER_RADIUS = 2;
    // Extra cost of a cell around a settlement centre.
    private static final int CENTER_PENALTY = 15000;
    // Extra cost of a cell inside a building footprint (it is no-paint anyway).
    private static final int FOOTPRINT_PENALTY = CENTER_PENALTY + 5000;
    // Blocks of margin kept around each building's exact footprint.
    private static final int FOOTPRINT_MARGIN = 1;
    // Gate buildings are looked for within this box around the village centre (XZ, Y).
    private static final int GATE_SEARCH_RADIUS_XZ = 120;
    private static final int GATE_SEARCH_RADIUS_Y = 64;
    // A gate whose town hall is farther than this (XZ) from the village centre belongs to another village.
    private static final long GATE_OWN_VILLAGE_RADIUS = 256L;

    private MillBuildings() {}

    /** Soft penalty squares around settlement centres, and every building's exact footprint (plus margin) as no-paint. */
    static void addProtectedZones(World world, ProtectedAreas pa) {
        MillWorldData wd = MillSettlements.wd(world);
        if (wd == null) return;

        if (wd.villagesList != null && wd.villagesList.pos != null) {
            for (Point p : wd.villagesList.pos) {
                if (p == null) continue;
                pa.addPenaltySquare(p.getiX(), p.getiZ(), SETTLEMENT_CENTER_RADIUS, CENTER_PENALTY);
            }
        }
        if (wd.loneBuildingsList != null && wd.loneBuildingsList.pos != null) {
            for (Point p : wd.loneBuildingsList.pos) {
                if (p == null) continue;
                pa.addPenaltySquare(p.getiX(), p.getiZ(), SETTLEMENT_CENTER_RADIUS, CENTER_PENALTY);
            }
        }

        for (Building b : distinctBuildings(wd)) {
            BuildingLocation loc = b.location;
            if (loc == null) continue;
            int[] box = footprint(loc);
            if (box[1] < box[0] || box[3] < box[2]) continue;
            pa.addNoPaintRect(box[0] - FOOTPRINT_MARGIN, box[1] + FOOTPRINT_MARGIN,
                    box[2] - FOOTPRINT_MARGIN, box[3] + FOOTPRINT_MARGIN, FOOTPRINT_PENALTY);
        }
    }

    /**
     * XZ box {minX, maxX, minZ, maxZ} (inclusive) of the blocks the building's plan places. The plan lays
     * deltaX in [0, length) and deltaZ in [0, width) at offset -size/2 through adjustForOrientation;
     * Millénaire's own minx..maxx is off by one on a side that depends on orientation and size parity.
     */
    private static int[] footprint(BuildingLocation loc) {
        if (loc.pos != null) {
            int l = Math.max(1, loc.length), w = Math.max(1, loc.width);
            Point a = BuildingPlan.adjustForOrientation(loc.pos.getiX(), 0, loc.pos.getiZ(), -(l / 2), -(w / 2), loc.orientation);
            Point b = BuildingPlan.adjustForOrientation(loc.pos.getiX(), 0, loc.pos.getiZ(), l - 1 - l / 2, w - 1 - w / 2, loc.orientation);
            if (a != null && b != null) {
                return new int[]{Math.min(a.getiX(), b.getiX()), Math.max(a.getiX(), b.getiX()),
                        Math.min(a.getiZ(), b.getiZ()), Math.max(a.getiZ(), b.getiZ())};
            }
        }
        return new int[]{Math.min(loc.minx, loc.maxx), Math.max(loc.minx, loc.maxx),
                Math.min(loc.minz, loc.maxz), Math.max(loc.minz, loc.maxz)};
    }

    /**
     * Anchors (chest, else building position) of the built gates of the village at or near {@code approxCenter}:
     * wall buildings whose plan key names a gate or gateway, in (x, z, y) order.
     */
    static List<BlockPos> gates(World world, BlockPos approxCenter) {
        MillWorldData wd = MillSettlements.wd(world);
        if (wd == null) return Collections.emptyList();

        BlockPos center = MillSettlements.resolveCenter(world, approxCenter, GATE_SEARCH_RADIUS_XZ);
        if (center == null) return Collections.emptyList();

        List<Building> found = new ArrayList<>();
        for (Building b : distinctBuildings(wd)) {
            Point p = b.getPos();
            if (p == null) continue;
            if (Math.abs(p.getiX() - center.getX()) > GATE_SEARCH_RADIUS_XZ
                    || Math.abs(p.getiZ() - center.getZ()) > GATE_SEARCH_RADIUS_XZ
                    || Math.abs(p.getiY() - center.getY()) > GATE_SEARCH_RADIUS_Y) continue;
            if (!isGate(b.location)) continue;
            if (!belongsTo(b, center)) continue;
            found.add(b);
        }
        found.sort((a, b) -> {
            Point pa = a.getPos(), pb = b.getPos();
            if (pa.getiX() != pb.getiX()) return Integer.compare(pa.getiX(), pb.getiX());
            if (pa.getiZ() != pb.getiZ()) return Integer.compare(pa.getiZ(), pb.getiZ());
            return Integer.compare(pa.getiY(), pb.getiY());
        });

        List<BlockPos> out = new ArrayList<>(found.size());
        for (Building b : found) {
            BuildingLocation loc = b.location;
            Point anchor = (loc != null && loc.chestPos != null) ? loc.chestPos : b.getPos();
            out.add(new BlockPos(anchor.getiX(), anchor.getiY(), anchor.getiZ()));
        }
        return out;
    }

    // Millénaire names wall gates "..._gate" or "..._gateway" (borderpost_gate, full_wall_gateway); slopes and caps are walls.
    private static boolean isGate(BuildingLocation loc) {
        if (loc == null || loc.planKey == null) return false;
        return loc.planKey.toLowerCase(Locale.ROOT).contains("gate");
    }

    private static boolean belongsTo(Building b, BlockPos center) {
        Point th;
        try {
            th = b.getTownHallPos();
        } catch (Throwable t) {
            return true;
        }
        if (th == null) return true;
        long dx = th.getiX() - center.getX();
        long dz = th.getiZ() - center.getZ();
        return dx * dx + dz * dz <= GATE_OWN_VILLAGE_RADIUS * GATE_OWN_VILLAGE_RADIUS;
    }

    private static List<Building> distinctBuildings(MillWorldData wd) {
        List<Building> out = new ArrayList<>();
        Set<Building> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Building b : wd.allBuildings()) {
            if (b != null && seen.add(b)) out.add(b);
        }
        return out;
    }
}
